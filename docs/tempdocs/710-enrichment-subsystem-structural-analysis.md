---
title: "Enrichment/embedding subsystem — structural analysis and long-term design. Founder-directed scope increase out of 691 §Phase L: 691's campaign kept unearthing the same structural defect classes (representation forks, memory-blind batching, undeclared model capabilities, bare-literal pacing, implicit scheduling dependencies, metrics that can lie); this tempdoc runs the evidence-scoped structural analysis and produces a design + migration order. Analysis is READ-ONLY; no restructuring is authorized by this tempdoc — the deliverable is a defect-class → code-site → design-move map for founder review."
type: tempdocs
status: "MERGED (Waves 1/1.5/2 — PR #133/#135/#136); remaining structural-analysis moves beyond those waves are the open residual per the 2026-07-12 reconciliation note. Full pipeline ran same-day: S-A/S-B (four parallel read-only audits: 33-instance history inventory, representation/dataflow map, resource/config/capability census, observability/test-seam audit) → S-C synthesis (6 design moves, 4 migration waves, explicit restraint list; dominant class = undeclared-model-capabilities, 12 instances) → S-D adversarial review (all 5 spot-checked facts held; 1 BLOCKER + 4 corrections found and APPLIED — Move 1 backward-compat plan, Move 4 re-sequenced after 691+702, honest sizing on Moves 1/2, escalation/test-fragility dispositions, Move 3 claim precision). Wave 0 feeds back into 691 Phase 2 (runHidden profiler gap would corrupt the A/B). Notable live-bug candidate found: BgeM3BackfillOps writes VECTOR on chunk docs (observations-logged, needs triage). Nothing implemented; implementation NOT authorized. [STATUS RECONCILIATION 2026-07-12: this header is now stale — Wave 1 (bounded tokenization, ORT metrics, BGE-M3 fix) MERGED as PR #133 (29be073), Wave 1.5+2 (pacing config + Model Capability Contract) MERGED as PR #135 (cbcb86c), and role-aware capability requirements MERGED as PR #136 (99e5ec9). Remaining waves/moves beyond these are unchanged by this note. Reconciled during the 705 takeover; owner should refresh the header to reflect merged state.]"
created: 2026-07-10
author: agent session 2026-07-10 (Fable; founder-directed scope increase from 691)
category: architecture / indexing / long-term-structure
related:
  - 691-corpus-build-throughput          # the spawning campaign; §Phase L records the defect-class observations
  - 686-real-pdf-corpus-and-tika-pressure-measurement   # extraction leg (OUT of scope here; shares the unbounded-tokenization incident)
  - 702-dense-fusion-score-calibration-euclidean-cosine # merged sibling (PR #121) touching the same config surface
  - 708-encoder-domain-fit-legal-professional-text      # paused encoder lane; "encoder swap without incident" is this doc's north star
  - 647-engine-performance-attribution-and-budget-allocation # the method: attribution before allocation before (re)design
  - 553 # representation-drift class precedent (projection-vs-fork; execution-surfaces register pattern)
---

# 710 — Enrichment/embedding subsystem structural analysis

## Charter (founder-directed, 2026-07-10)

Increase scope from 691's point fixes to a code-and-structure-level analysis of the
enrichment/embedding vertical, with the goal of designing it to be better for long-term
development. Founder and agent agree the 691 campaign's findings are symptoms of structural
debt: every phase paid a discovery tax re-deriving invariants the structure doesn't express,
and the defects cluster into repeatable classes.

**This tempdoc authorizes ANALYSIS + DESIGN only.** Implementation (restructuring PRs) requires
a separate founder go-ahead after the design review. All analysis is read-only.

## Scope

**In:** the enrichment/embedding vertical —
encoder layer (`OnnxEmbeddingEncoder`, `SpladeEncoder`, `BertNerInference`, `ort-common`
session/arena/manifest infra), embedding service layer (`EmbeddingService`, provider
interfaces, prefix/pooling handling), backfill orchestration (`BackfillScheduler`,
`CombinedEnrichmentBackfillOps`, `LateChunkingEmbedBackfillOps`, `LoopPacingPolicy`),
chunking (`ChunkSplitter`, `ChunkDocumentWriter`, encoder-internal windowing), the enrichment
config surface (`ResolvedConfigBuilder.buildEmbedding/buildNer/...`, `EnvRegistry`), and
enrichment observability (`EncoderProfileAccumulator`, batch timing, per-cycle logs).

**Out:** extraction/Tika/OCR (686/705/706 own it), the Lucene write path and primary indexing
loop, query-time search pipeline (except where persisted representations couple to it), the
LLM inference lane, model distribution/packaging (657 owns it).

## Defect-class hypotheses (seeded from documented history; S-A validates/extends)

1. **Representation forks** — same content, two independent derivations that can drift
   (parent VECTOR vs CHUNK_VECTORs, E-5; whole-doc SPLADE truncation vs chunk_splade, 691 §G).
2. **Memory-blind batching** — fixed batch constants + guessed arena sizes with no
   `batch × seq` memory model; ≥3 incidents (NER arena OOM 691 A-2; SPLADE batch 16→4 cut;
   embed 8×8192 OOM 691 §J-4).
3. **Undeclared model capabilities** — pooling mode, context length, prefixes, variant
   precision consumed as scattered assumptions (fp16-missing incident F-013; CLS discovery
   mid-implementation 691 I-2; empty-prefix discovery 691 L-6).
4. **Bare-literal pacing** — throughput constants with no config surface, derivation, or
   rationale (`chunkSlotsPerBatch=50`, batch 100, commit thresholds).
5. **Implicit scheduling dependencies** — enrichment pass ordering encoded positionally, not
   as declared dependencies; late chunking just inverted one silently (691 §G scheduling note).
6. **Metrics that can lie** — recording sites off the hot path miss whole regimes
   (NER batched-path blind spot, 691 B-5/C).

## Method

- **S-A. Evidence inventory** (subagent): mine tempdocs 640/647/648/686/691/700/701/702/706/708
  + observations + both registers for every documented enrichment-subsystem defect/incident/
  friction; classify into the classes above (new classes allowed); cite tempdoc § + code site.
- **S-B. Code surveys** (three parallel subagents, file:line evidence): representation &
  dataflow map; resource/config/capability census; observability & test-seam audit.
- **S-C. Synthesis + design** (main session): defect-class → code-site → design-move map;
  target shape; enforcement tier per class (structural/compile-time > gate > hook > prose,
  per tier-register philosophy); migration order respecting 691 Phase 2 → 702 merge → 708.
  Per-class "leave it, document it" is a valid outcome — long-term velocity, not purity.
- **S-D. Adversarial review** (independent subagent ≠ designer): attack the design for
  over-DRY (AHA), migration cost across live worktrees, and silent-green retrieval risk.
- **S-E. Founder review** — nothing is implemented before it.

## Design tenets (fixed up front)

- **North-star acceptance test: an encoder swap lands without an incident.** (708 may force
  one; the fp16/CLS/prefix/context incidents are all "the pipeline didn't know what the model
  was".) A design move earns its place by making a documented incident class impossible or
  loudly visible — not by symmetry or aesthetics.
- **Attribution before refactor** (647's method applied to structure): every design move cites
  the incidents it retires.
- **AHA / projection-not-fork** (CLAUDE.md; 553): unify only what shares a reason to change;
  one canonical derivation, projections not forks.
- **Behavior-preserving by gate, not by promise:** migration PRs are A/B-able against the
  existing relevance/union-recall/leak/perf gates; silent-green retrieval regression is the
  named failure mode.

## S-A/S-B survey results (2026-07-10) — condensed evidence record

Four parallel read-only subagent audits completed same-day. Their full reports are
session-ephemeral; every load-bearing finding is preserved inline here with `file:line`.

### S-A: documented-history inventory (33 instances, classified)

Class frequencies across tempdocs 640/647/648/686/691/700/701/702/706/708 + observations +
both registers: **undeclared-model-capabilities 12** (dominant), **metrics-that-can-lie 6**,
memory-blind-batching 3 (+1 corollary), bare-literal-pacing 3, representation-forks 2 (fewest
instances but the largest measured cost — up to ~50% of embed work on dense corpora, 691 F-2).
Three NEW classes emerged: **missing-failure-escalation** (3 instances — the combined pass
shipped without the poison-pill escalation its individual-op siblings all had; fixed by 700),
**uncached-hot-path-resource-reload** (1 — `SsotAnalyzerRegistry` re-parsed per request, ~40
loads/doc, independently re-discovered by two sessions 3 days apart, still unfixed), and
**test-harness-fragility-for-encoder-regressions** (1 — `@Tag("evidence")` invisibility +
model-dir walk-depth inconsistency make exactly the crash-regression tests skip silently in
worktrees). The seeded class **implicit-scheduling-dependencies is under-evidenced** — zero
observed incidents; its only instance is the forward-looking late-chunking inversion (691 §G).
Highest-recurrence-risk judgment: (1) representation forks (a second instance, SPLADE, is
already named); (2) the un-audited embed/NER twins of 686's upfront-tokenization crash;
(3) stale numeric rationale comments (NER "2.0ms/call" was 15-17× off live); (4)
escalation-omission in any future batch path; (5) "log, don't fix" observations not converting
to work (the SsotAnalyzerRegistry re-discovery proves it).

### S-B1: representation & dataflow map

- **`VECTOR` has six write paths** (sync path effectively dead — `JobBatchWriter.java:116`
  passes a bare `false` literal with a comment, making `IndexingDocumentOps.buildDocument`'s
  synchronous-embed branch production-unreachable; migration batch path `IndexingLoop.java:740-771`;
  individual backfill; combined backfill; BGE-M3 unified; late-chunking).
- **THREE independent chunkers** over the same content, differently parameterized, only one
  persisting offsets: encoder-internal token window 512/128 (`OnnxEmbeddingEncoder.java:101-102`,
  discarded), RAG `ChunkSplitter` ~500/50 estimated tokens via char heuristic (~3.85 chars/token;
  offsets persisted as `CHUNK_START_CHAR/END_CHAR`, invariant `substring(start,end)==content`,
  `ChunkSplitter.java:794-800`), NER `ChunkSplitter` 400/50 (`NerService.java:31-33`, discarded).
  Late chunking reuses only the persisted RAG boundaries — it removes the duplicate *embedding*
  but does not unify the chunking schemes.
- **BGE-M3 backfill is defect-suspect (live bug candidate)**: `BgeM3BackfillOps` queries
  `SPLADE_STATUS=PENDING` across parents AND chunks but unconditionally writes
  `VECTOR`+`EMBEDDING_STATUS` (`BgeM3BackfillOps.java:171-181,214-223`) — on a chunk doc that is
  the wrong field pair; `CHUNK_EMBEDDING_STATUS` never touched, so the plain provider separately
  re-embeds the same chunk with a DIFFERENT model → parent/chunk vectors from different embedding
  spaces, and the NER-readiness gate (`BackfillScheduler.java:230-236`) can stay permanently
  blocked. (Logged to observations; needs triage — severity depends on whether any deployment
  enables BGE-M3.)
- Other convention-maintained invariants: `ENTITY_*_TEXT` derivation hand-duplicated in two files
  (`NerBackfillOps.java:98-111` = `CombinedEnrichmentBackfillOps.java:408-427`); dead
  `CHUNK_CONTENT` fallback in the combined pass's parent-only branch
  (`CombinedEnrichmentBackfillOps.java:271-273`); `PARENT_TOKEN_COUNT` records the TRUE token
  count while the SPLADE vector encodes only the first `maxSeqLen` tokens, divergence unrecorded
  (`SpladeEncoder.java:268-274` vs `:253-256`); chunk "token" targets are char-heuristic estimates
  that downstream real tokenizers silently re-truncate.
- Status machine: per-stage `*_STATUS`/`*_RETRY_COUNT` with shared pure failure helpers (700's
  fix); disambiguation completion is in-memory only (`BackfillScheduler.java:78-79,265-286`);
  ordering deps are positional (chunk-embed waits on parent drain ONLY in the individual path,
  `BackfillScheduler.java:207-210`; NER waits on both; SPLADE on "nearly done"; late chunking
  ordered first to pre-empt the combined pass claiming the same PENDING parents).

### S-B2: resource / config / capability census

- **Batch/arena constants**: embed `MAX_ORT_BATCH_SIZE=8` (`OnnxEmbeddingEncoder.java:218`,
  OOM-history rationale), SPLADE 4/4 CPU/GPU (`SpladeEncoder.java:293,304`), NER 16
  (`BertNerInference.java:284`), BGE-M3 4/2 (`BgeM3Encoder.java:51-52`, **bare** — no derivation,
  unlike all siblings). Arenas: embed 3072 / SPLADE 4096 / NER 2048 / rerank 2048 / bgem3 3072
  (`ResolvedConfigBuilder.java:1006-1138`). Arena extend-strategy `kSameAsRequested` runtime-wide;
  the per-session override field exists but is "always empty today"
  (`SessionOptionsApplier.java:81-83`) — three lanes' comments name `kNextPowerOfTwo` as the
  known-but-unlanded fix.
- **The 686 crash pattern is still live in two lanes**: SPLADE got
  `TOKENIZE_GROUP_CHAR_BUDGET=512_000` (`SpladeEncoder.java:325`) after the native-heap JVM crash;
  **embed's `embedBatchWithChunking` Phase 1 (`OnnxEmbeddingEncoder.java:394-418`) and NER's
  `inferBatch` (`BertNerInference.java:313-329`) still tokenize the full caller list upfront,
  unbounded** — the same shape, unexploded.
- **OOM handling is asymmetric**: OOM detection is a string match on ORT's message
  (`NativeSessionHandle.isBfcArenaFailure`, `NativeSessionHandle.java:483-489` — an ORT text change
  silently defeats every fallback). SPLADE retries the failed sub-batch on CPU in-line
  (`SpladeEncoder.java:596-637`); **embed discards the whole 100-doc backfill batch to per-doc
  fallback on one OOM anywhere** (`OnnxEmbeddingBackend.java:140-142` →
  `EmbeddingService.java:387-394` returns null for the batch → `EmbeddingBackfillOps.java:108-124`).
- **Pacing has zero override surface**: every `LoopPacingPolicy` constant (poll 16, embed backfill
  100, NER 100, SPLADE 200/10/5000ms, commit 10s/1000) has no `EnvRegistry` entry (grep-confirmed);
  `chunkSlotsPerBatch=50` is an unnamed inline literal (`CombinedEnrichmentBackfillOps.java:138`);
  BGE-M3's backfill sizes bypass `LoopPacingPolicy` entirely (`BackfillScheduler.java:59-60`).
  Dead cross-reference found: `ResolvedConfigBuilder.java:1022` cites a constant that no longer
  exists (logged to observations).
- **Model-capability map — the core input.** Machine-checked at boot (graph-probed, cannot drift):
  `needsTokenTypeIds` + SPLADE output format (`OrtSessionAssembler.probeModelNames`). EVERYTHING
  ELSE is implicit with a **silent fallback**: pooling mode substring-parsed from
  `pooling_config.json` with silent default **MEAN** on absence/corruption
  (`OnnxEmbeddingEncoder.detectPoolingStrategy:736-755` — for the CLS-pooled gte model a missing
  file silently mean-pools everything, debug-log only); context length config (2048) never
  validated against the model's real trained capacity (no artifact even declares it); embedding
  dimension detected reactively from the first inference's output shape
  (`OnnxEmbeddingEncoder.java:74,345-347`) with no compatibility check against the existing index;
  variant precision = filename-substring `"fp16"` (`DevModeVariantProbe.java:70,77`); `build.json`
  provenance never read at runtime; `model_manifest.json` absent for ner/reranker (silent legacy
  convention fallback — the F-013 incident is this exact failure mode); NER label mapping silently
  falls back to a hardcoded default; tokenizer↔weights correspondence never verified.

### S-B3: observability & test seams

- **The B-5 blind-spot class is REINTRODUCED on this very branch**: `runHidden()` never records
  into `EncoderProfileAccumulator` — `embedSingle` and the entire late-chunking path
  (`embedWithSpans`) are invisible to the embed profiler (only the batched path records,
  `OnnxEmbeddingEncoder.java:339`).
- **The reranker lane is structurally absent from observability** — no `registerEncoder`, no
  status surface, DEBUG-only log (`CrossEncoderReranker.java:267,279`).
- **`batchTiming`/`enrichmentCompleted` record ONLY on the combined path**
  (`CombinedEnrichmentBackfillOps.java:515-523` is the sole caller) — in individual or
  late-chunking mode the counters freeze with no signal; `LateChunkingEmbedBackfillOps` structurally
  cannot record (no `OperationalMetrics` reference). Backfill MODE (combined/individual/late) is
  observable nowhere. Late-chunking's log line has no timing fields and is silent on
  all-deferred cycles (`LateChunkingEmbedBackfillOps.java:237-248`).
- Coverage percentages are computed live from Lucene counts (`IndexStatusOps.java:596-634`) — not
  affected by the metrics freeze — but doc-level vs chunk-level remain two unlinked percentages,
  and `vectorsReady` collapses a hardcoded 95% threshold into a boolean (`IndexStatusOps.java:628`).
- **Test seams**: `poolSpan`/`pool` (pure functions!) have zero pure-Java unit coverage — reachable
  only via a model-gated integration test that silently skips in every worktree; NO
  `LateChunkingEmbedBackfillOpsTest` exists (the mock seam exists and is proven in
  `CombinedEnrichmentBackfillOpsTest`); no test anywhere asserts an encoder path invokes
  `recordOrtCall` (the B-5 class has no regression gate); `BackfillScheduler` mode selection is
  untested. ORT native stderr still NUL-corrupts worker.log (no mitigation found).

## S-C synthesis: defect-class → design-move map (2026-07-10, main session)

One cross-cutting policy retires the dominant class, plus five scoped moves. Each move cites the
incidents it retires and its enforcement tier (structural > gate > hook > prose).

**Move 1 — Model Capability Contract (retires the 12-instance dominant class; the north star).**
Honest sizing (S-D correction #3): this is NOT a schema toggle on `ModelManifest` — today's
manifest is a five-field FILE-ROUTING record (filenames only, `ModelManifest.java:32-33`); the
capability facts live in four independently-evolved mechanisms across two modules (pooling
substring-parse in `OnnxEmbeddingEncoder:736-755`; precision filename-substring in
`DevModeVariantProbe:70,77`; dimension reactive-detection at `OnnxEmbeddingEncoder:345-347`;
context length as a bare config int with no manifest tie-in). The move is a four-mechanism
unification into one typed contract (pooling mode, trained context length, embedding dimension,
per-variant precision, prefixes, label config, tokenizer identity) PLUS authoring net-new
manifests for the lanes that have none (ner, reranker; splade/bgem3 to verify). Three rules:
(a) **no silent capability defaults** — a missing or unparseable declared fact is a WARN+degraded
today and a startup failure only in contract mode (see compatibility plan below), never a silent
guess (today's worst: absent `pooling_config.json` silently mean-pools a CLS model);
(b) **probe what the artifact can prove** — extend the existing graph-probe pattern
(`needsTokenTypeIds`, SPLADE output format — the in-repo existence proof) to dimension (one boot
probe vs today's reactive detection) and precision (ONNX tensor element types vs filename
substring); (c) **config validates against capability** — `embed.context_length` must be ≤ the
manifest's trained context; exceeding it is an explicit, logged decision.
**Backward-compatibility plan (S-D BLOCKER #1 — mandatory):** `models/onnx/ner/` and
`models/onnx/reranker/` have NO manifest today and work only via `ModelManifest.loadOrDefault`'s
silent convention fallback (`:89-96`) — an unqualified fail-fast default would hard-kill both
lanes on every existing install. Therefore: fail-fast ("contract mode") stays FLAG-GATED OFF
until tempdoc 657 ships manifests with every model distribution; until then the sole behavior
change is silent→WARN+degraded (strictly more information, zero availability change); repo-side
manifests for all lanes are authored as part of this move; the flip to fail-fast-by-default is
its own later, 657-coordinated decision. Enforcement: structural (typed contract consumed at
`InferenceCompositionRoot`) + startup validation + a CI check that every `models/**` dir carries
a manifest. Retires: F-013/INT8-on-CUDA (row 4), the CLS mid-flight discovery (13),
context-vs-capacity blindness (14), prefix surprise (15), dimension drift, manifest-absent
lanes, stale GGUF prerequisite (28). Acceptance test: an encoder swap = write one manifest;
everything downstream adapts or refuses loudly.

**Move 2 — instrumentation at the choke point (retires metrics-that-can-lie).** Recording moves
to where the ORT run happens — one wrapper at the session/Lease layer records every inference
for every lane; call sites cannot forget (the B-5 gap and this branch's `runHidden` gap both
become impossible). Register ALL lanes incl. reranker. `batchTiming` records at the scheduler
(which knows the mode), not inside one ops class; backfill mode becomes a status field; counter
units documented in the wire schema. One regression test asserts call-count parity per path.
Enforcement: structural (choke point) + an ArchUnit-style rule that no encoder invokes
`session.run` outside the wrapper.

**Move 3 — bounded tokenization + OOM-fallback parity (defuses the 686 landmine).** Apply
SPLADE's `TOKENIZE_GROUP_CHAR_BUDGET` pattern to embed (`embedBatchWithChunking` Phase 1) and
NER (`inferBatch`) — same bound, same regression-test shape (`SpladeEncoderBoundedTokenizeTest`
precedent). Give embed SPLADE's sub-batch-level OOM fallback (CPU retry of the failed sub-batch)
instead of discarding the whole 100-doc batch. Keep the typed-OOM string match in its single
choke point but add a canary test pinning the ORT message format. Enforcement: structural +
regression tests. Claim precision (S-D correction #5): the fallback preserves **coverage/count**
by construction, not bit-identical vectors — a CPU-retried sub-batch may differ numerically from
its GPU siblings (precedented: SPLADE's fallback already mixes regimes today; not a new risk,
but not "identical results" either). This is the highest-urgency safety item: the docs
themselves call it the same unexploded landmine (686 §Unverified #2, 691 seed item 4) and it
blocks raising any chunk cap.

**Move 4 — pacing gets a config surface (retires bare-literal-pacing).** `LoopPacingPolicy`
constants + `chunkSlotsPerBatch` + the BGE-M3 strays become a typed pacing config record with
env overrides and derivation comments; fixes the SPLADE gpu_mem_mb doc drift and the dead
cross-reference. Purely mechanical, no retrieval semantics. Enforcement: structural/config; the
existing `environment-variables.md` doc-sync discipline covers drift.

**Move 5 — representation-derivation discipline, LIGHT (representation-forks).** The heavy
machinery (a derivation register + gate à la execution-surfaces) is NOT yet earned — only two
instances, one being actively fixed by 691's late-chunking/§G work. Do: fix the BGE-M3
chunk-doc bug (a fork + status-machine bug, live-defect candidate); extract the duplicated
`ENTITY_*_TEXT` derivation into one helper; record the "whole-object-as-projection-of-parts"
principle + the SPLADE analogue in the search-quality register as a watch item with a named
trigger (build the register/gate only when the SPLADE instance is actually built). Enforcement:
prose+register now, structural later if the class grows.

**Move 6 — test-seam repairs (enables all other moves).** Pure-Java unit tests for
`poolSpan`/`pool` (they are pure functions today — no extraction needed, only tests);
`LateChunkingEmbedBackfillOpsTest` via the existing mock seam; a scheduler mode-selection test;
fix the model-dir walk-depth inconsistency AND the `@Tag("evidence")` invisibility (both halves
of the test-harness-fragility class — S-D correction #4) so asset-gated/evidence-tagged encoder
regression tests stop silently skipping. Adopt "every new `*BackfillOps` ships with an
escalation test using the shared pure failure helpers" as the checked convention for the
missing-failure-escalation class (700 fixed the per-doc instances; the convention prevents the
next omission). Enforcement: tests are the enforcement.

**Explicit restraint decisions (AHA / under-evidenced):**
- NO dependency-graph scheduler — implicit-scheduling-dependencies has zero observed incidents;
  the smallest honest move is declaring the ordering constraints in one commented block in
  `BackfillScheduler` when Wave 1 touches it. Revisit only if an ordering incident occurs.
- NO general token-budget batcher unification across lanes yet — lanes differ legitimately
  (SPLADE seq-buckets, NER chunking, embed windowing); Move 3 gives each lane the bound it
  needs; unify only if a fourth lane appears or the three converge naturally.
- NO SPLADE whole-doc projection build (691 §G's "candidate scope beyond embed") — per
  `structural-defects-no-repeat` critique discipline, its own evidence must demand it.
- NO wholesale rewrite of the six-path VECTOR write fan-in — the dead sync path (D.7) and dead
  fallback branch (D.2) are deletions/simplifications inside other moves, not a redesign.
- NO systemic whole-batch backoff for the combined path (the remaining missing-failure-escalation
  instance, 700 §Secondary) — 700 already evaluated and explicitly deferred it; the per-doc
  poison-pill escalation it shipped bounds the damage; revisit on an observed
  repeated-systemic-failure incident. (Named here per S-D correction #4 — deferred with a
  trigger, not silently dropped.)

## Migration waves (respects live lanes: 691 Phase 2 ships first; 702 merges; 708 restarts after)

- **Wave 0 — feeds back into 691 Phase 2 on THIS branch (before its A/B):** record `runHidden`
  into the embed profiler (else the Phase-4 A/B mis-attributes embed cost — the OFF arm records
  ORT calls, the ON arm doesn't); add timing fields + all-deferred visibility to the
  late-chunking log; `LateChunkingEmbedBackfillOpsTest`; pure-Java `poolSpan`/`pool` tests.
  Cheap, in-scope for 691's own correctness.
- **Wave 1 — independent, behavior-preserving:** Move 3 (bounded tokenization + fallback
  parity — first, it gates everything that raises embed load), Move 2 (choke-point
  instrumentation + reranker lane + mode observability; honest sizing per S-D: requires adding a
  `run()`-shaped method to `SessionHandle`/`Lease` and migrating ~6 files of call sites off raw
  `session.run` — one-time, mechanical, but not "one wrapper"), BGE-M3 bug fix + Move 6 seams.
  These touch ort-common/encoder classes, NOT the contended config files.
- **Wave 1.5 — Move 4 (pacing config) AFTER both 691 and 702 merge** (S-D MAJOR #2): its env
  wiring necessarily touches `EnvRegistry.java`/`ResolvedConfigBuilder.java` — the exact
  collision surface 691 §L-2 flagged between the two unmerged branches; landing it earlier
  creates a three-way conflict. Fold into whichever of 691/702 merges last, or land immediately
  after.
- **Wave 2 — before/with the 708 decision:** Move 1 (capability contract) — sequenced so an
  encoder swap consumes the contract rather than re-learning every capability the hard way.
  Fail-fast flip additionally gated on 657 shipping manifests (see Move 1's compatibility plan).
- **Wave 3 — evidence-gated:** Move 5's register/gate machinery only if the SPLADE projection
  instance is built; batcher unification only on a fourth lane.

Every wave lands as independent PRs judged by the existing relevance/union-recall/leak/perf
gates; none changes retrieval semantics (Move 3's sub-batch fallback preserves results by
construction — same inputs, smaller batches).

## S-D adversarial review (2026-07-10, independent subagent ≠ designer) — verdict: fit-with-corrections; corrections APPLIED above

The reviewer spot-checked all five load-bearing factual claims against source — **all five held
at file:line, none exaggerated** (silent-MEAN pooling default; unrecorded `runHidden`; unbounded
embed/NER upfront tokenization; BGE-M3 chunk-doc mis-write — "if anything, understated"; embed's
whole-batch OOM discard). Design-layer findings, all incorporated into S-C/waves above:

1. **BLOCKER (fixed in Move 1):** unqualified fail-fast would hard-kill NER/reranker on every
   existing install — both lanes have no manifest and live off `loadOrDefault`'s silent
   convention fallback. → Compatibility plan added: WARN+degraded is the sole behavior until 657
   ships manifests; fail-fast stays flag-gated.
2. **MAJOR (fixed in waves):** Move 4's env wiring recreates the exact
   `EnvRegistry`/`ResolvedConfigBuilder` collision 691 §L-2 flagged vs 702. → Re-sequenced to
   Wave 1.5, after both merges.
3. **MAJOR (fixed in Move 1):** "promote ModelManifest" undersold scope — it's a file-routing
   record today; the real work is unifying four detection mechanisms across two modules +
   authoring net-new manifests. → Honest sizing added.
4. **MINOR (fixed in Move 6 + restraint list):** missing-failure-escalation and the
   `@Tag("evidence")` fragility half had silently vanished between S-A and S-C. → Escalation-test
   convention added to Move 6; whole-batch backoff added to the restraint list with a trigger.
5. **MINOR (fixed in Move 3):** "preserves results by construction" softened to coverage/count
   (CPU-retried sub-batches aren't bit-identical; precedented by SPLADE's existing fallback).

Surviving unmodified: Move 2's architectural core (with honest ~6-file migration sizing), Move
5's restraint calibration, Move 6, Wave 0's cheapness, all Axis-1 facts.

## S-C.R — internet research addendum (2026-07-10, /research pass; post-S-D)

Two focused external passes on the design's moving-target aspects (capability-declaration prior
art; ORT memory/error mechanics + OTel conventions). Findings below AMEND the moves; primary
sources verified by the researching agents (HF file fetches, ORT source/blame, official docs).

**R-1. Move 1 should ADOPT the sentence-transformers convention as its read layer, not invent a
schema.** Verified against the actual `Alibaba-NLP/gte-multilingual-base` HF repo: it ships
`modules.json`, `1_Pooling/config.json` (**older boolean-flag schema** —
`pooling_mode_cls_token:true` — while our sidecar uses the newer `"pooling_mode":"cls"` string;
a reader must accept BOTH generations), and `sentence_bert_config.json` (`max_seq_length: 8192`,
agreeing with `config.json` `max_position_embeddings: 8192`). Per-fact verdicts:
- **Pooling / context / dimension**: read the ST files + `config.json` directly; cross-validate
  the two context sources. **Never trust `tokenizer_config.json` `model_max_length`** — for this
  exact model it says **32768** vs the real 8192 trained context (and is almost certainly where
  691 §H's unsubstantiated "32k" figure came from — source of that error now identified).
- **Precision**: NO ecosystem field is authoritative for an exported ONNX file's precision
  (`torch_dtype` describes the original checkpoint, not the export). Self-declared manifest
  field required; sanity-check against ORT `getInputInfo()/getOutputInfo()` element types (the
  Java API cannot introspect initializer dtypes, and mixed-precision graphs make I/O types a
  check, not a source of truth). This retires the filename-substring mechanism.
- **Prefixes**: `config_sentence_transformers.json` `prompts` is the designed home but is
  UNPOPULATED in practice — verified 404 for both gte-multilingual-base AND multilingual-e5-large
  (whose README prescribes prefixes in prose only). Stays our own manifest field; read the ST
  file if present, never treat absence as "no prefix".
- **Fail-closed posture is externally validated**: TEI (HuggingFace's embedding server — the
  same problem, widely deployed) ERRORS on missing/ambiguous capability facts rather than
  defaulting (TEI issue #366, gte-multilingual-base support). Move 1's rule (a) matches the
  field's chosen posture.
- **Long-term hardening (Wave 2+, optional)**: stamp capabilities INTO the ONNX file via
  `metadata_props` (reverse-DNS keys, e.g. `io.justsearch.pooling_mode`) at model-conversion
  time and read via the already-created session's `getMetadata().getCustomMetadata()` — zero
  marginal load cost, makes capabilities inseparable from weights. Precedent: GGUF ships
  `{arch}.pooling_type` / `{arch}.context_length` as first-class binary metadata. No upstream
  exporter populates this — it's our own conversion-script step (`scripts/models/build-*.py`).

**R-2. Move 3's string-match OOM detection is confirmed as THE mechanism, not a workaround.**
ORT has **no typed OOM error code** — BFC-arena exhaustion surfaces as generic `ORT_FAIL` with
the diagnostic only in message text (verified in `onnxruntime_error_code.h` + `bfc_arena.cc`).
The exact message has been **unchanged since June 2020** (git blame, commit `9790e194`) — stable
in practice, structurally unguaranteed → the canary test pinning the message format is exactly
right. Also confirmed: **no pre-flight memory-estimate API exists** (batch sizing must stay
catch-and-retry or calibrated heuristic — validates Move 3's shape and the restraint on a
memory-model batcher).

**R-3. Two arena assumptions in our code are stale or suspect (new watch-items).**
(a) `kNextPowerOfTwo` — named by three lanes' comments as the known-but-unlanded fix — is
actually the **CUDA EP default** that ORT docs pair with over-reservation risk on tight VRAM;
`kSameAsRequested` (our runtime-wide setting) trades that for external fragmentation. Neither
dominates for variable batch×seq: any strategy change is an **A/B with VRAM-headroom
measurement**, not a known fix — the three code comments should be corrected when Move 3
touches those files. (b) **Arena shrinkage, which we enable on every run**
(`SessionOptionsApplier.java:109-115`), is reported in ORT issues as unreliable for CUDA and
latency-costly (doesn't cleanly free after peak-then-small sequences) — never validated locally;
a cheap A/B candidate for whoever next holds the dev stack on throughput work.

**R-4. Move 2 metric names: adopt OTel `gen_ai.*` WITH named deviations.** The GenAI semantic
conventions explicitly cover embeddings (`gen_ai.operation.name="embeddings"`,
`gen_ai.usage.input_tokens`, `gen_ai.embeddings.dimension.count`,
`gen_ai.client.operation.duration`) and explicitly sanction in-process inference for spans
(span kind **INTERNAL** for same-process models — use that, not CLIENT). Deviations: override
the spec's histogram buckets (tuned for LLM-scale seconds, useless for sub-ms/ms encoder calls);
keep per-lane and batch-size as bespoke attributes (no spec concept). Caveat: the conventions
are **Development-tier** (repo `semantic-conventions-genai`) — adopt names for interop, don't
couple dashboards/gates to them as a contract.

**R-5. License/attribution (public-repo check).** Interop with file formats/key names
(ST config files, `gen_ai.*` names, ONNX metadata keys) carries no attribution burden — formats
and names aren't copyrightable expression; a from-scratch Java reader is clean. Attribution
applies only if code/doc text is copied verbatim: sentence-transformers/ONNX/TEI/OTel are
Apache-2.0, llama.cpp/GGUF is MIT, and **ONNX Runtime is MIT (not Apache-2.0** — corrected
assumption). Nothing in the current design copies external code.

## S-C.D — settled design (2026-07-10, /design pass; general level, not implementation)

The synthesis named the moves; this settles the component design for the two that needed it
(Moves 1 and 2), names every orphan, and records the extend-vs-replace decisions. Adjacent
tempdocs consulted: 657 (install/pack substrate SHIPPED 2026-07-02 — packs already model per-EP
variants incl. "NER INT8+FP16", and `InstallContract` records per-model selected variant),
700/702/704-708 (absorbed via S-A/691 §L).

### Move 1 settled: one capability contract, resolved once, at the existing choke point

**Shape.** A typed `ModelCapabilities` value (pooling mode, trained context length, embedding
dimension, per-variant precision, prefixes, label config, tokenizer identity) resolved ONCE per
model directory at composition time, in `ort-common` — the module that already owns
`ModelManifest` and the graph probes. Resolution extends the EXISTING single choke point
(`InferenceCompositionRoot.resolveVariant` is already where contract-path and dev-probe-path
converge); no parallel resolver.

**One manifest, not two.** Capability fields JOIN the existing `model_manifest.json` (today a
five-field file-routing record) — routing and capability are two sections of one per-model
declaration. No second sidecar format.

**Source priority per fact** (from S-C.R): manifest field → ecosystem files where authoritative
(ST `1_Pooling/config.json`, BOTH schema generations; `sentence_bert_config.json` cross-checked
against `config.json max_position_embeddings`; `prompts` if present; never
`tokenizer_config.json model_max_length`) → graph probe (token_type_ids and SPLADE output format
today; + dimension boot-probe; + I/O dtype sanity for precision) → **no default** (WARN+degraded
now; fail-fast in contract mode once packs carry manifests — the 657-gated flip).

**Consumption.** Encoders stop reading files entirely. The per-lane `Shape` records
(`EmbeddingShape` already carries poolingStrategy/maxSeqLen/needsTokenTypeIds — it IS the
capability projection, and it stays) are constructed FROM the contract by the composition root.
This is the structural change: `ort-common` becomes the single owner of "what is this model";
`worker-core` encoders become pure consumers.

**657 coordination (not duplication).** 657's `ModelPackage`/`InstallContract` own *which
variant is installed*; the capability manifest owns *what each variant is*. The manifest ships
inside the pack payload; the fail-fast flip is scheduled on that shipping event. The optional
hardening rung — stamping capabilities into the ONNX file via `metadata_props` in
`scripts/models/build-*.py` (GGUF precedent) — layers on top without changing the read API
(embedded values outrank sidecar when both present).

### Move 2 settled: recording where the run happens

A `run(...)`-shaped method on the `SessionHandle.Lease` (lane name bound at assembly time, where
`OrtSessionAssembler` already knows it) records every ORT invocation; encoders migrate off raw
`session.run` (~6 files, mechanical). An ArchUnit rule pins the invariant: no `session.run`
outside `ort-common`. Metric names per R-4 (`gen_ai.*` with named deviations). `batchTiming`
recording moves to `BackfillScheduler` (the only component that knows which pass ran); backfill
mode becomes a status field.

### Orphan list (deletion/tombstoning owned by THIS tempdoc's waves, not a later sweep)

1. `OnnxEmbeddingEncoder.detectPoolingStrategy` + its substring JSON parse (`:736-755`) — moves
   into the contract resolver; the encoder keeps only `EmbeddingShape.poolingStrategy`.
2. `EmbeddingService.loadPrefixes` + the hand-rolled `extractJsonString` parser (`:454-481+`) —
   replaced by contract prefix fields.
3. The reactive `embeddingDimension` volatile-int detection (`OnnxEmbeddingEncoder.java:74,345-347`)
   — replaced by boot probe + declaration (plus an index-compatibility check at startup).
4. `DevModeVariantProbe`'s filename-substring precision inference (`:70,77`) — the probe keeps
   file-existence duties; precision comes from declaration + I/O-dtype sanity check.
5. The bespoke `pooling_config.json` / `prefix_config.json` sidecars — read as legacy during the
   migration window, tombstoned once manifests carry the facts.
6. `BertNerInference.loadLabelMapping`'s silent hardcoded fallback (`:140-169`) — label config
   becomes a declared, loud-on-absence contract fact.
7. (Move 2) the six per-call-site `profiler.recordOrtCall` invocations across four encoder
   classes, and `CombinedEnrichmentBackfillOps`'s exclusive ownership of `batchTiming`
   (`:515-523`) — both subsumed by the choke points.

### Extend-not-replace inventory (existing design judged USABLE and kept)

`ModelManifest` (extended, not replaced) · `InferenceCompositionRoot` resolution choke point
(extended) · per-lane `Shape` records (kept as the projection layer) · the graph-probe pattern
(extended — it is the design's own best precedent) · 657's `VariantSelector`/`InstallContract`
(kept; complemented) · 700's shared pure failure helpers (kept; Move 6 makes them a checked
convention) · SPLADE's bounded-tokenize + sub-batch CPU retry (kept; Move 3 copies it to the
sibling lanes rather than inventing a new mechanism).

### Reach judgment (principles recognized, NOT built general)

**P1 — facts about an artifact travel with the artifact and are validated once, at the boundary
where the artifact enters the system** (never re-inferred at consumption sites). This is NOT a
new principle here — it is the existing SSOT / one-canonical-authority discipline (SSOT
catalogs; jseval corpus register signatures; 657's runtime-manifest closure rule "new runtime
facts must be manifest fields, CI-enforced") applied to model artifacts. Move 1 CONFORMS to
that seam rather than creating a parallel one. Candidate scope beyond this tempdoc: the
llama-server/GGUF lane — GGUF already embeds KV metadata (`{arch}.context_length`,
`pooling_type`); whether `InferenceLifecycleManager` consumes it or config-guesses is UNAUDITED
(named, not built; audit only when that lane next changes). Already-conforming: corpus
signatures. Evidence P1 earns its keep: the next encoder swap (708's candidate outcome) lands
with zero capability incidents, and the 12-instance class stops accruing. Retirement condition:
if the declaration layer itself becomes the drift source (manifest rot that validation cannot
catch), shrink declarations to the probe-impossible facts and prefer runtime probes — a
declaration that can silently lie is worse than an honest probe.

**P2 — measurement lives at the narrowest choke point the measured event passes through**;
call sites cannot forget what they never had to remember. Conforms to the existing
`CommitReason`/`CommitOps` shape (commits are already choke-pointed). Candidate scope: any
future encoder lane (free coverage); explicitly NOT tokenize-phase timing (knowledge only call
sites have — forcing it through the choke point would be the over-generalization). Evidence P2
earns its keep: zero new unrecorded-path incidents (two occurred under the per-call-site
regime: B-5, `runHidden`). Retirement condition: when a needed measurement requires context the
choke point structurally lacks, record at the call site without guilt — P2 governs the events
the choke point owns, not all measurement everywhere.

## Wave 1 — IMPLEMENTED (2026-07-11, branch worktree-710-wave1; founder-authorized via approved plan + per-stage /publish)

- **Move 3 (B1):** bounded upfront tokenization for embed + NER (`TOKENIZE_GROUP_CHAR_BUDGET`
  ported from SPLADE; live order/value-equivalence verified against the real model) — the 686
  landmine class is defused in all three lanes. Embed sub-batch OOM fallback ladder (GPU batch-1 →
  CPU last resort, testable seam) replaces whole-batch nulling. BFC-message canary test. BONUS
  bug found by the new live test: NER's tokenizer applied DJL default padding under `batchEncode`
  (construction lacked `padding:false`) — fixed.
- **Move 2 (B2):** ORT profiler recording moved to the `SessionHandle.Lease.run()` choke point
  (`OrtRunRecorder` hook bound at composition); all six lanes migrated, per-call-site
  `recordOrtCall` deleted; reranker + citation lanes REGISTERED (were absent from observability);
  ArchUnit rule `OrtRunChokePointTest` pins no-raw-`session.run` outside `io.justsearch.ort`
  (negative-checked). `batchTiming` ownership moved to `BackfillScheduler` (records whichever
  path ran — the combined-only freeze is gone); new `backfillMode` status field wired
  proto→IndexStatusOps→WorkerStatusMapper→EnrichmentProgressView (schemas + TS regenerated,
  wire gate green); counter units documented.
- **B3 (D.3 fix):** `BgeM3BackfillOps` routes chunk docs to `CHUNK_VECTOR`/`CHUNK_EMBEDDING_STATUS`
  (was writing the parent field pair — permanently blocking NER readiness under BGE-M3 + chunk
  vectors); per-doc-type failure escalation added (was silent infinite retry).
- **Move 6 remainder (B4):** one shared `ModelDirTestResolver` (testFixtures, depth-8, loud
  path-naming skips) replaced NINE divergent model-dir walkers; the `evidence`/`experiment` test
  exclusion is documented at its build-logic exclusion site; `ENTITY_*_TEXT` derivation deduped
  into `NerBackfillOps.applyEntityFieldUpdates` (D.1).

### Wave 1 validation (2026-07-11, battlefield-en-v1 pipeline, git 3e83279+)

Full unit suite green. Live pipeline run (390 docs, defaults, 691 single-pass default-on):
enrichment 3.2 docs/s (pre-Wave-1 reference C2: 3.15 — no Wave-1 throughput regression);
`backfillMode` field live on the wire (`"individual"` at end-of-run snapshot); choke-point
recording active in all lanes; relevance-gate: battlefield is un-pinned (skip) — the pinned
corpora were gate-validated in 691 Stage A. Notable side-observation: with the 691 single-pass
default-on, battlefield `vector` nDCG@10 = **1.0000** (26/26 golds at rank 1, 26 distinct
rank-1 docs — anti-crowding verified) vs its 624-era certification 0.4143; hybrid 0.9517.
The corpus's "hard" difficulty rating predates the F-031 fix — 624's difficulty calibration
for agent-utility corpora may need re-baselining (noted for the 624/704 lane, not acted on).

### New live evidence for the inventory (from 691 Stage A's A/B, 2026-07-10/11)

**The RMW-destroys-vectors incident (691 §N-5) is a LIVE instance of a previously-undeclared
invariant**: `KnnFloatVectorField` is non-stored; ANY later read-modify-write silently destroys it
(chunks get re-queued, `WritePathOps.java:471`; parents do NOT) — a separate VECTOR-writing pass
was erased by the next stage's RMW with status still COMPLETED, collapsing dense retrieval, found
only by the live A/B. Classification: this is a SEVENTH defect-class instance pattern —
"convention-maintained write-ordering invariant" (nearest S-A class: implicit-scheduling-
dependencies, which S-A had marked under-evidenced — it now has its observed incident).
Candidate design moves for Wave 2+ (NOT built): (a) declare + enforce (ArchUnit/registry: only
the bundling pass may write VECTOR), or (b) make RMW vector-preserving (re-read + re-attach,
kills the class structurally — costlier, adapters-lucene hot path). Recorded in observations
(conditions store) + F-031's structural caveat; decision belongs to the Wave-2 arc with founder
review.

## Wave 1.5 + Wave 2 — IMPLEMENTED (2026-07-11, branch worktree-710-wave2)

- **Move 4 (C1):** all 12 enrichment pacing constants → typed `justsearch.backfill.*` config
  (defaults identical, derivations kept as comments); `chunkSlotsPerBatch` named+configurable
  (691 F-1 cited); BGE-M3 strays unified; SPLADE gpu_mem_mb doc drift + dead comment fixed.
- **Move 1 (C2):** `ModelCapabilities` + `ModelCapabilityResolver` in ort-common — one resolution
  per model dir at the composition choke point (manifest capabilities → ST ecosystem files (both
  1_Pooling schema generations; sentence_bert/config.json cross-check preferring the smaller;
  tokenizer_config model_max_length never read) → legacy sidecars (deprecated readers) → WARN,
  never silent). `detectPoolingStrategy` + `loadPrefixes`/`extractJsonString` DELETED; precision
  declaration-first (filename heuristic narrowed to WARN-logged fallback); NER label fallback
  loud; declared-vs-reactive dimension cross-check; strict-mode flag default-off (657-gated per
  the S-D compatibility plan). Capability manifests authored for gte/ner/reranker/splade from
  their real source files.
- **Empirical correction to S-C.R R-1:** the "sanity-check precision against ORT I/O element
  types" idea is VOID in practice — fp16-weight exports emit fp32 OUTPUT tensors (both our fp16
  models), so the check false-positived on every correctly-declared model; REMOVED after the
  live validation caught it. Weight precision is declaration-only (+ filename legacy fallback).
- **Live validation (battlefield pipeline, fresh dist):** parity EXACT vs Wave-1 reference
  (vector 1.0000 / hybrid 0.9517, comparable=True); all 4 lanes init; the run exercised the
  FALLBACK path by design (runtime models resolve from the main checkout, which gets the new
  manifests only at merge) — fallback WARNs fired exactly as specified, retrieval unaffected.
  Post-merge acceptance check: a live boot against the merged models dir must show zero
  capability WARNs for the declared models (orchestrator TODO at publish time).

## Log

- 2026-07-10: chartered; S-A + S-B subagent surveys launched (read-only).
- 2026-07-10: all four surveys returned; condensed evidence + S-C synthesis recorded above.
- 2026-07-10: S-D adversarial review completed (verdict: fit-with-corrections); all five
  corrections applied to S-C/waves.
- 2026-07-10: S-C.R research addendum — Move 1 adopts ST-convention read layer + TEI fail-closed
  precedent + optional ONNX-embedded metadata; Move 3's string match confirmed sole mechanism
  (stable since 2020) + two stale arena assumptions flagged; Move 2 adopts `gen_ai.*` names with
  deviations.
- 2026-07-10: S-C.D settled design (/design pass) — Move 1 component architecture (one contract,
  resolved once in ort-common at the existing composition choke point; one manifest not two;
  Shape records kept as projections; 657 complemented not duplicated), Move 2 Lease-level
  recording, full orphan list (7 items, owned by this tempdoc's waves), extend-not-replace
  inventory, reach judgment (P1 conforms to the existing SSOT/manifest-closure seam — llama/GGUF
  lane named as unaudited candidate scope; P2 with explicit non-scope). Status: READY FOR S-E
  FOUNDER REVIEW. Implementation remains unauthorized.
