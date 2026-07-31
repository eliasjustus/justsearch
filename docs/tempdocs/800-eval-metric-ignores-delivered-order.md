---
title: "800 — The eval harness scores a different ranking than the engine delivers"
type: tempdocs
status: "VERIFIED DEFECT, no fix applied (2026-07-31). The mechanism is confirmed end-to-end at file:line on both sides. NOT fixed here because the correction moves published numbers: the public README's nDCG@10 table, the relevance-ratchet floors, and ~81 nDCG references in the search-quality register all derive from this metric. That is a claims-integrity decision for the owner, not a code change to make unilaterally. Surfaced from tempdoc 799's structural-health pass."
created: 2026-07-31
category: measurement-integrity / search-quality / eval-harness
related:
  - 799-structural-health-theorization.md   # where this was found; §G.2 is the principle it instantiates
  - ../reference/search-quality-register.md # ~81 nDCG-derived findings sit downstream
  - ../../scripts/jseval/relevance-ratchet-baselines.v1.json  # floors project from the same source
---

## The defect

**The engine reorders results by cross-encoder score. The harness then re-sorts them by the
pre-rerank fusion score before measuring.** So reported nDCG@10 describes the CE-*selected set*
in *fusion* order — and a stage that only reorders is invisible to the metric that judges it.
Cross-encoder reranking is exactly such a stage.

## Evidence chain (both halves verified at source)

**1. The Head reorders without rewriting the score.**
`KnowledgeSearchEngine.java:1006-1014` builds the reranked list by moving the existing
`SearchResult` objects into CE order:

```java
List<SearchResult> rerankedResults = new ArrayList<>(results.size());
for (int idx : orderToApply) {
  rerankedResults.add(results.get(idx));
}
```

Grepping that block for `setScore` / `withScore` / `score =` returns **0**. List order changes;
each hit's `score` field keeps its pre-rerank value. (Reranking is Head-side, not worker-side —
`SearchResponseBuilder.java:470` notes "the Head reranks".)

**2. The harness measures by that stale score.**
`scripts/jseval/jseval/retriever.py:143`:

```python
scored_docs.append(ScoredDoc(query_id=qid, doc_id=doc_id, score=hit["score"]))
```

`ir_measures` ranks by score, so it reconstructs the fusion ordering and discards the delivered
CE ordering entirely.

Together: the API's response order is CE; the measured order is fusion.

## Why it matters more than a normal bug

This is 799 §G.2 in the wild — *an instrument defect produces a wrong belief, dated back to
every measurement it touched*, silently and retroactively. A product bug shows up as wrong
behaviour now; this shows up as a whole family of conclusions that were never testing what they
claimed to test.

Specifically, any finding of the shape "the cross-encoder helps / hurts / is neutral" was
computed on a ranking the cross-encoder did not produce. The register carries **~81** nDCG
references, and several findings are explicitly about CE behaviour (F-001, F-002, F-006, F-008).
Their *conclusions* may well survive — but the evidence for them needs recomputing, not
assuming.

## Blast radius — why this is not a quiet fix

Correcting the metric changes numbers that are already public:

- **`README.md:121-125`** publishes a retrieval-quality (nDCG@10) table.
- **`relevance-ratchet-baselines.v1.json`** projects its floors from the same `release.v1.json`,
  so the standing quality gate moves with it.
- **~81 nDCG references** in the search-quality register.

So the fix is cheap and the *consequences* are not. This is a claims-integrity decision.

## Options (not a recommendation to act unilaterally)

1. **Fix the harness — score by delivered rank.** Have `retriever.py` derive the score from
   response position rather than `hit["score"]`, so the metric measures what the API actually
   returns. Smallest change, and it makes the metric mean what everyone already assumes it
   means. Requires re-running the release cohort and re-baselining.
2. **Fix the wire — rewrite `score` on rerank.** Arguably more correct (a hit's score should
   describe its final rank), but it is a wire-contract change with other consumers, including
   the UI, and it would change what users see.
3. **Both**, with 1 first.

Option 1 is very likely right, but the decision that matters is not which option — it is
**whether to re-run and re-baseline the published numbers**, and how to describe the change.

## What is NOT claimed here

- **No claim that the engine is worse than reported.** The direction of error is unknown until
  a re-run: fusion order and CE order overlap substantially, and the CE-selected *set* is
  unchanged. The defect is that the number does not measure the delivered ranking, not that the
  delivered ranking is bad.
- **No claim that register conclusions are wrong** — only that their evidence was computed
  against the wrong ordering and needs recomputing before it is relied on again.

## Suggested first step

An A/B on one existing run directory: score it both ways (current `hit["score"]` vs delivered
rank) and report the nDCG@10 delta per corpus. That is offline, costs nothing, needs no model,
and converts "the numbers may be wrong" into "here is exactly how wrong, per corpus" — which is
the input the re-baseline decision actually needs.

## Provenance

Logged as an observation by another session on 2026-07-29 (`obs:retriever`), which identified
half the mechanism (the harness side). Tempdoc 799's pass flagged it as the highest-value
unactioned finding; this document verifies the engine side, which the original note asserted
but did not evidence, and establishes the blast radius.
