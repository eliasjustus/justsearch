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
