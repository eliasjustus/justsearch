---
title: "271: Multi-Agent Backend Ownership and Interference Model"
type: tempdoc
status: done
created: 2026-03-10
updated: 2026-03-13
revisited: 2026-03-12
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and tests before promotion.

# 271: Multi-Agent Backend Ownership and Interference Model

## Purpose

This tempdoc is the umbrella work doc for one repo-level question:

How can multiple agents work in this repo without interfering with each other's backend usage?

This is a prevention-first tempdoc. It exists to define:

1. which backend modes are intentionally shared versus safe for concurrent use
2. which resources actually create interference
3. how the repo should prevent, surface, and attribute interference between agents
4. what operator and agent UX should expose about current backend ownership and purpose

## Thesis

Backend interference in JustSearch is mainly an ownership and resource-claim problem.

It is not primarily:

- a generic backend-instability problem
- a "Spotless killed my backend" problem
- a problem that workflow attribution alone can solve

A multi-agent-safe model therefore needs three distinct layers:

1. ownership control
2. interference attribution
3. human coordination/status UX

Tempdoc 272 contributes mainly to layer 2. It does not replace layers 1 or 3.

## Resolved decisions in this rewrite

This rewrite resolves three implementation ambiguities that were still open in earlier versions of
this tempdoc.

### 1. Shared-stack v1 takeover policy is `warn`, not silent `allow`

Current code in `dev-runner` auto-stops the previous active run. That behavior exists today.

The v1 target policy in this tempdoc is:

- later shared-stack starts remain possible
- silent replacement is not acceptable
- the current owner must be surfaced before takeover
- the new start must record explicit takeover intent and outcome

So the v1 policy is `warn`:

- not `block`, because shared full-stack work still needs a practical replacement path
- not unconditional `allow`, because that keeps multi-agent interference invisible

### 2. Canonical ownership state lives in launcher-owned runtime files, not telemetry

The v1 source of truth is:

- shared current-owner lease: `tmp/dev-runner/active.json`
- shared per-run ownership snapshot: `tmp/dev-runner/runs/<runId>/run.json`
- shared stop or takeover effects: `tmp/dev-runner/runs/<runId>/stop-report.json`
- direct per-run ownership and resource claims: `<dataDir>/runtime/backend-run.json`

Derived projections:

- `tmp/eval-lifecycle/runs/<runId>.json` is the cross-engine lifecycle registry and projection layer
- workflow telemetry is the event, attribution, and evidence layer
- MCP tools are the operator-facing read surface

So:

- launcher-owned files are the ownership contract
- lifecycle registry is a normalized projection
- workflow telemetry is not the primary ownership store

### 3. The MCP ownership UX is a typed status tool built on existing state

The v1 MCP surface should extend `justsearch.dev.status` or `justsearch.dev.quick_health`, or add
one more typed tool beside them.

It should not be:

- a free-text-only message board
- a brand-new parallel ownership database
- a summary generated only from workflow telemetry

Human purpose text remains useful, but only as one field inside a structured result.

## Canonical docs and code reviewed first

Canonical docs:

- `CLAUDE.md`
- `docs/explanation/01-system-overview.md`
- `docs/explanation/04-storage-engine.md`
- `docs/explanation/06-configuration-ssot.md`
- `docs/reference/contributing/agent-guide.md`
- `docs/reference/contributing/mcp-dev-tools.md`
- `docs/how-to/use-ui.md`
- `.claude/rules/branch-safety.md`

Current implementation surfaces reviewed:

- `scripts/dev/dev-runner.cjs`
- `scripts/dev/justsearch-dev-mcp/server.mjs`
- `scripts/dev/justsearch-dev-mcp/log.mjs`
- `scripts/lib/bench/backend-launcher.mjs`
- `scripts/lib/bench/eval-backend-lifecycle.mjs`
- `scripts/lib/workflow-telemetry.mjs`
- `scripts/bench/report-workflow-attribution.mjs`
- `scripts/agent-analytics/hooks/export-session-env.mjs`
- `docs/tempdocs/272-workflow-attribution-and-usability-theory.md`

## Current verified facts

### Resource ownership already exists at three levels

- The Head acquires an `AppInstanceLock` for the effective `dataDir`.
- The Worker acquires an `IndexRootLock` for the effective `indexBasePath`.
- On Windows, `WorkerSpawner` assigns the Worker to a `WindowsJobObject` with
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. When the Head process exits — cleanly or via crash — the
  OS automatically kills the Worker. (Added in tempdoc 274, Lock Cleanup lane.)

Operational consequence:

- two app instances should not share one `dataDir`
- two Workers from different data dirs still cannot share one effective `indexBasePath`
- orphan Workers that outlive their Head are no longer possible on Windows — the OS enforces
  Head→Worker lifetime coupling via Job Objects

This means backend coexistence is already constrained by real ownership boundaries, even when those
boundaries are not surfaced clearly to agents. The Job Object enforcement further simplifies the
stale-lease recovery model: if the Head PID is dead, the Worker is guaranteed dead too.

### Hidden shared resources can defeat apparent isolation

- `HeadlessApp` loads persisted `UiSettings` early.
- If persisted settings contain `indexBasePath`, that value can be mirrored into
  `justsearch.index.base_path`.
- `WorkerSpawner` forwards that sysprop to the Worker.

Operational consequence:

- distinct `JUSTSEARCH_DATA_DIR` values are not enough by themselves
- launchers that do not also isolate `JUSTSEARCH_HOME` or force in-memory settings can still share
  one effective `indexBasePath`
- "isolated backend" assumptions can therefore be false in practice

### Current shared-stack metadata is not sufficient for agent ownership

Current `dev-runner` state tracks enough to recover the active run, but not enough to explain
multi-agent intent:

- `tmp/dev-runner/active.json` currently records `runId`, `runPath`, and `updatedAt`
- `tmp/dev-runner/runs/<runId>/run.json` records ports, `dataDir`, spawn data, logs, and PIDs
- neither file currently records:
  - owner session identity
  - source of ownership
  - lease freshness
  - takeover intent
  - takeover policy
  - effective `indexBasePath`
  - cleanup policy
  - purpose summary

Current MCP tools can resolve the active run and basic readiness, but they do not yet expose
structured ownership semantics.

## Backend operating modes

### 1. Shared full-stack mode

Launchers and surfaces:

- `scripts/dev/dev-runner.cjs`
- `justsearch-dev-mcp` tools that resolve the active run
- legacy shared launcher paths that route through the same dev-runner ownership model

Current behavior:

- one active shared run at a time
- later starts can stop the earlier run
- tools that omit `runId` target the latest active run

V1 target policy:

- this mode remains single-owner
- takeovers remain possible
- takeovers must be visible, attributable, and recorded as `warn`-policy ownership transfers
- agents should be able to see the current owner before replacing the stack

Recommended use:

- shared UI or frontend verification
- intentional full-stack work where one shared dev stack is expected

### 2. Isolated backend-only mode

Launchers and surfaces:

- `scripts/lib/bench/backend-launcher.mjs`
- `scripts/lib/bench/eval-backend-lifecycle.mjs --engine direct`

Behavior:

- no global `active.json` single-owner registry
- concurrent backends are allowed when resource claims differ
- these launchers already isolate `JUSTSEARCH_DATA_DIR` and `JUSTSEARCH_HOME`

Required isolation:

- distinct API ports
- distinct `JUSTSEARCH_DATA_DIR`
- distinct `JUSTSEARCH_HOME`
- distinct effective `indexBasePath`
- cleanup scope that does not reap another run's processes

Recommended use:

- parallel backend-only agents
- eval workflows
- local repros that do not need the shared frontend stack

Ownership rule:

- ownership here is about claimed resources, not one global active stack

### 3. Unsafe or manual mode

Launchers and surfaces:

- `scripts/dev/run-headless-api.ps1`
- raw `:modules:ui:runHeadless` use without full isolation overrides
- `modules/ui-web/scripts/dev-all.cjs`
- other ad hoc launch paths that do not isolate `JUSTSEARCH_HOME`, settings, or index root

Behavior:

- weak or absent shared ownership semantics
- higher contamination risk from persisted UI settings
- harder to attribute or explain when interference happens

Policy implication:

- treat this mode as high risk for multi-agent work
- avoid it for normal parallel agent workflows unless every shared resource is explicitly isolated

### Mode preference by task

- Shared UI or frontend verification: use shared full-stack mode
- Parallel backend-only automation or agent workflows: use isolated backend-only mode
- Manual debugging with ad hoc launchers: use only when the operator is intentionally taking on
  higher interference risk

## Structured interference model

The table below is intentionally split into "current evidence now" and "important current gaps" so
the doc does not overstate what the repo can already explain.

| Interference type | Shared resource | Exposed launcher families | Current evidence now | Important current gaps |
|---|---|---|---|---|
| `shared_stack_takeover` | shared dev-runner owner | shared full-stack mode | `tmp/dev-runner/active.json`, `run.json`, dev-runner stderr, `justsearch.dev.status`, `justsearch.dev.quick_health` | no owner identity, no takeover intent, no run-history MCP view |
| `data_dir_collision` | `dataDir` / `AppInstanceLock` | isolated backend-only and unsafe/manual | launcher args, `run.json`, `backend-run.json`, backend stderr, workflow telemetry artifacts | no explicit conflict event, no canonical victim record |
| `index_base_path_collision` | effective `indexBasePath` / `IndexRootLock` | any launcher that reuses settings or index root | worker stderr, persisted UI settings, `justsearch.dev.fetch_api_json` against `effective_config` or `debug_state` while the backend is still running | no launcher-owned persisted record of effective `indexBasePath` |
| `api_port_collision` | API port | isolated backend-only and unsafe/manual | `run.json`, `backend-run.json`, status endpoints, OS port-owner checks, workflow telemetry | no canonical actor or victim schema |
| `cleanup_reap_interference` | cleanup target / reap scope | direct lifecycle launchers and stop/cleanup paths | `stop-report.json`, `cleanup_result` workflow events, port-owner evidence, direct cleanup envelopes, `killOrphanFromLockMetadata` in lifecycle-cleanup (274) | largely mitigated by WindowsJobObject (274) — orphan Workers are killed by the OS when Head exits. Retain event type for defense-in-depth and non-Windows platforms |
| `settings_contamination` | shared `JUSTSEARCH_HOME` or persisted UI settings | unsafe/manual mode, partially isolated helpers | settings files, `effective_config`, `debug_state`, worker stderr | no ownership record ties settings source to current launcher |
| `manual_or_unattributed_interference` | missing ownership or session evidence | manual shell use, CI, ad hoc scripts | `session_id: null`, attribution report, logs only | confidence remains low until explicit ownership metadata exists |

Interpretation rule:

- interference is always defined in terms of a contested resource or an intentional takeover
- a process exit by itself is not a sufficient diagnosis

## Tempdoc 272 as a supporting layer

Tempdoc 272 is still active and remains useful, but its role is narrower than this tempdoc's role.

### What 272 contributes

- `session_id` and `workflowRunId` joins for workflow runs
- `report-workflow-attribution.mjs` classification of `attributed`,
  `orphaned_session_ref`, and `unattributed`
- a clearer distinction between workflow telemetry, session analytics, traces, and eval identity
- a Windows-safe fallback via `tmp/agent-telemetry/current-session-id`

### What 272 does not contribute

- shared-backend ownership control
- takeover policy
- resource-claim semantics
- operator-facing backend ownership summaries

### Separation of concerns

- Ownership control: launcher state and explicit backend ownership metadata
- Interference attribution: workflow telemetry plus session joins from 272
- Human coordination/status UX: optional summaries and MCP-visible ownership state

### Evidence strength

For this tempdoc's purposes, evidence strength should be treated in this order:

1. explicit launcher-owned ownership metadata
2. explicit `--session-id` or env-based session attribution
3. file-based fallback attribution from `current-session-id`
4. manual inference from logs or timestamps

The Windows file fallback is acceptable for attribution, but it is weaker than explicit ownership
metadata because it is still a repo-local fallback rather than a backend ownership contract.

## Current biggest implementation uncertainties

The remaining implementation uncertainty is no longer whether the repo can collect backend-related
evidence. The real uncertainty is whether the ownership model will be precise enough to prevent
interference and trustworthy enough to explain it afterward.

### Remaining work after this rewrite

The protocol uncertainties that motivated this tempdoc are closed by the 2026-03-11 addendum and
the final uncertainty-closure pass below.

What remains is implementation work:

- shared lease admission needs to be implemented in `dev-runner.cjs` with the documented sidecar
  `wx` lockfile flow
- launcher-owned records need to persist the documented ownership and claim fields
- MCP and CLI surfaces need to project the finalized ownership, confidence, and takeover semantics
- the eval wrapper needs to retain per-run registry snapshots on stop instead of deleting them
- the first implementation pass should be characterization-test-first at the three main seams:
  - shared lease admission in `dev-runner.cjs`
  - MCP projection in `justsearch-dev-mcp`
  - retained run history in `eval-backend-lifecycle.mjs`

## External research pass (2026-03-11)

This pass focused on external prior art that could reduce design uncertainty for the multi-agent
backend ownership model.

Reviewed sources:

- Kubernetes Leases:
  <https://kubernetes.io/docs/concepts/architecture/leases/>
- OpenTelemetry session semantic conventions:
  <https://opentelemetry.io/docs/specs/semconv/general/session/>
- OpenTelemetry MCP semantic conventions:
  <https://opentelemetry.io/docs/specs/semconv/gen-ai/mcp/>
- Anthropic Claude Code hooks reference:
  <https://code.claude.com/docs/en/hooks>
- MCP Tools spec:
  <https://modelcontextprotocol.io/specification/2025-06-18/server/tools>
- MCP Resources spec:
  <https://modelcontextprotocol.io/specification/2025-06-18/server/resources>
- MCP roadmap:
  <https://modelcontextprotocol.io/development/roadmap>

### F1. Shared backend ownership should look like a lease, not a bare pointer

The most useful external prior art is Kubernetes Lease semantics.

The important pattern is not simply "who is active?", but:

- holder identity
- lease duration
- renewal timestamp
- stale-owner detection

Implication for this tempdoc:

- `active.json` should evolve toward a lease-like ownership record
- shared-stack status should answer not just "who owns it?" but also "is that ownership fresh?"
- takeover behavior should be defined against lease state, not inferred from process survival alone

### F2. Session identity should remain distinct from ownership identity

OpenTelemetry session conventions and Anthropic's hook model both reinforce the same point:

- session identity is real and useful
- session identity is not the same thing as resource ownership

Implication for this tempdoc:

- keep using `session_id` as a contextual join key
- do not define shared backend ownership in terms of `session_id` alone
- ownership identity should include backend run identity and source, with session identity as a
  supporting field when available

### F3. The MCP summary/status surface should be a typed tool first

MCP Tools are the better fit for a model-facing ownership summary because they support structured
input/output contracts. MCP Resources are a better fit later if the repo wants browsable or
subscribable state.

Implication for this tempdoc:

- the first ownership summary surface should be a typed MCP tool result
- a resource-based view can be added later if live browsing or subscriptions become useful
- human purpose text should remain one field inside a structured summary, not the primary result

### F4. No external standard currently covers backend ownership control

The MCP roadmap still treats observability and audit trails as open platform concerns, and OTel MCP
conventions cover tracing or session context rather than ownership or lease control.

Implication for this tempdoc:

- a repo-local ownership record and repo-local interference event schema are justified
- the repo does not need to wait for a standard to define backend ownership metadata
- external standards should be used mainly for session and trace compatibility, not for the control
  plane itself

## Reusable current infrastructure

The current codebase already contains several strong primitives that should be extended rather than
replaced.

### 1. Shared full-stack state already exists in `dev-runner`

Current reusable files:

- `tmp/dev-runner/active.json`
- `tmp/dev-runner/runs/<runId>/run.json`
- `tmp/dev-runner/runs/<runId>/stop-report.json`

What these already provide:

- active run pointer
- stable `runId`
- API and UI ports
- `dataDir`
- process IDs
- spawn metadata
- log paths
- stop verification including:
  - `portsClosed`
  - killed PIDs
  - Windows port-owner command-line evidence

V1 implication:

- `active.json` is the canonical shared lease record
- `run.json` is the per-run ownership and resource-claim snapshot
- `stop-report.json` records takeover and cleanup effects

### 2. Direct backend launchers already persist per-run state

Current reusable files:

- `<dataDir>/runtime/backend-run.json`
- `<dataDir>/runtime/backend.pid`
- `<dataDir>/runtime/api-port.txt`

What these already provide:

- direct-run identity
- engine and profile information
- `dataDir`
- PID and API port discovery
- stdout and stderr log locations
- cleanup summary

What the direct launcher also already detects:

- stale runtime artifacts
- lingering data-dir-rooted processes
- port-owner process IDs on stop
- whether another process had to be reaped to release a port

V1 implication:

- `backend-run.json` is the canonical direct ownership and claim record
- the missing step is to enrich that file with resource claims and owner metadata, not to invent a
  second direct-run registry

### 3. `eval-backend-lifecycle` already normalizes shared and direct engines

Current reusable file:

- `tmp/eval-lifecycle/runs/<runId>.json`

What this wrapper already provides:

- canonical run IDs across direct and legacy engines
- `engineRunId` bridging to underlying launcher-specific IDs
- normalized readiness and phase envelopes
- normalized cleanup data
- status snapshots from `/api/status`
- workflow telemetry emission
- `session_id` propagation and workflow attribution

V1 implication:

- this file should remain a normalized lifecycle projection
- it should mirror ownership and claim fields from launcher-owned records
- it should not become the primary ownership contract

### 4. Workflow telemetry already has an event and metadata plane

Current reusable capabilities in `scripts/lib/workflow-telemetry.mjs`:

- mutable `meta.json` with mergeable `artifacts`, `tags`, and `stats`
- append-only event stream via `appendEvent(...)`
- lifecycle-specific event projection via `emitLifecycleEnvelopeEvents(...)`
- existing cleanup and stale-artifact event types
- agent-session joins via `session_id`

V1 implication:

- interference reporting can likely reuse workflow events instead of requiring a separate first-pass
  event pipeline
- ownership confidence, contested-resource tags, and interference dispositions can be attached to
  workflow runs as derived evidence
- workflow telemetry remains secondary evidence, not the ownership source of truth

### 5. MCP already has structured, typed status tools

Current reusable MCP surfaces:

- `justsearch.dev.status`
- `justsearch.dev.quick_health`
- `justsearch.dev.preflight`
- `justsearch.dev.tail_log`
- `justsearch.dev.fetch_api_json`
- evidence capture and validation tools

Current reusable characteristics:

- structured tool outputs via MCP `structuredContent`
- existing `run.json` enrichment inside `justsearch.dev.status`
- quick shared-stack orientation without spawning subprocesses
- access to `effective_config` and `debug_state` endpoints
- existing log and evidence surfaces for root-cause analysis

Current limitation:

- there is no current MCP inventory or ownership-history tool in the maintained server surface

V1 implication:

- the future ownership summary does not need a free-text-only tool
- the best path is to extend `status` or `quick_health`, or add one more typed MCP tool beside them

### 6. Agent/session enrichment utilities already exist

Current reusable surfaces:

- `tmp/agent-telemetry/events.ndjson`
- `scripts/agent-analytics/lib/telemetry-io.mjs`
- `scripts/bench/report-workflow-attribution.mjs`
- `scripts/agent-analytics/hooks/export-session-env.mjs`

What they already provide:

- raw `session_start` events
- session metadata such as transcript path and model
- workflow-run-to-session joins when `session_id` is present
- a Windows-safe file fallback for session attribution

V1 implication:

- owner-session enrichment can reuse the agent-analytics pipeline
- no new session metadata store is required for v1
- this remains enrichment, not the primary ownership contract

## V1 implementation direction

This tempdoc should drive a concrete first implementation for multi-agent backend safety.

### 1. Shared ownership control

Implement shared ownership in `dev-runner` state:

- convert `tmp/dev-runner/active.json` from a bare pointer into a lease record
- mirror owner and claim snapshots into `tmp/dev-runner/runs/<runId>/run.json`
- record takeover or cleanup effects in `stop-report.json`

### 2. Direct resource claims

Implement isolated ownership in `backend-run.json`:

- API port
- `dataDir`
- `JUSTSEARCH_HOME`
- effective `indexBasePath`
- cleanup policy or claim scope
- owner identity when known

### 3. Cross-engine projection

Extend `tmp/eval-lifecycle/runs/<runId>.json` to mirror selected ownership fields so one normalized
registry can support reports and comparisons without displacing the launcher-owned sources of truth.

### 4. Interference reporting

When interference occurs, the repo should emit a structured event that identifies:

- actor when known
- victim when known
- contested resource
- evidence source
- whether the outcome was prevented, warned takeover, startup abort, degraded run, or unresolved

### 5. MCP-visible ownership summary

The MCP layer should expose a backend ownership summary that answers:

- who currently owns the backend
- which mode it is in
- which resources it claims
- whether the shared lease is fresh or stale
- whether attribution is strong or weak
- optional short purpose summary

### 6. Human summary overlay

Human-written summary text is useful, but only as an overlay:

- optional in v1
- useful for agent coordination and handoff
- never sufficient by itself to determine ownership or root cause

## Draft interface shapes

These are draft shapes for later implementation. They are included here so the work does not
remain vague.

### Shared active lease record (`tmp/dev-runner/active.json`)

```json
{
  "kind": "backend-shared-lease.v1",
  "schemaVersion": 1,
  "backendRunId": "run-20260311-120000",
  "runPath": "tmp/dev-runner/runs/run-20260311-120000/run.json",
  "launcherFamily": "dev-runner",
  "mode": "shared",
  "holder": {
    "source": "claude|manual|ci|unknown",
    "agentSessionId": "optional-session-id",
    "workflowRunId": "optional-workflow-run-id"
  },
  "takeoverPolicy": "warn",
  "lease": {
    "durationSec": 30,
    "renewedAt": "2026-03-11T12:05:00.000Z",
    "expiresAt": "2026-03-11T12:05:30.000Z",
    "stale": false
  },
  "purposeSummary": "optional short human summary",
  "updatedAt": "2026-03-11T12:05:00.000Z"
}
```

### Backend ownership record (`run.json` or `backend-run.json`)

```json
{
  "kind": "backend-ownership.v1",
  "schemaVersion": 1,
  "launcherFamily": "dev-runner|legacy.lifecycle|direct.lifecycle|manual",
  "mode": "shared|isolated|unsafe",
  "backendRunId": "uuid-or-run-id",
  "workflowRunId": "optional-workflow-run-id",
  "owner": {
    "source": "claude|manual|ci|unknown",
    "agentSessionId": "optional-session-id",
    "confidence": "high|medium|low"
  },
  "resourceClaims": {
    "apiPort": 33221,
    "dataDir": "tmp/dev-runner/runs/...",
    "justsearchHome": "tmp/dev-runner/runs/...",
    "effectiveIndexBasePath": "D:/.../index/default"
  },
  "cleanupPolicy": "shared-stack|same-data-dir|unknown",
  "takeoverPolicy": "warn|forbid|not-applicable",
  "sharedLeaseRef": "optional path or run id",
  "purposeSummary": "optional short human summary",
  "startedAt": "2026-03-11T12:00:00.000Z",
  "updatedAt": "2026-03-11T12:05:00.000Z"
}
```

### Interference event record

```json
{
  "kind": "backend-interference-event.v1",
  "schemaVersion": 1,
  "eventType": "shared_stack_takeover|data_dir_collision|index_base_path_collision|api_port_collision|cleanup_reap_interference|settings_contamination|manual_or_unattributed_interference",
  "actor": {
    "backendRunId": "optional-actor-run-id",
    "workflowRunId": "optional-actor-workflow-run-id",
    "agentSessionId": "optional-actor-session-id"
  },
  "victim": {
    "backendRunId": "optional-victim-run-id",
    "workflowRunId": "optional-victim-workflow-run-id",
    "agentSessionId": "optional-victim-session-id"
  },
  "resource": {
    "type": "shared-dev-stack|dataDir|indexBasePath|apiPort|cleanupTarget|settingsStore",
    "value": "resource identifier"
  },
  "evidence": {
    "source": "dev-runner|backend-run|eval-lifecycle|workflow-telemetry|mcp|logs|manual",
    "paths": [
      "optional evidence paths"
    ]
  },
  "severity": "info|warn|error",
  "disposition": "prevented|warned_takeover|startup_aborted|degraded|unresolved",
  "confidence": "high|medium|low",
  "ts": "2026-03-11T12:05:30.000Z"
}
```

### MCP status summary

```json
{
  "kind": "backend-ownership-summary.v1",
  "schemaVersion": 1,
  "running": true,
  "mode": "shared|isolated|unsafe",
  "owner": {
    "backendRunId": "optional-run-id",
    "workflowRunId": "optional-workflow-run-id",
    "agentSessionId": "optional-session-id",
    "confidence": "high|medium|low"
  },
  "purposeSummary": "optional short human summary",
  "resourceClaims": {
    "apiPort": 33221,
    "dataDir": "tmp/...",
    "justsearchHome": "tmp/...",
    "effectiveIndexBasePath": "D:/.../index/default"
  },
  "sharedLease": {
    "fresh": true,
    "renewedAt": "2026-03-11T12:05:00.000Z",
    "expiresAt": "2026-03-11T12:05:30.000Z"
  },
  "riskMarkers": [
    "shared_stack",
    "weak_attribution",
    "cleanup_scope_unclear"
  ]
}
```

## Scenarios and acceptance criteria

The future implementation should be considered correct only if it supports all of these scenarios:

1. Two agents using MCP or shared dev tools can see who currently owns the shared backend before a
   takeover happens.
2. A later shared-stack start is described as an intentional warned transfer, not an unexplained
   crash.
3. Two agents using direct backend launchers can run concurrently when their resource claims differ.
4. A collision on `indexBasePath` is explainable as a specific resource conflict rather than generic
   backend failure.
5. A cleanup or reaping action can be tied back to the actor or launcher that triggered it.
6. A run with weak or missing attribution is clearly marked as lower-confidence evidence.
7. A missing or stale human summary does not prevent diagnosis of ownership or interference.

## Historical case study: why this doc exists

The original investigation started from a misleading question: whether `spotlessApply` could make a
running backend exit unexpectedly.

What the repro actually showed:

- `spotlessApply` did not directly terminate the running backend
- the real failure mode was startup self-abort caused by a shared `indexBasePath`
- that shared index root came from persisted UI settings, not from the formatting task

This remains useful as a case study because it illustrates the main thesis of this tempdoc:

- without an ownership and resource-claim model, agents will misdiagnose backend interference as
  random instability

## Staged implementation plan

Implementation is staged to frontload highest-value, lowest-risk changes. Each stage produces a
working, testable increment.

Default takeover policy is `deny` for all callers (CLI and MCP). CI scripts that need unconditional
starts must pass `--takeover warn`. Stale leases auto-reclaim without requiring explicit takeover.

### Stage 1: Foundation (low risk) — COMPLETE

Goal: eval history retention + lease-shaped ownership fields + renewal loop. No control-flow changes
to startup.

Files: `scripts/lib/bench/eval-backend-lifecycle.mjs`, `scripts/lib/bench/test-eval-backend-lifecycle.mjs`,
`scripts/dev/dev-runner.cjs`, `scripts/dev/justsearch-dev-mcp/schemas.mjs`

- [x] 1a. Eval history retention: replace `removeRegistry(runId)` on stop with `writeRegistry` that
  merges stop envelope into existing registry doc. Retained file becomes immutable-per-run
  projection. Test: verify registry file exists after stop with `stoppedAt` set.
- [x] 1b. MCP schema: add `takeover: z.enum(['deny', 'warn', 'force']).optional()` to
  `StartInputSchema` before `.strict()`.
- [x] 1c. Ownership fields in `active.json` and `run.json`: extend `active.json` from bare pointer
  to lease record with `kind`, `launcherFamily`, `mode`, `holder`, `takeoverPolicy`, `lease`
  fields. Extend `run.json` with `owner`, `resourceClaims`, `cleanupPolicy`. Session ID resolution:
  `JUSTSEARCH_AGENT_SESSION_ID` env var, then file fallback at
  `tmp/agent-telemetry/current-session-id`.
- [x] 1d. Lease renewal in supervisor loop: add 10s `setInterval` that rewrites `active.json` with
  fresh `renewedAt`/`expiresAt` and incremented `sequence`. Only renew if `active.json` still
  points to this run. Clear interval on shutdown.

Commits:
1. `b441cb49` `feat(eval): retain lifecycle registry on stop for post-run diagnosis (271)`
2. `2b8e4b71` `feat(lifecycle): add lease-shaped ownership fields and renewal to dev-runner (271)`

Verified: both lifecycle test suites pass. Live verification confirmed `active.json` lease fields,
`run.json` ownership fields, and renewal loop (sequence advancing every 10s).

### Stage 2: MCP ownership projection (medium risk) — COMPLETE

Goal: surface ownership in `quick_health` and `status`; wire `takeover` through start handler to CLI.

Files: `scripts/dev/justsearch-dev-mcp/server.mjs`, `scripts/dev/justsearch-dev-mcp/cli.mjs`,
`scripts/dev/dev-runner.cjs`

- [x] 2a. Ownership projection in `quick_health`: extract lease fields from `active.json`, add
  `ownership` block with `holder`, `takeoverPolicy`, `lease`, `leaseFresh`, `mode`,
  `launcherFamily`. Output schema uses `.passthrough()` — no schema change needed.
- [x] 2b. Ownership projection in `status`: inject `ownership`, `resourceClaims`, `owner` from
  `active.json` and `run.json` into status result.
- [x] 2c. Takeover passthrough: read `takeover` from input, pass to CLI args via
  `buildDevRunnerArgsStart`. Add `--takeover` to `parseArgs` in dev-runner (stored, not yet acted
  on — Stage 3 adds the admission logic).
- [x] 2d. CLI args: add `takeover` to `buildDevRunnerArgsStart` in `cli.mjs`.

Note: 2c was split from the original plan. The `OWNER_CONFLICT` structured response from MCP
requires Stage 3's sidecar lockfile logic to produce the conflict — so that part of 2c is deferred
to Stage 3. The passthrough wiring is complete.

Commit:
3. `b9f6c605` `feat(mcp): ownership projection and takeover passthrough (271)`

Verified: both lifecycle test suites pass. Logic tests confirm ownership projection (fresh/expired
lease detection), CLI builder (`--takeover` flag emitted/omitted correctly), and `parseArgs`
acceptance of `--takeover`.

### Stage 3: Sidecar lockfile + takeover contract (higher risk) — COMPLETE

Goal: replace "auto-stop previous run" with lease-aware admission. Core behavioral change.

Files: `scripts/dev/dev-runner.cjs`, `scripts/lib/bench/test-dev-runner-lifecycle.mjs`,
`scripts/lib/bench/test-lifecycle-fixtures.mjs`

- [x] 3a. Characterization tests (6 cases: no active run, fresh lease+deny, fresh lease+warn,
  stale lease+deny, stale lock dead PID, live lock conflict).
- [x] 3b. Sidecar `wx` lockfile admission via `acquireAdmission()` extracted function:
  - `fs.writeFile(lockPath, payload, { flag: 'wx' })` for exclusive creation
  - if `EEXIST`: read lock, check holder PID alive → conflict if live, remove + retry if dead
  - once lock held: read `active.json`, check lease freshness + PID liveness
  - apply takeover policy (`deny`, `warn`, `force`)
  - release lock in `finally` block (always)
- [x] 3c. Add `--takeover` to `parseArgs` with `deny|warn|force` validation. (Done in Stage 2.)
- [x] 3d. Helpers: `isPidAlive(pid)` extracted to module scope. `OWNER_CONFLICT` structured
  response emitted inline in `cmdStart`.
- [x] 3e. MCP OWNER_CONFLICT short-circuit: `server.mjs` detects `error.code === 'OWNER_CONFLICT'`
  before readiness wait and returns structured conflict to caller.
- [x] 3f. `__test` exports: `acquireAdmission`, `isPidAlive`, `lockPath`, `activePath`.

Breaking change: `deny` default blocks starts when a fresh owner exists. CI callers
(`dev-runner-lifecycle.mjs`, DAG runners) that expect unconditional starts must pass
`--takeover warn`.

Commits:
4. `f2e756d2` `feat(lifecycle): sidecar lockfile admission and takeover contract (271)`
5. `014a6f89` `test: characterize ownership admission seams (271)`

Verified: all 6 admission characterization tests pass. Tests use sacrificial spawned processes
as owner PIDs and save/restore `active.json`/`active.lock.json` state. Guard skips tests if
dev stack is running. Not live-tested end-to-end (MCP server runs from main checkout, not
worktree).

### Stage 4: indexBasePath capture + stop report enrichment (medium risk) — COMPLETE

Goal: two-stage `indexBasePath` resolution and enriched stop-report with takeover disposition.

Files: `scripts/dev/dev-runner.cjs`

- [x] 4a. Pre-spawn `indexBasePath` resolution: `resolveExpectedIndexBasePath(dataDir)` reads
  `<dataDir>/ui/settings.json` for `indexBasePath` (camelCase), falls back to
  `<dataDir>/index/default`. Returns `{ path, evidence }`. Added `expectedIndexBasePath` and
  `expectedIndexBasePathEvidence` to `runJson.resourceClaims`.
- [x] 4b. Post-start `indexBasePath` confirmation: `fetchConfirmedIndexBasePath(apiPort)` fetches
  `/api/debug/effective-config` for `keys[].key === "justsearch.index.base_path"`, falls back to
  `/api/status` `indexBasePath`. Added `confirmedIndexBasePath`, `confirmedIndexBasePathEvidence`,
  `confirmedIndexBasePathSource` to `runJson.resourceClaims`. No two-stage write: readiness is
  confirmed before runJson construction, so confirmed value included in initial write.
- [x] 4c. Stop-report enrichment: `stopRun` accepts optional `opts.disposition`, `opts.actor`,
  `opts.victim`. Stop-report bumped to `schemaVersion: 2`. All 4 call sites pass disposition:
  `acquireAdmission` (stale_reclaim, warn/force), `cmdCleanup` (normal_stop), `cmdStop`
  (normal_stop). New helper `fetchJsonHttp` matches existing `http.request` style.

Commit:
6. `481a4b76` `feat(lifecycle): indexBasePath capture and stop-report enrichment (271)`

Verified: both lifecycle test suites pass (`test-dev-runner-lifecycle.mjs`,
`test-eval-backend-lifecycle.mjs`). Live-tested end-to-end 2026-03-12 (see below).

### Stage 4b: Quick wins from deferred items investigation

Post-stage-4 investigation resolved two uncertainties from the deferred list:

- [x] `workerConfigSnapshotPath` resource claim: file is `<dataDir>/runtime/worker-config-snapshot.json`,
  written by `HeadlessApp.java` (not Worker). Path deterministic from `dataDir`. One-line addition
  to `resourceClaims`.
- [x] Confidence model derivation: replace hardcoded `'high'` with computed value based on
  `agentSessionId` resolution method (env var → high, file fallback → medium, null → low) and
  `confirmedIndexBasePath` presence.

### Stage 5: Hardening from end-to-end verification (2026-03-12)

Post-merge live testing revealed two indirect effects. Both fixed in this stage.

Files: `scripts/dev/dev-runner.cjs`

- [x] 5a. Stale port file: with `--clean=none`, `<dataDir>/runtime/api-port.txt` from a
  previous run persists. If the previous backend bound an ephemeral port (e.g. 58723) and
  the new backend binds a different one (e.g. 62654), dev-runner reads the stale file and
  polls the wrong port, causing a readiness timeout. Fix: delete `api-port.txt` before
  spawning the backend.
- [x] 5b. Lockfile PID-reuse deadlock: if the process crashes while holding `active.lock.json`
  and the PID is later recycled to an unrelated process, `isPidAlive()` returns true and
  admission reports `lock_held` indefinitely. Fix: add age-based stale detection — locks
  older than 2 minutes are treated as stale regardless of PID liveness (the lock is normally
  held for <100ms during admission).

### Stage 6: Agent guidance and documentation (2026-03-12)

Post-verification analysis identified that the ownership system is fully implemented but agents
have no instructions for how to interact with it. The following gaps were found:

- `CLAUDE.md`, `mcp-dev-tools.md`, and `.claude/rules/` contain zero guidance about ownership,
  `OWNER_CONFLICT`, or takeover decisions. An agent encountering `OWNER_CONFLICT` has no
  documented path forward.
- The MCP `justsearch_dev_start` tool description does not mention the takeover parameter or
  what `OWNER_CONFLICT` means.
- No guidance tells agents to check `quick_health` before starting to see who owns the stack.
- `branch-safety.md` says "coordinate via user direction" but doesn't mention the takeover
  mechanism.

These are documentation-only changes:

- [x] 6a. Update `docs/reference/contributing/mcp-dev-tools.md` with ownership-aware workflows:
  new "Backend Ownership" section (checking, conflicts, stop-report) + troubleshooting entry
  for `OWNER_CONFLICT`.
- [x] 6b. Update `.claude/rules/branch-safety.md` "Shared Dev Stack" section with ownership
  protocol: check `quick_health` before start, `OWNER_CONFLICT` recovery, takeover rules.
- [x] 6c. Update MCP server instructions to list `OWNER_CONFLICT` in common errors, and
  `justsearch_dev_start` tool description to mention takeover and ownership conflict.

### Critical gap: stop/clean have no ownership gate (2026-03-12)

During live verification, the agent implementing the ownership system (this session) repeatedly
started, stopped, and cleaned the dev stack while another agent was actively using it. The
ownership system prevented unauthorized starts (OWNER_CONFLICT works) but did nothing to prevent
unauthorized stops or cleans. The result was mutual interference — exactly what the tempdoc was
designed to prevent.

Key observations:

1. Agent guidance (Stage 6 docs) is not a reliable control. The implementing agent ignored its
   own guidance and stopped another agent's stack without checking ownership. Rules that depend
   on agent compliance will be violated.
2. The ownership gate only exists on `start`. The `stop`, `cleanup`, and `--clean` operations
   are unprotected — any agent can destroy any other agent's running backend.
3. The MCP server is shared across all agent sessions. There is no session scoping — all tools
   operate on the same global state regardless of caller identity.
4. `--clean=soft` is silently destructive to another agent's runtime state (port file, worker
   config snapshot, API port mapping).

### Stage 7: Cross-worktree ownership and session-scoped access control (complete)

The structural fix is to enforce ownership at the tool level, not via agent guidance. Deep
research (see findings below) revealed that the primary blocker is not session identity but
**cross-worktree state isolation**: each worktree has its own `active.json`, so ownership
claims are invisible across worktrees.

Design direction: **shared ownership state + automatic session identity + session-scoped gates.**

Implementation:

- [x] 7a. **Cross-worktree shared state**: Added `resolveMainRepoRoot()` to both
  `dev-runner.cjs` and `paths.mjs`. Parses `.git` file in worktrees to find main repo.
  `stateRoot`/`runsRoot`/`activePath` now resolve under the main repo. All MCP server
  callsites updated to use `mainRepoRoot` for shared state reads. Fixed `acquireAdmission`
  to resolve `runPath` relative to `mainRepoRoot` (not `repoRoot`).
- [x] 7b. **Session identity via PreToolUse hook** (required for same-cwd protection):
  File-based session ID (`current-session-id`) is a shared singleton per `repoRoot` — when
  two agents share the same CWD (both on main, or MCP servers resolve to the same root),
  the last session to start overwrites the file and both agents read the same ID. This was
  confirmed in live testing: agent A started the stack, agent B called `quick_health` and
  saw `callerIsOwner: true` (wrong), then successfully stopped agent A's stack (no gate).
  **Cross-worktree protection works** (different worktrees → different files), but
  **same-root protection requires true per-session identity.** The fix is a PreToolUse hook
  that injects the real `session_id` from the Claude Code hook JSON into MCP tool params
  via `updatedInput`. This is the only reliable source of per-session identity.

  **Implemented (2026-03-12):** Full pipeline from hook → MCP server → dev-runner CLI:
  1. `mcp-session-inject.mjs` PreToolUse hook (matcher: `mcp__justsearch-dev__.*`) reads
     `session_id` from hook stdin JSON, injects it as `sessionId` into `updatedInput`
  2. All 14 MCP input schemas (`schemas.mjs`) accept optional `sessionId` field
  3. MCP server (`server.mjs`) passes `sessionId` to CLI arg builders for `start`/`stop`,
     and prefers `input.sessionId` over file fallback for `status`/`quick_health` ownership
  4. CLI arg builders (`cli.mjs`) emit `--session-id=<value>` when present
  5. `dev-runner.cjs` parses `--session-id`, passes to `resolveAgentSessionId(cliSessionId)`
     which checks CLI arg first → env var → file fallback
  6. All 6 callsites of `resolveAgentSessionId()` updated to thread CLI session ID;
     `acquireAdmission()` accepts `{ takeover, sessionId }`
  7. Verified live: `quick_health` returns `callerIsOwner: true` with hook-injected session ID
- [x] 7c. **Session-scoped stop gate**: `cmdStop()` checks caller's `agentSessionId` against
  `active.json` owner. Mismatch → `OWNER_CONFLICT`. `--force` bypasses the gate. MCP stop
  handler detects `OWNER_CONFLICT` and returns structured error with `actionRequired`.
- [x] 7d. **Session-scoped clean gate**: `cmdStart()` rejects `--clean=soft/hard` when run
  is owned by a different session. Same `OWNER_CONFLICT` pattern as stop.
- [x] 7e. **Auto-scoped MCP context**: `quick_health` and `status` ownership projections
  include `callerIsOwner` boolean. Added `resolveAgentSessionIdForMcp()` to `paths.mjs`
  (reads worktree-local session ID) and `callerIsOwner` to `OwnershipProjectionSchema`.

#### Research findings: session identity and cross-worktree isolation (2026-03-12)

**Problem statement (revised):** The original research focused on session identity — how the
MCP server identifies which agent is calling it. Deeper investigation revealed a more
fundamental problem: the ownership system itself is per-worktree, providing zero cross-worktree
protection.

##### Verified architecture facts

Each worktree session spawns its own MCP server process (stdio = 1:1 pipe, confirmed via
anthropics/claude-code #11778). The MCP server's `cwd` is the worktree root for worktree
sessions (claude-code #17565: cwd field in `.mcp.json` is ignored). MCP servers inherit the
full parent `process.env` — the bugs cited in `.mcp.json` `env` blocks (#1254, #28332) are
about explicit env blocks being treated as replacements instead of merges, not about env
stripping.

##### Finding 1: `CLAUDE_SESSION_ID` is NOT a native env var

Multiple open feature requests (anthropics/claude-code #13733, #17188, #25642, #29318), none
merged as of March 2026. Only `CLAUDECODE=1` and `CLAUDE_CODE_ENTRYPOINT=cli` are natively
set.

##### Finding 2: MCP protocol has NO caller identity for stdio transport

The MCP spec defines `Mcp-Session-Id` as a server-assigned header for session continuity, not
caller identity. OAuth 2.1 authorization exists for HTTP transport only. No per-request caller
identity field exists in tool call messages. Relevant spec discussions:
modelcontextprotocol/modelcontextprotocol #1087, #193, #596.

##### Finding 3: Session identity pipeline works correctly per-worktree

**Corrected from initial research** (which incorrectly claimed MCP servers get minimal env and
the file-based fallback is a "global singleton that breaks under concurrent agents").

Actual pipeline:
1. `export-session-env.mjs` (SessionStart hook) writes session ID to **two paths**:
   `<repoRoot>/tmp/agent-telemetry/current-session-id` (from script's `__dirname`) AND
   `<process.cwd()>/tmp/agent-telemetry/current-session-id` (from hook execution context).
2. For worktree sessions, `process.cwd()` = worktree root, creating a per-worktree file.
3. The MCP server's `resolveRepoRoot()` uses `process.cwd()` (= worktree root).
4. `resolveAgentSessionId()` reads from `<repoRoot>/tmp/agent-telemetry/current-session-id`
   where `repoRoot` = worktree root (from MCP server's `cwd`).

The main repo's copy IS a global singleton (last agent to start overwrites it). But the
per-worktree copy (from `process.cwd()`) is correct and unique per agent. The MCP server
reads the per-worktree copy because its `resolveRepoRoot()` resolves to the worktree root.

##### Finding 4: `CLAUDE_ENV_FILE` does NOT reach MCP servers

`CLAUDE_ENV_FILE` is sourced before each Bash tool call only. It does not affect MCP server
processes or their child processes. This finding is unchanged from initial research.

##### Finding 5: PreToolUse hooks CAN modify MCP tool parameters

**Corrected from initial research** (which incorrectly claimed "hooks cannot modify MCP tool
call parameters — they can only add context text").

PreToolUse hooks can return `updatedInput` in `hookSpecificOutput` to modify tool parameters
before execution. This is already used in this repo: `intervene.mjs` injects `limit: 200`
into Read tool calls for large files. The mechanism works for MCP tools (tool names like
`mcp__servername__toolname`) — they go through the same hook pipeline.

Known bugs: `updatedInput` was previously ignored when combined with
`permissionDecision: "allow"` (anthropics/claude-code #15897). A fix was applied for the
`"ask"` case. The `"allow"` case may still be affected.

##### Finding 6: Cross-worktree ownership isolation gap (CRITICAL)

**This is the most important finding, missed entirely in the initial research.**

`dev-runner.cjs` line 21: `const repoRoot = path.resolve(__dirname, '..', '..');`
`stateRoot` (line 26): `path.resolve(repoRoot, 'tmp', 'dev-runner')`

Since `__dirname` resolves to the worktree's file system path, all ownership state is
per-worktree:

- `tmp/dev-runner/active.json` — per-worktree (verified empirically: main repo has 575-byte
  file with real lease data; worktree has separate 350-byte file with test stub data)
- `tmp/dev-runner/runs/<runId>/run.json` — per-worktree
- `tmp/dev-runner/runs/<runId>/stop-report.json` — per-worktree
- Default `dataDir` (`modules/ui-web/.dev-data`) — also per-worktree

**Consequence:** Worktree A's `OWNER_CONFLICT` mechanism is completely invisible to worktree B.
Each worktree sees its own empty `active.json` and believes no owner exists. The only
cross-worktree protection is OS-level port binding failure, which produces a cryptic startup
error rather than a structured `OWNER_CONFLICT` response with ownership metadata.

The ownership system (stages 1-5) works correctly for same-worktree scenarios (e.g., an agent
restarting within the same session). It provides zero protection for the multi-agent
interference case that tempdoc 271 was designed to prevent.

##### Finding 7: Community patterns for shared worktree state

Research identified four patterns used by other projects:

1. **`git rev-parse --git-common-dir`**: All worktrees share a common git directory. Runtime
   state can be stored under `.git/worktree-shared/` or similar. Used by opencode (#16995).
2. **Symlinks**: Tools like `worktree-env-sync` create symlinks from worktree paths to a
   shared location. Claude Code's `WorktreeCreate` hook could automate this.
3. **Fixed external path**: Store coordination state at `~/.cache/projectname/` or similar.
   Used by `git-worktree-runner` (CodeRabbit). Most robust for runtime coordination data.
4. **Build tool external caching**: Turborepo and FASTBuild both documented cache-miss problems
   with per-worktree paths; both solved by external shared cache directories.

Claude Code provides no built-in shared-state mechanism across worktrees. The `WorktreeCreate`
hook event exists and could set up symlinks, but this is DIY.

#### Resolved questions

**Q: Error or queuing when agent B needs agent A's stack?**
A: Clear error telling B to ask the user. This matches the existing `OWNER_CONFLICT` pattern.

**Q: Session-scoped `dataDir` (per-worktree `.dev-data`)?**
A: Already the default (Finding 6). Each worktree gets its own `modules/ui-web/.dev-data`.
This means separate embeddings per worktree, which is expensive but is the current behavior.

**Q: How does session identity flow to the MCP server?**
A: It works correctly per-worktree. Each session's MCP server reads the correct session ID
from its worktree's `tmp/agent-telemetry/current-session-id` file (Finding 3).

**Q: Does the ownership system work across worktrees?**
A: **No.** Ownership state is per-worktree. Cross-worktree protection requires shared state
that doesn't exist yet (Finding 6).

#### Viable implementation paths (revised)

The primary problem is no longer "how to identify the caller" (session identity works
per-worktree) but "how to share ownership state across worktrees." The paths are reframed:

**Path 1: Shared state directory via symlink (recommended)**
- Create a shared `tmp/dev-runner/` at a fixed location (e.g., `<mainRepo>/tmp/dev-runner/`)
- On worktree creation, symlink `<worktree>/tmp/dev-runner/` → shared location
- Can be automated via `WorktreeCreate` hook or the existing `EnterWorktree` mechanism
- Pro: Minimal code changes. `dev-runner.cjs` reads/writes the same files, just through a
  symlink. All existing ownership logic (leases, admission lock, OWNER_CONFLICT) works as-is.
- Con: Symlinks on Windows require elevated permissions or developer mode. Worktree creation
  must run the setup. Manual worktree creation would miss the symlink.
- Note: Windows 10+ Developer Mode enables symlinks without elevation. This repo already
  targets Windows-first development.

**Path 2: Fixed external state path**
- Change `stateRoot` in `dev-runner.cjs` to a fixed path outside the repo (e.g.,
  `path.join(os.homedir(), '.justsearch', 'dev-runner')`)
- Pro: No symlinks needed. Works across all worktrees automatically.
- Con: Breaks the current "everything under repo root" convention. Harder to find/debug.
  Requires migrating existing state. Multiple repo clones on the same machine would collide.

**Path 3: Resolve to main repo root**
- Change `dev-runner.cjs` to find the main repo root (not the worktree root) using
  `git rev-parse --git-common-dir` or by detecting the `.git` file (which in worktrees
  contains `gitdir: /path/to/main/.git/worktrees/<name>`)
- Pro: No symlinks, no external paths. State naturally shared because it's always under the
  main repo's `tmp/`.
- Con: Adds a git dependency to `dev-runner.cjs`. Must handle the case where the main repo
  is the current worktree (no `.git` file, `.git` is a directory).

**Path 4: Auto-inject session ID via PreToolUse hook (session identity layer)**
- A PreToolUse hook matching `mcp__justsearch-dev__*` injects `sessionId` into tool params
  via `updatedInput`. The hook reads `session_id` from its JSON input (always available).
- Pro: Zero agent compliance needed. Fully automatic.
- Con: Only solves session identity, not the cross-worktree ownership state gap. Must be
  combined with Path 1, 2, or 3.
- Risk: `updatedInput` + `permissionDecision: "allow"` may be buggy (#15897). Needs testing.

**Recommendation:** Path 3 (resolve to main repo root) + Path 4 (auto-inject session ID).

Path 3 is the cleanest because it uses git's own worktree mechanics — the `.git` file in a
worktree already points to the main repo. No symlinks, no external state, no hook setup.
Combined with Path 4 for automatic session identity, this gives cross-worktree ownership
protection with automatic caller identification and no agent compliance requirements.

#### Remaining uncertainties (post-deep-research) — ALL RESOLVED

1. ~~**`git rev-parse --git-common-dir` on Windows**~~ — Resolved: used `.git` file parsing
   instead of git subprocess. No performance concern (single `readFileSync` per invocation).
2. ~~**`.git` file parsing reliability**~~ — Resolved: `resolveMainRepoRoot()` implements
   error handling; falls back to `repoRoot` if `.git` file is missing or malformed.
3. ~~**`updatedInput` bug status (#15897)**~~ — Resolved: `permissionDecision: "allow"` +
   `updatedInput` works correctly in production. Confirmed via live test with
   `mcp-session-inject.mjs` hook (2026-03-12).
4. ~~**State migration**~~ — Resolved: no migration needed. Stale per-worktree
   `tmp/dev-runner/` directories are harmless — shared state under `mainRepoRoot` is
   the only authoritative source.

### Deferred items (lower priority)

Completed deferred items:

- [x] **proactive ownership check in MCP start handler** — `server.mjs` start handler
  reads `active.json` before spawning dev-runner. If a fresh owner exists and caller
  is not the owner, returns `OWNER_CONFLICT` immediately (saves the ~6-40s start attempt).
  Takeover requests bypass the pre-check and go to dev-runner as before.
- [x] **interference event records** — `stopRun()` in `dev-runner.cjs` appends
  interference-class dispositions (`stale_reclaim`, `warned_takeover`, `forced_reclaim`)
  to `tmp/dev-runner/interference-events.ndjson`. Per-run stop-reports still written.
- [x] **promotion of stable operator rules into canonical docs** — expanded "Backend
  Ownership" section in `docs/reference/contributing/mcp-dev-tools.md` with session
  identity model, lease mechanics, protection gates, and interference log. Updated
  troubleshooting entry for stop gate.
- [x] **cross-mode resource claim checks** — `backend-launcher.mjs` reads dev-runner's
  `active.json` before starting. If an active dev-runner stack claims overlapping port
  or dataDir, emits a stderr warning. Read-only check (does not write to `active.json`).

Remaining deferred items:

- direct backend-only ownership enrichment in `backend-launcher.mjs` (write-side: making
  backend-launcher record its own ownership in a way other launchers can detect)
- cross-engine projection enrichment in `eval-backend-lifecycle`
- human summary overlay (no consumer exists)

### Verification

Per-stage unit tests:
- stage 1: `node scripts/lib/bench/test-eval-backend-lifecycle.mjs` — PASS
- stage 3: `node scripts/lib/bench/test-dev-runner-lifecycle.mjs` — PASS (admission tests A-F)
- stage 4/4b: `node scripts/lib/bench/test-271-stage4-helpers.mjs` — PASS
  (resolveExpectedIndexBasePath 5 tests, resolveOwnerConfidence 8 tests)
- stage 7: `node scripts/lib/bench/test-dev-runner-lifecycle.mjs` — PASS (tests G-J)
  - G: `resolveMainRepoRoot` returns main repo from worktree context
  - H: stop with session mismatch → OWNER_CONFLICT
  - I: stop with session match → passes gate
  - J: stop with `--force` bypasses session gate

End-to-end integration (2026-03-12, post-merge to main):
1. [x] start dev stack via MCP `justsearch.dev.start` — started, runId `03b2d848`
2. [x] `run.json` contains `owner` (source, agentSessionId, confidence: "medium"),
   `resourceClaims` with all fields including `workerConfigSnapshotPath`,
   `expectedIndexBasePath` (derived_default), `confirmedIndexBasePath` (effective_config)
3. [x] `active.json` has lease with `sequence: 14`, holder, takeoverPolicy
4. [x] `quick_health` shows `running: true` with ownership fields flowing through
5. [x] second start with default takeover (deny) → `OWNER_CONFLICT` with full holder,
   lease, and resourceClaims in error response
6. [x] stop stack → stop-report has `schemaVersion: 2`, `disposition: "normal_stop"`,
   `portsClosed: true`
7. [x] `quick_health` after stop → `running: false`, no active owner

Stage 7 live verification (2026-03-12, from worktree `271-ownership`):
1. [x] **7a shared state**: Started stack via MCP. `active.json` written to
   `D:/code/JustSearch/tmp/dev-runner/active.json` (main repo root, not worktree).
   Contains `holder.agentSessionId: "542059f4-..."`, lease with sequence 1.
2. [x] **7c stop gate**: `JUSTSEARCH_AGENT_SESSION_ID=fake-other-session node dev-runner.cjs
   stop --json` → exit code 1, `error.code: "OWNER_CONFLICT"`.
3. [x] **Force bypass**: Same mismatched session + `--force` → exit code 0, stack stopped,
   `portsClosed: true`, 3 PIDs killed.
4. [x] **Cleanup**: `active.json` removed after stop.
5. [x] **7e callerIsOwner**: After merge to main and session restart, MCP `quick_health`
   returns `ownership.callerIsOwner: true` with full ownership projection (holder,
   lease, takeoverPolicy, leaseFresh). Verified run `ae32500c`.
6. [~] **7d clean gate**: Not tested live (destructive). Same code pattern as stop gate,
   covered by unit tests.

Multi-agent live test (2026-03-12, two agents on main):
1. [x] Agent A (session `542059f4`) started stack via MCP → run `c73dfb18`, `active.json`
   at shared main repo root with correct `holder.agentSessionId`.
2. [!] Agent B (session `af9ae2cc`) called `quick_health` → saw `callerIsOwner: true`
   **incorrectly**. Both MCP servers read the same `current-session-id` file (last writer
   wins when CWD is shared). Agent B's real session ID was not used.
3. [!] Agent B called `stop` → **succeeded** (no OWNER_CONFLICT). The stop gate compared
   the file-based session ID (same for both agents) against the owner — match → allowed.
4. **Conclusion**: Session-scoped gates only work across worktrees. Same-CWD agents bypass
   each other's gates. **7b (PreToolUse hook injection) is required**, not optional.

PreToolUse `updatedInput` feasibility test (2026-03-12):
1. [x] Diagnostic hook (`diag-mcp-updated-input.mjs`) registered with matcher
   `mcp__justsearch-dev__.*` — fires for all justsearch-dev MCP tools.
2. [x] Hook stdin contains `session_id`, `tool_name`, `tool_input`, `tool_use_id`.
3. [x] `updatedInput: { probe: false }` injected into `quick_health` → server received
   it, returned `httpReady: null` (probe skipped). **`updatedInput` works for MCP tools.**
4. [x] Windows: `/dev/stdin` doesn't exist — must use `process.stdin` async iteration.
5. [x] Settings: new hook registrations need session restart; script file changes hot-reload.

Not tested live (unit-tested only):
- `takeover: "warn"` path (covered by admission test C)
- `takeover: "force"` path (covered by admission test logic, no separate test)
- stale lease reclaim (covered by admission test D)
- clean gate (7d) — same code pattern as stop gate, covered by unit tests

## Decision-complete addendum (2026-03-11)

This addendum closes the protocol questions that were still open in the main draft.

The decisions below are based on:

- traced repo behavior in:
  - `scripts/dev/dev-runner.cjs`
  - `scripts/lib/bench/dev-runner-lifecycle.mjs`
  - `scripts/lib/bench/backend-launcher.mjs`
  - `scripts/lib/bench/eval-backend-lifecycle.mjs`
  - `scripts/lib/bench/lifecycle-cleanup.mjs`
  - `scripts/dev/justsearch-dev-mcp/server.mjs`
  - `modules/ui/.../HeadlessApp.java`
  - `modules/ui/.../EffectiveConfigController.java`
  - `modules/ui/.../StatusLifecycleHandler.java`
  - `modules/ui/.../UiSettingsStore.java`
  - `modules/app-services/.../WorkerSpawner.java`
  - `modules/app-util/.../AppInstanceLock.java`
  - `modules/indexer-worker/.../KnowledgeServer.java`
  - `modules/indexer-worker/.../IndexRootLock.java`
- primary-source external references:
  - Kubernetes Lease docs and leader-election config
  - SQLite locking and busy-timeout docs
  - Node.js `fs` docs
  - Microsoft file-move and file-lock docs
  - Terraform state locking docs
  - git-lfs locking API docs
  - OpenTelemetry session, MCP, logs, and entities docs

### A1. Current code findings that constrain the design

#### A1.1 Shared full-stack mode is single-pointer state, not a lease

Today `scripts/dev/dev-runner.cjs` does this:

- on `start`, it reads `tmp/dev-runner/active.json`
- if the prior run still looks alive, it silently stops it
- it writes new `tmp/dev-runner/runs/<runId>/run.json`
- it then overwrites `tmp/dev-runner/active.json`

Atomicity today:

- file writes already use `writeJsonAtomic(...)`
- that implementation is temp-file then rename
- this is good enough for atomic replacement of one file
- it is not, by itself, a true mutual-exclusion acquire protocol

Operational consequence:

- replacement of one JSON file is atomic
- ownership acquisition is not compare-and-swap
- concurrent starts can still race and the later writer can overwrite the earlier pointer
- the current code therefore supports "last writer wins", not a defensible lease acquire

#### A1.2 Direct mode persists only current-run state

`backend-launcher.mjs` writes:

- `<dataDir>/runtime/backend.pid`
- `<dataDir>/runtime/api-port.txt`
- `<dataDir>/runtime/backend-run.json`

This is a current-run record, not a retained history. Stop removes those runtime artifacts.

Operational consequence:

- direct mode already has a canonical current ownership file
- raw direct mode does not currently preserve immutable run history after stop
- if retained history is required, the wrapper layer must mirror it before stop removes it

#### A1.3 `indexBasePath` can be known pre-spawn and confirmed post-start

The effective path is determined by this chain:

1. `HeadlessApp` loads `UiSettingsStore`
2. if `UiSettings.indexBasePath` is set, it mirrors it into sysprop `justsearch.index.base_path`
3. `WorkerSpawner` forwards that sysprop to the Worker
4. `KnowledgeServer` loads runtime config and acquires `IndexRootLock` on the effective path
5. `/api/status` exposes `indexBasePath`
6. `/api/debug/effective-config` exposes `justsearch.index.base_path` with source details

Important current fact:

- both shared and direct launchers set `JUSTSEARCH_HOME = dataDir`
- `UiSettingsStore` resolves `settings.json` under `JUSTSEARCH_HOME/ui/settings.json`

Operational consequence:

- a launcher can cheaply compute a pre-spawn expected `indexBasePath` by reading:
  - explicit env/sysprop overrides it is about to pass
  - otherwise `dataDir/ui/settings.json`
  - otherwise default `<dataDir>/index/default`
- after HTTP readiness, the launcher can and should upgrade that claim with runtime-confirmed evidence from:
  - `/api/debug/effective-config`
  - fallback `/api/status`

#### A1.4 Cleanup semantics are narrower than they look

Current cleanup and stop behavior implies these scopes:

- shared dev-runner stop:
  - targets runner PID tree first
  - then frontend/backend root PIDs
  - then any remaining process owning the recorded API/UI ports
- direct backend stop:
  - targets the recorded backend PID
  - then the recorded API port owner if needed
  - then reaps root processes whose command lines still reference the same `dataDir`
- lifecycle cleanup:
  - removes runtime artifacts and optional data-dir contents
  - now scans for `app.lock` and `*.index.lock` artifacts (added in 274)
  - `killOrphanFromLockMetadata()` reads PIDs from lock files and kills orphan trees (added in 274)
  - it does not currently name cleanup claims in ownership metadata

Operational consequence:

- current code already implies two different cleanup models:
  - shared-stack cleanup by run ownership plus known ports
  - direct cleanup by `dataDir` root plus known API port
- `WindowsJobObject` (274) largely eliminates orphan-Worker scenarios on Windows, making cleanup
  reaping a defense-in-depth concern rather than the primary recovery mechanism
- cleanup claim scope must still be made explicit in ownership records for non-Windows platforms
  and for diagnosis

#### A1.5 MCP already has the right insertion points

Current maintained MCP surfaces already read the shared state:

- `justsearch.dev.quick_health`
- `justsearch.dev.status`
- `justsearch.dev.preflight`
- `justsearch.dev.fetch_api_json`

They can already surface:

- active shared run
- current ports
- basic liveness and readiness
- effective config and debug state through HTTP

Operational consequence:

- the warning path should be exposed in structured MCP output and CLI JSON
- the shared-start control path should stay single-path
- the right design is "same start tool, extra takeover intent field"

### A2. v1 canonical ownership model

#### A2.1 Canonical sources of truth

Canonical ownership files by launcher family:

- shared full-stack current owner:
  - `tmp/dev-runner/active.json`
- shared full-stack immutable per-run snapshot:
  - `tmp/dev-runner/runs/<runId>/run.json`
- shared full-stack stop/takeover effects:
  - `tmp/dev-runner/runs/<runId>/stop-report.json`
- direct current owner and current claims:
  - `<dataDir>/runtime/backend-run.json`

Projection layers:

- `tmp/eval-lifecycle/runs/<runId>.json`
  - normalized projection only
- workflow telemetry
  - derived event/evidence layer only
- MCP
  - operator read surface only

Authoritative rule:

- launcher-owned files are authoritative for current ownership and explicit ownership metadata
- runtime HTTP endpoints are authoritative for runtime-confirmed effective values
- telemetry, MCP summaries, and session joins are derived evidence

#### A2.2 Advisory versus authoritative files

Repo-managed files fall into three trust classes:

- authoritative:
  - launcher-owned ownership files
  - runtime endpoint payloads fetched from the live backend
  - lock files enforced by OS primitives:
    - `app.lock`
    - sibling `*.index.lock`
- advisory:
  - lifecycle registry projections
  - workflow telemetry
  - MCP summaries
  - pre-spawn expected claims
- weak fallback:
  - session fallback file `tmp/agent-telemetry/current-session-id`
  - logs, timestamps, and human summaries

### A3. Exact shared lease state machine

#### A3.1 Lease fields

Shared ownership is a lease record in `tmp/dev-runner/active.json` with at least:

- `backendRunId`
- `launcherFamily`
- `mode`
- `holder`
- `takeoverPolicy`
- `lease.durationSec`
- `lease.renewedAt`
- `lease.expiresAt`
- `lease.sequence`
- `previousLeaseId` or `previousBackendRunId` on transfer
- `updatedAt`

#### A3.2 Lease timing

v1 timing:

- renew interval: 10 seconds
- lease duration: 30 seconds
- stale threshold: `now > expiresAt`
- immediate stale hint:
  - if the recorded runner PID is dead, treat the lease as stale without waiting for the full 30s

Why these numbers:

- they follow the same shape as Kubernetes leader-election semantics:
  - periodic renew
  - bounded stale window
  - shorter renew interval than lease duration
- they are long enough to survive short pauses and log delays
- they are short enough for agent workflows on one workstation

#### A3.3 Acquire rules

`dev-runner start` follows this rule set:

1. Compute the candidate lease payload and claimant metadata.
2. Acquire an admission mutex by creating `tmp/dev-runner/active.lock.json` with `flag: 'wx'`.
3. If the sidecar lockfile already exists:
   - read it
   - if the recorded PID is dead, remove it and retry lock acquisition
   - if the recorded PID is alive, return `OWNER_CONFLICT`
4. Once the admission mutex is held, read `active.json`.
5. If no active lease exists, atomically write the new lease to `active.json`.
6. If an active lease exists and is fresh:
   - without explicit takeover intent: abort with `OWNER_CONFLICT`
   - with `takeover=warn`: stop victim, record transfer, then atomically rewrite `active.json`
   - with `takeover=force`: stop victim, record `forced_reclaim`, then atomically rewrite `active.json`
7. If an active lease exists and is stale:
   - surface stale-owner warning
   - reclaim without a second round trip
   - record disposition as `stale_reclaim`
   - atomically rewrite `active.json`
8. Remove `active.lock.json` after `active.json` is committed or the acquire attempt aborts.

Admission-lock rule:

- `active.lock.json` is an admission mutex only, not the long-lived lease
- `active.json` remains the canonical shared lease record
- temp-file rename remains the payload-write primitive for `active.json`
- temp-file rename is never the mutual-exclusion primitive by itself

v1 rule for stale leases:

- stale reclaim is not silent
- it does not require a second confirmation call
- it must emit a warning in CLI JSON and MCP structured output

#### A3.4 Renew rules

Only the active shared supervisor renews the shared lease.

Renewal triggers:

- time-based renewal every 10 seconds from the long-running `dev-runner` supervisor
- optional immediate renewal after start reaches `ready_http`

Renewals must not come from:

- MCP status tools
- quick-health probes
- external readers

Reason:

- readers must never accidentally keep ownership alive

#### A3.5 Expire rules

A shared lease is expired when either condition holds:

- `expiresAt` is in the past
- the recorded runner PID is dead and the lease is older than one missed renew interval

This keeps expiry conservative when clocks are fine and faster when the owner process is obviously gone.

Post-274 note: on Windows, `WindowsJobObject` guarantees that if the Head (runner) PID is dead,
the Worker is also dead. This makes the dead-PID check a definitive signal for full backend
termination, not just a heuristic. The lease can be reclaimed immediately when the runner PID is
dead, without waiting for the full 30s expiry window.

### A4. Takeover interaction model

#### A4.1 One control path

There must be one shared-start path only.

The existing shared-start stack remains:

- MCP `justsearch.dev.start`
- any CLI wrapper that calls `dev-runner start`
- `dev-runner.cjs start`

The only new control-plane input is takeover intent.

#### A4.2 Exact caller intent contract

Shared-start callers get one new argument:

- `takeover = "deny" | "warn" | "force"`

v1 defaults:

- omitted means `deny`
- `warn` is the normal intentional replacement path
- `force` is reserved for operator-grade override of a fresh owner

Behavior:

- `deny`
  - if fresh owner exists, return conflict summary and do nothing
  - if owner is stale, warning plus `stale_reclaim` proceeds automatically
- `warn`
  - if fresh owner exists, surface victim summary, stop victim, record warned takeover, continue
  - if owner is stale, warning plus `stale_reclaim` proceeds automatically
- `force`
  - if fresh owner exists, bypasses soft safeguards and records `forced_reclaim`
  - it is not required for the normal stale-owner path

#### A4.3 MCP and CLI warning behavior

Both surfaces must expose takeover warnings.

CLI JSON on conflict must include:

- current owner summary
- freshness
- resource claims
- how to retry with explicit takeover

MCP structured output on conflict must include:

- same ownership summary
- `actionRequired: "retry_with_takeover"`
- suggested `takeover` value

No free-text-only warning is sufficient.

### A5. Exact `indexBasePath` capture strategy

#### A5.1 Two-stage capture rule

Every launcher family that owns backend startup must persist:

- `expectedIndexBasePath`
- `expectedIndexBasePathEvidence`
- `effectiveIndexBasePath`
- `effectiveIndexBasePathEvidence`

Stage 1, pre-spawn expected claim:

Resolve in this order:

1. explicit launcher-passed sysprop/env override
2. `JUSTSEARCH_HOME/ui/settings.json` `indexBasePath`
3. derived default `<dataDir>/index/default`

Stage 2, post-start effective claim:

After `ready_http`, fetch:

1. `/api/debug/effective-config`
2. fallback `/api/status`

Persist the runtime-confirmed effective value over the expected one.

#### A5.2 Evidence precedence

Evidence precedence for `indexBasePath`:

1. `/api/debug/effective-config` entry for `justsearch.index.base_path`
2. `/api/status.indexBasePath`
3. launcher pre-spawn resolution from explicit overrides
4. launcher pre-spawn resolution from settings file
5. launcher default derivation from `dataDir`

#### A5.3 Early-failure fallback

If the backend dies before any HTTP endpoint is reachable:

- keep the pre-spawn expected claim
- mark `effectiveIndexBasePath = null`
- mark ownership confidence no higher than `medium`

This is sufficient because:

- the launcher controls `JUSTSEARCH_HOME`
- `UiSettingsStore` path resolution is deterministic
- the remaining uncertainty is whether startup mutated the winning source before failure, which is unlikely but not impossible

### A6. Exact v1 resource-claim set

#### A6.1 Must be part of v1 ownership claims

For shared full-stack runs:

- `sharedStackSlot`
- `apiPort`
- `uiPort`
- `dataDir`
- `justsearchHome`
- `settingsStorePath`
- `runtimeDir`
- `workerConfigSnapshotPath`
- `expectedIndexBasePath`
- `effectiveIndexBasePath`

For direct backend-only runs:

- `apiPort`
- `dataDir`
- `justsearchHome`
- `settingsStorePath`
- `runtimeDir`
- `workerConfigSnapshotPath`
- `expectedIndexBasePath`
- `effectiveIndexBasePath`

#### A6.2 Diagnostics-only in v1

Useful for diagnosis, but not part of the v1 ownership gate:

- backend PID tree
- frontend PID tree
- log paths
- `api-port.txt`
- `backend.pid`
- `stop-report.json`
- active runtime variant/model
- inference server port ownership
- GPU / VRAM usage

#### A6.3 Out of scope for v1 ownership control

Out of scope for the v1 canonical claim set:

- global GPU scheduling across unrelated tools
- ownership of externally adopted inference servers
- multi-user machine-wide arbitration outside the repo
- a new central database for all ownership history

### A7. Cleanup actor, victim, and disposition semantics

#### A7.1 Cleanup policy field

Every ownership record must declare one of:

- `shared-stack`
- `same-data-dir`
- `unknown`

Meaning:

- `shared-stack`
  - shared dev-runner may stop the prior shared owner and its UI/API ports
- `same-data-dir`
  - direct launcher may reap processes rooted in the same `dataDir`
- `unknown`
  - never claim cleanup safety beyond best effort

#### A7.2 Actor and victim rules

Actor:

- the run that initiated stop, cleanup, or takeover

Victim:

- the run that most recently held the contested canonical claim at the time of the action

If no canonical victim can be named:

- leave victim null
- do not invent a victim from timing alone

#### A7.3 Exact dispositions

- `prevented`
  - ownership conflict detected before takeover or startup
- `warned_takeover`
  - explicit `takeover=warn` replaced a fresh owner
- `forced_reclaim`
  - explicit `takeover=force` or operator-grade override replaced a fresh owner
- `stale_reclaim`
  - expired owner was reclaimed after warning
- `startup_aborted`
  - startup failed due to contested resource before usable runtime
- `degraded`
  - run continued, but interference or cleanup side effects were observed
- `unresolved`
  - insufficient evidence to name outcome precisely

### A8. Exact confidence rules

#### A8.1 Ownership record confidence

`high`:

- launcher wrote the ownership record
- owner source is explicit (`workflowRunId`, explicit session id, explicit launcher source)
- resource claims are launcher-known or runtime-confirmed

`medium`:

- launcher wrote the ownership record
- owner identity depends on env/file fallback attribution from tempdoc 272
- `indexBasePath` is only pre-spawn expected, not runtime-confirmed

`low`:

- ownership is inferred from logs, timestamps, or partial runtime artifacts
- launcher-owned record is missing or stale

#### A8.2 Interference event confidence

`high`:

- contested resource is canonical
- actor and victim both resolve to launcher-owned records or OS-enforced locks

`medium`:

- contested resource is canonical
- only one side resolves canonically, or one side depends on runtime endpoint / session fallback joins

`low`:

- event depends on manual inference only
- no canonical ownership record names the victim

#### A8.3 MCP summary confidence

MCP should report:

- `ownershipConfidence`
- `interferenceConfidence`

and must explain the highest contributing evidence source, for example:

- `launcher_record`
- `runtime_endpoint`
- `session_fallback`
- `log_inference`

### A9. Exact ownership-history surface

v1 minimum history is:

- current shared owner:
  - `tmp/dev-runner/active.json`
- immutable shared per-run snapshots:
  - `tmp/dev-runner/runs/<runId>/run.json`
  - `tmp/dev-runner/runs/<runId>/stop-report.json`
- normalized eval-run snapshots when the wrapper is used:
  - `tmp/eval-lifecycle/runs/<runId>.json`

History policy by launcher family:

- shared dev-runner:
  - retain immutable run directories
  - current prune policy stays:
    - 14 days
    - latest 200 runs
- eval wrapper:
  - keep one retained file per canonical run id
  - stop updates that file with final state instead of deleting it
  - `status --run-id <id>` after stop may return `not_running`, but the retained registry file remains
- raw direct launcher:
  - current-run file only in v1
  - no guaranteed retained history after stop

This is sufficient for v1 because:

- shared-stack diagnosis is the main operator need
- direct retained history already exists when agents use the supported eval wrapper
- raw direct launcher history can be deferred without blocking the ownership protocol

### A10. Exact MCP ownership summary behavior

The MCP summary must answer:

- who owns the backend
- whether the lease is fresh
- which mode is active
- which resources are claimed
- whether takeover would conflict
- what confidence level applies

Required fields:

- `mode`
- `launcherFamily`
- `backendRunId`
- `workflowRunId`
- `agentSessionId`
- `ownershipConfidence`
- `resourceClaims`
- `sharedLease`
- `takeoverRequired`
- `takeoverSuggested`
- `riskMarkers`

Exact behavior:

- `quick_health`
  - remains read-only and fast
  - adds ownership summary when shared state exists
- `status`
  - remains the detailed typed surface
  - adds full ownership and claim details
- `start`
  - on conflict, returns structured owner summary and required retry mode

Implementation note from current code trace:

- MCP output schemas are already permissive enough for the new ownership fields
- the only strict MCP schema change required for v1 is adding `takeover` to `StartInputSchema`

### A11. Why file-backed ownership remains sufficient in v1

The repo does not need SQLite for v1 ownership control.

Reasoning:

- launcher state is local-first and single-host
- shared mode has exactly one current-owner lease file plus immutable run snapshots
- direct mode already persists current-run metadata under the owned `dataDir`
- Node's own docs warn that concurrent file modifications are not synchronized or thread-safe
- therefore the missing primitive is not "a database", but "explicit lease acquire semantics plus a sidecar admission lock"

The required strengthening is:

- do not rely on temp-file rename alone for mutual exclusion
- use a `wx`-created sidecar lockfile before replacing the shared lease record
- keep rename-based atomic replacement for the lease payload itself

For raw direct mode, launcher-owned files remain acceptable because the true resource safety comes from:

- `AppInstanceLock` on `dataDir`
- `IndexRootLock` on effective `indexBasePath`
- API port binding by the OS

### A12. Final v1 protocol decision summary

#### Shared lease state machine

- `acquire`
  - no owner or stale owner: acquire
  - fresh owner: reject unless takeover intent is explicit
- `renew`
  - shared supervisor every 10s
- `expire`
  - stale after 30s or dead owner PID plus missed renew
- `warn_takeover`
  - explicit `takeover=warn`
- `forced_reclaim`
  - explicit `takeover=force` against a fresh owner
- `stale_reclaim`
  - warning emitted, then reclaim proceeds without second prompt

#### Canonical source of truth

- launcher-owned ownership files are primary
- runtime endpoints confirm effective values
- lifecycle registry, telemetry, and MCP are projections

#### Exact v1 claim set

- shared:
  - shared slot, API/UI ports, `dataDir`, `JUSTSEARCH_HOME`, settings path, runtime path,
    worker snapshot path, expected/effective `indexBasePath`
- direct:
  - API port, `dataDir`, `JUSTSEARCH_HOME`, settings path, runtime path, worker snapshot path,
    expected/effective `indexBasePath`

#### Exact `indexBasePath` strategy

- persist expected pre-spawn claim
- upgrade with runtime-confirmed value after HTTP readiness
- keep expected-only evidence if startup dies early

#### Cleanup semantics

- declare cleanup policy explicitly
- name actor and victim only from canonical evidence
- use the fixed disposition set above

#### Confidence semantics

- `high|medium|low`
- applied to ownership records, interference events, and MCP summaries
- confidence derives from evidence class, not from optimism

#### Minimum history

- shared current owner + immutable shared run snapshots
- eval wrapper per-run registry snapshots
- no new database in v1

### A13. Final uncertainty closure

This follow-up pass closes the remaining protocol ambiguities from the 2026-03-11 addendum.

Final decisions:

- lease acquire primitive:
  - sidecar `wx` lockfile at `tmp/dev-runner/active.lock.json`
  - atomic replace of `active.json` only while the sidecar admission lock is held
- stale reclaim rule:
  - warning plus automatic `stale_reclaim`
  - no `takeover=force` required
- live v1 cleanup-policy enum:
  - `shared-stack | same-data-dir | unknown`

### A14. Implementation de-risking findings

The remaining implementation risk is concentrated in three seams, not in unresolved protocol
design.

#### A14.1 Shared lease admission seam

Current risk concentrates in `scripts/dev/dev-runner.cjs` `cmdStart`, which currently:

- reads `active.json`
- may auto-stop the previous run
- writes `run.json`
- overwrites `active.json`

The first implementation pass should add characterization tests before behavior changes.

Minimum characterization cases:

- fresh sidecar lock conflict:
  - live lock holder PID
  - start returns `OWNER_CONFLICT`
  - no new `active.json` owner is committed
- stale sidecar lock recovery:
  - dead lock holder PID
  - start removes stale lockfile, acquires admission mutex, and continues
- concurrent-start protection:
  - two starts must not collapse back to last-writer-wins ownership of `active.json`

#### A14.2 MCP projection seam

Current risk in `scripts/dev/justsearch-dev-mcp` is lower than it initially appeared.

Findings from the code trace:

- `status` and `quick_health` output schemas already allow extra fields
- the hard schema gate is the strict `StartInputSchema`
- the start path already returns structured tool output that can carry ownership conflict detail

Minimum MCP verification cases:

- `start` conflict returns current owner summary plus retry guidance
- stale-owner reclaim returns warning-oriented structured output
- `quick_health` and `status` project ownership summary fields without breaking existing callers

#### A14.3 Eval wrapper history seam

Current code in `scripts/lib/bench/eval-backend-lifecycle.mjs` still deletes
`tmp/eval-lifecycle/runs/<runId>.json` on successful stop.

That behavior conflicts with the v1 history model in this tempdoc.

v1 implementation rule:

- `stop` updates the retained registry snapshot with final stop state
- `removeRegistry(runId)` is not used for normal successful stop
- the retained file remains the normalized immutable-per-run projection for diagnosis

Minimum verification case:

- after `start` then `stop`, `tmp/eval-lifecycle/runs/<runId>.json` still exists and records the
  final stop envelope

## Primary-source references used in this addendum

- Kubernetes Leases:
  <https://kubernetes.io/docs/concepts/architecture/leases/>
- Kubernetes leader-election config:
  <https://kubernetes.io/docs/reference/config-api/kube-scheduler-config.v1/>
- SQLite locking:
  <https://www.sqlite.org/lockingv3.html>
- SQLite busy timeout:
  <https://www.sqlite.org/pragma.html#pragma_busy_timeout>
- Node.js `fs`:
  <https://nodejs.org/api/fs.html>
- Windows moving/replacing files:
  <https://learn.microsoft.com/en-us/windows/win32/fileio/moving-and-replacing-files>
- Windows `LockFileEx`:
  <https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-lockfileex>
- Terraform state locking:
  <https://developer.hashicorp.com/terraform/language/state/locking>
- git-lfs locking API:
  <https://github.com/git-lfs/git-lfs/blob/main/docs/api/locking.md>
- OpenTelemetry session semantics:
  <https://opentelemetry.io/docs/specs/semconv/general/session/>
- OpenTelemetry MCP semantics:
  <https://opentelemetry.io/docs/specs/semconv/gen-ai/mcp/>
- OpenTelemetry logs:
  <https://opentelemetry.io/docs/specs/otel/logs/>
- OpenTelemetry entities data model:
  <https://opentelemetry.io/docs/specs/otel/entities/data-model/>

## Post-271 codebase changes review (2026-03-11)

This section records changes that landed after the original tempdoc was written and assesses their
impact on the implementation plan.

### Changes reviewed

- Tempdoc 274 (Scoring Recalibration and Lock Cleanup) — merged `b8480d6f`
- Tempdoc 275 (Gradle Cold-Start Optimization) — merged into main
- Tempdocs 272, 273, 274-fusion, 276 — reviewed for overlap

### R1. WindowsJobObject eliminates orphan-Worker scenarios (274)

Tempdoc 274's Lock Cleanup lane added `WindowsJobObject` in `modules/app-util` and wired it into
`WorkerSpawner`. The Job Object uses `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, which means:

- when Head exits for any reason (clean shutdown, crash, OOM), the OS kills Worker automatically
- orphan Workers holding index locks or API ports after their Head is gone are no longer possible
  on Windows

Impact on 271:

- the stale-lease recovery path (`stale_reclaim`) is simpler than originally scoped:
  - if the recorded runner PID is dead, the backend is guaranteed dead (Head→Worker lifetime
    coupling is OS-enforced)
  - `stale_reclaim` only needs to clean up stale lease files and verify ports are free
  - no process tree killing is needed for the normal stale recovery case
- the `cleanup_reap_interference` event type remains useful for defense-in-depth and non-Windows
  platforms, but is no longer the primary orphan-backend scenario on Windows
- the confidence model for "is the backend actually running?" is higher when PID-dead detection
  is combined with Job Object guarantees

No structural changes to the lease protocol are needed. The protocol already defined stale
detection via dead PID as an immediate stale hint (A3.5). Job Objects make that hint definitive
rather than probabilistic.

### R2. Lifecycle cleanup gained lock-file awareness (274)

Tempdoc 274 also added:

- `app.lock` and `*.index.lock` detection in `lifecycle-cleanup.mjs` runtime artifact scanning
- `killOrphanFromLockMetadata()` — reads PIDs from `app.lock` or `backend.pid` and kills orphan
  process trees before cleanup

Impact on 271:

- 271's cleanup-policy field (`shared-stack | same-data-dir | unknown`) remains valid
- the lifecycle cleanup layer now handles lock-file artifacts that 271's ownership records will
  reference, which is complementary

### R3. Startup timing constants already updated (275)

Tempdoc 275 changed timeout values in the exact files 271 will edit:

- `dev-runner.cjs`: `defaultPortEmitTimeoutMs` 30s→20s, `defaultBackendReadyTimeoutMs` 30s→60s
- `server.mjs`: `waitTimeoutMs` default 30s→60s, description strings updated
- `schemas.mjs`: `waitTimeoutMs` description updated

Impact on 271:

- 271 must base edits on current HEAD, not the file state when this tempdoc was written
- no design conflict — these are timeout calibration changes, not ownership semantics

### R4. No overlap from 272, 273, 274-fusion, 276

- 272 (Workflow Attribution): pure analytics/telemetry. Correctly scoped as supporting layer.
- 273 (SPLADE Follow-Up): Java indexing work, no intersection with dev-runner/MCP layer.
- 274-fusion (Hybrid Fusion Upgrade): search-quality work in adapters-lucene/configuration.
- 276 (Session Type Classification): agent-analytics work in evaluate-session/dispatch.

### R5. Target implementation files — drift assessment

- `scripts/dev/dev-runner.cjs` — minor timeout changes from 275. Core `cmdStart` logic unchanged.
- `scripts/dev/justsearch-dev-mcp/server.mjs` — description string and timeout default from 275.
  Core tool handler logic unchanged.
- `scripts/dev/justsearch-dev-mcp/schemas.mjs` — one description string from 275. Schema
  structure unchanged.
- `scripts/lib/bench/eval-backend-lifecycle.mjs` — zero changes. Safe to implement as designed.
- `scripts/dev/justsearch-dev-mcp/cli.mjs` — zero changes.

### Summary of adjustments

1. Added `WindowsJobObject` to "Current verified facts" §Resource ownership (now three levels).
2. Simplified `cleanup_reap_interference` in the interference model table.
3. No structural changes to the lease protocol, takeover contract, MCP projection, or eval history
   retention. All remain valid and needed.
4. Implementation must target current HEAD for file edits in dev-runner/MCP files.

## External research: multi-agent workflow time wastes (2026-03-13)

This section surveys the biggest workflow time wastes in multi-agent AI coding setups, based on
internet research across academic papers, industry reports, engineering blogs, and community data.
Each finding is assessed for relevance to JustSearch's specific setup (3-4 parallel Claude Code
agents, git worktrees, shared dev stack, Windows, Gradle).

### Sources consulted

Academic papers:
- METR Study: "Measuring the Impact of Early-2025 AI on Experienced OSS Developer Productivity"
  (<https://arxiv.org/abs/2507.09089>) — RCT with 246 tasks, 16 experienced developers
- "Where Do AI Coding Agents Fail?" (<https://arxiv.org/abs/2601.15195>) — 33,596 agent-authored PRs
- "On the Use of Agentic Coding" (<https://arxiv.org/abs/2509.14745>) — 567 Claude Code PRs across
  157 open-source projects
- "Why Are AI Agent PRs Unmerged?" (<https://arxiv.org/abs/2602.00164>) — qualitative analysis of
  600 rejected PRs
- "AI Agentic Programming: A Survey" (<https://arxiv.org/abs/2508.11126>) — taxonomy of 152 papers
- "Distributed Locking: Performance Analysis and Optimization Strategies"
  (<https://arxiv.org/html/2504.03073v1>)

Industry reports and engineering blogs:
- Anthropic 2026 Agentic Coding Trends Report
  (<https://resources.anthropic.com/2026-agentic-coding-trends-report>)
- Anthropic context engineering guide
  (<https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents>)
- LinearB 2026 Software Engineering Benchmarks Report
  (<https://linearb.io/resources/software-engineering-benchmarks-report>)
- Addy Osmani, "The 80% Problem in Agentic Coding"
  (<https://addyo.substack.com/p/the-80-problem-in-agentic-coding>)
- MIT Missing Semester 2026 "Agentic Coding" lecture
  (<https://missing.csail.mit.edu/2026/agentic-coding/>)
- Factory.ai context compression evaluation (<https://factory.ai/news/evaluating-compression>)

Community data and tooling:
- DEV Community: "I tracked where my Claude Code tokens actually go. 37% were wasted"
  (<https://dev.to/egorfedorov/i-tracked-where-my-claude-code-tokens-actually-go-37-were-wasted-2gll>)
- DEV Community: `read-once` hook — redundant file read reduction
  (<https://dev.to/boucle2026/read-once-a-claude-code-hook-that-stops-redundant-file-reads-4bjk>)
- Clash: pre-merge conflict detection across worktrees (<https://github.com/clash-sh/clash>)
- Gradle issue #8375: parallel builds sharing cache location fail
  (<https://github.com/gradle/gradle/issues/8375>)

### W1. Context loss and re-orientation (30-40% of token budget)

This is the single largest measured waste category.

Empirical data:
- 37% of all tokens spent in a Claude Code session were wasted — primarily on re-reading files
  already in context and reading files that were never used (DEV Community instrumentation study)
- 30-40% of read tokens go to redundant file reads after compaction. A `read-once` hook measured
  40% savings by blocking redundant reads — 19 of 47 file reads in one session were cache hits
- Only 20-30% of original detail survives compaction. Summaries focus on "what happened" and lose
  "why" and subtle decisions
- PreCompact hooks reduce critical information loss by 30% (Anthropic changelog, January 2026)
- Factory.ai evaluation: structured summarization retains significantly more technical details
  (file paths, error messages) than built-in compression — scored 3.70 vs Anthropic's 3.44

What happens after compaction:
- Agents re-read files they already read minutes earlier
- Agents re-explore approaches already tried
- Agents make decisions that contradict earlier analysis
- Context overhead can reach 8,400+ tokens per hour per agent for monitoring cycles

JustSearch mitigations already in place:
- `compaction-state.md` with file lists and edit history (`.claude/rules/`)
- `278-decision-log.md` for persistent experiment state
- Post-compaction orientation rules ("read the decision log first")
- Tempdoc-driven work that persists outside the context window

Remaining gap:
- No `read-once` or file-read caching mechanism
- No measurement of actual re-read rates per session
- Compaction triggers at ~65% (via `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`) but context quality
  still degrades substantially

### W2. Failed approaches and dead ends (19% measured slowdown)

Empirical data:
- METR study (RCT, 246 tasks): AI tools made experienced developers 19% slower. Developers
  accepted less than 44% of AI generations. Critically, developers *believed* AI sped them up by
  20% even while experiencing the 19% slowdown
- Independent Devin testing: 15% success rate (3/20 tasks), 14/20 failed in production
- LinearB (8.1M PRs, 4,800 teams): AI-generated code contains 1.7x more issues than human code
  (10.83 vs 6.45 issues per PR). PR acceptance dropped from 84% to 32%
- 67.3% of AI-generated PRs get rejected versus 15.6% for manual code
- "The 80% Problem": agents rapidly produce 80% of code but the remaining 20% — edge cases,
  security, production integration — is where the real work is

JustSearch mitigations:
- Tempdoc-driven work with explicit scope prevents agents from inventing tasks
- Decision log discipline with pass/fail criteria limits dead-end loops
- "If still failing after one retry with a different approach, document and move on" rule
- "Fix root causes, not symptoms" instruction prevents masking failures

Remaining gap:
- No measurement of per-session dead-end rate
- No aggregate tracking of how often agents retry the same approach across sessions

### W3. Build and compilation overhead (full rebuild per worktree)

Empirical data:
- Two Gradle builds sharing the same cache location fail when running in parallel
  (Gradle issue #8375) — directly relevant to worktree setups
- Any change to `buildSrc` or build scripts invalidates the entire Gradle configuration cache
  for the whole build
- Fast compilation is critical for agents because they iterate via compiler errors — each
  iteration consuming a full build cycle

JustSearch specifics:
- Each worktree pays the full build cost independently since Gradle build caches cannot be shared
  safely across worktrees
- The `build -x test` compile check pattern is correct but adds up across iterations
- `spotlessApply` + `build -x test` + module tests = 3 separate Gradle invocations per edit cycle
- Multi-module lockfile regeneration (`resolveAndLockAll --write-locks`) is slow and must be done
  whenever dependencies change

Remaining gap:
- No measurement of total build time per session
- No Gradle daemon sharing strategy across worktrees
- Lockfile changes across many modules are a known merge-conflict hotspot (visible in the
  current git status diff)

### W4. Merge conflicts and integration overhead (scales with file overlap)

Empirical data:
- "If two agents need to modify the same file or the same domain entity, the merge will be
  painful regardless of isolation" — modular architecture helps but does not eliminate this
- Analysis of 33,596 agent-authored PRs: unmerged PRs touch more files and fail CI more often
- 23% of rejected PRs were duplicates — multiple agents solving the same problem
- 38% of rejected PRs had no meaningful reviewer engagement (reviewer-level abandonment)
- Clash (open-source, Rust) performs read-only three-way merge simulation across worktrees
  during edits, detecting conflicts before agents finish their work

JustSearch specifics:
- Modular architecture (Head/Body/Brain, separate Gradle modules) naturally reduces file overlap
- `gradle.lockfile` changes across 20+ modules in the current diff are a central-registry
  hotspot — every feature that touches dependencies modifies every module's lockfile
- `verification-metadata.xml` is another single-file bottleneck

Potential mitigation:
- Run lockfile regeneration as a post-merge step on `main` rather than per-agent
- Consider Clash for pre-merge conflict detection across worktrees
- Architectural boundaries matter more than tooling — keep modules cleanly separated

### W5. Dev stack startup/teardown waste (38-60s per cycle)

JustSearch measured data (from tempdoc 275 and agent-lessons):
- Direct-launch cold start: ~6s to port emit, ~38s to worker ready
- MCP `justsearch_dev_start` default timeout: 60s
- Orphaned backends hold ports — each orphan cleanup cycle (PowerShell detect + kill + retry)
  adds 30-60+ seconds
- Health check polling at 30-60 second intervals means 3 consecutive polls (~90-180s) before
  investigation begins — this is dead time

External data:
- Cold start latency in AI inference: 5-20s for model loading alone
- Model preloading eliminates loading phase but requires careful GPU memory planning

JustSearch mitigations:
- Proactive ownership check in MCP start handler (saves 6-40s start attempt on conflict)
- `--clean none` preserves embedding progress across backend restarts
- WindowsJobObject guarantees Worker dies when Head exits — simplifies stale recovery

Remaining gap:
- No automatic orphan detection before start (currently manual PowerShell commands)
- No warm-start optimization when restarting the same configuration
- No measurement of total startup/teardown time as percentage of session time

### W6. Coordination and ownership overhead (per-interaction cost)

Empirical data:
- Distributed locking research: average latency grows by 292% from 8 to 64 nodes with
  centralized lock managers
- Adaptive lease duration improved throughput by up to 42% compared to fixed-duration approaches
- Lease-based locking maintains 65,000 ops/sec even at 100% contention — 261% improvement over
  centralized lock managers
- OpenAI Codex eliminates this entirely via cloud sandboxes (one per task), trading cloud
  dependency for zero coordination

JustSearch specifics:
- Single shared dev stack with lease-based ownership is the right pattern per research
- Each `quick_health` / `status` check costs tokens and wall-clock time
- `OWNER_CONFLICT` → retry with takeover → requires user approval → context switches
- Rule "other agents verify via unit tests and compilation, not live backend" is the key
  mitigation — most agents should never need the dev stack

Remaining gap:
- No measurement of how many ownership checks per session
- No aggregate data on how often `OWNER_CONFLICT` actually fires in practice
- No mechanism for agents to reserve the dev stack in advance (only claim-on-start)

### W7. Quantitative summary

| Category | Measured waste | Primary evidence |
|---|---|---|
| Context loss / re-orientation | 30-40% of token budget | DEV Community instrumentation, Factory.ai |
| Failed approaches / dead ends | 19% developer slowdown; <44% acceptance | METR RCT (n=246), LinearB (8.1M PRs) |
| Build / compilation | Full rebuild per worktree | Gradle #8375, per-worktree cache isolation |
| Merge conflicts | Scales with file overlap | 23% duplicate PRs (arxiv 2602.00164) |
| Dev stack startup/teardown | 38-60s per cycle + orphan cleanup | Tempdoc 275 measurements |
| Coordination / ownership | Per-interaction token + time cost | Distributed locking research |

### W8. Implications for 271 and this repo

What 271 already addresses well:
- Lease-based ownership is the empirically validated pattern (vs centralized locks)
- Session-scoped gates prevent the most expensive interference (accidental stop)
- Proactive ownership check saves the full start cycle cost on conflict
- Interference event log enables aggregate analysis of coordination overhead

What remains unaddressed:
- **Context waste is the largest category (30-40%)** and is mostly outside 271's scope.
  The repo mitigates it with `.claude/rules/` files, decision logs, and compaction hooks,
  but has no file-read caching or redundancy detection.
- **Build waste per worktree** is the second-largest in practice for this repo. Gradle cache
  sharing across worktrees is unsafe (Gradle #8375). This is a Gradle limitation, not an
  ownership problem.
- **Lockfile merge conflicts** are the most predictable integration overhead. A post-merge
  lockfile regeneration workflow would reduce this substantially.
- **No advance reservation** — agents cannot reserve the dev stack before starting work,
  so they discover conflicts only at start time. A reservation mechanism could reduce
  wasted context on planning work that requires the stack.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Multi-agent backend ownership analysis (2453 lines). Subsequent dev-stack ownership model (lease-based, per CLAUDE.md and `tmp/dev-runner/active.json`) consumed the findings; the analysis served its role.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

