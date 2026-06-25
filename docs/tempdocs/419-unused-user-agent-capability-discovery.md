---
title: "Unused User / AI-Agent Capability Discovery"
type: tempdocs
status: active
created: 2026-04-26
---

# 419 - Unused User / AI-Agent Capability Discovery

## Status

**ACTIVE DECISION SUMMARY / INVESTIGATION LOG.** Created 2026-04-26. The
current recommendation lives at the top of this document. The appendix
preserves the running record for the autonomous discovery workflow that
identified capabilities recent tempdocs and current code already enable, or
nearly enable, but that users or AI agents do not currently receive as
first-class product features.

Scope excludes release and runtime-resilience work. It also excludes
`docs/future-features/` as a source of feature claims. A future-feature doc
may coincidentally describe the same pattern, but this investigation only
counts evidence from current code, canonical docs, and tempdocs.

## Goal

Find currently unused or under-surfaced capabilities that are:

1. already implemented in code but hidden behind no UI, no MCP tool, no
   obvious API path, weak discoverability, or disabled defaults;
2. theoretically enabled by recent architectural substrate with a small
   product slice, without requiring a new foundational refactor; or
3. present as internal diagnostics/contracts that could become useful
   user-facing or agent-facing workflows.

The output should not be "more features because they are possible." The
output should separate real latent product value from tempting-but-premature
surface area.

## Ownership annotations (2026-04-26)

The following candidates are taken over by tempdoc 415's follow-up work
+ tempdoc 420's design stub (created 2026-04-26 alongside the 415 ship):

| Candidate | Owner | Status |
| --- | --- | --- |
| **C4** Authoritative Session Policy Inspector | Tempdoc 422 (backend slice) | SHIPPED 2026-04-27 — read-only `GET /api/inference/encoders` derived explainer reading `OrtCudaView` + `PolicySnapshot`; live-stack curl evidence in 422 § Validation evidence (2026-04-28). Tier 2 trend data + frontend slice deferred to follow-ups |
| **C20** Agent Session Resume and Replay | Tempdoc 420 (design stub) | OWNED — full implementation deferred until prioritized; substrate (typed `terminationReason`, schema v3, per-session metrics) shipped via 415 |
| **C28** Notification-to-Session Continuity | Tempdoc 420 | OWNED — bundled into 420's `ConversationId`/`RunId` decomposition |
| **C33** Agent Session Transcript Export | Tempdoc 420 | OWNED — `GET /api/agent/conversations/{id}` surface designed in 420 |
| **C43** Workspace Operation Timeline | Tempdoc 420 | OWNED — `GET /api/agent/conversations` enumeration designed in 420 |
| **C44** Agent History Contract Alignment | Tempdoc 415 follow-up | OWNED — taken over as a one-line P0 hygiene fix (rename `failureCount` → `failedCount` in `AgentLoopService.toBatchSummary`); blocks 420's "history as audit surface" claim per 419's own P0 gate |

The remaining candidates (C12/C14/C30/C31/C32/C49/C50/C52/C54/C55
scope-to-AI bridges; C15/C24/C26/C34/C37/C38/C39/C41/C42 evidence/trust;
C23/C45/C46/C51 write-agent UX; C5/C19/C25/C29/C36/C47/C53 capability
disclosure; C27/C40 document intelligence) are NOT claimed by 415/420/422
and remain available for other tempdocs.

## Current Decision Summary

The strongest latent product direction is **scope-aware AI and workspace
action**, not generic diagnostics. The investigation found many useful
internal surfaces, but the highest-value gap is that JustSearch already knows
where the user is working and what evidence exists, while AI entry points are
still too detached from that scope.

The product chain should be treated as:

1. **Library** decides what exists in the workspace.
2. **Browse** decides where the user is working.
3. **Search** decides what evidence is relevant.
4. **Inspector** verifies and explains.
5. **Agent** acts with approvals.
6. **Timeline/Undo** lets the user review and recover.

This workflow deliberately excludes release, installer, CI, and
runtime-resilience work. It also excludes `docs/future-features/` as a source
of feature claims. Items from those areas only remain as historical evidence
when they help explain current user/agent capability boundaries.

The current answer is not "surface every hidden endpoint." The current answer
is to connect existing scope, evidence, action, and audit surfaces into
coherent user and agent workflows.

## Consolidated Feature Map

The original investigation produced candidates `C1` through `C55`. Those IDs
are retained for traceability, but they should no longer be treated as
separate work items. The decision unit is now the consolidated theme.

### Scope-to-AI Bridges

**Candidate IDs:** `C12`, `C14`, `C30`, `C31`, `C32`, `C49`, `C50`, `C52`,
`C54`, `C55`.

Make AI entry points honor the user's current scope and the UI's stated
promises. This includes command/action Ask wiring, Browse Ask/Summarize,
folder and selection scope, default Enter action correctness, mode-aware
suggestions, and live help/shortcut consistency.

**Disposition:** Highest-priority product bridge. Fix the promise/correctness
gaps first, then wire scoped Ask/Summarize flows.

### Evidence and Trust Loop

**Candidate IDs:** `C15`, `C24`, `C26`, `C34`, `C37`, `C38`, `C39`, `C41`,
`C42`.

Turn existing preview, citation, query-understanding, and full-document
retrieval substrate into a clear verification path. Users and agents should be
able to see why evidence was selected, inspect the relevant document slice,
and escalate from snippets to preview/full-document context when scope allows.

**Disposition:** High-priority trust work after the first scope bridges. MCP
preview and Inspector citation verification are the clearest concrete slices.

### Continuity and Auditability

**Candidate IDs:** `C20`, `C28`, `C33`, `C43`, `C44`, `C48`.

Productize the fact that agent sessions, operation history, notifications,
and undo already exist as separate pieces. The useful feature is a workspace
timeline: what happened, what failed, what can be resumed, what can be
reviewed, and what can be undone.

**Disposition:** Important second wave. Fix the agent history contract
mismatch before relying on history as an audit surface.

**Status (2026-04-28):** five of the six candidates shipped or formally
deferred via tempdoc 415's follow-up plan (see
`docs/tempdocs/412-observability-pattern-adoption.md` §"419 follow-up —
shipped 2026-04-28"):

- **C20** — *shipped*. Backend list/detail/resume-by-id endpoints +
  frontend `'sessions'` tab with Resume buttons.
- **C28** — *deferred behind C20 product feedback*. Tauri notification
  deep-link plumbing tracked in `docs/observations.md`.
- **C33** — *V1 shipped*. Bundled meta+events transcript download via
  `Content-Disposition: attachment`. V2 redaction-aware export deferred
  pending a redaction policy.
- **C43** — *V1 shipped*. Client-aggregated workspace timeline (fourth
  `'timeline'` tab in `AgentView`). SessionId-on-batches join (V2)
  deferred — tracked in `docs/observations.md`.
- **C44** — *shipped*. Backend rename `failureCount` → `failedCount` +
  Zod schema lock-in. Restored the failed-operation badge previously
  silently broken.
- **C48** — *not yet started*. Remains in the queue.

### Safe Write-Agent UX

**Candidate IDs:** `C23`, `C45`, `C46`, `C51`.

Before broadening Organizer or file-changing agent behavior, approval cards
must show typed user-level diffs instead of raw tool JSON. Library exclude
previews are a good review-before-apply pattern, but write-agent expansion
should wait until approval and timeline UX are stronger.

**Disposition:** Prerequisite for any broader write-agent workflow.

### Capability and Disclosure Model

**Candidate IDs:** `C5`, `C19`, `C25`, `C29`, `C36`, `C47`, `C53`.

JustSearch has multiple capability surfaces: built-in agent tools, MCP tools,
readiness state, disclosure levels, and system-state diagnostics. The useful
product form is not a raw diagnostics panel; it is a capability explanation
layer that tells users and agents what is available, why something is
disabled, and what risk tier an action belongs to.

**Disposition:** Strategic design layer. Use the unused Power tier as a lens
for scope/trust controls, not as a dumping ground for diagnostics.

### Document Intelligence and Library Hygiene

**Candidate IDs:** `C27`, `C40`, `C51`, plus suggested-roots and onboarding
findings.

VDU enrichment, suggested roots, and exclude previews are real latent value,
but they are supporting workflows. They become strongest when attached to
scope-aware Ask, evidence verification, and safe review-before-apply patterns.

**Disposition:** Defer behind scope, trust, and approval work unless a narrow
Library/Browse slice is already being touched.

## Recommended Priority Queue

### P0 - Correctness and Promise Fixes

- Honor the configured default action everywhere keyboard/file activation is
  handled.
- Align agent history failure field naming before building more history UI.
- Make help and shortcut copy match real command/action wiring.

### P1 - Product Bridges

- Wire command and action Ask flows so `??`, `/help`, action-panel Ask, and
  help-to-action behaviors are real rather than aspirational.
- Wire Browse Ask/Summarize for selected files and obvious folder scope.
- Treat Browse folders and selections as first-class scope sources, not just
  as file tree navigation.

### P1 - Trust Loop

- Expose preview-backed citation verification in Inspector.
- Add production MCP preview support where schema/substrate already exists.
- Preserve query-understanding and filter-normalization information at the UI
  boundary when it helps explain evidence selection.

### P2 - Continuity

- Productize agent session resume/replay and transcript export.
- Combine operation history, notifications, undo, and root-relative paths into
  a workspace timeline.
- Fix history contract alignment before presenting failed/partial batches as
  authoritative audit data.

### P2 - Safe Write-Agent Work

- Replace raw approval JSON with typed approval/diff cards.
- Keep broad Organizer expansion behind approval, diff, and timeline
  hardening.
- Use exclude preview as the model for review-before-apply workflows.

### P3 - Product Architecture

- Use the reserved Power disclosure tier for scope/trust controls that are too
  detailed for Simple but not diagnostic enough for Advanced.
- Build capability explanation from real tool/readiness state rather than
  static help text.

## Candidate Supersession Map

| Old IDs | Consolidated Theme | Disposition |
| --- | --- | --- |
| `C12`, `C14`, `C30`, `C31`, `C32`, `C49`, `C50`, `C52`, `C54`, `C55` | Scope-to-AI bridges | Current top priority; start with correctness/promise gaps and Browse/command Ask wiring. |
| `C15`, `C24`, `C26`, `C34`, `C37`, `C38`, `C39`, `C41`, `C42` | Evidence and trust loop | High priority after scope bridges; preview/citation verification is the clearest slice. |
| `C20`, `C28`, `C33`, `C43`, `C44`, `C48` | Continuity and auditability | Productize as workspace timeline/resume/transcript work; fix contract mismatch first. |
| `C23`, `C45`, `C46`, `C51` | Safe write-agent UX | Approval diffs and review-before-apply must precede broader write-agent expansion. |
| `C5`, `C19`, `C25`, `C29`, `C36`, `C47`, `C53` | Capability and disclosure model | Strategic layer; use to explain available actions and risk tiers. |
| `C27`, `C40`, `C51`, suggested-roots/onboarding findings | Document intelligence and library hygiene | Supporting track; strongest when attached to scope/trust/review flows. |
| `C1`, `C2`, `C3`, `C4`, `C6`, `C7`, `C8`, `C9`, `C10`, `C11`, `C13`, `C16`, `C17`, `C18`, `C21`, `C22`, `C35` | Folded, tactical, or deprioritized findings | Keep as evidence; promote only when they directly support one of the six themes. |

## Superseded and Deprioritized Items

The following are not primary work for this discovery workflow:

- Diagnostics export as a general user/agent feature.
- Dynamic logging as a product surface.
- Runtime reload or hot-reload product features.
- Generic time-series or MetricCatalog explorers.
- Broad policy/admin/AI-pack surfacing beyond already-visible Brain surfaces.
- Release, installer, CI, and runtime-resilience-adjacent work.

These items may still be useful as support evidence, advanced diagnostics, or
internal tooling. They should not drive the user/agent feature roadmap unless
they directly explain a capability, disabled state, or scoped recovery action.

## Long-term Substrate Decisions for Ingestion-Ledger Productization

Added 2026-04-26 by the agent who landed tempdocs 410+418 (the substrate that
WP1 / WP4 / Slice C question depend on). The original 419 investigation framed
the substrate as "ready to productize"; this section pins the *long-term*
shape of that productization for backend work, separating it from
"smallest-risk now" pragmatic answers. Each entry names the architecturally
correct destination, the trade-off vs the pragmatic short-term choice, and the
condition that would force the long-term answer to become the actual choice.

The pattern across all six items: prefer **substrate over per-feature fixes**
and **refined contracts over absolute prohibitions**. Substrate investments
compound across every later product feature; absolutes that don't match
product direction force the same difficult decisions to be re-litigated for
each new surface.

### Implementation status (updated 2026-04-28)

All 8 planned PRs have shipped. Substrate is live on `main`.

| Substrate item | Slice | Status | Commit |
|---|---|---|---|
| S1 — Drawer shape (frontend doc) | T1 / PR1 | ✓ shipped | `2cb646c39` |
| S3 — Per-scan IDs (proto + worker + MDC) | T2 / PR2 | ✓ shipped | `5a50fe045` |
| S4 — Substrate cancel propagation (`CancelToken`) | T3 / PR3 | ✓ shipped | `62d16ca50` |
| S5 — ADR-0028 (proposed → accepted) | T5.0 / PR5 | ✓ accepted | `13f5291ee` → `96458f3b8` |
| S5 — Path resolution implementation | T5.1–T5.6 / PR6 | ✓ shipped | `96458f3b8` |
| S6 — Lite mode env flag + how-to doc | T6.1 / PR7 | ✓ shipped | `e5331396f` |
| S2 — SSE event channel + scan-progress endpoint | T4 / PR4 | ✓ shipped | `7ab5fab5a` |
| S6 — Isolated test backend fixture + tests | T6.2–T6.4 / PR8 | ✓ shipped | (this commit) |

The HTTP→gRPC cancel gap noted in observations.md is now closed at the SSE
layer: SSE close → `ScanProgressRegistry.cancel(scanId)` → `CancelToken.cancel()`
→ gRPC CANCELLED → worker scan terminates with `CLIENT_CANCELLED`. The
`KnowledgeIngestResponse` carries the worker-allocated `scanId` so callers
can subscribe immediately after the synchronous ingest call returns.

ADR-0028 (`docs/decisions/0028-scoped-reverse-path-lookup.md`) is accepted
and the contract refinement is live in `docs/explanation/03-knowledge-server.md`.
The structural `ingestionEventViewExportContractIsPinned` and the new
`LibraryResolveHashOnlyCallerPin` (ArchUnit) both enforce the privacy
contract as code, not promise.

PR8 shipped 2026-04-28 in a focused session. `IsolatedBackendFixture`
spawns `HeadlessApp` in a child JVM with `JUSTSEARCH_LITE_MODE=true`, fresh
tempdir data dir, and OS-assigned port; full readiness is reached in
~3.4 s (port file → `/api/health` 200 → `components.worker.state=READY`).
`IngestionDiagnosticsContractTest` pins both T6.3 (privacy invariant +
extraction-honesty fields queryable) and T6.4 (T5 resolver returns the
correct path for an admitted file; diagnostic export still does not leak
paths even with the resolver wired). Five consecutive `--rerun-tasks`
runs all green; no leaked tempdirs.

The two design refinements the PR8 spike surfaced both proved essential
in implementation: `@argfile` for the child JVM's classpath (Windows
8191-char cmdline limit) and ledger-based readiness for the privacy /
resolver tests (the embedding ONNX session loads on first search, exceeds
the 5 s gRPC search deadline, trips the `GrpcCircuitBreaker`, and would
make search-based readiness checks fail for the second-and-subsequent
tests in the class). The dedicated search test (extraction provenance
fields projection) tolerates that warm-up by retrying on
`HttpTimeoutException` until the model is warm.

### S1 - Per-event drawer in Library activity (long-term)

The drawer should exist. The hash should be hidden. The visible fields are
the human-meaningful ones already in `IngestionEventView`: source kind,
size, modification time, plain-English reason, retry policy, when the event
was observed. The "which exact file?" question is **separated** from the
drawer design and handled by S5 below.

Short-term answer (no drawer at all) is fine for V1. Long-term, the drawer
becomes the natural support/diagnostic affordance once friendly labels exist
for every reason code.

### S2 - SSE event channel for live state, polling reserved for snapshots

Long-term, every "tell me when something changes" workflow uses SSE: scan
progress, ingest progress, agent action progress, AI streaming responses.
"Give me current state" workflows continue to use polling. Local-first
desktop deployment makes the usual SSE concerns (connection limits, reconnect
storms) negligible — one user, one tab, low fan-out.

Short-term polling for WP4 is faster to ship the first time, but every future
streaming feature pays the same "polling again?" cost. Establishing SSE in
Javalin is a one-time ~1-day investment that all subsequent streaming
features inherit.

### S3 - Per-scan IDs as the canonical identifier, aggregator as derived view

Worker already generates a `request_id` per gRPC scan (visible in worker.log
on every scan-related entry). Promote that to a first-class `scanId` exposed
in `ScanRootProgress` and through the Head HTTP layer. "All active scans" is
then `SUM(scans-by-id)` — derivable, not primary.

This is the simpler architectural shape long-term: single source of truth,
no special case for "the only scan" vs "multiple scans." Per-scan IDs unlock
cancel, retry, per-root display, and per-scan debugging without any further
substrate work.

### S4 - Substrate-level HTTP→gRPC cancel propagation

The cancel gap documented in observations.md (2026-04-26) is not specific to
scan. Every gRPC call from `RemoteKnowledgeClient` has the same gap:
HTTP-client abort never reaches the gRPC stream. Long-term, the fix is a
single wrapper that opens a `Context.CancellableContext` per HTTP request and
propagates Javalin's connection-closed signal to all downstream gRPC calls
in the request's call tree.

Short-term per-endpoint fixes (the path I scoped for WP4 cancel) compose to
3-4x the work over time. The substrate fix is roughly 2x the per-endpoint
fix the first time but eliminates the gap for every subsequent long-running
endpoint (`ingest`, `recentIngestionEvents`, long-running `search`,
`retrieveContext`, agent action streams).

### S5 - Privacy contract refinement: scoped reverse-lookup with explicit
export exemption

The current contract — "raw paths never cross the Worker→Head boundary" —
is too blunt for the long-term product. The right contract distinguishes
**exportable surfaces** (diagnostic ledger, summary, support-bundle export)
from **owner-display surfaces** (user clicks "show filename" in the Library
activity panel for a hash currently under a watched root).

Concretely:

- New Worker RPC `LookupPathByHash(pathHash) -> Optional<Path>` returns the
  path only if the hash corresponds to a file under a currently-watched root
  the requesting Head session has authority for.
- `/api/diagnostics/ingestion/recent`, `/summary`, and any future
  `/api/diagnostics/export` continue to return hashes only — the resolver
  RPC must not be reachable from those endpoints.
- A new endpoint such as `POST /api/library/resolve-hash` is the *only*
  caller of the resolver RPC, invoked in direct response to user action.
- The contract document gets an explicit "exemption" section listing which
  surfaces may resolve hashes; a structural test enforces that the export
  endpoints do not transitively call the resolver.

Short-term answer ("V1 says no") is correct as a holding position but leaves
the most useful product question permanently unanswered. Long-term refinement
preserves operator-export safety while unlocking the user's natural "which
file failed?" question.

This is the **only one of the six that needs an ADR before code**, because
it's a deliberate contract change rather than a refactor.

### S6 - Per-class isolated backend lifecycle for system tests, with a "lite"
mode

Today's integration tests run against a shared backend with shared state, so
write-side tests (anything that mutates the index, ledger, or queue) are
fundamentally flaky against background activity. Long-term, every system
test class spins up its own backend in a child process with a fresh
`JUSTSEARCH_DATA_DIR`, a free port, and a clean Lucene index. Per-class (not
per-test) amortizes the ~30s startup. A "lite" startup mode that skips
embedding/SPLADE/NER session loading drops to ~5s for tests that don't
exercise AI.

This is the only path to reliable end-to-end tests of ingestion contracts
(privacy invariant, ledger redaction, env-driven skip end-to-end). The
short-term answer (defer until WP1 starts) is fine if WP1 isn't imminent;
long-term the harness pays back across every system-test addition.

### Sequencing summary

| Substrate item | Cost | Pre-WP1/WP4 sequencing |
|---|---|---|
| S1 drawer shape | Same as short-term; UI design only | Decide before WP1 ships |
| S2 SSE pattern | +1 day vs polling | Establish before first streaming feature |
| S3 per-scan IDs | Marginal | Land with WP4 substrate |
| S4 substrate cancel | +2-3 hours vs per-endpoint | Land with WP4; pays back the next time |
| S5 contract refinement | 1-2 days + ADR | Separate tempdoc; do not bundle with WP1 |
| S6 test harness | 2-3 days | Optional pre-WP1; required pre-S5 |

If the next 1-2 sprints can spend ~1 week on substrate (S2 + S3 + S4 + S6
done together), WP1 and WP4 can ship cleanly on top. If WP1 needs to ship
next week, the short-term answers are the right pragmatic call and these
substrate items become their own follow-up tempdocs. S5 is intentionally
out of WP1/WP4 scope either way — it's a contract decision that needs
explicit user direction.

## Appendix - Investigation Log

Everything below is the historical investigation record. It preserves evidence
and pass-by-pass reasoning, but pass-local rankings are superseded by the
current decision summary, consolidated feature map, and recommended priority
queue above.

### Original Investigation Plan

#### Phase 0 - Define the Discovery Lens

Create a strict classification before collecting findings, so the document
does not become a grab bag.

Candidate classes:

- **Implemented-hidden**: production code exists; no first-class UI/MCP/user
  workflow exposes it.
- **Implemented-gated**: code exists and is reachable only through env flags,
  debug endpoints, eval flags, or manual scripts.
- **Agent-visible-but-undiscoverable**: a capability exists in MCP/API output
  but agents rarely choose it or cannot verify it.
- **Substrate-enabled**: recent architecture makes a product slice small
  enough to consider, but no feature code exists yet.
- **Diagnostic-only**: telemetry/status/provenance exists for engineers and
  could become an operator/user/agent feature.
- **Not a feature**: useful internally, but surfacing it would create more
  confusion than value.

For every candidate, record:

- user or agent persona;
- evidence source;
- current implementation state;
- why it is unused or underused;
- activation cost;
- risk / confusion cost;
- likely product payoff;
- recommended disposition.

#### Phase 1 - Broad Inventory

Inventory current surfaces and compare them against recent capability claims:

- REST endpoints and controllers in `modules/ui`;
- gRPC proto services in `modules/ipc-common`;
- React views and visible controls in `modules/ui-web`;
- MCP tools in `scripts/prod/justsearch-mcp`;
- config flags and debug/admin endpoints in `EnvRegistry`, `ConfigKey`, and
  status records;
- jseval commands and workflows that represent agent/operator capabilities;
- recent tempdocs 330-418, excluding release/resilience and future-features.

The goal is breadth first: build a candidate table before deciding which
items deserve deep reads.

#### Phase 2 - Cross-Surface Gap Analysis

For each candidate, compare four layers:

1. **Capability exists**: code, endpoint, service, or substrate.
2. **Human surface exists**: UI control, settings page, status view, help text.
3. **Agent surface exists**: MCP tool, response field, recovery hint, schema.
4. **Verification path exists**: test/eval/smoke command proving it works.

A capability is "unused" when layer 1 exists but layers 2 or 3 are missing
or ineffective.

#### Phase 3 - Deep Dives

Deep-read only the strongest clusters from Phase 1/2. Expected clusters:

- search precision and query-understanding affordances;
- agent-facing retrieval workflows and diagnostics;
- runtime/session/telemetry status that could become operator guidance;
- hot-reload/admin lifecycle features enabled by 406/417/418;
- model/session/install capabilities that exist but are hidden or hard to
  reason about.

Each deep dive should end with a disposition: ship, experiment, document,
defer, or reject.

#### Phase 4 - Synthesis

Rank candidates by:

- **Latent value**: how much user/agent capability already exists unused.
- **Activation effort**: how much new code/product design is needed.
- **Risk**: how likely surfacing it creates false confidence, user confusion,
  instability, or maintenance burden.
- **Strategic fit**: whether it advances JustSearch's local-first,
  structured, agent-friendly retrieval identity.

#### Phase 5 - Update Loop

As evidence improves, update this tempdoc in-place. Do not wait until the
end to write. Each investigation slice should leave behind a durable note
with the commands or files inspected.

### Initial Hypotheses

These are hypotheses to test, not findings:

1. Recent work created more internal truth than external affordance:
   MetricCatalog, nested status records, encoder profiles, runtime gauges,
   workflow signal policy, and build stamps may all be useful to agents but
   are probably not exposed as workflows.
2. Search has several high-value latent features: structured metadata filters,
   per-source retrieval, source-aware context labels, excerpt highlighting,
   scoped keyword search, full-document retrieval, and filter normalization.
   Some are implemented; agent discoverability may lag behind capability.
3. 406/418 likely enabled user-visible lifecycle/admin actions, but the safe
   product form may be narrower than the substrate suggests.
4. The biggest product wins are probably not new backend primitives. They are
   surfaces that let users and agents understand and steer existing retrieval.

### Evidence Log

#### Slice A - Surface Inventory (2026-04-26)

Read current route registration, proto contracts, UI views, MCP schemas, and
recent tempdocs. The main evidence sources were:

- `modules/ui/src/main/java/io/justsearch/ui/api/routes/*Routes.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/AgentRoutes.java`
- `modules/ipc-common/src/main/proto/indexing.proto`
- `modules/ui-web/src/components/views/{AgentView,LibraryView,HealthView,BrainView}.tsx`
- `modules/ui-web/src/api/domains/{agent,indexing,search,packs}.ts`
- `scripts/prod/justsearch-mcp/{server,schemas}.mjs`
- `modules/app-agent/src/main/java/io/justsearch/agent/tools/*.java`
- tempdocs 311, 362, 366, 374, 397, 400, 412-418

Notes:

- `docs/future-features/` was deliberately not used as evidence, even when
  recent tempdocs referenced it.
- Release/distribution content from tempdoc 374 was read only to understand
  current product surfaces such as AI install/runtime/policy/diagnostics. Its
  release-channel and installer readiness claims are out of scope for this
  investigation.
- The only tempdoc below 350 modified in the last seven days was
  `311-gpu-memory-partitioning.md`; it matters here because it introduced
  GPU-session recovery and policy questions that later became structured
  session-policy diagnostics in 397.

#### Slice B - Immediate Surface Map

Implemented REST/API surfaces with potential latent feature value:

- Agent endpoints:
  - `POST /api/agent/run/stream`
  - `POST /api/agent/approve`
  - `POST /api/agent/reject`
  - `GET /api/agent/tools`
  - `GET /api/agent/session/last`
  - `POST /api/agent/session/resume-last/stream`
  - `GET /api/agent/session/{id}/events`
  - `DELETE /api/agent/session/{id}`
  - `POST /api/agent/undo`
  - `GET /api/agent/history`
  - `GET /api/agent/history/{batchId}`
- Knowledge/retrieval endpoints:
  - `POST /api/knowledge/search`
  - `GET /api/knowledge/status`
  - `POST /api/knowledge/ingest`
  - `GET /api/knowledge/suggest`
  - `POST /api/knowledge/folders`
  - `POST /api/knowledge/folder-files`
  - `POST /api/knowledge/retrieve-context`
  - `POST /api/knowledge/match-citations`
- Indexing/operator endpoints:
  - roots, suggested roots, add/remove roots, reindex, excludes apply;
  - migration start/cutover/rollback/pause/resume;
  - index GC;
  - failed jobs list/clear;
  - privacy-safe ingestion ledger reads:
    `/api/diagnostics/ingestion/recent` and
    `/api/diagnostics/ingestion/summary`.
- Debug/observability endpoints:
  - `/api/debug/state`, `/api/debug/commit-metadata`,
    `/api/debug/effective-config`, `/api/debug/events`,
    `/api/debug/worker-log`, `/api/debug/dashboard`,
    `/api/debug/chunks`;
  - dynamic logging: `/api/debug/logging`;
  - RRD metric queries:
    `/api/debug/metrics/timeseries` and
    `/api/debug/metrics/timeseries/available`;
  - authoritative Worker session policy:
    `/api/debug/session-policies`;
  - reset index and admin runtime reload.
- AI/runtime/policy endpoints:
  - summarize, ask, hierarchical summarize, preview;
  - install manifest/status/start/cancel/repair;
  - AI pack status/installed/preflight/import;
  - runtime status/activate/deactivate;
  - inference status/mode/reload/detach;
  - offline processing and worker restart;
  - effective policy, policy validation, user policy creation,
    allowlist add, diagnostics export.

Implemented gRPC substrate that is richer than visible product workflows:

- `SearchService`: `Search`, `Suggest`, `FetchDocuments`,
  `FetchDocumentSlice`, `RetrieveContext`, `MatchCitations`, folder listing,
  all-doc-id listing, and rerank.
- `IngestService`: batch submit/status/delete/prune/sync, VDU pending and
  recovery, migration controls, index GC, failed jobs, reset index, session
  policies, runtime reload, recent ingestion events, ingestion outcome summary,
  `ScanRoot`, `WatchRoot`, and `UnwatchRoot`.

UI findings:

- `AgentView` already exposes built-in agent chat, history, tool approval,
  rejection, cancellation, and undo. It starts sessions with built-in
  profiles and fixed `maxIterations: 10`.
- `LibraryView` exposes roots, reindex, exclude preview/apply, manual/Tauri
  folder add, and background indexing-complete notifications. It does not
  appear to consume privacy-safe ingestion event/summary endpoints or
  streaming `ScanRootProgress`.
- `HealthView` exposes status, reindex, worker restart, rebuild migration,
  failed-job list/clear, and derived recent health events from `/api/status`.
  It does not appear to consume the time-series endpoints, session-policy
  endpoint, ingestion ledger endpoints, or dynamic logging endpoint.
- `BrainView` is a real surface for AI install, pack import, policy helper,
  runtime variants, and simple/advanced runtime controls. This is less
  "unused" than initially hypothesized; the hidden parts are more about
  diagnostics and explainability than basic controls.

MCP findings:

- Production MCP currently registers four tools:
  `justsearch_answer`, `justsearch_search`, `justsearch_ingest`,
  `justsearch_status`.
- The MCP schema contains rich retrieval controls: structured filters,
  `boostFilters`, `includeExcerpts`, facets, `doc_ids`,
  `return_full_documents`, `context_format`, automatic entity filtering,
  and optional citation verification folded into `justsearch_answer`.
- Tempdoc 366's eval history shows that some of these controls remain low-use
  even after hints: entity filters, `boostFilters`, `includeExcerpts`, and
  `return_full_documents` are implemented but are not reliably discovered by
  agents.

Built-in agent findings:

- Built-in Java tools are: `search_index`, `browse_folders`, `ingest_files`,
  and `file_operations`.
- Built-in agent has capabilities MCP deliberately lacks: path-sandboxed file
  move/rename/copy/mkdir, transaction logs, approval gates, and undo.
- MCP has capabilities the built-in agent lacks or under-exposes: answer-level
  retrieval, citation verification, facets/boost filters/full-document
  retrieval, and richer search response hints.
- Tempdoc 200 already called this split intentional, but the current codebase
  makes the split more product-relevant: both systems now look mature enough
  that their asymmetry is itself a feature-discovery problem.

#### Slice C - Deep Read: Ingestion Activity

Files read:

- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteJobQueue.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueTest.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java`
- `modules/ui-web/src/api/domains/indexing.ts`
- `modules/ui-web/src/hooks/useSources.ts`
- `modules/ui-web/src/stores/useSystemStore.ts`

Key findings:

- The ingestion ledger is more product-ready than a raw debug table. It
  returns an export-safe `IngestionEventView` with fields:
  `id`, `pathHash`, `collection`, `outcomeClass`, `reasonCode`,
  `retryPolicy`, `diagnosticSummary`, `observedAtMs`, `sourceSizeBytes`,
  `sourceModifiedAtMs`, `sourceKind`, `artifactStatus`, `policyId`,
  `parserId`.
- Tests explicitly prevent raw path leakage. `JobQueueTest` verifies that the
  event view has `pathHash` and not `path`, `filePath`, `originalPath`,
  `rawPath`, or `absolutePath`. It also pins the exact export field set and
  tells future contributors to update the canonical redaction contract if it
  changes.
- `SqliteJobQueue.ingestionOutcomeSummary` groups by
  `outcome_class`, `reason_code`, and `retry_policy`, with `count` and
  `last_observed_at`. This is the correct primary UI shape; it avoids showing
  users a list of unactionable hashes.
- `RemoteKnowledgeClient` maps gRPC events to simple JSON-compatible maps, so
  a frontend API wrapper would be straightforward.
- `indexing.ts`, `useSources`, and `useSystemStore` do not currently define
  ingestion ledger types or fetch these endpoints. The Library stack only
  knows roots, suggested roots, excludes, migration, and failed jobs.

Conclusion:

The first user-facing slice should not be a "ledger viewer." It should be an
**Indexing activity summary**:

- top rollups by outcome/reason for the last 24h or all retained events;
- plain explanations for common reasons like unchanged, skipped by policy,
  cloud placeholder, extraction failed, budget exceeded;
- link to failed jobs when the rollup represents retryable/permanent failures;
- optional advanced recent-event drawer with hashes, not shown as the main
  affordance.

Open product question:

- Can Head map `pathHash` back to a safe displayed filename for files that are
  still under watched roots without violating the 410 privacy contract? The
  current tests deliberately avoid raw-path export, so V1 should assume "no."

#### Slice D - Deep Read: Built-In Agent Retrieval Parity

Files read:

- `modules/ui/src/main/java/io/justsearch/ui/api/RetrieveContextController.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java`
- `modules/app-agent-api/src/main/java/io/justsearch/agent/api/ToolDefinition.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/AppFacadeBootstrap.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/tools/SearchTool.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/tools/BrowseTool.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/tools/FileOperationsTool.java`

Key findings:

- `RetrieveContextController` is not just an MCP shim. It parses filters,
  `doc_ids`, context format, `return_full_documents`, filter normalization,
  context sufficiency, quality signals, and citation chunks over
  `appFacade.documents().retrieveContext(...)`.
- The built-in agent tool SPI is simple: `ToolDefinition` only needs name,
  description, JSON schema, safety level, execute, and optional undo.
- `AppFacadeBootstrap` already wires tools through an in-process
  `ToolRegistry`. It registers safe read-only tools (`search_index`,
  `browse_folders`) and gated write tools (`file_operations`, `ingest_files`).
- The built-in agent currently uses `KnowledgeHttpApiAdapter` for search,
  browse, ingest, and scan-root. It does not register a retrieve-context /
  answer-style tool.
- `AgentLoopService.DEFAULT_SYSTEM_PROMPT` explicitly tells the model to
  always call `search_index` for factual knowledge-base questions. That makes
  the built-in agent biased toward the lower-level search path even when an
  answer-style context assembly would be better.

Conclusion:

The smallest strong parity slice is **not** "make the built-in agent use MCP"
and not "port all MCP schema knobs." It is a new read-only Java tool, likely
`retrieve_context` or `answer_from_index`, backed directly by
`appFacade.documents().retrieveContext(...)`.

V1 shape:

- Safety: `READ_ONLY`, auto-approved.
- Inputs: `query`, `top_k`, `max_tokens`, optional `filters`,
  optional `doc_ids`, optional `return_full_documents`.
- Outputs: compact context passages, source IDs, retrieval mode,
  context-sufficient flag, truncation flag, and a short hint when context is
  insufficient.
- No citation-verification sub-flow in V1; the built-in agent's local model is
  already budget-constrained, and `match_citations` requires a generated
  answer round trip.

Why this is better than enhancing `search_index`:

- `search_index` is a discovery tool. It returns result summaries and excerpts.
- `retrieve_context` is an answer-preparation tool. It handles context packing,
  doc chunking, full-document fallback, and sufficiency signals.
- Keeping both lets the prompt say: use `retrieve_context` for answerable
  questions; use `search_index` to explore what exists.

#### Slice E - Deep Read: Guided Health Diagnostics

Files read:

- `modules/ui-web/src/components/views/health/deriveHealthEvents.ts`
- `modules/ui-web/src/stores/systemTypes.ts`
- `modules/telemetry/src/main/java/io/justsearch/telemetry/RrdMetricStore.java`
- `modules/ui-web/src/api/domains/packs.ts`

Key findings:

- `deriveHealthEvents` is already the right abstraction for user-facing
  diagnostics: it turns raw status fields into a capped list of events such as
  index unavailable, AI not ready, embedding unavailable, throughput stalled,
  schema blocked, queue DB unhealthy, memory pressure, and last failed job.
- `SystemStatus` already has many structured fields useful for guided
  explanations: readiness envelope, queue health, migration, GPU status,
  encoder profiles, disk pressure, failed jobs, throughput state, ORT CUDA
  status, LLM status, and search config.
- `RrdMetricStore` keeps a curated set, not arbitrary telemetry. That makes it
  viable as backing data for explanations, but still risky as a generic UI.
- The frontend has an `exportDiagnostics` API wrapper in `packs.ts`, but that
  action is not framed as a general Health support action.

Conclusion:

The Health opportunity is not "add graphs." It is to extend the existing
derived-event model with **evidence-backed explanations**:

- For "throughput stalled": show pending jobs, docs/sec, and optionally the
  relevant recent time-series trend if available.
- For "embedding unavailable": show readiness reason, ORT CUDA status, and a
  link to Brain/runtime details.
- For "queue DB unhealthy": show last check/error time and a diagnostics
  export action.
- For "memory pressure": show heap usage and maybe JVM metric trend.

V1 should add a general "Export diagnostics" action to Health before adding
time-series charts. Diagnostics export is already redacted and bundles much of
the state that support/debug workflows need.

### Candidate Register

#### C1 - Ingestion Activity / Outcome Ledger

**Class:** Implemented-hidden / Diagnostic-only.

**Persona:** User who wonders "why did this file not appear?", local support
workflow, and AI agent checking whether ingest finished cleanly.

**Evidence:** `IndexingRoutes` exposes
`/api/diagnostics/ingestion/recent` and
`/api/diagnostics/ingestion/summary`; `IndexingController` returns
privacy-safe ledger events and outcome rollups; 418 moved traversal,
admission, skip policy, cloud-placeholder recording, cancellation, and
backpressure into Worker-owned scan/watch flows.

**Current state:** The endpoints exist and appear intentionally privacy-safe,
but `LibraryView` and `HealthView` do not consume them. The Library surface
still frames indexing mostly as root status plus failed jobs/exclude results.

**Why unused:** It lives under diagnostics, not under the user's mental model
of "what happened to my folder?" Agent-facing MCP status also does not expose
recent ingest outcomes.

**Activation cost:** Low to medium. A read-only Library/Health panel could show
rollups by outcome/reason and a short recent-event feed. Agent surface could
add an optional status field or a small diagnostics tool.

**Risk / confusion:** Medium. Events use hashed paths, so the UI cannot say
which exact file was skipped unless it has a safe local mapping. The product
copy must avoid pretending the hash is actionable to a normal user.

**Payoff:** High. This is the cleanest near-term example of "internal truth
that users and agents actually need." It answers missing-file confusion
without weakening path privacy.

**Disposition:** Ship a small user-facing "Indexing activity" view before
building more indexing controls. Prefer aggregated explanations first;
event-level hash feeds are advanced/support detail.

#### C2 - Worker Scan Progress as a First-Class Workflow

**Class:** Substrate-enabled.

**Persona:** User adding a large folder; agent initiating ingest and deciding
when to proceed.

**Evidence:** 418 added server-streaming `ScanRoot` with progress counts,
bytes walked, terminal reason, hashed current directory, Worker-side
backpressure, and cancellation. Head-side callers were migrated to observe
scan progress, but the UI still appears to rely on coarse root status.

**Current state:** The gRPC contract exists. REST and UI do not expose a rich
scan-progress stream as a user workflow.

**Why unused:** The architectural move was primarily about trust boundaries
and admission authority. The product layer has not yet turned streaming scan
progress into a visible experience.

**Activation cost:** Medium. Needs a Head SSE or polling abstraction over scan
progress, then Library UI state for active scans.

**Risk / confusion:** Medium. Progress can be approximate; cancellation and
terminal reasons need careful wording. Hashed directory values are not useful
as literal UI labels.

**Payoff:** Medium-high for large-folder onboarding and agent workflows.

**Disposition:** Experiment after C1. C1 gives durable "what happened";
scan progress gives live "what is happening."

#### C3 - Time-Series Metrics / MetricCatalog Surfaces

**Class:** Implemented-hidden / Diagnostic-only.

**Persona:** Power user, support workflow, agent diagnosing degraded search or
indexing behavior.

**Evidence:** 417 shipped MetricCatalog-as-contract, RRD-backed curated
metrics, `surfacedAt` metadata, and `/api/debug/metrics/timeseries`.
`TimeSeriesController` provides `available` and range query endpoints with
relative time syntax. `HealthView` does not appear to consume these endpoints.

**Current state:** Strong backend/debug substrate; weak product surface.

**Why unused:** The endpoints are debug-namespaced, and no UI chart/dashboard
consumer is present except the raw debug dashboard. Tempdoc 400 explicitly
warns against dashboards without a named analytical question.

**Activation cost:** Low for a tiny chart against curated metrics; medium for
a useful diagnostic flow.

**Risk / confusion:** High if surfaced as generic graphs. Users do not need 30
metrics; they need explanations like "index writer is backed up" or "GPU is
busy with enrichment."

**Payoff:** Medium if framed as guided diagnostics, low if framed as charts.

**Disposition:** Do not ship a general metrics explorer yet. Ship targeted
Health explanations backed by curated metrics only when they answer a named
question, starting with indexing backlog / GPU utilization / telemetry health.

**C3 V1 disposition: SHIPPED (2026-04-26).** Backend evidence-fields added so the
frontend's existing `deriveHealthEvents.ts` taxonomy can render evidence-rich
detail when a HealthEvent fires. Three named questions covered, all extending
existing API records (no fork of the frontend taxonomy):

- `worker.core.recentJobQueueDepth: long[]` — 30-min trend of
  `worker.job_queue.depth` (curated RRD metric). Backs
  `index-throughput-stalled` / `index-throughput-degraded`.
  `WorkerOpsMetricCatalog` declares `surfacedAt(CORE_INDEX_VIEW,
  "recentJobQueueDepth")`.
- `gpu.recentUtilizationPercent`, `gpu.recentMemoryUtilizationPercent: double[]`
  — 30-min trends of the curated GPU gauges. `HeadGpuMetricCatalog` declares
  `surfacedAt(GPU_STATUS_VIEW, ...)` for both. Available for future GPU-related
  events (none defined today; product decision deferred).
- `telemetryHealth.flushFailureCount`, `telemetryHealth.gaugeCallbackFailureCount:
  long` — populated from the existing `TelemetryHealthState` counters.
  `TELEMETRY_HEALTH_VIEW` enum reserved for future metric-backed declarations.

Wired through:

- Worker: `WorkerOpsMetricCatalog.surfacedAt(...)` + `IndexStatusOps.buildCore`
  calls `RrdMetricStore.query("worker.job_queue.depth", now-1800, now)` and
  populates the new `recent_job_queue_depth` proto field on `CoreStatus`.
- Head: `HeadGpuMetricCatalog.surfacedAt(...)` + `StatusLifecycleHandler` queries
  `RrdMetricStore` for both GPU metrics and reads `TelemetryHealthState.snapshot()`
  to populate the two new top-level fields on `StatusResponse`.
- Schema regen + Zod sync via `:modules:app-api:updateSchemas`; frontend Zod
  schemas + TypeScript types extended in `modules/ui-web`.
- Contract tests: `MetricSurfaceContractTest` (worker-services) extended to
  cover `WorkerOpsMetricCatalog`; new parallel `HeadMetricSurfaceContractTest`
  in `app-services` covers `HeadGpuMetricCatalog`. Both validate
  `surfacedAt(...)` field names against API record components.

Frontend wiring (Export Diagnostics Health action, sparkline rendering on
firing events, V2 events for GPU + telemetry-health) is not part of V1 — the
backend evidence is now available for whichever frontend slice picks it up
next. Documented in `docs/explanation/08-observability.md` "Health
explanations" section.

**C3 V1.5 frontend SHIPPED (2026-04-26 same day, follow-up commits).**
Frontend wiring landed:

- `Sparkline.tsx` — minimal SVG component, auto-scaling to data range, returns
  null for <2 points. Reusable for future evidence trends.
- `HealthView` renders the sparkline next to firing
  `index-throughput-stalled` / `index-throughput-degraded` events using
  `worker.core.recentJobQueueDepth`. Stroke color tied to event severity.
- `HealthView` "Export diagnostics" button calling the existing
  `POST /api/diagnostics/export` endpoint, surfacing the resulting
  privacy-redacted ZIP path inline.
- Store mapping (`useSystemStore` + `systemTypes`) extracts
  `worker.core.recentJobQueueDepth` and `validated.telemetryHealth` so
  `base.*` consumers get them.
- Defensive tests: 7 `Sparkline` cases + 3 store-mapping cases (212 → 222
  frontend tests).

Live smoke (jseval scifact ingest, 5184 docs, 4.4 min full enrichment) confirmed
all four V1 fields populate end-to-end:
`gpu.recentUtilizationPercent: [52.27, 46.36]`,
`gpu.recentMemoryUtilizationPercent: [28.52, 23.39]`,
`telemetryHealth: {flushFailureCount: 0, gaugeCallbackFailureCount: 0}`.
Commits: `983ba19ee`, `094aaa5f7`, `04fd2d500`.

**C3 V2 disposition: SHIPPED (2026-04-28).** Three named-question HealthEvents
that consume V1's substrate following the existing
`OperationalMetrics.ThroughputMonitor` discipline (rolling-window state
machine, hardcoded sensible defaults, backend computes / frontend renders):

- **`telemetry-degraded` (P1)** — surfaces the existing
  `TelemetryHealthController` classification (5min stale / <0.9 success rate
  / disk-low) on the `/api/status` readiness envelope. New
  `TelemetryHealthClassifier` static helper extracted from the controller
  so `/api/telemetry/health` and the new `TELEMETRY` readiness dim share a
  single classification path; eliminates threshold-drift risk. New composite
  `"telemetry"` keeps subsystem hiccups out of `aiFeatures`.
- **`recentDocsPerSec` rate trend (P2)** — new
  `worker.documents.indexed.rate_per_sec` gauge curated for RRD archive,
  surfaced as `worker.core.recentDocsPerSec: double[]` mirroring V1's
  `recentJobQueueDepth`. Rendered as a complementary sparkline next to V1's
  depth sparkline for the same firing throughput events. Depth answers "is
  the backlog draining," rate answers "is the indexer making progress."
  Supplier reads `ThroughputMonitor.compute()` (rate already computed
  upstream) and also calls `recordSample()` so the monitor stays
  self-sufficient on the gauge-flush cadence.
- **`gpu-saturated` (P3)** — new head-side `GpuSaturationMonitor` (180s
  rolling window, `WINDOW_MS`/`MAX_GAP_MS`/`MAX_SAMPLES` constants and
  `synchronized` discipline mirror `ThroughputMonitor` exactly) plus a
  daemon-thread `GpuSaturationSampler` (15s cadence, short-circuits cleanly
  on NVML-unavailable / headless machines). Activity gate composed of
  `engineMonitor.queueDepth() + processingJobsCount + GPL_RUNNING +
  onlineAi.isAvailable()`; the last term suppresses false positives during
  llama-server background residency. New `GPU_SATURATED("gpu.saturated")`
  reason code under the existing `gpu.*` namespace.

Phase 0 prereq: MSW status fixture (`modules/ui-web/src/mocks/fixtures.mjs`)
gained the missing `readiness` envelope baseline — also unblocked demo-mode
rendering of V1's `index-throughput-*` events that had been silently dark.

Tests added: `TelemetryHealthClassifierTest` (7 cases), `GpuSaturationMonitorTest`
(8 cases, clock-injected), `GpuSaturationSamplerTest` (5 cases, Mockito), 4
new `deriveHealthEvents.test.ts` cases (positive + negative for both events).
Frontend test count: 222 → 227.

Live smoke confirmed: `readiness.components.telemetry` and
`readiness.components.gpu` both present with `state: READY` defaults; backend
boots with the new sampler thread cleanly; head-side NVML probe
(`gpu.recentUtilizationPercent`) accumulates samples on schedule.

Commits: `eafa1bdcc` (P0+P1), `348c88afb` (P2), `ed61b337c` (P3). Plan file:
`C:\Users\<user>\.claude\plans\cryptic-hopping-glade.md`.

**C3 disposition rolled up: COMPLETE.** V1 substrate + V1.5 frontend wiring +
V2 three-event slate covers all named questions originally identified
(indexing backlog / GPU utilization / telemetry health). Future V3 work
(trend-windowed events with new hysteresis, generic time-series explorer,
backend-side HealthEvent derivation, `/api/health/explanations` endpoint)
remains explicitly out of scope per the disposition's "named-question, not
generic-dashboard" frame.

#### C4 - Authoritative Session Policy Inspector

**Class:** Implemented-hidden / Diagnostic-only.

**Persona:** Support workflow, advanced user tuning GPU/ORT behavior, agent
explaining why GPU/CPU fallback happened.

**Evidence:** 397 closed around a Worker-authoritative policy snapshot and
`/api/debug/session-policies`; `SessionPoliciesController` explicitly reads
Worker state through gRPC instead of re-resolving on Head. Tempdoc 311 shows
why this matters: arena limits, shrinkage, retry, and GPU fallback have real
performance impact.

**Current state:** Endpoint exists and is explicitly designed as source of
truth. No product UI appears to surface it.

**Why unused:** It is too low-level for a normal settings page, and raw policy
JSON is not a user workflow.

**Activation cost:** Low for a "copy diagnostics" support panel; medium for a
Brain/Health "why this model runs on CPU/GPU" explainer.

**Risk / confusion:** High if users are invited to tune policy fields directly.
The useful capability is explanation, not editing.

**Payoff:** Medium. Particularly useful for agents and support because it
removes guesswork about actual Worker-side session construction.

**Disposition:** Surface as read-only explanation in diagnostics/export and
possibly Brain advanced details. Do not make it a settings editor.

#### C5 - Built-In Agent vs MCP Capability Asymmetry

**Class:** Implemented-hidden / Agent-visible-but-undiscoverable.

**Persona:** User using the in-app agent; external AI agents using MCP.

**Evidence:** Built-in Java tools provide `search_index`, `browse_folders`,
`ingest_files`, `file_operations` with approval/undo/session history. MCP
tools provide `justsearch_answer`, `justsearch_search`, `justsearch_ingest`,
and `justsearch_status` with rich retrieval controls. Tempdoc 200 concluded
the split is intentional, not a shared-schema failure.

**Current state:** Both surfaces are real, but each hides something valuable
from the other:

- Built-in agent has safe local file operations and undo; MCP does not.
- MCP has answer-level retrieval, citation verification, facets, full-document
  retrieval, and richer filter ergonomics; built-in agent does not.
- UI agent has session/history/resume endpoints; MCP agents get no equivalent
  JustSearch-native task history.

**Why unused:** The project treated the split as architectural sanity. That is
still correct. But capability discovery now suffers because "the agent" means
two different products with different strengths.

**Activation cost:** Low for documenting/displaying capability differences;
medium for porting one or two read-only MCP affordances into built-in agent;
high for safe MCP file operations.

**Risk / confusion:** Medium. Unifying too aggressively would weaken the
safety model. But leaving the split unexplained makes users and agents miss
the right surface.

**Payoff:** High. This is a strategic capability: JustSearch can be both an
in-app local file steward and an external-agent retrieval server.

**Disposition:** Do not unify the architecture. Build a capability matrix and
selectively cross-pollinate:

- add answer/retrieve-context/citation-style read-only tool to built-in agent;
- consider MCP `browse_folders` before MCP file ops;
- keep file operations in the built-in approval/undo model until there is a
  concrete external-agent safety design.

#### C6 - Agent Retrieval Knobs That Exist but Stay Invisible

**Class:** Agent-visible-but-undiscoverable.

**Persona:** External MCP agents doing source comparison, doc-specific QA, or
evidence verification.

**Evidence:** MCP schema and tempdoc 366 show implemented filters, facets,
`boostFilters`, `includeExcerpts`, `doc_ids`, `return_full_documents`,
context formats, and `verify_citations`. Tempdoc 366's eval sections show
low or zero adoption for several implemented knobs, especially entity filters,
`boostFilters`, `includeExcerpts`, and full-document retrieval.

**Current state:** The feature code exists. The agents mostly use the easy
paths: answer, search, source filters. Some knobs improved after response
hints, but not enough to count as discovered.

**Why unused:** Tool descriptions are overloaded and agents choose the
shortest successful path. Some features only matter for specific task shapes,
and the eval workload may not demand them.

**Activation cost:** Low for better response-time hints; medium for an
explicit "retrieval strategy" helper or auto-orchestrated server-side modes.

**Risk / confusion:** Medium. Adding more schema surface can reduce tool
quality. Tempdoc 366 already observed that bloat can hurt.

**Payoff:** Medium-high if focused on task-triggered behavior. Full-document
retrieval and doc-scoped search are valuable, but not as default knobs.

**Disposition:** Prefer progressive, task-triggered surfacing over schema
growth. The most promising product form is an answer response that says:
"I found candidate documents but only used chunks; call again with
`return_full_documents` for doc-level reconciliation" or does that
automatically under strict budget.

#### C7 - Search Facet UX Is Ahead of Agent Discoverability

**Class:** Implemented but unevenly surfaced.

**Persona:** Human search user and external agent.

**Evidence:** React search code supports facets, entity facet variants,
NER-coverage gating, metadata facets, language/mime facets, and excerpts.
MCP also exposes facets and normalizes entity facet keys. Tempdoc 366 says
agents still struggle with entity-filter usage and filter value confidence.

**Current state:** Human UI has strong filter affordances. Agent surface has
the data but weaker behavior.

**Why unused:** Humans can see facet chips and click them. Agents need the
response to become an affordance, not just data.

**Activation cost:** Low for response hints; medium for a dedicated facet
discovery step in `justsearch_answer`.

**Risk / confusion:** Low if kept read-only. Medium if the system over-filters
without user/agent intent.

**Payoff:** Medium. More valuable for external-agent accuracy than for human
UI, which already has the interaction model.

**Disposition:** Treat as an agent UX problem, not a backend feature gap.

#### C8 - Diagnostics Export as a User/Agent Support Workflow

**Class:** Implemented-hidden / Diagnostic-only.

**Persona:** User reporting a bug, support agent, local AI agent preparing a
support bundle.

**Evidence:** `/api/diagnostics/export` creates a redacted zip containing AI
install/runtime state, settings, policy snapshots, GPU capabilities, logs,
telemetry, crash reports, debug state, and status snapshots. Brain pack code
calls diagnostics export, but it is not a general Health support workflow.

**Current state:** A strong support primitive exists, but is mostly tied to
pack/policy flows.

**Why unused:** It is not framed as "collect diagnostics" in Health, and MCP
does not expose it.

**Activation cost:** Low for a Health button; low/medium for an agent-visible
support action with explicit confirmation.

**Risk / confusion:** Medium. Even redacted bundles are sensitive and should
require clear user intent.

**Payoff:** Medium. This turns many debug endpoints into one safe support
artifact.

**Disposition:** Ship a user-confirmed Health action. Do not make external
agents invoke it silently.

#### C9 - Dynamic Logging

**Class:** Implemented-hidden / Diagnostic-only.

**Persona:** Support workflow and developer/operator.

**Evidence:** `/api/debug/logging` GET/POST exists. No obvious UI consumer was
found in Health/Settings.

**Current state:** Reachable debug API, not productized.

**Why unused:** It is a sharp tool and easy to overexpose.

**Activation cost:** Low.

**Risk / confusion:** High for normal users. Logging changes can generate huge
files or expose sensitive context.

**Payoff:** Low for users, medium for support.

**Disposition:** Keep debug-only or place behind an advanced support gate.

#### C10 - Runtime Reload / Hot-Reload Product Features

**Class:** Substrate-enabled.

**Persona:** Advanced user/operator changing config or SSOT.

**Evidence:** 416 documents product features enabled by the 406 runtime swap
substrate and current `POST /api/admin/runtime/reload`. Routes confirm the
endpoint exists. 416 itself says triggers/watchers/change detection remain.

**Current state:** Admin substrate exists. Product feature does not.

**Why unused:** The endpoint alone is too blunt; safe UX requires semantic
change detection and messaging.

**Activation cost:** Medium to high, depending on which hot-reload feature.

**Risk / confusion:** High for generic "reload" buttons. Users can believe
field/analyzer changes took full effect when they require reindex.

**Payoff:** Medium, but explicitly not a focus for this task because the user
is already working release/resilience areas and 416 is tracked separately.

**Disposition:** Record only as context. Do not prioritize in this discovery
workflow.

#### C11 - Policy / AI Pack Administrative Surfaces

**Class:** Implemented-gated / partially surfaced.

**Persona:** Enterprise/offline admin and user importing local AI packs.

**Evidence:** `BrainView` and `BrainPackImportSection` consume effective
policy, pack status, installed packs, preflight/import, user policy creation,
allowlist addition, diagnostics export, and Tauri file/folder helpers. Routes
also expose policy validation and user policy creation.

**Current state:** Not hidden; advanced-gated in Brain.

**Why unused:** If unused, likely because it is specialized, not because code
is missing.

**Activation cost:** Low for documentation; product already exists.

**Risk / confusion:** Medium. Enterprise policy is inherently advanced.

**Payoff:** Low for this investigation.

**Disposition:** Not a primary unused feature. Keep as advanced Brain surface.

### Synthesis

### Synthesis - Pass 1

The most important discovery is that JustSearch already has three different
"truth layers" that are stronger than their user/agent affordances:

1. **Operational truth**: ingestion ledger, failed jobs, scan progress,
   session policy, telemetry, diagnostics export.
2. **Retrieval truth**: facets, filter normalization, doc-scoped search,
   full-document retrieval, citation matching, context sufficiency.
3. **Agent-action truth**: built-in agent sessions, approval, history, undo,
   safe file operations, plus MCP's more capable external retrieval tools.

The tempting but wrong conclusion would be "surface all debug endpoints." The
better conclusion is that JustSearch needs **explanation workflows**, not
control panels:

- "Why is this document missing?"
- "What is indexing doing right now?"
- "Why did search answer from these sources?"
- "Why is AI slow or on CPU?"
- "What did the agent do, and can I reverse it?"

Those questions map directly to already-implemented substrate. The product gap
is affordance design, not backend capability.

### Ranked Opportunities

1. **Indexing activity/outcome view (C1)**  
   Highest near-term value. It is implemented, privacy-conscious, and answers
   a real user question. It should be a Library/Health user workflow before it
   becomes an agent tool.

2. **Built-in agent retrieval parity slice (C5/C6)**  
   Add a read-only answer/retrieve-context/citation capability to the built-in
   agent, or at least expose why MCP is the richer QA surface. The built-in
   agent currently has the safer action model; MCP has the better retrieval
   model. Cross-pollinating one read-only retrieval tool is the safest bridge.

3. **Guided Health diagnostics (C3/C4/C8)**  
   Use session policies, time-series metrics, telemetry health, and diagnostics
   export to answer specific health questions. Avoid generic charts.

4. **Scan progress (C2)**  
   Valuable after the durable outcome view exists. Live progress without
   post-hoc explanations is less useful than it feels.

5. **Agent retrieval progressive disclosure (C6/C7)**  
   Continue the tempdoc 366 direction: fewer tools, better response-time
   nudges. Avoid schema bloat.

### Explicit Non-Priorities

- Generic metric explorer.
- Generic admin reload UI.
- Dynamic logging as a normal-user feature.
- Full MCP file operations before a dedicated safety design.
- More AI pack/policy product work for this investigation; Brain already
  surfaces most of that stack.

### Next Investigation Slices

The first three were completed in this investigation pass as Slices C-E:

1. `RetrieveContextController`, built-in agent tool SPI, and agent tool wiring
   were deep-read. Result: recommend a new built-in read-only
   `retrieve_context` / `answer_from_index` tool, not MCP unification.
2. Ingestion ledger storage, privacy tests, and UI indexing hooks were
   deep-read. Result: recommend an Indexing activity summary based on rollups
   first, with event hashes hidden behind an advanced drawer.
3. Health derivation, status types, RRD metric curation, and diagnostics export
   were deep-read. Result: recommend guided Health explanations and general
   diagnostics export before any chart/dashboard work.
4. Remaining optional follow-up: revisit tempdoc 361/365 agent feedback-loop
   notes after a concrete built-in `retrieve_context` tool is designed.

### Recommended Work Packages

### WP1 - Library Indexing Activity Summary

**Why first:** already implemented backend, strong privacy tests, clear user
question.

**Scope:**

- Add frontend API types/functions for:
  - `GET /api/diagnostics/ingestion/summary?since=...`
  - `GET /api/diagnostics/ingestion/recent?limit=...`
- Add a Library section that shows outcome rollups with friendly labels.
- Add a compact empty/success state when there are no recent non-success
  outcomes.
- Add an advanced recent-events drawer only if needed for support.

**Do not:**

- Display path hashes as though they are meaningful filenames.
- Try to reverse-map hashes in V1.
- Mix this with live scan progress yet.

### WP2 - Built-In Agent `retrieve_context` Tool

**Why second:** strategic bridge between in-app safe action model and MCP's
stronger retrieval model.

**Scope:**

- Implement a read-only Java tool backed by `DocumentService.retrieveContext`.
- Register it beside `search_index` and `browse_folders`.
- Update the built-in agent prompt so factual QA prefers `retrieve_context`,
  while exploratory tasks still use `search_index`.
- Keep schema small: query, top_k, max_tokens, optional filters/doc_ids,
  optional full-document retrieval.

**Do not:**

- Port the full MCP schema.
- Add write capabilities to MCP as part of this.
- Add citation verification until the basic context tool proves useful.

### WP3 - Health Diagnostics Export and Explanation Hooks

**Why third:** reduces support/debug friction without creating a misleading
dashboard.

**Scope:**

- Add a general Health "Export diagnostics" action using existing
  `/api/diagnostics/export`.
- Extend `deriveHealthEvents`-style UI with small explanation payloads for the
  top events: throughput, embedding unavailable, queue DB unhealthy, memory
  pressure.
- Only use RRD time-series where it answers a concrete event.

**Do not:**

- Ship a generic metrics explorer.
- Surface dynamic logging as a normal-user setting.

### WP4 - Live Scan Progress

**Why later:** valuable, but only after the durable outcome surface exists.

**Scope:**

- Design an SSE or polling bridge over Worker `ScanRootProgress`.
- Show active root scan counts and terminal reason in Library.

**Do not:**

- Leak hashed current-directory values into primary UI.
- Treat progress counts as exact completion promises.

---

### Second Investigation Pass - Uncovered Surfaces

This pass intentionally avoided the already-covered release/resilience areas
and looked in UI action paths, Browse/Inspector AI workflows, Brain runtime
controls, search response metadata, disclosure gates, and older agent-facing
tempdocs that had not been used deeply in the first pass.

#### Slice F - Command Bar, Action Panel, and Help Promises

**Files read:** `GlobalCommand.tsx`, `App.tsx`, `useSearchStore.tsx`,
`models/actions.ts`, `ActionPanel.tsx`, `HelpView.tsx`, `useAppAI.ts`.

Findings:

- The global command bar has a real tri-mode model: search, slash command,
  and chat. `useSearchStore.detectMode()` marks both `??` and `ask ` as
  chat mode, and the placeholder advertises `?? ask AI`.
- Submission is not symmetric with the UI contract. `App.handleSubmit()`
  handles `??` by logging `Chat command` and returning. `ask ...` enters chat
  visual mode, but because only `q.startsWith('??')` is intercepted, it falls
  through as a normal search query.
- Help explicitly says: "Type ?? followed by your question in the search bar
  to ask the AI about your selected files or general questions." That is a
  user-facing promise, not just dead code.
- The Action Panel is more complete than the command mode path: it already has
  explainable enabled/disabled states for `Summarize`, `Ask a question`,
  `Find similar`, and reindex warnings. Command mode does not appear to reuse
  this action catalog.
- `/help` currently routes to a log line rather than a visible command/help
  surface. `/contrast`, `/hc`, `/clear`, and `/reconnect` do real work, but the
  advertised command affordance is much thinner than the UI suggests.

Interpretation:

The command bar is the clearest second-pass "feature-shaped gap." It is not
half-designed; it has store-level modes, localized/help text, placeholders, and
input behavior. What is missing is the routing layer that turns chat mode into
an actual scoped AI workflow.

This is deeper than a small bug because it points to a product primitive:
**scope-aware global asking**. The app already has selected-file Q&A in the
Inspector and whole-index answer tooling in MCP. The command bar could become
the human equivalent of `justsearch_answer`, with an explicit scope pill:
selected files, current results, current filters, or whole index.

#### Slice G - Browse, Inspector, and Summary Path Divergence

**Files read:** `BrowseView.tsx`, `InspectionPane.tsx`,
`InspectorAIInput.tsx`, `useAppFileActions.ts`, `useSummary.ts`,
`useAppAI.ts`, `streams.ts`, `SummaryController.java`,
`api/domains/browse.ts`.

Findings:

- Browse is richer than the route inventory implied: folder tree, persistent
  expansion, inline filtering, multi-select, virtual file rows, context menus,
  and conversion of a browse file into a HUD result item for Inspector preview.
- Browse has a local `useAppFileActions()` wiring where `requestSummary` is a
  no-op with a comment: `Phase 4: wire AI summarization`. This means context
  menu summarization from Browse is likely inert even though the Inspector path
  can summarize a selected browse item through `App`.
- Single-file summary and multi-file/full-coverage summary are separate user
  paths. Context menu single-file summary uses `useSummary()` and
  `/api/summarize/stream`, with client-side content fallback/truncation logic.
  Inspector batch/single selection uses `/api/summarize/batch/stream` and can
  get full-coverage metadata.
- The backend exposes `/api/summarize/hierarchical/stream`, and frontend stream
  metadata understands `hierarchical`, `sections`, and reduction levels.
  However, `streams.ts` exports wrappers only for single summary, batch
  summary, and ask. No hierarchical stream wrapper was found.
- Inspector already has the answer-trust loop: citations, retrieval mode,
  chunks used/found, context length, coverage, finish reason, token usage,
  citation focus, and a persisted trust-loop nudge.
- Preview already exposes VDU provenance: `tika`, `vdu`, pending, processing,
  and failed states, with page count. Browse requests `vdu_status`.

Interpretation:

The app has two AI surfaces that feel close to convergence but are not yet one
workflow:

1. The **Inspector path** is evidence-rich and trust-aware.
2. The **context-menu/Browse path** is fast and discoverable but thinner.

That creates an accidental quality lottery: the button a user chooses can
change whether they get old snippet-style summarization or the newer
full-coverage/evidence-rich path. The important feature is not "add another
summary button"; it is **make every AI entry point resolve to the same scoped
AI operation model**, then let the UI choose compact or detailed presentation.

#### Slice H - Search Pipeline Metadata and Invisible Query Understanding

**Files read:** `search.ts`, `useSearch.ts`,
`KnowledgeSearchController.java`, `KnowledgeHttpApiAdapter.java`,
`KnowledgeSearchResponse.java`, tempdocs 363 and 366.

Findings:

- The backend and API contract contain rich search-side metadata:
  `queryUnderstanding`, `filterNormalization`, `boostFilters`,
  `pipelineExecution`, per-hit provenance, matched fields, match spans, excerpt
  regions, entity facet variants, and index capabilities.
- The frontend maps provenance and `pipelineExecution`, but `SearchResponse`
  does not currently expose `queryUnderstanding` or `filterNormalization`.
  `search()` also does not send `boostFilters` from `SearchOptions`, despite
  backend support.
- `useSearch()` hard-enables a specific pipeline profile:
  sparse+dense+RRF+LambdaMART+cross-encoder. Advanced controls for the user are
  mostly query syntax, filters, sort, and facets, not pipeline/provenance
  explanation.
- Tempdoc 363 says hard query-understanding filters were net-negative, while
  soft boosts were strongly net-positive in simulation. Tempdoc 366 then made
  `queryUnderstanding` response metadata an agent coordination signal. That
  signal is present server-side but not carried into the ordinary UI.
- `Find similar` sets the query to `similar:${item.title}`. A repository search
  found only this UI producer, not a backend parser or handler for `similar:`.

Interpretation:

The most interesting unused search feature is not another visible filter. It is
**search explanation as a trust and steering surface**:

- "We boosted these sources/entities because your query implied them."
- "We normalized this filter value before searching."
- "This result ranked high because title/content/source matched."
- "Find similar" should either become a real structured operation or be renamed
  to the Lucene/text behavior it actually performs.

This overlaps with agent features because MCP already treats response metadata
as a way to teach the outer agent. The human UI could use the same idea in a
lighter form: a small "why these results?" disclosure tied to current query and
top hit, not a full debug panel.

#### Slice I - Brain Runtime, Offline Processing, and Disclosure Gates

**Files read:** `BrainRuntimeSection.tsx`, `InferenceHandlers.java`,
`api/domains/inference.ts`, `Gate.tsx`, `featureRegistry.ts`,
`useDisclosure.ts`, `SettingsView.tsx`, `HealthView.tsx`.

Findings:

- Offline processing is already a user-triggerable workflow in Advanced Brain:
  `Process queue` calls `/api/offline/process`, which starts VDU + embedding
  work in a background virtual thread.
- Health shows queue/failed job/reindex/rebuild/restart actions, but not the
  same explicit "process pending enrichment now" action.
- Brain also exposes diagnostics export, AI Home/log reveal, runtime variants,
  ONNX search quality feature status, model slots, runtime reload, worker
  restart, pause-indexing-while-AI, context window, max tokens, and GPU layers.
- Progressive disclosure is centralized and data-driven:
  `FEATURE_REGISTRY` gates Brain advanced controls, Library reindex/excludes,
  Inspector context details, Lucene syntax, path prefix, and MIME filters.
  Level `1` is explicitly reserved for a future "Power" tier but unused.

Interpretation:

Brain Advanced is already a dense admin cockpit. That is why it should not be
the main place to add more normal-user features. The better opportunity is
**graduated workflow surfacing**:

- Simple mode: outcome-first controls like "finish processing pending files."
- Power mode (the currently unused level 1): search syntax, result explanation,
  command actions, and queue processing.
- Advanced mode: runtime paths, GPU layers, ONNX status, policy/import details.

The unused level 1 matters. It could prevent a recurring product tension:
features are either hidden from normal users or dumped into Advanced. A real
Power tier could expose operationally useful controls without asking users to
understand runtime installation internals.

#### Slice J - Agent Workflow Tempdocs Revisited

**Tempdocs read:** 361, 363, 365, 366.

Findings:

- Tempdoc 365 built strong UI feedback tooling for agents (`jseval ui-shot`,
  step discovery, affected-step index). That is agent-development-facing, not a
  user feature, but it implies the product can support visual state assertions
  cheaply.
- Tempdoc 361 identifies repeated agent failures around skill loading,
  empirical log-first debugging, and verification discipline. This is not a UI
  feature, but it suggests a built-in agent could benefit from system
  self-context: "which domain am I touching?", "which log should I read first?",
  "what restart/reingest step is actually necessary?"
- Tempdoc 366's later phases show agents stopped struggling with basic filters
  and started asking for entity facets, cross-document joins, full document
  retrieval, and scoped literal search. Some of that is now in the codebase as
  entity facets, `doc_ids`, full-document retrieval in retrieve-context, and
  excerpt highlighting.

Interpretation:

The built-in agent should not just get more tools. It should get **workflow
memory from the app's own diagnostic/eval surfaces**. A read-only
`retrieve_context` tool remains the best first step, but the second step might
be an "explain current workspace/system state" tool, not file mutation. The
agent should be able to ask JustSearch itself what is indexed, what enrichment
is pending, what retrieval features are active, and what evidence supports an
answer.

### Second-Pass Candidate Register

#### C12 - Global Command AI Ask Mode

**Status:** UI-promised but not functionally wired.

**Evidence:** `??` is advertised in placeholder/help/shortcuts and detected as
chat mode, but submit only logs. `ask ...` is detected as chat mode yet falls
through to search.

**Opportunity:** Implement scoped command-bar asking:

- If files are selected: call existing selected-doc `/api/ask/stream`.
- If current filters/results are active: ask over current result scope.
- If no scope exists: use retrieve-context/answer-style whole-index retrieval.

**Priority:** Very high. This is the most user-visible unused feature found in
the second pass.

#### C13 - Unified AI Operation Model

**Status:** Multiple working paths with divergent behavior.

**Evidence:** Inspector full-coverage summary/Q&A, legacy context-menu summary,
Browse no-op summary hook, hierarchical endpoint without a frontend wrapper.

**Opportunity:** Route all summarize/ask entry points through one scoped AI
operation layer, then decide presentation separately.

**Priority:** High. Prevents quality differences based on which button a user
discovers first.

#### C14 - Browse Action Parity

**Status:** Mostly implemented browsing surface with incomplete AI action
wiring.

**Evidence:** Browse can inspect files and has context menus, but its local
`requestSummary` is a no-op.

**Opportunity:** Make Browse context menu Summarize/Ask route to the same
Inspector/AI operation model as search results.

**Priority:** Medium-high, especially if Browse becomes a primary exploration
surface.

#### C15 - Search Explanation Surface

**Status:** Backend metadata exists; ordinary UI mostly drops or hides it.

**Evidence:** `queryUnderstanding` and `filterNormalization` records exist in
Java API responses but are not mapped in frontend `SearchResponse`. Provenance
and pipeline execution are mapped, but not surfaced as a coherent explanation.

**Opportunity:** Add a compact "why these results?" disclosure using applied
boosts, normalized filters, matched fields, and top-hit provenance.

**Priority:** Medium-high. Strong user/agent bridge, but should stay compact.

#### C16 - Real or Removed "Find Similar"

**Status:** UI action exists; backend semantics not found.

**Evidence:** `handleFindSimilar()` sets `similar:${item.title}`; no backend
handler for `similar:` was found.

**Opportunity:** Either implement actual similar-document retrieval using
doc_id/vector-neighbor semantics or rename/remove the action.

**Priority:** Medium. It is a small affordance, but misleading actions erode
trust.

#### C17 - Power Disclosure Tier

**Status:** Slot exists but unused.

**Evidence:** `DisclosureLevel` defines level `1` as reserved for future
"Power" tier; gates currently use simple `0` or advanced `2`.

**Opportunity:** Put user-understandable expert workflows at level 1: Lucene
syntax, path/MIME filters, search explanation, command actions, queue
processing. Keep runtime internals at level 2.

**Priority:** Medium. This is an enabling product architecture move, not a
single feature.

#### C18 - Health/Brain Queue Control Unification

**Status:** Implemented in Brain, not normalized across operational surfaces.

**Evidence:** Brain has `Process queue` for VDU + embeddings. Health has
queues/reindex/restart/rebuild but no matching process-pending-enrichment
action.

**Opportunity:** Move the outcome-first queue control into Health or Library,
while leaving detailed runtime controls in Brain Advanced.

**Priority:** Medium. Useful, but less strategically novel than command ask or
search explanation.

#### C19 - Built-In Agent System-State Tool

**Status:** Conceptual extension enabled by current diagnostics/status APIs.

**Evidence:** Agent tempdocs show repeated need for tool/context discipline;
status APIs now expose readiness, policies, feature availability, queue health,
and diagnostics.

**Opportunity:** After `retrieve_context`, give the built-in agent a read-only
`system_state` / `diagnose_workspace` tool that summarizes index readiness,
pending enrichment, active retrieval features, and relevant health events.

**Priority:** Medium. Do after retrieval parity, not before.

### Revised Work Package Additions

### WP5 - Command Bar Ask

**Why now:** The UI already promises it. This is the cleanest path from
"latent" to "felt by users."

**Scope:**

- Make `?? question` and `ask question` submit into the same AI operation.
- Choose an explicit scope in this order: selected files, inspected file,
  current results/current filters, whole index.
- Show the answer in Inspector AI tab or a lightweight command answer panel.
- Add a clear empty-scope/error state when AI is unavailable.

**Do not:**

- Build a general chatbot.
- Hide the retrieval scope.
- Duplicate MCP schemas in UI state.

### WP6 - Normalize AI Entry Points

**Why next:** It removes accidental feature fragmentation.

**Scope:**

- Replace Browse no-op summary wiring.
- Make context-menu summarize use the same full-coverage path where possible.
- Decide whether `/api/summarize/hierarchical/stream` should be explicitly
  wired or folded into batch summary behavior.
- Preserve Inspector's citation/context transparency as the canonical detailed
  view.

### WP7 - Search Explanation Micro-Surface

**Why later:** It is valuable, but should come after command ask has a real
scope and answer path.

**Scope:**

- Map `queryUnderstanding` and `filterNormalization` through frontend search
  types.
- Surface normalized filters/applied boosts as compact chips or an Inspector
  detail.
- Reuse matched fields and provenance for top-hit explanation.

**Do not:**

- Add a generic debug panel.
- Expose every pipeline timing by default.

### Second-Pass Ranking

1. **Global Command AI Ask (C12/WP5)** - strongest because it is already
   promised to users and can reuse existing AI paths.
2. **Unified AI Operation Model (C13/WP6)** - prevents duplicated or uneven AI
   behavior across Browse, Search, context menus, and Inspector.
3. **Search Explanation Surface (C15/WP7)** - best user/agent trust bridge
   after answer workflows are real.
4. **Browse Action Parity (C14)** - likely folds into WP6.
5. **Power Disclosure Tier (C17)** - important product architecture, but can be
   applied incrementally as the above features land.
6. **Find Similar semantics (C16)** - fix before relying on it, but not a
   strategic pillar.
7. **Queue control unification (C18)** - worthwhile placement cleanup.
8. **Built-in agent system-state tool (C19)** - good second agent tool after
   retrieval parity.

### Third Investigation Pass - Deeper Autonomy Sweep

This pass intentionally looked outside the already-investigated release,
resilience, and future-feature lanes. The goal was to test whether the earlier
ranking missed any stronger currently-enabled capabilities in agent session
state, MCP contracts, command/action surfaces, indexing UX, or diagnostic
substrate.

#### Slice K - Agent Session Resume, Replay, and Safety History

**Classification:** Implemented-hidden / implemented-partial.

**Evidence:**

- `AgentController` exposes more session surface than the frontend uses:
  `GET /api/agent/session/last`,
  `POST /api/agent/session/resume-last/stream`, and
  `GET /api/agent/session/{id}/events`.
- `modules/ui-web/src/api/domains/agent.ts` exposes tools, run, approve,
  reject, cancel, undo, history, and detail, but no wrapper for last session,
  resume-last, or event replay.
- `AgentView.tsx`, `useAgentStore.ts`, `AgentHistory.tsx`, and
  `ToolCallCard.tsx` already implement a fairly rich live agent UI:
  streaming chunks, active agent id, budget updates, tool calls, approval /
  rejection, cancel, operation history, and undo.
- `useAgentStore.ts` handles event types including `session_started`,
  `tool_call_*`, `tool_exec_*`, `budget_update`, `handoff_proposed`,
  `handoff_executed`, `done`, `progress`, and `error`.
- Tempdoc 415 confirms recent work around agent session lifecycle
  attribution and observability. The product implication is not metrics; it is
  that session identity and event streams now exist.

**Interpretation:** The backend appears to support a product-grade "continue /
recover / inspect previous agent session" workflow, while the UI currently
stops at operation history and undo. This is materially different from generic
history: it lets a user recover from refresh, inspect why an agent got stuck,
or hand a prior session back to the agent with context.

**Unused feature:** Agent session resume and event replay.

**Recommended slice:**

- Add API wrappers for last session, resume-last stream, and session events.
- In Agent view, show "Resume last session" when a resumable session exists.
- In History detail, add an event timeline view for tool calls, approvals,
  handoffs, budget updates, and final state.
- Keep this as an agent workflow feature, not a telemetry dashboard.

**Priority:** Very high. This now competes with command-bar ask as a top
candidate because it is already backed by explicit backend endpoints and
directly supports long-running user/agent workflows.

#### Slice L - MCP Answer Facets and Full-Document Retrieval Discoverability

**Classification:** Agent-visible-but-broken / agent-visible-but-undocumented.

**Evidence:**

- `scripts/prod/justsearch-mcp/server.mjs` describes `justsearch_answer` as
  returning facets for top sources and entities.
- The same tool runs a sidecar search for facets, but parses
  `JSON.parse(facetRes.body)` even though `httpPostJsonLimited` returns
  response text under `text`. This likely prevents answer-side facets and
  facet-derived hints from appearing.
- `server.mjs` forwards `return_full_documents` to
  `/api/knowledge/retrieve-context` when present.
- `RetrieveContextParams.java` has `returnFullDocuments`.
- `AnswerInputSchema` does not document `return_full_documents`; it is accepted
  only because the schema uses `.passthrough()`.
- Recent agent workflow tempdocs noted that agents want entity facets,
  source-scoped retrieval, and full-document context. Most of that substrate
  now exists, but the most discoverable agent tool hides or breaks part of it.

**Interpretation:** This is a sharp agent-facing feature gap. The system has
done the hard work of adding richer retrieval controls, but the primary MCP
answer tool does not reliably advertise or return the discovery aids an agent
needs for scoped follow-up calls.

**Unused feature:** Facet-guided answer refinement and full-document answer
mode for AI agents.

**Recommended slice:**

- Fix `facetRes.body` to parse `facetRes.text`.
- Add `return_full_documents` to `AnswerInputSchema` with a clear description
  and cautions.
- Consider exposing `expectedAnswerType` or source-detection hints from search
  beside answer context when cheap.

**Priority:** Very high for AI-agent usability. This is smaller than a UI
feature and likely has outsized agent payoff.

#### Slice M - Built-In Agent Tool Surface Is Stronger Than Its Product Story

**Classification:** Implemented-underexplained / agent-visible-but-narrow.

**Evidence:**

- `agentProfiles.ts` defines a primary agent and an organizer agent. The
  organizer has `search_index`, `browse_folders`, `file_operations`, and
  `ingest_files`; the primary is read-focused and can hand off write/ingest
  tasks.
- `ToolRegistry.java` reserves generated `handoff_to_*` tools for internal
  multi-agent handoff.
- `SearchTool.java` supports `query`, `limit`, `mode`, fine-grained retrieval
  pipeline controls, and `path_prefix`.
- `BrowseTool.java` supports top-level roots, relative parent resolution,
  file/folder listing limits, and fallback behavior.
- `IngestTool.java` can ingest directories through Worker-side ScanRoot RPC
  and individual files through the ingest callback, with limits and root
  validation.
- `FileOperationsTool.java` supports move, rename, mkdir, copy, conflict
  strategies, root validation, and undoable operation history.
- `AgentLoopService.java` injects roots into the system prompt, requires search
  before factual answers, supports handoff tools, handles context compression,
  and streams budget updates.

**Interpretation:** This is already close to a safe "AI librarian / organizer"
workflow: search, browse, ingest, organize, require approval, and undo. The
user-facing opportunity is not inventing more tools; it is making the existing
workflow discoverable and recoverable.

**Unused feature:** Safe knowledge-base organization by a specialized agent.

**Recommended slice:**

- Present the organizer as a specific mode or suggested action when the user is
  in Library/Browse with selected files or roots.
- Make approval/undo/history the visible trust contract.
- Pair this with session resume/replay so users can recover and audit longer
  organization runs.

**Priority:** High, but only if it rides on the existing safety/history UI.
Without resume/replay it risks feeling powerful but brittle.

#### Slice N - Search Explanation Has More Backend Data Than UI Mapping

**Classification:** Implemented-hidden.

**Evidence:**

- `KnowledgeSearchResponse` includes `queryUnderstanding` with
  `expectedAnswerType`.
- Tempdoc 385 records structured query analysis, temporal query extraction,
  answer type classification, per-source retrieval, and source-aware labels.
- MCP `justsearch_search` returns `queryUnderstanding` and
  `filterNormalization`.
- Frontend `SearchResponse` maps facets, entity variants, index capabilities,
  pipeline execution, per-hit provenance, matched fields, match spans, and
  excerpt regions, but not `queryUnderstanding` or `filterNormalization`.
- `ResultRow.tsx` already displays matched-field pills, excerpts, source,
  author, category, relevance, and approximate first match line. It does not
  expose per-hit provenance even though it is mapped through.

**Interpretation:** The UI already has enough result explanation to avoid a
heavy debug panel. The missing value is query-level explanation: "we understood
this as source/author/date/person/type" and "these filters/boosts were
applied." For agents, this data already appears in MCP search and can guide
follow-up. For users, it is absent.

**Unused feature:** Query understanding and normalized filter explanation.

**Recommended slice:**

- Map `queryUnderstanding` and `filterNormalization` in frontend search types.
- Show a compact explanation row only when something non-obvious was inferred:
  source, author, temporal range, expected answer type, normalized filter, or
  boost.
- Avoid exposing raw pipeline timing by default.

**Priority:** Medium-high. Keep behind command ask and agent resume, but it
would improve trust and make search feel more intelligent without adding a new
workflow.

#### Slice O - Effective Config and Session Policies as Agent/System-State Tools

**Classification:** Diagnostic-only with agent-facing potential.

**Evidence:**

- `EffectiveConfigController.java` exposes `/api/debug/effective-config`,
  including schema version, captured time, process identity, config sources,
  policy bridge, inference runtime config, and resolved config chain.
- `SessionPoliciesController.java` exposes `/api/debug/session-policies`,
  returning Worker-authoritative runtime/model policy snapshots and explicit
  error states when the worker is unavailable.
- `DiagnosticsController.java` can export a redacted diagnostics zip including
  install state, pack state, UI settings, policy, GPU capabilities, logs,
  telemetry tails, crash reports, debug state, and status snapshots.
- `LogLevelController.java` supports runtime log-level inspection and changes.
- `TimeSeriesController.java` exposes curated metrics and availability.

**Interpretation:** Most of this should not become normal UI. It is too sharp
and too debug-shaped. But it is exactly the kind of substrate an AI agent or
support workflow needs before explaining "why search/AI/indexing behaves this
way."

**Unused feature:** Read-only system-state explanation for agents and support.

**Recommended slice:**

- Add a read-only built-in agent tool that summarizes readiness, config source,
  model/policy status, queue health, and recent diagnostic flags.
- Optionally add a Help/Health action that copies or exports this summary.
- Do not build a broad debug console.

**Priority:** Medium. Valuable, but subordinate to retrieval parity and session
resume.

#### Slice P - Indexing Onboarding and Cleanup Are Mostly Already Surfaced

**Classification:** Implemented, not a major unused feature.

**Evidence:**

- `indexing.ts` exposes suggested roots, exclude dry-run/apply, migration,
  failed jobs list, and failed jobs clear.
- `useSources.ts` fetches suggested roots when no roots are configured.
- `LibraryView.tsx` wires preview/apply excludes and shows results.
- `HealthView.tsx` wires failed job listing/clearing and rebuild/migration
  controls.

**Interpretation:** This was a tempting lane, but most of the obvious
capability is already user-facing. The main remaining opportunity is narrative
placement, not a hidden feature.

**Disposition:** Do not prioritize in this investigation.

#### Slice Q - Action Panel Is Real, But Not Yet an AI Command Surface

**Classification:** Implemented-partial.

**Evidence:**

- `ActionPanel.tsx` is a global command palette with filtering, keyboard
  navigation, confirmation dialogs, unavailable-action disclosure, and
  selection context.
- Second pass found that command input help promises `??` / `ask ...`, but
  `??` only logs and `ask ...` falls through to search.

**Interpretation:** The action panel is more mature than the command-input ask
path. It could become the visible place for AI operations without forcing all
AI interaction through a chat surface.

**Recommended slice:**

- Add explicit actions such as "Ask about selection", "Summarize selection",
  "Find related documents", and "Organize selected files" when scope is valid.
- Route those actions through the same operation model as command ask and
  Inspector AI.

**Priority:** High as part of WP5/WP6, not separate.

### Third-Pass Candidate Additions

#### C20 - Agent Session Resume and Replay

**Persona:** User and AI agent operator.

**State:** Backend endpoints exist; frontend live/session UI exists; wrappers
and product affordance are missing.

**Why unused:** The session continuation endpoints are not reachable from the
current UI API domain.

**Payoff:** High. Makes long-running agent work recoverable, inspectable, and
less scary.

**Risk:** Moderate. Resume semantics must be explicit: resumed sessions should
show prior context and pending approval state clearly.

**Disposition:** Promote to top tier.

#### C21 - MCP Answer Facet Sidecar Fix

**Persona:** External AI agents using JustSearch MCP.

**State:** Intended in MCP server; likely broken due `facetRes.body` vs
`facetRes.text`.

**Why unused:** Hints/facets likely never appear in `justsearch_answer`.

**Payoff:** High. Agents can self-scope by source/entity without exploratory
round trips.

**Risk:** Low. Small fix, easy to test with mocked response or live MCP call.

**Disposition:** Promote to top tier.

#### C22 - Document `return_full_documents` for MCP Answer

**Persona:** AI agents doing synthesis over small scoped sets.

**State:** Backend and MCP forwarding exist; MCP schema does not document it.

**Why unused:** Agents will not know the parameter exists.

**Payoff:** Medium-high. Directly answers the agent need for full-doc context
without building another tool.

**Risk:** Token blowups. Requires clear schema description and maybe limits.

**Disposition:** Bundle with C21.

#### C23 - Safe Organizer Agent Workflow

**Persona:** User with messy indexed folders; AI agent acting as organizer.

**State:** Mostly implemented through profiles, handoff tools, file operations,
approval, history, and undo.

**Why unused:** The workflow is technically present but not introduced as a
clear product mode.

**Payoff:** High if trust UX is clear.

**Risk:** High if presented casually. File operations must stay approval-gated
and audit-friendly.

**Disposition:** Productize after or alongside C20.

#### C24 - Query Understanding UI Mapping

**Persona:** Search user and agent debugger.

**State:** Backend/MCP have query understanding; frontend does not map it.

**Why unused:** API mapper drops the query-level fields.

**Payoff:** Medium-high. Helps users understand filtered/boosted searches.

**Risk:** Low if rendered only when useful.

**Disposition:** Keep as WP7.

#### C25 - Agent/System-State Tool

**Persona:** Built-in agent, support workflows, power users.

**State:** Debug/status endpoints exist; no safe summarized tool.

**Why unused:** Diagnostics are engineer-shaped endpoints, not agent-facing
summaries.

**Payoff:** Medium. Prevents confused agent answers when AI/indexing/policies
are unavailable or degraded.

**Risk:** Medium. Must redact and summarize; avoid exposing raw debug clutter.

**Disposition:** Do after retrieval parity and session resume.

### Third-Pass Revised Ranking

1. **Agent Session Resume and Replay (C20)** - strongest newly discovered
   product feature because backend endpoints already exist and it changes how
   safe long-running agent work feels.
2. **MCP Answer Facets + Full-Document Discovery (C21/C22)** - smallest
   high-impact agent fix; likely restores a promised capability and exposes an
   existing full-document mode.
3. **Global Command AI Ask (C12/WP5)** - still extremely strong because the UI
   already promises it, but it now shares the top tier with session resume.
4. **Unified AI Operation Model (C13/WP6)** - becomes more important because
   command ask, Action Panel AI actions, Inspector AI, Browse summaries, and
   agent resume should not each invent their own result surface.
5. **Safe Organizer Agent Workflow (C23)** - high payoff, but should lean on
   resume/replay/undo so users trust it.
6. **Search Explanation Micro-Surface (C15/C24/WP7)** - valuable and enabled,
   but a second-order trust feature after the main AI workflows work.
7. **Built-in Agent Retrieval Parity (C6)** - still needed: built-in agents
   should get a read-only answer/retrieve-context tool, not only search.
8. **Agent/System-State Tool (C19/C25)** - useful once agents are doing more
   real work; keep scoped and read-only.

### Current Synthesis After Three Passes

The strongest pattern is no longer "there are hidden endpoints." It is that
JustSearch now has most of the substrate for **trustworthy agentic knowledge
work**:

- scoped retrieval and answer context;
- source/entity facets and query understanding;
- browse/search/ingest/file-operation tools;
- approval-gated writes and undo;
- session identity, events, and history;
- diagnostics that can explain whether the system is ready.

The missing product layer is the connective tissue:

- recover and replay agent sessions;
- let command/action/Inspector AI share one scoped operation model;
- make agent retrieval controls discoverable and reliable;
- reveal just enough search/query understanding for trust.

That is deeper than "add more buttons." The codebase is converging on a system
where a user can ask an AI agent to investigate, summarize, organize, or
maintain a local knowledge base with evidence and reversible actions. The next
features should reinforce that loop rather than adding unrelated surfaces.

### Fourth Investigation Pass - Evidence Loop, Suggestions, and Disclosure Sweep

This pass pushed into surfaces that had not been deeply classified yet:
autocomplete/suggestions, citation verification, preview provenance, desktop
notifications, progressive disclosure, and settings-backed behavior.

#### Slice R - Citation Trust Loop Is More Complete Than Expected

**Classification:** Implemented, strategically reusable.

**Evidence:**

- `RetrieveContextController` exposes `/api/knowledge/match-citations` for
  post-hoc answer sentence to source-chunk matching.
- `RagStreamingHandler` emits `citation_matches` after the main `done` event.
- `streams.ts` explicitly handles `citation_matches` as a non-terminal
  post-done event.
- `useAppAI.ts` injects `[N]` citation markers into answer text after matches
  arrive and enriches cross-encoder matches with the original RAG citation
  metadata.
- `MarkdownRenderer.tsx` renders citation buttons and wraps them with
  `CitationHoverCard` when citation metadata is available.
- `InspectorContext.tsx` lists sources, shows a one-time trust-loop nudge, and
  lets users click a source.
- `usePreviewState.tsx` switches the Inspector to Preview, loads the correct
  document or nearby slice, and highlights the cited span by offset or excerpt
  fallback.

**Interpretation:** This is not a hidden feature; it is one of the strongest
already-implemented user trust loops in the product. The underused opportunity
is to make every AI entry point land in this same loop. Command ask, Action
Panel AI actions, Browse summaries, and future agent answer tools should not
invent alternate answer displays.

**Recommended slice:** Treat Inspector answer + sources + preview jump as the
canonical AI evidence surface. Any new ask/summarize entry point should route
there or embed the same component model.

**Priority:** High as a design constraint, not as a standalone feature.

#### Slice S - VDU Enrichment Is Returned But Not Productized

**Classification:** Implemented-hidden / diagnostic-adjacent.

**Evidence:**

- `PreviewController` returns `vduStatus`, `vduPageCount`, and `vduEnrichment`
  from preview metadata.
- `inference.ts` types `vduEnrichment` as a preview response field.
- `InspectorPreview.tsx` only surfaces provenance badges and page count; it
  does not parse or show enrichment content.
- `BrowseView.tsx` carries `vduStatus` on browse file items, but no rich visual
  document-intelligence surface was found.

**Interpretation:** VDU already produces or stores more document intelligence
than the visible UI uses. This could be valuable for images/PDFs: detected
document type, extracted entities, OCR/vision summary, page count, or why a
visual document is searchable.

**Unused feature:** Document intelligence panel for VDU-enriched files.

**Recommended slice:**

- In Preview or Inspector AI context, show a compact "Document intelligence"
  section only when `vduEnrichment` is present.
- Keep the first version read-only and factual: doc type, visual summary,
  extracted entities, page count, provenance.
- Add an Action Panel command such as "Ask about visual extraction" only after
  the data is visibly grounded.

**Priority:** Medium. Valuable for PDFs/images, but should not outrank agent
resume or MCP retrieval fixes.

#### Slice T - Autocomplete Is Implemented, But Not Agent-Useful

**Classification:** Implemented, mostly user-facing; possible agent affordance.

**Evidence:**

- `suggest.ts` calls `GET /api/knowledge/suggest`.
- `useSuggest.ts` debounces requests, cancels in-flight calls, and requires two
  characters.
- `GlobalCommand.tsx` uses the suggestion hook, supports keyboard selection,
  and stores selected suggestions in search history.
- `KnowledgeSearchController.handleSuggest` gates on knowledge-server readiness
  and delegates to `adapter.suggest`.
- No MCP suggestion tool exists.

**Interpretation:** Suggest is not hidden for users. It is, however, absent
from agent workflows. External agents usually do not need UI-style
autocomplete, but a related capability could help agents discover vocabulary,
source names, and entity spellings. That said, facets and queryUnderstanding
are a better agent primitive than raw suggestions.

**Disposition:** Do not add `justsearch_suggest` as a priority. Prefer fixing
answer/search facets and query understanding first.

#### Slice U - Background Notifications Are Narrow But Ready For Agent Resume

**Classification:** Implemented-partial.

**Evidence:**

- `useBackgroundNotifications.ts` sends desktop notifications when an agent
  finishes or errors while the window is hidden.
- `LibraryView.tsx` also notifies when indexing completes while hidden.
- Agent notification body currently reports completion and tool-call count, but
  does not link to a resumable session or event replay.

**Interpretation:** Notifications are already present, which lowers the cost of
making long-running agent workflows feel real. The missing piece is continuity:
clicking or returning after a notification should bring the user to the
completed/resumable session with the event timeline.

**Recommended slice:** When C20 lands, connect background agent notifications
to the last session snapshot and event replay UI.

**Priority:** Medium-high only as part of C20. Not a separate notification
project.

#### Slice V - Progressive Disclosure Has a Reserved Product Tier

**Classification:** Substrate-enabled / partially implemented.

**Evidence:**

- `useDisclosure.ts` defines levels: 0 simple, 1 reserved for future "Power"
  tier, 2 advanced.
- `featureRegistry.ts` centralizes gates for Brain advanced controls,
  Inspector context details, Library reindex/exclude patterns, and search
  filters.
- `SettingsView.tsx` currently toggles simple vs advanced only.
- Earlier passes found several features that are too useful for full advanced
  mode but too detailed for simple mode: query explanation, source/citation
  trust details, organizer mode, and targeted queue/status actions.

**Interpretation:** The reserved level 1 is a real product affordance. It
could host "show me why / let me act safely" features without exposing all
runtime diagnostics.

**Unused feature:** A middle disclosure tier for evidence and workflow control.

**Recommended slice:**

- Define level 1 as "Power" or "Evidence" mode.
- Candidate gates: search explanation chips, expanded citation/context
  details, Action Panel AI operations, agent event replay, and safe organizer
  workflows.
- Keep Brain runtime knobs and raw diagnostics at level 2.

**Priority:** Medium. It is an enabling architecture decision; do not block
C20/C21 on it.

#### Slice W - Resume Error Messages Exist Without Resume UI

**Classification:** Implemented-hidden confirmation.

**Evidence:**

- `errorMessages.ts` already includes `RESUME_FAILED` and
  `UNSUPPORTED_RESUME_STATE`.
- Generated locale files include "Failed to resume agent session" and "Cannot
  resume this agent session."
- `agent.ts` still has no wrappers for `session/last`,
  `resume-last/stream`, or `session/{id}/events`.

**Interpretation:** The frontend error vocabulary already anticipates resume,
but the user-visible resume workflow is absent. This strengthens C20 rather
than creating a new candidate.

**Disposition:** Fold into C20.

### Fourth-Pass Candidate Additions

#### C26 - Canonical AI Evidence Surface

**Persona:** User asking questions or summarizing selected files.

**State:** Inspector trust loop is implemented; other entry points are uneven.

**Why unused:** Not absent, but not yet used as a universal destination for all
AI operations.

**Payoff:** High. Prevents fragmented AI answers and makes every answer
click-to-verify by default.

**Risk:** Low. Mostly routing and component reuse.

**Disposition:** Make this a constraint on WP5/WP6.

#### C27 - VDU Document Intelligence Panel

**Persona:** User inspecting visual PDFs/images; AI agent interpreting visual
document extraction quality.

**State:** Preview endpoint returns enrichment; UI only shows provenance badge.

**Why unused:** The enrichment payload is typed but not rendered.

**Payoff:** Medium. Makes visual-document work legible and could improve trust
in VDU extraction.

**Risk:** Medium. Must parse enrichment defensively and avoid overclaiming
vision results.

**Disposition:** Medium-priority product slice after main AI workflow work.

#### C28 - Notification-to-Session Continuity

**Persona:** User who starts a long agent task and backgrounds the app.

**State:** Desktop notifications exist; resumable session UI does not.

**Why unused:** Notifications are terminal pings, not re-entry points.

**Payoff:** Medium-high when combined with session replay.

**Risk:** Low to medium; depends on correct session identity.

**Disposition:** Bundle into C20.

#### C29 - Power/Evidence Disclosure Tier

**Persona:** User who wants more explanation and control, but not raw
diagnostic/runtime controls.

**State:** Disclosure level 1 exists in code but has no product mode.

**Why unused:** Settings only expose simple vs advanced.

**Payoff:** Medium. Gives a natural home to trust/explanation features.

**Risk:** Product naming and information architecture.

**Disposition:** Plan after top workflow fixes; use it to avoid dumping
everything into Advanced.

### Fourth-Pass Ranking Adjustment

The top ranking does not change, but two constraints become clearer:

1. **C20 Agent Session Resume and Replay** should include notification re-entry
   and event timeline, because notifications and resume errors already exist.
2. **C12/C13 Command Ask and Unified AI Operations** should use the existing
   Inspector evidence loop as their default answer surface.
3. **C21/C22 MCP retrieval fixes** remain the fastest agent-facing win.
4. **C26 Canonical AI Evidence Surface** is not a separate project; it is the
   product rule that keeps AI features coherent.
5. **C27 VDU Document Intelligence** is the best newly found medium-priority
   feature for non-agent users, especially for PDFs/images.

### Updated Deep Synthesis

The deeper pattern is now even sharper:

- Agent workflows need continuity: session resume, replay, notification
  re-entry, and undo history.
- AI answer workflows need evidence: citations, hover previews, source lists,
  preview jumps, and context metadata.
- Retrieval tools need discoverability: facets, full-document mode, normalized
  filters, and query understanding.
- Visual-document features need explanation: VDU provenance is visible, but VDU
  enrichment is not yet turned into a user-understandable document
  intelligence surface.

The highest-leverage product direction is therefore:

**Make every AI/agent action resumable, scoped, and verifiable.**

That sentence should drive the next slices better than "add chat" or "add more
debug panels." It captures what the current codebase is already unusually close
to supporting.

### Fifth Investigation Pass - Action Bridges and Help-to-Workflow Mismatches

This pass looked for places where the product already teaches, names, or models
a workflow but does not actually connect it to the implementation. The strongest
findings are around Action Panel AI operations, help/onboarding routes, and
agent run persistence.

#### Slice X - Action Panel Has an AI Ask Action Model But No Ask Wiring

**Classification:** Implemented-partial / user-visible-but-disabled.

**Evidence:**

- `models/actions.ts` defines an `ask` action:
  label `Ask a question...`, AI category, `canAsk` gating, selection gating,
  disabled reason handling, and telemetry name `action_ask`.
- `App.tsx` renders the global `ActionPanel` with `onAsk: undefined` and a
  comment: `wired in explainable-actions (focus Inspector input)`.
- `ContextMenuLayer.tsx` also passes `onAsk: undefined`.
- Inspector Q&A is real: `InspectorAIInput.tsx` calls `onAskQuestion`, and
  `useAppAI.ts` streams `/api/ask/stream`.
- The Action Panel's selection context is based on `selectedIds`, not the
  current highlighted/inspected result. A user can have a visible selected
  row/Inspector item while Action Panel says no selection.

**Interpretation:** This is a stronger version of C12/C13 than previously
stated. The code does not merely "need a command ask path"; it already has an
action definition for ask, a global panel to run it, and the Inspector operation
that can answer. The missing piece is scope resolution and focus/routing.

**Unused feature:** Action Panel "Ask a question" over selected or inspected
files.

**Recommended slice:**

- Resolve AI action scope in one helper:
  checkbox selection > inspected item > active result/cursor > current result
  set/query context.
- Wire Action Panel `onAsk` to open Inspector AI and focus the question input
  with that resolved scope.
- Wire Context Menu `Ask` for a single file.
- Reuse the existing Inspector evidence loop for the answer.

**Priority:** Very high. This is now the cleanest concrete implementation
slice for WP5/WP6.

#### Slice Y - Help Teaches Workflows That Do Not Execute

**Classification:** User-visible mismatch.

**Evidence:**

- `HelpView.tsx` says: "Type ?? followed by your question in the search bar to
  ask the AI about your selected files or general questions."
- `GlobalCommand.tsx` changes visual mode for `??` and `ask`, including a chat
  textarea and placeholder.
- `App.tsx` handles `??` by logging `Chat command` and returning; `ask ...`
  does not get special submit handling and can fall through as a search query.
- `HelpView.tsx` says `/help` shows available commands, while `App.tsx`
  handles `/help` by logging command names only.
- `Stage.tsx` renders a command-mode empty state that tells users to try
  `/help` for commands.

**Interpretation:** This is not just missing polish. Help, empty states, and
input mode affordances currently create a false promise for AI chat and command
help. Because the actual Inspector AI path exists, the right fix is to route
the promise into a real scoped Q&A operation, not remove the affordance.

**Recommended slice:**

- Implement `?? question` as a scoped ask operation using the same scope helper
  as Action Panel Ask.
- Implement `/help` as an in-app command list or route to Help with command
  section expanded.
- If whole-index general questions are not supported yet, say so explicitly and
  prefer selection/current-result scope.

**Priority:** Very high, bundled with C12/C30.

#### Slice Z - Launchpad Help Cards Search Instead Of Acting

**Classification:** Implemented, under-powered.

**Evidence:**

- `LaunchpadGrid.tsx` exposes help topic cards for Getting Started, AI
  Features, and Troubleshooting.
- Clicking a help card sets a search query, relying on built-in help documents
  being indexed.
- The same Launchpad has direct navigation buttons for Library, Brain, Health,
  and Actions.

**Interpretation:** Search-driven help is clever and consistent with the
product. But the cards are currently informational, not task-oriented. "AI
Features" could route to Brain when AI is unavailable or to Inspector/Agent
when ready; "Troubleshooting" could route to Health; "Getting Started" already
has nearby Library CTA behavior.

**Unused feature:** Help-to-action onboarding cards.

**Recommended slice:**

- Keep search-backed help as secondary.
- Add primary actions per topic based on app state:
  Getting Started -> Library add-folder CTA;
  AI Features -> Brain/Inspector/Agent depending on AI availability;
  Troubleshooting -> Health or diagnostics export.

**Priority:** Medium. Useful, but below the direct AI action mismatches.

#### Slice AA - Agent Run Store Enables Transcript Export / Audit View

**Classification:** Implemented-hidden / substrate-enabled.

**Evidence:**

- `AgentRunStore.java` is a durable append-only run store with schema version,
  session metadata, messages, active agent id, handoff history, checkpoints,
  resumable state, and event NDJSON.
- It prunes old run directories after 30 days.
- `AgentController` exposes last snapshot and per-session events.
- `AgentHistory.tsx` currently shows operation batches and undoable operations,
  not full run transcripts or event streams.

**Interpretation:** C20 should not be framed only as "resume the last session."
There is enough durable state for an audit-style session transcript: messages,
tool proposals, approvals/rejections, execution outputs, handoffs, budget
updates, and final response.

**Unused feature:** Exportable / inspectable agent session transcript.

**Recommended slice:**

- Add an event timeline to Agent History or a Session detail panel.
- Include "Copy transcript" or "Export session" after the event replay UI is
  stable.
- Keep write-operation undo history distinct from session transcript history,
  but link them where execution ids match.

**Priority:** High as part of C20.

#### Slice AB - Session-Only Settings Are Safely Handled But Weakly Explained

**Classification:** Implemented, minor UX opportunity.

**Evidence:**

- `settingsMode` can be `read_write` or `in_memory`.
- `useSettings.ts` exposes `readOnly` when `settingsMode === 'in_memory'`.
- `SettingsView.tsx` shows a `Session-only` badge and hides reset-to-defaults
  in read-only mode.
- `useSystemStore.ts` still optimistically updates and caches settings locally,
  but skips network flush in read-only mode.

**Interpretation:** This is not a major unused feature, but it is a subtle
local-first affordance. It could matter for managed/portable/demo sessions:
users can safely change settings for the current session without persistence.

**Disposition:** Do not prioritize. At most, improve the badge tooltip/copy
when touching Settings anyway.

#### Slice AC - Agent State Machine Hints Are Not Product-Ready

**Classification:** Not a feature yet.

**Evidence:**

- `AgentState.java` documents PRIMARY searching/deciding states and notes
  organizer executing/completing states as reserved work.
- Current product value is already captured by profiles, handoff, approvals,
  undo, run store, and session replay.

**Interpretation:** It is tempting to productize named agent states, but doing
  so now would expose implementation detail. The better user feature is a plain
  timeline: searching, asking approval, running tool, handing off, done.

**Disposition:** Avoid separate state-machine UI for now.

### Fifth-Pass Candidate Additions

#### C30 - Action Panel Ask Wiring

**Persona:** User who selected or inspected files and opens the command palette.

**State:** Action model exists; Inspector Q&A exists; `onAsk` is intentionally
undefined.

**Why unused:** Missing scope resolver and focus/routing into Inspector AI.

**Payoff:** Very high. It turns an existing disabled action into a natural AI
workflow.

**Risk:** Low to medium. The main risk is ambiguous scope; solve with explicit
scope display in Inspector.

**Disposition:** Promote into the top implementation slice for WP5/WP6.

#### C31 - Real `??` / `/help` Command Handling

**Persona:** Keyboard-first user following built-in help.

**State:** UI mode and help text exist; submit handlers only log or search.

**Why unused:** Command parser has no bridge to Inspector AI or Help command
list.

**Payoff:** High. Removes false promises from the core input.

**Risk:** Medium. Whole-index ask semantics need a clear decision.

**Disposition:** Bundle with C30 under one scoped-AI-command implementation.

#### C32 - Help-to-Action Launchpad

**Persona:** New or stuck user.

**State:** Help cards and nav actions exist separately.

**Why unused:** Topic cards only set search queries.

**Payoff:** Medium. Better onboarding and recovery without inventing a new
view.

**Risk:** Low. Keep search help as fallback.

**Disposition:** Later polish after AI command/action wiring.

#### C33 - Agent Session Transcript Export

**Persona:** User auditing an agent run; AI agent operator debugging a task.

**State:** Durable run metadata and events exist.

**Why unused:** UI exposes operation history, not session transcript/event
history.

**Payoff:** High when paired with resume/replay.

**Risk:** Medium. Need redaction and clear distinction between chat transcript,
tool outputs, and undoable operations.

**Disposition:** Add to C20's second stage.

### Fifth-Pass Ranking Adjustment

1. **C30 Action Panel Ask + C31 `??` command handling** now become the most
   concrete user-facing slice: the model, UI, help, and backend operation
   already exist, but the bridges are missing.
2. **C20 Agent Session Resume/Replay/Transcript** remains the strongest
   agent-workflow feature. The run store shows this can become an audit view,
   not only "resume last."
3. **C21/C22 MCP answer facets/full-doc discovery** remains the fastest
   external-agent improvement.
4. **C26 Canonical AI Evidence Surface** becomes the rule for C30/C31: every
   new ask entry point should land in the Inspector evidence loop.
5. **C27 VDU Document Intelligence** remains the best medium-priority
   non-agent feature discovered after the top workflow fixes.

### Fifth-Pass Synthesis

The investigation is converging on two missing bridge layers:

1. **Intent bridge:** Help, command input, Action Panel, context menus, and
   Launchpad already express user intent. They need to resolve scope and route
   into the existing operations.
2. **Continuity bridge:** Agent run store, notifications, history, undo, and
   session endpoints already preserve work. They need to become a resumable,
   inspectable session experience.

This strengthens the previous thesis. The best next direction is not to add a
new AI surface. It is to connect the surfaces that already exist so the product
keeps its promises.

### Sixth Investigation Pass - Contract and MCP Schema Gaps

This pass looked for places where the API/MCP contracts are ahead of the
product surfaces. The goal was not to find every internal diagnostic, but to
find narrow bridges that would let users and external AI agents do real work
with capabilities already present in code.

#### Slice AD - MCP Preview Schema Exists Without Production Tool

**Classification:** Strong unused external-agent feature.

**Evidence:**

- `scripts/prod/justsearch-mcp/schemas.mjs` defines `PreviewInputSchema` and
  `PreviewOutputSchema`.
- The production MCP server registers `justsearch_answer`,
  `justsearch_search`, `justsearch_ingest`, and `justsearch_status`, but not a
  preview tool.
- The app already has `/api/preview` and preview/citation-highlight UI paths.
- `justsearch_search` intentionally returns slim previews, usually capped to a
  short excerpt, and tells agents to use answer for assembled evidence.

**Interpretation:** External agents can discover files and ask evidence-backed
questions, but they cannot reliably open a document region after discovery.
That leaves a gap between search result and source inspection. The schema says
"preview is a tool-shaped concept"; production registration stops one step
short of making it available.

**Unused feature:** `justsearch_preview`, a read-only MCP tool for opening a
document by `docId` with `maxChars` and optional offset/windowing.

**Payoff:** High for AI agents. It would make search -> inspect -> answer
workflows materially better, and it would support citation verification,
source triage, and "read the important docs" workflows without asking the
assistant to infer from short snippets.

**Risk:** Low to medium. The main risk is accidentally exposing huge content.
Keep conservative caps, return provenance, and preserve the read-only
annotation.

**Disposition:** Promote as part of the MCP retrieval package with C21/C22.

#### Slice AE - MCP Suggest Schema Exists Without Production Tool

**Classification:** Real but lower-priority unused agent affordance.

**Evidence:**

- `SuggestInputSchema` and `SuggestOutputSchema` exist in the production MCP
  schema module.
- The production server does not register `justsearch_suggest`.
- Autocomplete already exists as a UI/user feature, so this is not a missing
  end-user surface.

**Interpretation:** Suggest is less important for agents than preview, facets,
or full-document retrieval. Still, it could be useful as a lightweight
vocabulary/entity discovery tool before a search.

**Disposition:** Do not prioritize alone. Consider registering only if bundled
with a broader MCP "explore the index" improvement.

#### Slice AF - Search Explanation Contract Is Dropped at the UI Boundary

**Classification:** Contract-to-UI mismatch; reinforces C15/C24.

**Evidence:**

- Search fixtures and MCP output include query understanding and filter
  normalization style information.
- `SearchResponseSchema` preserves hits, facets, entity facet variants,
  index capabilities, and pipeline execution, but not top-level
  `queryUnderstanding` or `filterNormalization`.
- Contract tests intentionally validate the fields the UI maps today; they do
  not assert preservation of those query-level explanation fields.

**Interpretation:** Search explanations are not just a missing panel. The UI
contract currently discards the data that would make a precise explanation
possible. MCP already exposes more of the reasoning shape than React does.

**Unused feature:** A "why these results" or "query interpreted as..." surface
fed by preserved query-understanding/filter-normalization fields.

**Disposition:** Treat as a mapper/schema task before any UI. Otherwise the UI
will be forced to explain from weaker derived state.

#### Slice AG - Readiness Contract Can Become Capability Explanation

**Classification:** User/agent feature, not another operations dashboard.

**Evidence:**

- `SystemStatus` includes a typed readiness envelope with component states,
  reason codes, composites for retrieval and AI features, search config,
  encoder profiles, queue health, GPU state, and grouped schema/embedding/chunk
  coverage.
- `HealthView` and `StatusDeck` surface a useful operational subset:
  connection, index health, failures, queues, GPU, AI mode, restart/reindex
  actions, and derived events.
- `deriveHealthEvents` already translates several readiness reason codes into
  user-facing messages, but the product does not expose a compact answer to:
  "What can I do right now, and why is this action unavailable?"

**Interpretation:** The rich status contract should not become a bigger debug
wall. The stronger product feature is a capability explanation layer:
retrieval ready, AI ask ready, document preview ready, indexing busy, vision
available, agent tools available, with short reasons and next action when a
capability is unavailable.

**Unused feature:** A user-facing and MCP-facing `system_state`/capability
summary derived from readiness composites and the existing inference/search
status fields.

**Disposition:** Keep behind C30/C31 and C20. It becomes most valuable when
actions and agent sessions need to explain why a command can or cannot run.

#### Slice AH - Preview Completes the Citation Trust Loop

**Classification:** Bridge feature.

**Evidence:**

- `justsearch_answer` already has optional citation verification behavior.
- The UI has source/citation highlighting paths.
- Preview schemas and HTTP preview exist, but MCP lacks preview registration.

**Interpretation:** Answer plus citation verification tells an agent whether
citations match. Preview lets the agent and user inspect the cited document
region afterward. Together they form a verifiable loop:
answer -> citation match -> open source region -> continue.

**Disposition:** Bundle with Slice AD. The point is not just "read a file"; it
is verifiable source inspection.

### Sixth-Pass Candidate Additions

#### C34 - Production MCP `justsearch_preview`

**Persona:** External AI agent inspecting indexed sources after search or
answer.

**State:** Schema and HTTP endpoint exist; production MCP registration is
missing.

**Payoff:** High. It makes external-agent workflows more trustworthy and less
snippet-bound.

**Risk:** Low to medium. Needs content caps and source/provenance hygiene.

**Disposition:** High priority in the external-agent package.

#### C35 - Production MCP `justsearch_suggest`

**Persona:** External AI agent exploring vocabulary before searching.

**State:** Schema exists; production MCP registration is missing.

**Payoff:** Low to medium. Useful, but not as central as preview/facets/full
documents.

**Disposition:** Optional bundle item, not a standalone priority.

#### C36 - Capability Explanation Layer

**Persona:** User or agent trying to understand whether search, AI ask,
preview, vision, or indexing actions are currently available.

**State:** Readiness/status contract is rich; UI surfaces operational slices.

**Payoff:** Medium to high once actions are wired. It prevents commands from
failing silently or feeling arbitrary.

**Risk:** Medium. It must stay concise and not become a diagnostics dump.

**Disposition:** Build as a derived summary, not a new raw status page.

#### C37 - Preserve Query Understanding in UI Search Contract

**Persona:** User asking why a search returned these results; agent explaining
how it interpreted a query.

**State:** Backend/MCP expose richer query interpretation data; UI schema and
tests omit it.

**Payoff:** Medium. It enables a proper search explanation surface.

**Risk:** Low. Mostly mapper/schema/test work if backend field shape is stable.

**Disposition:** Prerequisite for C15/C24.

#### C38 - Citation-to-Preview Verification Flow

**Persona:** User checking an AI answer; external agent validating source use.

**State:** Answer citation verification and preview substrate exist separately.

**Payoff:** High for trust. It turns citations from labels into inspectable
evidence.

**Risk:** Medium. Needs clear handling when source offsets are unavailable.

**Disposition:** Bundle with C34 and C26.

### Sixth-Pass Ranking Adjustment

1. **C30 Action Panel Ask + C31 `??` command handling** remain the best
   user-facing bridge. They turn existing promises into working entry points.
2. **C20 Agent Session Resume/Replay/Transcript** remains the strongest
   continuity feature.
3. **C21/C22/C34/C38 MCP retrieval package** is now the clearest external-agent
   slice: answer facets, full-doc option documentation, preview registration,
   and citation-to-preview inspection.
4. **C37 Search contract preservation** should precede any polished "why these
   results" UI.
5. **C36 Capability explanation layer** should support actions and agents, not
   become another Health dashboard.
6. **C27 VDU Document Intelligence** stays as the best medium-priority
   document-understanding surface.

### Sixth-Pass Synthesis

The deeper pattern is that the contracts are ahead of the product boundaries.
Search knows more than the UI preserves. MCP schemas name tools the production
server does not register. Status knows why capabilities are ready or blocked,
but the product mostly presents operational health.

The next good work is not to expose every field. It is to create narrow,
trust-building bridges:

1. User intent -> scoped AI action.
2. Agent discovery -> source preview.
3. Answer citation -> inspectable evidence.
4. Raw readiness -> capability explanation.

That would make JustSearch feel less like separate search, health, and AI
systems, and more like one coherent local knowledge workspace that can explain
and continue its work.

### Seventh Investigation Pass - Preview, Citation, and Document Intelligence

This pass checked whether the preview/citation thesis was only theoretical.
It is not. The UI already implements a surprisingly complete verification
loop, but it is mostly trapped inside the Inspector.

#### Slice AI - Inspector Preview Is Already a Verification Engine

**Classification:** Existing user feature with underused agent/export value.

**Evidence:**

- `usePreviewState` supports paged preview loading, abort/debounce on rapid
  target switching, citation-focused loading, citation highlighting, fallback
  excerpt matching, and "Load nearby" recovery.
- `InspectorPreview` supports raw/Markdown view, partial preview pagination,
  retry, and VDU provenance badges.
- E2E tests cover preview highlighting, citations, citation slices outside the
  current preview page, and answer-first Inspector behavior.

**Interpretation:** Preview is not a passive text box. It is already a source
verification engine: it can jump from an answer citation into a document slice,
warn when offsets do not match, and recover by loading a nearby page.

**Unused feature:** Make this verification engine available as a first-class
workflow outside the Inspector tab:

- "Open cited source" from answer cards and agent transcripts.
- "Verify answer sources" as a small checklist/timeline.
- MCP `justsearch_preview` with the same offset/window semantics.

**Disposition:** This strengthens C34/C38. Preview should be treated as the
trust primitive for both users and agents.

#### Slice AJ - VDU Enrichment Crosses the API Boundary But Has No Surface

**Classification:** Medium-priority unused document-intelligence feature.

**Evidence:**

- `PreviewResponse` includes `vduEnrichment`, `vduStatus`, `vduProcessed`,
  `vduPageCount`, and `textProvenance`.
- The preview hook stores `textProvenance` and `vduPageCount`, but ignores
  `vduEnrichment`.
- `InspectorPreview` renders a provenance badge and page count, not the
  enrichment payload.
- Worker/service tests show enrichment shaped like JSON metadata, for example
  document type.

**Interpretation:** The current UI tells the user that VDU processed a
document, but not what VDU learned. That is a missed bridge from extraction
quality to document understanding.

**Unused feature:** A compact "Document Intelligence" panel in Preview or
Context that parses safe VDU enrichment fields such as document type,
detected entities, visual summary, and page-level hints.

**Payoff:** Medium. It is most useful for PDFs/images/scans where the visible
text alone is not enough.

**Risk:** Medium. The enrichment schema must be treated as best-effort and
defensive; unknown JSON should not break the Inspector.

**Disposition:** Keep behind the command/action and MCP trust work, but keep
it as the best concrete VDU follow-up.

#### Slice AK - Full-Document RAG Is Implemented Below the Product Surface

**Classification:** Existing capability with incomplete agent/user control.

**Evidence:**

- `RetrieveContextParams` includes `returnFullDocuments`.
- UI/API streaming metadata distinguishes full-document versus preview/chunk
  use.
- Worker RAG code has an explicit `return_full_documents=true` path.
- Production MCP forwards `input.return_full_documents` to the backend, but
  the public MCP schema/documentation issue was already noted in C22.

**Interpretation:** Full-document retrieval is real. The missing product layer
is a controlled way to request it when the task calls for it: "read these
whole documents," "audit this source," or "summarize selected files without
chunk-only context."

**Unused feature:** A scoped "Use full document context" option for selected
documents and MCP answer calls, with token-budget warnings and clear metadata
when the answer used full documents.

**Disposition:** This belongs with C22, but it also supports C30/C31. If the
user asks about selected files, full-document mode may be the more honest
scope than generic RAG.

#### Slice AL - Built-In Agent Search Tool Is Still Snippet-Bound

**Classification:** Internal agent feature gap.

**Evidence:**

- The built-in Java `SearchTool` formats search output from search hits and
  falls back to `content_preview` when no excerpt regions exist.
- That is useful for discovery, but it does not give the built-in agent a
  direct "open this document window" primitive like the Inspector has.

**Interpretation:** The same gap exists twice: external MCP agents lack
production preview registration, and the built-in agent appears to lean on
search snippets. If agent sessions are going to become resumable/auditable,
agents also need source inspection that maps to what the user can verify.

**Unused feature:** A built-in agent preview/read tool, sharing the same
bounded preview API and citation-offset semantics as the Inspector and MCP.

**Disposition:** Lower priority than MCP preview because it touches the
internal agent toolset, but strategically aligned with C20.

### Seventh-Pass Candidate Additions

#### C39 - Inspector Verification Workflow

**Persona:** User checking whether an AI answer is grounded.

**State:** Citation click, preview loading, nearby citation loading, and
warning states already exist.

**Payoff:** High for trust. It makes the existing citation system feel
intentional rather than incidental.

**Risk:** Low to medium. Mostly UX composition around existing primitives.

**Disposition:** Bundle with C26/C38.

#### C40 - VDU Enrichment Panel

**Persona:** User inspecting scanned PDFs/images or visually rich documents.

**State:** Preview response can carry enrichment; UI only shows provenance.

**Payoff:** Medium. Turns VDU from hidden extraction machinery into visible
document intelligence.

**Risk:** Medium. Needs defensive parsing and schema tolerance.

**Disposition:** Best VDU-specific follow-up after higher-priority bridges.

#### C41 - Scoped Full-Document Context Toggle

**Persona:** User or agent asking about selected documents where chunk context
is insufficient.

**State:** Backend supports full-document retrieval; UI/agent controls are
incomplete.

**Payoff:** High for selected-file Q&A and audit tasks.

**Risk:** Medium to high. Needs context-budget communication and fallback.

**Disposition:** Pair with C30/C31 for selected-scope ask flows, and with C22
for MCP.

#### C42 - Built-In Agent Preview Tool

**Persona:** Local agent doing multi-step research inside the user's index.

**State:** Search tool is snippet-oriented; preview API exists.

**Payoff:** Medium. It lets the agent read what the user can verify.

**Risk:** Medium. Needs bounded output and transcript-friendly rendering.

**Disposition:** Later stage of C20/C23, after external MCP preview proves the
shape.

### Seventh-Pass Ranking Adjustment

1. **C30/C31 scoped ask entry points** remain the top user-facing work.
2. **C34/C38/C39 preview and citation verification** now rank just behind
   scoped ask and session continuity. This is the strongest trust-building
   feature cluster.
3. **C20/C33 session resume, replay, and transcript export** should include
   source-preview events so resumed sessions are auditable.
4. **C41 scoped full-document context** should be considered part of selected
   file ask, not merely an advanced backend flag.
5. **C40 VDU enrichment panel** is the best later document-intelligence
   surface.
6. **C42 built-in agent preview tool** should follow the same bounded preview
   contract as MCP.

### Seventh-Pass Synthesis

The product already has the bones of an unusually strong local trust loop:

1. retrieve evidence,
2. answer with citations,
3. click into preview,
4. highlight the cited region,
5. warn when offsets and text disagree,
6. load nearby source text.

That should become a named product behavior, even if the UI never calls it
"verification." The same loop should be available to users, external MCP
agents, and the built-in agent. Once that happens, JustSearch's AI features
stop feeling like answers beside search results and start feeling like a
source-grounded reading workflow.

### Eighth Investigation Pass - Agent Operations, Undo, and Workspace History

This pass moved away from ask/preview/status and looked at the built-in agent
execution system: approvals, tool calls, file operation logs, undo, handoffs,
and the current Agent view. The surprising finding is that this area is more
implemented than earlier passes assumed, but its product framing is still too
isolated.

#### Slice AM - Operation History Exists But Is Not a Workspace Timeline

**Classification:** Existing feature with underused product value.

**Evidence:**

- The agent backend exposes `/api/agent/history` and
  `/api/agent/history/{batchId}`.
- `FileOperationLog` writes one JSON transaction log per batch, records every
  operation, records success/failure/skipped/auto-renamed outcomes, finalizes
  batches, and prunes old logs.
- `AgentHistory` renders a History tab with expandable batches and undo
  buttons.
- The same operation history is not connected to search results, Browse view,
  file context menus, notifications, or session replay.

**Interpretation:** The system already knows "what changed" and "how to undo
it," but the user experiences this mostly as an Agent tab subview. For a local
knowledge workspace, file changes should feel like first-class workspace
events, not chat byproducts.

**Unused feature:** A workspace activity/timeline layer that merges agent file
operation batches, undo actions, handoffs, and source-preview events.

**Payoff:** High. It would make agent write operations feel accountable and
recoverable.

**Risk:** Medium. Needs careful copy and grouping so it does not become noisy.

**Disposition:** Add to the C20/C33 continuity track.

#### Slice AN - Operation History Has a Contract Naming Mismatch

**Classification:** Small concrete bug/gap discovered during feature sweep.

**Evidence:**

- Backend `toBatchSummary` emits `failureCount`.
- Frontend `AgentBatchSummary` and `AgentHistory` expect `failedCount`.
- The UI therefore may not show failed-operation counts even when the
  transaction log contains failed executions.

**Interpretation:** This is not a new feature, but it weakens the trust story.
If the operation history cannot reliably show failures, users have less reason
to treat it as an audit surface.

**Disposition:** Fix opportunistically before expanding the history/timeline
feature. It is a low-risk contract alignment task.

#### Slice AO - Approval Cards Show Tool Calls, Not User-Level Diffs

**Classification:** Under-shaped approval UX.

**Evidence:**

- `ToolCallCard` shows tool name, safety badge, formatted JSON arguments,
  status, output, approve/reject, remove, and undo.
- `ToolCallGroup` supports batch approve, collapse for large batches, and
  safety summaries.
- `file_operations` arguments already contain structured operations with op,
  source, destination, explanation, and conflict strategy.

**Interpretation:** The approval machinery is good. The missing feature is a
human diff: "Move A to B," "Copy C to D," "Create folder E," grouped by root
and conflict strategy. The current raw JSON is acceptable for technical users
but not ideal for approving file changes.

**Unused feature:** A typed approval renderer for known tool schemas, starting
with `file_operations` and `ingest_files`, plus a compact batch preview before
"Approve All."

**Payoff:** High for safety. It turns approval from "read JSON" into "review
the proposed workspace change."

**Risk:** Medium. Must keep raw details accessible for transparency.

**Disposition:** Pair with any future safe-organizer or file-agent work.

#### Slice AP - Multi-Agent Handoff Exists But Presets Are Too Narrow

**Classification:** Existing capability with brittle product preset.

**Evidence:**

- Agent sessions support `agentProfiles`, `initialAgentId`, active-agent tool
  subsets, handoff tools, persisted handoff history, and resume from the active
  agent.
- The UI starts with built-in Primary and Organizer presets.
- Primary is instructed to hand off for ingest, add, move, rename, or organize
  files.
- Organizer is currently instructed that its first step must be `ingest_files`
  with the path from the handoff reason, then confirm.

**Interpretation:** The infrastructure supports role-based agents, but the
shipped preset currently fits "find and ingest this" much better than "move,
rename, or organize these." That makes the multi-agent feature real but
under-shaped.

**Unused feature:** A general Organizer profile that can choose between
`file_operations`, `ingest_files`, and browse/search based on the requested
change, and that emits a clear operation plan for approval.

**Payoff:** High if JustSearch wants agentic file management to feel serious.

**Risk:** High. This touches write operations, so approval UX and undo history
must be strong first.

**Disposition:** Do not expand write-agent scope before AO is addressed.

#### Slice AQ - Tool Catalog Exists But Is Only Counted

**Classification:** Small but useful capability explanation gap.

**Evidence:**

- `/api/agent/tools` returns every tool name, description, safety level,
  undo support, and parameter schema.
- `useAgentStore` fetches this list.
- `AgentView` mostly renders the count of tools and availability, not a
  readable catalog of what the agent can do.

**Interpretation:** Users are asked to "ask the agent to search, browse,
ingest files, or manage your knowledge base," but the exact capabilities,
safety levels, and undo support are hidden. This is especially relevant when
write tools require approval.

**Unused feature:** A compact "Agent capabilities" drawer or empty-state panel
derived from live tool definitions, grouped by read/write/destructive and
annotated with undo support.

**Payoff:** Medium. It reduces mystery without adding another workflow.

**Risk:** Low. Keep schemas collapsed; show plain descriptions first.

**Disposition:** Good support work for C36 capability explanation and AO.

#### Slice AR - Browse Tool Has Agent-Friendly Relative Paths

**Classification:** Existing agent feature that can inform UI copy.

**Evidence:**

- `browse_folders` lists roots, subfolders, and files.
- It resolves relative paths against indexed root names, validates paths
  against indexed roots, and formats output using root-relative paths.
- The Primary/Organizer prompts explicitly rely on relative paths in handoff
  reasons.

**Interpretation:** The agent tool layer has a nice local-workspace path model:
root-relative paths are easier for users and safer for tools than raw absolute
Windows paths. The UI can lean into that same model in approvals, transcripts,
and command scopes.

**Unused feature:** Consistent root-relative path display across Agent,
History, Action Panel, Browse, and source verification.

**Disposition:** Treat as polish, but important polish. It makes all the
agent/workspace features feel less mechanical.

### Eighth-Pass Candidate Additions

#### C43 - Workspace Operation Timeline

**Persona:** User who let the agent change files and wants to review what
happened later.

**State:** Operation logs, history endpoint, undo, session events, and
handoff events exist.

**Payoff:** High. It gives agent actions durable accountability.

**Risk:** Medium. Needs grouping and retention-aware copy.

**Disposition:** Add to C20/C33 continuity work.

#### C44 - Agent History Contract Alignment

**Persona:** User reviewing failed or partial operation batches.

**State:** Backend emits `failureCount`; frontend expects `failedCount`.

**Payoff:** Low by itself, high as trust hygiene.

**Risk:** Low.

**Disposition:** Fix before relying on Agent History as an audit surface.

#### C45 - Typed Approval/Diff Cards

**Persona:** User approving agent write operations.

**State:** Structured file operation arguments exist; UI renders raw JSON.

**Payoff:** High. This is the safety UX that makes write agents viable.

**Risk:** Medium. Must preserve raw arguments and avoid hiding destructive
details.

**Disposition:** Prerequisite for broadening Organizer behavior.

#### C46 - General Organizer Profile

**Persona:** User asking the agent to organize, move, rename, copy, or ingest
files.

**State:** Multi-agent handoff and tool subsets exist; current Organizer
preset is ingest-first.

**Payoff:** High.

**Risk:** High without C45 and reliable undo/timeline.

**Disposition:** Later, after approval/timeline hardening.

#### C47 - Live Agent Capability Catalog

**Persona:** User learning what the agent can do and how risky each action is.

**State:** Tool metadata is fetched but only summarized as a count.

**Payoff:** Medium.

**Risk:** Low.

**Disposition:** Bundle with capability explanation.

#### C48 - Root-Relative Workspace Paths

**Persona:** User reading approvals, transcripts, citations, or history.

**State:** Agent tools already resolve and emit relative paths.

**Payoff:** Medium. Reduces visual noise and improves safety comprehension.

**Risk:** Low to medium. Must preserve absolute path access where needed.

**Disposition:** Cross-cutting polish for C43/C45/C39.

### Eighth-Pass Ranking Adjustment

1. **C30/C31 scoped ask entry points** remain the top user-facing bridge.
2. **C34/C38/C39 preview and citation verification** remain the strongest
   trust-building read workflow.
3. **C20/C33/C43 session and operation continuity** should now be framed as a
   workspace timeline, not just a transcript export.
4. **C45 typed approval/diff cards** become the key prerequisite for any
   serious agentic file-management work.
5. **C41 scoped full-document context** remains important for selected-file
   ask flows.
6. **C46 general Organizer profile** is promising, but should wait until
   approval diffs, undo history, and timeline are strong.
7. **C47/C36 capability explanation** should include live agent tool metadata.

### Eighth-Pass Synthesis

The agent system already has the core safety loop:

1. propose a tool call,
2. classify safety,
3. wait for approval,
4. execute,
5. log the operation,
6. offer undo.

What is missing is not more raw power. It is legibility. Users need to see
proposed changes as workspace diffs, completed changes as timeline events, and
undo as a durable recovery affordance. Once that exists, the multi-agent
Organizer can safely grow beyond the current ingest-shaped preset.

### Ninth Investigation Pass - Critical Gap Audit of Uncovered Areas

This pass explicitly asked: what have the earlier passes *not* considered?
The answer is that the investigation over-weighted AI, MCP, preview, status,
and agent execution. It under-weighted the everyday workspace surfaces:
Library, Browse, settings/disclosure, autocomplete, help/onboarding, and
keyboard preferences. Those areas contain several user-facing capabilities
that are already implemented or half-wired, and they matter because they are
how users choose scope before asking AI or authorizing an agent.

Areas still intentionally excluded:

- Release/build/installer/resilience work.
- Future-features documents.
- Broad AI pack/policy expansion as a product direction; only current
  user-facing settings/policy surfaces were considered.

#### Slice AS - Browse Is a Scoped Workspace Navigator, Not Just a Tree

**Classification:** Existing feature with underused workflow value.

**Evidence:**

- `BrowseView` supports persisted expansion state per root configuration,
  folder/file fetch caching, abortable loads, retry for failed folders, empty
  folder tracking, truncation tracking, sticky ancestor breadcrumbs,
  duplicate filename disambiguation, inline filtering, keyboard navigation,
  multi-select, folder context menus, and file context menus.
- Folder context menus can "Search within" by setting `pathPrefix` and moving
  to Search.
- File rows can open, reveal, copy path, inspect, and open context actions.

**Interpretation:** Browse is already a scope-building surface. It lets a user
choose *where* work should happen. Earlier passes treated scope mostly as
selected files or search results, but folder scope is equally important.

**Unused feature:** Folder-scoped AI/agent actions:

- Ask about this folder.
- Summarize this folder.
- Let agent organize this folder.
- Verify sources within this folder.

These should start from Browse context, carry `pathPrefix` or selected file
IDs, and land in the same Inspector/Agent trust loops already identified.

**Payoff:** High. It connects a very natural user action ("this folder") to AI
and agent workflows.

**Risk:** Medium. Folder scope can explode context size; use capability
explanation and context-budget warnings.

**Disposition:** Add to C30/C31 scoped ask and C45 approval work.

#### Slice AT - Browse Summarize Is Explicitly Stubbed

**Classification:** Concrete incomplete bridge.

**Evidence:**

- `BrowseView` wires `useAppFileActions` with `requestSummary: () => {}` and
  a comment: "Phase 4: wire AI summarization."
- `ContextMenuLayer` in Browse passes `canSummarize={false}`.
- Search/App context menus do pass summarization handlers.

**Interpretation:** The action model is reusable, but Browse deliberately
does not yet connect its selected file(s) to summarization. That means the
same file action is available from search results but disabled from Browse,
even though Browse can inspect files and manage selection.

**Unused feature:** Summarize/ask selected Browse files and folder-selected
files.

**Disposition:** High-confidence bridge task. It is smaller than folder-level
AI because single-file Browse summarization should be straightforward.

#### Slice AU - Library Excludes Are a Safe Cleanup Workflow

**Classification:** Existing advanced feature with agent/workflow potential.

**Evidence:**

- Library supports exclude patterns stored in settings.
- It has dry-run preview via `/api/indexing/excludes/apply?dryRun=true`.
- It reports per-pattern match counts, capped results, roots processed, and
  deletion counts after apply.
- Excludes are advanced-mode gated and explicit; they do not automatically
  delete indexed records.

**Interpretation:** This is already a careful review-before-apply workflow.
It resembles agent approval: preview impact, then apply. Earlier agent
approval findings should learn from this shape.

**Unused feature:** "Explain/preview cleanup" as a reusable pattern:

- Agent proposes exclude patterns.
- User sees dry-run counts before applying.
- Operation history records cleanup outcome.

**Payoff:** Medium to high for managing noisy indexes.

**Risk:** Medium. Exclude cleanup changes search visibility, so it needs clear
scope and undo/restore story.

**Disposition:** Later than ask/preview, but important for library hygiene and
agent-assisted organization.

#### Slice AV - Suggested Roots Are Onboarding Intent

**Classification:** Existing small feature that could feed agent/onboarding.

**Evidence:**

- `/api/indexing/suggested-roots` returns platform-aware folders that exist
  and are not already watched.
- Library empty state renders those suggestions as "Quick add."

**Interpretation:** Suggested roots are not only onboarding convenience. They
are a machine-readable model of likely user intent: where the user's knowledge
probably lives.

**Unused feature:** Use suggested roots in first-run guidance and agent
capability explanation: "I can search once you add Documents/Desktop/etc."

**Disposition:** Medium. Useful support for first-run and empty-index states,
but not a core AI feature.

#### Slice AW - Default Enter Action Setting Is Not Honored Globally

**Classification:** Concrete settings-to-behavior mismatch.

**Evidence:**

- Settings exposes `ui.defaultAction` with `open`, `reveal`, and `preview`.
- The settings contract and fixtures include `defaultAction`.
- `useAppKeyboard` says "Enter opens the file" and always calls
  `handleOpen(item)` for Enter in Search.
- `useAppKeyboard` does not receive or consult the configured default action.

**Interpretation:** The setting exists as a product promise, but at least the
global search keyboard path ignores it. That is a sharp user-facing mismatch.

**Unused feature:** Honor default action across result rows, keyboard Enter,
and Browse rows:

- `open`: native open.
- `reveal`: reveal in folder when available.
- `preview`: open Inspector Preview without launching the external file.

**Payoff:** High for keyboard-first users.

**Risk:** Low to medium. Need consistent behavior across Search and Browse.

**Disposition:** Fix as a standalone UX correctness task.

#### Slice AX - Disclosure Level 1 Is the Missing Power Tier

**Classification:** Product-shaping opportunity; already structurally enabled.

**Evidence:**

- `useDisclosure` defines levels 0, 1, 2.
- Level 1 is explicitly reserved.
- `FEATURE_REGISTRY` gates Brain compatibility, advanced controls, Inspector
  context details, Library reindex/excludes, Lucene syntax, path prefix, and
  MIME filters at level 2.

**Interpretation:** Earlier passes noted level 1, but the critical gap audit
makes the implication clearer. Many high-value features are not truly
"diagnostics"; they are power-user workflow controls. Path scope, MIME
filters, context details, and maybe source verification are closer to a Power
tier than to full Advanced.

**Unused feature:** A level-1 "Power" mode that exposes workflow controls
without exposing full runtime diagnostics.

**Disposition:** Strategic. Do not implement casually, but use it as a
classification lens when deciding whether new trust/scope controls belong in
Simple, Power, or Advanced.

#### Slice AY - Autocomplete Exists But Is Search-Only

**Classification:** Existing feature with narrow boundary.

**Evidence:**

- `useSuggest` debounces backend suggestions, cancels in-flight requests, and
  requires two characters.
- `GlobalCommand` renders suggestion dropdowns and keyboard navigation in
  search mode.
- Suggestions are suppressed for `/` and `??` modes.
- Production MCP schemas also define suggest, but production MCP does not
  register the tool, as noted in Slice AE.

**Interpretation:** Autocomplete is well implemented for direct search. It
does not currently help with command discovery, agent task framing, or path
scope selection.

**Unused feature:** Contextual suggestions by mode:

- Search mode: current behavior.
- Command mode: commands/actions.
- Ask mode: selected scope prompts or recent questions.
- Agent mode/MCP: optional index vocabulary discovery.

**Disposition:** Medium. Useful after command routing is fixed; not before.

#### Slice AZ - Help Teaches Unwired or Partial Behavior

**Classification:** Trust gap.

**Evidence:**

- Help says `??` asks AI about selected files or general questions.
- Earlier passes found the search submit path does not fully route `??` or
  `ask` into Inspector AI.
- Help also says Ctrl+Enter opens selected file, while global keyboard Enter
  currently opens and default action settings exist separately.

**Interpretation:** Help is acting as a specification for features that are
not consistently wired. This is dangerous because it trains users into dead
ends.

**Unused feature:** Make Help actionable and live:

- Only show shortcuts that match current wiring.
- Link help items to actions/views.
- Surface disabled reasons from the same capability/gate system used by the
  UI.

**Disposition:** Bundle with command/action bridge cleanup.

### Ninth-Pass Candidate Additions

#### C49 - Folder-Scoped Ask/Summarize/Agent Actions

**Persona:** User browsing folders who wants to work on a folder, not an
individual search result.

**State:** Browse has folder context menus, pathPrefix search, selection, and
Inspector/file actions.

**Payoff:** High. It makes scope natural.

**Risk:** Medium. Needs context budget and clear scope display.

**Disposition:** Add to C30/C31 as a scope source.

#### C50 - Wire Browse Summarize/Ask

**Persona:** User selecting files in Browse.

**State:** `requestSummary` is stubbed and `canSummarize` is false in Browse
context menu.

**Payoff:** High for consistency.

**Risk:** Low to medium.

**Disposition:** Concrete bridge task.

#### C51 - Exclude Preview as Review-Before-Apply Pattern

**Persona:** User cleaning noisy indexed content; agent proposing cleanup.

**State:** Dry-run and per-pattern counts exist.

**Payoff:** Medium to high.

**Risk:** Medium.

**Disposition:** Later library hygiene/agent organization track.

#### C52 - Honor Default Action Everywhere

**Persona:** Keyboard-first user; user who prefers preview over opening files.

**State:** Setting exists; keyboard path ignores it.

**Payoff:** High.

**Risk:** Low to medium.

**Disposition:** Standalone correctness fix.

#### C53 - Power Disclosure Tier

**Persona:** User who needs scope/trust controls but not full diagnostics.

**State:** Level 1 exists but is unused.

**Payoff:** Medium to high as a product architecture move.

**Risk:** Medium. Requires careful feature classification.

**Disposition:** Use as a design lens before adding more advanced-only trust
features.

#### C54 - Mode-Aware Suggestions

**Persona:** User typing in search/command/ask modes; external agent exploring
vocabulary.

**State:** Search suggestions exist; command/ask suggestions do not.

**Payoff:** Medium.

**Risk:** Low to medium.

**Disposition:** After command routing is real.

#### C55 - Live Help/Shortcut Consistency

**Persona:** New user relying on Help.

**State:** Help is static and partly ahead of implementation.

**Payoff:** Medium but important for trust.

**Risk:** Low.

**Disposition:** Bundle with C31/C52.

### Ninth-Pass Ranking Adjustment

1. **C30/C31 scoped ask and command handling** stays first, but "scope" must
   now explicitly include Browse folders and Browse selections.
2. **C52 default action correctness** becomes a high-priority non-AI fix
   because it is a direct settings promise mismatch.
3. **C50 Browse ask/summarize wiring** is the most concrete uncovered bridge.
4. **C34/C38/C39 preview/citation verification** remains the strongest trust
   loop.
5. **C20/C33/C43 workspace timeline** remains the continuity track.
6. **C45 approval diff cards** remains the write-agent prerequisite.
7. **C53 Power tier** should guide whether scope and trust controls stay
   hidden in Advanced or become broadly available.

### Ninth-Pass Synthesis

The investigation had a bias toward AI internals and agent execution. The
critical correction is that JustSearch's strongest product shape depends on
scope surfaces:

1. Library decides what exists in the workspace.
2. Browse decides where the user is working.
3. Search decides what evidence is relevant.
4. Inspector verifies and explains.
5. Agent acts, logs, and can undo.

The next implementation work should respect that chain. AI entry points should
not only start from search results; they should start from Browse folders,
Browse selections, Library cleanup previews, and user-configured default
actions. That is how the product becomes a coherent local workspace rather
than a set of impressive but separate surfaces.
