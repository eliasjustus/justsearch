---
title: "269: Early Product Decisions Review"
type: tempdoc
status: done
created: 2026-03-09
updated: 2026-03-11
---

> NOTE: Noncanonical investigation doc. May drift.

# 269: Early Product Decisions Review

## Purpose

Audit early architectural decisions that are still baked into the codebase. The
embedding-inference fidelity gap (tempdoc 268) triggered this review, but the
scope is broader.

This tempdoc does NOT restate the ADRs — it adds value in two ways:

1. **Trigger Audit**: Each ADR/RISK has (or should have) a "reassess when"
   trigger. Which triggers have actually fired?
2. **Undocumented Decisions**: Stack/library choices that were never formally
   documented as ADRs but are deeply embedded.

Cross-references: `docs/decisions/` (10 ADRs), `docs/reference/architectural-risks.md`
(7 RISK entries), tempdoc 182 (planned-but-unimplemented features).

## Section A: ADR / Risk Trigger Audit

### A1. Three-Process Architecture (ADR-0001)

**Trigger:** None defined in ADR. *(Finding: this ADR needs a reassess trigger.)*

**Assessment:** The three concerns that motivated the split remain valid:
(1) Windows file locking makes Lucene + UI in one process dangerous,
(2) llama-server crashes independently and must not take down the UI,
(3) GC pauses from Tika/indexing would stutter the UI.

The two-process alternative (merge Head+Body) was rejected in the ADR because
wrapping llama-server's full API via FFM would multiply binding complexity. This
reasoning still holds — the Brain is a separate native binary by necessity, so
merging Head+Body is the only realistic consolidation. But the Worker owns
Windows-locked MMap files, and the Head must never touch them (hard invariant).
Merging would violate this.

**Verdict:** **Not fired. Keep.** The original constraints haven't changed.
Cold start is annoying but acceptable for a desktop app that runs persistently.

**Suggested trigger:** "Reassess when: JustSearch targets a platform where
file locking is not a concern (Linux/macOS-only), or the Worker process is
replaced by an external search server (see ADR-0003)."

### A2. gRPC + MMF Hybrid IPC (ADR-0002)

**Trigger:** None defined in ADR. *(Finding: this ADR needs a reassess trigger.)*

**Assessment:** MMF handles bootstrap signaling (port discovery, heartbeat,
GPU arbitration). gRPC handles structured data. The MMF layer required a
migration from `Unsafe` to Arena API (commit `a935a2c1`) due to JDK 26
deprecation — a maintenance cost not anticipated in the ADR. However, the
layer is small (single layout class, ~64 bytes of shared memory) and the
migration was straightforward.

The "reassess when" should track: (a) JDK removing the Arena-based memory
mapping capability, or (b) gRPC acquiring a reliable bootstrap mechanism
that eliminates the need for out-of-band port discovery.

**Verdict:** **Not fired. Keep.** The JDK 26 migration was a one-time cost.
MMF is small and well-tested.

**Suggested trigger:** "Reassess when: JDK removes or restricts memory-mapped
file access via Arena API, or gRPC adds a well-known port + service mesh
discovery mechanism suitable for local desktop use."

### A3. Direct Lucene, No Search Platform (ADR-0003)

**Trigger:** None defined in ADR. *(Finding: this ADR needs a reassess trigger.)*

**Assessment:** The tempdoc 249 investigation (2026-03) evaluated OpenSearch,
Vespa, Qdrant, Milvus, and others. None fit the desktop constraint — they all
require running a separate server with 500MB+ memory overhead. The direct
Lucene approach has accumulated custom code (schema migration, hybrid fusion,
HNSW tuning, commit strategy, corruption recovery), but this is inherent to
owning the search layer. The question is not "should we use Elasticsearch"
(no) but "have we built so much custom search-server logic that maintaining
it exceeds the benefit of direct Lucene?"

Current custom search-server surface:
- Blue/green schema migration (milestones A-G done)
- RRF hybrid fusion (BM25 + dense + SPLADE)
- LambdaMART reranking
- Custom commit strategy (time + size + shutdown triggers)
- HNSW parameter tuning
- Corruption recovery + generation-scoped layout

This is substantial, but it's well-factored (adapters-lucene module, extracted
collaborators from LuceneIndexRuntime) and tested. The alternatives from
tempdoc 249 all failed the desktop footprint test.

**Verdict:** **Not fired. Keep.** No viable desktop alternative exists.

**Suggested trigger:** "Reassess when: an embeddable search library emerges
with native hybrid retrieval (BM25 + vector + learned sparse) and a sub-200MB
footprint, or JustSearch moves to a client-server deployment model."

### A4. Single-Tenant GPU Policy (ADR-0004 + RISK-001)

**Trigger:** "Target GPU VRAM exceeds 16GB, or CUDA adds reliable cross-process
VRAM reservation."

**Evidence:**
- Consumer GPUs in 2026: RTX 4070 (12GB), RTX 4080/5080 (16GB), RTX 4090 (24GB).
  The 16GB threshold is now met by mainstream cards.
- CUDA still has no cross-process VRAM reservation API.
- **However:** Embedding defaults to CPU-only (`JUSTSEARCH_EMBED_GPU_LAYERS`
  is opt-in). For the default configuration, there is no GPU contention at all —
  the Brain gets the GPU exclusively for chat, and the Worker runs embeddings
  on CPU. The pain only exists for users who explicitly opt into GPU embeddings.
- The nomic-embed-text-v1.5 model is ~140MB GGUF — tiny compared to an 8B
  chat model. On a 16GB+ GPU, concurrent loading would be feasible.

**Verdict:** **Partially fired.** The 16GB VRAM threshold is met. But the
default CPU-only embedding path means most users won't hit the contention.
The real trigger is whether GPU embeddings become the *default* (e.g., if
ONNX Runtime with CUDA becomes the embedding path per tempdoc 268), at which
point mutual exclusion would actively hurt the default experience.

**Action:** Monitor. If tempdoc 268's ONNX migration proceeds and uses GPU
for embeddings, revisit this policy — ONNX + llama-server could coexist
on 16GB+ GPUs with explicit VRAM budgeting.

### A5. Manual FFM Bindings for llama.cpp (ADR-0005 + RISK-003)

**Trigger:** "jextract matures to handle llama.cpp's complex structs cleanly,
or the API surface we use grows beyond ~30 functions."

**Evidence:**
- NativeLlamaBinding now binds **35 functions** (1,192 lines). The ADR was
  written when the surface was ~15-20 functions. The >30 threshold is exceeded.
- jextract has not matured sufficiently for llama.cpp headers (as of JDK 25).
- Tempdoc 268 recommends migrating embeddings to ONNX Runtime, which would
  reduce the FFM surface to chat/generation only — but that work hasn't
  happened yet. Today, all 35 functions are in active use.

**Verdict:** **Fired.** The API surface exceeded the threshold. The risk
materialized in tempdoc 268's finding that FFM bindings may contribute to the
embedding fidelity gap (struct layout mismatches, memory management).

**Action:** This is actively being addressed by tempdoc 268's ONNX migration
direction. If embeddings move to ONNX, the FFM surface drops back to ~15-20
functions (chat/generation only, already served by llama-server HTTP). The
ADR should be updated to reflect the reduced scope.

### A6. Two-Pronged Citation Strategy (ADR-0006)

**Trigger:** None defined in ADR.

**Assessment:** The strategy is working as designed. LLM-generated citations
are the primary path (model quality has improved since the ADR was written).
CPU cross-encoder supplementation is a nice defense-in-depth. No pain signal.

**Verdict:** **Not fired. Keep.**

**Suggested trigger:** "Reassess when: LLM citation accuracy exceeds 95% on
our eval suite (cross-encoder becomes redundant), or cross-encoder latency
becomes a UX bottleneck."

### A7. Entity Faceting Over Knowledge Graph (ADR-0007)

**Trigger:** "Revisit full knowledge graph if: >20% of users request
relationship queries, a stable Apache 2.0 embedded graph DB emerges,
JustSearch targets enterprise/team use cases, or hardware improvements
make 2-4s/doc acceptable."

**Evidence:**
- No user base yet (pre-release). No demand signal for relationship queries.
- Embedded graph DB ecosystem unchanged (Kuzu abandoned Oct 2025, no successor).
- Entity faceting itself is still partially implemented (ONNX NER model
  selected, Lucene field schema defined, but integration not complete).

**Verdict:** **Not fired. Keep.** All four trigger conditions remain unmet.

### A8. Settings Are Ephemeral, Defaults Are Safe (ADR-0008)

**Trigger:** "If settings begin to control critical behavior (e.g., exclusion
rules, privacy settings, API keys), this decision must be revisited."

**Evidence:** UiSettings now contains **6 critical behavior fields**:
- `excludePatterns` — file exclusion rules (security-relevant)
- `indexBasePath` — index location (data isolation)
- `gpuLayers` — GPU offload control
- `contextLength`, `maxTokens` — inference limits
- `llmModelPath`, `embeddingModelPath` — model selection
- `serverExecutablePath` — BYO llama-server override

These go beyond "UI preferences." Losing `excludePatterns` on upgrade could
expose files the user explicitly excluded. Losing `indexBasePath` could cause
data confusion. Losing model paths requires the user to reconfigure their
entire AI setup.

However: no API keys or secrets are stored (confirmed). Source file paths
are in the index, not settings. A settings reset is annoying but not
data-destructive.

**Verdict:** **Partially fired.** Settings now control behavior beyond UI
preferences. The ADR's "silently replace with defaults" policy is risky for
`excludePatterns` and model paths. Worth adding schema versioning (the ADR
estimates ~30 min for M1, ~2-4 hours for M2 — low cost for the protection).

**Action:** Consider implementing ADR-0008's suggested M1 (schema version
field) as a low-cost safety net. Not urgent but reduces upgrade risk.

### A9. RISK-002: SQLite Job Queue Throughput

**Trigger:** "Ingestion benchmarks show >2x throughput regression vs. direct
queue, or users report slow bulk imports exceeding 30 minutes."

**Evidence:** Pre-release, no user reports. Queue has been hardened and
decomposed (SqliteJobQueue → ops classes). No benchmark data showing
throughput regression.

**Verdict:** **Not fired. Monitor.**

### A10. RISK-004: Embedding Model Baked Into Index

**Trigger:** "A significantly better embedding model emerges that warrants the
reindex cost."

**Evidence:** Tempdoc 268 RQ1 surveyed the landscape (2026-03). nomic-embed-
text-v1.5 remains appropriate. snowflake-arctic-embed-m-v2.0 is comparable
but doesn't justify reindex. nomic-embed-text-v2-moe is worth watching.

**Verdict:** **Not fired. Monitor.** No model change warranted today.

### A11. RISK-005: No Backup/Restore for Lucene Index

**Trigger:** "Users report data loss from index corruption, or index sizes
grow large enough that reindexing becomes impractical."

**Verdict:** **Not fired.** Pre-release, no data loss reports. Source files
remain the authority.

### A12. RISK-006: ILM ↔ Ops Lambda Coupling

**Trigger:** "ILM grows beyond 1000 lines again, or a new feature (multi-model,
remote inference) requires a cleaner component boundary."

**Evidence:** InferenceLifecycleManager.java is currently **1,053 lines**.
The 1,000-line threshold is exceeded.

**Verdict:** **Fired.** ILM has re-grown past the threshold despite the Ops
decomposition. The class still holds all shared state, lock ownership, and
mode transition logic. The next feature that touches inference lifecycle
(e.g., ONNX embedding migration from tempdoc 268, or multi-model support)
will make this worse.

**Action:** Decompose further before adding new features. The `ModeStateMachine`
extraction was a good start; the remaining state management and lock
ownership should follow a similar pattern.

### A13. RISK-007: Agent Infrastructure Maturity

**Trigger:** "Multi-agent handoff work begins (tempdoc 211), or context
compression A/B gate passes, or a second model is available for routing."

**Evidence:** Tempdoc 211 M0 is **resolved** (2026-02-20). Layers 1-9
implemented. Recommendations 3/5/6 deferred to future tempdocs. Context
compression gate has not passed. No second model for routing.

**Verdict:** **Partially fired.** M0 handoff is done but M1+ is deferred.
Single-agent loop is still the only production path.

---

## Section B: Undocumented Early Decisions

These are stack/library choices embedded in the codebase since early
development that never received formal ADRs.

### B1. Javalin as HTTP Framework

**Chosen:** Javalin (lightweight embedded HTTP, Kotlin-friendly).
**Current state:** LocalApiServer.java (803 lines), 7 route modules, 6
middleware handlers (exception, before/after timing, CORS, session token).
**Pain level:** **Low.** Javalin is used directly with no custom framework
layer built on top. Routes are well-modularized into separate classes.
The middleware surface is small and standard.
**Alternative:** Spring Boot or Quarkus would add dependency injection,
OpenAPI generation, and a larger ecosystem — but at 500ms+ startup penalty
and significant migration cost. For a desktop app that starts once and
runs continuously, Javalin's lightweight footprint is the right trade-off.
**Verdict:** **Keep.** No active pain. Migration cost not justified.

### B2. Tika for Content Extraction

**Chosen:** Apache Tika 3.2.3 for document text extraction.
**Current state:** ContentExtractor in indexer-worker. Tika handles PDF,
Office, and dozens of other formats. OCR fallback exists for scanned PDFs
(VDU path: render PNG → OCR). No table/layout awareness.
**Pain level:** **Medium for PDFs, low for everything else.** Tika's flat
text extraction loses table structure and complex layouts. This was the
motivation for the tempdoc 249 Docling investigation.
**Alternative:** Docling (Python sidecar) for layout-aware PDF parsing.
Findings from tempdoc 249: Docling is a natural fit as an **optional
enhancer** for PDFs where Tika loses structure (tables, figures, complex
layouts). It's not a Tika *replacement* — it's 50-100x slower (0.6-2.2
pages/sec vs. Tika's 100+ pages/sec) and requires a Python sidecar. The
recommended integration pattern: route PDFs with detected poor extraction
quality through Docling, keep Tika for everything else.
**Verdict:** **Keep Tika as primary, add Docling as optional enhancer.**
This is already the direction from tempdoc 249.

### B3. OpenTelemetry for Local-Only Observability

**Chosen:** Full OpenTelemetry SDK (tracing + metrics + context propagation).
**Current state:** NDJSON file exporter as primary (traces.ndjson,
metrics.ndjson). Optional OTLP HTTP exporter via environment variables
(opt-in, no default backend). RRD time-series store for curated metrics.
W3C trace context propagation across processes.
**Pain level:** **Low.** OTel adds dependency weight but the integration is
mature and well-structured. The NDJSON exporter is lightweight. No external
backend is required or configured by default. Metrics are consumed locally
via `/api/debug/state` and the RRD time-series.
**Alternative:** Micrometer for metrics only, structured logging for traces.
Would reduce dependency count but lose the standardized span model that
powers cross-process tracing (Head → Worker gRPC spans).
**Verdict:** **Keep.** OTel earns its weight through cross-process tracing
and standardized metric semantics. The opt-in OTLP exporter is a free
option for power users. No active pain.

### B4. Tauri Desktop Shell

**Chosen:** Tauri v2 (Rust + WebView2) as the desktop wrapper.
**Current state:** Tauri 2.10.2, 6 plugins (single-instance, opener, dialog,
window-state, autostart, notification). System tray, window persistence,
CSP, path validation all implemented. Auto-updater not yet implemented.
**Pain level:** **Low.** Tauri v2 migration is complete (not pending — the
initial tempdoc 269 draft was wrong). The Rust build adds CI time but the
Rust surface is small (lib.rs with ~10 commands). Most system access is
in Java, as noted. Tauri provides: native window frame, system tray,
single-instance enforcement, file dialogs, desktop notifications, autostart,
CSP, and future deep linking / global hotkey support.
**Alternative:** Electron (simpler build, larger ecosystem, 100MB+ memory
overhead) or browser-only (no tray, no autostart, no file dialogs, no
single-instance). Neither is clearly better.
**Verdict:** **Keep.** Tauri v2 is working well. The "Tauri v1 → v2
migration pending" concern from the initial draft was incorrect.

### B5. Java + Gradle + 14 Custom Build Plugins

**Chosen:** Java 21+, Gradle 8.x, `build-logic/` convention plugin layer.
**Current state:** 30+ modules, 14 custom convention plugins (JVM base,
locking, coverage, Error Prone, SpotBugs, CMake, SSOT tasks, llama-server
tasks, NPM tasks, Stylelint, dependency hygiene, etc.). JDK 26 evaluation
in progress (tempdoc 209).
**Pain level:** **Medium.** The build-logic layer is a significant
investment. Configuration cache compatibility required multiple fixes.
Dependency locking (`resolveAndLockAll --write-locks`) is fragile. The
split between Java backend and Node.js tooling (MCP server, eval scripts,
doc tools) means two build ecosystems to maintain.
**Alternative:** Kotlin (reduce boilerplate, same ecosystem), or a
different build tool (Maven — simpler but less flexible; Bazel — overkill).
Full stack migration to Kotlin/TypeScript is unrealistic given the Lucene
dependency.
**Verdict:** **Keep Java. Address Gradle pain incrementally.** The Java
choice is locked by Lucene (Java-only API). Kotlin migration would reduce
boilerplate but add a second JVM language to maintain. The 14 custom
Gradle plugins are a maintenance cost but they enforce consistency across
30+ modules. The build complexity is inherent to the project's scope.

### B6. GGUF-Only Model Format

**Chosen:** All models (embedding + chat) must be in GGUF format.
**Current state:** Embedding uses GGUF via llama.cpp FFM. Chat uses GGUF
via llama-server. SPLADE and reranker already use ONNX (not GGUF).
**Pain level:** **Medium for embeddings, low for chat.** Tempdoc 268 found
a 34-39% embedding fidelity gap that may be caused by GGUF quantization
and/or the FFM inference path. Chat models work well via llama-server with
GGUF — the llama.cpp ecosystem provides excellent GGUF support.
**Alternative:** ONNX Runtime for embeddings (fp32 fidelity, already used
for SPLADE and reranker). The codebase already has ONNX Runtime as a
dependency — adding ONNX embedding would be an extension, not a new
dependency.
**Verdict:** **Add ONNX for embeddings, keep GGUF for chat.** This is
already the direction of travel from tempdoc 268. The result would be:
GGUF for chat (llama-server), ONNX for embeddings + SPLADE + reranker
(ONNX Runtime Java). Clean split by use case.

---

## Section C: Verdict Summary

| ID | Decision | Trigger Status | Verdict |
|----|----------|---------------|---------|
| ADR-0001 | Three-process architecture | No trigger (needs one) | **Keep** |
| ADR-0002 | gRPC + MMF hybrid IPC | No trigger (needs one) | **Keep** |
| ADR-0003 | Direct Lucene | No trigger (needs one) | **Keep** |
| ADR-0004 | Single-tenant GPU | **Partially fired** (16GB GPUs exist) | **Monitor** — revisit if GPU embeddings become default |
| ADR-0005 | Manual FFM bindings | **Fired** (35 functions > 30 threshold) | **Resolved** — tempdoc 268 ONNX migration complete, FFM removed |
| ADR-0006 | Two-pronged citations | No trigger (needs one) | **Keep** |
| ADR-0007 | Entity faceting | Not fired (no users, no graph DB) | **Keep** |
| ADR-0008 | Settings ephemeral | **Partially fired** (6 critical fields) | **Resolved** — schema version added (I5) |
| RISK-002 | SQLite job queue | Not fired | **Monitor** |
| RISK-004 | Embedding model baked in | Not fired | **Monitor** |
| RISK-005 | No backup/restore | Not fired | **Monitor** |
| RISK-006 | ILM lambda coupling | **Fired** (1,053 lines > 1,000) | **Act** — decompose before next feature |
| RISK-007 | Agent infra maturity | Partially fired (M0 done) | **Monitor** |
| B1 | Javalin HTTP framework | — | **Keep** |
| B2 | Tika content extraction | — | **Keep + supplement** with Docling |
| B3 | OpenTelemetry | — | **Keep** |
| B4 | Tauri desktop shell | — | **Keep** |
| B5 | Java + Gradle + plugins | — | **Keep, address pain incrementally** |
| B6 | GGUF-only model format | — | **Resolved** — ONNX for embeddings shipped (tempdoc 268) |

### Actionable Items — Implementation

Items owned by this tempdoc (implement here):

- [x] **I1. Delete dead build tasks** — Removed `createWorkerRuntime`,
  `generateWorkerAppCDS`, `buildWorkerDist` from root `build.gradle.kts`.
- [x] **I2. Drop `--add-modules=jdk.incubator.vector` from Worker launch** —
  Removed from `WorkerSpawner.buildCommand()`, test JVM args in
  `indexer-worker/build.gradle.kts`, `adapters-lucene/build.gradle.kts`,
  `app-launcher/build.gradle.kts`. Kept in `benchmarks/build.gradle.kts`
  (benchmarks need SIMD for meaningful results). Updated canonical docs.
- [x] **I3. Clean up WorkerSpawner AppCDS scaffolding** — Removed dead
  `base.jsa` check and misleading comment.
- [x] **I4. Cap Head heap** — Added `-Xmx512m` to Head launch in
  `modules/shell/src-tauri/src/lib.rs`.
- [x] **I5. ADR-0008 / Settings schema version** — Added `schemaVersion` field
  (default 1) to `UiSettings.java`. Jackson's `FAIL_ON_UNKNOWN_PROPERTIES=false`
  means existing settings files load cleanly (absent field defaults to 0/null).
  M2 (migration chain) deferred — requires its own design work.
- [x] **I6. AOT Cache for both processes** — Full implementation:
  - Training harness classes: `AotTraining.java` in both `modules/ui` and
    `modules/indexer-worker`. Use `Class.forName(name, false, loader)` to load
    representative classes without triggering static initializers (avoids
    Tika/ONNX native library hangs). JEP 483 explicitly supports different
    main classes for training vs production.
  - Gradle tasks: `generateHeadAotCache` and `generateWorkerAotCache` in
    `modules/ui/build.gradle.kts`. Two-step workflow (`AOTMode=record` then
    `AOTMode=create`) to avoid 2x memory of one-step. Tasks are dependencies
    of `bundleSidecarResources`.
  - Bundle: `.aot` files staged at `headless/aot/{head,worker}.aot` in Tauri
    sidecar resources.
  - Runtime: `lib.rs` passes `-XX:AOTCache=` for Head (if file exists).
    `WorkerSpawner` passes `-XX:AOTCache=` for Worker (if file exists).
  - Cache sizes: Head 20.2MB, Worker 15.1MB.
  - Graceful degradation: if `.aot` file is missing or stale, JVM falls back
    to normal class loading (no crash, just slower startup).

Post-implementation: timeout tightening and readiness improvements:

- [x] **I7. Tighten startup timeouts** — With measured 4.5s baseline (Worker
  2.6s), tightened timeouts across the stack: port discovery 60→15s,
  Tauri 30→15s, dev-runner 60→30s, MCP 60→30s, bench launcher 120→30s,
  regression thresholds halved. CI timeouts (300s) unchanged.
- [x] **I8. Early crash detection in port discovery** — Added
  `Process.isAlive()` check to `MainSignalBus.awaitPort()` poll loop (new
  3-arg overload). Crashed Worker detected within 100ms instead of waiting
  full timeout. Tauri stdout reader now notifies `BackendState` on pipe
  EOF, unblocking `api_port` immediately. Guards against false positives
  on graceful shutdown via `killed` flag.

Items tracked by other tempdocs (do not implement here):

- **ADR-0005 / FFM bindings** → tempdoc 268 ONNX migration
- **B6 / GGUF-only** → tempdoc 268 ONNX for embeddings
- **B2 / Tika** → tempdoc 249 Docling as optional PDF enhancer
- **RISK-006 / ILM size** → Needs its own tempdoc before next inference feature

### ADRs Updated with "Reassess When" Triggers

Triggers added to ADRs 0001, 0002, 0003, and 0006 — see the ADR files
directly in `docs/decisions/`.

## Decisions Excluded from This Review

| Decision | Why Excluded |
|----------|-------------|
| Hand-rolled RRF fusion | Added Feb 2026, not an "early" decision |
| SPLADE via ONNX | Added Feb 2026, not an "early" decision |
| React + Vite frontend | Mainstream choice, no active concern |
| ADR-0009 (Custom DAG for CI) | Created Feb 2026, too recent |
| ADR-0010 (Local-first observability) | Created Mar 2026, too recent |
| Pipeline engine / JavaFX / SSOT DSL | Already revisited and resolved (tempdoc 182) |

## Section D: Early Design Document Analysis

Source: Pre-repo design docs from `D:\txt\` (dated Nov 2025), written before
the codebase existed. These reveal the original intent behind decisions and
show which plans were executed, modified, or abandoned.

### Documents Reviewed

| Document | Date | Purpose |
|----------|------|---------|
| `PROJECT_OVERVIEW_AND_ROADMAP.md` | 2025-11-27 | Master overview of architecture + roadmap |
| `Theoretical Best Java Native LLM Engine.md` | ~2025-11 | Maximum-performance design (aspirational) |
| `Practical Best Java Native LLM Engine.md` | 2025-11-26 | Shipped J-LLM architecture (implemented) |
| `DESIGN_WORKER_COORDINATION.md` | 2025-11-27 | Worker process coordination (MMF, gRPC) |
| `DESIGN_GLOBAL_COORDINATOR.md` | 2025-11-27 | Breath-holding / resource arbitration |
| `DESIGN_MASTER_INDEXING_SYSTEM.md` | ~2025-11 | "Theoretically best" indexing design |
| `PRAGMATIC_IMPLEMENTATION_PLAN.md` | ~2025-11 | Pragmatic indexing plan (shipped variant) |
| `ROADMAP_INDEXING_SYSTEM.md` | ~2025-11 | Phase-by-phase implementation checklist |
| `long_term_vision.md` | ~2025-11 | UI/UX vision ("Cognitive Zoning Model") |

### Key Findings

#### D1. The "Theoretical Best" vs. "Pragmatic" Fork

Two parallel design documents existed for both the LLM engine and the indexing
system. The "Theoretical Best" designs specified:

- **JVector** (DiskANN) instead of Lucene HNSW for vector search
- **ONNX Runtime** for embeddings (not llama.cpp)
- **Disruptor** ring buffer with NUMA-pinned sequencer
- **Micro-sharding** with hardware-aware writer count
- **io_uring** async I/O for vector reads
- **Memory-mapped WAL** for crash durability
- Custom `FilterCodec` with adaptive quantization (F32→Int8→F16 rescore)

The "Pragmatic" variants deliberately rejected most of these in favor of
simpler approaches. **The project shipped the pragmatic variant.** This was
the right call — the theoretical designs assumed enterprise-scale workloads
(10M+ docs, saturating NVMe bandwidth) that a desktop app will never hit.

**Relevance to 269:** The theoretical design's choice of **ONNX Runtime for
embeddings** is now being revisited (tempdoc 268), ~4 months after the
pragmatic design chose llama.cpp FFM instead. The original "theoretical best"
judgment on inference runtime may have been correct.

#### D2. The Original Embedding Design Was ONNX, Not llama.cpp

`DESIGN_MASTER_INDEXING_SYSTEM.md` §6 specified:
> Engine: ONNX Runtime (via Java API). Model: Quantized all-MiniLM-L6-v2 (int8).

`PRAGMATIC_IMPLEMENTATION_PLAN.md` §3 changed this to:
> AI: Parse -> Tokenize -> llama.cpp Embedding (JNI).
> Unified Runtime: Use `io.justsearch.aibridge.llama.LlamaCppBackend`.
> Model: `nomic-embed-text-v1.5.Q4.gguf`.

The pragmatic plan unified embeddings onto the same llama.cpp runtime as chat
inference, trading ONNX's fp32 fidelity for operational simplicity ("one
runtime"). This is the exact decision that tempdoc 268 now questions — the
34-39% fidelity gap may be a direct consequence of this trade-off.

**Conclusion:** The ONNX-for-embeddings direction in tempdoc 268 is not a
new idea — it's a return to the original theoretical design, informed by
4 months of empirical evidence that the "unified runtime" shortcut cost too
much in retrieval quality.

#### D3. The "Global Coordinator" Became the MMF Signal Bus

`DESIGN_GLOBAL_COORDINATOR.md` designed an in-process `SystemState` singleton
with `AtomicBoolean INFERENCE_ACTIVE`. This assumed a single-process model
where the indexer and LLM shared a JVM.

When the architecture split into three processes (ADR-0001), this became the
MMF signal bus (`worker_signal.lock`). The concept survived — "breath holding"
(pause indexing during inference) — but the mechanism changed from
`AtomicBoolean` to memory-mapped file offsets. The "Suicide Pact" heartbeat
and "Breath Holding" timestamp patterns are essentially unchanged from the
original design.

**Relevance to 269:** The MMF mechanism (ADR-0002) is a direct evolution of
the Global Coordinator design. The design was sound; only the IPC transport
changed. This validates the ADR-0002 "Keep" verdict.

#### D4. Abandoned Plans That Never Shipped

Several features from the early docs were never implemented:

| Feature | Source | Status |
|---------|--------|--------|
| JLink custom runtime | PRAGMATIC §6.1 | **Implemented for Head** — bundled in Tauri sidecar. Worker shares it. |
| AppCDS shared memory archive | PRAGMATIC §6.2 | **Replaced by JDK 25 AOT Cache** (I1 removed dead code, I6 wired AOT). See §D4a. |
| PragmaticCodec (FilterCodec) | PRAGMATIC §3.1, ROADMAP Phase 1 | Implemented differently — uses Lucene defaults with HNSW config. |
| ONNX dependency audit vs llama.cpp | ROADMAP Phase 0 | Never done — chose llama.cpp for both. |
| Java-side sampling (Temperature, Top-P) | Theoretical J-LLM §6.1 | Partially — Java handles templating (Jinjava), but sampling moved to llama-server. |
| Virtual Slot Manager / KV cache in Java | Practical J-LLM §5 | Implemented but later superseded — chat moved to llama-server which owns KV cache. |
| CoordinatedEngineActor decorator | ROADMAP Phase 0 | Concept evolved into InferenceLifecycleManager + ModeStateMachine. |

#### D4a. JLink + AppCDS: Investigation and Resolution (2026-03-10)

**JLink:** Already shipping. Both Head and Worker share a bundled JLink
custom JRE (`modules/ui/build.gradle.kts` `createHeadlessRuntime` task).

**AppCDS:** Was scaffolded but broken (filename mismatch, not bundled, not
wired into Head). Dead code removed in I1.

**JDK 25 AOT Cache:** Supersedes AppCDS. Project Leyden's AOT Cache (JEPs
483, 514, 515) captures loaded+linked classes and method profiles. Wired
for both processes in I6 — training harness classes, Gradle tasks, Tauri
sidecar bundling, runtime loading. Cache sizes: Head 20.2MB, Worker 15.1MB.
Graceful degradation if cache is missing.

**Key constraint resolved:** `--add-modules=jdk.incubator.vector` disables
full module graph optimization (highest-value AOT feature). Dropped the
flag (I2) — Lucene scalar fallback costs only 0.03ms/query. Re-add when
Vector API exits incubation (estimated JDK 28-29) or when Lucene 11 ships
FFM native SIMD (PR #15508, no incubator needed).

**Benchmarked SIMD impact** (768-dim float vectors):

| Metric | Panama SIMD | Scalar fallback | Delta |
|--------|-------------|-----------------|-------|
| 768-dim dot product | 0.05 us/op | 0.18 us/op | 3.6x |
| HNSW search (efSearch=200) | 0.01 ms | 0.04 ms | 0.03 ms |

The 0.03ms/query delta is negligible against total search latency (200-1000ms).

**AOT Cache benchmarks** (Project Leyden, 2025-2026 sources):
- Spring Boot real app: 4.9s → 2.4s (51% reduction)
- JRuby: 941ms → 423ms (55% reduction)
- Projected JustSearch: Worker 2.6s → ~1.2-1.6s, total ~2-3s

#### D5. The UI Vision vs. Current Reality

`long_term_vision.md` specified a "Cognitive Zoning Model" with 5 zones
(Global Command, Activity Rail, Stage, Inspector, Status Deck). The current
UI implements most of this: the Activity Rail, center stage, and inspector
panel are all present. The "Status Deck" (Zone E) with real-time GPU/RAM
monitoring exists in the Health view.

Key aspirational features NOT yet implemented:
- **Global Search Bar** (Spotlight/Raycast pattern) — identified as the
  defining UX pattern but not built. Tauri supports it (tempdoc 191 §A).
- **"Glass Engine" materiality** (blur, noise, tint) — described in
  detail but current UI uses standard CSS tokens.
- **Vim mode** — specified in the vision doc, actually implemented.
- **Density Matrix** (Compact/Comfort/Rich) — implemented.

**Relevance to 269:** The UI vision was ambitious but the execution has been
disciplined. The Global Search Bar is the biggest missing piece from the
original vision — and it's a UX feature, not an architectural decision.

#### D6. The "Java Orchestrates, Native Executes" Philosophy

The overarching philosophy from `PROJECT_OVERVIEW_AND_ROADMAP.md`:
> "Java Orchestrates, Native Executes"

This is still the operative philosophy. Java manages lifecycle, scheduling,
and business logic. Native code (llama.cpp, ONNX Runtime, Lucene MMap)
handles performance-critical operations. The philosophy is sound and has
guided good decisions (FFM over JNI, Arena-based memory, delegating inference
to llama-server rather than trying to do it in Java).

The one place this philosophy has been *violated* is Java-side sampling and
KV cache management in the early J-LLM engine — which was subsequently
corrected by moving chat to llama-server (where the native binary owns
sampling and KV cache). The philosophy self-corrected.

### Summary of Early Doc Insights

1. **ONNX for embeddings was the original plan.** The switch to llama.cpp
   was a pragmatic simplification that is now being reversed (tempdoc 268).

2. **JLink is already shipping; AppCDS is scaffolded but disconnected.**
   Experimentally validated that JDK 25 AOT Cache works with the JLink
   runtime. The `jdk.incubator.vector` blocker is resolved by dropping
   the `--add-modules` flag (Lucene scalar fallback costs 0.03ms/query).
   Both processes can now get full AOT Cache benefits. See §D4a.

3. **The three-process split evolved from a single-process coordinator
   design.** The core patterns (breath holding, suicide pact) survived
   the architectural change, validating the design.

4. **Most "theoretical best" features were correctly rejected.** JVector,
   Disruptor, io_uring, NUMA pinning — none of these are needed for a
   desktop search app. The pragmatic plan was the right call.

5. **The UI vision was surprisingly well-executed.** Most of the Cognitive
   Zoning Model shipped. The Global Search Bar is the main gap.

## Status Log

- 2026-03-09: Created. Initial 15-entry flat list (shallow investigation).
- 2026-03-09: Restructured. Proper investigation against ADRs, risk register,
  canonical docs, and source code. Reduced to 19 entries in three sections
  (13 trigger audits + 6 undocumented decisions). Three fired triggers
  identified, four missing triggers suggested.
- 2026-03-10: Added Section D — analysis of 9 pre-repo design documents from
  Nov 2025. Key finding: ONNX for embeddings was the original plan; the
  switch to llama.cpp was a pragmatic shortcut now being reversed.
- 2026-03-10: Deep investigation into JLink + AppCDS (§D4a). Found JLink is
  already shipping (both processes share the Head's bundled runtime). AppCDS
  is scaffolded but broken (filename mismatch, not bundled, not wired into
  Head). Recommended replacing with JDK 25 AOT Cache. Found dead build tasks
  and unbounded Head heap.
- 2026-03-10: Experimental validation of JDK 25 AOT Cache. Ran 8 experiments
  against the actual JLink runtime. Found `jdk.incubator.vector` disables
  full module graph on Worker. Initially recommended deferring Worker AOT.
- 2026-03-10: Resolved incubator.vector blocker. Benchmarked Lucene SIMD vs
  scalar fallback: 0.05 vs 0.18 us/op on 768-dim dot product (3.6x), but
  only 0.03ms/query in real workload (negligible). Dropping the
  `--add-modules` flag gives both processes full module graph + AOT-linked
  classes. Confirmed: cache generated without incubator loads with
  `full module graph: enabled` and `Using AOT-linked classes: true`.
  Revised recommendation: AOT Cache for both processes, not just Head.
- 2026-03-10: Added landscape of 8 approaches to incubator.vector conflict
  (4 dead ends confirmed experimentally, 2 future paths identified). Added
  detailed explanation of what full module graph + AOT-linked classes + AOT
  method profiling concretely provide. Lucene 11 FFM native SIMD (PR #15508)
  identified as future "best of both worlds" path.
- 2026-03-10: Implemented I1-I4. Deleted 104 lines of dead build code
  (createWorkerRuntime, generateWorkerAppCDS, buildWorkerDist). Dropped
  `--add-modules=jdk.incubator.vector` from Worker launch path, test JVM
  args (4 modules), updated canonical docs. Cleaned up dead AppCDS
  scaffolding in WorkerSpawner. Capped Head heap at 512MB. Kept incubator
  flag in benchmarks module (needs SIMD for meaningful results). All
  affected module tests pass.
- 2026-03-11: Implemented I5 (settings schemaVersion field) and I6 (AOT Cache
  for both processes). Created AotTraining harness classes for Head and Worker
  that load representative classes without static initialization (avoids
  Tika/ONNX native hangs). Gradle tasks generate 20.2MB Head cache and 15.1MB
  Worker cache via two-step workflow (record + create). Caches bundled into
  Tauri sidecar at `aot/` and loaded at runtime by `lib.rs` and
  `WorkerSpawner`. All affected module tests pass. All implementation items
  (I1-I6) complete.
- 2026-03-11: Added "Reassess When" trigger sections to ADRs 0001, 0002, 0003,
  and 0006 — the four ADRs identified in §A as lacking reassess triggers.
  Trigger text matches suggestions from the tempdoc investigation.
- 2026-03-11: Post-implementation verification. Measured startup via dev stack
  (MCP start, `--clean hard`, fresh index). Head startup phases (ms):
  settings=151, telemetry=98, policy=33, worker=3580, facade=224, api=367,
  total=4456. Worker timeline: main()→gRPC ready in 2,639ms (config 712ms,
  embedding fingerprint 296ms, SPLADE init 1384ms, gRPC bind 238ms). Confirmed
  `--add-modules=jdk.incubator.vector` absent from Worker spawn command.
  Confirmed `schema_version: 1` present in `/api/status`. Head memory: 41 MB.
  Updated `docs/future-features/rust-worker-rewrite.md` stale timing values
  (3.4s → 2.6s post-ONNX migration + lazy-init).
- 2026-03-11: Tightened startup timeouts based on measured 4.5s baseline (I7).
  Port discovery 60s→15s (KnowledgeServerConfig), Tauri api_port 30s→15s,
  dev-runner 60s→30s, MCP wait 60s→30s, backend-launcher 120s→30s,
  regression thresholds max_delta_ms 15s→8s. CI timeouts kept at 300s.
  Researched readiness detection best practices (2024-2026 sources). Key
  finding: `MainSignalBus.awaitPort()` has no process liveness check — a
  crashed Worker still waits the full timeout. JDK 9+ `Process.onExit()`
  can race against the poll loop for instant crash detection (I8, not yet
  implemented). Also found Project Leyden AOT Cache benchmarks showing
  40-55% startup reduction in real apps — projecting JustSearch production
  startup at ~2-3s once AOT caches are active.
- 2026-03-11: Deep investigation into I8 implementation. Traced full code path:
  WorkerSpawner.spawnWorker() → workerProcess.set(process) → awaitPort() poll
  loop. Four awaitPort call sites identified (3 in WorkerSpawner, 1 in
  KnowledgeServerBootstrap). Chosen approach: add `Process` overload to
  awaitPort, check `!process.isAlive()` each 100ms iteration — simpler than
  CompletableFuture.anyOf(), no async machinery. KnowledgeServerBootstrap call
  site uses null-Process overload (degrades to current behavior). Also
  investigated Tauri side: stdout reader thread silently exits on pipe EOF with
  no notification to BackendState — needs set_spawn_error + notify_waiters
  after the for-loop. Confidence: Java 90% (verifiable), Rust 65% (no cargo
  check in workflow, need to read full BackendState impl first).
- 2026-03-11: Implemented I8 (early crash detection in port discovery). Java:
  added `awaitPort(long, long, Process)` overload to `MainSignalBus` — checks
  `!process.isAlive()` each 100ms poll iteration, fails immediately with exit
  code in the error message. Updated 3 WorkerSpawner call sites to pass
  `workerProcess.get()`. KnowledgeServerBootstrap uses 2-arg overload (no
  Process access). Tauri: added stdout-EOF notification in the reader thread —
  when pipe closes before port is set and `killed` is false, sets spawn_error
  and notifies both `port_ready` and `session_token_ready`. `api_port` command
  already checks `has_spawn_error()` and returns `None` immediately. Java side
  verified: `spotlessApply` + `assemble` + `:modules:app-services:test` pass.
  Tauri side cannot be cargo-checked in this workflow. All I1-I8 complete.
