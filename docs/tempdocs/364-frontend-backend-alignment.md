---
title: "364 — Frontend-Backend Alignment"
status: done
created: 2026-03-28
scope: modules/ui-web, modules/app-api
depends-on: [330, 341, 345, 362, 291, 306, 335, 337, 347, 357]
---

# 364 — Frontend-Backend Alignment

## Context

Frontend development paused ~mid-December 2025. Since then, ~3 months of
backend work shipped across 30+ tempdocs. The frontend still runs but consumes
a stale subset of what the backend now provides. This tempdoc catalogs every
gap and tracks the work to close them.

The gaps fall into three tiers:

- **Tier 1 — Type/data drift**: frontend types don't match backend responses;
  data is silently dropped or flat-field fallbacks mask the real structure.
- **Tier 2 — Missing UI surfaces**: backend provides data the frontend has no
  component to render (GPU dashboard, metadata filters, provenance, RAG quality).
- **Tier 3 — Cleanup/hardening**: remove dead flat fields, fix minor
  inconsistencies, wire unused pipeline config options.

---

## Tier 1 — Type & Data Drift

### T1-A: Status response — new sub-objects not in frontend types

**Source**: tempdocs 335, 337, 333, 357, 343

`SystemStatus` in `systemTypes.ts` and `SystemStatusSchema` in `schemas.ts`
were missing sub-objects that the backend emits in `/api/status`:

| Sub-object | Backend record | What it contains |
|---|---|---|
| `gpu` | `GpuStatusView` | available, utilization%, VRAM (total/used/free), driverVersion, deviceCount |
| `meta` | `StatusMeta` | workerRpcAtMs, workerRpcStale |
| `searchConfig` | `SearchConfigView` | **`@JsonUnwrapped` — flat at top level**, not nested |
| `encoderProfiles` | `Map<String, EncoderProfileView>` | Per-encoder ORT timing: calls, phaseTotalUs (Map), ortMinUs/MaxUs/P50Us/P95Us/P99Us |

**Work:**
- [x] Add `GpuStatusGroup`, `StatusMeta`, `EncoderProfileEntry` types to `systemTypes.ts`
- [x] Add corresponding Zod schemas to `schemas.ts`
- [x] Add `gpu`, `meta`, `encoderProfiles` fields to `SystemStatus`
- [x] Search config fields added as flat top-level keys (matching `@JsonUnwrapped` backend)
- [x] Wire into `useSystemStore.ts` status mapping
- [x] Update MSW fixture in `fixtures.mjs`

### T1-B: Status response — readiness envelope incomplete

**Source**: tempdoc 341

**Work:**
- [x] Add `chunkEmbedding` and `lambdamartModel` to `ReadinessComponentMap` type (already present in systemTypes.ts)
- [x] Update `ReadinessEnvelopeSchema` in `schemas.ts`

### T1-C: Search response — metadata fields missing from hit type

**Source**: tempdoc 362

**Work:**
- [x] Add `meta_source?`, `meta_author?`, `meta_category?`, `meta_published_at?` to `SearchHit`
- [x] Map them through `mapKnowledgeSearchResponse()` mapper

### T1-D: Search response — provenance and pipeline execution silently dropped

**Source**: tempdoc 291

**Work:**
- [x] Add `pipelineExecution?` to `SearchResponse` type
- [x] Add `provenance?` to `SearchHit` type (with full `HitProvenance` interface)
- [x] Wire both through the mapper

### T1-E: Search filters — metadata filter fields missing

**Source**: tempdoc 362

**Work:**
- [x] Add `metaSource`, `metaAuthor`, `metaCategory`, `metaPublishedAt` to `SearchFilters`
- [x] Add corresponding fields to `UiSearchFilters` + defaults
- [x] Wire in `useSearch.ts` `buildOptions()` → backend request
- [x] Add facet requests for `meta_source`, `meta_author`, `meta_category`
- [x] Wire URL serialization/deserialization for metadata filters

### T1-F: Pipeline config — missing fields in buildOptions

**Work:**
- [x] Add `freshnessEnabled?: boolean` to `PipelineConfig` type
- [x] Decision: rely on backend defaults for `spladeEnabled`, `crossEncoderWindow`, `expansionEnabled`, `freshnessEnabled`. Expose via advanced search UI later (Tier 2).

### T1-G: hasActiveFilters bug — entity arrays ignored

**Work:**
- [x] Fix `hasActiveFilters` to check entity + metadata array lengths

---

## Tier 2 — Missing UI Surfaces

### T2-A: GPU dashboard in HealthView

**Source**: tempdocs 335, 337

**Work:**
- [x] Add GPU card to HealthView: VRAM bar (used/total, color-coded), utilization %, driver version, device count
- [ ] Show GPU-enabled status per subsystem (embed, SPLADE, reranker) — deferred (needs `GpuDiagnosticsView` fields which are separate from the `gpu` NVML sub-object)
- [x] Gate behind GPU data availability (`gpu.available != null`); show "No GPU detected" fallback

### T2-B: Metadata facet filters in SearchFiltersBar

**Source**: tempdoc 362

**Work:**
- [x] Add metadata facet sections to `SearchFiltersBar` (META_FACETS data-driven, same pattern as entity facets: search, expand/collapse, toggle pills)
- [x] Add filter chips for active metadata filters (Source/Author/Category)
- [x] Include metadata filters in `hasActive` check
- [ ] Add published-date range filter control (deferred — needs date picker component)
- [x] Display `meta_*` fields on result rows in rich density mode (source/author/category badges in metadata line)
- [x] Add `metaSource`, `metaAuthor`, `metaCategory` to `HUDResultItem` and wire through `resultMapper`

### T2-C: RAG quality signals in chat UI — DEFERRED

**Source**: tempdoc 345. Data pipeline complete (SSE events parsed in
`streams.ts`). Needs UI design for confidence indicators and citation
grounding badges. Low urgency — the AI answer UX works without it.

### T2-D: Search diagnostics / provenance panel — DEFERRED

**Source**: tempdocs 291, 306. Data pipeline complete (`pipelineExecution`
and `provenance` now flow through to `SearchResponse` and `SearchHit`).
Needs design for expandable diagnostics section with timing breakdown,
query classification badge, and per-hit provenance. Power-user feature.

### T2-F: Search Quality Features shows wrong reranker status

**Source**: BrainView audit (2026-03-29)

BrainView "Search Quality Features" shows "Search reranking: Inactive —
Model not found at standard locations" when the cross-encoder actually
executes on every search (confirmed: `crossEncoderApplied: true`, 388ms).

**Root cause**: `WorkerModelDiscovery.discoverAll()` passes `null` for
explicit path — can only find models at auto-discovery filesystem
locations. But `RerankerConfig.fromEnv()` (which activates the actual ORT
session) reads `JUSTSEARCH_RERANK_MODEL_PATH`. When the model is provided
via env var forwarded to the Worker subprocess, discovery reports "not
found" while the pipeline uses it.

**Fix options** (in order of preference):
1. **Frontend**: Read reranker status from `pipelineExecution.components.cross_encoder`
   in search responses (ground truth — reflects what actually executed)
   rather than relying on ONNX feature discovery (which reflects static
   file presence, not runtime state)
2. **Backend**: Fix `WorkerModelDiscovery` to pass explicit path from
   resolved config, not `null`
3. **Backend**: Add a `rerankerActive` field to `/api/status` that
   reflects the Worker's actual ORT session state

**Work:**
- [x] Fix approach: read `rerankerOrtCuda` and `rerankerModelPath` from
  system store (ground truth from `/api/status`) and override stale ONNX
  discovery status in `BrainRuntimeSection.tsx`
- [x] Added `OrtCudaStatus` type, `OrtCudaSchema`, and per-subsystem
  ORT/path fields (`rerankerOrtCuda`, `embedOrtCuda`, `spladeOrtCuda`,
  `rerankerModelPath`, `spladeModelPath`, `nerModelPath`) to types,
  schemas, and store mapping
- [x] Extracted `SearchQualityFeaturesSection` component that corrects
  ONNX feature status using store data before rendering
- [x] Verified in Chrome: "Search Quality Features" now shows "1/2 active"
  with "Search reranking: Active" + correct model path

### T2-E: Search config & encoder profiles — DEFERRED

**Source**: tempdocs 343, 357. Data available in status store (flat search
config fields, `encoderProfiles`). Needs design for diagnostics view
showing active search configuration and per-encoder ORT performance.
Developer-facing feature.

---

## Tier 3 — Cleanup & Hardening

### T3-A: Remove flat-field remnants from status types (341 Phase 4d)

**Source**: tempdoc 341

**Work:**
- [x] Audit all consumers — confirmed all use `grouped ?? flat` pattern
- [x] Fix `deriveHealthEvents.ts` line 238 — was reading flat `reindexRequiredReason` without grouped fallback
- [x] Remove flat embedding fields from `SystemStatus` (4 fields)
- [x] Remove flat schema fields (5 fields)
- [x] Remove flat chunk fields (6 fields)
- [x] Remove flat queue-DB fields (5 fields)
- [x] Remove flat migration fields (`migrationState`, `buildingGenerationId`, `previousGenerationId`)
- [x] Remove corresponding flat schemas from `SystemStatusSchema`
- [x] Remove flat-field fallback `??` mapping lines from `useSystemStore.ts`
- [x] Wire new sub-objects (`gpu`, `meta`, `encoderProfiles`) + flat search config fields in store
- [x] Update all component consumers to grouped-only reads:
  - `BrainCompatibilitySection.tsx` (embedding, schema, chunk)
  - `StatusDeck.tsx` (reindexRequired)
  - `deriveHealthEvents.ts` (schema, embedding, queueHealth)
  - `HealthView.tsx` (queueHealth)
- [x] Update `deriveHealthEvents.test.ts` fixtures to use grouped sub-objects
- [x] Remove redundant flat `embeddingCompatState` from `fixtures.mjs`

### T3-B: MSW fixtures — align with current backend shape

**Work:**
- [x] Add `gpu`, `meta`, flat search config fields, `encoderProfiles` to MSW status fixture
- [x] Add `meta_source`, `meta_author`, `meta_category` to search hit `fields` in MSW mock
- [x] Add `provenance` (per-hit scoring breakdown) to MSW search hits
- [x] Add `pipelineExecution` and `indexCapabilities` to MSW search response
- [ ] Add `rag_meta` and `citation_matches` to MSW streaming fixtures (deferred — T2-C)

### T3-C: Unused status fields — surface or document

**Decision (implemented):**
- **Kept** `failedByFileKind`, `throughputDocsPerSec`, `throughputWindowState` — natural HealthView candidates
- **Removed** `contentLengthAvgChars/MinChars/MaxChars`, `searchesZeroResultCount`, `rerankerOrtCuda` — no foreseeable UI home
- **Removed** `OrtCudaStatus` interface and `OrtCudaSchema` (both now unused)

### T3-E: Citation scorer tokenizer URL wrong in model registry

**Source**: BrainView audit (2026-03-29)

`model-registry.v1.json` line 121: citation scorer tokenizer downloads
from `Xenova/ms-marco-MiniLM-L-6-v2` (the reranker model) instead of
`Xenova/ms-marco-MiniLM-L-2-v2` (the citation scorer model). The
`termsUrl` (line 123) also points to the reranker's HuggingFace page.

The SHA256 still matches (tokenizer was actually built from L-6-v2), so
downloads work. But the provenance metadata is incorrect — the Install AI
section shows the wrong source link for the citation scorer tokenizer.

**Work:**
- [x] Fix URL in `model-registry.v1.json` to `Xenova/ms-marco-MiniLM-L-2-v2`
- [x] Fix `termsUrl` to `cross-encoder/ms-marco-MiniLM-L-2-v2`
- [x] Verified SHA256 matches — L-2 and L-6 tokenizers are identical files
  (same hash `D241A60D...`, same size 711396 bytes)

### T3-D: retrieve-context endpoint — frontend integration — DEFERRED

**Source**: tempdoc 345. Product decision pending — keep backend-internal
for now (SSE `/api/ask/stream` already uses them). Revisit if a "retrieve
without asking" mode or pre-flight quality check UX is designed.

---

## Contract Audit (2026-03-29)

Systematic verification of all frontend-backend API contracts. Three
mismatches found and fixed:

| # | Endpoint | Issue | Fix |
|---|---|---|---|
| 1 | `GET /api/status` | `SearchConfigView` is `@JsonUnwrapped` — fields flat at top level, not under nested `searchConfig` key. Frontend had `searchConfig?: SearchConfigGroup` → always `undefined`. | Changed to 10 flat fields on `SystemStatus` and `SystemStatusSchema`. Updated store mapping, MSW fixture, and fixture tests. |
| 2 | `GET /api/status` | `EncoderProfileView.phaseTotalUs` is `Map<String,Long>` (JSON object). Frontend declared `number` (scalar). | Fixed to `Record<string, number>` in type and Zod schema. Updated MSW fixture. |
| 3 | `GET/POST /api/settings/v2` | `vimMode: Boolean` in backend `UiSettingsV2` DTO. Frontend `UISettings` and `UiSettingsV2Schema` missing it — round-trip loss on settings save. | Added `vimMode?: boolean` to type and schema. |

**Harmless gap (not fixed):** Grouped sub-objects emit `observedAtMs: long`
undeclared in frontend types. `.loose()` schema passes it through silently.
Not consumed — left as-is.

**All other endpoints verified clean:** search request/response (all filter
and pipeline fields match, including `metaSource`/`freshnessEnabled`),
preview, browse, agent, streaming (summarize/ask/batch), reindex, worker
restart. No removed or renamed endpoints.

---

## Implementation Progress

| Phase | Items | Status |
|---|---|---|
| **Phase 1: Types & data** | T1-A, T1-B, T1-C, T1-D, T1-E, T1-F, T1-G | **Done** |
| **Phase 2: Cleanup** | T3-A, T3-B, T3-C | **Done** |
| **Phase 3: UI — filters** | T2-B (metadata facets) | **Done** (date picker deferred) |
| **Phase 4: UI — GPU** | T2-A (GPU dashboard) | **Done** |
| **Phase 5: Contract audit** | All endpoints | **Done** — 3 mismatches fixed |
| **Phase 6: BrainView audit** | T2-F (reranker status) | **Done** — verified in Chrome |
| **Phase 7: Manifest fix** | T3-E (citation URL) | **Done** — SHA verified identical |
| **Phase 8: Semantic validation** | Status bar, simple BrainView, settings round-trip, remaining views | **Done** — 1 issue found |
| **Phase 9: Root-cause investigation** | T1–T5 implementation feasibility for 368 | **Done** — plans produced, all overlap with 368 |
| **Deferred** | T2-C, T2-D, T2-E, T3-D | Data pipeline ready; UI design needed |

---

## Visual Verification

- **Chrome (real backend):** All views verified against real backend at
  `http://127.0.0.1:33221` with 611 indexed files. GPU card renders real
  NVML data (1.7 GB / 12 GB VRAM, 26% utilization, driver 595.79).
  HealthView shows all grouped sub-objects correctly. BrainView compatibility
  cards render from grouped fields after flat-field removal.
- **Search parity verified:** Same query ("search architecture") produces
  identical top-10 ranking (same docs, same order, same scores) across:
  UI in Chrome, direct API curl, and jseval hybrid mode. Cross-encoder
  executes in all three paths.
- **`jseval ui-shot`:** `home`, `health`, `search-results`, `filters-chips`,
  `ai-brain`, `ai-brain-advanced` all captured and verified.
- **TypeScript**: All typechecks pass.
- **Unit tests**: 190/190 pass (16 test files).

---

## BrainView Audit (2026-03-29)

Critical analysis of the AI Brain view against the real backend.

### Working correctly (post flat-field removal)

- **Schema mismatch card**: Renders from `status.schema.compatState` →
  "Index schema mismatch detected", reason "schema mismatch", fingerprint
  hashes, "Rebuild Index" CTA. Grouped-only reads work correctly.
- **Chunk vector card**: "Semantic search ready — 4,573 chunks indexed
  with vectors". Reads from `status.chunkCoverage`.
- **Embedding card**: Correctly hidden (`embedding.compatState` is
  `COMPATIBLE`).
- **Install AI**: 9 model components listed with HuggingFace URLs, all
  "pending".
- **Runtime/Models/Inference settings**: All render correctly with real data.

### Pre-existing issues discovered (not caused by 364)

**1. "Search reranking: Inactive" when cross-encoder actually executes**

The "Search Quality Features" section shows "0/2 active" with "Search
reranking: Inactive — Model not found at standard locations". But the
search pipeline confirms cross-encoder executes (`crossEncoderApplied:
true`, 388ms).

**Root cause**: Two independent model discovery paths:
- `WorkerModelDiscovery.discoverAll()` passes `explicitPath = null` —
  can only find models at auto-discovery filesystem locations. Reports
  to Head via gRPC health check → cached in `RemoteKnowledgeClient`.
- `RerankerConfig.fromEnv()` in `KnowledgeServer.initDeferredModels()`
  reads `JUSTSEARCH_RERANK_MODEL_PATH` env var — this is what actually
  activates the live ORT session.

If the model is provided via env var (forwarded to Worker by
`WorkerSpawner`) but not at auto-discovery paths, the UI shows
"Inactive" while the pipeline actually uses it. Additionally, a startup
timing window exists where the cache is empty until the first
`getHealthCheck()` round-trip completes.

**Scope**: Backend issue (`WorkerModelDiscovery.java` line 33,
`RuntimeActivationService.java` lines 188-223). Not a frontend bug.
Separate tempdoc needed if fixing.

**2. Citation scorer tokenizer points to wrong HuggingFace URL**

`model-registry.v1.json` line 121: citation scorer tokenizer downloads
from `Xenova/ms-marco-MiniLM-L-6-v2` (the reranker model) instead of
`Xenova/ms-marco-MiniLM-L-2-v2` (the citation scorer model). The
`termsUrl` also points to the reranker's page. The SHA256 will still
match (tokenizer was actually built from L-6-v2), but the provenance
metadata is incorrect.

**Scope**: Backend manifest issue. Cosmetic — doesn't affect functionality.

---

## Model Download System Audit (2026-03-29)

Critical analysis of the complete model install pipeline (registry, download,
discovery, UI).

### What works

- **Download pipeline**: BITS-first with curl fallback, SHA-256 verification,
  atomic moves, resume support, disk-persisted state across restarts
- **Policy system**: Machine → user → bundled allowlist priority, fail-closed
  on parse errors, per-model SHA allowlisting
- **Progress UI**: 1s polling, per-asset byte progress, phase stepper,
  optimistic updates

### Issues found

**1. Registry would overwrite upgraded reranker with old model (quality regression)**

The actual `models/onnx/reranker/model.onnx` on disk is the **gte-modernbert**
model (340 MB, installed via tempdoc 343). The registry's `onnx-reranker` entry
points to the old `ms-marco-MiniLM-L-6-v2` (23 MB) with `targetPath:
onnx/reranker/model.onnx`. Clicking "Install AI" would overwrite the better
model with the old one.

The registry has the gte model as a separate entry (`onnx-reranker-gte`) but
its `targetPath` is `onnx/reranker-gte/model.onnx` — NOT the auto-discovery
path. `WorkerModelDiscovery` looks for `onnx/reranker/`, not
`onnx/reranker-gte/`. So the upgraded model would be downloaded but never
auto-discovered.

**Fix needed (separate tempdoc)**: Either update the registry so
`onnx-reranker-gte` targets `onnx/reranker/` (replacing the old entry), or
update `WorkerModelDiscovery` to prefer `onnx/reranker-gte/` when present.

**2. All 9 assets show `installed: false` despite working models**

The manifest's `installed` check compares SHA-256 against the registry.
Manually installed or upgraded models don't match the registry SHA, so
everything shows "Not Installed" even though the reranker, embedding, and
chat models are all active and functioning.

**3. No skip-if-working logic**

The installer doesn't check whether a model is actually loaded and
functioning — only whether its SHA matches the registry. A user with
manually installed upgraded models sees "Not Installed" and is encouraged
to click Install, which would downgrade their models.

**4. `arrangeAssetIfNeeded` copies, not moves — orphan files**

Downloads go to `models/<filename>`, then get **copied** (not moved) to
`models/<targetPath>`. Both copies persist on disk. Across all ONNX assets
this wastes ~190 MB.

**5. Citation scorer model missing from disk**

`onnx/citation-scorer/` directory doesn't exist on this machine. The UI
correctly shows "Citation scoring: Inactive". This is expected (model was
never installed), but the Install AI flow would fix it.

### Current state on disk

| Path | Content | Size | Active? |
|---|---|---|---|
| `models/onnx/reranker/` | gte-modernbert (manually installed) | 340 MB | Yes (`rerankerOrtCuda.available: true`) |
| `models/onnx/reranker-gte/` | (does not exist) | — | — |
| `models/onnx/citation-scorer/` | (does not exist) | — | No |
| `models/*.gguf` | Qwen3.5-9B + mmproj | ~5.9 GB | Chat: yes, VDU: not tested |

---

## Open Questions (resolved or deferred)

1. ~~**Backend projection gap (T1-C)**~~ — **Resolved.** REST response
   already includes `meta_*` stored fields. MCP slim results updated in
   `fceffdda5`.
2. ~~**Pipeline config UI (T1-F)**~~ — **Decided.** Backend defaults; expose
   later via advanced search UI.
3. **Diagnostics scope (T2-D, T2-E)**: New view vs. expandable sections in
   existing views? — Deferred.
4. ~~**Flat-field removal timing (T3-A)**~~ — **Done.** Removed immediately;
   all consumers confirmed on grouped reads.
5. ~~**Evidence capture**~~ — **Superseded by tempdoc 365** (`jseval ui-shot`
   / `jseval ui-check`). Old Node.js evidence system deleted.
6. ~~**Search Quality Features status vs pipeline reality**~~ — **Fixed.**
   Frontend now reads `rerankerOrtCuda` from `/api/status` (ground truth)
   and overrides stale ONNX discovery status. Shows "1/2 active" correctly.
7. ~~**Citation scorer tokenizer URL**~~ — **Fixed.** Updated to
   `ms-marco-MiniLM-L-2-v2`. SHA verified identical.
8. ~~**Settings don't round-trip**~~ — **Not a bug.** The test backend
   was a leftover `runHeadlessEval` instance with
   `PersistenceMode.IN_MEMORY` (save is a no-op, load returns defaults).
   A normal `runHeadless` backend uses `READ_WRITE` mode and settings
   persist correctly to `%APPDATA%\justsearch\ui\settings.json`.
   Fixed secondary frontend issue: added `vimMode` to
   `api/domains/settings.ts` `UISettings` (was missing from the API
   client type, present in store types and Zod schema).
9. **Model registry + discovery root causes** — Tracked in tempdoc 368
   (architecture root causes): stale registry would overwrite upgraded
   reranker, fragmented model identity across 5 systems, no capability
   vs version concept, install shows "not installed" for working models.
10. **T1–T5 implementation investigation (Phase 9)** — Concrete
    implementation plans produced for all 5 theoretical fixes from 368.
    **All 5 items overlap completely with 368's scope** — this work is
    implementation planning for 368, not new 364 scope. Key findings:
    - T1: Existing `StatusRecordSchemaTest` + victools infrastructure;
      blocked by `@JsonUnwrapped` (12 annotations) until T5 lands
    - T2: 6-step plan to route ORT session state through gRPC health
      check; ~8 files across 4 modules
    - T3: Minimal fix is 4 registry edits (no Java); full capability
      schema deferred until variant count justifies it
    - T4: Smallest item — add `meta.persistenceMode` to settings
      response + wire staleness slots already in `ReadinessComponentView`
    - T5: New `/api/status/v2` endpoint with explicit nesting; Java side
      trivial (~3 files), frontend migration is the bulk (~40 field
      access updates across components)

---

## Model Distribution Audit (2026-03-30)

Complete inventory of the model landscape across all three distribution
channels: Tauri bundle, in-app registry download, and Git LFS.

### Three independent systems, no single source of truth

| System | What it controls | Config location |
|---|---|---|
| **Tauri bundle** (`bundleSidecar`) | Models shipped in installer | `modules/ui/build.gradle.kts` (hardcoded URLs + SHA-256) |
| **In-app registry** | Models downloaded via Install AI | `modules/ui/src/main/resources/ai/model-registry.v1.json` |
| **Git LFS** | Models available at dev clone time | `.gitattributes` (`*.onnx`, `*.gguf` → LFS) |

These three are completely disconnected. `build.gradle.kts` hardcodes its
own URLs and hashes independently of the registry. The registry doesn't
know about Git LFS. Git LFS doesn't know about the registry.

### What a fresh Tauri install gets

| Model | What's bundled | What actually runs | Gap |
|---|---|---|---|
| **Reranker** | ms-marco-MiniLM-L-6-v2 INT8 (22 MB) | gte-multilingual-reranker-base FP32 (340 MB) | **3 generations behind** |
| **Citation scorer** | ms-marco-MiniLM-L-2-v2 INT8 (15 MB) | ms-marco-MiniLM-L-6-v2 (23 MB) | **1 generation behind** |
| **SPLADE** | (not bundled) | Custom PRESPARSE build (498 MB FP16) | **No install path** |
| **NER** | (not bundled) | Davlan multilingual (135 MB) | **No install path** |
| **Embedding ONNX** | (not bundled) | gte-multilingual-base FP16 (628 MB) | **No install path** |
| **Chat GGUF** | (not bundled) | Qwen3.5-9B (5.49 GB) | Download via registry (different model: Qwen3-VL-8B) |
| **llama-server** | b8571 CPU + CUDA 12.4 | Same | OK |

**A fresh Tauri install has no working search quality features beyond
basic BM25.** No dense retrieval (no embedding ONNX), no sparse retrieval
(no SPLADE), no NER, wrong reranker. The only ONNX models bundled are
from the original founding-era model set.

### Registry vs disk mismatches

| Capability | Registry asset | Disk (actual) | Match? |
|---|---|---|---|
| Reranker | gte-reranker-modernbert-base INT8 (150 MB) | gte-multilingual-reranker-base FP32 (340 MB) | **No** |
| Citation | ms-marco-MiniLM-L-2-v2 INT8 (15 MB) | ms-marco-MiniLM-L-6-v2 (23 MB) | **No** |
| Chat | Qwen3-VL-8B-Thinking Q4 (4.68 GB) | Qwen3.5-9B Q4 (5.49 GB) | **No** |
| Embedding | nomic-embed-text GGUF (80 MB, llama.cpp) | gte-multilingual-base ONNX FP16 (628 MB, ORT) | **Different runtime** |
| SPLADE | (not in registry) | Custom PRESPARSE (498 MB) | N/A |
| NER | (not in registry) | Davlan multilingual (135 MB) | N/A |

### Models that cannot be downloaded from any public source

Only **SPLADE** is truly custom — `scripts/models/build-splade.py` bakes
PRESPARSE ops (ReLU → log1p → ReduceMax → TopK(k=256)) into the ONNX
graph. The output interface (INT64 indices + FLOAT32 weights) is
JustSearch-specific. No public HuggingFace artifact exists with this
format. The build is fully reproducible (`build.json` with HF commit
hash), but requires Python + PyTorch + ONNX toolchain.

All other models are either standard HF downloads or simple
transformations (FP16 conversion, split-file merge, INT8 quantization).

### Provenance gaps — 4 active models lack `build.json`

| Model | Directory | Why missing |
|---|---|---|
| gte-multilingual-base (embedding) | `onnx/gte-multilingual-base/` | Adopted post-348 provenance work |
| gte-multilingual-reranker-base | `onnx/reranker/` | Adopted post-348 provenance work |
| Davlan multilingual NER | `onnx/ner/` | Adopted post-348 provenance work |
| ms-marco-MiniLM-L-6-v2 (citation) | `onnx/citation-scorer/` | Adopted post-348 provenance work |

Build scripts exist (`build-crossencoder.py`, `build-ner.py`,
`build-embedding.py`) and would generate `build.json` if re-run, but the
current active model directories were populated manually during the
tempdoc 343 multilingual upgrade.

### Resolution: model distribution architecture

**Root cause:** Three distribution channels (Tauri bundle, registry, Git
LFS) evolved independently. None reflects the current model stack.

**Two user populations:**
- **Developers** — get everything via Git LFS at clone time. Registry and
  Tauri bundle are irrelevant.
- **End users (Tauri installer)** — need either bundled models or a
  download path. Currently neither path gives them SPLADE, NER, or
  embedding ONNX.

#### Principle 1: Registry is the single source of truth

`model-registry.v1.json` declares every model the system needs.
`build.gradle.kts` staging tasks read URLs and hashes from the registry
(or a shared manifest) rather than hardcoding their own stale values.

#### Principle 2: Bundle ONNX, download GGUF

The bundle/download boundary follows the product model: **search is the
core product**, so search-critical ONNX models ship in the installer.
Chat/vision GGUF files are optional features and huge — they remain
download-only.

| Tier | Models | Distribution | Rationale |
|---|---|---|---|
| **Bundled** | Reranker, SPLADE, NER, Embedding ONNX, Citation scorer | Tauri installer resources | Search works on first launch (~1.7 GB) |
| **Downloaded** | Chat GGUF, mmproj GGUF, nomic embedding GGUF | In-app "Install AI" | Optional, large (~5.8 GB) |

#### Principle 3: Host custom artifacts

SPLADE (and any future custom-built model) needs a public download URL
for the registry. Options in preference order:

1. **HuggingFace model repo** — `justsearch/splade-presparse-multilingual-v1`.
   Follows ecosystem conventions. Tempdoc 348 already considered this.
2. **GitHub Releases** — attach `.onnx` as release assets on the
   JustSearch repo. Free, versioned, SHA-verifiable.
3. **Bundle-only** — skip download, include in installer. Simplest
   fallback if hosting is deferred.

For the initial fix, option 3 (bundle-only) is sufficient. Hosting can
be added later when the registry needs to serve fresh installs that
don't use the Tauri installer (e.g., headless server deployments).

#### Principle 4: Backfill provenance

Run build scripts against the 4 active model directories missing
`build.json` to establish reproducible audit trails. This is independent
of distribution — it's about being able to rebuild the models from
source if the LFS copies are ever lost.

### Validated production model set (tempdoc 343 Phase D, git 5d19ff2c1)

Source: tempdocs 358 (selection) → 343 (validation). Model research is
explicitly declared complete ("Model switches — settled").

| Role | Model | On-disk variant | Size | Source |
|---|---|---|---|---|
| **Embedding** | `Alibaba-NLP/gte-multilingual-base` | FP16 (CPU+GPU) | 628 MB | `models/onnx/gte-multilingual-base/model_fp16.onnx` |
| **SPLADE** | `opensearch-neural-sparse-encoding-multilingual-v1` | FP16 GPU, FP32 CPU, PRESPARSE | 498+995 MB | `models/splade/naver-splade-v3/` |
| **NER** | `Davlan/distilbert-base-multilingual-cased-ner-hrl` | INT8 + FP16 | 135+270 MB | `models/onnx/ner/` |
| **Reranker** | `Alibaba-NLP/gte-multilingual-reranker-base` | FP32 + FP16 | 341+629 MB | `models/onnx/reranker/` |
| **Citation** | `cross-encoder/ms-marco-MiniLM-L-6-v2` | INT8 | 23 MB | `models/onnx/citation-scorer/` |
| **Chat (pkg)** | `Qwen3-VL-8B-Thinking-Q4_K_M.gguf` | GGUF Q4 | 4.68 GB | Registry download |
| **Chat (dev)** | `Qwen3.5-9B-Q4_K_M.gguf` | GGUF Q4 | 5.49 GB | Not in registry |

**`embeddinggemma-300m` is NOT the active embedding** — earlier
investigation was wrong. `EmbeddingOnnxModelDiscovery` discovers it
first by probe order, but tempdoc 343 validated with gte-multilingual-base
and F-012 confirmed it was always active. The discovery priority may
need auditing — if embeddinggemma-300m directory is present, it shadows
the validated model.

**368 agent conflict**: The 368 worktree retargets the registry's
`onnx-reranker-gte` entry (gte-reranker-modernbert-base INT8, 150 MB)
to `onnx/reranker/`. But the validated production model is
gte-multilingual-**reranker**-base FP16 (340 MB) — a different, larger
model. The registry entry needs to be updated to the correct model
identity and hash, not just retargeted.

### Concrete changes needed

**Phase A — Fix `build.gradle.kts` staging (Tauri bundle ships correct models)**

Switch from download-and-rename to copy-from-local for all ONNX models.
The models exist in Git LFS; Tauri builds require a full checkout anyway.

Changes to `modules/ui/build.gradle.kts`:

1. Replace `downloadRerankerModel`/`downloadCitationModel`/
   `downloadOnnxTokenizer` download-rename pipeline with local copy:
   - `models/onnx/reranker/` → `build/.../models/onnx/reranker/`
   - `models/onnx/citation-scorer/` → same pattern
2. Add new staging for the 3 missing model types:
   - `models/onnx/ner/` → `build/.../models/onnx/ner/`
   - `models/onnx/gte-multilingual-base/` → same pattern
   - `models/splade/naver-splade-v3/` → `build/.../models/splade/naver-splade-v3/`
3. Each directory should include: `model.onnx` (or variant per
   `model_manifest.json`), `tokenizer.json`, `config.json`,
   `model_manifest.json` (if present), `pooling_config.json` (if present).
   Exclude: `*.optimized`, `*.opt-meta`, `*.sha256`, `build.json`,
   FP32 variants when FP16 exists (save bundle space).
4. Update `generateOnnxNotice` to cover all 5 model types with correct
   attribution (Apache 2.0 for all except NER which is AFL-3.0).
5. Update smoke-test validation (lines 1335-1358) to check all 5 paths.
6. Keep download tasks as dead code or behind a flag for CI-without-LFS
   scenarios — but they should download the correct models if ever
   re-enabled.

**Bundle size estimate (FP16/INT8 only, smallest viable per model):**
- Embedding FP16: 628 MB + tokenizer + configs
- SPLADE FP16: 498 MB + tokenizer + configs
- NER INT8: 135 MB + tokenizer + config
- Reranker FP32: 341 MB + tokenizer (no INT8 variant on disk)
- Citation INT8: 23 MB + tokenizer
- **Total: ~1.6 GB** (up from 38 MB)

**Phase B — Update registry to match validated baseline**

1. Replace `onnx-reranker-gte` entry: update model identity from
   `gte-reranker-modernbert-base` to `gte-multilingual-reranker-base`,
   update SHA-256 to `ccf51dba...` (340 MB FP32), update URLs (need
   to determine — may not have a direct HF ONNX download URL, in which
   case mark as bundled-only)
2. Replace `onnx-citation-scorer` entry: update from L-2 to L-6
   (`cross-encoder/ms-marco-MiniLM-L-6-v2`), SHA `a13ec391...` (23 MB)
3. Add `onnx-splade` entry: no public URL (custom PRESPARSE build),
   needs hosted artifact or bundled-only flag
4. Add `onnx-ner` entry: downloadable from HF community exports
5. Add `onnx-embedding` entry: gte-multilingual-base, needs hosted
   artifact or community ONNX URL
6. Coordinate with 368 agent — their registry edits need the corrected
   model identity (multilingual, not modernbert)

**Phase C — Backfill `build.json` provenance**

4 active model directories lack `build.json`:
- `models/onnx/gte-multilingual-base/`
- `models/onnx/reranker/`
- `models/onnx/ner/`
- `models/onnx/citation-scorer/`

Run build scripts to generate provenance. Verify output SHA-256 matches
committed LFS files.

**Phase D — Embedding discovery priority audit**

`EmbeddingOnnxModelDiscovery` tries `embeddinggemma-300m` before
`gte-multilingual-base`. If both directories exist, the wrong model
is loaded. Either:
- Remove `embeddinggemma-300m/` from disk (it's superseded)
- Or fix the discovery priority to try `gte-multilingual-base` first

### Model freshness (as of 2026-03-30)

| Model | Role | Weights vintage | Last HF update | Successor? | Status |
|---|---|---|---|---|---|
| `gte-multilingual-base` | Embedding | 2024-07 | 2025-07 | `gte-modernbert-base` exists but is EN-only | **Current** — no multilingual successor |
| `opensearch-neural-sparse-encoding-multilingual-v1` | SPLADE | 2025-02 | 2025-06 | EN-only v3-distill/v3-gte exist, no multilingual v2 | **Current** — only multilingual sparse model |
| `Davlan/distilbert-base-multilingual-cased-ner-hrl` | NER | 2022-03 | 2023-08 | None from same author. `xlm-roberta-large-ner-hrl` is larger sibling. GLiNER is modern alternative | **Stale** — frozen since Aug 2023, 3+ years old |
| `gte-multilingual-reranker-base` | Reranker | 2024-07 | 2025-07 | `gte-reranker-modernbert-base` (EN-focused) | **Current** — no multilingual successor |
| `ms-marco-MiniLM-L-6-v2` | Citation | 2021 (weights) | 2025-08 (lib) | None in same family. GTE rerankers are stronger | **Legacy** — 5-year-old weights, still widely used |
| `Qwen3-VL-8B-Thinking` | Chat (pkg) | 2025-10 | 2025-11 | Qwen3.5 text series out, no 3.5-VL-Thinking yet | **Current** — latest VL-Thinking |
| `Qwen3.5-9B` | Chat (dev) | 2026-02 | 2026-03 | Latest generation | **Current** |

**Age is an acknowledged trade-off, not a gap.** Tempdoc 358 explicitly
documents that all recommended models fail the S6 soft preference
(released within 4 months). The constraint set (multilingual + small
encoder + permissive license + ONNX + ≤600M params) rules out
everything newer. The industry moved to large decoder models that don't
fit these constraints. Key rationale per model:

- **NER (2023):** Only DistilBERT-architecture multilingual NER with
  permissive license and community ONNX. The `xlm-roberta-base` sibling
  (278M, 20 langs) was deprioritized as 4x larger with different arch.
  GLiNER is a different paradigm (zero-shot) requiring new infrastructure.
- **Citation scorer (2021 weights):** L-6 is the optimal quality/speed
  point in the MiniLM family (+4.16 MRR@10 over L-2, while L-12 gives
  zero gain at 2x cost). Using the GTE reranker (306M) for citation
  was explicitly rejected — 13x larger, requires code changes, and
  citations aren't multilingual-sensitive.
- **Embedding and reranker** (Jul 2024): The ModernBERT successors
  (Jan 2025) are EN-only. When a multilingual ModernBERT ships, it's a
  natural upgrade target.

**Phase E — Clean up superseded model directories**

Remove directories that are no longer active. Priority order:

1. **`models/onnx/embeddinggemma-300m/`** — URGENT: shadows production
   embedding due to discovery priority. Remove directory (or rename to
   `-backup`) so `gte-multilingual-base` is discovered.
2. **`models/onnx/embedding/`** — stale nomic-embed fallback, never
   reached when either embeddinggemma-300m or gte-multilingual-base
   exists.
3. Backup directories (low priority, just disk waste):
   `reranker-modernbert-backup/`, `reranker-minilm-backup/`,
   `citation-scorer-l2-backup/`, `ner-distilbert-en-backup/`,
   `splade/naver-splade-v3-backup/`
4. Evaluation candidates never adopted: `gte-base-en-v1.5/`,
   `gte-modernbert-base/`, `snowflake-arctic-embed-m-v2.0/`,
   `multilingual-e5-base/`, `bge-m3/`

**Phase F — Remove dead registry entries**

1. Remove `embedding` asset (`nomic-embed-text-v1.5.Q4_K_M.gguf`) —
   production embedding uses ORT (gte-multilingual-base), not
   llama.cpp. The GGUF embedding path is dead code.
2. Verify no code references the `embedding` asset ID for download or
   settings application (only `chat` and `embedding` are special-cased
   in `AiInstallService.applySettingsFromInstalledFiles()`).

**Phase G — Make `build.gradle.kts` read from registry (deferred)**

Replace hardcoded URLs/hashes with Gradle task that parses the registry.
Deferred until registry is complete (Phases A–B).

---

## Search Quality Verification (2026-03-30)

Post-implementation eval comparing against tempdoc 343 Phase D baselines
(git 5d19ff2c1, 2026-03-28). Run on SciFact with `jseval run --pipeline
--start-backend --clean`.

| Mode | 343 baseline | Current | Delta | CE? | Notes |
|---|---|---|---|---|---|
| lexical | 0.661 | 0.661 | **0.000** | No | Identical |
| bm25_splade | 0.668 | 0.669 | **+0.001** | No | Noise |
| full (CE off) | 0.714 | 0.707 | -0.007 | No | Dense now active (was "missing" in 343); BM25-dom weights dilute slightly |
| **hybrid (CE on)** | 0.734 | **0.737** | **+0.003** | Yes | UI mode — server-resolved, includes CE reranking |

**No regression.** The `hybrid` mode (what the UI uses) matches the 343
baseline. Embedding discovery fix correctly loads `gte-multilingual-base`
(confirmed in worker.log: `ONNX model 'gte-multilingual-base': found`).
Cross-encoder runs at p50=158ms per query (GPU FP16).

**Note on jseval `full` vs `hybrid`:** jseval's `full` mode has
`crossEncoderEnabled: false` hardcoded in `retriever.py`. The 343
baseline of 0.734 was measured with CE enabled via balanced-weight
experiments, not via jseval `full`. The `hybrid` mode uses server-side
resolution which includes CE — this matches the UI's search pipeline.

**Models confirmed active:**
- Embedding: `gte-multilingual-base` FP16 (SHA `f1d0f4ec...`) — GPU
- SPLADE: `naver-splade-v3` (multilingual PRESPARSE) — GPU
- NER: `onnx/ner` — GPU
- Reranker: `onnx/reranker` FP16 (`rerankerOrtCuda.available: true`) — GPU
- CE latency: p50=158ms (confirms reranker active in hybrid mode)
