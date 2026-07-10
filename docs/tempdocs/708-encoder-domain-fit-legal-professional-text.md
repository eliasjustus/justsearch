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
