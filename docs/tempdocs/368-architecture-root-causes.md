---
title: "368 — Architecture Root Causes from Frontend Alignment"
status: done
created: 2026-03-29
scope: cross-cutting
depends-on: [364]
---

# 368 — Architecture Root Causes from Frontend Alignment

## Context

Tempdoc 364 (frontend-backend alignment) uncovered a pattern: multiple
independent symptoms traced back to a small number of architectural root
causes. This tempdoc captures those root causes and tracks structural
fixes, separated from the frontend-specific work in 364.

---

## Root Cause 1: No shared contract between frontend and backend

Backend Java records (`StatusResponse`, `KnowledgeSearchRequest`,
`UiSettingsV2`) and frontend TypeScript types (`SystemStatus`,
`SearchFilters`, `UISettings`) are maintained independently. No code
generation, shared schema, or contract test binds them.

**Symptoms in 364:**
- All Tier 1 drift (T1-A through T1-G) — 3 months of backend changes
  invisible to frontend
- `searchConfig` expected nested, actually `@JsonUnwrapped` flat
- `phaseTotalUs` declared as `number`, actually `Map<String, Long>`
- `vimMode` missing from frontend settings (round-trip loss)

**Fix — contract tests:**
- [x] Serialize a `StatusResponse` in Java, validate against
  `SystemStatusSchema` in a shared test
  (`StatusRecordSchemaTest.CrossLanguageContract` → `contract.test.ts`).
  Java side uses a fully deterministic sample (fixed timestamps) and
  does exact string comparison against the committed fixture — any
  field addition, removal, or type change fails the Java test with
  instructions to regenerate. TypeScript side validates the fixture
  against the Zod schema.
- [x] Round-trip `SettingsV2` through frontend save → backend load →
  frontend read, verify no field loss
  (`SettingsV2ContractTest` → `contract.test.ts`)
- [x] Serialize `KnowledgeSearchResponse` with populated hits, provenance,
  metadata, facets, pipeline execution, and entity variants. TypeScript
  test maps through `mapKnowledgeSearchResponse()` (production path) and
  validates against `SearchResponseSchema`. Covers the search surfaces
  that drifted in 364 T1-C/T1-D.

The contract tests use `ORDER_MAP_ENTRIES_BY_KEYS` for deterministic JSON
output across JVM runs. The fixture represents the sorted wire format,
not production key order (which is unspecified in JSON).

The frontend is the only consumer, shipped in the same build as the
backend. Contract tests catch every drift scenario that matters and run
in CI. Schema-first codegen (OpenAPI generation, neutral schema with
dual-language type generation) would eliminate drift by construction but
adds toolchain complexity — a new build dependency, generated code to
manage, and a tool every contributor must understand — that doesn't pay
for itself with a single consumer.

---

## Root Cause 2: Jackson serialization invisible to API consumers

`@JsonUnwrapped` across multiple levels (`StatusResponse` →
`WorkerOperationalView` → `SearchConfigView` / `EnrichmentProgressView`)
changes the wire format from nested to flat. This is invisible to anyone
who doesn't read the Java annotations.

Compounded by 330 §4 adding **named** grouped sub-objects (`embedding`,
`schema`, etc.) alongside existing `@JsonUnwrapped` flat fields — so some
sub-records are nested keys and others are flattened, with no predictable
pattern.

**Symptoms in 364:**
- `searchConfig` always `undefined` in frontend (expected nested, was flat)
- General confusion about which fields are grouped vs flat in status

**Fixes:**
- [x] Audit all `@JsonUnwrapped` in `StatusResponse` hierarchy — document
  which are intentional vs accidental (see audit below)
- ~~Consider removing `@JsonUnwrapped` from `SearchConfigView`~~ — deferred
  indefinitely. 364 already adapted the frontend to read flat fields.
  Changing to a named key would require another frontend migration for
  no functional benefit. The contract test catches future surprises.
- [x] ~~Add a `GET /api/status/schema` endpoint~~ — superseded by the
  cross-language contract test fixture (`status-response-live.json`)
  which serves the same purpose of documenting the actual wire shape

**Audit results (368):**

| Level | Field | Type | Intentional | Notes |
|-------|-------|------|-------------|-------|
| L1 | `StatusResponse.worker` | `WorkerOperationalView` | Yes | Flattens all worker fields to root. Foundational design — all downstream unwraps exist within this. |
| L2 | `.core` | `CoreIndexView` | Yes | `indexHealthy`, `indexedDocuments`, `pendingJobs`, `indexState`, `indexSizeBytes` — stable field names. |
| L2 | `.failure` | `FailureTrackingView` | Yes | `failedJobs`, `lastFailedPath`, etc. — failure visibility at root. |
| L2 | `.migration` | `MigrationGenerationView` | Yes | `activeGenerationId`, `migrationState`, etc. — also has nested `migrationEnumerator` object. |
| L2 | `.compatibility` | `CompatibilityStatusView` | Yes | `embeddingCompatState`, `indexSchemaCompatState`, etc. — duplicated in grouped sub-objects. |
| L2 | `.queueDb` | `QueueDbStatusView` | Yes | `queueDbHealthy`, `queueDbLastBackupAtMs`, etc. — duplicated in `queueHealth` group. |
| L2 | `.enrichment` | `EnrichmentProgressView` | Yes | `embeddingDocCount`, `spladeDocCount`, etc. — has nested `batchTiming`, `encoderProfiles`. |
| L3 | `.enrichment.chunk` | `ChunkCoverageView` | Yes | `chunkDocCount`, `chunkVectorCoveragePercent`, etc. — duplicated in `chunkCoverage` group. |
| L2 | `.gpu` | `GpuDiagnosticsView` | Yes | Produces nested objects (`rerankerOrtCuda`, `spladeOrtCuda`, `embedOrtCuda`) + flat scalars. |
| L2 | `.vectorFormat` | `VectorFormatView` | Yes | `vectorFormatConfig`, `vectorFormatStored`, etc. |
| L2 | `.telemetry` | `TelemetryMetricsView` | Yes | `throughputDocsPerSec`, `contentLengthAvgChars`, etc. |
| L2 | `.searchConfig` | `SearchConfigView` | **No** | `chunkAwareEnabled`, `ccWeightSparse`, etc. — the only accidental unwrap. Should be a named `searchConfig` key per 330 §4 convention, but 364 already adapted the frontend to flat fields. Deferring change to avoid churn. |

The `@JsonUnwrapped` annotations exist because they eliminate hundreds of
lines of manual field-by-field mapping. Replacing them with an explicit
DTO layer (separate wire model from domain model) would mean writing all
that boilerplate without codegen to generate it. The audit documents the
current state; the contract test from RC1 catches future surprises.

---

## Root Cause 3: Model identity fragmented across five systems

No single source of truth for "is this model installed and working." Five
independent mechanisms each answer differently:

| System | Checks | Runs in | Used by |
|---|---|---|---|
| Registry manifest (`installed`) | SHA-256 vs registry | Head | Install AI UI |
| `resolveOnnxFeatures()` | Head env + Worker cache | Head | Search Quality Features UI |
| `WorkerModelDiscovery` | Auto-discovery only (no env) | Worker → Head | ONNX feature cache |
| `RerankerConfig.fromEnv()` | Env vars + auto-discovery | Worker | Actual ORT session |
| `rerankerOrtCuda` in status | ORT session loaded? | Worker → status | (nothing, until 364 T2-F) |

**Symptoms in 364:**
- "Search reranking: Inactive" when cross-encoder executes (system 2 vs 5)
- All assets show "not installed" despite working models (system 1 vs 5)
- `WorkerModelDiscovery` intentionally strips explicit paths (system 3 vs 4)

**Fixes:**
- [x] Designate ORT session state as the canonical source of truth for
  "is this feature active" — `session_active` proto field on
  `OnnxDiscoveredModel`, wired through health check → feature cache →
  `OnnxFeatureStatus.modelActive`. Both reranker (via `OrtCudaStatus`)
  and citation-scorer (via `CitationScorer.isAvailable()`) report
  runtime session state.
- [x] Fix `WorkerModelDiscovery.discoverAll()` to pass explicit path from
  resolved config (not `null`) — eliminates system 3 vs 4 disagreement
- [x] Add `modelActive: boolean` field to ONNX feature status, derived
  from actual ORT session state rather than file discovery
- [x] Frontend: `BrainSimplePanel` considers `onnxFeatures[].modelActive`
  as a satisfaction signal for `isInstalled` — models placed outside the
  installer no longer show "not installed" when ORT sessions are running.
  `modelActive` added to `OnnxFeatureStatusSchema` (Zod validation).

A unified state machine per capability (owned by the Worker, queried by
all consumers) would be the clean long-term design — but the Head needs
model info before the Worker starts (install UI during cold start), and
models load on background threads with non-linear lifecycle transitions.
The incremental fixes collapse systems 3 and 4 into agreement and make
system 5 (actual ORT session state) the tiebreaker, which addresses the
symptoms without pretending the lifecycle is simpler than it is.

---

## Root Cause 4: No concept of model capability vs model version

The registry is a flat asset list. `onnx-reranker` (ms-marco, 23 MB) and
`onnx-reranker-gte` (modernbert, 150 MB) are independent entries targeting
different filesystem paths. The system can't express "these are two
versions of the reranker capability; prefer the newest."

**Symptoms in 364:**
- Install would overwrite upgraded gte-modernbert reranker with old ms-marco
- Upgraded model downloads to `onnx/reranker-gte/` (dead path for discovery)
- No skip-if-working: SHA mismatch → "not installed" even when better model
  is active

**Fix:**
- [x] Update registry to remove old `onnx-reranker` entry entirely and
  have `onnx-reranker-gte` target `onnx/reranker/` directly

There are two reranker variants. Removing the stale entry and pointing
the current one at the correct path eliminates the overwrite problem in
minutes. A capability/supersedes abstraction (`capability` field, version
ordering, skip-if-satisfied logic) would be warranted if the registry
grows to many competing variants of the same capability — build it then,
not now.

---

## Root Cause 5: Status response grew organically without API design

`/api/status` started as a health check and grew to 100+ fields through
accretion across 30+ tempdocs. No versioning, no schema evolution strategy.

Field conventions vary:
- Flat scalars (`embeddingReady`, `chunkDocCount`)
- Grouped sub-objects (`embedding`, `schema`, `chunkCoverage`)
- Duplicated in both forms simultaneously (pre-364 T3-A)
- `@JsonUnwrapped` from Java records (searchConfig fields)
- Named nested objects (`gpu`, `meta`, `llm`)

**Symptoms in 364:**
- 23 flat-field duplicates of grouped sub-objects (removed in T3-A)
- searchConfig fields flat by accident (RC2)
- New sub-objects (`gpu`, `meta`, `encoderProfiles`) not in frontend types
- `observedAtMs` emitted on all grouped sub-objects but undeclared

**Standing defense:** The RC1 contract test catches field-level drift
at CI time. The RC2 audit documents the current structure. No active
fix is needed — the system works correctly after 364.

**If the structure becomes a bottleneck again** (e.g., a future tempdoc
needs to add a new status section and there's no clear place for it),
design the v2 schema at that point — scoped to the actual changes
being made, not a hypothetical clean rewrite.

---

## Root Cause 6: Invisible runtime modes change API semantics

API endpoints behave differently depending on runtime modes (`IN_MEMORY`
vs `READ_WRITE`, eval vs production) but the response shape is identical.
Nothing in the API response signals which mode is active.

**Symptoms in 364:**
- `POST /api/settings/v2` returns merged data but save is a no-op in
  `IN_MEMORY` mode — the 200 response is a lie (data wasn't persisted)
- `GET /api/settings/v2` returns defaults in `IN_MEMORY` mode with no
  indication that real settings exist on disk elsewhere
- Developer spent significant time investigating a "settings persistence
  bug" that was actually correct eval-mode behavior — because nothing in
  the system communicated the mode
- Frontend debounce layer (500ms `settingsPendingFlush`) masks the
  symptom — change appears to stick for a few seconds, then silently
  reverts on next poll

**Same pattern as RC3:** the system reports on a concept (settings state,
model state) but the answer depends on invisible runtime context that no
consumer can query.

**Fixes:**
- [x] Include `settingsMode: "read_write" | "in_memory"` in the
  `GET /api/settings/v2` response — added as `settingsMode` field
  on `SettingsV2` record, populated from `settingsStore.mode()`
- [x] Frontend: show "Read-only" pill in SettingsView header when
  `settingsMode === "in_memory"` — hides Reset to Defaults button
- [x] POST handler: return 409 with `SETTINGS_READ_ONLY` error code
  when `IN_MEMORY` mode would discard the save
- [x] Contract test updated: Java fixture includes `settingsMode`,
  TypeScript validates it as `read_write | in_memory`
- [x] Frontend: `updateSettings()` skips network flush when
  `settingsMode === "in_memory"` — changes apply locally for the
  session but don't trigger pointless 409 requests
- [x] Frontend: `flushSettingsNow()` no longer retries on 409 —
  was creating infinite retry loop for a permanent rejection

**Remaining code smell — settings type duplication:**

`UISettings`, `LLMSettings`, and `AppSettings` are defined in two
places: `api/domains/settings.ts` (used by components/hooks via barrel)
and `stores/systemTypes.ts` (used by Zustand store). The two copies are
structurally identical but not linked by TypeScript — `useSettings.ts`
bridges them with `storeSettings as AppSettings` cast (line 111). If a
field is added to one copy and not the other, the cast silences the
mismatch. Fix: delete the duplicates from `systemTypes.ts` and import
from `api/domains/settings.ts`.

The deeper pattern: every API that changes behavior based on runtime mode
should **declare** that mode in its response. Silent behavioral switches
are indistinguishable from bugs to every consumer.

---

## Why not bigger solutions?

The first five root causes share a meta-problem: the API surface is an emergent
property of Java serialization, not a designed artifact. Theoretically,
schema-first codegen (neutral schema → generated Java DTOs + TypeScript
types) would eliminate drift by construction, make annotation accidents
impossible, and force deliberate API design. Each root cause reinforces
the others, and codegen is the deepest leverage point.

In practice, these solutions are overscaled for a local-first desktop app
with a single bundled frontend:

- **Contract tests over codegen** (RC1): one consumer, same build. Two
  test files catch drift at CI time without adding a code generation
  toolchain.
- **Audit over DTO layer rewrite** (RC2): `@JsonUnwrapped` eliminates
  hundreds of lines of manual mapping. Removing it without codegen means
  writing all that boilerplate. The audit + contract test catches future
  surprises.
- **Targeted fixes over state machine** (RC3): the Head needs model info
  before the Worker starts (cold-start install UI). A Worker-owned state
  machine can't serve this case. Collapsing systems 3+4 and making
  system 5 the tiebreaker addresses the actual pain.
- **Registry cleanup over capability abstraction** (RC4): two reranker
  variants. Remove the stale entry. Build the abstraction when the
  variant count justifies it.
- **Versioned schema over endpoint decomposition** (RC5): single poll,
  atomic consistency. A v2 schema with explicit nesting fixes the
  structure without fragmenting the request model.

---

## Theoretical Long-Term Architecture

The six root causes share a meta-pattern: **the system's internal state
is opaque to its consumers.** Types drift because the wire format isn't
declared. Jackson annotations are invisible. Model status depends on
which subsystem you ask. Settings behavior depends on an undeclared
runtime mode. The theoretically correct architecture has five properties:

### T1. The API is a designed artifact, not an emergent property

Every endpoint has a machine-readable schema as the single source of
truth. Both backend serialization and frontend types are derived from
it — neither is primary, both are generated. The schema is versioned
with explicit evolution rules (additive-only within a version, breaking
changes require a new version with a migration period).

No accidental flattening via `@JsonUnwrapped`, no undocumented fields
through Java record inheritance. Every field is explicitly declared in
the schema. The schema forces deliberate design because you can't add
a field without declaring it.

*Eliminates: RC1, RC2, RC5.*

### T2. Every capability has exactly one authority

For any question ("is the reranker active?", "are settings persistent?",
"is the index healthy?"), exactly one subsystem is the authority. Every
other subsystem queries it rather than maintaining its own answer.

- **Models**: The Worker's ORT session manager is the authority for "is
  this model loaded and running." Registry, file discovery, env-var
  config — all feed into it. It is the sole publisher of runtime state.
  The Head doesn't independently discover models; it reads the Worker's
  published state.
- **Settings**: The persistence layer is the authority. Its state includes
  values AND persistence mode. Consumers know what the values are AND
  whether those values are persistent or ephemeral.
- **Index health**: The Worker owns index state. The Head reads it via
  a single channel, never derives it independently.

*Eliminates: RC3, RC6.*

### T3. The registry expresses capabilities, not assets

Instead of a flat file list, the registry declares capabilities
("reranker", "embedding", "chat") with satisfaction criteria. Each
capability can be satisfied by multiple model versions. The installer
checks capability satisfaction, not SHA match — if the reranker
capability is already satisfied by a better model, it skips the download.

The registry schema:
```
capability: "reranker"
satisfiedBy:
  - id: gte-modernbert  (preferred)
    targetPath: onnx/reranker/
  - id: ms-marco-l6     (fallback)
    targetPath: onnx/reranker/
satisfactionCheck: ORT session loaded for "reranker"
```

*Eliminates: RC4.*

### T4. API responses declare their own context

Every response includes metadata about the conditions under which it
was produced: runtime mode, data freshness, authority source.

- Settings: `"persistenceMode": "in_memory"` — frontend disables save
- Status: `"workerRpc": { "atMs": ..., "stale": true }` — frontend
  shows staleness warning
- Model status: `"source": "ort_session"` vs `"source": "file_discovery"`
  — consumers know the confidence level

No response can be misinterpreted because the context is always explicit.
A 200 from a settings POST in `in_memory` mode either returns a different
status code (409), or includes `"persisted": false`.

*Eliminates: RC6.*

### T5. The status response is a composed view of authority states

Not a flat dump from `@JsonUnwrapped` records, but an explicit
composition that queries each authority and assembles a response with
clear provenance. Each section is a named sub-object with its own
`observedAt` and `source`.

Single-request, atomic consistency (no endpoint decomposition), but
designed structure with generated types.

*Eliminates: RC5, and prevents recurrence of RC1/RC2.*

### How these relate

T1 (schema-first) and T5 (composed status) eliminate the structural
drift problems (RC1, RC2, RC5). T2 (single authority) eliminates the
"five systems disagree" problems (RC3, RC6). T3 (capability registry)
eliminates the model versioning problem (RC4). T4 (self-describing
responses) is the mechanism by which T2's authority state reaches
consumers.

Together they form a coherent architecture where: the API shape is
declared (T1), each piece of data has one owner (T2), models are managed
by capability (T3), responses carry their own context (T4), and the
status endpoint composes rather than dumps (T5).

---

## Relationship to 364

364 applied targeted fixes (type alignment, flat-field removal, GPU card,
reranker status override, citation URL fix). Those fixes are correct but
treat symptoms. This tempdoc tracked the structural causes and applied
right-sized fixes to prevent recurrence.

## Visual verification (2026-03-29)

Verified against live dev stack (`localhost:5173` + backend on `33221`,
611 indexed files, 4573 chunks with vectors):

- **Registry cleanup (RC4)**: `/api/ai/install/manifest` returns 7 assets
  (was 9). Old `onnx-reranker` entries absent. `onnx-reranker-gte` targets
  `onnx/reranker/model.onnx`. Citation tokenizer URLs show `L-2-v2`.
- **modelActive in API (RC3)**: `/api/ai/runtime/status` returns
  `modelActive: false` for both reranker and citation-scorer (correct —
  no ORT sessions loaded in dev instance).
- **Search Quality Features**: "1/2 active" — search reranking Active at
  `D:\code\JustSearch\models\onnx\reranker`, citation scoring Inactive
  (model not on disk). Consistent with API data.
- **BrainSimplePanel**: Shows "Not Installed" (correct — installer hasn't
  run, `modelActive=false` for both features, `hasActiveModels` override
  correctly does not fire).
- **Status bar**: Ready, 611 indexed, AI idle — no anomalies.
