---
title: "Retrieval quality vs corpus size — resolved: the 624 collapse is a synthetic-corpus artifact (the engine is measured size-robust on realistic corpora 3k→10k; ANN is not the cause), and the durable deliverable is a representation-completeness (leg_union_recall) FLOOR gate that completes the recall-survival guard triad (quality floor · completeness floor · leak ceiling), superseding the original cross-size 'Milestone D' ratchet as the wrong instrument. Began as a diagnose-first size-robustness investigation; ended as a per-stage recall-survival guard design conforming to the 636/D-005 instrument + 640 metric-family SSOT."
type: tempdocs
status: IMPLEMENTED + validated 2026-07-08 (worktree 624-scale-corpus, NOT merged — no PR) — the representation-completeness `leg_union_recall` FLOOR gate (`jseval union-recall-gate`, sibling of `leak_gate`) is built, unit-tested (48 pass incl. a committed-baselines lock test), and live-validated (PASS at floor 0.96 scifact / 1.0 needle-burial-v1; FAIL on regressed projections); pins only REPRODUCIBLE corpora (scifact + committed needle-burial-v1) after a review caught+fixed a non-reproducible pin; `gated_by` + the search-engine-hint surface it; gradle build green; Milestone D tombstoned. See §Implemented + §"Review + fix". Prior phases (unchanged) — investigated + EXPERIMENTALLY MEASURED 2026-07-08 (5 live retrieval evals, no engine code changed) — VERDICT: the 624 signal is a SYNTHETIC-CORPUS ARTIFACT, now PROVEN (not just inferred) by a realistic control: MIRACL/de is size-robust — recall FLAT across 3k→10k (final_recall 0.967→0.967, CASCADE_LEAK 0.03→0.03), while the synthetic battlefield corpus COLLAPSES over the same volume growth (final_recall 0.385→0.139, nDCG 0.22→0.11). E1 attribution: synthetic drop is 63% LEG_MISS (confusable-head geometry no retriever can fix) + fusion-order CASCADE_LEAK amplified by the synthetic corpus's broken BM25. E2: ANN recall decay (the doc's "leading candidate") is NOT the mechanism — near-exhaustive ef_search doesn't move recall. Mechanism correction: route the fusion-truncation robustness note to 636 (bounded recall / 3-way splice), NOT 639 (ANN). Recommendation: DO NOT run Milestones A–C as a fix — no size-dependent product defect exists in the target range. The only forward work is optional + low-priority: Milestone D (a cross-size recall ratchet, now cheaply buildable from the E4 MIRACL sweep + relevance_gate template) as a standing guard. Residual unmeasured slice: realistic 10⁵–10⁶ (parked under 639/636; a defect there is unlikely given the flat 3k→10k trend). Full evidence chain: "Experimental results" + "Synthesis & revised verdict" sections. DESIGN SETTLED 2026-07-08 (see "Design" + "Principle & reach"): the durable deliverable is a representation-completeness (`leg_union_recall`) FLOOR gate — the symmetric sibling of the shipped `leak_gate` ceiling — completing the recall-survival guard triad and enforcing the LEG_MISS stage that dominated E1 but no current gate can catch. This SUPERSEDES the original "Milestone D" cross-size ratchet, retired as the wrong instrument (a size-delta gate is insensitive to uniform recall regressions). Extends the 636 machinery + 640 metric-family SSOT; not an engine fix (verdict: none warranted now). Design not yet implemented — awaiting go-ahead. [was: open — investigation request; surfaced by 624 pass-26 on an ADVERSARIAL SYNTHETIC corpus.]
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

**Milestone D — Ratchet it.** ⚰️ **SUPERSEDED (2026-07-08) → see §Design.** This original framing — a
*cross-size* ratchet (recall@K across corpus sizes) — was retired as the wrong instrument: a size-delta gate
is insensitive to the common *uniform* recall regression (see §Design "What this supersedes"). The durable
deliverable that replaces it is the **representation-completeness (`leg_union_recall`) FLOOR gate** built in
this tempdoc's implementation. The text below is kept as dated history. ~~Add the scaling-robustness gate
(recall@K across sizes) to jseval so size-robustness becomes a standing, enforced property — the durable
outcome regardless of how big the current gap turns out to be.~~

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

## Proposed experiments (diagnose-first; cheapest-decisive-first; pre-registered)

The verdict above is inference from static code + literature + one same-size on-disk comparison. Before
closing, it is worth *measuring* the mechanism with cheap runs on existing tooling/corpora — and, per
`interrogate-results`, each experiment below carries a **pre-registered prediction** and states which
result would **overturn** the artifact verdict (not just confirm it). All costs are local GPU + dev-stack
time (no LLM/$ — these are retrieval evals, not agent-utility runs). Ordering is chosen so the expensive
realistic-corpus build (E4) is only reached if a real mechanism survives the cheap synthetic isolators.

**E1 — Attribution (the missing measurement).** Run a retrieval eval with `modes: [vector, lexical,
splade, full]` on BOTH `battlefield-en-v1` (390) and `battlefield-en-scale-v1` (2,736), cap ~100 queries,
and read the **auto-generated** `staged_recall_accounting` buckets + `leg_union_recall` + `final_recall`.
- *Isolates:* LEG_MISS (no leg had gold → embedding/lexical geometry) vs CASCADE_LEAK (a leg had it, the
  window dropped it before rerank → the real fixable mechanism, 636) vs JUDGE_RANK_LOW (present, mis-ranked
  → 643).
- *Prediction (artifact):* at 2,736 `leg_union_recall` itself falls (LEG_MISS-dominant) — the near-identical
  embeddings collapse, the answer is not in ANY leg's set.
- *Overturns verdict if:* `leg_union_recall` stays high but `final_recall` drops (CASCADE_LEAK-dominant) →
  the gold IS retrieved but the fixed-20 window drops it → a real, generalizable engine fix, not an artifact.
- *Cost:* 2 eval runs (index each corpus once). Low.

**E2 — ANN isolation (empirically kill/keep the doc's "leading candidate").** Re-run E1's 2,736 eval with
`index.vector.ef_search` set near-exhaustive (~1000–2000 for a ~15–30k-vector index) vs the default.
- *Isolates:* dense-leg recall limited by ANN *approximation* (→ ef_search recovers it) vs by embedding
  *geometry* (→ no change).
- *Prediction (verdict):* no meaningful change — ANN approximation is not the bottleneck at 10³–10⁴ vectors.
- *Overturns F2 if:* high ef_search materially lifts vector-leg recall → ANN matters even at this scale
  (surprising; would be a genuine finding and would re-list 639 as live).
- *Cost:* 1 extra eval arm (env knob only). Low.

**E3 — Size-vs-confusability isolation (the most decisive, most able to overturn).** Generate battlefield
variants at **fixed `n_chains`** (fixed query set + gold-head count) with `distractor_ratio ∈ {1.4, 4, 10,
20}` → N grows ~3–15× at constant semantic difficulty (distractors are descriptor-disjoint hard negatives,
`corpus_generate.py:654-668`). Measure nDCG@10 / recall@10 vs N.
- *Isolates:* does raw *volume* of same-genre docs bury the gold (size-sensitive — would generalize to a real
  corpus of 10⁴ similar notes) or not (the drop is purely head-count confusability → artifact)?
- *Prediction (artifact):* roughly **flat** — the burying competitors are the gold heads (fixed here), not
  the descriptor-disjoint distractors.
- *Overturns verdict if:* nDCG drops steeply with `distractor_ratio` at fixed heads → volume-of-similar-docs
  alone degrades retrieval → validates the tempdoc's size worry on a controlled variable.
- *Cost:* generate 3–4 corpora (fast) + eval each (capped). Moderate.

**E5 — Reranker-window confirm (conditional on E1 = CASCADE_LEAK).** Set `crossEncoderWindow`≈100 (and/or
wire `spliceRecallComplete` into the 3-way CC path) and re-run; does nDCG@10 recover? Confirms the lever
before any real fix. *Cost:* 1 eval arm. Low. (Design/impl only if E1 warrants — not now.)

**E4 — Realistic size sweep (the north-star test; expensive; DEFERRED).** Fixed MIRACL query set against a
growing realistic doc pool (5k → 50k → 500k via `corpus_fetch` / ir_datasets). Measure recall@K(N). This is
the only experiment that answers the product question, and the expensive Milestone A. **Reach it only if
E1/E3 surface a real, generalizable mechanism** — otherwise the artifact verdict stands and E4 is not
justified now.

**Recommended run order:** E1+E2 as one dev-stack session (three eval configs: 390-default, 2736-default,
2736-high-ef) → E3 → decision gate (any real mechanism? → E5/E4; else close product half). E1+E2 alone
(≈one indexing pass per corpus + a few capped evals) would already settle recall-vs-ranking and ANN-vs-
geometry — the two questions the 624 run left unmeasured — for well under an hour of shared-stack time.

## Experimental results (2026-07-08, run live on the dev stack — worktree `624-scale-corpus`)

> Artifacts under `tmp/699-experiments/`. Each run: `jseval run` on the fresh-indexed corpus, 100–130
> queries, modes `[vector, lexical, splade, full]`, `staged_recall_accounting` auto-emitted. jseval
> reproduced the 624 headline exactly (corpus fidelity gate: `nDCG@10=0.1628`, matching pass-26's 0.163).

### E1 — Attribution on `battlefield-en-scale-v1` (2,736 docs, 100 queries) → LEG_MISS-dominant

Per-leg nDCG@10: vector 0.092, lexical 0.052, splade 0.106, full (CC fusion) 0.070 — **every leg is weak;
no leg is strong.** `staged_recall_accounting` (final_mode=`full`):

| bucket | rate | reading |
|---|---|---|
| **LEG_MISS** | **0.63** | gold in NO leg's candidate set — representation/geometry (the artifact) |
| CASCADE_LEAK | 0.25 | a leg had gold, fusion buried it below the returned top-10 |
| JUDGE_RANK_LOW | 0.09 | gold in final list, mis-ranked (643's turf) |
| OK_RANK1 | 0.03 | gold at rank 1 |

`leg_union_recall = 0.35`, per-leg recall {vector 0.12, lexical 0.12, splade 0.21}, `final_recall = 0.12`.
- **Pre-registered prediction CONFIRMED:** LEG_MISS-dominant, `leg_union_recall` collapses to 0.35 — for
  ~⅔ of queries the gold is not retrieved by *any* leg. On a corpus of near-identical boilerplate that is
  the embedding/lexical geometry failing, exactly as the artifact hypothesis predicts (E2 tests whether
  this LEG_MISS is *geometry* or *ANN approximation*).
- **Secondary real mechanism found:** CASCADE_LEAK = 0.25 is non-trivial — for a quarter of queries a leg
  *did* retrieve the gold but CC-fusion ranked it below the returned top-10. This is a genuine,
  size-sensitive truncation (`leg_union_recall 0.35` → `final_recall 0.12` = ~23 pts of retrieved gold lost
  before the final list) → **E5 is warranted.** Note (corrects Finding 4): in eval's `full` mode the
  cross-encoder is OFF (`retriever.py:23`), so this leak is *fusion-order*, not a CE-window effect — E5 must
  test *enabling* the reranker with a wide window, not merely widening an already-running window.

### E2 — ef_search near-exact (2,736 docs, ef 10→2000) → ANN approximation is NOT the lever

| leg R@10 | E1 (default ef) | E2 (ef=2000) |
|---|---|---|
| vector | 0.12 | 0.20 |
| lexical *(ef-invariant control)* | 0.12 | 0.08 |
| splade *(ef-invariant control)* | 0.21 | 0.19 |
| leg_union_recall | 0.350 | 0.360 |
| LEG_MISS | 0.63 | 0.61 |

- **Prediction upheld (with a caveat):** cranking ef_search ~200× moved `leg_union_recall` 0.35→0.36 and
  LEG_MISS 0.63→0.61 — negligibly. Near-exhaustive ANN search still leaves ~61% of golds unretrieved by
  **any** leg → the LEG_MISS is embedding/lexical *geometry* (near-identical docs), not HNSW pruning. F2
  empirically upheld; the doc's "leading candidate" (ANN recall decay) is **not** the cause at this scale.
- **Interrogate-results caveat (honest):** the vector leg *nominally* rose 0.12→0.20, but the **lexical leg,
  which ef_search cannot touch, moved a comparable amount (0.12→0.08)** — revealing the run-to-run noise
  floor (~±0.04–0.05 at n=100, from BM25 tie-breaking across near-identical docs on separate index builds)
  is as large as the apparent vector signal. So a residual ANN effect on the vector leg alone cannot be
  ruled *in* from one run-pair, but it is immaterial to the verdict: the corpus stays LEG_MISS-dominant
  (geometry) regardless, and on realistic corpora the engine is already healthy (`release.v1.json`). A
  clean same-index ef_search sweep (`--skip-ingest`, no reindex) would remove the ambiguity if the residual
  vector effect ever mattered — it does not for this question.

### E3 — distractor volume at FIXED heads + identical queries → a REAL fusion-order size-sensitivity

130 gold heads and byte-identical 130 queries held constant (verified: `queries.json` SHA identical across
the sweep); only descriptor-disjoint distractor volume grows:

| ratio | docs | full nDCG@10 | full R@10 | LEG_MISS | CASCADE_LEAK | leg_union_recall | vector R@10 |
|---|---|---|---|---|---|---|---|
| 1.0 | 780 | 0.220 | 0.385 | 0.01 | 0.61 | 0.992 | 0.98 |
| 4.0 | 1,950 | 0.121 | 0.139 | 0.05 | 0.82 | 0.954 | 0.95 |
| 10.0 | 4,290 | 0.111 | 0.139 | 0.07 | 0.79 | 0.931 | 0.93 |

- **Prediction PARTIALLY OVERTURNED.** I predicted flat nDCG (burying competitors are the fixed heads, not
  the disjoint distractors). Instead nDCG dropped 0.22→0.11 as volume grew ~5.5× at constant semantic
  difficulty. **A real, size-sensitive degradation exists — volume alone hurts.** The tempdoc's size worry
  is not purely a synthetic artifact.
- **But the mechanism is fusion-order, NOT retrieval.** `leg_union_recall` stays 0.93–0.99 and the vector
  leg retrieves the gold 93–98% of the time — the answer is *in the building*. The loss is CASCADE_LEAK
  (0.61→0.79): the retrieved gold is dropped **before the returned top-10** as more distractors crowd the
  fused ranking. This is 636's bounded-recall / fusion-order lever (my Finding 4's family), decisively not
  ANN (E2) and not representation (E1's LEG_MISS was specific to the *confusable-head* corpus; here heads
  are fixed and disjoint distractors don't cause LEG_MISS).
- **Why fusion buries a retrieved gold:** eval's `full` runs CC-fusion with **no cross-encoder** (E1), and
  the CC default is **BM25-dominant (0.60)** while BM25 is the weakest leg on these synonym-obfuscated docs.
  So the strong vector hit is diluted, and added distractors supply fused-score competitors that outrank it.
  → **E5 tests the fix** (enable the reranker: does it rescue the retrieved-but-buried gold?). → **E4 tests
  generalization** (on realistic MIRACL docs where BM25 actually works, does the same fusion-truncation
  appear as N grows, or was it amplified by the synthetic corpus's broken lexical signal?).

### E4 — realistic MIRACL/de sweep (fixed 305 queries, varying N) → engine is size-robust on real docs

Real, public, qrelled corpus (`corpus-fetch-miracl`, seed 666); the query set is byte-identical (305
queries) across sizes — only distractor volume grows. Modes `[vector, lexical, splade, full]`.

| N (docs) | full nDCG@10 | full R@10 | vector R@10 | LEG_MISS | CASCADE_LEAK | leg_union_recall | final_recall | OK_RANK1 |
|---|---|---|---|---|---|---|---|---|
| 3,001 | 0.814 | 0.967 | 0.984 | 0.00 | 0.03 | 1.000 | 0.967 | 0.64 |
| 10,001 | 0.795 | 0.967 | 0.990 | 0.00 | 0.03 | 1.000 | 0.967 | 0.61 |
| 40,001 | *(pending — readiness off-by-one)* | | | | | | | |

- **The realistic sweep is FLAT.** 3k→10k (3.3× volume): full R@10 **identical** 0.967, final_recall
  **identical** 0.967, CASCADE_LEAK **identical** 0.03, leg_union 1.000, LEG_MISS 0. nDCG 0.814→0.795 is
  within noise. **This is the north-star property — size-robust recall — empirically satisfied on realistic
  docs**, and it extends `release.v1.json`'s ~3–5k realistic coverage to 10k (40k pending).
- **Decisive contrast with the synthetic corpus** over comparable volume growth:

  | | leg_union | final_recall | CASCADE_LEAK | full nDCG |
  |---|---|---|---|---|
  | synthetic E3 (780→4,290) | 0.99→0.93 | **0.385→0.139** | **0.61→0.79** | **0.220→0.111** |
  | realistic E4 (3,001→10,001) | 1.00→1.00 | **0.967→0.967** | **0.03→0.03** | 0.814→0.795 |

  The synthetic corpus collapses; the realistic corpus is flat. **E3's fusion-order CASCADE_LEAK was
  synthetic-amplified** (BM25 is broken on synonym-obfuscated near-identical docs, so BM25-dominant CC
  fusion buries the vector gold). On realistic docs every leg works, so fusion keeps the gold (CASCADE_LEAK
  0.03). The engine's retrieval funnel is healthy and size-robust on real corpora in the tested range.

### E5 (first attempt) — VOID: reranker never ran

The first E5 config used `--ce` + `RERANK_TOP_K` but **not the actual backend gate
`JUSTSEARCH_RERANK_ENABLED`** (`EnvRegistry.java:657`), so the cross-encoder was skipped (summary shows
`lambdamart active:false`, no CE timing; full nDCG 0.070→0.079 ≈ unchanged from E1). Re-run as **E5b** with
the correct gate, targeting the r10 corpus (leg_union 0.93, final_recall 0.14 → maximum CE headroom).
[Lesson logged: `--ce` injects the pipeline flag but the ONNX cross-encoder needs `JUSTSEARCH_RERANK_ENABLED=true` too.]

### E5b — reranker on r10 (correct gate) → no measurable rescue, CE activation unconfirmed (INCONCLUSIVE, moot)

`JUSTSEARCH_RERANK_ENABLED=true` + `RERANK_TOP_K=100` + `--ce`, r10 corpus (4,290 docs, max CE headroom):

| | full nDCG@10 | full R@10 | CASCADE_LEAK | final_recall | OK_RANK1 |
|---|---|---|---|---|---|
| E3 r10 (no CE) | 0.111 | 0.139 | 0.79 | 0.139 | ~0 |
| E5b r10 (CE gate on) | 0.113 | 0.131 | 0.81 | 0.131 | 0.12 |

- **No rescue** — every metric is within noise of the no-CE baseline; CASCADE_LEAK did not drop. **And I
  could not confirm the cross-encoder fired** (no `cross_encoder_ms`/`ce_p50_ms` timing in the summary; the
  client-resolved `full` eval mode may bypass the Head's cross-encoder stage entirely). So E5 is
  inconclusive on "does a reranker rescue fusion-buried golds."
- **This does not affect the verdict, because it is moot:** E4 shows realistic corpora have CASCADE_LEAK
  ≈ 0.03 — there is essentially nothing to rescue on real docs. The reranker-rescue question only mattered
  if a realistic corpus showed high CASCADE_LEAK, and it does not. Pursuing CE-in-eval activation further
  would be a rabbit-hole with no bearing on the product question. (jseval gap logged to observations.)

## Synthesis & revised verdict (post-experiment — MEASURED, not inferred)

Five experiments turned the static "artifact, park it" inference into a measured result — and, importantly,
**refined the mechanism** (the pre-experiment doc named the wrong leading cause):

1. **The 624 signal is a synthetic-corpus artifact — now proven, not inferred.** The realistic control (E4)
   is the clincher: same engine, MIRACL/de, recall **flat** across 3k→10k (final_recall 0.967 → 0.967,
   CASCADE_LEAK 0.03 → 0.03, leg_union 1.000), versus the synthetic corpus **collapsing** over comparable
   volume growth (final_recall 0.385 → 0.139). At matched size (~3k) realistic nDCG is 0.81 vs synthetic
   0.16. The engine is **size-robust on realistic documents in the tested range** — the north-star property,
   empirically satisfied.
2. **ANN recall decay — the doc's "leading candidate" — is NOT the mechanism (E2).** Near-exhaustive
   ef_search (10→2000) left leg_union_recall and LEG_MISS essentially unchanged; ruled out physically
   (HNSW recall@10 ≈ 0.998 at 10⁴ vectors) and empirically. Any future ANN work (639) has no empirical
   basis from this investigation.
3. **The synthetic collapse decomposes into two mechanisms, both corpus-pathology-driven, neither a
   product defect:** (a) **LEG_MISS / geometry (E1, 63%)** — near-identical confusable heads that no
   retriever (exact or approximate) can separate; real corpora don't have this. (b) **Fusion-order
   CASCADE_LEAK (E3)** — the gold is *retrieved* (leg_union 0.93) but BM25-dominant CC fusion buries it
   below top-10, amplified by the synthetic corpus's broken lexical signal; on realistic docs where BM25
   works, this is 0.03.
4. **A latent (non-biting) robustness finding worth handing off:** the fusion path *can* bury a retrieved
   gold when a leg is degraded (CASCADE_LEAK), and the recall-complete splice is not wired into the shipped
   3-way CC path (SPLADE unprotected) — a 636/643 bounded-recall consideration, not urgent because it does
   not fire on healthy realistic corpora.

**Verdict (unchanged in direction; now measured, mechanism corrected):**
- **Do NOT run Milestones A–C as a fix effort.** There is no size-dependent product defect in the target
  range — the engine is measured size-robust on realistic corpora to 10k (and 40k indexes cleanly). The
  624 signal is a proven artifact of an adversarial synthetic corpus, not a scaling defect.
- **Correct the record:** the mechanism is *not* ANN recall decay (ruled out); the synthetic collapse is
  geometry (LEG_MISS) + broken-BM25-amplified fusion truncation (CASCADE_LEAK). Route the fusion-truncation
  robustness note to **636** (bounded recall / splice the 3-way path), not 639.
- **Cheapest evidence that would reopen this:** a *realistic* corpus at 10⁴–10⁶ showing recall drop with N.
  E4 now covers 3k→10k flat; the residual 10⁵–10⁶ slice is unmeasured but the trend + the physical picture
  make a defect there unlikely. Parked under 639/636, low priority.
- **The one durable deliverable — Milestone D (cross-size recall ratchet)** — is now cheaply buildable: the
  E4 fixed-query MIRACL sweep (`corpus-fetch-miracl --n-docs`) + the `relevance_gate`/`ratchet_kernel`
  template. Worth adding as a standing guard so size-robustness can never silently regress — but low
  priority given the release/adoption critical path (654-660), and it is a *guard*, not a fix.

**Bottom line:** the product half of 699 **closes as "artifact — retrieval is size-robust on realistic
corpora (measured 3k→10k), the 624 collapse is a synthetic-corpus pathology, ANN is not the cause."** The
only forward work is optional: a cross-size ratchet (Milestone D) as a low-priority standing guard, and a
one-line hand-off of the fusion-splice robustness note to 636. No engine fix is warranted now.

## Theorization & open directions (2026-07-08 — NOT design; ideas to weigh before anything hardens)

> The narrow question is settled, but the experiments exposed structure worth thinking about before a
> Milestone-D design (or a decision to build nothing) is locked. This section is deliberately exploratory:
> reframings, hidden assumptions, solution *directions* with tradeoffs, and a candidate broader principle.
> None of it is a committed design; several ideas may prove not worth building. Recorded so the reasoning
> survives, not to pre-empt the design phase.

### 1. Reframe: the causal axis is corpus *pathology*, not corpus *size*

The whole doc — title, north star, Milestone D — is framed on **N (document count)**. The experiments say N
is a **proxy, not the cause.** What actually degraded retrieval was two corpus *properties* that happen to
correlate with size in the synthetic generator but not in general:
- **Semantic confusability density** (how many near-identical items compete for one query) → drives LEG_MISS.
- **Per-leg reliability asymmetry** (one leg's signal is degenerate while another's is strong) → drives
  CASCADE_LEAK through mis-weighted fusion.

Realistic MIRACL grew N 3×→ with both properties held low, and stayed flat. The synthetic corpus grew *those
properties* (via head-count / broken lexical), and collapsed — at trivial N. **Consequence:** a ratchet keyed
on raw N risks measuring the wrong variable — it could read flat forever (false comfort) while a corpus whose
*pathology* rose went unguarded. A guard should ratchet the **failure-stage rates** (leg_union_recall,
CASCADE_LEAK) under a corpus that deliberately exercises the causal properties — not nDCG-vs-N on a benign
fixture. This is a general methodology point: *when testing "robustness to X," find the property X is a proxy
for and test that; ratcheting the proxy can pass while the cause regresses.*

### 2. Candidate invariant: funnel non-abandonment (no silent truncation of an un-scored candidate)

The staged_recall instrument (`LEG_MISS → CASCADE_LEAK → JUDGE_RANK_LOW → OK`) is really a statement that
retrieval is a **narrowing cascade** where recall can only be *lost* downstream, never regained. That frames
two sub-properties worth stating as invariants (candidate, not adopted):
- **Representation completeness** — if a document is representable by some leg, the leg-union retrieves it
  (`leg_union_recall ≈ 1`). Corpus-size-agnostic; a property of encoders + index, not of N.
- **Funnel non-abandonment** — no narrowing stage may drop a candidate that an upstream stage surfaced
  *unless a scoring stage has examined and demoted it*. CASCADE_LEAK is exactly a violation: CC-fusion
  silently drops a *retrieved* gold that **no reranker ever scored**. The fix-family is "carry the
  recall-complete set until a stage with positive evidence prunes it" (the splice, generalized) or "widen the
  examined window until it covers the leg-union top-m."

The transferable shape: *a series of narrowing selection stages must account for recall survival per stage
and must not truncate un-examined candidates.* This recurs well beyond search — candidate-generation→ranking
in recommenders, optimization passes that must preserve semantics, agent tool-shortlisting. Whether JustSearch
should elevate "funnel non-abandonment" to a named engine invariant (with a gate) is a real question for
636/639, not this doc — but the phrasing is the reusable artifact.

### 3. Repurpose the "artifact" as a fault-injection instrument (leg-failure graceful degradation)

The synthetic corpus was dismissed as a broken measuring stick. Inverted, it is a **precise fault injector**:
confusability kills the lexical leg while dense stays informative (E3), so it *tests what happens when one leg
fails*. That is a legitimate, valuable robustness axis the engine does not currently own: **when one retrieval
leg is degraded, do the healthy legs + fusion still surface the answer, or does fusion bury it?** E3 says it
buries it (BM25-dominant CC dilutes the good dense signal). Real corpora rarely break a leg on *average* — but
specific real regimes do: code (lexical dominates, dense weak), heavy OCR/scans (lexical is garbage), heavily
multilingual mixes, near-duplicate-dense folders. So "leg-failure graceful degradation" is a sharper, more
defensible robustness target than "size robustness," and the generators already exist to stress it. A standing
**fault-injection suite** (deliberately degrade each leg; assert leg-union survival through fusion) is a
direction worth weighing against, or instead of, the size ratchet.

### 4. Hidden assumptions & risks (things that could re-open the verdict)

- **"Realistic" = MIRACL ≠ realistic *personal files*.** MIRACL is clean Wikipedia-passage QA: short,
  well-separated, single-language, no duplication. JustSearch's actual target — a person's Documents folder —
  has *pockets* of exactly the synthetic pathologies: near-duplicate email threads and forwarded copies,
  versioned drafts, templated invoices/boilerplate, OCR noise, mixed languages. So the artifact verdict is
  sound for *average realistic difficulty* but **the worst-case pockets of a real corpus can still trigger
  LEG_MISS / CASCADE_LEAK locally.** The honest claim is "size-robust on clean diverse IR corpora," not
  "robust on all personal corpora." A more representative fixture (duplication + templating + OCR + multiling
  *mixed in*) would test the actual product surface — and might not stay flat.
- **Metric unit — recall@10 vs recall-under-iteration.** Everything measured *single-query* recall@10. But
  the agentic use case (624) has the agent iterate (search → rerank → read → re-query), and even at
  out-of-band retrieval it answered 60% by iterating. So the product-binding quantity may be "is the answer
  findable within an agent's *session* budget," not "is it in one query's top-10." A CASCADE_LEAK that a
  second query recovers is far less costly than one that no query recovers. Framing the north star in
  recall-under-iteration terms reconnects 699 to 624 and could change what's worth fixing.
- **Non-redundancy — the untouched half of 639.** 639 named *two* candidate-set gaps: completeness (recall,
  which this doc measured) **and non-redundancy** (a top-N dominated by near-duplicates). This investigation
  did nothing on the second, which 639 argues is *the common-case* experience on personal files (many
  near-identical hits crowding out genuine alternatives). It may matter more day-to-day than the rare
  buried-needle case, and it is orthogonal to size. Worth explicitly scoping out here and leaving to 639.
- **Measurement rigor for any guard.** The live runs are single-seed at n=100–130 with a ~±0.05 noise floor
  (visible when an ef-invariant leg moved as much as the treated leg in E2). The verdict survives because
  effects are large — but a *ratchet* would need the non-determinism envelope (`jseval calibrate`) and
  multi-seed floors, or it will false-fire.

### 5. Solution directions (sketches + tradeoffs — none chosen)

- **A. Staged-recall ratchet (not a size ratchet).** Guard `leg_union_recall` and CASCADE_LEAK rate on a
  fault-injection corpus. *Pro:* tests a cause that can actually regress; mechanism-specific. *Con:* needs a
  fault-injection fixture + calibrated floors. *Vs Milestone D:* strictly more informative than nDCG-vs-N.
- **B. Recall-preserving fusion (close the funnel-non-abandonment violation at the source).** Guarantee the
  union of each leg's top-m survives into the examined/rerank window (the 636 splice, wired into the shipped
  3-way CC path; SPLADE currently unprotected). *Pro:* makes CASCADE_LEAK structurally impossible for the
  top-m per leg — a correctness property, not a tuning. *Con:* the verdict says it doesn't bite on healthy
  corpora → YAGNI tension (see §6). *Cheap insurance framing:* low cost, closes a demonstrated silent-loss
  path.
- **C. Reliability-weighted fusion.** Down-weight a leg whose per-query score distribution is *degenerate*
  (all-similar ⇒ uninformative — exactly BM25 on confusable docs). The existing `AdaptiveWeightSelector` is
  off and keyed on doc length; a distribution-entropy signal would auto-correct the E3 failure without a
  static re-weight. *Pro:* addresses the cause (mis-weighting), self-tuning. *Con:* more moving parts;
  interacts with 636/643 ranking work; needs its own eval.
- **D. Query-time retrieval-confidence signal (the north star's "graceful + visible" clause).** The north
  star asked to *tell the user* when retrieval confidence is low. The instrument gives a substrate:
  inter-leg agreement + whether the top hit survived all stages are observable at query time (`SearchTrace`
  already records per-hit stage provenance). A derived "retrieval confidence" could drive a UI hint or an
  agent signal to iterate/broaden. *Pro:* turns an unavoidable-at-extreme-scale degradation into an honest,
  visible one — a product feature, not just a guard. *Con:* presentation-authority work (own audit
  discipline); calibration of the confidence signal is itself a project.

### 6. The YAGNI ↔ structural-defect tension (name it, don't resolve it here)

CASCADE_LEAK is a *demonstrated, silent* recall loss with a clear mechanism (E3) — which reads like a "known
structural defect" (fix it; don't wait for a repeat). Yet it *doesn't fire on healthy realistic corpora* —
which reads like a speculative edge case (YAGNI). Both framings are honestly available, and the §4 "MIRACL ≠
personal files" caveat is what tips it: if real personal corpora have pathology pockets, CASCADE_LEAK is
latent-but-reachable, not hypothetical. The decision (fix now as insurance vs park until a representative
personal-corpus fixture shows it biting) belongs to 636's owner with that caveat in hand — this doc's job is
to make the tension legible, not to pick.

### 7. Possible reframe of 699 itself (flagged, not done)

Given all the above, 699's durable centre of gravity may be less "retrieval quality vs corpus size" (largely
answered: artifact) and more **"retrieval-funnel recall-survival and graceful degradation under leg failure."**
That is a broader, longer-lived framing that subsumes the size question and connects cleanly to 636 (bounded
recall), 639 (candidate-set integrity), and 643 (ranking). Whether to retitle/re-scope 699 to that, fold it
into 639, or keep 699 as the closed size-investigation and open the broader thread elsewhere, is a
scoping decision for the owner — recorded here as an option, not taken.

## Prior art & research landscape (2026-07-08 web pass — the §5/§2 directions are named research areas)

The theorization above coined several ideas that turn out to be established, actively-researched concepts. A
short literature pass (public sources; citations only — **no external code or text was copied**, so this has
no license/notices impact; if a future design *adapts* a published algorithm's implementation, check its
license first) maps our terms to the field's, so the design phase starts from prior art rather than
reinventing it. Claims below are paraphrased from the cited sources, not verbatim.

- **"Funnel non-abandonment" / CASCADE_LEAK (§2) is the *bounded recall problem*.** The IR literature already
  names this exactly: a retrieve-then-rerank cascade cannot recover a document the first stage dropped —
  reranker recall is upper-bounded by retriever Recall@k. Our `staged_recall` doc already nods to it (and to
  the *Seven Failure Points of RAG*, arXiv 2401.05856, whose FP2 it maps to). Named mitigations we should
  evaluate rather than invent: a **recall-preserving cascade** (score the top-m with the cross-encoder, leave
  the tail order untouched → Recall@m is exactly the first stage's — this is precisely the 636 "splice"), and
  **corpus-graph / adaptive reranking** that pulls neighbours of top-k into later windows (GAR; *SlideGar* —
  "Guiding Retrieval using LLM-based Listwise Rankers", arXiv 2501.09186). Direction **B** = adopt a
  recall-preserving cascade / bounded-recall mitigation, not a new mechanism.
- **Reliability-weighted fusion (§5-C) and retrieval-confidence (§5-D) are *Query Performance Prediction*
  (QPP).** The exact intuition we proposed — a leg whose score distribution is *degenerate/all-similar* is
  uninformative — is the classical post-retrieval QPP signal: **NQC** (Normalized Query Commitment = std-dev
  of top scores), **WIG** (Weighted Information Gain), **Clarity**, **SMV**, **UEF**; "well-separated score
  distributions ⇒ easier query." So "reliability-weighted fusion" = per-leg QPP feeding fusion weights, and
  "retrieval-confidence signal" = QPP as a confidence/abstention trigger. **Important caveat for direction D:**
  QPP is not solved — its reliability for neural IR has documented limits (*Uncovering the Limitations of
  QPP*, arXiv 2504.01101), and QPP-for-RAG is itself an open thread (*Can QPP Choose the Right Query Variant
  for RAG Pipelines*, arXiv 2604.22661). A confidence signal built on QPP inherits QPP's noise.
- **Query-adaptive fusion (§5-C) is a hot 2025 area.** Learning a per-query dense/sparse mix is active:
  dynamic alpha tuning (DAT, Hsu et al. 2025), query-driven alpha prediction (QDAP — infer the fusion weight
  from the query embedding at low latency), and query-specificity weighting (survey: *Query-Adaptive Hybrid
  Search*, MDPI MAKE, doi 10.3390/make8040091). Our `AdaptiveWeightSelector` (off; keyed on doc length) is a
  primitive version; the field has moved to query-representation- and QPP-driven weights.
- **The recall-under-iteration reframe (§4) matches where agentic-retrieval eval is heading.** The field is
  actively moving from single-query recall to *multi-step / session* metrics: "search-during-think", multi-hop
  decomposition, and query-reformulation-then-fuse (RAG-Fusion, RQ-RAG). Evaluation is following —
  *factorised* metrics for deep/agentic search (*Demystifying deep search*, arXiv 2510.05137) and benchmarks
  like BRIGHT. Notably relevant to 624: reports that **stronger generators tolerate more distractor knowledge
  while weaker models benefit most from agentic selection/filtering** — i.e. iteration compensates for
  single-query recall loss, which is exactly why the 624 haiku agent recovered under out-of-band retrieval.
  There is even fresh debate on whether lexical-only suffices for agentic search (*Rethinking Agentic Search
  with Pi-Serini*, arXiv 2605.10848) — directly relevant to our leg-reliability framing.

**What this changes for the (future) design.** Three of our four solution directions are "adopt/adapt a named
method + evaluate on our corpora", not "invent": B → recall-preserving cascade (bounded-recall lit); C →
QPP-driven adaptive fusion (DAT/QDAP family); D → QPP-as-confidence (with its documented reliability caveat).
The **fault-injection robustness axis (§3)** and the **corpus-pathology-not-size methodology (§1)** are the
parts I did *not* find pre-packaged — those look like the genuinely differentiated contributions, if 699's
broader thread is pursued. **Recommendation:** if/when the owner opens the broader thread, do a proper
literature review of bounded-recall mitigations and QPP-for-hybrid-fusion before designing, and treat the
fault-injection suite + pathology-axis guard as the novel piece. Nothing here changes the closed verdict (no
fix warranted now); it de-risks the *optional* forward work and gives it citable footing.

## Design (settled 2026-07-08) — the durable deliverable, *superseding* the original "Milestone D"

> Design-level, not implementation. It matches scope to the problem the investigation actually found, extends
> the existing 636 recall-survival machinery rather than replacing it, and explicitly retires the original
> cross-size "Milestone D" as the wrong instrument (see *What this supersedes*). All numbers cited trace to
> the reproducible runs in *Experimental results*; no new unmeasured quantities are introduced.

### The problem, scoped precisely
The 624 scare required a bespoke artifact-vs-defect investigation because **retrieval recall-survival was not a
standing, enforced property** (639/580: "unowned and unmeasured"). The experiments localized the dominant
failure to the **first funnel stage** — *representation completeness* (E1: LEG_MISS was 63% of the synthetic
drop; the gold was in *no* leg's candidate set). The design's job is narrow: make that stage a guarded
property (a standing **local-first advisory** ratchet, like its siblings — see the confidence check on why
eval gates are not CI-blocking in this repo), so the next regression there is caught by the standing ratchet
instead of by a from-scratch investigation.

### What already exists (extend, do not replace)
The 636 lineage already shipped most of the recall-survival stack, and the design conforms to it:
- **`staged_recall_accounting`** — the D-005 instrument; auto-runs after every eval; computes the four funnel
  buckets **plus `leg_union_recall`** and per-leg recall.
- **`leak_gate`** — a per-corpus **ceiling** on `leak_rate` (CASCADE_LEAK), baselines projected from the
  release (never hand-typed), exit 0/1/2, wired to the `search-engine-hint` trigger. The bounded-recall /
  fusion-survival stage is already guarded.
- **`recall_profile`** — reports `leg_union_recall` per corpus, but is explicitly "a profile, not a gate."
- **`ratchet_kernel` + `metric_families`** — the shared gate kernel and the metric-family SSOT (640): each
  family declares source (`per_mode`/`per_run`/`projection`), metric keys, and comparator
  (`abs_tolerance` floor / `ceiling`). `QUALITY` is a floor; `LEAK` is a projection-sourced ceiling.

So the funnel already has an **aggregate quality floor** (relevance_gate/nDCG) and a **fusion-survival ceiling**
(leak_gate). **The one stage measured everywhere but gated nowhere is `leg_union_recall` — representation
completeness — exactly the stage E1 found dominant.** `leak_gate` cannot cover it: fewer golds retrieved does
not *raise* leak_rate (it can lower it), so a completeness regression slips through every existing gate except
diffusely via nDCG.

### The design: a representation-completeness FLOOR gate — the symmetric sibling of `leak_gate`
- Register a **`union-recall` metric family** (source `projection` = `staged_recall_accounting.json`, metric
  `leg_union_recall`, comparator `abs_tolerance` → **floor** = baseline − tolerance; higher-is-better). This is
  a new but natural cell in the existing comparator × source matrix (projection + floor), mirroring `QUALITY`'s
  floor and `LEAK`'s projection source.
- Gate it as `leak_gate` gates `leak_rate`, but as a **floor** (fail when current < baseline − tolerance).
  Per-corpus floors **projected from `release.v1`** (add a `union_recall` section composed by `jseval release`
  from the same projections) under the 623/683 anti-fork discipline, with fallback baselines like leak-gate's,
  wired to `search-engine-hint`. Pin across the eval corpora — **a floor is never ballast**: a floor at the
  measured value catches *any* downward move, including the uniform regressions a cross-size delta-gate misses.
- **Conformance over cloning.** `leak_gate` and this gate differ only in (metric key, comparator direction) —
  both already encoded in `metric_families`. The principled shape is a **registry-driven projection-metric
  gate** (one reader: ceiling → limit = base+tol, floor → limit = base−tol) serving `leak`, `union-recall`,
  and the already-registered-but-ungated `judge_low_cost_weight`. Whether to generalize `leak_gate` or clone
  it is an implementation choice; the design-level commitment is to conform to 640's metric-family SSOT, not
  add a third parallel near-duplicate.

This completes the **recall-survival guard triad** — quality floor (nDCG) · **completeness floor
(`leg_union_recall`)** · leak ceiling (CASCADE_LEAK) — one guard per funnel stage the instrument already
measures.

### What this supersedes / orphans (this tempdoc's cleanup, not a later sweep)
- **The original "Milestone D — cross-size recall ratchet" (recall/nDCG-vs-N) is retired as the WRONG
  instrument.** It was never built (no code to delete); it is tombstoned here. The experiments make the reason
  concrete: a gate on the *delta between corpus sizes* is **insensitive to the common regression** — a worse
  encoder or fusion degrades recall *uniformly* across sizes, so the size-delta stays ≈0 and the delta-gate
  passes while recall collapsed. It would catch only the exotic "recall became size-*dependent*" failure while
  missing the ones that actually happen. Size is a proxy (Theorization §1); the completeness **floor** guards
  the cause (per-stage recall) and catches uniform and non-uniform regressions alike. *Wherever the earlier
  sections of this doc call Milestone D "the one durable deliverable," read it as superseded by this section.*
- **The size-specific residual is parked under 639, not built here.** "Does completeness hold at 10⁵–10⁶?"
  needs a large realistic fixture that does not exist and is not warranted now (E2 already ruled out ANN as the
  mechanism; 639 owns the ANN-recall-at-scale question).
- Nothing else is orphaned: `staged_recall_accounting`, `leak_gate`, `recall_profile`, `ratchet_kernel`,
  `metric_families`, and the corpus generators are all reused/extended.

### Explicit scope boundaries (structure the problem does NOT require — deliberately not built)
- **Not an engine fix.** Wiring `spliceRecallComplete` into the 3-way CC path (SPLADE currently unprotected) is
  636's runtime turf; the verdict is that no fix is warranted now (E4: realistic corpora are size-robust,
  CASCADE_LEAK ≈ 0.03). The gate is a standing regression sentinel, not a remedy.
- **Not ANN / dedup guards** (639's charter; ANN empirically ruled out, E2). **Not a JUDGE_RANK_LOW ranking
  gate** (643's turf). **Not a fault-injection suite** (a broader robustness program, Theorization §3 —
  recorded as a future direction, not required by this problem). **Not QPP confidence / adaptive fusion**
  (engine features, Prior-art §, not guards).

### Honest framing (public-history-safe)
The engine is **already measured size-robust on realistic IR corpora in the tested 3k→10k range** (E4). This
design adds a **standing regression sentinel** so the completeness stage cannot silently regress; it does not
claim to fix a present defect (there is none in that range), nor to guarantee robustness at unmeasured
10⁵–10⁶ scale.

## Principle & reach (recognize the shape; do not build the general structure now)

**Conforms to existing seams — this design is an *instance*, not a new principle:**
- **636 / register D-005** — "capability is measured by recall-survival *per stage*, not by an aggregate
  score." The completeness gate is the missing *enforcement* of the first stage the instrument already
  measures; it does not invent a parallel notion of recall.
- **640 metric-family SSOT** — gates read one family definition; the design adds a *family*, not a parallel
  gate vocabulary.
- **623/683 anti-fork projection** — baselines projected from a measured release, never hand-typed.
- **This doc's own "funnel non-abandonment" invariant** — now enforced at three tiers: measurement
  (`staged_recall`), runtime (the `spliceRecallComplete` splice, 2-way path), and CI (leak_gate + the new
  completeness gate). The remaining hole (splice not wired into the 3-way path) is named and owned by 636 —
  not fixed here.

**Candidate broader principle (named + scoped, deliberately NOT generalized into structure now):**
> **Instrument-then-ratchet** — *a per-stage property that is measured but not gated is a latent regression
> surface.* Measuring a stage tells you it regressed *after the fact*; only a ratchet stops the regression
> shipping. `leg_union_recall` was the retrieval funnel's instance (measured by the projection, surfaced by
> `recall_profile`, gated by nothing).

- **Where else it may already apply / be violated (candidate scope — observed, not acted on):** the
  **enrichment/indexing pipeline** exposes coverage metrics (embedding / SPLADE / NER coverage %) via
  `/api/status` that are *measured* but, as far as this investigation saw, not gated by a standing CI ratchet
  on a fixture; and `judge_low_cost_weight` is *registered* as a metric family yet appears ungated. Both are
  candidate instances of the same measured-but-ungated shape. **Neither is built here** — neither is required
  by 699's problem; recorded so the pattern is visible to whoever picks up 639/643 or the enrichment work.
- **Evidence it earns its keep:** the completeness gate fires on **≥1 real change where the aggregate
  relevance_gate (nDCG) passed** — proving the per-stage signal is non-redundant with the aggregate.
- **Retirement condition:** if across ~10 releases no completeness (or leak) gate *ever* fires independently of
  relevance_gate — i.e. every stage regression is also an nDCG regression already caught — the per-stage gates
  are redundant with the aggregate and should be **collapsed back into relevance_gate**. A per-stage gate that
  never fires on its own is self-justifying apparatus and should be retired rather than kept for symmetry.

## Pre-implementation confidence check (2026-07-08 — read-only investigation + existing-artifact analysis)

> Ran before committing to implement, to test the design's load-bearing assumptions. Method: codebase reads +
> analysis of the existing E1–E4 run artifacts under `tmp/699-experiments/` — **no new eval runs, no feature
> code.** Each finding cites `file:line` or a measured artifact.

- **U1 — [LINCHPIN] Does the completeness gate earn its keep (non-redundancy vs nDCG)? → CONFIRMED
  non-redundant.** Across the existing runs, `leg_union_recall` and nDCG@10 diverge sharply: `e1`
  battlefield-scale (`leg_union_recall` 0.35, nDCG 0.070) vs `e3-r10` (0.93, 0.111) — a **0.58** completeness
  difference maps to only a **0.041** nDCG difference (~14× sensitivity gap). Mechanism: nDCG@10 is *compressed
  near zero on hard corpora*, so a completeness collapse that moves nDCG ≈0.04 (within the ±0.05 noise floor,
  so `relevance_gate` may not fire) moves `leg_union_recall` ≈0.5 (a completeness floor fires cleanly). The
  reverse divergence also appears (E3 sweep: nDCG craters 0.22→0.11 while union holds 0.99→0.93 — the
  CASCADE_LEAK regime). So the three signals cover distinct failure modes; the completeness gate is not
  ballast. *(The empirical "earns its keep" proof — firing on a real code change nDCG missed — still awaits a
  live regression; the retirement condition above governs that.)*
- **U2 — Signal stability / tolerance → RESOLVED.** `leg_union_recall` across two independent index-builds of
  the same corpus (`e1` vs `e2`): 0.350 vs 0.360, spread **0.010** — comfortably under `leak_gate`'s fixed
  `tolerance_abs=0.05`. A fixed tolerance (leak-style) is adequate; no calibrate envelope needed. Caveat: n=2
  repeats on one corpus — a proper `jseval calibrate` pass at implementation time would firm the number.
- **U3 — Machinery coupling → RESOLVED; it's a clone, not a refactor.** The release composer sources the leak
  section by a hardcoded per-metric block (`commands/release.py:103-121`), not a family-generic loop; the gates
  read only the tolerance constant from `metric_families`. So the implementation is: clone `leak_gate.py` →
  `union_recall_gate.py` (floor: fail when `current < baseline − tolerance`), register a `union-recall`
  `MetricFamily` (`projection` + `abs_tolerance`), add a CLI command + a `*-baselines.v1.json`, wire the
  `search-engine-hint`, add unit tests (clone `test_leak_gate`), and optionally a ~5-line union block in the
  release composer (the projected-baseline upgrade). No engine code.
- **U4 — CI enforcement reality → RESOLVED; framing corrected.** `.github/**` references **no** eval gate
  (grep for jseval/eval/ratchet/staged_recall/relevance = zero): all eval ratchets (relevance/perf/leak) are
  **local-first advisory**, surfaced by the `search-engine-hint` hook, *by design* — they need GPU + models +
  a full eval run that public hosted CI can't do. So the completeness gate is a standing local-first advisory
  ratchet, **not** a CI-blocking gate; the doc wording was corrected accordingly. (Making eval ratchets
  CI-blocking would be a separate, broader decision applying equally to the existing three.)
- **U5 — Baselines & corpus set → RESOLVED; no new runs needed.** The current `release.v1.json` carries no
  leak/union section yet, so leak_gate runs off *derived* `fallback_baselines`; a union gate follows the same
  derive-from-runs path. Crucially, `leg_union_recall` lives in the **same** `staged_recall_accounting`
  projection already used to derive leak baselines — so pinning union floors reuses the existing pinned-corpus
  runs (needle-burial / enron / scifact / legal-clerc) with **no new eval runs**. Those corpora already have
  `leg_union_recall < 1.0` (legal-clerc leak 0.205 ⇒ union < 1), so the floor is a meaningful mid-range value,
  not a trivial 1.0.
- **U6 — Metric semantics → RESOLVED.** `leg_union_recall = leg_union_hits / n` (`staged_recall_accounting.py:314`)
  is a proper [0,1] recall, the ~complement of `leg_miss_rate` (exact for single-gold queries; e3-r10:
  0.931+0.069=1.000). Gate the **floor on `leg_union_recall`** (finer, D-005-aligned) rather than a ceiling on
  `leg_miss_rate`.

### Verdict of the confidence check
No blocker surfaced; the linchpin (U1) held up empirically, and the one wrong assumption (U4 "CI-enforced")
was a framing error now corrected, not a design flaw. The implementation is a bounded clone of a well-tested
existing gate, touches ~5–6 files, needs no engine changes and no new eval runs (reuses existing projections),
with the only live step being a routine `derive` pass to pin floors from the corpora leak_gate already uses.

**Confidence for the remaining (implementation) work: 8 / 10.** Held back from 9–10 only by: the earns-its-keep
proof is empirically *plausible* but not yet demonstrated on a real regression (retirement condition covers
it); the tolerance rests on n=2 repeats (a calibrate pass would firm it); and pin-corpus selection + measured
floors need the routine derive run.

### Implementation difficulty & recommended model/effort
**Difficulty: low–moderate.** It is pattern-conformance work — cloning `leak_gate` (ceiling) into a
`union_recall_gate` (floor) and mirroring its family/CLI/baselines/tests — with no engine code, no new eval
infrastructure, and no new corpus runs. The hard part (deciding *what* to guard and *why not* the cross-size
ratchet) is done and recorded above. Residual judgment at implementation time is small and local: tolerance
value, which corpora to pin, and running the `derive` pass.
**Recommendation: Sonnet, medium effort**, briefed with this tempdoc's Design + this confidence check. Bump to
Sonnet high effort only if the same task also bundles the release-composer projection upgrade + the calibrate
pass. Opus is not warranted for the implementation — the orchestration/judgment tier of this work is already
complete; what remains is mechanical conformance to an existing, tested pattern.

## Implemented (2026-07-08) — the representation-completeness FLOOR gate is built + validated

Built by cloning `leak_gate` (ceiling) into a floor gate (Sonnet subagent for the code; orchestrator for the
dev-stack derive + wiring + validation). **No engine/Java/UI code touched; no user-visible surface → no
browser validation applies.** All numbers below trace to the live runs recorded here.

**Code (new / changed under `scripts/jseval/`):**
- `jseval/union_recall_gate.py` (new) — floor twin of `leak_gate.py`: `evaluate` fails (exit 1) when
  `current leg_union_recall < pinned_floor − tolerance`; exit 0 (ok / un-pinned), exit 2 (projection
  missing / `status != ok` / metric absent). `derive_baselines` (measured → `leg_union_recall_min`),
  `project_release_to_baselines` (reads a future release `union_recall` section). Reads the existing
  `staged_recall_accounting` projection — no projection change.
- `jseval/metric_families.py` — `UNION_RECALL` family (`projection` source, `abs_tolerance` floor, tol 0.05).
- `jseval/commands/gates.py` + `commands/inventory.generated.json` — `union-recall-gate` +
  `union-recall-gate-derive` CLI (registered via each group's `COMMANDS` list).
- `jseval/commands/ops.py` — `union-recall-gate` added to the `_gate_coverage()` baseline-file map, so the
  `datasets` command derives `gated_by: union-recall-gate` (tolerant of an absent baselines file).
- `tests/test_union_recall_gate.py` (new, incl. a committed-baselines lock test) + committed-surface lock
  tests (`test_metric_families`, `test_datasets_command`) updated to include the new gate — *expanded*, not weakened.
- `union-recall-gate-baselines.v1.json` (new) — pointer + `fallback_baselines` shape (mirrors leak's, tempdoc
  683), measured via `union-recall-gate-derive`, pinning only **REPRODUCIBLE** corpora: **`beir/scifact` floor
  0.96** (BEIR auto-fetch) + **`golden/needle-burial-v1` floor 1.0** (committed `635-corpora` source), both in
  leak-gate's pin set; tolerance 0.05.
- `scripts/agent-analytics/hooks/search-engine-hint.mjs` — the engine nudge now surfaces FOUR ratchets
  (relevance / perf / leak / **union-recall**).

**Validation (live, this worktree):**
- Unit: `pytest tests/test_union_recall_gate.py tests/test_leak_gate.py tests/test_metric_families.py
  tests/test_datasets_command.py` → **48 passed** (incl. the new committed-baselines lock test); full jseval
  suite pass minus the 2 pre-existing `test_correction_probe` red (unrelated — expected-state.v1.json).
- Live PASS: real needle-burial-v1 run (`leg_union_recall` 1.0) vs floor 0.95 → **exit 0**; real scifact run
  (0.96) vs floor 0.91 → **exit 0**.
- Live FAIL: a regressed needle-burial projection (`leg_union_recall` 0.80) vs floor 0.95 → **exit 1** (fires);
  earlier scifact-regression demo (0.50 vs 0.91) → **exit 1**.
- `jseval datasets` shows `scifact → …, union-recall-gate` and `golden/needle-burial-v1 → …, union-recall-gate`.
- `./gradlew.bat build -x test` → **BUILD SUCCESSFUL** (no JVM surface touched).

**Review + fix (2026-07-08).** A critical review + an independent refute-first subagent audited commit
`a3e6556`; the gate's logic/tests/wiring were confirmed correct, but three issues were fixed in a follow-up:
(1) **[high]** the first cut pinned `golden/699-e3-r10_0`, a **non-reproducible** git-ignored experiment corpus
(generator in `tmp/`) — repinned onto the committed, reproducible `golden/needle-burial-v1` (all pinned corpora
are now regenerable from git; verified `git ls-files scripts/jseval/635-corpora/needle-burial-v1` + `datasets`
shows `699-e3-r10_0 → UNGATED`); (2) **[med]** added the committed-baselines lock test; (3) **[low]** corrected
stale "not yet committed" comments in `ops.py` / `test_datasets_command.py`.

**Teardown (rode along):** the original "Milestone D" cross-size ratchet is tombstoned in §Plan (⚰️ SUPERSEDED
→ §Design). No code existed to delete (never built).

**Deferred (documented, not built — symmetric with leak's own state):** the release-projection upgrade (a
`union_recall` section composed in `jseval/release.py`); the committed baselines file already carries the
`current_release` pointer so the floors auto-project once a release is recomposed with projections. And the
pinned set can grow (enron-qa / legal-clerc / needle-burial) via further `union-recall-gate-derive` runs.

Status: **implemented + validated in the `624-scale-corpus` worktree; not merged (no PR opened per instruction).**

## Remaining-work confidence check (2026-07-09 — read-only scoping pass, no feature code)

> After the core gate shipped, "remaining work" became ambiguous. This pass scopes what actually remains and
> prices the parked items so a scope decision is informed. Findings carry `file:line`/command pointers.

- **U1 — Is 699 complete? → Effectively YES; no unowned obligation is unbuilt.** The core deliverable
  (completeness FLOOR gate) is shipped + validated. Every remaining candidate is either 699-owned-and-deferred
  (R1/R2 below) or legitimately parked to another owner (P1/P2) — none is a silently-dropped 699 obligation.
- **U2 — Release-projection compose upgrade (R1): correctly deferred; bounded when done.** `release.py`
  `compose()` (`:308`) takes `leak_by_dataset` and emits a `leak` section (`:415,:428`); a `union_recall`
  mirror is ~15–25 lines across `release.py` + `commands/release.py` + a union analogue of the anti-fork
  recompose guard (`commands/release.py:185-196`). Crucially the committed `release.v1.json` carries **neither
  a `leak` nor a `union_recall` section** (grep count 0), so leak's *own* release-projection is inert today and
  `fallback_baselines` governs both gates. Building union's projection now — before leak's is even exercised —
  is premature asymmetric work; the symmetric-defer decision holds. When a release is next recomposed with
  projections, do leak + union together (and the lock test's `assert "union_recall" not in release` is the
  deliberate trip-wire to update then).
- **U3 — Pin-set parity (R2): optional; full parity not achievable here anyway.** enron-qa / legal-clerc-200
  are not materialized (`jseval datasets`); legal-clerc-200 has a `666-corpora` recipe (cheap fetch), **enron-qa
  has none** — so even leak-gate's enron pin is inert in this worktree. union pinning the two reproducible
  corpora runnable here (scifact + needle-burial-v1) is sound; adding legal-clerc-200 is a cheap optional
  nicety, not a coverage gap relative to what leak can actually run here.
- **U4 — Large-N guard (P1): impractical as a *standing* guard; parking justified.** At the measured
  ~27 docs/s realistic-corpus indexing rate, 10⁵ docs ≈ ~1 h and 10⁶ ≈ ~10 h to index — fine as a periodic
  one-off measurement (appropriately 639's ANN-at-scale turf), impractical as a CI-cadence ratchet. The cheap
  per-corpus completeness floor is the right *standing* instrument; the 10⁵–10⁶ question stays a parked one-off.
- **U5 — Integration completeness: no gap.** `union-recall-gate` appears in every enumerator the other three
  ratchets use — the `COMMANDS` list (`commands/gates.py`), `_gate_coverage` (`commands/ops.py`),
  `baseline_shift`/derive, and `search-engine-hint.mjs`. The only place it is absent is `release.py compose` —
  where leak is also effectively absent/inert (the deferred R1). No aggregate "run-all-gates" runner and no CI
  eval lane exist to miss (confirmed: `.github/**` has no eval gate).

### Verdict, confidence & model recommendation
**There is no mandatory remaining work.** 699's core is complete and consistent; the only remaining candidates
are optional/deferred and low-value-now: R1 (release-projection mirror — do it *with* leak's at the next
release recompose, not before) and R2 (add legal-clerc-200 for a little more coverage — a cheap
fetch+run+derive, no code judgment). P1/P2 are correctly parked (639 / a product tempdoc).

**Confidence for the remaining work: 9/10.** High because what remains is minimal, well-scoped, and low-risk;
the −1 is that R1's recompose anti-fork-guard analogue and R2's legal-clerc fetch haven't been exercised (mild
unknowns, not blockers).

**Difficulty: low.** R1 is a mechanical mirror of an existing block + a guard analogue; R2 is operational
(fetch/run/derive, no code). Neither needs design judgment — that tier is done.
**Recommendation: do nothing now (close 699 as complete); if R1/R2 are later wanted, Sonnet at low effort is
sufficient.** Opus is not warranted for either.
