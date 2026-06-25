---
title: "395 — Adaptive Pipeline Considerations"
---

# 395 — Adaptive Pipeline Considerations

**Status:** Open (considerations register, not active work).
**Created:** 2026-04-19 evening (spawned from tempdoc 393 item 2.4
close-out).
**Owner:** Unclaimed — awaiting design pass before any implementation.
**Scope:** Long-term considerations for making pipeline runtime
parameters adaptive to detected hardware, model choice, and workload.
**Parent context:** Many JustSearch pipeline constants were
empirically tuned for a single hardware profile (12 GB Ada dev GPU,
gte-multilingual-base FP16, specific concurrent-session count).
They work today because that's also what ships. They will stop
working when the hardware envelope or model stack changes.

> NOTE: Noncanonical doc. Design register, not a design doc.
> Considerations live here until a specific design tempdoc is opened
> to address them. Items are not actionable in their current form —
> each needs its own design pass before implementation.

---

## Purpose

A register for pipeline parameters that are currently **hard-coded
constants** but will need to adapt to:

1. **Hardware diversity** — 8 GB vs 12 GB vs 24 GB GPUs, integrated
   graphics, CPU-only fallback.
2. **Model stack changes** — future embed/reranker/LLM swaps (see
   tempdoc 358 and successors) with different memory footprints and
   compute profiles.
3. **Workload variation** — small corpora vs 100K-file personal
   archives, interactive vs batch, mixed concurrent load.

Each item below is a consideration, not a plan. Converting one to
action requires: (a) a design tempdoc, (b) a validation strategy
(typically a tempdoc 311-style A/B across hardware tiers), (c)
agreement on the fallback behaviour when adaptation fails.

---

## Considerations

### A1. Per-session GPU arena sizing

**Current state.** `OnnxEmbeddingEncoder.DEFAULT_GPU_MEM_MB = 3072`
(empirically validated for 12 GB card + 3 concurrent encoder arenas
via tempdoc 311 Phase 7 A/B: 2048 MB fragments, 4096 MB causes
cross-arena VRAM contention and 5× regression). Override via
`JUSTSEARCH_EMBED_GPU_MEM_MB` / `justsearch.embed.gpu_mem_mb`
(`EnvRegistry.java:297`). Related arena sizes for SPLADE/NER/reranker
are similarly fixed.

**Why it will need to adapt.**
- 8 GB cards can't afford 3× 3072 MB arenas without pressure.
- 24 GB cards are leaving throughput on the table by using the same
  arena as a 12 GB card.
- 358 model swap may change per-session working-set size.
- ORT's `arena_extend_strategy = kSameAsRequested` (chosen in
  `OrtSessionFactory.java:89`) trades waste for fragmentation — the
  fragmentation worsens non-linearly with arena overallocation, so
  "bigger is safer" is wrong above the sweet spot.

**What upstream gives us.** Per tempdoc 393 § 2.4 research: ORT's
CUDA EP docs do not publish a sizing heuristic, percentage-of-VRAM
guidance, or multi-session recommendations. Shared allocator across
sessions ([discussion #21577](https://github.com/microsoft/onnxruntime/discussions/21577))
may not apply to CUDA. Any adaptive logic is JustSearch-original.

**Design questions to resolve before implementing.**
1. Per-hardware-profile table vs. formula? A table (per `HardwareProfile`
   tier) is more explicit but needs A/B validation per tier. A
   formula (e.g. `256 MB per GB of total VRAM, clamped`) is
   implicit and needs justification.
2. How do concurrent encoder arenas coordinate? SPLADE, embed, NER,
   reranker all claim memory from the same GPU. If they're sized
   independently, total claimed can exceed total VRAM. Either
   (a) each sizes independently with a global cap enforced at
   construction, or (b) a central allocator plans the split.
3. What's the fallback if probing fails? `VramDetector` already
   sticky-fails to -1 on nvidia-smi absence. Current code falls back
   to the fixed 3072. Adaptive must retain a safe fallback.
4. What's the validation strategy? The tempdoc 311 Phase 7 A/B ran
   on the dev stack across one hardware profile. Cross-tier
   validation means the same A/B on 8 GB, 12 GB, 24 GB systems —
   which nobody has immediate access to.

**Handoff requirement.** A design tempdoc must answer: "what's the
sizing function, how is it validated per tier, and what's the
rollback path if a tier shows regression." Not ready for
implementation.

---

### A2. Batch sizes across encoder types

**Current state.** `OnnxEmbeddingEncoder.MAX_ORT_BATCH_SIZE = 8`
(334 Phase 8 validated: batch=16 at 2048 MB OOM'd, batch=16 at
4096 MB regressed 5× on 12 GB GPU). SPLADE and NER have their own
tuned batch sizes. All fixed.

**Why it will need to adapt.** Same reasons as A1 — different VRAM
classes can run different batches. 24 GB could do batch=16 safely.
8 GB needs batch=4 or CPU fallback.

**Design questions.**
1. Is batch size coupled to arena size, or independently tuned?
2. Does it belong in `HardwareProfile`, in a new `BatchProfile`, or
   per-encoder at init?
3. How does it interact with `BFCArena kSameAsRequested` under
   variable input lengths (the current fragmentation mode)?

---

### A3. Thread counts for intra-op / inter-op

**Current state.** ORT intra-op / inter-op thread counts are set
per session. Not currently varied by hardware.

**Why it will need to adapt.** A user on a 4-core CPU with GPU-lite
profile and a user on a 16-core workstation shouldn't get the same
intra-op setting. Too many threads on small CPUs causes context
switch thrashing; too few on big CPUs underuses them.

**Design questions.**
1. Heuristic from `Runtime.availableProcessors()` vs
   `HardwareProfile`-tiered?
2. Interaction with indexing-loop parallelism (currently
   single-threaded by design)?

---

### A4. Concurrent encoder session count

**Current state.** Embed, SPLADE, NER, reranker all hold live ORT
sessions simultaneously during backfill. Assumption: the combined
VRAM fits. For 8 GB cards this is already not true — they run on
"GPU-lite" profile (tempdoc 381) with inference offloaded or
deferred.

**Why it will need to adapt.** The number of concurrent sessions is
currently implicit (all loaded at startup). A smaller GPU might
need serial session activation: load one encoder, run, release, load
the next. This is a fundamentally different pipeline shape.

**Design questions.**
1. Is this a load/unload coordinator, or a pipeline-stage scheduler?
2. How does it affect `CombinedEnrichmentBackfillOps` which currently
   assumes all encoders are live?
3. Latency cost of session teardown/rebuild per stage.

---

### A5. Chunk size and overlap

**Current state.** `ChunkDocumentWriter` uses `CHUNK_TARGET_TOKENS`
and `CHUNK_OVERLAP_TOKENS` as constants. Tuned for current embed
model's context window (512).

**Why it will need to adapt.** Larger-context embed models (e.g. 2K+
context gte-modernbert successors) should use bigger chunks. Smaller
models should use smaller.

**Design questions.**
1. Derive chunk size from the loaded model's `max_position_embeddings`?
2. Backward compatibility: re-chunking existing docs on model swap
   is expensive. Do we require a rebuild on chunk-shape change?

---

### A6. Commit cadence

**Current state.** Time-based commit in the indexing loop. Commits
per N batches in the tight backfill loop. Not adapted to workload.

**Why it will need to adapt.** For 1K-doc corpora, committing every
batch is overhead. For 1M-doc initial ingests, committing too rarely
risks losing large amounts of work on crash. Same code today.

**Design questions.**
1. Bytes-flushed-based commits vs time-based vs batch-count-based?
2. Interaction with NRT refresh cadence (already separately tuned)?

---

### A7. GPU VRAM coexistence with concurrent LLM

**Current state.** When tempdoc 394 item 4 ships — `memory.enable_memory_arena_shrinkage` removed from
`OrtSessionFactory.createGpuRunOptions()` plus default embed arena raised
3072 → 6144 MB — the inference stack's measured steady-state VRAM peak
is **~10.5 GB** (measured in experiment E6', 2026-04-20: 253 s → 139 s
pipeline, 1.82× speedup, 0 OOMs). On a 12 GB card this fits comfortably
when no concurrent LLM is loaded. It does **not** fit when `llama-server`
(a Brain-process `llama.cpp` server) is also running: LLM VRAM is
typically 4–6 GB depending on quant and model, and 10.5 + 4 > 12.

Today, ingest completes before LLM inference is typically invoked, so
the constraint is only a concern when both run concurrently (e.g.
RAG chat during ongoing backfill, or a second user query). Tempdoc 394
landed item 4 as an unconditional config change on the assumption that
adaptive coexistence belongs here — not in 394.

**Why it will need to adapt.** The shrinkage-off configuration is
correct for eval, LLM-off deployments, and short ingest windows on
any machine. The fleet includes users who run LLM concurrently on
12 GB cards, users on 24 GB cards where the problem doesn't exist, and
users on 8 GB cards where neither 394 item 4 nor concurrent LLM was
ever viable (GPU-lite profile, tempdoc 381).

**Candidate adaptation strategies.**

- **A7-i. Runtime-conditional shrinkage.** Detect whether llama-server
  is loaded at session init (or on LLM-state change) and re-enable
  shrinkage dynamically. Reverts to the 394 baseline (190.6 s) when
  LLM is present; captures the full 1.82× when it isn't. Simplest
  adaptation; costs per-call detection overhead or a lifecycle hook.
- **A7-ii. VRAM reclamation.** Shrink other encoder arena caps under
  shrinkage-off: SPLADE 4096 → 2048, reranker 2048 → 1024, to free
  ~3 GB. May require per-encoder testing that reduced caps don't OOM
  under shrinkage-off. Uniform config, but risks new encoder OOMs and
  caps additional future throughput.
- **A7-iii. Shared allocator (P3 / ORT `CreateAndRegisterAllocator`).**
  Small JNI shim calling `OrtApi::CreateAndRegisterAllocator` gives all
  four encoder sessions a single shared VRAM pool. LLM gets the
  remainder of the card. Sum-of-caps problem dissolves structurally.
  Highest engineering cost; cleanest outcome. Tempdoc 394's prior-art
  section documents the API (unexposed in Java binding) and ORT docs
  references. This is the P3 mechanism of 394's theorized design
  validated as worth building *if* coexistence is the trigger.

**Design questions to resolve before implementing.**
1. Where is LLM presence detected and communicated to the inference
   stack? At `IndexerWorker` bootstrap, at per-session ORT init, or
   via a runtime signal bus? How does hot LLM load/unload propagate?
2. Is the adaptation one-shot at startup or flippable at runtime?
   A7-i flips easily; A7-ii and A7-iii are effectively startup-only.
3. How does this interact with A1 (per-session arena sizing) and A4
   (concurrent session count) — do they share a single adaptation
   surface, or are they independent axes?
4. What is the fallback if detection fails? Default must be safe — i.e.
   leave shrinkage-on (394 baseline), not shrinkage-off-with-LLM-also-up.
5. Eval-mode guarantee: whichever strategy, `jseval` with
   `--start-backend --clean` (no LLM) must retain the full 1.82×
   measured payoff. Regression there would silently invalidate 394's
   entire result.

**Empirical basis.** E6' (tempdoc 394, 2026-04-20): 253 s → 139 s
pipeline on scifact, VRAM peak 4.8 → 10.5 GB. Run A (items 1 + 2
landed, shrinkage-on): 190.6 s, VRAM peak ~4.8 GB. Stacked projection
(items 1 + 2 + item 4 eval-mode): ~105 s, 2.4× total. LLM-on holds
that back to the Run A baseline unless one of A7-i/ii/iii lands.

**Handoff requirement.** A design tempdoc must pick one strategy (or
a combination), specify detection + fallback, validate on both LLM-on
and LLM-off paths on at least one hardware tier, and preserve the
eval-mode guarantee above.

---

## Cross-cutting principles

Any design tempdoc derived from this register should:

1. **Require a validation matrix.** "Runs on my 12 GB dev box" is
   not sufficient — the whole point is to handle other
   configurations. At minimum, define expected behavior on 8 GB and
   24 GB systems even if actual runs are deferred to when hardware
   is available.
2. **Preserve the fixed-value fallback.** Every adaptive decision
   must gracefully degrade to the current empirically-validated
   constant if detection fails. Users who run without nvidia-smi,
   in containers, or on unusual hardware shouldn't be worse off.
3. **Be observable.** Log the detected hardware and the chosen
   adaptive parameters at startup. If adaptation produces a bad
   choice, the user should be able to tell from worker.log why.
4. **Honour the env-override escape hatch.** Existing env vars
   (`JUSTSEARCH_EMBED_GPU_MEM_MB` etc.) must continue to win over
   any adaptive logic. Power users with measured workloads need the
   final say.

---

## Relation to existing tempdocs

- **311 (BFCArena root cause)** — the source of A1's current value
  via Phase 7 A/B. Any A1 redesign must re-run the equivalent A/B
  per hardware tier.
- **334 (single-pass enrichment)** — established MAX_ORT_BATCH_SIZE
  via Phase 8. A2 would rerun.
- **358 (model selection)** — most of these considerations become
  acute once the model stack swaps. Adaptive work should be scheduled
  to land before or with 358 successor.
- **381 (model distribution)** — introduced `HardwareProfile` tiers.
  A1 and A4 probably want to use this as the dispatch surface.
- **390 (system optimization), 391 (pipeline throughput)** — baseline
  measurement infrastructure (jseval, timelines) that any adaptive
  work will use for validation.
- **393 (code audit)** — item 2.4 closed to this doc; the
  "accepted-as-designed" rationale for keeping 3072 MB is in 393.
- **394 (encoder call-path batching)** — A7's empirical basis. E6'
  experiment measured 1.82× pipeline speedup from shrinkage-off plus
  6 GB embed arena; that config's 10.5 GB steady-state VRAM peak is
  what A7 must adapt around. 394 ships item 4 unconditionally; A7
  owns the coexistence adaptation design.

---

## Exit criteria

This doc does **not** close in the normal "every item done" sense.
It closes when:

1. Each consideration has either been (a) converted into a design
   tempdoc (which then owns the actual work), or (b) explicitly
   deprecated as not-worth-pursuing with rationale.
2. No new considerations have accumulated in 6+ months (suggesting
   the register has stabilised).

Until then, new hard-coded pipeline constants that exhibit the same
pattern (empirically tuned for dev stack, load-bearing for shipped
hardware profile) should be added here rather than silently
accumulated.

---

## Sources

- `modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoder.java` — A1, A2.
- `modules/ort-common/src/main/java/io/justsearch/ort/OrtSessionFactory.java` — arena_extend_strategy choice (A1);
  `memory.enable_memory_arena_shrinkage` toggle (A7).
- `modules/gpu-bridge/src/main/java/io/justsearch/gpu/VramDetector.java` — detection infrastructure A1 will use.
- `modules/configuration/src/main/java/io/justsearch/configuration/model/HardwareProfile.java` — tier dispatch surface.
- Tempdoc 393 § 2.4 — the close-out that spawned this register,
  including the ORT upstream research trail.
- Tempdoc 394 — E6' measurement underpinning A7; prior-art calibration
  of ORT `CreateAndRegisterAllocator` (A7-iii candidate strategy).
