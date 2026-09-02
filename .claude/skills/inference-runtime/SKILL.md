---
description: "TRIGGER only for deep inference-runtime work: GPU detection, ORT sessions, VRAM limits, Worker encoder model loading, BFCArena config, or NER/SPLADE/reranker/citation inference code. Do not load for ordinary AI module ownership questions; use /module-arch or canonical architecture docs instead."
user-invocable: true
---

# Inference Runtime Context

Read this before starting any inference runtime work. Do not re-run
experiments already recorded in the Baselines or Findings sections.

This is intentionally a heavy skill. Use it when runtime baselines or settled
experiments matter; avoid loading it for general agent, prompt, or module
ownership questions.

<!-- generated:start — do not edit between markers; run: node scripts/docs/skills-sync.mjs -->

<!-- source: docs/reference/inference-runtime-register.md -->

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
- After ORT/session concurrency changes, run the stress-tagged Gradle tests
  explicitly with `./gradlew.bat test -PincludeStress=true --tests "*Stress*"`.
  There is no scheduled stress cadence, so runtime agents touching
  `NativeSessionHandle` or `SessionHandle` are one trigger point for this
  opt-in verification.

**Replaces:** the GPU- and inference-related items of the former
`docs/reference/issues/` registers (`gpu-detection.md`, `retrieval-quality.md`
RAG-001/RAG-009). That whole register set was retired in tempdoc 821 §7 D5
(2026-08-12); its still-live entries were routed into the observations store
(retired, tempdoc 872 — see git history of `docs/observations.md`), and
anything belonging to this domain should be promoted from there into the
sections below rather than re-created as a standalone issue file.

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
- **VRAM budget (all ORT consumers):** embed ~2GB + SPLADE ~1GB + NER ~0.5GB + reranker ~2GB = ~5.5GB total (leaves ~6.5GB for LLM on 12GB GPU). *Updated by tempdoc 691:* NER's arena cap is now 2GB (see F-013) — caps are per-session budgets, not pre-allocations, and enrichment backfill yields the GPU when Main claims it, so LLM coexistence is unaffected; measured total VRAM peak during full-corpus enrichment (no LLM): 7.7GB of 12GB. *Further 691 Phase-N note (2026-07-11):* the default-on long-doc single-pass embed (batch-1, up to 8192 tokens) can BFC-OOM inside the 3072MB embed arena on near-8k docs (fragmentation from varying seq lengths; ~1.3-1.5GB BiasSoftmax requests) — it falls back to windowed cleanly, but `JUSTSEARCH_EMBED_GPU_MEM_MB=6144` removes the double-pay and recovers the last ~0.04 nDCG on legal-clerc (0.2967→0.3401). **6144 is the shipped default since 2026-07-11 (founder decision; history 2048→3072 (391)→6144 (691/F-031))** — worst-case cap sum across lanes now exceeds 12GB on paper, but caps are per-session budgets, not pre-allocations, and GPU mutual exclusion + shrinkage + the windowed fallback bound the realized peak (measured 7.7GB at the old caps; re-measure at next full-corpus enrichment profiling).
- **Evidence:** tempdoc 360 (Worker migration), tempdoc 361 I9; tempdoc 691 Phase C (NER cap update).

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

### F-013: A missing fp16 variant silently runs the INT8 CPU model on CUDA at ~10× per-call cost (NER incident, fixed)

- **Answer:** When a model dir has only the quantized CPU `model.onnx` (no `model_fp16.onnx`), dev-mode variant selection loads it on the CUDA EP where dynamic-INT8 QOperator nodes lack native kernels — per-node CPU-fallback round-trips cost ~10-15× per call. For NER this made enrichment backfill ~75% NER (per-call p50 28.8ms vs healthy 3.16ms) and corpus build 2.69× slower end-to-end (battlefield: 333s → 124s after fix). The file went missing via an interrupted `scripts/models/build-ner.py` run (~2026-07-01: `model.onnx` downloaded, fp16 step never completed, no `build.json` written).
- **Fixes (tempdoc 691):** (1) `model_fp16.onnx` restored with provenance (sha256 `8121A428…DA49`, HF `Xenova/distilbert-base-multilingual-cased-ner-hrl` @ `c2a4dbf5…`); (2) `DevModeVariantProbe` now reports CPU-file-on-CUDA as **degraded** (mirroring `VariantSelector`'s contract branch) and `InferenceCompositionRoot.resolveVariant` WARN-logs every degraded selection — the silent week came from the probe labeling this case `optimal`; (3) NER arena default `justsearch.ner.gpu_mem_mb` 512 → 2048 (the fp16 variant's attention intermediates, O(batch×heads×seq²) ≈ 200MB at batch=16/seq=512, OOM a 512MB arena → continuous batched→per-doc fallback; at 2048: zero OOM).
- **Metric caveat (open):** `encoder_profiles` NER `ortP50` was recorded only by the batch=1 `infer()` path; the batched `inferBatch()` path now records too (tempdoc 691), but runs before that fix report meaningless NER p50s in batched-healthy conditions — use batch-timing shares for pre-691 NER attribution.
- **Corrects F-008's frame:** the 2026-era "NER 18% GPU efficiency / per-call overhead" analysis measured the healthy fp16 path; the 2026-07 incident was a different mechanism (INT8-on-CUDA), not that overhead worsening.
- **Evidence:** tempdoc 691 Phases A-C (attribution runs, root-cause chain, C-series A/B verification, all artifacts + provenance).

---

### F-014: 708 offline encoder screen — candidate footprint/throughput record + two runtime facts (tempdoc 708, 2026-07-11)

- **Context:** the 708 bake-off (search-quality F-034: NO MODEL SWAP) measured candidate encoders
  offline in torch fp16 on the RTX 4070 — a screen, NOT ORT production baselines (the Canonical
  Baselines table above stays ORT-only). Doc-side encode throughput at chunk granularity
  (500-token chunks, includes tokenize+forward+pool), fp16 size estimates:
  incumbent gte-multilingual-base 628 MB / 9.7 docs/s; arctic-embed-m-v2.0 ~610 MB / 10.0;
  granite-278m ~556 MB / 12.9; arctic-embed-l-v2.0 ~1.1 GB / 5.6; bge-m3 ~1.1 GB / 6.2;
  multilingual-e5-large ~1.1 GB / 6.0; Qwen3-Embedding-0.6B ~1.2 GB / 1.35 (W1; ~6× slower than
  same-size peers — decoder-style embedder; its W2 8k-context run exceeded 60 min for 198 docs and
  was abandoned).
- **Runtime fact 1 (production-relevant):** `OnnxEmbeddingEncoder.createChunks` raw id-slice
  windows CLS-pool a non-[CLS] token on windows 2+ — offline A/B isolates this as the dominant
  share of the old whole-doc dense death (0.105 vs 0.745 R@10 with proper per-window special
  tokens, same model/windowing). F-031's single-pass path moots it up to 8192 tokens; any residual
  >8192-token window-mean path still carries it (open, no active tempdoc — the observations
  inbox that held it was retired in tempdoc 872).
- **Runtime fact 2 (tooling):** Snowflake arctic-embed-m-v2.0's HF remote code (mGTE family)
  hard-requires `xformers` on CUDA (`AssertionError: please install xformers`); the incumbent's
  Alibaba remote code does not. `xformers` 0.0.35 installs clean against torch 2.13.0+cu126
  (`--no-deps`; triton warnings non-fatal).
- **Evidence:** tempdoc 708 §Execution log (final table + run JSON pointers).

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

### D-010: llama-server context window is a derived resource - SHIPPED (tempdoc 883, PR 1)

- **Choice:** `-c` is no longer a user preference. `ContextWindowPolicy`
  (`modules/app-inference`) produces a **ladder** - top rung 32768 with GPU layers, 8192 at
  `-ngl 0`, then 16384 -> 8192 -> 4096 - and the launch steps down one rung on a
  `PROCESS_EXITED` startup failure (the same seam `relaunchWithoutReasoningBudget` uses). The Head
  contributes the top rung at `ORDINAL_AUTO_DETECT` (150, `auto_detected` / `hardware_probe`)
  AFTER GPU detection, so `/api/debug/effective-config` explains the window with the mechanism that
  already explains GPU detection. `UiSettings.contextLength` `0` = auto (settings schema bumped to
  2, migrating the old 4096 default); the settings-to-sysprop promotion and the
  `justsearch.context.size.source` marker are deleted. An explicit
  `justsearch.context.size` above ordinal 150 is a ONE-RUNG ladder: honoured or failed loud.
- **Slots + KV:** `-np 2 -kvu -ctk q8_0 -ctv q8_0 -fa on`, keys `justsearch.llm.slots` (default 2,
  clamped [1,8]) and `justsearch.llm.kv_type` (default `q8_0`, restricted to llama.cpp cache types).
  `-kvu` is mandatory next to an explicit `-np`: llama-server enables `kv_unified` only when the
  slot count is automatic, so `-c 32768 -np 2` alone gives `n_ctx_seq` 16384 while `/props` still
  reports `n_ctx` 32768. Two slots is a SCHEDULING choice (a background delegate must not evict the
  foreground prompt-cache prefix, tempdoc 841), not a memory one.
- **Rationale:** the model trains at 262k; the app ran it at 4096 with four engine-chosen slots and
  an f16 KV cache. Measured on the bundled b8571 (tempdoc 883 independent review): `-fa auto`
  resolves to on for CUDA and for `-ngl 0` but is passed explicitly because a q8_0 V-cache aborts
  the launch without it; KV at 32k q8_0 is 544 MiB on both profiles; `--fit` (default on) MAXIMIZES
  rather than fits, choosing 242,944 tokens / 4 GB KV when `-c` is omitted, so an explicit `-c` is
  required. No VRAM arithmetic and no GGUF reader: Qwen3.5 is a Gated-Delta-Net hybrid (8 of 32
  layers carry KV, plus ~50 MiB/slot of recurrent state independent of n_ctx; 32 KiB/token f16,
  17 KiB/token q8_0), so any dense-attention formula is ~4x wrong, and `/props` on b8571 does not
  expose `n_ctx_train`. Free VRAM is recorded on the activation record, never used as an input.
- **What is intent vs observation:** `/api/inference/status.contextWindow`
  (`{rung, reason, freeVramBytes, slots, kvType}`) is the INTENT. `/props` `n_ctx` (published as
  `llmContextTokens`) and `n_ctx_seq` in the llama-server log are the OBSERVATION and stay
  authoritative. `ServerPropsOps` compares the readback against the LAUNCHED rung, not
  `InferenceConfig.contextSize()` - the latter is stale by construction after a step-down.
- **Evidence:** tempdoc 883 (contract, independent review fold R1-R4, §B pre-impl pass, §C
  post-impl pass). Live-window acceptance (`n_ctx_seq`, `kv_unified=true`, forced step-down,
  q8_0 vs f16 tok/s) is scheduled separately and NOT yet measured as of PR 1.
- **Revisit when:** the live window runs and reports the q8_0 tok/s cost (if it exceeds 10% on the
  dev GPU the design says make f16 the default at 16k and below); or when lane F adds a second
  VRAM arbiter - the window, `gpuLayers`, slots, KV type and reranker/VDU co-residency all compete
  for the same VRAM and should be one memory plan at activation, not several.

### D-002: BGE-M3 VRAM budget — FP16+Flash at 3072 MB arena

- **Choice:** FP16+Flash Attention with 3072 MB arena limit (`JUSTSEARCH_BGE_M3_GPU_MEM_MB=3072`).
- **Rationale:** 8192-token input at FP16 needs ~2.6 GB. 3072 MB provides headroom. Coexists with GTE-ModernBERT (~150 MB) and 7B LLM (~4.5 GB Q4_K_M) on 12 GB GPU.
- **Evidence:** tempdoc 322
- **Revisit when:** model changes or VRAM budget analysis for different GPU tiers.

### D-008: Runtime lifecycle authority — desired-state spec/status + single-writer reconciler — SHIPPED (tempdoc 737)

- **Choice:** Head-side AI-runtime lifecycle is governed by one authority
  (`io.justsearch.app.services.runtimestate`): persisted desired state
  (`UiSettings.chatEnabled`, nullable = never-set, default off; set true on
  successful activation), Condition-shaped `RuntimeStatus`
  (ENGINE/ADOPTION/LEASE/PROCEDURE axes with reason codes),
  `RuntimeGpuLease` (binary grants; size-admitting interface for future
  co-residency), and a single-thread level-triggered `RuntimeReconciler` —
  the ONLY sanctioned caller of `switchToOnlineMode/switchToIndexingMode`
  (ArchUnit-forced, `RuntimeReconcilerGuardrailsTest`). Machine actors hold
  non-spec engine states only inside declared procedures (VDU_BATCH);
  `endProcedure` returns the engine to spec. User semantics: soft-off —
  `chatEnabled=false` disables the chat service; procedures may run the
  engine with reason `engine-up-for-background-processing`, and
  `InferenceCapability` composes spec so chat is never offered during
  soft-off background work.
- **Consequences for runtime agents:** never call `switchTo*` directly
  (build fails); request engine state via spec writes
  (`core.set-chat-enabled` operation / `POST /api/settings/v2`) or a
  procedure. `Mode` is internal FSM machinery projected to the wire; the
  `/api/status` `phase` field is a deprecated alias of the additive
  `engineState/chatEnabledSpec/procedure/engineReason/leaseHolder` fields.
  New representations of runtime state must register in
  `governance/runtime-state.v1.json` (fork gate fails the build otherwise).
- **Re-entrancy contract:** listeners fire under the transition lock;
  reconciliation never runs on a listener thread (level-triggered dirty
  flag). Anti-flap: repeated foreign flips (>3 in 5 min) hold convergence
  with reason `convergence-held-flap-suspected`.
- **Evidence:** tempdoc 737 (§8 diagnosis, §12 design, §14 derisk, §15
  implementation log; live Checkpoint 1 record).
- **Revisit when:** sized GPU grants (12 GB+ co-residency) are implemented —
  the lease interface admits sizes but only binary logic ships; or when the
  deprecated wire aliases (`phase`, `starting`) retire per §12d.

### D-009: Observed execution provider on runtime status — SHIPPED (tempdoc 805 W3)

- **Choice:** `/api/ai/runtime/status.onnxFeatures[]` carries additive OBSERVED fields
  (`executionProvider`, `gpuFallback`, `fallbackReason`) beside the intent/discovery fields,
  projected from `EncoderRuntimeExplainer` — now the single authority for "what EP is this
  encoder actually on", consumed by BOTH `/api/inference/encoders` (tempdoc 422) and the
  runtime status. New Head-side seam: `EncoderRuntimeCache` / `WorkerEncoderRuntimeCache`
  (2 s TTL, last-known-good) over the Worker's `getSessionPolicies` +
  `getEncoderOrtCudaViews` RPCs. No proto change: per-encoder `OrtCudaStatus` already crossed
  as `StatusResponse.gpu.*OrtCuda` → `OrtCudaView`.
- **Rationale:** round 11 (tempdoc 734 R11-F3): an upgraded machine ran ALL ONNX inference on
  CPU (retained runtime pack missing the four ORT natives PR #276 moved to
  `ort-native-cuda12-v1.24.3.zip`) while the status reported `cuda12`/`active`/`gpuLayers:99`
  — `sessionActive`/`modelActive` is TRUE for a CPU-fallback session and is explicitly NOT an
  EP claim; the honest reading is the observed triple. A status field is an intent or an
  observation, never an intent presented as an outcome (805 Part D principle 3).
- **Companion guarantees:** the ORT native pack is content-checked at publish
  (`scripts/release/check-ort-native-asset.mjs`, DLL set parsed from
  `OrtCudaHelper.ORT_NATIVE_DLL_SET` — no second hardcoded list), per the ORT-bump coupling
  checklist in `cut-a-release.md`. Install truth split into two axes:
  `installedFully` (install history, contract-measured per FILE, entry-kind aware) vs
  `repairNeeded` (disk reality vs current registry, contract-independent) —
  `InstallCompleteness` in app-services, registered as a logic seam.
- **Evidence:** tempdoc 805 Parts G.3/H/I; round-11 mechanism in tempdoc 734.
- **Revisit when:** the sandbox `onnx-ep-fallback-vs-status` must-watch converts to an API
  assertion (its note names the condition), after which the watch entry retires.

---

## Open Questions

Unanswered questions that need investigation. Agents should prefer
picking up items here over inventing new experiments.

### Q-001: ~~Should GPU sessions warm up at startup?~~ — ANSWERED

- **Answer:** Yes. Warm-up inference added to `initDeferredModels()` in tempdoc 360 (Worker migration). All ORT GPU sessions now run a dummy inference at startup to prime CUDA kernels and BFC arena. First-query DEADLINE_EXCEEDED no longer occurs.
- **Evidence:** tempdoc 360 (warm-up implementation); tempdoc 356 (identified the fix).

---

### Q-002: What does q8_0 KV cost in tok/s on the dev GPU, and does the 32k top rung hold under co-residency?

- **Context:** tempdoc 883 decision 2 ships `-ctk q8_0 -ctv q8_0` by default and decision 1 ships a
  32768 top rung, both argued from launch-time fit (KV at 32k q8_0 measured at 544 MiB) rather than
  from throughput or from behaviour under load.
- **What to measure:** (a) generation tok/s with `-ctk/-ctv q8_0` vs `f16` at the same rung, on the
  dev RTX 4070 - the design says that if q8_0 costs more than 10%, `f16` becomes the default at the
  16k rung and below; (b) whether the 32k rung still fits with the reranker and a VDU batch
  co-resident, or whether the top rung should stay at 16k until that is measured (the owner's own
  open question in 883).
- **Instrument:** `jseval llm-bench` / `llm-gate` (F-012) for tok/s; the recorded
  `contextWindow.reason` on `/api/inference/status` for step-downs.

## Future Work

Identified improvements not yet started. Lower priority than Open
Questions — these are "we should eventually" not "we need to know."

- **FW-001: Arena shrinkage tuning** — Current arena limits (BGE-M3 3072MB, SPLADE 2048MB) are conservative. Actual peak may be lower with arena shrinkage enabled. Profile and right-size. Source: tempdoc 311.
- **FW-002: CPU fallback latency budget** — GTE-ModernBERT at 149M params on CPU: ~160-300ms for top-20. Borderline for interactive search. Measure and decide if CPU CE should be disabled under latency pressure. Source: tempdoc 309 §15.
- **FW-003: ORT CUDA runtime pack** — GPU-accelerated embedding via ORT+cuDNN. Self-check and status wiring in place but ~2.2 GiB pack not assembled. Blocked on cuDNN redistribution licensing. Source: RAG-001 (retired from issues/).
- **FW-004: Speculative decoding** — Eagle-3 could improve generation throughput but needs 400MB-1.3GB VRAM (conflicts with 8GB budget) and isn't integrated into llama-server API yet. Deferred until Eagle-3 llama-server integration + user base with >12GB VRAM. Source: RAG-009 (retired from issues/).

---

<!-- source: docs/explanation/05-ai-architecture.md -->

# 05. AI Architecture (The "Brain")

JustSearch implements a **Hybrid Inference Architecture** to provide advanced AI features (RAG, Vision, Summarization) on consumer hardware with limited VRAM (e.g., 8GB).

## The Problem: VRAM Contention
Modern local AI requires two distinct types of models:
1.  **Embedding Model:** ONNX Runtime encoder assets selected from the model manifest. High-throughput, used for vector search and chunk embeddings in the Worker process.
2.  **Generative LLM:** (e.g., `Qwen_Qwen3.5-9B-Q4_K_M.gguf`, the current packaged default). Latency-sensitive, used for Chat, Q&A, Summarization, and VDU (served by `llama-server.exe`). Models that emit `reasoning_content` support chain-of-thought reasoning (see §Reasoning Pipeline below).

On an 8GB GPU, loading both simultaneously (or leaving both GPU-enabled) can cause OOM (Out Of Memory) errors or fallback to ultra-slow system RAM.

## The Solution: Mutual Exclusion

JustSearch enforces a strict **Single-tenant GPU Policy** across processes:
* The **Main Process** owns Online inference (`llama-server.exe`) via `modules/app-inference` and `InferenceLifecycleManager`.
* The **Worker Process** owns indexing + Worker-side ONNX Runtime encoders, and cooperates via the MMF `main_gpu_active` flag (offset `24`, `MmfWorkerSignalLayoutV1.OFFSET_MAIN_GPU_ACTIVE`).

### The Runtime Authority (desired state, status, procedures)

Since tempdoc 737, who runs on the GPU is governed by one Head-side authority
(`io.justsearch.app.services.runtimestate`), not by callers switching modes
directly:

* **Desired state (spec):** the persisted user intent `chatEnabled`
  (`UiSettings`; written by the `core.set-chat-enabled` operation, the
  Settings API, and on successful runtime activation). "Shut Down AI" writes
  intent — it does not command a transition.
* **Observed status:** Condition-shaped per-axis state (ENGINE
  `Down/Starting/Healthy/Recovering` with a reason code, ADOPTION, GPU LEASE
  holder, and any in-flight PROCEDURE), exposed additively on `/api/status`
  (`engineState`, `chatEnabledSpec`, `engineReason`, `procedure`,
  `leaseHolder`; the older `phase` string is a deprecated alias).
* **Reconciler:** a single-writer, level-triggered loop converges the engine
  toward `spec ∧ policy`. It is the only permitted caller of the mode-switch
  primitives (ArchUnit-enforced). Autonomous work (e.g. the VDU batch) runs
  inside a declared **procedure**; when the procedure ends, the engine
  returns to spec — the system can no longer park itself in a state the user
  didn't ask for.
* **Soft-off semantics:** with `chatEnabled=false`, background procedures may
  still run the engine (idle/energy-gated); status carries the reason
  `engine-up-for-background-processing`, and the chat capability is NOT
  offered while it does.

### Modes (internal machinery)

The `Mode` enum remains the internal FSM vocabulary of
`InferenceLifecycleManager` beneath the authority; externally the Worker only
ever sees the one-bit GPU lease (`main_gpu_active`).

| Mode | Active Model | Purpose | Process |
| :--- | :--- | :--- | :--- |
| **Indexing Mode** | Embedding Model | Vectorizing documents in background | Worker |
| **Online Mode** | Generative LLM | Interactive Chat, Q&A, Summarization, Vision | Main (llama-server) |
| **Offline Mode** | none | No GPU work; background queues can accumulate | Main + Worker |

### Transition Protocol

When the user escalates to an Ask/agent turn in the unified "Search" window (tempdoc 687 — there is no separate Chat tab):
1.  **Main:** Begins a mode transition via `ModeStateMachine` (validates not already transitioning, stores previous mode for rollback).
2.  **Main:** Signals Worker via MMF (`main_gpu_active = 1`).
3.  **Worker:** Unloads/suspends GPU-backed ORT encoder work as needed and skips embedding work while the flag is set.
4.  **Main:** Starts `llama-server.exe` (or **adopts** an already-running instance on the configured port).
5.  **Main:** Polls `GET /health` until 200 OK (timeout configurable via `justsearch.inference.health_check_timeout_ms` system property, default 30000ms; progress logged every 10s during wait — tempdoc 369), then reads `GET /props` (best-effort) to learn the effective `n_ctx` and `model_alias`.
6.  **Main:** Completes the transition to ONLINE via `ModeStateMachine`. On failure at any step, rolls back to the previous mode.

When the user closes Chat or minimizes the app:
1.  **Main:** Kills `llama-server.exe`.
2.  **Main:** Signals Worker (`OFFSET_GPU_ACTIVE = 0`).
3.  **Worker:** Reloads Worker-side ORT encoders as needed and resumes backfill.

## Components

### 1. `llama-server` (The Engine)
We use the compiled binary from `llama.cpp` as a separate process (`llama-server.exe`) for maximum performance and isolation.

**v1 note (current shipping posture):** v1 Simple Mode bundles a **CPU-only** `llama-server` runtime by default (pinned upstream build).
GPU-accelerated runtimes (NVIDIA CUDA) are **deferred to v3 hardware-awareness** and are expected to be distributed via an offline **GPU Booster Pack** (runtime variant) rather than downloaded as arbitrary executables.
The control plane and flags (e.g., `-ngl`) exist today, but GPU acceleration only applies when a GPU-capable runtime is used.
*   **Protocol:** OpenAI-compatible API (`/v1/chat/completions`).
*   **Diagnostics:** `GET /health` and `GET /props` (includes `n_ctx` + `model_alias`).
*   **Binary discovery:** `InferenceConfig.findServerExecutable()` searches canonical paths and `variants/` subdirectories. When GPU is configured (`gpuLayers > 0`), prefers `variants/cuda12/` for CUDA-optimized binary. Falls back to baseline binary. **Dev-layout path** (active only when `justsearch.repo.root` system property is set): searches `{repoRoot}/modules/shell/src-tauri/resources/headless/` (Tauri resource bundle). Added in tempdoc 369 for eval backend LLM support. (The former `{repoRoot}/third_party/llama.cpp/build/` local source-build path was removed with the vendored llama.cpp tree — tempdoc 632; the runtime is the pinned upstream prebuilt download.)
*   **Crash diagnostics:** `waitForServerHealth()` parses llama-server stderr for known failure patterns (e.g., `unknown model architecture`) and surfaces user-facing error messages instead of opaque "failed to load model" errors.
*   **Arguments:**
    *   `-m <model_path>`: Main GGUF model file.
    *   `-c <ctx_size>`: Context window - a **derived resource**, not a preference (see the section below).
    *   `-ngl <layers>`: Number of GPU layers (offload).
    *   `-np <slots>`: Parallel slots. `2` by default (`JUSTSEARCH_LLM_SLOTS`) so a background delegate cannot evict the foreground turn's prompt-cache prefix.
    *   `-kvu`: Unified KV cache. Required *because* `-np` is explicit: passing `-np` at all disables llama-server's automatic `kv_unified`, and without `-kvu` two slots halve the window a request actually gets (`n_ctx_seq`) while `/props` still reports the full `n_ctx`.
    *   `-ctk <type> -ctv <type>`: KV cache type, `q8_0` by default (`JUSTSEARCH_LLM_KV_TYPE`).
    *   `-fa on`: Flash attention, explicitly on - a quantized V-cache aborts the launch without it, so this is never left at `auto`.
    *   `--mmproj`: Vision adapter path (for Qwen/Llava).
    *   `--port <port>`: HTTP port.
    *   `--jinja`, `--metrics`, `--host 127.0.0.1`, and (when thinking is enabled) `--reasoning-format deepseek --reasoning-budget N`.
*   **VDU mode flags** (applied only during VDU batch processing, not global):
    *   `-np 1`: Single slot (multi-slot causes alternating 500s on vision) - pins the common `-np` above rather than adding a second one
    *   `--cache-ram 0`: Disable prompt cache (prevents silent crashes after ~7 pages)

#### The context window (`-c`) is derived, not configured

The packaged model trains at 262k tokens; the app used to run it at 4096 because that was the
shipped value of a `UiSettings` field with no UI control, promoted to a system property so it
outranked every other source. Since tempdoc 883 the window is a resource the runtime fits:

* `ContextWindowPolicy` (`modules/app-inference`) produces a **ladder**: top rung 32768 with GPU
  layers, 8192 at `-ngl 0` (CPU prefill at 32k is minutes per RAG ask), then 16384 -> 8192 -> 4096.
* The Head contributes the top rung at ordinal 150 (`auto_detected` / `hardware_probe`) after GPU
  detection, so `/api/debug/effective-config` explains the window with the same mechanism that
  explains GPU detection.
* If llama-server refuses a rung it exits immediately; `LlamaServerOps.waitForServerHealth` steps
  down one rung and relaunches. A rung that does not fit costs context, not inference.
* An explicit `justsearch.context.size` (env, `-D`, `settings.json`, YAML) is a **one-rung ladder**:
  honoured or the launch fails loud. `UiSettings.contextLength` `0` means auto.
* What was launched is published on `/api/inference/status` as `contextWindow`
  (`{rung, reason, freeVramBytes, slots, kvType}`, where reason is `fit`, `override` or
  `stepped-from:<n>`). That is the INTENT. What the server reports - `/props` `n_ctx`, and
  `n_ctx_seq` in its log - stays the authority for what a request actually gets, and is published
  separately as `llmContextTokens`.
* No VRAM arithmetic and no GGUF reader: the packaged model is a Gated-Delta-Net hybrid whose KV
  footprint no dense-attention formula predicts within 4x, and `/props` does not expose
  `n_ctx_train`. Free VRAM is recorded for diagnosis, never used as an input.
    *   `chat_template_kwargs: {"enable_thinking": false}`: Ensures VLM output goes to `content` field

### 2. `InferenceLifecycleManager` (The Manager)

Delegates to package-private collaborators: **`LlamaServerOps`** (process spawn/kill, health checks, zombie protection), **`OnlineModeOps`** (chat/vision completion requests, streaming, lock acquisition), **`TokenEndpointOps`** (tokenize/apply-template probing with caching), **`ServerPropsOps`** (/props parsing, model ID extraction), and **`ModeStateMachine`** (validated mode transitions).

*   **Responsibilities:**
    *   Spawning/Killing `llama-server`.
    *   **Zombie Protection:** Uses `taskkill /F /PID` on Windows to ensure VRAM is released.
    *   **Health Checks:** Waits for `/health` during startup; runs periodic health checks for hung detection.
    *   **External Instance Adoption:** If the configured port is already serving a healthy `llama-server`, it can adopt it instead of starting a duplicate process (prevents restart loops after a forced kill).
        * By default, adoption is verified via `GET /props` (not just `GET /health`) to avoid accidentally adopting an unrelated HTTP service.
        * Dev escape hatch: `-Djustsearch.inference.external.allow_health_only_adoption=true` (allows health-only adoption when `/props` is missing/unparseable).
        * Adopted servers are still monitored; if the external server becomes unhealthy mid-session, inference transitions to Offline (no process handle to restart).
    *   **Crash Recovery:** If the owned server crashes while in Online mode, it stops and restarts it (with cleanup first). Health checks and crash recovery run on independent schedulers (`healthScheduler`, `recoveryScheduler`), preventing a slow health probe from blocking recovery.
    *   **Mode State Machine:** `ModeStateMachine` validates all mode transitions (`beginTransition` → `complete`/`rollback`, `forceOffline` for emergencies). No raw state assignments — all transitions go through validated operations with precondition checks.
    *   **Effective Runtime Info:** Reads `/props` to capture best-effort runtime `n_ctx` and `model_alias`, which is surfaced via `/api/inference/status` and used as the request `model` id.
    *   **Hot-apply (current):** The Local API exposes `POST /api/inference/reload`, which re-reads persisted `/api/settings/v2` values and calls `OnlineAiRuntimeControl.applyRuntimeOverrides(...)` with `RESTART_IF_ONLINE`.
        * This updates model/context/gpuLayers without a full backend restart.
        * It **must not** auto-start `llama-server` when the system is Offline; it only restarts when already Online.
        * If Online AI adopted an external `llama-server` instance (no process handle), restart is rejected; use `POST /api/inference/detach` to switch to a managed server on a new port.
    *   **Server-Model Compatibility Warnings:** Two runtime warnings help detect common misconfiguration:
        1. **GPU Variant Mismatch** (`LlamaServerOps.startLlamaServer()`): Warns when `gpuLayers > 0` but server executable not under `variants/` subdirectory (indicating CPU variant). Does not block startup.
        2. **Thinking Model Mismatch** (`ServerPropsOps.warnIfThinkingMismatch()`): Warns when `USE_THINKING=true` but loaded model name lacks "Thinking" substring. Does not block startup.

        These are non-fatal warnings logged at WARN level to aid troubleshooting. System continues with potentially degraded behavior.

### 3. Embedding Backend (Worker, ONNX Runtime)
*   **Class:** `io.justsearch.indexerworker.embed.EmbeddingService`
*   **Backend:** ONNX Runtime; sessions built via the Worker composition root (`InferenceCompositionRoot.compose(...)`) and applied by `OrtSessionAssembler` in `modules/ort-common` — see the composition subsection below and register entry D-007.
*   **Default:** CPU-only (GPU offload is opt-in via `JUSTSEARCH_EMBED_GPU_ENABLED`).
*   **GPU Coordination:** `IndexingLoop` unloads/reloads the embedding backend based on `WorkerSignalBus.isMainGpuActive()`.
*   **Model File Selection:** `ModelManifest.loadOrDefault()` reads `model_manifest.json` from the model directory to determine which `.onnx` file to use for CPU vs GPU. External directories without a manifest fall back to convention (`model.onnx` CPU, `model_fp16.onnx` GPU).

### ONNX Runtime Infrastructure (`ort-common`)

All ORT consumers (embedding, SPLADE, NER, BGE-M3, cross-encoder reranker, citation scorer) share a single session-construction pipeline in `modules/ort-common` (`io.justsearch.ort`). Tempdoc 397 collapsed six divergent construction paths onto the typed pipeline below; see [24-worker-inference-composition.md](24-worker-inference-composition.md) for the full explainer and register entry D-007 in `docs/reference/inference-runtime-register.md` for the decision rationale.

**Construction pipeline** (single production path, no customiser lambdas, no SPI discovery):

1. `RuntimePolicyResolver.resolve(cfg, hardware)` → `RuntimePolicy` — JVM-wide session settings (arena, CUDA provider, session, profiling).
2. `ModelSessionPolicyResolver.resolve(role, cfg, hardware, variant)` → `ModelSessionPolicy` — per-encoder GPU / CPU / lifecycle / RunOptions.
3. `InferenceCompositionRoot.compose(cfg, hardware, contract, modelsDir, arbiter)` → `InferenceSurface`. Resolves each encoder's `VariantSelection` (via `VariantSelector`, or `DevModeVariantProbe` when the install contract is absent), calls `OrtSessionAssembler.buildManager(Composition, arbiter)`, wraps sessions as `SessionHandle`, constructs encoders with pre-built `<Role>Assembly` (shape + tokenizer + vocabulary / label-mapping), returns the typed surface.
4. Encoders consume `SessionHandle` only — they do zero filesystem I/O in constructors. `ClosurePropertyTest` (ArchUnit) enforces this.

**Key classes:**

| Class | Purpose |
|-------|---------|
| `InferenceCompositionRoot.compose(...)` | Single production entry point; returns `InferenceSurface` |
| `InferenceSurface` | Typed bundle of ready-to-use encoders + `PolicySnapshot` + `List<SessionHandle>` for lifecycle management |
| `OrtSessionAssembler` | The only caller of ORT setters in production. Entries: `buildManager(Composition, GpuArbiter)`, `verifyModelSession(...)` (Gradle verify-model task), `probeModelNames(...)` |
| `SessionOptionsApplier` | Walks `RuntimePolicy` + `ModelSessionPolicy` fields → ORT setters. Every option value flows from a policy field (§6 closure property) |
| `RuntimePolicy`, `ModelSessionPolicy` | Typed policy records consumed by the applier |
| `SessionHandle` | Interface encoders consume: `acquire()`, `acquireCpu()`, `status()`, `releaseGpu()`, `reportCpuSessionFailure()`, `setLifecycleCallback(...)` |
| `NativeSessionHandle` | Concrete `SessionHandle` impl. Package-private `Builder`; external callers reach it only through the assembler |
| `GpuArbiter` | Typed replacement for `BooleanSupplier shouldUseGpu` |
| `ModelManifest` | Reads `model_manifest.json` for CPU/GPU model file selection (moved from worker-core, 359) |
| `GpuSessionConfig` | Record: `(gpuDeviceId, gpuMemLimitBytes)` |
| `OrtCudaHelper` | Windows DLL preloading, native path resolution, DLL presence checks |
| `OrtCudaStatus` | Structured CUDA observability record (`ready()`, `missingDlls()`, `providerFailed()`, `released()`) |
| `OnnxSessionCache` | Session creation with per-machine graph-optimisation caching (uses `BASIC_OPT` for FP16 models, `EXTENDED_OPT` for others) |

**Diagnostics:** `GET /api/debug/session-policies` returns the resolved `RuntimePolicy` and every `ModelSessionPolicy` as JSON, proxied from the Worker's live `InferenceSurface` via the `GetSessionPolicies` gRPC rpc (§14.28 U4). Diffing two runs is diffing two records; no log archaeology.

**Encoder runtime state:** `GET /api/inference/encoders` (tempdoc 422) returns a derived per-encoder explainer that correlates the policy snapshot with the runtime `OrtCudaView` probe to answer "why is encoder X currently on CPU/GPU/unavailable?" with one structured response. Keys are `EncoderRole.consumerName()` (`embed`, `bgem3`, `splade`, `ner`, `reranker`, `citation`) so operators can correlate the response with `ort.session.*` metric lines in `metrics-worker.ndjson`. Read-only and user/agent-facing (not under `/api/debug/`) by design — the underlying `/api/debug/session-policies` is dev-namespaced and exposes the raw policy snapshot.

Production session option values are driven by `RuntimePolicy` + `ModelSessionPolicy` — `SessionOptionsApplier` is the single setter site:
- `arena_extend_strategy = kSameAsRequested` (exact allocation; two sessions share GPU)
- `enable_cuda_graph = 0` (allows arena shrinkage between calls)
- `use_device_allocator_for_initializers = 1` (weights bypass arena)
- `setMemoryPatternOptimization(false)` (variable-length sequences)
- `setInterOpNumThreads(1)`, `allow_spinning = 0` (reduce CPU contention)

**FP16 CPU optimization caveat:** `OnnxSessionCache.optimizeAndCache()` uses `BASIC_OPT` (instead of `EXTENDED_OPT`) for FP16 models on CPU. This reduces first-run graph optimization from 30-60+ minutes to ~5-10 minutes. FP16 embedding on CPU is still broken/unsupported — ORT CPU EP has no native FP16 support and inserts Cast (FP16->FP32) nodes before every operation, causing severe runtime overhead. The correct solution is to ship FP32 model variants for CPU (SPLADE already does this correctly; embedding does not yet — see model-inventory.md `gte-multilingual-base` entry). See [ADR-0019](../decisions/0019-cpu-gpu-model-selection-strategy.md) for the full CPU/GPU model selection decision.

Model file verification: `./gradlew.bat :modules:worker-core:verifyModel -Pmodel=<path> -Pgpu=true`

### 4. Reranker GPU Coordination (Worker-side, default enabled)

The cross-encoder reranker runs in the **Worker process** (360), sharing
GPU arbitration with embedding, SPLADE, and NER via the signal bus.
GPU is enabled by default (`JUSTSEARCH_RERANK_GPU_ENABLED=true`).

GPU arbitration:
- **Startup initialization**: GPU session is created in `initDeferredModels()` with a warm-up inference to compile the ORT execution plan.
- **Signal bus arbitration**: `selectSession()` checks `!signalBus.isMainGpuActive()` — same as all other Worker ORT consumers.
- **VRAM release**: `releaseGpuSession()` frees the GPU session when Main process claims GPU (e.g., `llama-server` going online).
- **Fallback**: Reranking continues on CPU session while GPU is released or unavailable.
- **Head-side invocation**: The Head calls the Worker's `Rerank` gRPC RPC, sending pre-built document texts (title + snippet). The Head has no ORT sessions.

Defaults: `gpu_mem_mb=2048`, `max_seq_len=512`. At seq=512, GPU inference
for 20 docs takes ~2.2s (vs ~42s on CPU at seq=2048).

Observability: `OrtCudaStatus` record tracks GPU state, visible in `/api/status` under `rerankerOrtCuda`.

## Reasoning Pipeline

JustSearch supports **chain-of-thought reasoning** via the configured chat model — any model that emits `reasoning_content` in OpenAI-compatible SSE streams. This is gated by the `JUSTSEARCH_USE_THINKING` environment variable (default: `true`).

### Activation

When `USE_THINKING=true`, `LlamaServerOps` adds `--reasoning-format deepseek` to the `llama-server` command line. This tells `llama-server` to emit reasoning tokens as a separate `reasoning_content` field in SSE deltas, instead of inline `<think>` tags in the `content` field.

### Streaming Architecture

`OnlineModeOps` parses SSE deltas from `/v1/chat/completions` and routes content to `StreamCallbacks` — a record with 6 callbacks defined in `OnlineAiService`:

| Callback | Purpose |
|----------|---------|
| `onChunk` | Response text content |
| `onReasoningChunk` | Chain-of-thought reasoning (separate from content) |
| `onToolCallDelta` | Tool call JSON deltas (agent loop) |
| `onUsage` | Token usage metadata |
| `onComplete` | Stream finished |
| `onError` | Stream error |

`AgentLlmCaller` (the agent loop's LLM-caller collaborator) accumulates reasoning chunks into a `StringBuilder` and logs the full reasoning at DEBUG level after each agent turn. Its durable form is the run journal: `AgentRunStore` records every `reasoning_chunk` event, and `AgentInteractionMapper.fromRunEvents` folds those records into per-step `{text, durationMs}` blocks at read time, attaching them to the turn they belong to (a read-time projection, never a second durable representation).

On the conversation path, `ConversationEngine` forwards each reasoning chunk to the SSE sink as a `reasoning_chunk` event AND accumulates it, so `persistedAssistant` writes the turn's thinking onto the conversation record as `reasoning: [{text, durationMs}]` — absent when the model did not think. Both surfaces therefore render the same blocks live and after a reload, from one authority. The persisted key is stripped again at the LLM-input boundary (`buildLlmInput` projects every context message to the model contract's keys), so a persisted turn's reasoning is never re-sent to the server on the next turn of a conversation.

### Sampling Parameters

`SamplingParams` (`modules/app-api`) defines per-workload presets injected into HTTP request bodies at 3 injection points in `OnlineModeOps` (`streamChatWithTools`, `streamChat`, `sendChatRequest`):

| Preset | Temperature | Top-P | Used by |
|--------|-------------|-------|---------|
| `THINKING` | 0.6 | 0.95 | Reserved preset for explicit reasoning-heavy calls (not currently wired in production paths) |
| `AGENT` | 0.2 | 0.9 | Agent chat (`AgentLoopService`) |
| `DETERMINISTIC` | 0.1 | 0.9 | Summarization, Q&A |
| `VDU` | 0.0 | 0.9 | Vision document understanding (deterministic OCR output) |

When `SamplingParams` is null, no sampling parameters are sent (server defaults apply).

### Think-Tag Handling

Despite `--reasoning-format deepseek`, `<think>` tags can leak into content in edge cases (llama.cpp bug [#13189](https://github.com/ggml-org/llama.cpp/issues/13189), non-streaming responses, or a build that renders thinking inline). Both defenses live in `OnlineModeOps`, one per transport:

1. **`sendChatRequest()`** — strips `<think>...</think>` via regex from non-streaming responses before returning.
2. **`ThinkTagStreamFilter`** — a stateful filter over the streaming content channel. It is *not* a per-chunk regex: tags straddle SSE frame boundaries (`<thi` + `nk>`), so it holds back only a suffix that could still become a tag. Captured thinking is **rerouted to the reasoning channel**, not deleted, so a build that leaks inline behaves like one that emits `reasoning_content`. Content with no tags passes through byte-identically.

The agent loop no longer strips tags of its own — two strippers over the same fact is two authorities, and only the stream-level one can see a tag that arrived in two pieces.

### VDU Reasoning Suppression

`VduProcessor` prepends `/no_think` to both VDU prompt constants (Pass 1: OCR extraction, Pass 2: metadata enrichment). This is a Qwen3 soft switch — the model was trained to recognize `/no_think` in system or user messages and suppress extended reasoning.

Suppression avoids the "long-wrong trajectory" problem where thinking chains degrade perception quality on pure OCR tasks, and reduces latency and token consumption.

**Known limitation:** `/no_think` in a user message mid-conversation does NOT suppress reasoning (Qwen3 Jinja template checks the system prompt only). The VDU pipeline always sends it as the first message, so this limitation does not apply.

### Empty Output Recovery

`AgentLlmCaller.callLlmWithRetries` retries on empty content (no text and no tool calls) per `AgentRetryPolicy`'s `EMPTY_RESPONSE` decision, and the retry **changes the request**: attempt 2 goes out with `enableThinking=false`. An empty turn is usually not transient server state — a local model that returns nothing on a given prompt shape returns nothing again — so a byte-identical re-issue was a delay, not a second chance. Suppressing the thinking prompt is the one change measured to move it (tempdoc 881 §A.3). (There is no `/no_think` injection on this path.)

An empty turn is usually the model emitting its tool call *inside* an unterminated thinking block, in the XML `<tool_call><function=NAME>` grammar, which `--reasoning-format deepseek` routes wholly to `reasoning_content` where llama-server's tool-call parser cannot see it. `AgentLlmCaller` therefore recovers a wrapper-delimited call from the reasoning channel when a turn has no structured call and no text, and reports the runtime's `finish_reason` rather than guessing at a cause. Do **not** describe this failure as reasoning-token exhaustion: measured on Qwen3.5-9B at n_ctx 4096, it is `finish_reason=stop` after 35–55 of 1024 completion tokens, with the prompt at half the window (tempdoc 881 §A.2 refutes 868 §D.3).

### Reasoning Budget

`llama-server` accepts `--reasoning-budget N` to control reasoning token generation at the template level. Default: **512** — bounded reasoning, on (`JUSTSEARCH_REASONING_BUDGET` env var / `-Djustsearch.llm.reasoning_budget`).

**The budget is shared with the answer.** Reasoning tokens and answer tokens come out of the same completion ceiling (`max_tokens`, default 1024 in the conversation engine); the server spends it on reasoning first. That is why the number matters more than the switch:

| Budget | Measured behaviour (b8571, Qwen3.5-9B, default ceiling) |
|---|---|
| `0` | No reasoning. The server injects a `/no_think` equivalent at the chat template level — more reliable than prompt-level suppression, and the system prompt's `/no_think` stays as defense-in-depth. |
| `512` (default) | Binds at exactly 512 reasoning tokens and leaves room for a complete answer (3/3 non-empty at the parameterless configuration). |
| `-1` (unbounded) | Reasoning consumed the entire completion budget and **not one answer token was produced**, 4/4 — terminating with a normal `done` event and no error. |

Because that failure is silent, config resolution **refuses** an unbounded budget and any value at or above the engine's default completion ceiling, clamping back to 512 with a WARN. Raise `max_tokens` rather than the budget if a workload needs longer thinking.

**Per-request control.** `chat_template_kwargs.enable_thinking` reaches the chat template (it shifts prompt rendering) but cannot authorise reasoning generation while the server budget is 0 — upstream computes `enable_thinking = use_jinja && reasoning_budget != 0 && …`, an AND a request cannot satisfy. So a request can *suppress* thinking (`enableThinking: false`, used by the agent loop and the Quick effort rung) but not enable it against a zero-budget server. Prompt-level `/think` / `/no_think` remains available for per-step control within a session; the most recent directive wins in multi-turn Qwen3 conversations.

**Build compatibility.** Support for a finite budget varies by llama-server build (b8185 accepted only `-1` / `0`; b8571 accepts arbitrary finite values), and `/props` does not advertise it — `chat_template_caps` carries `supports_preserve_reasoning` but no `supports_enable_thinking`. The authoritative signal is therefore launch-argument acceptance: a build that rejects `--reasoning-budget` is relaunched without the flag, and the verdict is published on the runtime manifest as `ai.thinkingSupport` (`SUPPORTED` / `UNSUPPORTED` / `DISABLED` / `UNKNOWN`). Thinking fails closed; inference does not.

### Operational Findings (Tested)

These findings were validated through controlled experiments (12-16 scenario batteries, A/B testing) and are reflected in the current defaults:

1. **System prompt phrasing matters dramatically for Thinking models.** Imperative phrasing ("Call browse_folders first") causes the model to follow it literally on every query. Conditional phrasing ("Use browse_folders when you need to discover folder structure") allows contextual tool selection. This single change was the most impactful improvement in agent quality.

2. **`/no_think` works in system prompts but is ineffective mid-conversation.** The Qwen3 Jinja template checks the system prompt for `/think`/`/no_think` directives. A `/no_think` user message mid-conversation does not suppress reasoning — the model continues generating reasoning tokens (verified: 8605 chars vs 8738 chars initial). The reliable suppression mechanism is `--reasoning-budget 0` at the server level.

3. **Budget-aware prompts are counterproductive for 8B models.** Injecting remaining token budget ("Budget: X/8192 tokens used") into the system prompt caused a V4 regression (66.7% → 58.3%). The suffix "Be concise. Avoid redundant tool calls." made the agent too conservative — it stopped exploring after a single negative result instead of following up. 8B models follow concrete instructions ("use absolute paths") better than abstract meta-cognitive ones ("be concise").

4. **AGENT sampling preset (temp=0.2) is within consensus range but not A/B validated.** Industry consensus is temp 0.0-0.5 for tool-calling agents. The current temp=0.2 has not been tested against temp=0.0 or temp=0.1 for this model.

### Rollback

Set `JUSTSEARCH_USE_THINKING=false` to disable reasoning stream formatting. This omits `--reasoning-format deepseek` from the server command line. Sampling parameters are still sent when callers explicitly provide a preset. Think-tag stripping still runs as a safety net. Server restart is needed for the `--reasoning-format` flag change.

## Vision Capability Detection

JustSearch detects vision capabilities at two levels to ensure VDU features are only enabled when the runtime supports them:

### Config-Level Detection

`InferenceLifecycleManager.hasVisionCapability()` performs static capability detection based on configuration:

- **Check**: `mmprojPath != null` (vision projector model is configured)
- **When**: Configuration load time
- **Purpose**: Prevents startup when VDU is required but unconfigured

### Runtime Detection

`ServerPropsOps.extractServerProps()` performs dynamic capability detection from the running `llama-server`:

1. Fetches `GET /props` after server health check passes
2. Extracts `modalities.vision` boolean from response JSON
3. Caches result in `ServerProps` record
4. Exposes via `/api/inference/status` `hasVisionCapability` field

### VDU Processing Guard

`VduProcessor.process()` enforces runtime guard before processing vision documents:

- **Precondition**: `inferenceLifecycleManager.hasVisionCapability()` must return `true`
- **Failure mode**: Returns `VduResult.skipped()` with reason "Vision capability not available"
- **Telemetry**: `vdu.outcome_total{outcome=skipped}` counter

This dual-layer detection ensures:
1. Early failure detection (config level) before server startup
2. Runtime verification (actual server capabilities) for external server adoption
3. Graceful degradation when vision features unavailable

## RAG Summarization Architecture

To handle documents of any size, JustSearch implements a two-path summarization strategy. The entry point is `SummaryController`, which delegates to decomposed collaborators: `FullCoverageSummarizer` (paged content loading + orchestration), `MapReducePipeline` (hierarchical map/reduce), `ContentLoadingOps` (gRPC document fetching), and `SectionProcessingOps` (section splitting + token estimation):

### 1. Full Coverage (default for UI workflows)
*   **Goal:** summarize the *entire* extracted content (not just top-k chunks).
*   **Approach:** load content in pages (guard-railed), then either:
    * stream a direct summary for small inputs, or
    * run a hierarchical map/reduce when content would exceed the context window.

### 2. Quick Summary (RAG representative chunks)
*   **Goal:** fast “good enough” summary when full coverage is disabled (or as a fallback).
*   **Approach:** use Knowledge Server retrieval to pull representative chunks (top-k), then summarize those.

### Retrieval modes + degradation (current)

RAG retrieval (`SearchService.retrieveContext`) returns explicit metadata so clients can distinguish "semantic", "keyword-only", and fallback behavior:
- `retrieval_mode`: `BM25` | `HYBRID` | `CHUNK_HYBRID` | `FULLTEXT_FALLBACK`
- `retrieval_mode_reason`: allowlisted reason code explaining why a mode was chosen (or blocked); see `docs/reference/contracts/search-and-rag-reason-codes.md`
- `context_truncated`: true when the Worker hit the retrieval budget

Chunk-level hybrid (`CHUNK_HYBRID`) uses the `chunk_vector` field and is coverage-gated: it is only used when chunk vectors are sufficiently backfilled (>= 95%). Readiness is surfaced via `/api/status` (`chunkVectorCoveragePercent`, `chunkVectorsReady`, etc.). A kill switch exists via `rag.chunk_vectors.enabled` (default true).

Optional quality boost (disabled by default): a cross-encoder chunk reranker can rerank BM25 chunk hits under a tight time budget. GPU acceleration requires an ONNX Runtime CUDA-capable native runtime (see `docs/explanation/16-gpu-booster-pack.md`).

### Token budgets (current)
`SummaryController` uses the configured `maxTokens` (persisted via `/api/settings/v2`) as the **output** budget for summarize/Q&A/chat. It also computes a safe **input** budget from the effective context window (`n_ctx`) to avoid llama-server 400s when input + output would exceed the server limit.

The Head passes this input token budget to the Worker (`RetrieveContextRequest.max_context_tokens`) so the Worker can budget context during retrieval (avoids "Worker fetches 200K chars, Head truncates to 3K tokens" waste). The Head still keeps a safety-net truncation step and filters citations based on the returned `sections[]` to avoid "citations for dropped chunks" after truncation.

## Q&A (multi-file “Ask”)

Q&A uses the Worker's retrieval path (`DocumentService.retrieveContextWithMeta(...)` → gRPC `SearchService.retrieveContext`) to get relevant context, then streams an answer via `OnlineAiService`.

Important correctness/UX detail (current):

- RAG retrieval can legitimately return an **empty** context (no chunks indexed + BM25 finds no matches).
- In that case, `SummaryController.handleAskStream` falls back to `documents().fetchBatch(...)` (full docs) instead of hard-failing with `NO_CONTENT`.

### Context size guardrails (strict char budgeting)
Token-aware budgeting is preferred when available (`max_context_tokens > 0`). Character budgeting remains a fallback safety net.
JustSearch enforces a strict **character cap** on retrieved context strings (default **200,000 chars**) to prevent oversized prompts and “soft cap” drift.

Implementation:

- **Token-aware budgeter:** `TokenAwareBudgeter` (`modules/indexing/src/main/java/io/justsearch/indexing/rag/TokenAwareBudgeter.java`) is used when the Head provides `max_context_tokens > 0`.
- **Budgeter:** `ContextBudgeter` (`modules/indexing/src/main/java/io/justsearch/indexing/rag/ContextBudgeter.java`) counts **all** overhead (section headers + separators), not just raw document content.
- **Worker retrieval:** `GrpcSearchService` uses `ContextBudgeter` when building the context returned by `SearchService.retrieveContext` (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/GrpcSearchService.java`).
- **Fallback retrieval:** when RAG returns empty/insufficient context, the fallback full-doc path is also budgeted via `ContextBudgeter` (`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java`).

Regression coverage:

- `modules/indexing/src/test/java/io/justsearch/indexing/rag/ContextBudgeterTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/GrpcSearchServiceRetrieveContextTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceContextBudgetTest.java`

### Stable Intermediate Format: `SECTION_SUMMARY_V1`
To reduce long-run hallucinations in hierarchical runs, all map/reduce steps use a strict intermediate schema:

```text
<SECTION_SUMMARY_V1>
CLAIMS:
- <short claim> (evidence: "<very short quote>")
ENTITIES:
- <entity>
DATES_NUMBERS:
- <date/number> — <context> (evidence: "<very short quote>")
UNKNOWNS:
- <important missing/unclear info, or "none">
</SECTION_SUMMARY_V1>
```

* **Map:** summarize each section into exactly one `SECTION_SUMMARY_V1` block.
* **Reduce:** merge multiple blocks into exactly one smaller `SECTION_SUMMARY_V1` block (dedupe, preserve evidence).
* **Synthesis:** produce the final user-facing summary from the blocks (do not mention the tags).

All hierarchical steps use `OnlineAiService.streamChat(...)` so they share the same streaming primitive and error handling.

## Citation Pipeline

JustSearch uses a **two-pronged citation strategy** (see ADR-0006) to attribute AI-generated answers to source documents:

### Prong 1: LLM-generated citations (primary)

RAG Q&A prompts inject the retrieved source chunks as a plain `Documents:` / `Question:` user message (`RAGContext`), and instruct the LLM to place `[N]` citation markers inline. (The earlier numbered `<passage id="N" source="file">` XML wrapper is retired.) The `rag.citations` SSE event delivers the citation metadata (`ContextCitation[]`):

- `parentDocId`, `chunkIndex`, `chunkTotal` — chunk identity
- `startChar`, `endChar` — character offsets for click-to-jump
- `startLine`, `endLine`, `headingText` — section navigation
- `score` — BM25 retrieval score
- `excerpt` — source chunk text

This path works with any model capable of following citation instructions. No embedding service or ONNX models required.

### Prong 2: Post-hoc cross-encoder matching (supplementary)

After the LLM finishes streaming, `GrpcSearchService.matchCitations()` runs a CPU-only ONNX cross-encoder (`CitationScorer`) to score each answer sentence against source chunks:

1. Answer text is split into sentences via `BreakIterator`
2. Each sentence is scored against all source chunks via `CitationScorer.scoreAll()` (ms-marco-MiniLM-L-6-v2, ~22 MB INT8 ONNX)
3. Scores are sigmoid-normalized to [0,1], filtered by threshold (default 0.5)
4. Results are sent as a `citation_matches` SSE event (after `done`)

The cross-encoder runs on CPU, eliminating the GPU contention that blocked the original embedding-based approach (embedding model and LLM compete for VRAM on single-GPU systems).

Fallback chain in `matchCitations()`:
1. Cross-encoder (CPU, no GPU needed) → preferred
2. Embedding cosine similarity (requires `EmbeddingService`) → blocked during Q&A on single-GPU
3. `EMBEDDING_UNAVAILABLE` → no post-hoc matching

### Frontend rendering

The RAG-citation event orchestration lives in `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` (`onRagCitationMatches` / `onRagCitationDelta`), consuming the streaming callbacks in `modules/ui-web/src/api/streams.ts` and feeding a resolved citation model (`shell-v0/components/chat/citationResolve.ts` / `citationTypes.ts`) to the presentational components under `shell-v0/components/chat/`. It handles both prongs:

- Cross-encoder scores **refine** the RAG citations — excerpts, offsets, and headings from the `meta` event are preserved; only the citation's `score` is updated.
- LLM-generated `[N]` markers are reconciled against the resolved citations before render (prevents duplication).
- `CitationHoverCard` displays document name, excerpt preview, score badge (hidden for BM25 scores >1.0), and section metadata.
- `MarkdownBlock` parses `[N]` syntax into clickable citation buttons.

### Citation Parsing and Attribution Contract (RAG Eval)

For automated RAG evaluation, citation handling uses a permissive parser with strict attribution rules:

- Accepted marker formats: `[1]`, `[Document1]`, `[Document 1]`, and truncated `[1` (stream cutoff tolerance).
- Attribution rule is strict: a parsed marker only counts as correct when the marker number resolves to the expected source document for that claim/query.
- This keeps format tolerance high while preventing false credit from wrong-source citations.

### Configuration

The citation scorer is opt-in via environment variables:

| Env Variable | Default | Description |
|:---|:---|:---|
| `JUSTSEARCH_CITATION_SCORER_ENABLED` | `false` | Enable cross-encoder citation scoring |
| `JUSTSEARCH_CITATION_SCORER_MODEL_PATH` | — | Path to ONNX model directory (`model.onnx` + `tokenizer.json`) |
| `JUSTSEARCH_CITATION_SCORER_THRESHOLD` | `0.5` | Minimum similarity score for a match |
| `JUSTSEARCH_CITATION_SCORER_MAX_SEQ_LEN` | `512` | Maximum token sequence length |
| `JUSTSEARCH_CITATION_SCORER_DEADLINE_MS` | `2000` | Time budget for scoring |

### ONNX Model Distribution

| Feature | Model | Size | Notes |
|---------|-------|------|-------|
| Search reranker | `gte-multilingual-reranker-base` | ~340 MB (FP16 GPU) | 306M params, 70+ langs; default `maxSeqLen=512`; 175ms/20 docs on GPU (343, 359, 360) |
| Citation scorer | `ms-marco-MiniLM-L-6-v2` | ~22 MB (INT8) | CPU-only by design; upgraded from L-2 (343) |

**Auto-discovery resolution order** (implemented in `OnnxModelDiscovery` via `ResolvedPathResolver`):
1. Explicit env var override (no validation)
2. `<modelsDir>/onnx/<modelName>/` (validated, auto-enable)
3. `<dataDir>/models/onnx/<modelName>/` (validated, auto-enable)
4. `<repoRoot>/models/onnx/<modelName>/` (validated, auto-enable)
5. Dev fallback (requires `ENABLED=true` env var)

Models are bundled in the installer as flat assets (~40 MB total) with a post-download arrangement step.

### Model Identity & Swap Detection

Silent model swaps (e.g., user replaces a GGUF file between restarts) can cause subtle quality regressions without any signal. Two mechanisms detect this:

1. **ONNX model fingerprinting** — `CitationMatchOps` computes SHA-256 of `model.onnx` on scorer initialization and stores the fingerprint in a volatile field. On re-initialization, a fingerprint mismatch triggers a warning log. This covers the citation scorer and reranker ONNX models.

2. **Chat model swap detection** — `InferenceLifecycleManager` persists the active model ID (learned from `llama-server /props`) to `<dataDir>/inference-model-id.txt`. On startup, if the persisted ID differs from the newly reported model ID, a warning is logged. This covers the generative LLM served by `llama-server`.

Both mechanisms are warn-only (no blocking behavior) since legitimate model upgrades are a normal operation.

## Vision Support (VDU)
Vision Document Understanding (VDU) enriches visual documents beyond baseline text extraction.
Baseline scanned/image-text searchability is Worker-owned: structured Tika runs first, then bounded
Tika/Tesseract OCR can produce `extraction_method=OCR_TIKA` when the text layer is missing or weak.
Successful OCR also records compact `visual_extraction_evidence`, including OCR language, optional
Tesseract TSV confidence summary, fallback route, truncation, and OCR skip/guard reason when relevant.
That evidence can queue VDU enrichment when baseline text exists but OCR/layout signals suggest richer
visual understanding would help.
*   **Flow:**
    1.  Worker extracts with structured Tika. If extracted text is empty/garbage and the file is OCR-eligible, Worker extraction attempts bounded Tika/Tesseract OCR before VDU is considered.
    2.  The system goes idle (the enrichment backfill runs on idle cycles) and/or the user triggers the "Process pending enrichment" operation (`core.trigger-offline-processing`).
    3.  Head/app-services selects pending docs and runs `VduBatchProcessor` → `VduProcessor`.
    4.  `VduProcessor` calls a Vision-capable model via `llama-server` (e.g., the configured chat model + `--mmproj`) with: “Transcribe the text in this image.”
    5.  Worker persists successful non-empty VDU by overwriting `content`, re-deriving `content_preview` and `language`, regenerating chunks, and recording `extraction_method=VDU`.
    6.  Failed or completed-empty VDU preserves the best baseline text. The UI surfaces per-doc `vduStatus` + `textProvenance` in the Inspector Panel so users can see whether the current text came from Tika, OCR, or VDU.

Worker status splits visual demand into `visualTextNeededCount` for missing baseline readable text and
`visualEnrichmentNeededCount` for documents where VDU is useful after baseline text exists. OCR blockers
therefore degrade retrieval only when baseline text is still missing; VDU enrichment-only blockers degrade
AI features instead.

Verification lanes:

- Hermetic eligibility fixtures (no llama-server): `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VduEligibilityPdfFixturesTest.java`
- Tier-2 OCR (requires llama-server): `modules/system-tests/src/systemTest/java/io/justsearch/systemtests/vdu/VduBatchProcessorE2ETest.java` (`processesScannedPdfWithRealLlm`, fixture `modules/system-tests/src/systemTest/resources/fixtures/pdf/scanned-alpha.pdf`)

### VDU Resilience & Observability

*   **Timeout Protection:** `VduProcessor` enforces strict timeouts on LLM operations to prevent single-threaded VDU queue blocking:
    *   Pass 1 (vision completion): 120 seconds
    *   Pass 2 (chat completion): 60 seconds
    *   Timeout telemetry: `vdu.timeout_total` counter increments on timeout
*   **Circuit Breaker:** `VduBatchProcessor` uses a circuit breaker (5 failures, 1 minute recovery) to fast-fail remaining documents when the LLM is repeatedly failing. This prevents hammering a dead inference engine during batch processing.
*   **Latency Metrics:** Timer metrics (`vdu.pass1.duration_ms`, `vdu.pass2.duration_ms`, `vdu.total.duration_ms`) track pipeline performance. See `docs/explanation/08-observability.md` for the full metrics list.
*   **Debug Trace Logging:** Enable TRACE logging for `io.justsearch.app.services.vdu.VduProcessor` to see truncated text samples (first 500 chars of Pass 1, first 300 chars of Pass 2). JVM property: `-Dlogging.level.io.justsearch.app.services.vdu.VduProcessor=TRACE`.

---

<!-- source: docs/explanation/17-ai-bridge-deep-dive.md -->

# 17. AI Bridge Deep Dive

This page is retained as historical context. It no longer describes the live AI runtime architecture.

The former AI bridge design has been decomposed. Do not use this page as current implementation guidance for llama-server lifecycle, embeddings, GPU detection, prompt support, or backend ownership.

## Current Ownership Map

| Current area | Owner | Notes |
|--------------|-------|-------|
| Online llama-server lifecycle | `modules/app-inference` | Starts, adopts, health-checks, reloads, and stops the online OpenAI-compatible llama-server process. |
| Backend abstractions and local translator support | `modules/ai-backend` | Owns Java backend abstractions used by local translation/summarization paths. It does not own the live llama-server lifecycle. |
| GPU and VRAM detection | `modules/gpu-bridge` | Owns hardware capability detection and GPU-related helper surfaces. |
| Prompt support | `modules/prompt-support` | Owns prompt templates and prompt/reasoning support utilities. |
| Worker embeddings and ORT encoders | Worker modules plus `modules/ort-common` | Embeddings, SPLADE, NER, BGE-M3, cross-encoder reranking, and citation scoring use Worker-side ONNX Runtime session composition. |

## Current References

- Architecture overview: [05-ai-architecture.md](05-ai-architecture.md)
- Module ownership: [19-module-architecture.md](19-module-architecture.md)
- Inference runtime register: [../reference/inference-runtime-register.md](../reference/inference-runtime-register.md)
- Worker inference composition: [24-worker-inference-composition.md](24-worker-inference-composition.md)
- Historical decision: [../decisions/0017-ai-bridge-module-decomposition.md](../decisions/0017-ai-bridge-module-decomposition.md)

## Historical Context

Older documentation referred to an in-process GGUF/FFM `ai-bridge` module with llama.cpp bindings and actors such as `GenerationActor`, `EmbeddingActor`, and `SharedModel`. That architecture is obsolete for current agent-facing guidance.

When updating prompts or generated skills, route implementation questions to the current ownership map above instead of reviving the old `ai-bridge` vocabulary.

## Historical Breadcrumbs

The removed deep-dive material described these obsolete implementation concepts:

| Historical concept | Current interpretation |
|--------------------|------------------------|
| Manual FFM llama.cpp bindings and `NativeLlamaBinding` | No longer a current live-runtime guide. Online generation is managed through the external llama-server lifecycle in `app-inference`. |
| `GenerationActor`, `EmbeddingActor`, `SharedModel`, and `LlamaService` | Historical in-process concurrency model. Do not use these names when extending current inference behavior. |
| In-process GGUF embeddings | Replaced for current guidance by Worker-side ONNX Runtime encoder composition. |
| AI bridge-owned GPU/VRAM management | Split out; use `gpu-bridge` for hardware capability surfaces and `ort-common`/Worker composition for ORT session policy. |
| AI bridge-owned prompt templates | Split out; use `prompt-support`. |

This breadcrumb section exists so older tempdocs, ADRs, and commit messages remain intelligible without making the deprecated architecture look current.

<!-- generated:end -->
