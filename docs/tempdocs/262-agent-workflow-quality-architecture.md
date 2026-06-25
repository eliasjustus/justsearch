---
title: "262: Agent Workflow Quality Architecture"
type: tempdoc
status: done
created: 2026-03-06
updated: 2026-03-07
---

> NOTE: Noncanonical umbrella tempdoc. Verify behavioral claims against canonical docs, code, and contract tests before promoting anything.

# 262: Agent Workflow Quality Architecture

## Purpose

Define the workflow-quality problem space for this repo, summarize the current evidence and repo
baseline, record the adopted architecture direction for the completed scoped program, and point to
the child tempdocs that hold the detailed investigations.

This tempdoc is now the umbrella strategy and index for this workstream. The detailed workflow
reliability, measurement, and stack-selection material has been split into dedicated child docs.

## Closure update (2026-03-07)

The scoped workflow-quality program described by this tempdoc is now complete:

- repo-local reliability closure is implemented in code and covered by targeted tests
- run-centric workflow telemetry and the scoped gating/descriptive metrics are implemented
- one saved direct comparison pair and one saved legacy comparison pair were generated at:
  - `tmp/workflow-quality-closure/2026-03-07T02-06-23-016Z/workflow-quality-closure-summary.json`
- the self-hosted Opik pilot ran successfully with repo-owned workflow join attributes preserved:
  - local stack state: `tmp/opik-local/state.json`
  - verification response: `tmp/workflow-telemetry/runs/workflow-otlp-pilot-1772849296090/opik-query-response.json`
- the Inspect AI plus Claude Code pilot ran successfully and preserved workflow evidence paths:
  - log: `tmp/inspect-ai/claude-workflow-pilot/2026-03-07T02-15-59+00-00_agent-cli-backend-lifecycle-pilot_5STX7Ao39THnHcgVdCPTdi.json`

Adopted decision for current scope:

- keep local NDJSON as the canonical evidence source
- support self-hosted Opik as an optional external backend
- support Inspect AI with Claude Code CLI as the default external workflow-harness path
- keep both external layers opt-in rather than default-on

## Promoted canonical docs (2026-03-07)

Stable outputs from this tempdoc set now live in canonical docs:

- workflow telemetry contract:
  `docs/reference/contracts/workflow-telemetry-contract.v1.md`
- workflow-quality operator runbook:
  `docs/how-to/validate-workflow-quality.md`
- lifecycle-family and canonical entrypoint policy:
  `docs/reference/contributing/dag-runner-operations.md`
- local-first workflow-quality observability decision:
  `docs/decisions/0010-local-first-workflow-quality-observability.md`

This tempdoc now remains as completed rationale/history rather than as the active policy source.

## Problem statement

Real agent sessions in this repo are not primarily limited by model quality. They are often limited
by workflow quality:

- lifecycle state drift between wrappers, subprocesses, and runtime truth
- preserved-state contamination across eval and investigation runs
- brittle shell and script surfaces, especially across Bash and PowerShell boundaries
- weak observability for "ready vs degraded vs busy vs stale" workflow states
- fragmented telemetry and evaluation layers that make workflow regressions hard to compare

The result is predictable waste: restart loops, port and PID forensics, quoting failures, stale
state confusion, compaction-heavy recovery work, and long partial sessions.

## Workflow taxonomy

The repo should treat these as separate workflow classes, not one generic "agent workflow" problem:

1. Backend-only agent and eval workflows
2. Full-stack local and MCP-backed dev workflows
3. Ad hoc manual and agent-driven PowerShell workflows
4. Offline workflow-quality analysis and comparative evaluation

The main architectural mistake would be forcing all four classes through a single launcher,
telemetry path, or success metric.

## Current repo baseline

The repo already has meaningful workflow-quality foundations:

- real local OpenTelemetry in product runtime
- local NDJSON traces and metrics
- W3C trace propagation across process boundaries
- a Claude-side workflow analytics pipeline under `scripts/agent-analytics`
- a second runtime agent-event store in `modules/app-agent`
- structured lifecycle and eval DAG runners
- a split lifecycle story:
  - `backend-launcher.mjs` and `eval-backend-lifecycle.mjs --engine direct` for backend-oriented flows
  - `dev-runner.cjs` and `dev-runner-lifecycle.mjs` for legacy or full-stack flows

The repo is therefore not missing telemetry from scratch. The central gap is fragmentation and
workflow-family drift.

## Evidence summary

The current evidence base combines direct transcript and code-path analysis with the existing
agent-analytics pipeline from tempdoc 118.

High-signal findings so far:

- workflow-specific failures are concentrated in investigation and evaluation sessions
- dev-stack and MCP-heavy sessions are materially longer, noisier, and more partial on average
- process-hygiene scores are useful for description, but not as the primary success metric
- the trigger session for this work was not an isolated anecdote; similar lifecycle and port
  forensics behavior appears repeatedly in retained workflow telemetry
- backend-only and full-stack flows already have different lifecycle needs inside the repo

That means the program should optimize for workflow correctness, isolation, and comparability
first, not for one composite workflow score.

## Preferred long-term direction

The best long-term solution is an OTel-native hybrid stack:

- canonical substrate: `OpenTelemetry`
- semantic layer: repo-owned versioned mapping aligned to `OpenInference` and OTel GenAI semantics
- default local sink: keep local NDJSON exporters
- shared observability and eval backend: `Opik`
- comparative workflow harness: `Inspect AI`
- optional offline trajectory mining: `PM4Py`

This fits the repo because it standardizes what already exists instead of replacing it, keeps the
local-first development model intact, and gives the project a credible path from transcript-heavy
local investigation to reproducible workflow-quality experiments.

## Historical implementation program

The implementation should run as four coordinated but separate lanes:

1. Governance lane
   - owner doc: `262`
   - outputs:
     - workflow taxonomy
     - decision status
     - success criteria
     - cross-lane sequencing rules

2. Reliability lane
   - owner doc: `263`
   - outputs:
     - repo changes
     - wrapper changes
     - progress and cleanup behavior changes
     - regression tests

3. Measurement lane
   - owner doc: `264`
   - outputs:
     - baseline metrics
     - validity constraints
     - workflow comparison rules
     - instrumentation requirements for lane 2 and lane 4

4. Stack and pilot lane
   - owner doc: `265`
   - outputs:
     - architecture comparison rubric
     - proof-of-concept integrations
     - adopt or reject decisions for external platforms

## Dependency rules

- `263` should not wait on `265`; most repo reliability fixes are no-regret changes.
- `264` should run before or alongside `263`; otherwise the repo ships workflow fixes without a
  stable way to compare before vs after behavior.
- `265` should consume requirements from `262` and `264`, not invent them independently.
- `262` should only be updated when a lane changes the program framing, success metrics, or adopted
  architecture direction.

## Historical implementation decisions

- Backend-only and eval workflows should converge on `backend-launcher.mjs` or
  `eval-backend-lifecycle.mjs --engine direct` wherever the UI is not actually required.
- Local NDJSON remains the default sink during the implementation program; external backends are a
  pilot path, not a prerequisite for workflow fixes.
- The existing lifecycle JSON envelopes, `/api/status`, and agent-analytics NDJSON are sufficient to
  start lane 2 and lane 3 work without waiting for new vendors or collectors.
- External stack work should begin as traces-only and pilot-scoped rather than as a repo-wide
  observability migration.

## Implementation status

The core repo-local program is now implemented in the tree:

- workflow-run evidence now exists under `tmp/workflow-telemetry/runs/<workflowRunId>/` with SSOT
  schemas and shared Node helpers
- the lifecycle adapter now emits additive envelope fields such as `schemaVersion`,
  `workflowRunId`, `workflowFamily`, `phase`, `phaseTimestamps`, `cleanup`, and
  `fallbackReadiness`
- lifecycle cleanup is normalized by artifact class:
  - `none` always clears runtime artifacts
  - `soft` preserves durable assets while clearing queue/runtime/log noise
  - `hard` recreates the data dir
- the remaining major DAG runners now route through `eval-backend-lifecycle.mjs`
- agent-safe search automation now exists in `scripts/search/run-search-workflow.mjs`
- `beir-eval-win.ps1` now supports `-PipelineFile` with mutual exclusion against `-Pipeline`
- `EvalSession.psm1` now delegates backend-only lifecycle ownership to the adapter
- workflow comparison now exists via `scripts/bench/compare-workflow-runs.mjs`
- MCP dev-server events can now bridge into workflow telemetry
- Java tracing now supports optional OTLP fan-out while retaining local NDJSON, and backend traces
  now carry repo-owned workflow resource attributes for joinability
- remaining ranking-experiment lifecycle execution now goes through the `EvalSession` /
  adapter-owned `installDist` path rather than a live manual launch path
- gating workflow metrics now include:
  - `indexServingMs`
  - `portsClosed`
  - `orphanProcessReaped`
- descriptive workflow metrics now include:
  - `manualForensicsCount`
  - `silentWindowMaxMs`
  - `silentWindowCount`
- external pilot tooling and real pilot results now exist for:
  - self-hosted Opik OTLP verification
  - Inspect AI plus Claude Code lifecycle evaluation

## Current status after implementation

- `263` reliability lane:
  - complete for the scoped repo-local goals in this program
- `264` measurement lane:
  - complete for the scoped workflow-quality contract in this program
  - run-centric telemetry, gating metrics, and the planned descriptive metrics
    (`manualForensicsCount`, `silentWindowMaxMs`, `silentWindowCount`) are implemented
- `265` stack and pilot lane:
  - complete for pilot validation and decision support in this program
  - self-hosted Opik and Inspect AI plus Claude pilots both ran successfully
  - current adopted stance is optional integration, not default-on infrastructure

## Cross-cutting theory decisions

### 1. Separate logical workflow identity from transport trace identity

- The repo should treat these as different concepts:
  - `workflowRunId`: canonical repo-owned join key for launcher, DAG, and workflow artifacts
  - `session_id`: external agent-session key when a Claude or Codex session drove the run
  - `trace_id`: OpenTelemetry or W3C transport trace identity when distributed tracing is in play
  - runtime-agent `TraceContext.traceId`: a repo-local event identity that may alias a session/run,
    but should not be assumed to be a W3C `traceparent` value
- This avoids overloading one ID across incompatible formats and keeps future OTel export aligned
  with the W3C Trace Context model rather than with ad hoc session IDs.

### 2. Reuse the existing readiness contract instead of inventing a new one

- The repo already has a stable typed readiness model in `/api/status.readiness` and `/api/health`.
- Wrapper scripts should add launch-phase fields such as:
  - process spawned
  - pid recorded
  - port discovered
  - http reachable
  - workload quiescent
- But they should not redefine what `READY`, `DEGRADED`, `NOT_READY`, `NOT_CONFIGURED`, or
  `UNKNOWN` mean.

### 3. Treat cleanup semantics as artifact-class policy, not as one `clean` switch

- The repo should define preservation and cleanup separately for:
  - runtime artifacts
  - queue and work-in-progress state
  - logs and crash debris
  - watcher metadata
  - index assets
  - datasets and corpora
  - models and training artifacts
  - user settings
- This is the only way to support both "preserve expensive assets" and "start from a clean runnable
  state" without ambiguity.

### 4. Keep privacy and export policy as first-class architecture constraints

- The repo already uses query redaction and exporter allowlists internally; workflow telemetry
  should extend those patterns rather than bypass them.
- Remote export should stay opt-in, additive, and local-first.
- Sensitive workflow content should remain excluded by default from propagated headers and remote
  backends.

## Success criteria

- Reliability:
  - backend-only workflows start and stop deterministically without manual PID or port forensics
  - controlled eval baselines are not contaminated by unrelated preserved work
- Measurement:
  - workflow runs are comparable using a stable run-centric evidence model
  - before vs after changes can be assessed without depending on one agent vendor or one composite
    score
- Architecture:
  - local NDJSON remains sufficient for local debugging
  - external backend use is optional and reversible

## Uncertainty resolution plan

Historical planning retained below. The current-scope uncertainties listed in this section were
resolved during implementation and pilot execution.

### Phase 1. Resolve repo-local uncertainty first

- Goal:
  - convert the highest-confidence parts of the program into testable repo behavior before touching
    external platforms
- Main owners:
  - `263` for lifecycle, cleanup, and invocation changes
  - `264` for run-centric measurement and evidence capture
- Exit criteria:
  - backend-only workflows have one canonical launcher family
  - controlled evals can run without preserved-state contamination by default
  - wrappers emit a normalized workflow-run event stream with stable join keys
  - the new behavior is covered by regression tests

### Phase 2. Resolve measurement uncertainty in parallel

- Goal:
  - prove that workflow improvements can be compared without relying on one agent runtime or one
    composite score
- Main owner:
  - `264`
- Exit criteria:
  - workflow-run telemetry exists with explicit schema and join rules
  - gating, descriptive, and exploratory metrics are operationalized separately
  - at least one before-vs-after comparison is run on the same workflow family

### Phase 3. Resolve migration uncertainty explicitly

- Goal:
  - prevent launcher-family changes from becoming implicit breakage
- Main owner:
  - `263`
- Exit criteria:
  - every important workflow is assigned to one lifecycle family
  - `EvalSession` has a documented adapter or compatibility role
  - full-stack holdouts are named explicitly rather than treated as accidental laggards

### Phase 4. Resolve external-stack uncertainty through pilots, not adoption-by-argument

- Goal:
  - validate whether the recommended external stack actually improves observability and workflow
    evaluation for this repo
- Main owner:
  - `265`
- Exit criteria:
  - Java runtime traces can fan out to a remote backend without displacing local NDJSON
  - one workflow-eval pilot can compare agent workflows using the repo's join model
  - privacy, export, and reversibility constraints are validated in practice

## Historical order of execution

1. Establish the workflow-run evidence model and join keys.
2. Normalize backend-only lifecycle behavior and cleanup semantics.
3. Add agent-safe invocation surfaces for eval workflows.
4. Run before-vs-after measurements on backend-only and eval workflows.
5. Only then run external backend and harness pilots.

## Historical uncertainty register and closure notes

### U1. Join model details

- Resolve by:
  - implementing the workflow-run event envelope first
  - mapping existing `session_id`, DAG `runId`, launcher `runId`, and runtime `TraceContext`
    into explicit fields instead of relying on aliasing
- Closed when:
  - the same workflow run can be traced across launcher events, DAG state, status snapshots, and
    agent telemetry

### U2. PowerShell migration scope

- Resolve by:
  - turning `EvalSession` into a compatibility layer or delegating adapter
  - measuring whether remaining direct PowerShell lifecycle ownership still provides unique value
- Closed when:
  - PowerShell eval flows no longer maintain a separate authoritative lifecycle model

### U3. Node-side OTel necessity

- Resolve by:
  - first implementing normalized NDJSON workflow telemetry in Node tooling
  - only adding direct OTel where NDJSON plus conversion is insufficient
- Closed when:
  - the repo can state clearly which Node surfaces need direct OTel and which do not

### U4. External platform selection

- Resolve by:
  - running narrow pilots against the repo's own success criteria
  - requiring reversibility and local-first coexistence
- Closed when:
  - one backend or harness materially reduces analysis friction compared with local-only evidence
- Current status:
  - resolved for current scope
  - self-hosted Opik was started locally and verified through the repo-managed verifier path
  - Inspect AI plus Claude Code completed a real backend-lifecycle pilot run with a passing score

### U5. Schema publication policy

- Resolve by:
  - defining one repo-owned workflow schema with additive evolution rules
  - using mapping and dual-write windows rather than direct breaking replacements
- Closed when:
  - new workflow telemetry can evolve without breaking local files, tests, or existing evidence

## Decision status

- Adopted in this completed program:
  - separate the work into governance, reliability, measurement, and stack-pilot lanes
  - treat backend-only and full-stack workflows as different lifecycle classes
  - keep NDJSON local-first as the canonical evidence source
  - support self-hosted Opik as an optional external backend
  - support Inspect AI plus Claude Code as the default optional external workflow harness
- Resolved for current scope:
  - the workflow-run / agent-session / OTel join model is explicit enough for repo-local telemetry
    and the external pilot runs
  - PowerShell eval tooling remains a compatibility layer rather than the authoritative lifecycle
    surface
  - Node-side direct OTel export is not required for phase 1 because the run-centric NDJSON layer
    plus Java OTLP fan-out is sufficient
  - repo-owned workflow telemetry uses additive schema evolution and remains the canonical contract
- Future follow-up questions outside current scope:
  - whether broader PowerShell retirement is worth doing beyond the current compatibility role
  - whether selected Node surfaces eventually justify direct OTel export
  - how the stable workflow contracts should be promoted into canonical docs or ADRs

## Post-hardening baseline correction

Recent repo commits on 2026-03-06 materially changed the implementation baseline for this tempdoc
set:

- `31d443d6 feat(eval): harden and centralize BEIR lane contracts`
- `9afee0dc scripts/bench: close eval core modernization gaps`
- `382cbc67 docs(tempdocs): record post-hardening phase 5 coordination`
- `40a50278 docs: close eval core modernization stream`

Implications for this program:

- The search-quality/eval lane is no longer starting from the pre-hardening state reflected in
  tempdoc 259.
- The eval lifecycle adapter already exists and is already the canonical lifecycle surface for the
  backend-only search-eval DAGs.
- BEIR metric-contract, runtime-gate, artifact-selection, and readiness-reporting work is already
  implemented in the repo head.
- Therefore the workflow-quality program should treat those commits as the current baseline and
  focus on:
  - extending and normalizing the existing adapter rather than inventing a new one
  - adding repo-wide workflow telemetry and cleanup semantics on top of the current search-eval
    hardening
  - separating "deprecate PowerShell lifecycle ownership" from the broader question of whether the
    PowerShell eval core itself should be replaced

## Child tempdocs

Detailed material now lives in these docs:

- `263-agent-workflow-reliability.md`
  - tactical repo-specific workflow friction
  - lifecycle drift, preserved-state contamination, eval isolation, shell ergonomics
- `264-agent-workflow-quality-evidence.md`
  - measurement semantics
  - tempdoc 118 relationship
  - analytics coverage, cohorts, and empirical findings
- `265-agent-workflow-stack-selection.md`
  - OSS tooling survey
  - current repo-tech baseline
  - long-term observability and evaluation stack recommendation

## Related tempdocs

- `118-agent-efficiency-research.md`
- `216-eval-harness-consolidation.md`
- `227-agent-quality-improvement.md`
- `230-agent-eval-quality-improvements.md`
- `254-mcp-dev-tools-issues.md`
- `257-backend-cold-start-investigation.md`

## Critical assessment

### What this doc does well

- It now behaves like a real umbrella doc rather than an overloaded working notebook.
- The workflow taxonomy is a useful correction to the earlier tendency to treat all agent work as
  one problem class.
- The current repo baseline and long-term direction are both concise enough to be reusable.

### Current weaknesses

- It remains a noncanonical umbrella tempdoc even after canonical promotion, so it should be kept
  as completed rationale/history rather than extended as an active policy document.
- It records the adopted optional external layers, but it still does not define hard SLO-style
  thresholds per workflow family.
- The historical planning sections are now useful as rationale, but they also make the document
  longer than an ideal steady-state architecture note.

### What would improve this doc next

- Leave this tempdoc in place as completed rationale/history rather than extending it further.

## Working note

Keep this tempdoc concise. New detailed findings should usually go to one of the child docs unless
they materially change the umbrella problem framing or long-term architecture recommendation.
