---
title: "Retrieval quality vs corpus size: does JustSearch's hybrid retrieval hold as the corpus grows, or does the right document silently fall out of reach at scale? A diagnose-first investigation — separate the synthetic-corpus artifact from a real scaling defect, attribute the loss to a pipeline stage, then make size-robustness a measured, ratcheted property. North star: the probability the engine surfaces the right document in its top-K does not silently degrade as a personal/team corpus grows from hundreds to hundreds of thousands of files."
type: tempdocs
status: open — investigation request (2026-07-08). NO fix yet, and NO confirmed defect yet: this owns the QUESTION, whose first milestone is DIAGNOSIS (artifact vs real). Surfaced by tempdoc 624's scale-corpus matrix run (pass 26), which is the first time retrieval was exercised at multi-thousand-doc scale and the first empirical signal that quality may fall off with size — but that signal is on an ADVERSARIAL SYNTHETIC corpus and does not by itself prove a product defect.
created: 2026-07-08
author: agent (Opus autonomous run) — filed from the 624 scale-corpus session
category: search-quality / retrieval / ann-recall / eval-infrastructure / scaling
related:
  - 639-candidate-set-integrity-ann-recall-and-result-dedup   # THE prior owner of the leading real mechanism — ANN recall at scale, explicitly "unowned and unmeasured / parked"
  - 636-retrieval-buried-signal-long-documents                # the staged_recall_accounting projection this investigation reuses to find WHERE the answer is lost
  - 580-relevance-freeze-and-fw001-thaw                        # records "no ANN action (HNSW inherited from Lucene)" — i.e. ANN recall is parked, not owned
  - 624-agentic-retrieval-eval-rebuild                          # origin: pass 26's scale run + the corpus-design tension finding
  - 643-judge-stage-ranking-quality-next-lever                 # sibling: 643 = does the judge rank a PRESENT answer; this + 639 = is the answer in the set at all
---

> NOTE: Noncanonical working tempdoc. Verify every claim against `main` + canonical docs + live
> measurement before treating it as truth. This tempdoc deliberately does NOT assert that JustSearch
> has a retrieval scaling defect — it asserts that we have a *signal* and no measurement that would
> confirm or refute it, and it lays out how to find out.

# 699 — Retrieval quality vs corpus size

## The observation (what was actually seen)

In tempdoc 624 pass 26 (2026-07-08), the retrieval engine was exercised for the first time at
multi-thousand-document scale, on a fabricated eval corpus (`battlefield-en-scale-v1`, 2,736 docs):

- Hybrid **nDCG@10 fell to 0.163** — vs **0.414** on the 390-document version of the *same corpus
  family*. Across three sizes the fit was clean: **nDCG@10 ≈ 0.414·√(130 / head-count)** — quality
  dropped roughly with the inverse square-root of the number of descriptor-bearing documents.
- A direct `justsearch_search` probe on a sample query put the **gold document outside the top 10** —
  the top results were other, semantically-similar documents.
- Yet a forced-retrieval agent (condition C, JustSearch-only) still answered **60%** correctly by
  iterating (search → rerank → read) — so retrieval is degraded, not dead.

## The two explanations — and why this run cannot distinguish them

**(1) Synthetic-corpus artifact (the failure is in the measuring stick, not the product).**
The corpus was engineered to be maximally confusable: hundreds of near-identical documents of the form
"the {type} in the {place}, {qualifier}", differing only in a (type, place, qualifier) descriptor.
Those documents form one tight semantic cluster, so *any* dense retriever struggles to separate the one
gold head from its ~900 look-alikes. **No real corpus of a person's files looks like this** — real
documents are semantically diverse, so the cluster-collapse that tanked nDCG here would not occur the
same way. Under this explanation, the fix is to the eval-corpus design (624's separate concern), and the
product is fine.

**(2) A real, size-dependent retrieval defect (the failure would show on real corpora too, just less
dramatically).** There are concrete, corpus-independent mechanisms by which top-K retrieval degrades as
a corpus grows, and at least one is *already flagged as unmeasured in this codebase*:

- **ANN recall decay (leading candidate).** The dense leg searches an approximate index (HNSW inherited
  from Lucene). Approximate recall is by construction below exact recall, and **the gap widens as the
  index grows** (more neighbors to prune, fixed search effort). Tempdoc **639** already names this
  "unowned and unmeasured" and a "silent ceiling on everything downstream"; **580** records "no ANN
  action" — it is parked. If the gold document is not in the first-stage candidate set, no fusion or
  reranker can recover it.
- **Reranker candidate-window truncation.** The cross-encoder reranks only a fixed top-N from first-stage
  retrieval. As the corpus grows, more competitors crowd that window, so the gold document is more likely
  to fall *outside* the window before the reranker ever sees it — a scale-dependent recall loss distinct
  from ranking quality.
- **Fusion / score-normalization tuned at small scale.** RRF weights and score normalization calibrated
  on small corpora may not hold as candidate distributions shift with size.
- **Chunk-space densification.** More documents → more chunks → the dense space gets denser → the
  margin between the right chunk and its neighbors shrinks.

**The honest state: this run cannot tell (1) from (2)**, because the pathological synthetic corpus
triggers BOTH the artifact and (if present) the real mechanisms at once. Determining which dominates is
the first, non-negotiable milestone — chasing a fix before this is chasing a possible artifact.

## Why it matters (the product stake)

JustSearch's core promise is "better retrieval than plain file tools, *especially* on large, messy
folders." The 624 run showed the agent only starts preferring JustSearch once the corpus is big enough
that grep breaks — i.e. **the large-corpus regime is exactly where JustSearch must be at its best.** If
retrieval quality silently decays with size, the promise weakens precisely where it is supposed to be
strongest, and — worse — it decays *invisibly* today, because recall-vs-size is not measured anywhere.
An engine that quietly gets worse as the user adds files is a trust defect even if average-case quality
looks fine on small benchmarks.

## The goal (north star + a measurable form)

**North star (the user's framing, made precise):** retrieval quality is **corpus-size-robust** — the
probability that the engine returns the right document in its top-K does **not silently degrade** as the
corpus grows across JustSearch's target range (personal/team files: ~10² to ~10⁵–10⁶ documents).

"Never fails regardless of size" is not literally achievable (any finite-effort approximate index
eventually degrades), so the achievable, honest goal is **flat-or-graceful-and-visible**:

- **Flatness criterion.** For a fixed query set on a *realistic, diverse* corpus, **recall@K(N)** (the
  gold document is in the returned candidate set of size K) stays within a small tolerance as corpus size
  N scales by orders of magnitude — it must not fall off a cliff.
- **Graceful + visible where decay is unavoidable.** Where recall does drop at extreme scale, it drops
  smoothly and is **measured and ratcheted**, never silent. The system should be able to *tell the user*
  (or the agent) when corpus size is pushing retrieval past a confidence threshold, rather than returning
  quietly-worse results.
- **Guard.** A jseval **scaling-robustness ratchet** that measures recall@K at multiple corpus sizes and
  fails the build if quality degrades beyond tolerance between sizes — so this can never silently regress
  again.

## Plan (diagnose first; do NOT fix before the defect is confirmed real)

**Milestone A — Artifact vs defect (the gate for everything else).** Measure recall@K / nDCG@K as a
function of corpus size on a **realistic, diverse** corpus with real relevance judgments (e.g. a large
public IR corpus subsampled at 500 → 5k → 50k documents against a *fixed* query set), NOT the synthetic
descriptor corpus. If quality holds on realistic documents → the 624 signal was largely a synthetic
artifact; hand the corpus-design half back to 624/635 and close the product half of this doc. If quality
degrades on realistic documents too → a real scaling defect; proceed.

**Milestone B — Attribution (only if B is reached): find WHERE the answer is lost.** Reuse the
`staged_recall_accounting` projection (636/639 lineage) to determine, per query, the first stage at which
the gold document disappears: was it (i) absent from the dense ANN candidate set (→ ANN recall, 639), (ii)
present in candidates but truncated before the reranker window, or (iii) present through reranking but
mis-ranked (→ ranking quality, 643's side, not this doc's)? The dominant stage names the fix; do not
guess it.

**Milestone C — Targeted fix guided by B.** e.g. raise ANN search effort (HNSW `efSearch`/candidate
count) with a measured latency/recall trade (coordinate with 640's perf ratchet), widen the reranker
candidate window, or re-tune fusion at scale. One lever, chosen by attribution, measured before/after.

**Milestone D — Ratchet it.** Add the scaling-robustness gate (recall@K across sizes) to jseval so
size-robustness becomes a standing, enforced property — the durable outcome regardless of how big the
current gap turns out to be.

## Explicit non-goals / boundaries

- This is **not** the eval-corpus-design problem (624's "grep-expense ↔ retrieval-difficulty tension").
  That is a *measurement-instrument* concern and stays with 624/635; this doc owns the *product* question
  of retrieval robustness at scale. They are siblings: Milestone A needs a realistic large fixture, whose
  construction may borrow from the corpus-design work, but the deliverable here is engine robustness, not
  a better synthetic corpus.
- Do **not** open by tuning ANN parameters — that presumes Milestone B's outcome before it is measured.
- Ranking quality of an *already-retrieved* answer is 643's/636's turf; this doc is strictly about whether
  the answer is **in the returned set at all** as size grows (639's axis, now with an empirical trigger).

## First question for the next agent

Before any code: does a realistic, diverse corpus reproduce the size-dependent recall drop the synthetic
corpus showed — yes or no? Everything downstream forks on that one measurement.
