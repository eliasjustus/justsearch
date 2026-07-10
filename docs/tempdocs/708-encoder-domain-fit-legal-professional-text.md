---
title: "Encoder-domain fit on legal/professional text: can any locally-runnable multilingual embedding (and/or learned-sparse) model separate legal-shaped documents by content — the F-030 follow-up (dense R@10 ≤0.145 and SPLADE ≤0.165 at EVERY query shape and granularity on CLERC)"
type: tempdocs
status: "open — STUB filed 2026-07-10 at founder request; investigation authorized to proceed through takeover → theorize → design → plan (NO implementation, NO model-swap shipping, NO spend beyond local compute without a separate go-ahead). Owner question spawned by F-030 (search-quality register) / tempdoc 678 §Final attribution verdict; named a new unowned piece in 704's pillar-5 resolution note."
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
