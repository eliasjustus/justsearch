---
title: Inference Runtime Register
type: reference
status: stable
created: 2026-03-19
updated: 2026-04-24
description: "Shared decision register for GPU, ORT, VRAM, and inference runtime. Read before starting inference work. Update before finishing."
---

# Inference Runtime Register

Coordination register for inference runtime work (GPU detection, ORT
sessions, VRAM management, model loading, CPU/GPU routing). Every
inference-related tempdoc agent must read this before starting and
update it before closing.

**Rules:**
- Do not re-run an experiment listed under Baselines or Findings without
  justification (e.g., driver update, ORT version change, new hardware).
- When your work settles a question from Open Questions, move it to
  Findings with your tempdoc citation.
- When your work opens a new question, add it to Open Questions.
- Keep entries terse. Evidence lives in tempdocs; this file is the index.
- After ORT/session concurrency changes, dispatch the manual CI stress
  lane with `gh workflow run ci.yml -f runStress=true`. ADR-0026 removed
  the scheduled stress cadence, so runtime agents touching
  `NativeSessionHandle` or `SessionHandle` are one trigger point for the
  repo-wide policy-covered stress lane.

**Replaces:** Relevant items from `docs/reference/issues/gpu-detection.md`
(GPU-) and inference-related items from `retrieval-quality.md` (RAG-001,
RAG-009). Remaining open items from those files should be triaged into
this register's sections or retired to `decisions.md`.

---

## Canonical Baselines

Frozen reference measurements. Do not re-measure unless the runtime
environment changes (ORT version, driver, hardware).

| Metric | Model | Value | Conditions | Measured in | Valid since |
|--------|-------|-------|------------|-------------|-------------|
| Encode throughput | BGE-M3 FP16 GPU | 100.2 docs/sec (6.4ms/doc) | RTX 4070 12GB, batch=50, SciFact | 322 | dc4f79a |
| Encode throughput | SPLADE-v3 O3+FP16 GPU | 40.1 docs/sec (28ms/doc) | RTX 4070 12GB | 322 | dc4f79a |
| Encode throughput | EmbeddingGemma INT8 GPU | 10.2 docs/sec (98ms/doc) | RTX 4070 12GB, batch=8, 2048MB arena | 312 | 078aee2 |
| Encode throughput | EmbeddingGemma INT8 CPU | 6.7 docs/sec (150ms/doc) | 20 logical cores, batch=8 | 312 | 078aee2 |
| Encode throughput | EmbeddingGemma Q4 GPU | 9.4 docs/sec (106ms/doc) | RTX 4070 12GB, batch=8, 2048MB arena | 312 | 078aee2 |
| Encode throughput | nomic-embed GPU | 10.3 docs/sec (97ms/doc) | RTX 4070 12GB | 322 | dc4f79a |
| CE rerank top-20 | GTE-ModernBERT GPU | ~40-80ms | RTX 4070, ONNX, 8192 context | 309 §41 | dc4f79a |
| CE rerank top-20 | MiniLM-L6-v2 CPU | ~40-80ms | CPU INT8 | 309 §15 | — |
| VRAM peak | BGE-M3 FP16+Flash | ~2.6 GB | 8192-token input, batch=50 | 322 | dc4f79a |
| VRAM steady | GTE-ModernBERT INT8 | ~150 MB | ONNX, arena shrinkage enabled | 309 §41 | dc4f79a |
| GPU cold start | BGE-M3 first batch | ~1441ms/doc | Session init overhead, then steady | 322 | dc4f79a |

---

## Findings

Settled empirical facts. Each was an open question that got answered.

### F-001: ONNX GPU lazy session init causes first-query timeouts

- **Answer:** First query after backend start exceeds the 5s gRPC deadline because the ONNX GPU session initializes lazily on first use.
- **Evidence:** tempdoc 309 §35, §41. Observed across BGE-M3, SPLADE, GTE-ModernBERT.
- **Conditions/caveats:** Only affects first query after cold start. Subsequent queries fast. Workaround: warmup query at startup.

### F-002: CUDA DLL path must be explicitly configured for runHeadlessEval

- **Answer:** `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` must point to a directory containing `onnxruntime_providers_cuda.dll`, `cublasLt64_12.dll`, etc. Without it, all ONNX GPU sessions fall back to CPU silently.
- **Evidence:** tempdoc 309 §33. Path used: `tmp/ort-variant-test/cuda-12.4/`.
- **Conditions/caveats:** Only affects `runHeadlessEval`. Production app-launcher bundles CUDA DLLs in `native-bin/`.

### F-003: GPU transition propagation was broken (fixed)

- **Answer:** `IndexingLoop.reloadEmbeddingService()` created a new EmbeddingService during GPU transitions but only wired it to the loop, not SearchOrchestrator. Fixed: added `Consumer<EmbeddingService>` listener + corrected `lastMainGpuActiveState` startup assumption (true→false).
- **Evidence:** tempdoc 309 §33. Fix merged in `eccf9e0d5`.
- **Conditions/caveats:** Fix is correct but was not the cause of the Phase 1a dense failure (that was an eval workflow issue — index built without embeddings).

### F-004: bge-reranker-v2-m3 has ONNX GPU regression (5.7x slower)

- **Answer:** ONNX Runtime CUDA provider is 5.7x slower than PyTorch for XLM-RoBERTa-based cross-encoder models. GPU acceleration is counterproductive.
- **Evidence:** tempdoc 309 §39, FlagEmbedding issue #987.
- **Conditions/caveats:** May be fixed in future ORT releases. Model forced to CPU-only.

### F-005: batch=8 is optimal for 300M-param embedding models on RTX 4070

- **Answer:** `MAX_ORT_BATCH_SIZE=8` is optimal. batch=16 needs ~940MB (borderline OOM on 2048MB arena). batch=32 needs ~1.9GB (always OOMs). External benchmarks confirm gte-large (335M, comparable) bottoms out at batch=3-5 on GPU.
- **Evidence:** tempdoc 312 items 29-30. BFCArena math: EmbeddingGemma MultiHeadAttention at batch=8 = ~470MB (fits in 853MB available from 2048MB arena).
- **Conditions/caveats:** Specific to RTX 4070 with 2048MB embedding arena. Larger arenas or GPUs with more VRAM could support larger batches.

### F-006: CPU intraOpNumThreads tuning has no effect on 300M-param models

- **Answer:** Tested default (20 cores), 10 (physical), 4. Results: 158ms, 161ms, 161ms per doc — within noise. ORT saturates available threads regardless.
- **Evidence:** tempdoc 312 item 33.

### F-007: ORT sequence length cap does not affect GPU memory allocation

- **Answer:** `maxSeqLen` only affects tokenizer truncation, not GPU memory. ORT allocates dynamically based on actual input tensor dimensions. Batch padding pads to max-in-batch, not to `maxSeqLen`.
- **Evidence:** tempdoc 312 item 34. Tested 2048, 512, 128 — no effect.

### F-008: NER per-call overhead dominates encoder efficiency

- **Answer:** RTX 4070 FP16 roofline is 29.15 TF. Embed/SPLADE achieve 42–53% GPU efficiency. NER achieves only 18% — a ~5.6ms fixed overhead per `session.run()` call at batch=1 dominates. 82–92% of each encoder call is spent in `session.run()`.
- **Evidence:** tempdoc 356 roofline analysis (RTX 4070, 49s theoretical, 81–111s realistic).

### F-009: NaN-on-CPU-OOM behavior in ORT sessions

- **Answer:** When ORT CPU session exhausts memory, some models return NaN outputs silently rather than throwing an exception. `SessionHandle.reportCpuSessionFailure()` (impl: `NativeSessionHandle`, formerly `OrtSessionManager`) handles this case and BFC arena failures are detected via `NativeSessionHandle.isBfcArenaFailure()`.
- **Evidence:** tempdoc 359 D9. Fixed in shared handle infrastructure (renamed in tempdoc 397 §14.23).

### F-010: Cross-encoder latency baselines (GPU vs CPU)

- **GPU:** ~2.2s for top-20 documents at seq=512, 2048MB arena, RTX 4070. Default: `gpu=true, mem=2048MB, seq=512`.
- **CPU:** ~42s for top-20 documents at seq=2048 on RTX 4070 host CPU.
- **VRAM budget (all ORT consumers):** embed ~2GB + SPLADE ~1GB + NER ~0.5GB + reranker ~2GB = ~5.5GB total (leaves ~6.5GB for LLM on 12GB GPU).
- **Evidence:** tempdoc 360 (Worker migration), tempdoc 361 I9.

### F-011: JAR-bundled CUDA defeats native-path-based GPU-failure-reproduction

- **Answer:** Setting `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` to an empty (or DLL-missing) directory triggers the documented `"ORT CUDA DLLs not found … will try CUDA provider anyway (JAR-bundled)"` log line in `NativeSessionHandle.tryCreateGpuSession`, but `OnnxSessionCache.createCachedGpuSession` then extracts CUDA from JAR-bundled resources and GPU init succeeds anyway. The native-path env var is therefore NOT a viable reproducer for `gpu_init_failure_total{cause=cuda_unavailable}` or any other live GPU-init-failure path.
- **Evidence:** tempdoc 414 V4 validation attempt, 2026-04-26. Worker logs confirmed the warning fired but `tryCreateGpuSession` succeeded.
- **Conditions/caveats:** Live failure reproduction requires either (a) running on a non-CUDA machine, (b) deliberate JAR modification (delete the bundled CUDA resources), or (c) a test-only `JUSTSEARCH_FORCE_GPU_INIT_FAILURE` flag injected into `tryCreateGpuSession` to throw a synthetic `OrtException`. Future agents authoring tempdocs that propose env-var-based GPU-failure reproducers should reference this finding before promising the gate works.

### F-012: LLM-generation latency/throughput is gate-able as a relative ratchet — tokens/sec needs no backend change

- **Answer:** LLM generation latency + throughput regress invisibly the same way retrieval quality did. Tempdoc 640 L added a `bench`-sourced `llm-gen` ratchet: `jseval llm-bench` (warmup-discard + multi-sample) → `jseval llm-gate` gates **TTFT**, **e2e summarize**, and **tokens/sec** medians against RELATIVE ratio bands (no absolute SLO), a sibling of the perf / relevance ratchets sharing `jseval/ratchet_kernel.py`.
- **Don't re-derive (640 D):** tokens/sec needs **no** conversation-subsystem change — the chat `done` event already emits `promptTokens` + `totalTokens` flat (`ConversationEngine:357-361`), so `llm_bench` derives `completion = total − prompt`. (The confidence pass disproved the "needs a backend SSE `usage` emit" assumption.)
- **Baseline (RTX 4070, Qwen3VL-8B-Thinking Q4, summarization):** TTFT ~103 ms, e2e ~6.3 s, ~25.5 tokens/sec. Floor: `scripts/jseval/llm-gen-ratchet-baselines.v1.json` (projected from a green bench via `--update-baseline`, never hand-typed; per-machine + per-configured-LLM).
- **Evidence:** tempdoc 640 L + D (2026-06-24); live-confirmed end-to-end on a real summarization (25.5 t/s).
- **Conditions/caveats:** Advisory tier (nudged by `search-engine-hint` on inference-path edits, not a CI-blocking gate). The committed baseline pins TTFT + e2e; tokens/sec pins on the next `--update-baseline` (needs a bench run where doc-discovery serves the eval index).

---

## Decisions

Design choices in the current inference runtime, with rationale.

### D-001: GTE-ModernBERT as default CE model — SHIPPED

- **Choice:** Replace MiniLM-L6-v2 (22.7M, 512 tokens) with GTE-ModernBERT-base (149M, 8192 tokens) at `models/onnx/reranker/`. Default `maxSequenceLength` changed to 512 (GPU-viable; model supports 8192 but attention is O(n²)).
- **Rationale:** Auto-detects `needsTokenTypeIds` from ONNX input names. GPU default since tempdoc 360: `gpu=true, mem=2048MB, seq=512` — 2.2s for topK=20 on GPU (vs 42s CPU at seq=2048). Batch padding requires `attentionMask[0]=1` in padding rows (ModernBERT global attention NaN fix, 360).
- **Evidence:** tempdoc 309 §41 (model selection), tempdoc 360 (Worker migration, GPU defaults, NaN fix)
- **Revisit when:** settled.

### D-003: gte-multilingual-base as default embedding model — SHIPPED (supersedes EmbeddingGemma-300M)

- **Choice:** Replace EmbeddingGemma-300M with `Alibaba-NLP/gte-multilingual-base` as the production ONNX embedding model. `EmbeddingOnnxModelDiscovery` hardcodes `MODEL_NAME = "gte-multilingual-base"`, falls back to `embeddinggemma-300m/` then `embedding/` (nomic).
- **Rationale:** Equivalent quality (nDCG@10 0.7132 vs 0.7128 on SciFact). 39s faster pipeline (181s vs 220s). 70+ languages (vs English-only). Apache 2.0 license. Lazy CPU session design avoids 20+ GB RAM spike on GPU failure.
- **Evidence:** tempdoc 358 (exhaustive model search, only 2 models pass all hard requirements H1–H9); tempdoc 312 items 23-24 (original EmbeddingGemma selection, now superseded)
- **Previous default:** EmbeddingGemma-300M (Q4 GPU / INT8 CPU, tempdoc 312) — retained as legacy backup at `models/onnx/embeddinggemma-300m/`
- **Revisit when:** settled.

### D-004: Centralized ORT GPU session creation (historical) — SUPERSEDED by D-007

- **Choice:** Extracted identical GPU session creation code from all five ORT consumers (`SpladeEncoder`, `OnnxEmbeddingEncoder`, `BgeM3Encoder`, `BertNerInference`, `CrossEncoderReranker`) into a shared `OrtSessionFactory` in the `ort-common` module (tempdoc 349). Superseded by `OrtSessionManager` (tempdoc 359, renamed `NativeSessionHandle` in tempdoc 397 §14.23); all five consumers co-located in Worker (tempdoc 360); factory deleted (tempdoc 397 §14.22 Phase A).
- **Rationale:** All encoders used identical session options (`kSameAsRequested` arena, no CUDA graph, device allocator for initializers, no memory pattern optimization). The factory encoded these once.
- **Evidence:** tempdoc 349 (factory extraction), tempdoc 352 (module split), tempdoc 359 (`OrtSessionManager`), tempdoc 360 (Worker co-location), tempdoc 397 (factory absorbed into assembler + handle; closure property §6).
- **Current shape:** See D-007 below. The identical-session-options claim is now enforced by `SessionOptionsApplier` walking `RuntimePolicy` records (tempdoc 397 §14.24 FA) rather than by a shared factory class.
- **Verification:** `./gradlew.bat :modules:worker-core:verifyModel -Pmodel=<path>` task — routes through `OrtSessionAssembler.verifyModelSession`, which shares the applier apply path with production (§14.24 FA).

### D-005: Model file manifest convention (`model_manifest.json`) — SHIPPED

- **Choice:** Each model directory declares CPU/GPU model file selection via `model_manifest.json` (fields: `cpu`, `gpu`, `tokenizer`, `pooling_config`, `label_config`). Encoders use `ModelManifest.loadOrDefault()` — falls back to convention (`model.onnx` CPU, `model_fp16.onnx` GPU) for external directories without a manifest.
- **Rationale:** Eliminates implicit file naming conventions that caused the Q4 CPU regression (tempdoc 334). Swapping a model file requires updating one JSON field; encoders pick it up automatically.
- **Evidence:** tempdoc 340
- **Key class:** `ModelManifest` in `modules/worker-core/.../ort/ModelManifest.java`
- **Revisit when:** settled.

### D-006: Model build provenance (`build.json`) — SHIPPED

- **Choice:** Each model directory contains a `build.json` recording source HF model ID + commit hash, transformations applied, output SHA-256, tool versions, and exact build command. Build scripts in `scripts/models/` auto-capture provenance.
- **Rationale:** Model files were opaque blobs with no recorded origin. Updating or debugging a model required reverse-engineering from commit messages and memory.
- **Evidence:** tempdoc 348
- **Integrity check:** `python scripts/models/check-integrity.py`
- **Revisit when:** settled.

### D-007: Single-entry session construction via `OrtSessionAssembler` — SHIPPED (tempdoc 397)

- **Explainer:** [docs/explanation/24-worker-inference-composition.md](../explanation/24-worker-inference-composition.md) — conceptual walkthrough of the pipeline (resolvers → composition root → assembler → handle → surface), policy record shape, and diagnostic endpoint.
- **Choice:** All ORT session construction flows through `OrtSessionAssembler` in `modules/ort-common`. **Three external entry points** (post-§14.28 U1): `buildManager(Composition, GpuArbiter) → SessionHandle` for variant-driven composition-root calls; `verifyModelSession(env, modelPath, GpuSessionConfig) → OrtSession` for the `verifyModel` Gradle task; `probeModelNames(env, modelPath) → ProbedNames` for the short-lived probe session per-encoder `buildAssembly` factories use. Setter-to-policy mapping centralises on package-private `SessionOptionsApplier`, which walks `RuntimePolicy` + `ModelSessionPolicy` fields one-for-one.
- **Rationale:** 394 item 4 revealed two call paths producing non-equivalent sessions under equal inputs. 397 made that class of bug type-unrepresentable via the §6 closure property: every ORT setter value reads a typed policy-record field; there is exactly one apply site (`SessionOptionsApplier`). Policy flows through typed records (`RuntimePolicy`, `ModelSessionPolicy`, `Composition`) resolved by pure functions (`RuntimePolicyResolver`, `ModelSessionPolicyResolver`).
- **What enforces the boundary:** Java visibility + single-apply-site invariant + Gradle source-set scoping + ArchUnit rule.
  - `NativeSessionHandle.Builder` is package-private (§14.19 Phase 4; class renamed from `OrtSessionManager` in §14.23 Phase B). `NativeSessionHandle.builder(...)` factory method is package-private. Flat policy-substitute setters (`.gpuConfig`, `.deferCpuSession`, `.cpuOptLevel`, `.gpuRetryEnabled`, `.gpuRetryIntervalMs`) deleted in §14.26 T1-B; Builder accepts `.runtime(RuntimePolicy)` + `.policy(ModelSessionPolicy)` for policy inputs only. `ModelSessionPolicy.forFallback(...)` factory composes scalar inputs into a policy record at the assembler boundary (mirrors `forVerification` for the verifier).
  - `OrtSessionAssembler.buildManager` returns `SessionHandle`, not `NativeSessionHandle` (§14.21 R1).
  - `NativeSessionHandle.selectSession` is `private`; `runOptionsFor(OrtSession)` deleted; `peekCpuSession` is package-private (§14.21 R2). `inputNames()` + `outputNames()` removed from `SessionHandle` interface (§14.25 FD-ProbeDeletion).
  - **Closure property** (§14.25 FA): `NativeSessionHandle.createGpuSession` + `OrtSessionAssembler.verifyModelSession` both delegate to `SessionOptionsApplier.{applyBase, applyGpuSessionOptions, applyCudaProviderOptions, buildGpuRunOptions}`. Zero hardcoded option values remain outside the applier. §14.28 U2 further collapses the handle's `gpuEnabled` derivation to one branch: `ModelSessionPolicyResolver` zeroes `arenaCapBytes` for non-CUDA variants so `arenaCapBytes > 0` ⇔ GPU session (policy record is self-describing).
  - **Three fallback methods deleted** (§14.28 U1): `buildFallback`, `composeRerankFallback`, `composeCitationFallback` are gone. Test harnesses route through `InferenceCompositionRootTestHelper.sessionFor` in `modules/ort-common`'s testFixtures source set — Gradle scope makes the helper unreachable from production classpaths.
  - **ArchUnit enforcement** (§14.28 U8): `ClosurePropertyTest` is a denylist over owner packages (`java.nio.file`, `java.io`, `java.nio.channels`) + specific classes (`ModelManifest`, `ObjectMapper`, `JsonParser`, `HuggingFaceTokenizer`, `DefaultVocabulary`, `Model`, `ModelZoo`). Encoder primary constructors (FQN-based allowlist: `BertNerInference`, `CrossEncoderReranker`, `CitationScorer`, `OnnxEmbeddingEncoder`, `SpladeEncoder`, `BgeM3Encoder`) cannot call any method on those owners. Negative test verified.
- **Encoder shape (§14.25 FD):** every ORT-backed encoder constructor accepts `(SessionHandle, <Role>Shape, <Role>Tokenizer, ...role-specific)`. Encoders perform zero filesystem I/O. Shape records: `NerShape`, `RerankerShape` (shared by reranker + citation), `EmbeddingShape`, `SpladeShape`, `BgeM3Shape`. Assembly records: `NerAssembly`, `RerankerAssembly`, `EmbeddingAssembly`, `SpladeAssembly`, `BgeM3Assembly`. Each composed via `InferenceCompositionRoot.compose<Role>Assembly(...)` (variant-driven) or each encoder's static `buildAssembly(sessions, ...)` (fallback).
- **Composition root** (§14.27 T2-C1/C2): `InferenceCompositionRoot.compose(ResolvedConfig, HardwareProfile, InstallContract, Path modelsDir, GpuArbiter) → InferenceSurface` is §7.6's single-entry composition. `InferenceSurface` is a record bundling `Optional<EmbeddingAssembly> embedding`, `Optional<NerAssembly> ner`, `Optional<RerankerAssembly> reranker`, `Optional<RerankerAssembly> citation`, `Optional<SpladeAssembly> splade`, `Optional<BgeM3Assembly> bgeM3`, `PolicySnapshot policies`, `List<SessionHandle> handles`. Per-encoder failures are caught inside `compose()` and surface as `Optional.empty()` — graceful degradation preserved. `KnowledgeServer.initDeferredModels()` calls compose() once and destructures.
- **Dev-mode variant resolution** (§14.27 T2-A1): `DevModeVariantProbe.probe(Path modelDir, boolean gpuEnabled) → VariantSelection` centralises filesystem-probe variant discovery for dev mode (no `InstallContract`). Every `VariantSelection` in the JVM comes from one of two sibling paths: `VariantSelector.select` (contract-driven) or `DevModeVariantProbe.probe` (filesystem-driven). Composition root + assembler never know the difference.
- **Ops-layer eager-wire** (§14.27 T2-E1): `RagContextOps.getChunkReranker`, `CitationMatchOps.getCitationScorer`, and `NerService` are pure getters over encoders the composition root wired. No lazy `construct-on-first-use-if-not-wired` paths. `WorkerAppServices.wireCitationScorer(CitationScorer)` carries the eagerly-built encoder. `NerService.buildFallback` deleted.
- **Query-handler gate** (§14.28 U3): `GrpcSearchService` awaits a `modelReadyLatch` (120 s timeout) at entry of `search` / `retrieveContext` / `rerank` / `matchCitations`. Closes a boot-race regression where queries arriving before `initDeferredModels` completion silently missed reranker + citation wiring. Latch supplier wired via `WorkerAppServices.wireModelReadyLatch`.
- **Diagnostic endpoint** (§14.25 FB + §14.28 U4): `JUSTSEARCH_ORT_PROFILING_DIR` + `JUSTSEARCH_ORT_VERBOSE` are typed via `RuntimePolicy.Profiling` → `ResolvedConfig.Ai.Profiling` → `EnvRegistry.ORT_PROFILING_DIR` / `ORT_VERBOSE_LOGGING`. `SessionOptionsApplier` reads `runtime.profiling()`; zero `System.getenv` calls remain in the apply path. `/api/debug/session-policies` reads Worker's authoritative `PolicySnapshot` via the `IngestService.GetSessionPolicies` gRPC rpc (§14.28 U4 — JSON payloads decouple `.proto` wire format from `RuntimePolicy` schema evolution). Head's `SessionPoliciesController` is a thin adapter over `RemoteKnowledgeClient.getSessionPolicies`; pre-§14.28 Head-side re-resolve path is deleted. Response shape: `{configStatus: "ok" | "surface-unavailable" | "worker-unreachable", runtime, models}`.
- **Evidence:** tempdoc 397 (closed 2026-04-21 through §14.28). §14.20 initial closure + §14.21 R1–R5 + §14.22 Phase A + §14.23 Phase B + §14.24 audit + §14.25 FA/FE/FB/FC/FD (11 commits) + §14.26 residuals audit + §14.27 T1/T2 remediation (8 commits) + §14.28 critical-review remediation (9 commits). Total: 30+ commits across 397's landed arc.
- **Key classes (internal, opaque to external callers):** `NativeSessionHandle`, `SessionOptionsApplier`, `OnnxSessionCache`, `DevModeVariantProbe`.
- **Key classes (external):** `SessionHandle` (interface, zero I/O methods), `OrtSessionAssembler` (three entry points: `buildManager`, `verifyModelSession`, `probeModelNames`), `Composition`, `ModelSessionPolicy` (+ `Gpu` / `Cpu` / `Lifecycle` / `RunOptions` subrecords + `forFallback` + `forVerification` factories), `RuntimePolicy` (+ `Arena` / `CudaProvider` / `Session` / `Profiling` subrecords + `defaults()` factory), `InferenceCompositionRoot.compose` + `compose<Role>Assembly`, `InferenceSurface`, role-specific shape + assembly records.
- **Test harness:** `InferenceCompositionRootTestHelper.sessionFor(consumerName, modelDir, gpu, gpuMemMb) → SessionHandle` in `modules/ort-common`'s testFixtures source set. Single authorised test-only surface for integration tests + benchmarks to construct a `SessionHandle` without a full `ResolvedConfig`. `@VisibleForTesting` semantic is enforced structurally by Gradle source-set scoping (testFixtures is not on production runtime classpaths).
- **Verification:** `NativeSessionHandleConcurrentStressTest` for concurrency baseline (10 threads covering #3 CPU recreation + #5 lifecycle-callback + post-close acquire; invariants #1/#2/#4 require CUDA, parked as tempdoc 398; metadata-read thread retired in §14.25 FD-ProbeDeletion); `OrtSessionOptionsTest` for applier parity + causality invariants; `RuntimePolicyResolverTest` for profiling round-trip + CPU-variant zero-arena invariant (§14.28 U2); `ClosurePropertyTest` for §7.5 pure-encoder contract (denylist-by-default, §14.28 U8); `InferenceSurfaceTest` + `InferenceCompositionRootComposeTest` for compose orchestration shape (§14.28 U6/U7); `GrpcSearchServiceModelReadyLatchTest` for the query-handler gate (§14.28 U3); `SessionPoliciesControllerTest` for the gRPC-bridged diagnostic (§14.28 U4); jseval pipeline anchor (§14.7.3): 191.1 s baseline. Post-§14.28 reference run: 208 s total / 24.9 docs/sec / nDCG@10 = 0.750 on 300 scifact queries (commit `0ed0321ce`, 2026-04-21).
- **Revisit when:** 395 A1/A4/A7 adaptive policy work starts (resolver now has a real read-path; §14.28 U2 further made the record self-describing); 394 P3 scheduler lands new `RunOptions` fields (`SessionOptionsApplier.buildGpuRunOptions` is the single setter site); tempdoc 400 observability work identifies a structural gap that motivates additional runtime assertions on the closure property.

### D-002: BGE-M3 VRAM budget — FP16+Flash at 3072 MB arena

- **Choice:** FP16+Flash Attention with 3072 MB arena limit (`JUSTSEARCH_BGE_M3_GPU_MEM_MB=3072`).
- **Rationale:** 8192-token input at FP16 needs ~2.6 GB. 3072 MB provides headroom. Coexists with GTE-ModernBERT (~150 MB) and 7B LLM (~4.5 GB Q4_K_M) on 12 GB GPU.
- **Evidence:** tempdoc 322
- **Revisit when:** model changes or VRAM budget analysis for different GPU tiers.

---

## Open Questions

Unanswered questions that need investigation. Agents should prefer
picking up items here over inventing new experiments.

### Q-001: ~~Should GPU sessions warm up at startup?~~ — ANSWERED

- **Answer:** Yes. Warm-up inference added to `initDeferredModels()` in tempdoc 360 (Worker migration). All ORT GPU sessions now run a dummy inference at startup to prime CUDA kernels and BFC arena. First-query DEADLINE_EXCEEDED no longer occurs.
- **Evidence:** tempdoc 360 (warm-up implementation); tempdoc 356 (identified the fix).

---

## Future Work

Identified improvements not yet started. Lower priority than Open
Questions — these are "we should eventually" not "we need to know."

- **FW-001: Arena shrinkage tuning** — Current arena limits (BGE-M3 3072MB, SPLADE 2048MB) are conservative. Actual peak may be lower with arena shrinkage enabled. Profile and right-size. Source: tempdoc 311.
- **FW-002: CPU fallback latency budget** — GTE-ModernBERT at 149M params on CPU: ~160-300ms for top-20. Borderline for interactive search. Measure and decide if CPU CE should be disabled under latency pressure. Source: tempdoc 309 §15.
- **FW-003: ORT CUDA runtime pack** — GPU-accelerated embedding via ORT+cuDNN. Self-check and status wiring in place but ~2.2 GiB pack not assembled. Blocked on cuDNN redistribution licensing. Source: RAG-001 (retired from issues/).
- **FW-004: Speculative decoding** — Eagle-3 could improve generation throughput but needs 400MB-1.3GB VRAM (conflicts with 8GB budget) and isn't integrated into llama-server API yet. Deferred until Eagle-3 llama-server integration + user base with >12GB VRAM. Source: RAG-009 (retired from issues/).
