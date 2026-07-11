---
title: "Encoder-domain fit on legal/professional text: can any locally-runnable multilingual embedding (and/or learned-sparse) model separate legal-shaped documents by content — the F-030 follow-up (dense R@10 ≤0.145 and SPLADE ≤0.165 at EVERY query shape and granularity on CLERC)"
type: tempdocs
status: "CLOSED 2026-07-11 — VERDICT: NO MODEL SWAP. Bake-off complete (42 signature-bound runs, Gate 0 PASSED at delta 0.005): the incumbent gte-multilingual-base was never domain-limited — F-030(678)'s encoder-domain-mismatch verdict is superseded in mechanism by construction defects (F-031 window-mean/missing-CLS dilution + F-032 RMW chunk-vector destruction, both shipped fixed via 691/711); no eligible candidate significantly beats the incumbent at chunk granularity (sign tests p=0.085/0.488/1.0); shipped legal vector nDCG 0.6180 captures ~96% of the offline ceiling 0.643. Registers updated (F-033 + F-030 refinement + 678 E5-D correction + inference-runtime). Full pipeline history below (takeover GO -> theorize -> design -> plan -> execute -> 691-pause -> resume-close)."
created: 2026-07-10
author: agent (Fable orchestration), filed at founder direction after the pillar-5 attribution campaign closed
category: search-quality / dense-retrieval / model-selection / inference-runtime
related:
  - 678-nl-question-query-robustness            # the attribution campaign (E5-A..E5-C-v2) whose verdict this doc picks up
  - 707-pillar1-inband-utility-corpus           # deliberately DECOUPLED consumer: 707/Branch B measures the engine as-is; this doc is NOT on the U0 critical path
  - 702-dense-fusion-score-calibration-euclidean-cosine  # gate/fusion exonerated (eval-gated, shipped PR #121) — do not re-litigate
  - 636-retrieval-buried-signal-long-documents  # staged-recall instrument + funnel-and-judge stance (D-005); the diagnostic machinery
  - 580-relevance-freeze-and-fw001-thaw         # model-stack decision history; F-006's "model swaps measure neutral" lesson and its regime limit
  - 640-engine-performance-budget-latency-throughput-footprint  # footprint/latency budget any candidate must fit
  - 657-install-modes-and-model-pack-decomposition  # model-pack tiers: candidate size directly affects install-mode economics
---

> NOTE: Noncanonical working tempdoc. STUB + investigation charter: the takeover/theorize/design
> passes expand this document; verify every inherited number against the register and the cited
> tempdocs before building on it.

# 708 — Encoder-domain fit on legal/professional text

## The question (from F-030, stated as a falsifiable investigation)

The pillar-5 attribution campaign (2026-07-10, tempdoc 678) closed with the verdict
**encoder-domain mismatch**: on `mixed/legal-clerc-200` (real case-law retrieval, the paying-ICP
document shape), the dense leg's raw pre-fusion R@10 is **0.100 / 0.145 / 0.145** across verbose /
keyword / LLM-natural query shapes, chunk granularity adds only **+3.0 pts**, and the multilingual
SPLADE encoder shows the same profile (**≤0.165** everywhere). Gate/fusion was exonerated
separately (702, eval-gated). The surviving hypothesis is the representation itself:
`gte-multilingual-base` (and `opensearch-neural-sparse-multilingual`) do not separate legal case
documents by citation-relevant content.

**This doc's question:** is that a property of *these* encoders (→ a better locally-runnable model
recovers the legs; huge headroom: lexical reaches 0.855 on the same task) or of *this task shape
for local-scale embedding models generally* (→ the honest close is a scoped claim, not a fix)?

## Standing constraints (inherit; do not re-derive)

1. **D-003 / ADR-0043 — multilingual by construction.** One uniform multilingual model, never a
   per-language or per-domain routed stack. A "legal encoder for legal corpora" router is
   unrepresentable by design; a candidate must be a *general multilingual* model that ALSO handles
   legal text, or it is ineligible.
2. **Local-first footprint (640 / 657).** Candidates must run on modest desktop hardware via the
   existing ORT path; model size hits the 657 install-tier economics directly. Record size/latency
   next to every quality number.
3. **F-006's regime limit.** "CE/encoder swaps measure neutral" was established on corpora where
   retrieval already worked (max headroom 1–4%). This is the opposite regime: the leg is *dead*
   (0.10 vs 0.855 lexical) — F-006 does not predict the outcome here and must not be cited to
   pre-empt the experiment.
4. **The eval instruments exist — reuse, don't rebuild.** legal-clerc-200 + its two committed query
   variants (`-kw`, `-llm` via `corpus-query-variant`), the staged-recall per-leg decomposition,
   and the union-recall/leak gates cover measurement. MIRACL de/fr + scifact + enron guard against
   trading away the multilingual/diverse-corpus wins (the engine's actual measured strength).
5. **Not on the U0 critical path.** 707/Branch B deliberately measures the engine as-is; nothing in
   this doc blocks or reorders the corpus → smoke → powered-run sequence. Do not let this
   investigation grow a dependency edge into 624/707.

## The cheap first experiment (charter for the takeover pass; refine, don't inflate)

An **offline encoder bake-off** on legal-clerc-200: embed the 198 docs (and/or their chunks) + the
200 queries (all three committed shapes) with 2–4 candidate models; measure raw R@10/R@100 by
exact nearest-neighbor (no engine integration, no reindex, no ANN confound). One session-class,
local-GPU-only. Candidate axes the takeover should research before picking: long-context
multilingual embedders, models with published legal/professional-domain evals (MTEB-law /
LegalBench-RAG adjacent), footprint tiers. If the best candidate's offline R@10 on this task is
still ≤~0.3, the scoped-claim branch strengthens; if any clears ~0.6 offline, the integration
design (ORT path, reindex cost, 657 tier impact, full multi-corpus regression sweep) becomes the
plan's subject.

## Outcome branches (both are legitimate closes)

- **Branch FIX:** a candidate clears the bar offline AND survives the multi-corpus regression sweep
  (scifact/MIRACL/enron non-inferiority) AND fits the footprint budget → design + plan the swap
  (which then owns reindex/migration, 657 tier updates, register re-baselining).
- **Branch SCOPE:** no candidate clears → the close is a *scoped public claim*: semantic legs
  contribute on diverse/multilingual content (measured: MIRACL 0.85+); legal-shaped retrieval is
  lexical-carried and measurably well-served (F-030: RAG chunk surface 0.68 gold-in-context in
  ~2.9 docs) — recorded in the register + fed to 659/RESEARCH.md claim scoping, and this doc closes
  without engine changes.

## Non-goals

Corpus design (707), query-side levers (678), fusion/gating changes (702 closed; D-004/D-005
stances stand), per-language or per-domain routing (D-003 forbids), late-interaction/ColPali
(deferred with revisit-triggers in the sidecar routing record — do not re-propose without the
trigger), LambdaMART (F-021; separately contingent per 702 §Downstream).

## Process contract for the investigating agent

Pipeline: **/takeover → /theorize → /design → /plan**, updating this tempdoc at each stage per the
skills' own instructions. Load `/search-quality` AND `/inference-runtime` registers before starting;
update both before closing. Hard stops: no implementation, no model downloads >1GB without listing
them first in the plan, no dev-stack takeover if contended, no spend. The takeover pass ends in an
explicit go/no-go verdict on the bake-off design before theorize begins.

---

## Takeover pass (2026-07-10, agent Fable)

**VERDICT: GO** — the offline encoder bake-off on `legal-clerc-200` is the correct cheapest decisive
experiment, it does *not* already exist, and no cheaper substitute (published benchmarks) can decide
FIX vs SCOPE. Proceed to /theorize → /design → /plan. Both outcome branches remain live. The takeover
did NOT run the bake-off (that is design-de-risking, gated on the settled protocol); it verified the
premise, feasibility, and the protocol refinements the design must adopt.

### Basis (primary-source / register-cited)

1. **The question is real and unanswered.** F-030 (search-quality register) attributes the legal
   dense/SPLADE death to **encoder-domain mismatch** only *after* eliminating the alternatives, each
   primary-source verified this pass:
   - Gate/fusion exonerated — `702` §Root-cause: the dense field is indexed EUCLIDEAN while thresholds
     assume COSINE, but **vectors are L2-normalized so EUCLIDEAN and COSINE rank identically** → the
     miscalibration is *latent*, cannot change ranking, and post-fix legal hybrid is unchanged
     (0.517≈0.521). Recalibration shipped correctness-only (PR #121). (`702` lines 23-49, 66.)
   - Query length/shape, naturalness, and doc granularity eliminated — `678` §E5-D/E5-final: dense R@10
     **0.100 / 0.145 / 0.145** (verbose / keyword / LLM-natural), chunk granularity adds only **+3.0 pts**
     (chunk-hybrid 0.710 gold-in-context), SPLADE ≤0.165 everywhere. (`678` lines 159-247.)
   The surviving hypothesis is the representation itself — exactly what a candidate-encoder bake-off
   tests. Not re-litigating 702/D-003/D-004/D-005.

2. **The cheapest decisive evidence does not exist and cannot be substituted.** There is **no offline
   embed + exact-NN instrument** anywhere in the repo: `scripts/jseval` has no torch/sentence-transformers
   (deps are `ir-measures`, `ir-datasets`, `httpx`, `scipy`; onnxruntime only as the optional `ner`
   extra — `pyproject.toml`); `ann_proof.py` is a *projection over run artifacts*, not raw NN. Published
   leaderboards (MTEB-Law, LegalBench-RAG, MMTEB/MIRACL) cannot decide this: CLERC's task shape (a
   **citing-sentence → cited-case** retrieval, `666-corpora/legal-clerc-200/recipe.json` = test split,
   single-removed/direct) is idiosyncratic and covered by no public benchmark; published numbers inform
   *candidate selection* but not the FIX/SCOPE verdict. So the bake-off is the cheapest thing that
   actually answers the question, and it is net-new.

3. **It is genuinely local-compute-only and cheap.** Corpus is regenerable via plain HTTP
   (`corpus_fetch.fetch_clerc_sample` streams `jhu-clsp/CLERC` from HuggingFace; `jseval corpus-fetch-clerc`
   registered) — nothing committed (`datasets/` gitignored, absent from both checkouts, confirmed). Query
   variants already exist (`jseval corpus-query-variant` → `kw` deterministic + `llm` reduced, PRs #123/#125).
   Scoring is `ir-measures` (already a dep). Embedding ~198 docs (+chunks) + ~600 queries (200 × 3 shapes) ×
   ~4-8 candidates on the RTX 4070 is minutes; exact NN over a 198×dim matrix is trivial numpy. **No
   dev-stack, no engine, no reindex, no ANN confound, no API spend.**

4. **It respects every standing constraint.** D-003 — candidates screened to *general multilingual*
   models only (a legal-only/English-only encoder is named-and-excluded, ineligible by construction).
   640/657 footprint — the full bundled ONNX retrieval stack is ≈3.5 GB and the mcp-lite retrieval
   download ≈2.1 GB (`657` lines 152-157, 67-68); gte-multilingual-base FP16 ≈628 MB, so any FIX
   candidate's size directly moves those tiers and must be recorded next to its quality number. F-006's
   "swaps measure neutral" regime limit does not apply (this is a *dead-leg* regime, 0.10 vs 0.855
   lexical). Instruments reused, not rebuilt.

**What it displaces / duplicates:** nothing shipped. The offline harness is a new, throwaway-or-jseval
instrument that does NOT duplicate the engine's Java/ONNX dense path and does NOT reopen 702/D-003/D-004/D-005.

### Feasibility findings the design/plan must carry (surfaced by takeover)

- **A — a small new harness is required.** No offline embed+exact-NN exists. Precedent env is
  `scripts/models/` (`requirements.txt`: `transformers`/`optimum`/`onnxruntime`/`huggingface-hub`;
  `build-embedding.py` downloads onnx-community exports). Design decides the form: (i) sentence-transformers/
  torch scratch script (simplest, per-model-correct pooling for free) vs (ii) optimum ONNX-export +
  onnxruntime (closer to the production path, more work). Candidates with an onnx-community export can run
  via onnxruntime with no torch; others need a torch scratch env.
- **B — CRITICAL anchor control.** Reproduce the **incumbent's** F-030 dense R@10 (≈0.10 verbose /
  0.145 kw/llm) offline with the EXACT production recipe — `gte-multilingual-base`, **CLS pooling,
  empty query/doc prefixes** (verified: `model_manifest.json` + `pooling_config.json` `{"pooling_mode":"cls"}`
  + `prefix_config.json` empty) — BEFORE trusting any candidate delta. If the anchor does not reproduce,
  the harness is measuring a different thing than the engine (the tempdoc's own "validate against jseval's
  hybrid number first" discipline, made concrete).
- **C — per-candidate correct recipe (fairness).** Each candidate must use its OWN pooling / instruction
  prefixes (e.g. E5 `query:`/`passage:`) / normalization. A wrong recipe reads as a false negative.
- **D — both granularities.** F-030's dilution finding (97% of CLERC docs chunked, median 28.5k chars)
  means the bake-off must test whole-doc AND chunk-level, mirroring the +3.0-pt granularity result — else
  it under-measures candidates.
- **E — stage the regression guard.** The scifact / MIRACL-de/fr / enron non-inferiority sweep belongs
  to **Branch FIX** design, not the initial legal screen: only a candidate that clears legal needs it,
  and it is cheaper offline-approximate first, engine-authoritative later.
- **F — download hygiene.** Some candidates exceed 1 GB (e.g. BGE-M3 ≈2.2 GB) → must be **listed in the
  plan first** (hard constraint); staged under a `tmp/` scratch dir, never committed to the LFS `models/` tree.
- **G — thresholds stand, made relative by the anchor.** Keep the tempdoc's ≤~0.3 → SCOPE / ≥~0.6 →
  integration bands; with the anchor at ≈0.10, any candidate lifting legal dense R@10 to ~0.5+ while
  holding multilingual is already a strong FIX signal.

**Cheapest evidence that would invalidate the need:** if a quick published-number scan showed every
eligible Apache/MIT multilingual embedder already reports weak legal *citation-retrieval* — but no such
benchmark exists for this task shape, which is *why* the bake-off is warranted rather than skippable.

**Candidate shortlist:** an internet research pass (general multilingual, permissive-license, ONNX-availability,
legal/MMTEB evidence) is in flight and will seed /theorize; candidate selection is theorize/design material,
not a gate on this GO verdict.

---

## Theorization pass (2026-07-10, same session)

### The candidate landscape (internet research pass, 2026-07-10)

A third-party benchmark exists that is *close* to this doc's question: **MLEB** (Massive Legal
Embedding Benchmark, Isaacus, arXiv:2510.19365 — 10 expert-annotated legal retrieval datasets,
NDCG@10). It is not CLERC's citation-shape task and is English-heavy, so it ranks *candidates*, it
does not decide FIX/SCOPE. The eligible shortlist it produces (all general multilingual; license and
ONNX-path from the research pass; sizes are fp16 estimates):

| Candidate | Params / dim / ctx | fp16 | License | ONNX | Legal signal (MLEB, 3rd-party) |
|---|---|---|---|---|---|
| `Qwen/Qwen3-Embedding-0.6B` | 0.6B / 1024 (MRL) / 32k | ≈1.2 GB | Apache-2.0 | community only | **77.13** (rank 11) — best eligible |
| `Snowflake/snowflake-arctic-embed-l-v2.0` | 568M / 1024 / 8192 | ≈1.1 GB | Apache-2.0 | **official** | 74.08 |
| `Snowflake/snowflake-arctic-embed-m-v2.0` | 305M / 768 / 8192 | ≈610 MB | Apache-2.0 | **official** | 73.94 |
| `BAAI/bge-m3` (dense path) | 568M / 1024 / 8192 | ≈1.1 GB | MIT | community (dense only) | 69.44 |
| `intfloat/multilingual-e5-large` | 560M / 1024 / **512** | ≈1.1 GB | MIT | community | 68.11 (instruct variant) — weakest |
| `ibm-granite/granite-embedding-278m-multilingual` | 278M / 768 | ≈556 MB | Apache-2.0 | **official** | unverified (only English-R2 siblings scored) |
| Incumbent `gte-multilingual-base` | 305M / 768 / 8192 | 628 MB | Apache-2.0 | shipped | **unverified on MLEB** — no published number |

Named-and-excluded (cite as considered): `jinaai/jina-embeddings-v3` (CC-BY-NC-4.0) and v4 (Qwen
Research License; MLEB 78.62) — license-ineligible; `voyage-law-2` (MLEB 79.63) and Isaacus Kanon 2
(MLEB **86.03**, #1) — legal-specialized AND proprietary-API, doubly ineligible under D-003;
`Alibaba-NLP/gte-Qwen2-1.5B-instruct` (Apache but no ONNX path found, ≈3.3 GB fp16, stretch tier
only); granite English-R2 + legal-BERT lineage (`nlpaueb/legal-bert`, `law-ai/InLegalBERT`) —
English-only/legal-only, D-003-ineligible.

Two structurally interesting facts in that table: **(a)** `arctic-embed-m-v2.0` is *built on the
incumbent's own architecture* (mGTE, same 305M/768 class) — the cleanest "same architecture, different
training" A/B; if IT lifts legal where the incumbent doesn't, the mismatch is training-distribution,
not capacity. **(b)** the legal-specialized proprietary models (Kanon 86.0, voyage-law 79.6) sit only
~9-12 pts above the best *eligible* generalist (Qwen3 77.1) on MLEB — the "you need a legal encoder"
premium is measurable but not categorical, which keeps Branch FIX plausible for a generalist.

### Alternative framings considered (kept or discarded)

1. **"Task-shape limit" framing (supports Branch SCOPE).** CLERC's query IS a citing passage — long,
   in-register legal prose whose *lexical* overlap with the gold case is enormous (BM25 0.855,
   monotonic in verbosity, F-030). "Which specific precedent does this sentence cite" is plausibly an
   *identity/entity*-matching task, not a *topicality* task — and single-vector cosine topicality may be
   the wrong geometry regardless of encoder quality. This framing predicts: every candidate improves
   modestly, none approaches lexical → exactly the ≤~0.3 SCOPE band. The bake-off distinguishes it cleanly.
2. **Instruction-conditioned embedding as a *uniform* lever (kept, one extra condition).**
   Qwen3-Embedding and arctic accept task instructions. A single, corpus-independent query instruction
   is NOT per-domain routing (one uniform recipe — D-003 bucket-C), and costs one extra bake-off
   condition for instruction-capable candidates. A *per-corpus* instruction switch WOULD be routing →
   forbidden; only a globally-uniform instruction could ever ship.
3. **Learned-sparse candidates (mostly discarded).** The title says "and/or learned-sparse", but the
   eligible multilingual learned-sparse field is effectively one model deep (the incumbent opensearch
   multilingual-v1); no alternative with legal evidence surfaced. BGE-M3's sparse head is the one cheap
   rider (same forward pass as its dense probe). Decision: dense-first screen; M3-sparse optional; a
   dedicated SPLADE bake-off is NOT warranted by the field's thinness.
4. **Reranker-recovery framing (kept as a metric, not a candidate).** The engine is funnel-and-judge
   (D-005): a dense leg that gets gold into the *CE window* is useful even at weak R@10, because the CE
   may rank it. The bake-off must therefore record **R@100 and R@20**, not only R@10 — a candidate at
   R@10 0.3 / R@100 0.7 is a different (partially recoverable) animal than 0.3 / 0.35. This bounds what
   "recovers the leg" means: the leg's job is candidate supply.
5. **Scale-stress condition (kept, cheap).** F-029: dense R@10 decays 0.10→0.03 from 198→4k docs and
   `corpus-fetch-clerc --n-docs 4000` exists. Any candidate clearing the 198-doc screen gets re-scored
   against the 4k distractor pool offline (same query embeddings + more doc vectors; exact NN stays
   trivial). Without this the screen over-states real-corpus performance.
6. **Fine-tune-the-incumbent framing (discarded).** A legal-tuned artifact IS a per-domain lever in
   spirit (D-003), adds training infra, and answers "can we specialize", not this doc's question. Out.
7. **"Bigger model" framing (bounded, not discarded).** The ≥1.5B tier (gte-Qwen2, jina-v4-class) hints
   the legal gap narrows with scale — but ≈3+ GB fp16 breaks 657 tier economics (today's whole bundled
   ONNX stack ≈3.5 GB) and the missing ONNX path breaks the ORT runtime. The eligible ceiling is the
   ~0.6B / 1.2 GB tier; if even that stays ≤~0.3, the SCOPE claim gains its honest size-axis caveat:
   "at locally-shippable scale".

### Hidden assumptions the design must not paper over

- **Offline exact-NN ≈ engine dense leg** — only if the anchor control (takeover §B) reproduces
  F-030's 0.10/0.145 with the production recipe. If offline comes out materially *higher* than the
  engine's leg, the delta is engine-side (ANN/truncation/pooling drift — a 639-adjacent finding, not
  this doc's), and candidate deltas would inherit the confound.
- **Chunk-probe fidelity.** Production chunking is 500-token windows with 50-token overlap
  (`ChunkDocumentWriter`); the offline chunk condition must mirror that, not invent its own.
- **MLEB rank ≠ CLERC rank.** MLEB is statute/QA-shaped and English-heavy; CLERC is citation-shaped.
  Ordering may not transfer — which is why all shortlisted candidates run, not just the MLEB-top one.
- **Community-ONNX fidelity** (Qwen3-0.6B has no first-party export). For the bake-off, run candidates
  in torch/sentence-transformers (recipe-correct by construction) and treat ONNX-parity as a Branch-FIX
  integration concern — don't let export bugs contaminate the science.
- **n=200 statistics.** Adjacent-candidate deltas of a few points won't be significant; decision bands
  stay coarse (≤0.3 / ≥0.6), and any "clear winner" claim needs a paired bootstrap or sign test.

### Broader shape this points at (recorded, not designed)

If Branch SCOPE closes this doc, the scoped claim ("semantic legs contribute on diverse/multilingual
content; legal-shaped retrieval is lexical-carried and measurably well-served") becomes a *public
product claim* (659/RESEARCH.md) — the first instance of a **capability-boundary statement** derived
from measurement rather than marketing. The reusable shape: any future corpus-family dead-leg finding
should land as a scoped claim + register finding, not silent scorecard variance. One instance so far —
name it, don't build a framework (AHA/C-018).

---

## Design (2026-07-10, same session) — the settled bake-off protocol

### The production recipe, pinned from source (what the harness must mirror)

The engine's whole-doc dense vector is NOT a single forward pass. Verified in
`modules/worker-core/.../embed/onnx/OnnxEmbeddingEncoder.java`:

- max context: `EmbeddingConfig.contextLength` default **2048** tokens (`EmbeddingConfig.java:32-33,48`);
- docs ≤ maxSeqLen embed in one pass (`OnnxEmbeddingEncoder.java:164-167`); longer docs are split into
  **512-token windows, 128-token overlap** (`chunkSize = Math.min(512, maxSeqLen)`, `chunkOverlap = 128`
  — `:100-101`, `createChunks` `:525-530`), each window embedded + pooled, then the whole-doc vector is
  the **unweighted mean of the window vectors** (`embed()` `:170-179`, `meanPoolChunks` `:567-587`);
- pooling **CLS** (per `models/onnx/gte-multilingual-base/pooling_config.json`), **no query/doc prefixes**
  (`prefix_config.json` empty), L2-normalized output.

CLERC docs (median 28.5k chars ≈ 7k+ tokens) therefore *always* take the window-mean path — ~15+
window vectors averaged per doc. This IS the "maximal mean-pool dilution" F-030 names, now concrete.
It also sharpens what the bake-off can learn: a **native-long-context single-pass condition**
(condition W2 below) separates "the encoder can't represent legal text" from "window-mean pooling
destroys whatever it represents" — two different SCOPE claims and two different FIX designs.

### Instrument: a committed, standalone offline harness (new, experiment-grade)

- **Form:** one Python script + a small results-schema, committed at
  `scripts/jseval/experiments/encoder_bakeoff_708.py` (precedent: `scripts/jseval/experiments/` exists
  for one-off experiment scripts; `scripts/models/` is the model-build env precedent). It is **not**
  part of the `jseval` package and adds **no dependency to jseval's `pyproject.toml`** — it runs in its
  own scratch venv (`torch` + `sentence-transformers` + `ir-measures` + `numpy`), models cached under a
  `tmp/` scratch dir via `HF_HOME` override — never under the LFS-tracked `models/` tree.
- **Runtime choice — torch/sentence-transformers, deliberately NOT ONNX:** per-candidate recipe
  correctness (pooling/prefix/instruction/normalization) comes from each model's own ST config, which
  removes the false-negative class that a hand-rolled per-model ONNX pipeline would create. ONNX export
  fidelity is a **Branch-FIX integration concern**, not a science concern — measured later, only for the
  winner. (One exception: an optional secondary anchor cross-check may run the shipped
  `models/onnx/gte-multilingual-base` file via onnxruntime to bound torch-vs-ONNX drift.)
- **Inputs (all reused, none rebuilt):** corpus regenerated by `jseval corpus-fetch-clerc --name
  legal-clerc-200 --seed 666 --n-queries 200` and **verified against the register signature**
  `90d4300d1435c6af00950b6095100fc6b29260385b294dc76896d54308bcfaf1` via
  `jseval.corpus_identity.corpus_signature()`; keyword variant regenerated by `jseval
  corpus-query-variant ... keyword` (deterministic, backend-free). The `-llm` shape is **dropped from the
  decision matrix**: it needs a live LLM backend (contention risk) and F-030 measured it identical to
  keyword for dense (0.145 = 0.145) — not decision-bearing. Scoring via `ir_measures` (already the
  project's metric library). Exact NN = brute-force cosine over L2-normalized vectors (numpy matmul; 198
  docs is trivial, 4k still trivial).
- **Outputs:** one JSON per (model × condition) under `tmp/eval-results/708-bakeoff/` carrying model id
  + revision hash, recipe (pooling/prefix/instruction/window params), corpus signature, metrics
  (R@10/R@20/R@100, nDCG@10), per-query gold ranks (for paired tests), wall-clock + device. Summary
  tables land in THIS tempdoc with run pointers. **Deliberately NOT written into the register's baseline
  schema** — the register's Canonical Baselines stay engine-authoritative (jseval runs only); this
  harness is a pre-engine screen, a projection for a decision, not a second authority (553/D-005
  anti-fork discipline). Register gets a *finding* (F-xxx), not baseline rows.

### Conditions matrix

**Candidates (7 + anchor):** the theorization shortlist — Qwen3-Embedding-0.6B, arctic-embed-l-v2.0,
arctic-embed-m-v2.0, bge-m3 (dense; sparse head recorded as a rider), multilingual-e5-large,
granite-embedding-278m-multilingual — plus the **incumbent gte-multilingual-base as anchor**. Eligibility
was screened 358-style (hard requirements): general-multilingual (D-003), Apache/MIT, locally runnable,
≤~1.2 GB fp16.

**Query shapes (2):** verbose (committed CLERC citing-sentence) + keyword (deterministic kw variant).

**Doc-side conditions (3):**
- **W1 — production-mirror whole-doc:** 512-token windows / 128 overlap / unweighted mean / per-model
  pooling — the F-030-comparable condition (for the anchor this MUST reproduce R@10 ≈ 0.100 verbose /
  0.145 kw: Gate 0).
- **W2 — native-long-context whole-doc:** one forward pass at the model's real context (2048 for the
  anchor, 8192 arctic/bge-m3, 32k Qwen3), truncate beyond. Isolates window-mean dilution from encoder
  representation quality.
- **C — chunk-level MaxP:** 500-token chunks / 50-token overlap (mirroring the *index-side*
  `ChunkDocumentWriter` granularity), doc score = max chunk similarity. The F-030 chunk-condition analogue.

**Extra conditions:** instruction-capable candidates (Qwen3, arctic) run W-conditions ± one **uniform**
task instruction (identical string for all corpora — D-003 bucket-C; a per-corpus instruction would be
routing and is not on the table). BGE-M3's sparse head rides along in the same forward pass where cheap.

**Scale stress (survivors only):** re-score clearing candidates against the 4k distractor pool
(`corpus-fetch-clerc --n-docs 4000`, recipe committed as `666-corpora/legal-clerc-4k`) — F-029 measured
the incumbent decaying 0.10 → 0.03 at 4k; a FIX candidate must not collapse the same way.

**Multilingual non-inferiority screen (survivors only, offline-approximate):** same harness on
`beir/scifact` (ir_datasets) + MIRACL-de/fr samples (recipes committed, `corpus-fetch-miracl`) — a
candidate that lifts legal but drops MIRACL is a D-003 regression and exits. Engine-authoritative
confirmation (real jseval runs with the model swapped in) is explicitly **Branch-FIX follow-on work
with its own go-ahead**, not this doc's execution.

### Decision protocol (Gate 0 first, then bands)

1. **Gate 0 — anchor validity:** incumbent W1-verbose R@10 must land within **±0.05 of 0.100** (and W1-kw
   near 0.145). Failure high (offline ≫ engine) = the engine leg is losing recall somewhere downstream of
   the representation (ANN/truncation/backfill — a 639-adjacent finding worth its own routing); failure in
   any direction stops the bake-off until explained. No candidate delta is interpretable before Gate 0.
2. **SCOPE band:** best candidate, best condition, R@10 ≤ **~0.3** at 198 docs → Branch SCOPE closes the
   doc (scoped public claim + register finding). With the W2 condition present, the claim gains its
   precise form: representation-limited vs pooling-limited.
3. **FIX band:** any candidate R@10 ≥ **~0.6**, OR R@100 ≥ **~0.85** with R@10 ≥ ~0.4 (the funnel-and-judge
   argument: the leg's job is CE-window candidate supply, D-005) → proceed to scale stress + multilingual
   screen + footprint/latency table → Branch FIX plan.
4. **Gray zone (0.3–0.6):** not auto-decided. Surface to founder with the full evidence set (R@100
   recoverability, 4k stress, W2-vs-W1 split, footprint delta) — both branches remain legitimate closes.
5. **Statistics:** adjacent-candidate ordering claims require a paired sign test (or bootstrap) over
   per-query gold ranks; band membership (coarse) does not.

### What this design supersedes / orphans

Nothing shipped. No engine code, no jseval package change, no register-baseline rows. The harness is a
new, committed, experiment-grade instrument; if Branch FIX later ships a swap, the harness remains the
reproducible record of why. The only deletion candidate this doc could ever create is in Branch FIX
(the incumbent model dir + 657 registry entries) — owned by that future plan, not now.

### Reach judgment

- **Conforms to (not parallel to):** the 358 model-search pattern (hard-requirements screen → quality
  gate → single winner), the 553/D-005 one-authority rule (harness = projection/screen, jseval = truth),
  the 623 verified-binding pattern (corpus signature recorded in every result artifact), and F-030's
  own validate-the-probe-first discipline (Gate 0).
- **Principle worth naming: "offline representation screen before engine integration."** Any future
  encoder/reranker candidate question should get a recipe-correct offline screen with an anchor-reproduction
  gate before any engine-side swap work. *Earning its keep:* the screen's verdict later agrees with an
  engine-authoritative jseval run on the same corpus (Gate 0 + one Branch-FIX confirmation would be the
  first datapoint). *Retire when:* offline screens disagree with engine-authoritative results twice —
  then only engine sweeps are trustworthy and the screen is a false economy.
- **Second principle (from theorize, now with a home): capability-boundary claims.** A measured dead
  leg on a corpus family closes as a scoped public claim + register finding. Applies to any future
  domain-shaped collapse; no framework until a second instance exists.

---

## Implementation plan (2026-07-10, same session) — all remaining work

Execution note: everything below is local-compute-only, no dev-stack requirement, no engine change.
GPU use yields to any contending session (run CPU or wait). The `-llm` query shape is out of the
decision matrix (design §Conditions); nothing here starts the dev stack.

### Declared model downloads (hard-constraint disclosure — >1 GB items listed BEFORE any download)

All fetched transiently into `tmp/708-bakeoff/hf-cache/` (gitignored scratch; deleted at close), never
into the LFS `models/` tree, never committed. Approximate published-weight sizes:

| Download | ~Size | >1 GB? |
|---|---|---|
| PyTorch wheel (cu12x or cpu) for the scratch venv | 2–2.7 GB | **yes** |
| `Qwen/Qwen3-Embedding-0.6B` | ~1.2 GB | **yes** |
| `Snowflake/snowflake-arctic-embed-l-v2.0` | ~1.1–2.3 GB | **yes** |
| `BAAI/bge-m3` | ~2.3 GB | **yes** |
| `intfloat/multilingual-e5-large` | ~1.1–2.2 GB | **yes** |
| `Snowflake/snowflake-arctic-embed-m-v2.0` | ~0.6–1.2 GB | borderline |
| `ibm-granite/granite-embedding-278m-multilingual` | ~0.6 GB | no |
| `Alibaba-NLP/gte-multilingual-base` (anchor, torch weights) | ~0.6–1.2 GB | borderline |
| CLERC corpus stream (transient HTTP, `corpus-fetch-clerc`) | collection-stream, GB-scale worst case | transient data, not a model |

Total scratch budget ≈ 12–15 GB disk. Anchor ONNX cross-check uses the already-shipped
`models/onnx/gte-multilingual-base/model.onnx` (resolves from the main checkout) — no download.

### Phase 0 — environment (bounded, throwaway)

1. Create `tmp/708-bakeoff/venv` (Python ≥3.11); install `torch`, `sentence-transformers`,
   `ir-measures`, `numpy` (+ `onnxruntime`, `tokenizers` for the anchor cross-check). `HF_HOME` →
   `tmp/708-bakeoff/hf-cache`.
2. jseval invoked with `PYTHONPATH=<worktree>/scripts/jseval` (the known Windows worktree pitfall —
   CLAUDE.md Common Pitfalls) for `corpus-fetch-clerc` / `corpus-query-variant` / `corpus_identity`.
3. GPU check: if another session holds the GPU (dev stack up / VRAM busy), run CPU or defer — never
   contend.

### Phase 1 — data (reuse committed recipes; verify identity)

1. `jseval corpus-fetch-clerc --name legal-clerc-200 --seed 666 --n-queries 200` → verify
   `corpus_signature() == 90d4300d…baf1` (register). **Abort on mismatch** (measuring a different corpus).
2. `jseval corpus-query-variant --dataset mixed/legal-clerc-200 --variant keyword` → the `-kw` queries.
3. Defer the 4k fetch (`--n-docs 4000`, recipe `666-corpora/legal-clerc-4k`) until a survivor exists.

### Phase 2 — harness (`scripts/jseval/experiments/encoder_bakeoff_708.py`, committed)

1. Recipe registry per model: HF id + revision pin, pooling, prefixes/instruction strings, max ctx,
   normalization — anchor entry hard-codes the production recipe (CLS, no prefixes, 2048 ctx,
   512/128 window-mean per design §pinned).
2. Conditions W1 (production-mirror window-mean), W2 (native-long-context single pass), C (500/50
   chunk MaxP); query shapes verbose + kw; exact-NN cosine (numpy); metrics R@10/R@20/R@100 + nDCG@10
   via `ir_measures`; per-query gold ranks for paired sign tests.
3. Result JSON per (model × condition): recipe, corpus signature, metrics, ranks, device, wall-clock,
   encode throughput (docs/s — feeds the 640-style footprint/latency table).
4. Smoke: 5-doc/5-query self-check (signature validation, shape checks, rank determinism) before any
   full run. No jseval-package edits, no new jseval deps.

### Phase 3 — Gate 0 (anchor reproduction; blocks everything downstream)

1. Run incumbent anchor: W1 × {verbose, kw}. **Accept:** R@10 within ±0.05 of F-030's 0.100 (verbose)
   and 0.145 (kw).
2. Optional drift bound: same texts through shipped `model.onnx` (onnxruntime CPU, FP32) on a 30-doc
   subsample; cosine(torch, onnx) ≥ 0.99 expected.
3. **On failure:** stop; diagnose direction (offline ≫ engine → engine-side recall loss, route as its
   own 639-adjacent finding; offline ≪ engine → harness recipe bug). No candidate numbers are reported
   from a failed-gate harness.

### Phase 4 — candidate screen (the bake-off proper)

1. Six candidates × {W1, W2, C} × {verbose, kw}; + uniform-instruction variants for Qwen3/arctic;
   + bge-m3 sparse rider where cheap. Serial on one GPU (parallelism is pointless on a single device;
   no subagent orchestration for compute — a `sonnet` implementation subagent MAY draft harness
   boilerplate, but runs and evidence judgment stay in the implementing session).
2. Record per-model footprint (fp16 est.) + throughput next to every quality number (640/657 duty).
3. Apply decision bands (design §Decision protocol): SCOPE ≤~0.3 / FIX ≥~0.6 (or R@100≥0.85 ∧ R@10≥0.4) /
   gray 0.3–0.6 → founder.

### Phase 5 — survivors only

1. 4k scale stress (fetch `legal-clerc-4k`; re-embed 3.8k distractors; same queries).
2. Offline multilingual non-inferiority: `beir/scifact` (ir_datasets) + `mixed/miracl-de-2k` /
   `mixed/miracl-fr-2k` (committed recipes; signatures `d6f4026b…` / `a145edfa…`). Non-inferiority =
   candidate within noise of anchor on all three (paired sign test) — a legal win that costs MIRACL exits.

### Phase 6 — verdict + closure (both branches legitimate)

1. Write results tables + run pointers into this tempdoc; declare SCOPE / FIX / gray-zone-referral.
2. **Update both registers before close** (process contract): search-quality register — new finding
   (encoder bake-off result; answers this doc's question; W2-vs-W1 tells representation-limited vs
   pooling-limited) + close/annotate the F-030 follow-up pointer; inference-runtime register — only if a
   runtime-relevant fact surfaced (e.g. candidate throughput/ONNX-fidelity findings).
3. **Branch SCOPE:** draft the scoped public claim (semantic legs contribute on diverse/multilingual —
   MIRACL 0.85+ hybrid; legal-shaped retrieval is lexical-carried and served — F-030 RAG 0.68
   gold-in-context in ~2.9 docs) for 659/RESEARCH.md wording approval (founder). Public-claims lane rules
   apply: every number must trace to a run pointer.
4. **Branch FIX:** write the follow-on plan STUB (do not execute): engine-authoritative jseval sweep with
   the winner swapped in (build via `scripts/models/build-embedding.py` path + ONNX export parity check),
   full register-baseline re-run, reindex/migration cost, 657 tier-economics delta, D-003 conformance
   note. **Explicit founder go-ahead required** before any of it.
5. Teardown: delete `tmp/708-bakeoff/` scratch (models + venv); `git status` guard that no model files
   entered the tree; harness + results-summary stay committed.

### Validation summary

Gate 0 anchor reproduction (the harness's own correctness proof against a register-recorded engine
measurement), corpus-signature verification on every dataset, smoke self-checks, paired sign tests for
ordering claims, and register cross-checks. No UI surface; no dev-stack validation needed by design.

## Execution log (2026-07-10, founder go-ahead received — same session)

### Phases 0–3: environment, data, harness, Gate 0 — COMPLETE

- **Phase 0:** scratch venv `tmp/708-bakeoff/venv` (Python 3.12.13; torch 2.13.0+cu126,
  `cuda.is_available()=True`; transformers 4.57.6; sentence-transformers 5.6.0; ir-measures; numpy).
  `HF_HOME=tmp/708-bakeoff/hf-cache`. GPU free apart from desktop load (~2.5 GB/12 GB) — no contention.
  All 7 model snapshots downloaded to scratch (two needed an `HF_HUB_DISABLE_SYMLINKS=1` retry — Windows
  symlink privilege; nothing entered `models/` or git).
- **Phase 1:** `jseval corpus-fetch-clerc --name legal-clerc-200 --seed 666 --n-queries 200` → 198 docs /
  200 queries; `corpus_signature()` = `90d4300d…baf1` — **MATCH** with the register (byte-identical corpus
  to the one F-030 measured). `corpus-query-variant --variant keyword` → `legal-clerc-200-kw` (top_k=8,
  0 fallbacks — same construction as F-030's kw shape).
- **Phase 2:** harness committed (`scripts/jseval/experiments/encoder_bakeoff_708.py`, commit 4655adf).
  Smoke (12 docs, anchor, GPU): runs end-to-end, non-degenerate ranking.
- **Phase 3 — GATE 0 PASSED.** Anchor (gte-multilingual-base, exact production W1 recipe: raw id windows
  512/128 + tail-merge, CLS per window, per-window L2, unweighted mean, L2):

| Anchor condition | R@10 | R@20 | R@100 | nDCG@10 | F-030 engine value | Δ |
|---|---|---|---|---|---|---|
| W1 verbose | **0.105** | 0.235 | 0.775 | 0.0611 | R@10 0.100 (dense leg) | +0.005 |
| W1 kw | **0.150** | 0.230 | 0.625 | 0.0692 | R@10 0.145 | +0.005 |

  Both within ±0.05 (actual Δ = 0.005). The offline nDCG@10 0.0611 also matches the register's
  engine-measured `vector`-mode 0.060 (666 baseline) to within noise — triple-consistency. The harness
  measures what the engine measures; candidate deltas are interpretable. (Run JSONs:
  `tmp/eval-results/708-bakeoff/anchor_W1_{verbose,kw}.json`, corpus-signature-bound.)

  Early observation (not a verdict): anchor R@100 0.775 verbose — the gold IS in the incumbent's top-100
  for ~78% of queries; the death is concentrated at the top of the list, which the funnel-and-judge
  frame (the R@100/R@20 metrics) was designed to see.

### Phase 4 — PAUSED-INCOMPLETE (founder directive, 2026-07-10: 691 coordination)

**Status: the screen was paused mid-run by founder directive** (see §691 coordination below). The
in-flight job was additionally killed when the GPU became contended by an unrelated local workload
(an image-gen process at 11/12 GB, 100% util) — per charter, this lane yielded; nothing was relaunched.
All completed runs below are corpus-signature-bound (`90d4300d…baf1`, byte-identical to F-030's corpus);
run JSONs live in `tmp/eval-results/708-bakeoff/` (one per row, with per-query gold ranks).

**Measured (20 runs; verbose and kw cells are R@10 / R@20 / R@100 / nDCG@10; docs/s = doc-side encode
throughput, RTX 4070 fp16):**

| Model | Cond | verbose | kw | docs/s |
|---|---|---|---|---|
| **anchor** (gte-ml-base, 305M) | **W1 prod-mirror** | **0.105** / 0.235 / 0.775 / 0.061 | **0.150** / 0.230 / 0.625 / 0.069 | 9.6 |
| anchor | W2 @2048 | 0.655 / 0.735 / 0.935 / 0.468 | 0.290 / 0.385 / 0.755 / 0.180 | 30.7 |
| anchor | W2 @8192 | **0.745** / 0.815 / 0.955 / 0.526 | 0.330 / 0.400 / 0.780 / 0.212 | 9.0 |
| anchor | C (500/50 MaxP) | **0.855** / 0.895 / 0.975 / 0.643 | 0.335 / 0.430 / 0.805 / 0.223 | 9.7 |
| arctic-l-v2 (568M) | W1 (text-window) | 0.790 / 0.865 / 0.960 / 0.590 | 0.330 / 0.440 / 0.780 / 0.218 | 4.9 |
| arctic-l-v2 | W2 @8192 | 0.775 / 0.850 / 0.960 / 0.555 | 0.350 / 0.435 / 0.765 / 0.236 | 4.9 |
| arctic-l-v2 | C | **0.865** / 0.910 / 0.965 / 0.671 | 0.395 / 0.485 / 0.830 / 0.267 | 5.6 |
| granite-278m | W2 @512 | 0.445 / 0.555 / 0.865 / 0.298 | 0.245 / 0.320 / 0.675 / 0.157 | 31.6 |
| granite-278m | C | 0.715 / 0.780 / 0.955 / 0.512 | 0.325 / 0.425 / 0.770 / 0.214 | 12.9 |
| qwen3-0.6b (595M) | W1 (text-window) | 0.530 / 0.640 / 0.890 / 0.377 | 0.135 / 0.200 / 0.665 / 0.077 | 1.35 |

**Unmeasured (remain for resume):** qwen3-0.6b W2/C + the `qwen3-0.6b-cite` instruction variant;
arctic-m-v2 all conditions (first attempt failed on a recipe bug — mGTE architecture needs
`trust_remote_code=True`; fixed in commit aa342c4, not yet rerun); bge-m3 all; me5-large all (weights
downloaded via `local_dir` after HF-cache symlink failures); **anchor-fav W1** (the attribution
control — incumbent under candidate-favorable text windows; separates the raw-id-window/[CLS] artifact
from mean-pool dilution inside W1's 0.105); granite-278m W1 (failed: window max_len 528 > granite's 514
position embeddings — cap `max_len` at the model limit at resume, one-line fix). The prepared runner is
`tmp/708-bakeoff/run_screen2.sh`.

**Provisional reading — labeled PROVISIONAL-PENDING-691, explicitly NOT a Phase 6 verdict:**
1. Under the pre-registered protocol, the FIX band (R@10 ≥ ~0.6 verbose) is cleared — **by the incumbent
   itself** once the whole-doc window-mean construction is removed: W2@2048 0.655, W2@8192 0.745,
   chunk-MaxP 0.855. Candidates confirm rather than decisively beat it (arctic-l C 0.865 ≈ anchor C
   0.855, at ~2× footprint).
2. **The mechanism finding (the pause-worthy result): F-030's "encoder-domain mismatch" is substantially
   a whole-doc vector CONSTRUCTION artifact, not (only) a representation limit.** Same model, same
   corpus, same queries, exact-NN: production 512/128 window-mean = 0.105; single-pass truncation@2048 =
   0.655; chunk-MaxP = 0.855 — **equal to BM25's verbose R@10 (0.855, F-030)**. The incumbent separates
   legal case documents fine at chunk granularity; averaging ~15+ window vectors into one whole-doc
   vector destroys the signal.
3. Query-shape secondary finding: kw is weak for every model and condition (max 0.395) — dense needs the
   verbose citing sentence. Mirrors F-030's BM25-verbosity-monotonicity on the dense side.
4. Open tension to reconcile at resume: engine E5-D measured chunk granularity adding only +3.0 pts on
   the RAG surface, while offline chunk-dense reaches 0.855 R@10 — E5-D measured a fused end-metric, not
   the isolated chunk-dense leg, and engine-side `chunk_vector` construction/coverage may differ. If the
   engine's chunk-dense leg does NOT reproduce ~0.85-class recall, that is an engine-side gap
   (639-adjacent), not a model question.
5. Model-swap question: provisionally NOT the lever — construction is. This re-scopes the remaining
   candidate screen (see §691 coordination).

### 691 coordination — this lane is PAUSED (founder directive, 2026-07-10)

- A parallel worktree (**tempdoc 691 takeover session**) is implementing **late chunking**
  (arXiv:2409.04701) for enrichment-embedding dedup — its E-5 design derives the whole-doc vector as a
  projection of chunk vectors from a **single native long-context pass**, i.e. exactly the construction
  this doc's **W2 condition** measures. This screen's anchor W2/C results (0.105 → 0.745/0.855) are
  direct quality evidence for that design.
- **691 is the presumptive vehicle for this doc's Branch FIX.** The fix these numbers point at is a
  vector-construction change on the incumbent model — no model swap, no footprint change, no D-003
  exposure — which lands naturally in 691's late-chunking implementation.
- To avoid colliding implementations and register merge conflicts (691's branch also edits the
  search-quality register), **this lane stops before Phase 5/6**: no decision-protocol verdict, no
  register updates, no scoped-claim drafting, no follow-on stub — those re-open after 691 merges, when
  this branch is rebased/merged onto post-691 main.
- **On resume, the remaining work re-scopes:** if 691's late-chunked incumbent holds W2/C-level quality
  engine-side, the candidate screen's question ("which model?") collapses to a confirmation pass and
  Branch FIX becomes "adopt 691's construction + re-baseline"; the unmeasured candidates matter only if
  691's engine-side numbers do NOT reproduce the offline construction gain. The anchor-fav attribution
  control and the E5-D tension (reading 4 above) are the first two items to close at resume.
- **Scratch kept intact for resume** (`tmp/708-bakeoff/`: venv + all 7 model snapshots, ~12 GB; datasets
  regenerated and signature-verified) — no teardown, no re-download needed. Result JSONs are the durable
  record regardless.

### RESUMED + CLOSED (2026-07-11, founder go-ahead post-691/711 merge)

**State absorbed at resume (read on disk):** origin/main merged (d1004a2); **F-031** (tempdoc 691,
PR #131) shipped the single-pass long-doc whole-doc vector default-on — this doc's W2 construction is
in production; **F-032** (tempdoc 711, PR #139) found ALL chunk vectors silently destroyed post-write
at the HEAD every pillar-5 probe (and this doc's Gate-0 target) measured against (`chunk_vector`
0/4,293 present; `WritePathOps.readModifyWrite` RMW bug, fixed via catalog-declared `rmwPolicy`);
shipped legal `vector` nDCG@10 is now **0.6180**, hybrid 0.5592.

**Screen completed at resume:** anchor-fav control, bge-m3, me5-large, granite-W1 refix (max_len cap),
and arctic-m-v2 (two rescues: `trust_remote_code=True` recipe fix aa342c4, then `xformers` — the
Snowflake mGTE remote code hard-requires it on CUDA; non-fatal triton warnings). **Founder-ratified
skip, recorded per no-silent-caps:** qwen3-0.6b W2/C + `qwen3-0.6b-cite` DROPPED — dominated at W1
(0.530 verbose), ~6× slower than siblings (1.35 docs/s), W2-verbose attempt killed after 60+ min.

**FINAL RESULTS TABLE (42 runs, all corpus-signature-bound `90d4300d…baf1`; cells = R@10/R@100/nDCG@10,
verbose | kw; docs/s = doc-side encode throughput at C, RTX 4070 fp16 torch; run JSONs
`tmp/eval-results/708-bakeoff/`):**

| Model (fp16) | W1 window-mean | W2 single-pass (native ctx) | C chunk-MaxP 500/50 | docs/s |
|---|---|---|---|---|
| **anchor = incumbent, prod recipe** (628 MB) | **0.105**/0.775/0.061 \| 0.150/0.625/0.069 | @2048: 0.655/0.935/0.468 \| 0.290/0.755/0.180 · @8192: **0.745**/0.955/0.526 \| 0.330/0.780/0.212 | **0.855**/0.975/**0.643** \| 0.335/0.805/0.223 | 9.7 |
| anchor-fav (same model, proper per-window [CLS]/[SEP]) | **0.745**/0.950/0.558 \| 0.310/0.800/0.187 | — (covered by anchor) | — | 9.2 |
| arctic-m-v2 (≈610 MB) | 0.775/0.950/0.580 \| 0.300/0.780/0.210 | @8192: 0.690/0.935/0.496 \| 0.300/0.785/0.191 | 0.860/0.970/0.659 \| 0.365/0.785/0.254 | 10.0 |
| arctic-l-v2 (≈1.1 GB) | 0.790/0.960/0.590 \| 0.330/0.780/0.218 | @8192: 0.775/0.960/0.555 \| 0.350/0.765/0.236 | **0.865**/0.965/**0.671** \| 0.395/0.830/0.267 | 5.6 |
| bge-m3 (≈1.1 GB) | 0.700/0.950/0.522 \| 0.280/0.750/0.178 | @8192: 0.650/0.930/0.499 \| 0.290/0.725/0.190 | 0.860/0.970/0.639 \| 0.330/0.780/0.210 | 6.2 |
| me5-large (≈1.1 GB) | 0.655/0.905/0.460 \| 0.315/0.775/0.199 | @512: 0.430/0.845/0.297 \| 0.220/0.700/0.148 | 0.770/0.955/0.602 \| 0.400/0.800/0.255 | 6.0 |
| granite-278m (≈556 MB) | 0.645/0.910/0.475 \| 0.235/0.745/0.160 | @512: 0.445/0.865/0.298 \| 0.245/0.675/0.157 | 0.715/0.955/0.512 \| 0.325/0.770/0.214 | 12.9 |
| qwen3-0.6b (≈1.2 GB) | 0.530/0.890/0.377 \| 0.135/0.665/0.077 | skipped (ratified) | skipped (ratified) | 1.35 (W1) |

**Attribution complete (anchor-fav landed):** production W1 0.105 → anchor-fav W1 **0.745** — the
dominant defect in the production whole-doc vector was **CLS-pooling raw id-slice windows that lack a
[CLS] token** (windows 2+, `OnnxEmbeddingEncoder.createChunks`), compounded by mean-pool dilution;
with proper per-window special tokens the same window-mean construction equals single-pass W2@8192
(0.745 = 0.745). Chunk granularity adds the rest (0.855). Residual >8192-token window-mean path logged
to the observations inbox.

### Phase 5 — RE-SCOPED TO NOTHING (founder directive at resume)

No 4k stress, no multilingual non-inferiority screens: the FIX shipped via 691/711 as a
*construction* change on the incumbent model (no model change → no multilingual regression surface;
the incumbent already holds the register's MIRACL/scifact baselines). No candidate integration is on
the table, so the survivor gates have no subject. The screen stands as a do-not-re-derive completeness
record.

### Phase 6 — VERDICT (pre-registered protocol applied to the full table)

**NO MODEL SWAP.**
1. **Gate 0:** PASSED (offline 0.105/0.150 vs engine 0.100/0.145; nDCG 0.061 ≈ 0.060).
2. **FIX band (R@10 ≥ ~0.6 verbose):** cleared by the **incumbent itself** in every non-production
   construction — W2@2048 0.655, W2@8192 0.745, chunk-MaxP 0.855 (= BM25-verbose parity), and even
   window-mean-with-real-[CLS] 0.745. **Branch FIX was realized by construction fixes (F-031 + F-032),
   not model choice**; shipped HEAD (legal vector nDCG 0.6180) captures ~96% of the incumbent's
   offline chunk-granularity ceiling (0.643).
3. **Candidate ordering — no significant winner:** paired sign tests on per-query gold ranks
   (C-verbose vs anchor-C): arctic-l 58/40/102, **p=0.085 (n.s.)**; arctic-m 55/47/98, **p=0.488**;
   bge-m3 57/56/87, **p=1.0**. arctic-l's +0.028 nDCG costs ~2× footprint (657 tier hit) + ~1.7×
   slower encode; arctic-m (same size class, official ONNX) is statistically indistinguishable. No
   margin clears swap costs (reindex, registry/tier updates, regression sweep, re-baselining).
4. **The SCOPE branch is moot as formulated:** legal-shaped retrieval is NOT beyond locally-runnable
   multilingual encoders — the incumbent handles it at the right construction/granularity. Surviving
   scoped truths: **kw-shape queries are weak for dense across every model and condition** (max
   0.400 — dense needs sentence-shaped queries; mirrors F-030's BM25-verbosity monotonicity), and
   **SPLADE remains unrecovered on legal** (0.0591 shipped) — a separate representation question
   deliberately not reopened (the eligible multilingual learned-sparse field is one model deep).
5. **Register updates made:** F-033 (verdict + table pointer, search-quality); dated refinement note
   on F-030(678); dated correction annotation on tempdoc 678 §E5-D (the "+3.0 pts at chunk
   granularity" was an F-032 artifact — that probe's chunk-hybrid arm had zero chunk vectors);
   inference-runtime register F-014 (screen footprint/throughput + the xformers/CLS-artifact runtime
   notes). Residual fusion gap (hybrid 0.5592 vs lexical 0.6891) routed to the observations inbox —
   out of 708 scope.

### 691/711 reconciliation — realized (updated from "presumptive", 2026-07-11)

The §691-coordination section above wrote "691 is the presumptive vehicle for Branch FIX" — now
**realized and shipped**: F-031 (single-pass whole-doc vector, default-on) is the W2 construction this
screen validated offline before 691 merged; F-032 (RMW preservation) unlocked the chunk-granularity
quality this screen measured as the offline ceiling. The offline→engine agreement (0.643 offline
C-MaxP nDCG vs 0.6180 shipped vector-mode) is the first confirmation datapoint for the
"offline representation screen before engine integration" principle named in §Reach.

**Teardown:** `tmp/708-bakeoff/` scratch (venv + model snapshots + local-models, ~16 GB) deleted at
closure; result JSONs (`tmp/eval-results/708-bakeoff/`, 43 files incl. smoke) and the committed
harness kept.

### Open founder decisions this plan surfaces

1. **Gray-zone adjudication** (if the best candidate lands 0.3–0.6 R@10).
2. **Branch-FIX go-ahead** (engine sweep + reindex + 657 tier impact + register re-baselining).
3. **Branch-SCOPE claim wording** for 659/RESEARCH.md (public claim, needs sign-off).
4. Whether the `-llm` query shape should ever be re-added (needs dev-stack LLM; F-030 says it adds no
   discrimination for dense — recommend leaving it out).
5. GPU scheduling window if the machine is contended during the screen (~hours of encode time).
