---
title: "Retrieval quality vs corpus size: does JustSearch's hybrid retrieval hold as the corpus grows, or does the right document silently fall out of reach at scale? A diagnose-first investigation — separate the synthetic-corpus artifact from a real scaling defect, attribute the loss to a pipeline stage, then make size-robustness a measured, ratcheted property. North star: the probability the engine surfaces the right document in its top-K does not silently degrade as a personal/team corpus grows from hundreds to hundreds of thousands of files."
type: tempdocs
status: investigated + EXPERIMENTALLY MEASURED 2026-07-08 (5 live retrieval evals on the dev stack, no engine code changed) — VERDICT: the 624 signal is a SYNTHETIC-CORPUS ARTIFACT, now PROVEN (not just inferred) by a realistic control: MIRACL/de is size-robust — recall FLAT across 3k→10k (final_recall 0.967→0.967, CASCADE_LEAK 0.03→0.03), while the synthetic battlefield corpus COLLAPSES over the same volume growth (final_recall 0.385→0.139, nDCG 0.22→0.11). E1 attribution: synthetic drop is 63% LEG_MISS (confusable-head geometry no retriever can fix) + fusion-order CASCADE_LEAK amplified by the synthetic corpus's broken BM25. E2: ANN recall decay (the doc's "leading candidate") is NOT the mechanism — near-exhaustive ef_search doesn't move recall. Mechanism correction: route the fusion-truncation robustness note to 636 (bounded recall / 3-way splice), NOT 639 (ANN). Recommendation: DO NOT run Milestones A–C as a fix — no size-dependent product defect exists in the target range. The only forward work is optional + low-priority: Milestone D (a cross-size recall ratchet, now cheaply buildable from the E4 MIRACL sweep + relevance_gate template) as a standing guard. Residual unmeasured slice: realistic 10⁵–10⁶ (parked under 639/636; a defect there is unlikely given the flat 3k→10k trend). Full evidence chain: "Experimental results" + "Synthesis & revised verdict" sections. [was: open — investigation request; surfaced by 624 pass-26 on an ADVERSARIAL SYNTHETIC corpus.]
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
