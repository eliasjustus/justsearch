---
title: "Natural-language question robustness: the engine loses ~21 points of top-3 hop-1 recall to question boilerplate — verbose NL questions rank worse than their own distinctive descriptor phrase"
type: tempdocs
status: "open — RESEQUENCED 2026-07-10 (supersedes the 07-03 sequencing clause below): tempdoc 704 made the pillar-5 dense-legs attribution — this doc's mechanism at its extreme (F-029: dense/SPLADE R@10 ≤0.15 on CLERC-shaped legal text) — the measurement program's explicit FIRST pickup, and it GATES the pillar-1 corpus design that the powered 624 re-run needs. The original clause ('design AFTER the 624 re-run lands') is therefore inverted and void: the re-run now waits on this lane, not vice versa. The §Pillar-5 attribution experiment section (2026-07-10) is the designed, runnable first work item (~$0, dev-stack sessions); the broader query-robustness LEVER design (reduction/reformulation mechanism) remains open and follows the attribution verdict. [Original 07-03 clause, retained as history: design AFTER the 624 harness fix + real-with-tool re-run lands — that run decides whether lazy verbatim-question usage is the dominant real access pattern.] Evidence in hand: measured 69% -> 90% top-3 gap between verbatim-question and descriptor-phrase formulations (624 mechanism replay, 2026-07-03). D-005-clean by construction: the lever keys on the query's own shape, never on an assumed corpus."
created: 2026-07-03
updated: 2026-07-03
author: agent retrospective (624 mechanism investigation), filed by agent — STUB
category: search-quality / query-side / retrieval-engine
related:
  - 624-agentic-retrieval-eval-rebuild   # origin — the engine-replay evidence and the access-pattern question the re-run will settle
  - 363-query-understanding-boost-extraction  # dormant query-side machinery to register-check before any new mechanism (QU, F-018)
  - 655-mcp-conformance-and-capability-policy # the consumer boundary: agents/users paste whole questions through the tool surface
principle: "a retrieval engine consumed by agents and end users will receive whole natural-language questions, not curated keyword queries — robustness to the query's own boilerplate is engine capability, not caller responsibility."
---

> Noncanonical working tempdoc. STUB: goals and context only.

# 678 — Natural-language question robustness

## Goal

Close (or substantially narrow) the measured gap between how well the engine retrieves for a verbose
natural-language question and how well it retrieves for that question's own distinctive descriptor
content — so that the laziest realistic usage (pasting the whole question) performs close to the best
reformulated usage, without requiring the caller to be a skilled query formulator.

## Context — the measurement (tempdoc 624 engine replay, 2026-07-03, clean per-corpus indexes)

On the two certified battlefield corpora (26 queries each, deliberately hard paraphrase band,
`hybrid` mode, top-10), hop-1 document recall by query formulation:

| Formulation | top-3 (pooled n=52) | top-10 |
|---|---|---|
| verbatim benchmark question | 69% | 79% |
| the question's descriptor phrase alone | 90% | 96% |
| best-of-3 formulations | 94% | 98% |

The engine is near-ceiling when given the distinctive content and measurably degraded when that same
content arrives wrapped in question boilerplate ("What is the value associated with the founder of
the designer of…"). Concrete instance: one query's verbatim form missed top-10 entirely while its
descriptor phrase ranked the gold document #1. The effect is language-invariant (EN and DE verbatim
both 69%). Why it matters: the realistic consumers of this engine — MCP agents and end users — paste
questions; every point recovered here accrues to real-world results without any caller cooperation,
and directly moves the agent-utility measurement's most realistic usage mode.

## Relevant register facts for the design pass (check before building anything new)

Query-side machinery already exists in various states of dormancy and must be register-checked first:
query understanding / boost extraction (363, F-018 — shipped, default-off), LLM query expansion
(TEXT-preset only by design), QPP signals (computed, unused for routing — F-019 constrains what they
can do), and the fusion/weighting layer's existing per-leg configurability. The design pass should
also characterize WHERE the boilerplate hurts (lexical leg dilution vs dense-embedding dilution vs
fusion) before choosing a mechanism — the replay data supports constructing that decomposition
cheaply. Per D-005, any solution must be a fixed, regime-blind behavior keyed on the query itself.

## Explicit non-goals

Not benchmark tuning (the fix must be justified on general verbose-question handling, with the
battlefield replay as *one* measurement among the register's corpora); not agent-side prompt
engineering (that is 655's layer); not a per-corpus router (D-005).

## §Pillar-5 attribution experiment (designed 2026-07-10, Fable orchestration — 704's first pickup, run in this lane)

### The question and why it forks everything downstream

Why are dense + SPLADE near-dead on CLERC-shaped legal retrieval (F-029: R@10 ≤0.15 at 198 docs,
≤0.03 at 4k — "hybrid" de facto BM25-only on the paying-ICP corpus shape)? Two explanations with
opposite consequences: **(a) product gap** — verbose citing-sentence queries dilute the query
embedding (this doc's mechanism at its extreme, compounded by very long case documents); fix is
query-side, then re-measure. **(b) corpus artifact** — CLERC queries are citation sentences, not
user queries; fix is the pillar-1 corpus's *query* construction. 704's pillar-1 corpus design forks
on this answer, and the powered 624 run waits on that corpus — hence first pickup.

### Scope boundary settled in 702 §B.6 (do not re-litigate)

Threshold recalibration (702) **cannot move raw dense-leg R@10** — unit vectors rank identically
under EUCLIDEAN and COSINE. It can only move post-gate/post-fusion numbers (the miscalibrated
low-signal gate effectively required cos ≥ 0.25 to count the vector leg as healthy, and verbose
legal queries plausibly score below that — capping the dense leg's fused contribution). Therefore:
**measure pre-gate leg recall and post-fusion recall separately, and run all post-fusion stages on
the post-702 engine.** Sequencing within the lane: 702 fix lands first, then this experiment.

### Stages (each ~$0; local dev stack; staged-recall instrument + `leg_union_recall` projections)

- **E5-A — raw-leg baseline (decisive for calibration-vs-retrieval, run first).** On
  `legal-clerc-200` (pinned, reproducible, union-recall floor 0.87): per-leg **pre-gate** R@10/R@100
  for dense and SPLADE (raw KNN / raw impact-ordered top-K, before low-signal gating and fusion).
  If raw dense R@10 is already ≤0.15, gating/calibration is NOT the cause → proceed to E5-C. If raw
  recall is materially higher than the post-fusion 0.15, the loss is in gate/fusion → 702's
  recalibration is the primary fix; re-measure post-fix and re-scope F-029.
- **E5-B — post-702 hybrid re-measure (rides with 702's eval gate).** `leg_union_recall`, hybrid
  R@10, arbitration firing rate, low-signal classification rate on legal-clerc-200, branch vs main.
  Also probes 701's unexplained `ann_proof` dense-evidence 0.455 anomaly (704's coordination note
  suspects a shared root).
- **E5-C — query-shape sweep (the (a)/(b) discriminator).** Same 198 docs, same qrels, three query
  variants: (1) original CLERC citing sentences; (2) LLM-reduced short user-style queries —
  generated ONCE with the local model and committed (determinism-by-commitment, 704 pillar 1's
  rule), (3) mechanically keyword-extracted phrases (no LLM, the cheap control). Pre-gate dense
  R@10 per variant. **Dense recovers with short queries → (a)**: the lever is query reduction (this
  doc's design pass proceeds; register-check 363/QU first per §Relevant register facts) and
  pillar-1 corpora need realistic-length queries. **Dense stays dead → doc-side attribution
  (E5-D).**
- **E5-D — doc-side attribution (only if E5-C is flat).** CLERC case documents are very long;
  check chunk-population stats on the indexed corpus (chunk count/length distribution vs the
  2000-char floor), and whether gold *chunk* embeddings rank when queried directly (chunk-level
  nearest-neighbor probe). Distinguishes embedding-content dilution (long chunks) from
  chunking/coverage gaps. Routes to 636 (buried-signal/long-documents) — not owned here.
- **E5-E — SPLADE pruning probe (optional rider, teardown lever #5).** SPLADE leg with `beta=1.0`
  vs the hardcoded 0.5 (`SearchInputCapture.java:320/329/331`) on the same query variants — verbose
  queries produce many terms; top-50% pruning may drop the discriminative ones. One knob, one run;
  if it moves recall materially, file the finding to 266/273.

### Interpretation tree → routing (pre-registered so the verdict routes itself)

| E5-A raw | E5-C sweep | Verdict | Routes to |
|---|---|---|---|
| low (≈ post-fusion) | recovers | (a) product gap, query dilution | 678 lever design (this doc); pillar-1 queries must be realistic-length |
| low | flat | doc-side (length/chunking) | E5-D → 636/686; pillar-1 needs doc-shape realism, not just query realism |
| materially higher than post-fusion | n/a | gate/fusion loss | 702 recalibration is the fix; re-scope F-029 post-fix |
| SPLADE-only anomaly in E5-E | — | pruning artifact | 266/273 (beta tune) |

Deliverable: an attribution verdict + the pillar-1 fork decision recorded here AND in 704's pillar-5
section; the founder ratifies the fork before the pillar-1 corpus tempdoc is filed (orchestration
sync point 1).

### E5-A + E5-B RESULTS (2026-07-10, same session — post-702 engine, worktree 702-dense-calibration)

Run: `mixed/legal-clerc-200` re-fetched via the committed recipe (198 docs / 200 queries, seed 666),
four modes (`lexical,vector,splade,hybrid`), clean lifecycle, branch `0f93193` (702 recalibration
included). All modes `comparable=True`; staged-recall reconciliation 0 mismatches / 200. Run dir
`scripts/jseval/tmp/eval-results/20260710T050037_mixed_legal-clerc-200`.

| Measure | Value | Register reference (pre-702, tempdoc 666) |
|---|---|---|
| raw per-leg recall (pre-fusion) | **lexical 0.855 · vector 0.10 · splade 0.15** | F-029: dense ≤0.15 / SPLADE 0.15 at 198 docs |
| leg_union_recall | 0.875 | union-recall floor pin 0.87 → PASS |
| hybrid nDCG@10 | 0.517 | 0.521 — unchanged |
| vector-mode nDCG@10 | 0.0597 | 0.060 — unchanged |
| staged buckets (hybrid, top-10) | leg_miss 0.115 · leak 0.215 · judge_low 0.30 · final_recall 0.67 | (701's probe leak ≤0.035 was `full` mode, **CE-off**; this is `hybrid`, CE-on — modes differ, not a regression: hybrid nDCG matches its own register baseline) |

**E5-A verdict (per the pre-registered tree, row 1): the dense leg's RAW pre-fusion recall is 0.10 —
essentially equal to its post-fusion contribution. Gate/fusion capping is NOT the cause of dense-death
on CLERC; the loss is in the leg's own retrieval.** E5-B side-verdict: the 702 recalibration does NOT
recover legal hybrid (0.517 ≈ 0.521) — consistent with 702 §B.6's prediction and completing 702's
exoneration as the F-029 mechanism (the miscalibration was latent on every corpus measured, four of
four).

### E5-C RESULTS — keyword-control variant (2026-07-10, same session, branch 678-e5c-query-variants)

Tooling: new `jseval corpus-query-variant` (deterministic keyword extraction: corpus-DF-ranked top-8
query terms, original order; pure function of the source dataset — licensing-clean, nothing
CLERC-derived committed; 14 unit tests). Variant `mixed/legal-clerc-200-kw`: 200/200 queries
transformed, 0 fallbacks; corpus + qrels byte-identical to source. Run
`20260710T071731_mixed_legal-clerc-200-kw`, four modes, all `comparable=True`.

| Leg (raw pre-fusion R@10) | E5-A verbose originals | E5-C keyword top-8 | Δ |
|---|---|---|---|
| lexical | 0.855 | **0.630** | **−0.225** |
| vector (dense) | 0.100 | 0.145 | +0.045 |
| splade | 0.150 | 0.165 | +0.015 |
| hybrid (final) | 0.670 | 0.470 | −0.200 |

**Verdict (pre-registered tree, "dense stays dead" row): dense does NOT recover under query
shortening — 0.10 → 0.145 is marginal, nowhere near lexical levels. Query LENGTH alone is ruled out
as the dense-death mechanism at this operating point; attribution shifts to DOC-SIDE (E5-D:
long-case-doc representation/chunking → routes 636/686).** Honest scope limit: the mechanical
keyword-bag control rules out length, not *naturalness* — a rare-term bag is the query shape
embedding encoders handle worst, so the LLM-reduced natural-phrase variant (E5-C variant 2, the
`llm-reduced` slot in the new subcommand's registry) remains informative before doc-side is declared
the sole mechanism. (The original 69→90% descriptor-phrase evidence used coherent phrases, not
keyword bags.)

**Sharp secondary finding: verbosity HELPS lexical on CLERC (−22.5 points under keyword reduction).**
The citing sentence's full context is load-bearing for BM25 on this corpus shape — so "reduce the
query" is NOT a free lever here; any query-side mechanism must be leg-aware (reduced variant for
dense, full text for lexical — a per-leg query policy, not a global rewrite). This materially
constrains this doc's eventual lever design AND pillar-1's query construction: realistic professional
corpora need queries evaluated at multiple verbosity operating points, not one "realistic" length.

**Pillar-1 fork consequence (for the corpus tempdoc):** do not assume dense contributes on
legal-shaped documents until E5-D attribution lands; the ratified substrate choice (real legal+email
distractor mass, EN+DE, injected fabricated gold) stands, with doc-length/chunking as an explicit
design dimension and query verbosity as a controlled variable.

Remaining in this lane: E5-C variant 2 (`llm-reduced` — local LLM, generate-once, recipe-committed,
content not committed), then E5-D (chunk-level probe: gold-chunk embedding ranks, chunk-length stats
vs the 2000-char floor).

### E5-D RESULTS — chunk-granularity probe (2026-07-10, same session)

**Corpus stats (mechanical):** CLERC docs are extreme-length — median 28,487 chars, mean 35,508,
p75 46,186, max 129,915; 97% exceed the 2,000-char chunking floor; 44% exceed 32k chars. The
whole-doc mean-pooled vector averages over ~30k chars — maximal dilution conditions.

**Probe (throwaway driver, 699-evidence-standard; validated against jseval first):** gold-doc-in-
context rate for the PRODUCT's chunk-first RAG surface (`POST /api/knowledge/retrieve-context`),
200 original verbose queries, same index both arms (jseval dev, no clean), A/B via
`JUSTSEARCH_RAG_RETRIEVE_MODE`. Probe matching validated: a search-API replication reproduced
E5-A's hybrid final_recall exactly (0.675 vs 0.67) before either arm was trusted.

| Arm | gold-in-context @ ~2.8 distinct docs | mode observed |
|---|---|---|
| chunk-hybrid (BM25 chunks + dense chunk vectors) | **142/200 = 0.710** | CHUNK_HYBRID 199/200 |
| chunk-bm25 (BM25 chunks only) | **136/200 = 0.680** | BM25 199/200 |

**Findings:** (1) **Dense adds only +3.0 points even at chunk granularity** (with verbose queries) —
granularity alone is not the recovery lever. (2) **Positive product finding:** the chunk-first RAG
surface reaches 0.68 gold-in-context within ~2.9 documents on legal text via lexical chunks alone —
7× the whole-doc dense R@10 (0.10) at a third of the candidate count; the RAG path is efficient on
the ICP shape today, riding BM25.

**Honest entanglement (do not over-conclude):** E5-D used VERBOSE queries, so its small dense delta
is consistent with BOTH remaining mechanisms — query-side dilution (the query embedding is bad
regardless of doc granularity) and encoder-domain mismatch (legal text collapses distances in the
embedding space). The missing decisive cell is **short/natural queries × dense retrieval** — exactly
E5-C variant 2 (`llm-reduced`, in progress). If natural short queries fail too, the attribution
lands on encoder-domain fit (a model/representation question → routes toward the encoder choice /
636 territory, NOT a 678 query lever); if they recover, 678's lever is real but must be per-leg
(E5-C's lexical finding stands: BM25 needs the verbosity).

Ruled out so far across E5-A→D: gate/fusion capping (E5-A/B), query length alone at the keyword
operating point (E5-C), and doc granularity alone (E5-D).

### E5-C-v2 RESULTS + FINAL ATTRIBUTION VERDICT (2026-07-10, same session — campaign CLOSED)

Variant `mixed/legal-clerc-200-llm`: 200/200 queries rewritten by the local LLM
(Qwen3.5-9B, temp=0, seed=42, prompt sha-recorded, 0 fallbacks) into coherent short legal
search phrases (spot-checked: real case-name/topic queries, not keyword bags). Run
`20260710T134438_mixed_legal-clerc-200-llm`, four modes, all `comparable=True`.

**The completed attribution matrix (raw pre-fusion R@10, same 198 docs + qrels throughout):**

| Leg | verbose originals | keyword top-8 | LLM natural phrase |
|---|---|---|---|
| lexical | 0.855 | 0.630 | 0.780 |
| **dense** | **0.100** | **0.145** | **0.145** |
| splade | 0.150 | 0.165 | 0.145 |

**FINAL VERDICT — Branch B: encoder-domain mismatch.** The dense leg is dead on CLERC-shaped
legal retrieval at every query shape (verbose / keyword / natural-short), every granularity
(whole-doc / chunk: E5-D +3.0 pts), and independent of gating (E5-A/B, 702). The mechanism is the
embedding representation itself: gte-multilingual does not separate legal case documents by
citation-relevant content. Eliminated in order: gate/fusion (E5-A/B) → query length (E5-C) →
doc granularity (E5-D) → query naturalness (E5-C-v2). SPLADE shows the same profile (≤0.165
everywhere) — the learned-sparse encoder shares the domain gap.

**Routing:** the encoder-domain question is NOT a 678 query lever and NOT a corpus question —
it routes to a model/representation investigation (636/580 territory; candidate shapes: domain
eval of alternative encoders, passage-level matching designs). 678's original verbose-question
gap (69→90% on battlefield corpora) remains real for corpora where dense WORKS — the lever design
here stays open for those, with E5-C's per-leg constraint (BM25 needs verbosity) binding. Tempdoc
707 (pillar-1 corpus) proceeds on its Branch B: the EN-legal member measures utility on the
engine as it is (lexical-carried, per E5-D the RAG chunk surface reaches 0.68 in ~3 docs);
no corpus design flatters dense. Secondary confirmation: lexical is monotonic in verbosity on
CLERC (0.630 keyword → 0.780 natural → 0.855 verbose).
