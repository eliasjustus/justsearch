---
title: "Retrieval quality vs corpus size: does JustSearch's hybrid retrieval hold as the corpus grows, or does the right document silently fall out of reach at scale? A diagnose-first investigation — separate the synthetic-corpus artifact from a real scaling defect, attribute the loss to a pipeline stage, then make size-robustness a measured, ratcheted property. North star: the probability the engine surfaces the right document in its top-K does not silently degrade as a personal/team corpus grows from hundreds to hundreds of thousands of files."
type: tempdocs
status: investigated 2026-07-08 (diagnose-first pass complete, no code changed) — VERDICT: at tested scale (≤~5k docs) the 624 signal is a SYNTHETIC-CORPUS ARTIFACT, not a product defect (already answered by `release.v1.json`: MIRACL-de-2k 3,103 docs = 0.852 nDCG vs battlefield 2,736 docs = 0.163, same engine). ANN recall decay (the doc's "leading candidate") is physically ruled out at this scale; the only real scale-sensitive lever is the fixed-20 reranker window on the unspliced 3-way path (636's turf), not ANN (639). Recommendation: DO NOT run Milestones A–C now; downgrade to a parked, evidence-gated robustness question. The only genuinely-open slice is recall-robustness at 10⁴–10⁶ on a REALISTIC corpus, for which no fixture exists and none is cheap — reopen only if that fixture is deliberately built. See the "Investigation (2026-07-08)" section for the full evidence chain and verdict. [was: open — investigation request; surfaced by 624 pass-26 on an ADVERSARIAL SYNTHETIC corpus.]
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

---

# Investigation (2026-07-08, agent Opus autonomous — no code changed)

> This section is the diagnose-first investigation the tempdoc asked for. It answers the "first question"
> above using **evidence that already exists on disk** plus a code/literature read of the four candidate
> mechanisms. Every load-bearing claim carries a `file:line` or a measured artifact. Verdict at the end.
> No fix, no design, no code changed — per the request.

## What I checked
- The empirical trigger (624 pass-26): its own numbers and the 624 author's own diagnosis.
- The four candidate mechanisms in code: dense/ANN (`adapters-lucene`), fusion + reranker window
  (`worker-services`, `app-services`), chunk collapse.
- Existing measurement infrastructure in `scripts/jseval/` (metrics, projections, ratchets, corpora).
- The HNSW literature, to size the ANN-approximation gap at this corpus scale.

## Finding 1 — The 624 fit tracks **confusable-head count**, not index size (artifact signature)
624 pass-26 fits `nDCG@10 ≈ 0.414·√(130 / heads)` across three points (624 §"Scale-corpus matrix
run", `docs/tempdocs/624-*.md:4310-4311`). Solving for the implied head-count: 390-doc→130 heads,
2736-doc→~838, 3120-doc→~1876. **The drop is a function of the number of near-identical descriptor-bearing
look-alikes, not of `N`.** That is the signature of semantic cluster-collapse (the measuring-stick
artifact), not of an index-size mechanism. The 624 author reached the same conclusion structurally:
"the same property that makes grep expensive … clusters those heads semantically and buries the gold …
a multi-thousand-doc *in-band* corpus is not constructible with this generator" (`624-*.md:4308-4314`).

Direct corpus inspection confirms it viscerally: `battlefield-en-scale-v1` docs are near-identical
templated boilerplate ("The surrounding district is known for long winters and quiet markets where
traders gather…") differing only in one entity descriptor, queried by hard 2-hop synonym-obfuscated
questions (`scripts/jseval/635-corpora/battlefield-en-scale-v1/{docs.jsonl,queries.json}`; 2,736 docs /
3,801 queries; `meta.json`: n_chains 380, distractor_ratio 1.4, doc_words 2500, semantic:true).
Real personal files do not share ~90% identical text — the tempdoc itself concedes this (explanation 1).

## Finding 2 — The "leading candidate" (ANN recall decay) is physically ruled out at this scale
- **Code:** the dense leg is Lucene approximate HNSW (`KnnFloatVectorQuery`), M=16 / efConstruction=200
  (`ChunkSearchOps.java:488-489`, `ReadPathOps.java:280-293`, `JustSearchCodec.java:32-35,64-66`,
  `ComponentsFactory.java:178-190`). Query-time `k` == request `limit` (×2 on chunk-merge), with an
  **optional static `ef_search` floor that is unset by default** (`ReadPathOps.java:296-303`,
  `RuntimeSession.java:320-325`). **Nothing scales `k`/`numCands` with corpus size** — so if ANN recall
  degrades with N, the engine does not compensate. There is **no exact/brute-force KNN path** anywhere
  (searched `bruteForce|exactKnn|FloatVectorValues|VectorScorer` → none), so an exact-vs-approximate
  recall comparison is not possible in-process today.
- **Literature:** at ~10⁴ vectors, standard HNSW achieves recall@10 ≈ 0.998; meaningful decay (→0.85–0.94)
  appears only at 10⁶–10⁷ vectors (ann-benchmarks; Elastic/Marqo HNSW writeups). The battlefield corpus is
  2,736 docs → even chunk-expanded (~10× → ~15–30k chunk vectors) it is ~2–3 orders of magnitude below
  where HNSW approximation bites. **ANN approximation cannot explain a 0.414→0.109 drop at this scale.**
- Corollary: because the docs are near-identical, even an *exact* KNN would collapse the gold into its
  look-alikes — the loss (if any leg misses) is **embedding geometry**, not the ANN approximation. The
  synthetic corpus therefore cannot implicate the ANN leg even where it shows a miss. **The tempdoc bets
  on the wrong leading mechanism.** (639's "do not open by tuning ANN" instinct is right; but 699 still
  frames ANN as the leading candidate, which mis-primes the next agent.)

## Finding 3 — DECISIVE: the tested-scale artifact-vs-defect question is **already answered on disk → artifact**
`scripts/jseval/release.v1.json` holds measured nDCG@10 for the same engine at one commit on **realistic,
diverse, qrelled** corpora:

| corpus | docs | nDCG@10 |
|---|---|---|
| `mixed/miracl-de-2k` | **3,103** | **0.852** |
| `mixed/miracl-fr-2k` | ~3,000 | 0.866 |
| `beir/scifact` | ~5,000 | 0.756 |
| `mixed/enron-qa` | — | 0.719 |
| `mixed/legal-clerc-200` | 198 | 0.516 |
| `battlefield-en-scale-v1` (synthetic) | 2,736 | **0.163** |

**MIRACL-de-2k has *more* documents than the battlefield corpus that scored 0.163, yet scores 5× higher
on the same engine.** This is the realistic-corpus control the tempdoc's Milestone A asked for — and it
already exists. At ~3k docs the engine is healthy on realistic documents (0.85) and only collapses on the
adversarial near-identical corpus. **Milestone A's "first question" (does a realistic corpus reproduce the
drop at these sizes?) is answered NO by data on disk. The 624 signal is the synthetic artifact.**

## Finding 4 — The one *real*, in-code, scale-sensitive mechanism is the reranker window, not ANN
There is a genuine size-sensitive recall gate in the pipeline, but it is **636's bounded-recall
(`CASCADE_LEAK`), not 639's ANN**:
- The cross-encoder reranks only a **fixed top-20** by fusion order (`KnowledgeSearchEngine.java:879-880`,
  `RerankerConfig.java:14-20,40`; `crossEncoderWindow` default 0 → falls back to `topK`=20). The Head only
  fetches `max(limit, 20)` from the Worker (`:552-555`). As a corpus grows and more competitors crowd the
  fusion ranking, a gold doc pushed below rank 20 is dropped **before the CE ever scores it**.
- The mitigation (`spliceRecallComplete`, top-N=10 per leg back into the pool) is wired **only in the
  2-way BM25+dense path** (`HybridSearchOps.java:477-490`); **SPLADE is unprotected and the shipped
  3-way Convex-Combination path (`runThreeWay`) does not call it at all** (`SearchExecutor.java:437-465`;
  weights default {BM25 0.60, dense 0.20, splade 0.20}, `ResolvedConfigBuilder.java:1489-1491`).
This is a real, corpus-independent, scale-sensitive lever — but it is a **ranking-window truncation**
(636's turf), whose fix is "widen the window / wire the splice into the 3-way path," measured before/after.
It is *not* an ANN-parameter problem. Note: on the synthetic corpus this window truncation is itself driven
by the semantic confusability (fusion can't lift gold above 900 look-alikes), so even it is triggered by the
artifact, not by N per se — which is why a **realistic large corpus** is the only clean test of it at scale.

## Finding 5 — The disambiguation tool already exists and auto-runs, but was never run on the scale corpus
- `staged_recall_accounting` (636/639 lineage) classifies each judged query into
  `LEG_MISS / CASCADE_LEAK / JUDGE_RANK_LOW / OK_RANK1` — exactly Milestone B's attribution — and it
  **auto-runs after every retrieval eval** (`scripts/jseval/jseval/projections/staged_recall_accounting.py`;
  registered `projections/__init__.py:61`; invoked `run.py:398-404`). jseval already computes recall@10 and
  nDCG@10 via `ir_measures` (`scoring.py:8`).
- **But no staged_recall output exists for `battlefield-en-scale-v1`** (searched; none). The 624 pass-26 run
  was an **agent-utility** run (`out/utility-comparison.v1.json` only), not a retrieval eval — so the
  recall-vs-ranking split that would attribute the drop was never computed. Also note the 624 evidence is
  **nDCG@10** (a ranking metric); "gold outside top-10" ≠ "gold outside the candidate set." The whole
  recall-framing of 699 rests on a set-membership claim that has not actually been measured.
- The generator already has the clean isolator 624 didn't use: hold `n_chains` fixed (query set + head-count
  constant) and raise `distractor_ratio` — distractors are never referenced by any query
  (`corpus_generate.py:654-668`), so this grows `N` at **constant semantic difficulty**, separating pure
  index-growth from cluster-collapse. The ratchet template for Milestone D also exists
  (`relevance_gate.py`, `ratchet_kernel.py`, `*-ratchet-baselines.v1.json`).

## Finding 6 — What 699 duplicates / displaces
699 is largely a **synthesis/coordination layer** over already-owned work: ANN recall (639, "unowned,
measurement-first"), the reranker-window bounded-recall + the `staged_recall` projection (636), ranking of a
present answer (643 = `JUDGE_RANK_LOW`), realistic-corpus construction (635), and the latency/recall trade of
any `ef_search` change (640's perf ratchet). Its **only distinct durable deliverable** is Milestone D — a
*cross-size* recall ratchet — which none of the others own. Milestones A–C, as scoped, mostly re-open 639/636.

## Verdict

**Should this tempdoc be done at all, and now? — Not as scoped, and not now. Re-scope and defer.**

1. **The empirical trigger is an artifact, and much of Milestone A is already answered.** The 624 drop tracks
   confusable-head count (F1), ANN is physically ruled out at 3k docs (F2), and realistic corpora at the same
   size are healthy (F3, on disk). The trigger provides essentially **no evidence of a real size-dependent
   defect** — it is the measuring-stick artifact the tempdoc's explanation (1) predicted. Treating it as a
   live product signal would be chasing an artifact (and the tempdoc points the next agent at the wrong
   mechanism, ANN — F2/F4).

2. **Cheapest evidence that would validate/invalidate the *residual* need — and does it exist?**
   - *At tested scale (≤~5k docs):* **already exists** → `release.v1.json` (F3). Verdict: artifact. £0.
   - *Attribution of the synthetic drop (if anyone still wants it):* one retrieval-eval run on the existing
     `battlefield-en-scale-v1` → read the **auto-generated** `staged_recall` buckets (F5). Minutes of
     compute, no new corpus. But it measures the *artifact* corpus, so its result mainly confirms F1/F4, not
     a product defect.
   - *The genuinely-open question — recall-robustness at 10⁴–10⁶ on realistic docs:* **no evidence exists and
     none is cheap.** No realistic fixture that large exists (the biggest realistic corpora are ~3–5k docs);
     building one is the expensive Milestone A. This — not the 624 trigger — is the only real gap, and it is
     a long-horizon robustness property, not a present defect.

3. **What it displaces/duplicates:** 639 (ANN), 636 (window + `staged_recall`), 643 (ranking), 635 (corpus),
   640 (perf). 699's only non-duplicative piece is the cross-size ratchet (Milestone D).

4. **Recommendation.** Downgrade 699 from "investigation with a live trigger" to a **parked, evidence-gated
   robustness question**, and record the three corrections above so the trigger isn't mistaken for a defect:
   (a) 624's drop is the synthetic artifact — settled at tested scale by `release.v1.json`; (b) the real
   scale-sensitive lever, *if one is ever needed*, is the fixed-20 reranker window on the unspliced 3-way path
   (636), **not** ANN (639); (c) the only work that would justify reopening 699 is building a **genuinely
   large realistic fixture** (10⁴–10⁶) and running the cross-size recall ratchet on it — a deliberate,
   non-urgent investment, out of step with the current release/adoption critical path (654-660; 624's own
   "next lever = 655 steering / powered adoption run"). **Do not run Milestones A–C now; Milestone D is the
   only durable idea and can wait for the large realistic fixture to exist.**

**Bottom line: this should wait for evidence X = "a realistic corpus at 10⁴–10⁶ docs shows a recall drop" —
and that evidence does not exist, is not cheap, and the cheap evidence we *do* have points the other way
(artifact). No fix is warranted; the product half of 699 closes as "artifact, at tested scale," with the
high-N robustness question parked under 639/636 until a large realistic fixture is deliberately built.**
