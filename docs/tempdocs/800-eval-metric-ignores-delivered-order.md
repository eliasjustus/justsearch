---
title: "800 — The eval harness scores a different ranking than the engine delivers"
type: tempdocs
status: "VERIFIED DEFECT + MEASURED, no fix applied (2026-07-31). The A/B is run: on all 8 hybrid cells the delivered top-10 differs from the measured top-10 for 100% of queries, delta up to ±0.06 nDCG@10 and BIDIRECTIONAL (enron worse delivered, legal better) — so no paper correction is possible and a re-run is the only route. lexical/splade/vector show exactly 0.0000, the control that confirms the mechanism. The mechanism is confirmed end-to-end at file:line on both sides. NOT fixed here because the correction moves published numbers: the public README's nDCG@10 table, the relevance-ratchet floors, and ~81 nDCG references in the search-quality register all derive from this metric. That is a claims-integrity decision for the owner, not a code change to make unilaterally. Surfaced from tempdoc 799's structural-health pass."
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

## Measured — the A/B has been run (2026-07-31)

The "suggested first step" below was executed. It is offline, needs no model or backend, and is
committed as `scripts/jseval/metric_order_ab.mjs` so the re-baseline decision rests on a repeatable
measurement rather than on this document's reasoning.

**Method.** For each existing run cell, score the same result set two ways:
*measured* = the `*_run.trec` file sorted by score descending (what `ir_measures` does), *delivered*
= `predictedDocIds` from `*_per_query.json`, which `artifacts.py` builds from `scored_docs` in
append order, i.e. the order the API returned.

**Validity check, run before believing any of it:** the two orderings must cover the *same document
set*, or the comparison is a set difference masquerading as a ranking delta. Verified per query —
**50/50 identical sets, identical lengths, zero mismatches**. It is a pure reorder.

**Corpus:** the 781 certification cells (8 cells × 4 modes = 32, n=50 queries each).

### Result

| cell | measured | delivered | delta | top-10 order differs | top-1 differs |
|---|---:|---:|---:|---:|---:|
| enron-raw-10k-short-natural | 0.5216 | 0.4753 | **−0.0463** | 50/50 | 37 |
| enron-raw-10k-verbose | 0.4687 | 0.4585 | −0.0102 | 50/50 | 28 |
| enron-raw-1k-short-natural | 0.6418 | 0.5832 | **−0.0586** | 50/50 | 34 |
| enron-raw-1k-verbose | 0.6602 | 0.6778 | +0.0176 | 50/50 | 34 |
| legal-clerc-10k-short-natural | 0.1015 | 0.1053 | +0.0037 | 50/50 | 40 |
| legal-clerc-10k-verbose | 0.1262 | 0.1625 | **+0.0363** | 50/50 | 40 |
| legal-clerc-1k-short-natural | 0.2305 | 0.2504 | +0.0199 | 50/50 | 41 |
| legal-clerc-1k-verbose | 0.3143 | 0.3525 | **+0.0382** | 50/50 | 41 |

**`lexical`, `splade` and `vector` show delta 0.0000 and 0 differing queries on every cell.** That
is the control, and it is a clean one: cross-encoder reranking runs only on the hybrid path, and
only the hybrid path moves. The mechanism is confirmed by which cells *don't* change as much as by
which do.

### What the numbers say

1. **The defect is real and total in scope.** On every hybrid cell, the delivered top-10 differs
   from the measured top-10 for **all 50 queries** — 100%. Top-1 differs on 28–41 of 50. The
   reported number has never been describing the ranking a user receives.
2. **The direction is corpus-dependent, not systematic.** §"What is NOT claimed" above declined to
   guess whether the engine was better or worse than reported; the data justifies that caution.
   Enron cells mostly score **worse** delivered (down to −0.0586); legal cells score **better**
   (up to +0.0382). There is no single correction factor, so no number can be adjusted on paper —
   a re-run is the only way.
3. **On legal, the harness has been understating the engine.** Delivered beats measured on all four
   legal cells, i.e. the cross-encoder is helping more than the metric credits it for.
4. **Magnitude is decision-relevant.** ±0.06 nDCG@10 is large next to the differences several
   register findings turn on.
5. **A note on the register's CE findings.** F-001/F-002 hold that CE hurts on personal email; the
   enron cells here move in that direction under the corrected ordering. Those conclusions may well
   survive — but they were reached from the wrong ordering, so they are currently right-by-luck
   rather than right-by-evidence, and need recomputing before being leant on again.

### Replicated across two further run sets (2026-07-31)

The same script was run over every other run directory on disk. The finding replicates on
**16 hybrid cells across three independent run sets**, and the control holds everywhere.

| run set | cells | n/cell | hybrid delta range | non-CE modes |
|---|---:|---:|---|---|
| `781-certification` (enron / legal) | 8 | 50 | −0.0586 … +0.0382 | 0.0000 on all |
| `781-certification/step0` (arms A1–A4) | 4 | 200 | −0.0547 … −0.0263 | 0.0000 on all |
| `786-sweep` (OHR-bench) | 4 | 645–708 | −0.0016 … +0.0121 | n/a (hybrid only) |

Two things the wider sample adds:

1. **Effect size tracks headroom.** OHR-bench sits at nDCG@10 ≈ 0.96 and moves by ~0.001–0.012;
   the enron/legal cells sit at 0.10–0.66 and move by up to 0.06. Where the engine is already
   near-perfect a reorder cannot change much — so the defect matters *most* exactly where quality
   findings are most contested.
2. **step0's arms are uniformly negative** (all four cells, 199/200 queries reordered), whereas the
   781 cells split by corpus. So "bidirectional" is a property of the corpus, not noise.

**A caveat the script surfaced rather than hid.** On the 786 sweep, 254 of 962 queries per cell had
same-length but *non-identical* document sets between the trec file and `predictedDocIds` — not a
pure reorder, so not a valid A/B subject. The script skips them, reports the count as a WARNING,
and the printed `n` reflects only the valid queries (e.g. 708, not 962). The cause is uninvestigated
and is a separate question from this defect; the 786 numbers above are therefore sound but drawn
from a subset.

### What this does not settle

The 781 cells are a certification corpus, not the published release cohort. The README table and
the relevance-ratchet floors project from `release.v1.json`, a different run. The delta there is
**unmeasured** — but this establishes that the delta is real, non-zero, bidirectional, and of a size
worth a re-run. Running the same script over the release cohort's run directory is the next step,
and costs nothing.

## Provenance

**Correction (2026-07-31): this is not a new discovery, and an earlier draft of this document
implied it was.** The defect was found independently on **2026-07-01** during tempdoc 643's
post-implementation critical-analysis pass, and stated in the search-quality register at F-026 in
terms that leave nothing out:

> trec-based rank is structurally blind to the floor's reordering, since the floor never rewrites
> a hit's `score` field, only its list order.

That session did the right thing locally: it recomputed F-026's four numbers "from a trec-based
computation to the true final-response-order computation" and published the corrected figures,
retracting a claim ("shifted 5 queries rank_3_5→rank_6_10... confirming the gate fires") that had
been describing noise.

What did not happen is the step from *this experiment's numbers are wrong* to *the apparatus that
produced them is wrong*. `retriever.py` was never changed, so every subsequent `.trec`-derived
number — and every prior one — still carries the blindness that bullet named. This is
`fix-root-causes-not-symptoms` in its quiet form: nothing was suppressed or disabled, the local fix
was correct and honestly reported, and the general case simply never got raised.

That prior discovery **strengthens** everything above rather than diminishing it. The mechanism is
not my inference from reading code; it was established a month earlier by someone measuring, and
this document's contribution is the part that was missing — the blast radius (16 cells, three run
sets), the direction (bidirectional, corpus-keyed), the magnitude (up to ±0.06 nDCG@10), and which
register findings are load-bearing on it.

Also logged as an observation by another session on 2026-07-29 (`obs:retriever`), covering the
harness half of the mechanism. Tempdoc 799's pass flagged it as the highest-value unactioned
finding, which is how it reached this document.

## The aperture: which half of the cross-encoder the metric can see (2026-07-31)

The re-derivation this section was opened to do — recomputing the register's CE findings from
their original artifacts — **is not possible**: only `781-certification` and `786-sweep` survive on
disk, and F-001/F-002/F-006/F-008 rest on tempdoc 309's runs, which do not. Recorded as a limit
rather than worked around.

But the sharper question turned out to be answerable from source alone, and it changes how those
findings should be read.

### The geometry

Three constants decide what the metric can perceive:

| quantity | value | source |
|---|---|---|
| eval's requested `limit` | 10 | `scripts/jseval/jseval/retriever.py:105`, passed as `{"limit": top_k}` at `:169` |
| `justsearch.rerank.top_k` | 20 | `EnvRegistry.java:731` |
| fetch window | `max(limit, topK)` = 20 | `KnowledgeSearchEngine.java:577-578` |
| post-rerank trim | first 10 | `KnowledgeSearchEngine.java:1027-1028` |

So the cross-encoder does **two** things to a 20-candidate window:

1. **Selects** which 10 survive the trim.
2. **Orders** those 10.

The trec score is the pre-rerank fusion score, so `ir_measures` re-sorts the delivered 10 back into
fusion order. **Selection passes through to the metric; ordering is discarded.** The engine is
measured through a half-open aperture — and the closed half is the ranking one, which is what a
rank-sensitive metric like nDCG@10 exists to measure in the first place.

This also explains a detail that would otherwise look odd: the deltas are non-zero but not enormous.
They are exactly the size of the discarded ordering term, no more — the selection term was already
in the reported number.

### What that does to the register's CE findings

- **F-001 / F-006 ("CE model upgrade produces zero measurable difference on ANY corpus").**
  A model swap changes both channels, but two reranker models agree far more about *which* 10 of 20
  documents are best than about the order within them. The swap's signal therefore lands mostly in
  the channel the metric deletes. "Zero difference, within noise, on every corpus" is precisely the
  output an apparatus produces when the differing part has been discarded — so the observation is
  consistent with the finding being true, and equally consistent with it being an artifact. These
  two findings are **not refuted, but their evidentiary weight is far lower than the register
  states**, and no amount of re-reading the old numbers can separate the cases.
- **F-002 / F-008 ("CE hurts personal email, helps academic/legal").** These are *corroborated*,
  and from a channel their original evidence could not see. Splitting the 781 cells by corpus:

  | corpus | cells | ordering-channel delta | direction |
  |---|---:|---|---|
  | Enron (email) | 4 | −0.0586, −0.0463, −0.0102, +0.0176 | 3 of 4 **harmful** |
  | CLERC (legal) | 4 | +0.0037, +0.0199, +0.0363, +0.0382 | 4 of 4 **helpful** |

  That is F-008's exact shape, reproduced on an independent corpus pair through the previously
  invisible half of the CE's contribution. It also implies the register **understates** the harm on
  email and **understates** the benefit on legal, since in both cases the omitted term points the
  same way as the reported one.

The CE changes the top-1 result on 28–41 of every 50 queries. None of that has ever reached a
reported number.

### Consequence for the fix decision

This raises the stakes on the harness fix without changing its shape. Correcting the scoring does
not merely adjust published numbers; it re-opens two register findings that currently read as
settled and are load-bearing for the "is a better cross-encoder worth it" question. The honest
status for F-001/F-006 today is *unmeasured*, not *measured as zero*.

Riders recording this were added to the four findings in
`docs/reference/search-quality-register.md`.

## Superseded on the published corpora (2026-07-31, tempdoc 802)

This document was explicit that its cells were "a certification corpus, not the published release
cohort" and that the release delta was "**unmeasured**". Tempdoc 802 measured it — all five corpora
in `release.v1.json`, re-run and scored both ways with ir_measures — and **the directions found here
do not transfer**:

| | 781 cells (this doc) | published corpus (802) |
|---|---|---|
| Enron | mostly **harmful** | `mixed/enron-qa` **+0.0184 helpful** |
| Legal | 4 of 4 **helpful** | `mixed/legal-clerc-200` **−0.0418 harmful** |

Both signs reverse. `mixed/en-email-enron-raw-*` and `mixed/en-legal-clerc-*` are certification
corpora with synthesized queries; the published benchmarks are different datasets that happen to
share a name. The caveat in "What this does not settle" was the right one, and it is what made this
checkable.

Two further corrections from 802, recorded here so this document is not read as current:

- **"Effect size tracks headroom" does not hold on the published cohort** — `miracl-fr` at nDCG 0.87
  moves +0.0128 while `scifact` at 0.76 moves only −0.0061.
- **`metric_order_ab.mjs` is not precise enough to quote.** It reports `measured=0.7844` on
  `enron-qa` where ir_measures says `0.7807`, because the trec file's stale fusion scores contain
  ties its `sort()` breaks differently; the delivered side (strictly distinct synthetic scores)
  agrees exactly. Sound for detecting and ranking the effect — which is all this document used it
  for — but any figure reaching a public claim must come from ir_measures.

The core finding of this document is unaffected: the metric reconstructs fusion order and discards
the cross-encoder's ranking. 802 confirms that on the published corpora, where the top-1 result
changes on 30–53% of queries and the ordering term ranges −0.0418 to +0.0184.
