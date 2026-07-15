# 712 — Sparse-leg long-doc death: SPLADE 512-token truncation confirmed (F-030/F-031/F-032's sparse sibling)

- **status:** steps 1–3 implemented flag-gated default-OFF (founder approval + riders,
  2026-07-11); step 4 (live jseval A/B) ON HOLD pending GPU sequencing with tempdoc 713; step 5
  parent-splade teardown DEFERRED to 713's parent-representation verdict. See §Implementation log
  — including a mechanism correction the implementation investigation forced on the takeover text.
- **created:** 2026-07-11
- **updated:** 2026-07-11

## Charter question (seed)

Is SPLADE's near-zero contribution on long-document corpora a truncation artifact — the exact
sibling of F-030/F-031's dense window-mean death, transposed to the sparse leg — and if so,
what representation fixes it?

**Answer (this tempdoc): YES.** An offline experiment on the byte-identical legal-clerc-200
corpus confirms it decisively. Production truncated whole-doc SPLADE scores nDCG@10 **0.0539**
(reproducing the shipped 0.0591 splade-mode baseline); per-chunk SPLADE covering the whole
document with max-pool merge scores **0.3274** (6.1×) and chunk-level MaxP scores **0.5445**
(10.1×). See §Takeover experiment.

## Evidence that motivated the charter (verified, citable — from the 711 close-out)

- `splade` mode on `mixed/legal-clerc-200` scores nDCG@10 **0.059–0.0591 in every measurement**
  (tempdoc 666 first baseline; 691 §N full-mode runs; 711's 2026-07-11 gate run at b88e76e)
  while `lexical` scores 0.686–0.689 on the same queries — the sparse leg contributes ~nothing
  where BM25 thrives.
- Suspected mechanism: whole-doc SPLADE encodes only the first ~512 tokens; legal-clerc parents
  run tens of thousands of tokens. Same shape as F-030 (window-mean dilution killed dense; fixed
  by F-031/F-032 — dense now 0.618 on this corpus, sparse still dead).
- 691 §G named "SPLADE whole-doc projection" as candidate scope; 710's restraint list explicitly
  deferred it pending its own evidence. This tempdoc is that evidence pass.
- NOTE: chunk docs carry no SPLADE fields at all (711 E2 audit: `SpladeBackfillOps` has zero
  `is_chunk` handling; chunks get dense-only enrichment). *(Correct as an index-state observation,
  but the mechanism claim was refined during implementation — see §Mechanism correction: chunks ARE
  seeded `splade_status=PENDING` and the combined pass marks them COMPLETED without encoding.)*

---

## Takeover investigation

### The production sparse path is a HARD head-truncation to 512 tokens

Confirmed by source (not inference), primary-source `file:line`:

- **The encoder truncates, it does not window.** `SpladeEncoder.encode(String)`
  (`modules/worker-core/.../splade/SpladeEncoder.java:252-260`) and both batch paths
  (`:376-385`, `:457-462`) do `int seqLen = Math.min(encoding.getIds().length, maxSeqLen);`
  then `truncate(...)`. Everything past token `maxSeqLen` is discarded.
- **`maxSeqLen` default is 512.** `SpladeConfig` (`SpladeConfig.java:26-27,48`), and the shipped
  model's `model_manifest.json` / `config.json` cap `max_position_embeddings = 512`.
- **The "truncation evidence" infra only *measures* the loss, it does not fix it.**
  `SpladeTruncationEvidence` (`SpladeTruncationEvidence.java`) records how many 512-token windows
  a doc *would* need (`deriveWindowCount`) and the truncation rate — a sidecar for observability,
  never a windowing encoder. The engine has known-and-measured this truncation for a while; it has
  never spanned it.
- **Backfill encodes whole-doc parent content.** `SpladeBackfillOps`
  (`SpladeBackfillOps.java:88-95`) fetches `CHUNK_CONTENT` first, else `getDocumentContent` (parent
  body). Chunk docs are never enqueued `splade_status=PENDING` (711 E2), so in practice only whole
  parents are SPLADE-encoded — each truncated to its first 512 tokens. *(Refined during
  implementation — the enqueue claim was wrong in mechanism; see §Mechanism correction.)*

### The shipped model IS the one F-030(678) blamed

The dir `models/splade/naver-splade-v3` is a **misnomer**: `build.json` / `config.json` show it is
`opensearch-project/opensearch-neural-sparse-encoding-multilingual-v1` — the *same* multilingual
SPLADE encoder F-030(678) attributed the dead sparse leg to ("the multilingual SPLADE encoder,
same profile"). Its ONNX graph bakes in the full SPLADE activation (ReLU → +1 → log →
ReduceMax-over-seq → TopK-256), emitting `output_idx`/`output_weights` directly, so encoding with
this ONNX file *is* the production sparse vector — no activation reimplementation, no drift.

### What the prior findings did and did NOT test

- **F-031/F-032 (dense):** the dense leg had the *same* disease (whole-doc window-mean over a
  512-token window / silently-destroyed chunk vectors) and the *same* cure shape: long-context /
  chunk-level representation + preservation revived it **0.06 → 0.618**. Offline, dense chunk-CLS
  MaxP reached **0.64** (691 §M). The precedent strongly predicts a sparse analog.
- **F-030(678) (the "encoder-domain mismatch" verdict):** its elimination campaign tested query
  *shape*, gating/fusion, and a product-RAG granularity A/B (`chunk-hybrid` vs `chunk-bm25`) — the
  granularity arm used **dense + BM25** chunks, **never chunk-level SPLADE**. So 678 left
  per-chunk SPLADE unmeasured; this tempdoc fills that gap. (This *refines* F-030(678) for the
  sparse leg; it does not contradict any measurement 678 actually took.)

### Takeover experiment (the charter's "cheapest evidence")

Offline, in the 691 Phase M style, isolated from every other engine concern (no reindex, no ANN,
no fusion). Script: `scripts/jseval/experiments/splade_chunk_truncation_check_712.py` (committed).

**Reproduction:**
```
PYTHONUTF8=1 python scripts/jseval/experiments/splade_chunk_truncation_check_712.py \
  --dataset-dir datasets/mixed/legal-clerc-200 \
  --model-dir F:/justsearch-public/models/splade/naver-splade-v3 \
  --out tmp/712-splade-check --device cuda --batch-size 8
```
Artifacts: `tmp/712-splade-check/results.json`. Corpus `corpus.jsonl` sha256
`630f53764f52011daad6963ef6888c1da7e4789eeb8103dfce9661d17a5e6a02` — **byte-identical to the F-032
A/B corpus**, so condition A bears directly on the shipped splade baseline. Model
`model_fp16.onnx` on `CUDAExecutionProvider`; 198 docs / 200 queries; queries = the default
`queries.jsonl` (same file the jseval splade baseline uses).

**Conditions** (identical corpus/queries/model; only the *coverage/merge* differs):
- **A — production mirror:** encode each doc's full text; tokenizer keeps the first 512 tokens
  (right-truncate, matching `Math.min(len,maxSeqLen)` + `truncation_side:right`); top-256 sparse.
- **B — per-chunk over the whole doc:** split into 500-token/50-overlap content-token windows over
  the *entire* doc (no 8192 cap — SPLADE has no long-context limit per chunk), re-encode each
  chunk's text exactly like A, then merge. Three merges measured.

**Results (nDCG@10 / R@10 / R@100, 200 judged queries):**

| Condition | nDCG@10 | R@10 | R@100 | vs A | note |
|---|---|---|---|---|---|
| **A** truncated whole-doc (production mirror) | **0.0539** | 0.14 | 0.69 | — | reproduces shipped splade 0.0591 |
| **B_max** per-term max-pool merge → doc-level field | **0.3274** | 0.53 | 0.94 | **6.1×** | needs no new field |
| B_sum per-term sum merge | 0.0891 | 0.175 | 0.79 | 1.7× | rejected merge (see below) |
| **B_maxp** chunk-level MaxP (best chunk per query) | **0.5445** | 0.775 | 0.945 | **10.1×** | needs chunk-level sparse fields |

Per-query win/loss: B_max beats A **104–15** (81 ties); B_maxp beats A **154–6** (40 ties). The
win is corpus-wide, not a few outliers.

Truncation severity: **194/198 docs exceed 512 tokens**; median doc **6,615 tokens** → production
SPLADE sees ~**7.7%** of the median legal document. Enrichment cost: 19.1 chunks/doc; condition B
did 3,781 chunk encodes in 32.3 s vs A's 198 doc encodes in 2.7 s (~19× forward passes, ~12× wall).

### Interrogating the result (why, not just what)

1. **Fidelity anchor holds.** A's 0.0539 reproduces the shipped splade-mode 0.0591 (register
   666/691/711) to within the FeatureField-saturation-vs-raw-dot-product difference. The harness
   mirrors production's *actual* dead leg — this is the sparse analog of 691 §J-B's parent-only
   0.3403 replication. Confidence that A ≈ production: high.
2. **The gain is recall, not just reranking.** R@10 0.14 → 0.775 (MaxP), R@100 0.69 → 0.945. The
   truncated doc simply does not contain the citation-relevant terms (they live past token 512);
   chunking surfaces them. This is the truncation mechanism, established causally — not a
   correlation.
3. **Anti-dilution signature.** `sum`-merge (which would amplify boilerplate) is *worse* than
   `max`-merge, and MaxP (single best chunk per query) is best. The relevant content is in *some*
   chunk, not spread across all — the exact signature of a coverage/truncation death, and it rules
   out a "queries match chunk boilerplate" alternative explanation.
4. **Consistent with the dense sibling.** Sparse chunk-MaxP 0.545 sits just under dense chunk-CLS
   MaxP 0.64 (691 §M) — same league, both ~10× over their truncated/whole-doc counterparts. The
   multilingual SPLADE encoder *does* separate legal content at chunk granularity; the deadness was
   representation (truncation), not encoder-domain, for the part this experiment isolates.
5. **Honest ceiling caveat.** B numbers are offline exact-retrieval ceilings (no ANN, no Lucene
   saturation, no fusion), same treatment as the dense 0.64 datapoint. The engine number after
   integration will be lower; the load-bearing result is the **A/B delta**, which shares the caveat
   and survives it.

### Verdict — GO (do it now)

**This should be done, now.** The charter's cheapest evidence did not previously exist; it does
now, and it demands the build:

- The sparse leg's ~0.059 on long legal docs is **substantially a 512-token truncation artifact**,
  not (solely) encoder-domain mismatch. Fixing coverage revives it 6–10×.
- It is the **exact structural sibling** of a problem the project already decided was worth fixing
  and shipped (F-031/F-032, dense). Declining the sparse fix while having shipped the dense one
  would be inconsistent, given identical evidence shape.
- **What it displaces/duplicates:** nothing is duplicated. It *activates* the already-present but
  currently-phantom `chunk_splade`/`chunk_splade_rank` evidence keys in
  `SearchExecutor.java:67-69` and conforms to the existing dense `chunk_vector` + `chunk_merge`
  seam rather than inventing a parallel shape. It refines (does not overturn) F-030(678) for the
  sparse leg.
- **Residual for a separate lane:** the part NOT recovered here (sparse chunk-MaxP 0.545 vs lexical
  0.686) stays with the encoder-domain question (708's lane), exactly as F-031 split the dense
  deficit.

---

## Theorization

Framings and directions considered before settling the design:

- **Two integration shapes, not one.** The experiment surfaces a real fork. (a) *Doc-level
  max-merge* — keep the single parent `splade` FeatureField, but compute it by encoding the parent
  in chunks and max-pooling per term. Cheapest: no schema change, no new retrieval leg, reuses the
  existing splade query path. Ceiling ~0.327. (b) *Chunk-level sparse leg* — give chunk docs their
  own `chunk_splade` field and fuse a sparse chunk-merge leg mirroring dense `chunk_vector`.
  Ceiling ~0.545, and it *conforms to a seam that already exists* for dense. (b) subsumes (a)'s
  coverage benefit and reaches higher.
- **The system already has the shape.** Dense retrieval already runs a chunk-level leg
  (`chunk_vector`) fused via `chunk_merge`, and the search-side chunk-collapse logic already lists
  `chunk_splade` as a max-evidence key (`SearchExecutor.java:67`) and `chunk_splade_rank` as a
  min-positive-rank key (`:69`) — forward-compat scaffolding with no producer. So the sparse
  chunk leg is *less* new than it looks: much of the search-fusion vocabulary is pre-wired; the gap
  is a field + a producer + turning the phantom keys into real data.
- **Cost is a first-class tradeoff, not a footnote.** Per-chunk SPLADE is ~19× the forward passes
  on long-doc parents. The 691 register already tracks enrichment docs/s (legal enrich 1.3 docs/s);
  a 19× SPLADE multiplier on long-doc corpora is material and must be measured, not assumed benign.
  This argues for encoding SPLADE on the chunk docs that *already exist and are already
  dense-enriched* (fold into the combined RMW pass) rather than a second whole-parent chunked
  encode — reusing the chunk pipeline amortizes the cost against work already being done.
- **RMW fragility is a landmine here (F-032).** Any new non-stored `chunk_splade` FeatureField is
  exactly the class of field F-032 found silently destroyed. `fields.v1.json` now fail-fasts at
  startup on an undeclared fragile field, so the field MUST declare an `rmwPolicy` — this is a
  constraint the design inherits, not a choice.
- **Broader principle (candidate).** "Whole-document encoding of long documents dies by
  truncation/dilution; the cure is chunk-level representation + max-pool merge." It has now earned
  its keep on *both* the dense (F-031/F-032) and sparse legs of the same corpus. Named and scoped
  in §Design.
- **A null-adjacent possibility that did NOT materialize.** Had B ≈ A, the verdict would have been
  a null close routing everything to 708's encoder-domain lane. It didn't; but the residual gap
  (0.545 vs lexical 0.686) is real and is explicitly *not* claimed by this tempdoc.

---

## Design

### Shape: mirror the dense chunk leg — a chunk-level sparse (`chunk_splade`) leg fused via chunk-merge

The correct design is **not** a bespoke doc-level merge; it is to make SPLADE do what dense already
does. Dense retrieval on this codebase runs a parent `vector` leg *and* a chunk-level `chunk_vector`
leg fused through `chunk_merge` (the mechanism F-032 revived). SPLADE has a parent `splade` leg but
no chunk analog. The design adds the missing symmetric half:

1. **New field `chunk_splade`** in `fields.v1.json` — a non-stored SPLADE FeatureField on chunk
   docs, mirroring `chunk_vector`'s declaration. It MUST carry an `rmwPolicy` (F-032 fail-fast):
   `reset-status:chunk_splade_status` (mirroring the parent `splade` field's
   `reset-status:splade_status`), so an RMW that cannot preserve the sparse payload downgrades its
   status to re-enrich rather than silently zeroing it.
2. **Produce `chunk_splade` on chunk docs.** Chunk docs already exist and are already dense-enriched
   in the combined RMW pass (`CombinedEnrichmentBackfillOps`). Fold SPLADE encoding of
   `chunk_content` into the *same* per-doc bundled write (the F-032 invariant: one RMW per doc,
   never a separate pass). `SpladeBackfillOps` already reads `CHUNK_CONTENT` first
   (`:91-95`) — the plumbing to encode chunk text exists; what is missing is enqueuing chunk docs
   for sparse enrichment and writing the result to `chunk_splade` (not the parent `splade`).
3. **Fuse a sparse chunk-merge leg.** The search-side chunk-collapse keys `chunk_splade` /
   `chunk_splade_rank` already exist (`SearchExecutor.java:67-69`); wire the sparse chunk leg into
   the same `chunk_merge` collapse the dense `chunk_vector` leg uses (MaxP-style: a parent's sparse
   score is the max over its chunks). This turns the phantom keys into real data and needs no new
   fusion vocabulary.

**Reduced-scope fallback (Path 1), if chunk-level cost proves prohibitive:** compute the *parent*
`splade` field by encoding the parent in chunks and max-pool merging into the same single field —
no schema change, no new leg, reuses the parent splade query path. Ceiling ~0.327 (still 6× the
status quo). This is a strict subset of the chunk-level design and can ship first as a de-risking
step, but the chunk-level leg is the target because it reaches ~0.545 and conforms to the existing
seam. The plan sequences Path 1 as an optional early rung, Path 2 as the destination.

### What this supersedes / orphans (teardown belongs to this tempdoc)

- **Nothing is deleted outright.** The phantom `chunk_splade`/`chunk_splade_rank` evidence keys in
  `SearchExecutor.java:67-69` were forward-compat scaffolding for exactly this leg; the design
  *activates* them rather than orphaning them. If, after live measurement, the parent whole-doc
  `splade` leg adds nothing on top of `chunk_splade` (plausible — its truncated encode is the dead
  one), retiring the parent splade *encode* on chunked docs becomes in-scope teardown for this
  tempdoc, decided by the live A/B, not deferred to a later sweep.
- **The register's "SPLADE encoder-domain mismatch at any granularity" reading of F-030(678)** is
  narrowed by this tempdoc for the sparse leg; the register update (below) records the refinement.

### Public-claims discipline

Every number in this tempdoc is an **offline** measurement with a committed reproduction command
and artifact path; none is a production headline. No public-facing (README / docs/business) claim
is made or implied — the chunk-level engine number is unmeasured until implementation, and the
register row will carry the offline caveat explicitly. No compliance/certification framing.

### Design reach — principle and its retirement condition

- **Principle (named): "long-document representation death is a coverage artifact of whole-doc
  encoding; the cure is chunk-level representation + max-pool merge."** It is not SPLADE-specific
  or dense-specific — it is a property of feeding a long document through a fixed-context encoder
  and reading one whole-doc projection.
- **Where it already applies / is violated:** dense parent `vector` (fixed by F-031/F-032), sparse
  parent `splade` (this tempdoc). Candidate future scope: any *new* whole-doc encoder leg — e.g. a
  whole-doc reranker input, or a future single-vector doc embedding — should assume truncation
  death on long corpora until a chunk-level or long-context variant is measured.
- **Evidence it earns its keep:** the A/B delta on this corpus — 6–10× for sparse, 5–10× for dense.
  Two independent legs of the same corpus now confirm it; that is enough to name it, not enough to
  build generalized apparatus (per AHA, only unify what shares a reason to change — the dense and
  sparse chunk legs share the `chunk_merge` seam, which is the unification already present).
- **Retirement condition:** if a native long-context sparse encoder (no 512 cap) reaches parity
  with chunk-MaxP on this corpus, the sparse chunk-merge apparatus becomes dead weight and should
  be retired for sparse — the principle would then say "use the long-context encoder," and the
  chunk-merge scaffolding would be the thing to tombstone. Track against 708's encoder lane.

---

## Implementation plan (for founder approval — NOT yet implemented)

Plan-mode adaptation: written here as a tempdoc section for founder sign-off. No feature code,
PRs, or pushes have been created. Only the experiment script + these tempdoc/register updates are
committed on `worktree-712-sparse`.

### Context / preconditions

- Worktree `712-sparse` (branch `worktree-712-sparse`), based on current `origin/main`.
- The dense `chunk_vector` + `chunk_merge` path is the reference implementation to mirror at every
  step; read it first for each layer (field decl, combined-RMW producer, search fusion).
- F-032's one-RMW-per-doc invariant is non-negotiable: `chunk_splade` must be written in the *same*
  bundled per-doc update as the chunk's existing dense enrichment, never a separate pass.

### Steps (each with file:line targets + verification)

1. **Declare `chunk_splade` (schema, dual-copy SSOT).**
   - Add the field to `SSOT/catalogs/fields.v1.json` and the classpath copy
     `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json`, mirroring the
     `chunk_vector` entry: `type: splade`, `stored: false`, `docValues: false`,
     `rmwPolicy: "reset-status:chunk_splade_status"`, plus a `chunk_splade_status` status field
     mirroring `splade_status`.
   - Add `SchemaFields` constants (`modules/indexing/.../SchemaFields.java`) for `CHUNK_SPLADE`,
     `CHUNK_SPLADE_STATUS`, and the PENDING/COMPLETED values, mirroring the `SPLADE`/`SPLADE_STATUS`
     constants.
   - Verify: `/ssot-catalog` dual-copy sync; `node scripts/governance/run.mjs --gate wire` and the
     `check-language-agnostic-analysis` check; `./gradlew.bat build -x test`. F-032's startup
     fail-fast will reject the field if `rmwPolicy` is missing — that is the intended guard.
2. **Produce `chunk_splade` in the combined RMW pass.**
   - In `CombinedEnrichmentBackfillOps.java` (the combined embed+SPLADE+NER pass), add a SPLADE
     sub-phase for chunk docs: encode `chunk_content` and write `chunk_splade` +
     `chunk_splade_status=COMPLETED` into the same per-doc update map already built for the chunk's
     dense vector (mirror the `chunkVectorsEnabled` sub-phase; gate behind a
     `chunkSpladeEnabled` flag + `EnvRegistry` entry mirroring the dense flag).
   - Confirm chunk docs get `chunk_splade_status=PENDING` at index time where they get
     `chunk_vector` pending (the enqueue site parallel to dense chunk enrichment).
   - Verify: `CombinedEnrichmentBackfillOpsTest`-style unit test asserting a chunk doc emerges with
     a non-empty `chunk_splade` and status COMPLETED in a single RMW; a Step-0 characterization test
     that a subsequent unrelated RMW does NOT zero `chunk_splade` (the F-032 regression guard —
     this is the `audit-driven-fixes-need-test` requirement, the plan is not done without it).
3. **Fuse the sparse chunk-merge leg at search time.**
   - Wire the sparse chunk leg into the existing `chunk_merge` collapse in
     `SearchExecutor.java` (the `CHUNK_COLLAPSE_MAX_EVIDENCE_SCORE_KEYS` /
     `..._MIN_POSITIVE_RANK_KEYS` sets at `:67-69` already name `chunk_splade`/`chunk_splade_rank`),
     mirroring how `chunk_vector` participates. Route a SPLADE query against `chunk_splade` and
     collapse to parent by MaxP.
   - Verify: `SearchExecutorLegSetMatrixTest` / `SearchTraceProjector` conformance — the
     `chunk_splade` leg appears in the trace when enabled and is absent when disabled; leg-set
     matrix stays green.
4. **Live end-to-end measurement (the truth tier — replaces the offline ceiling).**
   - `jseval run --start-backend --clean` on `mixed/legal-clerc-200` with the chunk-splade flag on
     vs off; record `splade` and `hybrid` mode nDCG@10, union-recall, and the leak/relevance gates,
     plus enrichment docs/s (the 19× cost, now amortized over chunks that are already enriched).
   - This is required per `audit-driven-fixes-need-test` + `static-green ≠ live-working`: the
     offline 0.545 is a hypothesis; the engine number is truth. Compare against the offline ceiling
     and interrogate any large divergence before recording.
5. **Register + teardown decision.**
   - Update `docs/reference/search-quality-register.md`: add the live legal-clerc `chunk_splade`
     rows; settle the sparse-truncation Open Question opened below; run
     `node scripts/docs/skills-sync.mjs`.
   - Decide from the live A/B whether the parent whole-doc `splade` encode still earns its place on
     chunked docs; if not, tombstone the parent-splade encode-on-chunked-parents in THIS PR
     (teardown rides along).

### Validation summary

Compile (`./gradlew.bat build -x test`) + affected-module unit tests + `wire`/`ssot`/
`language-agnostic` gates at each schema/producer step; the F-032 non-zeroing regression test at
step 2; live jseval A/B at step 4; register + skills-sync at step 5.

### Teardown ledger

- Activate (not orphan) the phantom `chunk_splade`/`chunk_splade_rank` keys — no dead scaffolding
  left behind.
- Conditional: retire the parent whole-doc `splade` encode on chunked parents IF live A/B shows it
  adds nothing over `chunk_splade` (decided in-PR, step 5).

### Subagent orchestration note

Steps 1–3 are bounded, verifiable implementation chunks suitable for `model: "sonnet"` delegation
with an inline self-contained brief (schema mirror; producer mirror; fusion mirror) — each has a
clear dense reference and a unit-test acceptance bar. Steps 4–5 (live dev-stack A/B, register
judgment, teardown decision) are orchestrator work: shared dev stack + evidence judgment must not
be delegated.

---

## Constraints / relations (from the seed)

- Any chunk-level SPLADE fields are new non-stored `FeatureField`s → MUST declare an `rmwPolicy`
  in `fields.v1.json` (711's startup fail-fast enforces this). — **honored in step 1.**
- Enrichment cost is a first-class output: per-chunk SPLADE multiplies encode calls (~19× measured
  offline); the 691 register tracks enrichment docs/s baselines. — **measured in step 4.**
- Register: `docs/reference/search-quality-register.md` (read before, updated at close). Related
  findings: F-013 (splade quality vs BGE-M3 sparse), F-030/F-031/F-032.

---

## Mechanism correction (implementation investigation, 2026-07-11)

The pre-implementation deep-read of the enrichment and search paths falsified two mechanism claims
in the takeover/plan text above. The *finding* (truncation kills the sparse leg; chunk coverage
revives it) stands unchanged; the *integration shape* changed:

1. **`chunk_splade`/`chunk_splade_rank` are NOT phantom field keys and no new field is needed.**
   They are the chunk-sparse sub-leg's *evidence-score keys*, emitted by the existing 3-way chunk
   branch fusion (`HybridFusionUtils.fuseWithCC3(..., "chunk_", ...)` from
   `SearchExecutor.executeChunkBranchFusion`). The **entire search side already exists and is
   live**: `ChunkSearchOps.searchChunksSplade` queries the **existing `splade` FeatureField on
   chunk docs** (`is_chunk=true`-filtered, `ChunkSearchOps.java:275-312`); the parent splade leg
   explicitly excludes chunks (`TextQueryOps.java:501`); `SearchPlanner.planChunkMerge` already
   flows the query's splade weights into chunk merge (`SearchPlanner.java:278-283`); the executor
   fuses the sub-leg at `ccWeightSplade` when `pipeline.spladeEnabled` and weights are present
   (`SearchExecutor.java:607-608,837-841`). Sparse needs no `chunk_vector`-style field fork —
   FeatureFields are term postings, query-time filterable; the dense fields are split only because
   KNN graphs cannot be.
2. **Chunk docs ARE enqueued for SPLADE — the producer then silently drops them.**
   `ChunkDocumentWriter.java:179` seeds every chunk doc `splade_status=PENDING`; the combined
   pass's splade-status query picks them up (no `is_chunk` filter, parent-cache population in
   `CombinedEnrichmentBackfillOps`). But chunk docs carry `CHUNK_CONTENT`, never `CONTENT`, so
   they hit the blank-`CONTENT` early-out, which marked `splade_status=COMPLETED` **without
   encoding** — a silent data-less COMPLETED (the F-032 bug-class, this time at enrollment rather
   than RMW). That single early-out branch — not a missing field, producer, or fusion leg — is why
   the chunk-sparse sub-leg had no data.

Consequence: the fix is a **producer enrollment change behind a flag**, not a schema + producer +
fusion build. The approved plan's steps collapsed accordingly (details in §Implementation log).

## Implementation log (steps 1–3, founder-approved with riders, 2026-07-11)

**Riders applied:** (1) flag-gated default-OFF in this PR, mirroring 691's late-chunking pattern;
default-on flip is a separate evidence-gated decision. Step 4 (live A/B) ON HOLD for GPU
sequencing with tempdoc 713; when run it must record enrichment wall-clock + docs/s and a
short-doc control corpus. (2) reset-status target must be stored/docValues-backed, fail-fast test
rides with step 1. (3) parent-whole-doc-splade teardown DEFERRED to tempdoc 713's verdict.

### Step 1 — config flag (no schema change; deviation from approved plan, see rider-2 note)

- `ResolvedConfig.Rag.chunkSpladeEnabled` (record field + javadoc), resolved by
  `ResolvedConfigBuilder.buildRag()` as `resolveBoolean("rag.chunk_splade.enabled", false)`
  — **default false**; yaml contribution `putYamlBoolean("rag.chunk_splade.enabled", ...)`;
  `EnvRegistry.RAG_CHUNK_SPLADE_ENABLED` (`JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED`); documented in
  `docs/reference/configuration/environment-variables.md`.
- **Rider-2 deviation + confirmation:** the plan's new `chunk_splade` field + `chunk_splade_status`
  are NOT created — chunk sparse rides the existing `splade` field + `splade_status` bookkeeping
  (the shape the whole existing search/RMW stack already speaks). `splade_status` is
  `stored:false, docValues:true` (docValues is the half every consumer uses: the RMW reset-status
  lane reads it via `readKeywordDocValue`, the batched enrollment fetch and pending queries are
  docValues reads). The rider's substance — reset-status target must never be another fragile
  field — is enforced at startup by `FieldMapper.validateRmwPolicies` (`FieldMapper.java:199-213`:
  target must exist and be docValues-backed), with 711's fail-fast tests already covering the
  reject paths (`RmwFieldPreservationTest.startupFailFastRejects*`). Note `chunk_embedding_status`
  is `stored:true, docValues:true`; `splade_status` is docValues-only — consistent with the parent
  splade bookkeeping it extends, and sufficient for every read path in play.

### Step 2 — producer: encode chunk docs' sparse postings in the combined bundled write

`CombinedEnrichmentBackfillOps` (flag threaded as `BackfillContext.chunkSpladeEnabled`, wired from
`BackfillScheduler` via `rag().chunkSpladeEnabled()`):

- **Chunk lane** (chunk-cache pickup): alongside the dense enrollment, enroll `chunk_content` for
  SPLADE when the flag is on. Enrolls on PENDING **and on COMPLETED**: this lane's own RMW cannot
  carry postings it does not re-derive — omitting splade would destroy the data and reset-status
  it back to PENDING (711's engine), costing a destroy→re-queue→re-encode cycle; re-encoding into
  the same bundled write is strictly cheaper. FAILED is respected (poison-pill).
- **Parent lane, blank-`CONTENT` early-out** (splade-status-query pickup): when the flag is on and
  `CHUNK_CONTENT` is non-blank, enroll for encoding instead of marking COMPLETED; also re-derive
  on COMPLETED when the pass writes the doc anyway (same RMW-destroy reasoning). Flag OFF keeps
  the historical mark-COMPLETED-without-data byte-identically.
- Both lanes feed the existing Phase-3b splade encode + the existing Phase-4 single-batched-write —
  the F-032 one-RMW-per-doc invariant is preserved by construction (no new write site).

### Step 3 — fusion: zero production change (verification only)

The search side was already complete (§Mechanism correction). Evidence it works end-to-end with
data present: `RmwFieldPreservationTest.chunkSpladeSearchableAndRmwDowngradesStatus` (new) indexes
a chunk doc with sparse postings through the real Lucene runtime, retrieves it through the
production `searchChunksSplade` query (`is_chunk` filter included), then proves the F-032
no-silent-loss guard: an unrelated RMW drops the postings but downgrades `splade_status` to
PENDING for re-derivation. Worker-services' existing trace/leg-set conformance suites stay green.

### Tests added (all green)

- `CombinedEnrichmentBackfillOpsTest` (worker-services): `chunkSpladeOff_...` (pins flag-off
  byte-identical silent-COMPLETED), `chunkSpladeOn_parentLanePickup_...` (encode from
  CHUNK_CONTENT, one bundled write), `chunkSpladeOn_chunkLane_...` (CHUNK_VECTOR + SPLADE in one
  write; COMPLETED re-derived, the anti-churn rule), `chunkSpladeOn_spladeFailedRespected_...`
  (poison-pill not resurrected).
- `RmwFieldPreservationTest` (adapters-lucene): `chunkSpladeSearchableAndRmwDowngradesStatus`
  (+ `createRuntimeWithChunkSplade` catalog fixture).

### What did NOT change

- `SSOT/catalogs/fields.v1.json` (both copies) — untouched; no regen needed.
- Search execution / planner / fusion code — untouched.
- `SpladeBackfillOps` (individual mode) — untouched; it already encoded chunk docs correctly via
  its CHUNK_CONTENT-first fetch when individual mode is active (pre-existing inconsistency with
  the combined pass, now resolved on the combined side behind the flag).

### Rider-3 symmetry note (recorded instead of teardown)

Whether the parent whole-doc `splade` encode on chunked parents still earns its place once chunk
sparse data exists is the sparse half of the question tempdoc 713 is concurrently deciding for
dense (what the parent doc should carry). Do not decide it here: two representation decisions in
one week from two tempdocs risks contradiction. Revisit after 713's verdict + this tempdoc's
step-4 A/B; the candidate teardown is the truncated parent-splade encode (its 0.0539 offline
showing says it contributes ~nothing on long-doc corpora, but the short-doc control must weigh in
first).

### Step 4 (ON HOLD — do not run without explicit go)

`jseval run --start-backend --clean` on `mixed/legal-clerc-200`,
`JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED=true` vs default-off: `splade` + `hybrid` nDCG@10, union
recall, leak/relevance gates, **enrichment wall-clock + docs/s as first-class outputs** (the
offline ~19× sparse-encode multiplier is an acceptance question), plus one short-doc control
corpus (e.g. `beir/scifact`) for regression. Default-on flip is a separate, evidence-gated
decision after those numbers exist.

## Step 4 — live A/B result (2026-07-11): keep default-OFF (F-036, answers Q-017)

Two live A/B runs on `mixed/legal-clerc-200` (byte-identical corpus sha 630f5376), flag OFF vs
ON, modes `splade,vector,hybrid`, `--embedding --splade --pipeline --start-backend --clean`.

**Run 1 (confounded):** the OFF arm's fresh build came up degenerate — the whole `chunk_merge`
leg absent on every mode (vector legs `[dense, query_classification]`, no chunk leg). This is
the intermittent fresh-build anomaly (see tempdoc 717). Raw deltas were confounded by
chunk-leg liveness, so a clean rerun was done.

**Run 2 (clean — both arms health-verified `chunk_merge`-active):**

| mode | OFF | ON | flag effect |
|---|---|---|---|
| splade (isolated sparse) | 0.0591 | **0.2588** | +4.4× — real |
| vector (dense; flag-invariant control) | 0.6187 | 0.6184 | none (confirms arm comparability) |
| **hybrid (production)** | **0.5625** | **0.5592** | flat / noise-negative |
| enrichment wall | 132 s | 275 s | **+108%** |

Short-doc control (`golden/battlefield-en-v1`, flag-on): hybrid 0.9517 = its baseline, no
regression.

**Verdict:** the sparse leg genuinely revives 4.4× in isolation, but that gain is fully absorbed
by the dense + cross-encoder signals at fusion — production hybrid does not move — while
enrichment cost more than doubles. **Default stays OFF.** The flag is a corpus-specific lever;
the foundation (flag + silent-COMPLETED fix) already shipped (#145). The parent-splade teardown
(step 5) remains deferred — 713's F-035 kept the dense parent for the symmetric reason (fusion
consumes the whole-doc branch); the sparse parent question is not reopened here.

**Reproduction:** `JUSTSEARCH_RAG_CHUNK_SPLADE_ENABLED={unset|true} python -m jseval run
--dataset mixed/legal-clerc-200 --modes splade,vector,hybrid --embedding --splade --pipeline
--start-backend --clean`. Per-arm summaries + worker logs archived (scratchpad 712-ab / 712-ab2).
Health gate: a run is valid only if `per_mode.vector.pipeline_tracking.observed` contains
`chunk_merge` (else it hit the tempdoc-717 anomaly and must be rerun).
