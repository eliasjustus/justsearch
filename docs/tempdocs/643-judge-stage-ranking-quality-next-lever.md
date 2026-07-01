---
title: "Judge-stage arbitration — bound the cross-encoder by a *relative* relevance-confidence signal (score-margin + leg-agreement, NOT a fitted per-corpus calibration — see Research pass 2) so it cannot regress below fusion (a 'refinement floor') and can be skipped when fusion is already confident; gate any *active* rank-promotion on real-corpus headroom. Reframes the original 'sharper judge / ranked-below-cutoff' stub: JUDGE_RANK_LOW is *in-window-not-first* (not below-cutoff), the obvious judge levers are dead/harmful (F-006 swaps≈0, F-002 CE hurts email), and the present defect is the CE replacing fusion *unconditionally*. Conforms to the D-004 per-query arbitration seam + the 553 canonical-record/projection seam; reveals the *stage-non-regression* principle (extends D-005 recall-survival to property-survival)."
type: tempdocs
status: designed + research-validated (2026-07-01) — general design settled (§Long-term design), then de-risked by two literature passes (§Research pass 2 amends E1/E2: relative signal not fitted calibration). IN-scope structure warranted now by a present defect; active-promotion half gated on §9; "gating reduces regressions" is an unproven hypothesis requiring its own regression test. No implementation.
created: 2026-06-24
updated: 2026-07-01
author: agent analysis — originated as a STUB (idea + purpose only), filed from the tempdoc 636 coverage-gap analysis (this session) as the symmetric judge-side counterpart to 639's candidate-set side. No design chosen, no implementation. Records the purpose + why it matters; the design phase is deferred.
related:
  - 636-retrieval-buried-signal-long-documents   # built the instrument (Staged Recall Accounting + §5 judge-ceiling probe) whose cross-corpus profile points here; 636 D-6 = "the next runtime lever is a future tempdoc's job"
  - 639-candidate-set-integrity-ann-recall-and-result-dedup  # the symmetric SIBLING — 639 owns the candidate-set/leg side (is the answer in the set), this owns the judge side (does the judge rank it well once it is)
  - 580-relevance-freeze-and-fw001-thaw          # the relevance-freeze + corpus-adaptive recipe-weight context the reranker stage lives in
---

> **Purpose-only STUB (2026-06-24).** Captures the single largest *measured* engine-quality gap that tempdoc
> 636's cross-corpus recall-profile surfaced, and which no lever currently owns. **No design is chosen and
> nothing is implemented here** — this file records *what the gap is* and *why it matters*. Unlike 639 (whose
> first step is to *build* a measurement), this gap is **already measured** by 636's §5 judge-ceiling probe and
> the `recall-profile` JUDGE_RANK_LOW bucket; so the first step here is a **design** for the lever, gated on
> that existing measurement. Everything about *which* lever and *whether* it is worth building is out of scope.

# 643 — Judge-stage ranking quality: the dominant measured recall-survival gap

## The idea

Tempdoc 636 built **Staged Recall Accounting** — an eval instrument that sorts every relevance failure into
**leg-miss / cascade-leak / judge-rank** — and ran it across the engine's eval corpora. The cross-corpus
**`recall-profile`** finding was unambiguous: **leaks are small everywhere; the dominant failure bucket is
`JUDGE_RANK_LOW` (FP2 "Missed-Top-Ranked")** — the answer *is* retrieved, *survives* fusion, and *reaches* the
cross-encoder, but the reranker scores it **below the result cutoff**. This is a per-item **ranking/scoring**
quality gap at the judge stage — the stage *after* the candidate set is assembled.

It is the symmetric counterpart to tempdoc **639**:
- **639 (candidate-set side):** *is the answer in the set?* (ANN recall + dedup, upstream of ranking).
- **643 (judge side):** *once it is in the set and reaches the judge, does the judge rank it well?* (the
  reranker / relevance-model quality, the dominant measured bucket).

Candidate levers (recorded, **not** chosen — the design phase decides): a sharper / better-calibrated
cross-encoder; a **judge-guided recall loop** (the literature's RGS pattern — re-query guided by the judge's
own signal at constant LLM budget); or feeding the judge a better-formed candidate window. 636's §5
**judge-ceiling probe** already quantifies the *headroom* (an AI-free `leg_union_recall − final_ndcg` ceiling
plus an optional LLM-oracle reranker), so the size of the prize is measurable before any lever is built.

## Why it matters

- **It is the largest *measured* gap, not a guessed one.** The whole point of 636's instrument is to *point*
  at the next lever; it points here (the dominant cross-corpus bucket). Acting on a measured dominant bucket is
  the instrument's purpose realized — not speculation.
- **It is currently unowned.** 639 took the candidate-set/leg side out of 636; the judge side was left as a
  bare "D-6 — future tempdoc's job" line inside 636. Without its own home it is the gap most likely to be lost
  when 636 closes, despite being the biggest one.
- **It is a regime-blind capability.** A reranker that ranks a present answer above the cutoff helps *any*
  workload — consistent with the engine-wide direction recorded at the close of 636 (improve fixed,
  regime-blind capability; do not tune for a guessed corpus). The lever is judged by the existing instrument,
  not by a corpus assumption.
- **The measurement is already built** (636's §5 probe + `recall-profile`), so this is the cheapest of the
  spun-out gaps to start: it skips straight to design-gated-on-existing-measurement.

## Scope boundary (purpose only — design deferred)

Out of scope for this stub, to be decided in a future design phase:
- *Which* lever (cross-encoder swap / recalibration, judge-guided recall loop, candidate-window shaping, …).
- *Whether* it is worth building — that judgment follows the measured headroom (636's judge-ceiling probe),
  not this stub.
- Any change to the candidate-set stage (that is 639's subject — this stub assumes the answer is already in the
  set and concerns only how the judge ranks it).

This file records only the **purpose and why it matters**, plus the one fact that distinguishes it from 639:
the measurement already exists, so the next concrete artifact is a **lever design gated on 636's judge-ceiling
headroom**, not a new measurement.

---

# Investigation — takeover pass (2026-07-01)

> **Author:** agent takeover (this session). **Scope per the assignment:** investigate the idea / motivation /
> proposed solution; verify against `main` + the §5 instrument + the external literature; think critically;
> record alternatives. **No design is chosen and nothing is implemented** — this section is analysis that *aims*
> the eventual design phase. Method: read the instruments (`staged_recall_accounting.py`, `judge_ceiling.py`),
> re-derived the cross-corpus numbers from 636, mapped the production judge code path on `main` with `file:line`
> citations, and ran a sourced literature pass. The search-quality register update (a new Finding) is a
> **closure** task, deferred with the design.

## TL;DR
The stub's central empirical claim is **true but materially reframed**, and the investigation flips the
priority the stub assigns:

1. **The bucket is real but mis-described.** `JUDGE_RANK_LOW` = *gold doc is in the returned top-10 but not at
   rank 1* — **not** "ranked below the result cutoff" as the title/prose say. It is the *opposite* of below-cutoff.
2. **"Dominant" is a cross-corpus mean (0.214), not universal.** It dominates on academic + email (+ the
   synthetic needle); legal is **leg-miss-bound** (639's turf), not judge-bound.
3. **The prescribed lever contradicts the engine's own settled findings** on the two *real* corpora where the
   bucket dominates: on **email the cross-encoder actively *hurts*** (it *manufactures* judge-rank-low cases),
   and on **academic, CE model swaps move nDCG ~0** (F-006). "A sharper judge" is the thing that already hurts
   (email) or the thing already shown inert (academic).
4. **The realizable prize is small and only measured on synthetic data.** The AI-free *perfect-judge* ceiling is
   ~0.16 nDCG on the real corpora; the one *realistic* probe (a local 9B LLM, on the synthetic needle) captured
   **~11%** of it (~0.02 nDCG) — and the literature says a heavier/LLM judge will not reliably win **rank-1**.
5. **The one mechanically-sound lever** the field endorses for "in-window-but-not-first" is **in-domain
   cross-encoder fine-tuning with semi-hard negatives** — which collides head-on with the engine's cold-start
   no-labels reality (register **F-021**) and **D-005** ("don't tune for a corpus").

Net: 643 is not wrong that judge-rank-low is the largest *failure-count* bucket, but it conflates **largest
bucket** with **highest-ROI lever**. The same instrument that names the bucket also measured that the bucket is
near-its-ceiling and that the obvious levers are dead or harmful. The honest next step is a small set of
**pre-design measurements (§9)** that decide whether any judge lever clears the noise floor on *real* corpora —
before committing design effort. See §10 for the decision this puts to the user.

## 1. Verification of the central claim (numbers re-derived from `main`)

The cross-corpus profile (636 §guard-activated, the 4-corpus table at `636-…:2446`):

| Corpus | `leak` | `leg_miss` | **`judge_low`** | `ok` | `leg_union_recall` | `judge_headroom_ceiling` | dominant bucket |
|---|---|---|---|---|---|---|---|
| `golden/needle-burial-v1` (synthetic) | 0.100 | 0.000 | **0.250** | 0.650 | 1.000 | 0.199 | judge / leak |
| `mixed/courtlistener-200` (legal) | 0.070 | **0.280** | 0.100 | 0.550 | 0.685 | 0.076 | **leg-recall** |
| `scifact` (academic) | 0.013 | 0.067 | **0.253** | 0.667 | 0.933 | 0.158 | **judge-rank** |
| `mixed/enron-qa` (email) | 0.047 | 0.100 | **0.253** | 0.600 | 0.893 | 0.163 | **judge-rank** |

Cross-corpus mean `judge_low` = **0.214** (636 line ~2946: "JUDGE_RANK_LOW dominant (0.214)"), mean `leak` ≈
0.058. So the headline — *judge-rank is the largest cross-corpus bucket, leak is smallest* — **holds**. The
instrument and its self-reconciliation (0 mismatches on every corpus) are sound; I am not disputing the
measurement, only its *interpretation as a lever-priority*.

## 2. Finding A — "below the result cutoff" is the wrong description of the bucket

The projection classifies (`staged_recall_accounting.py:259-264`):
```python
if in_final:                       # gold appears anywhere in the final ranked list
    buckets["OK_RANK1" if f_rank == 1 else "JUDGE_RANK_LOW"].append(qid)
elif in_union:                     # a leg had it but the final list dropped it
    buckets["CASCADE_LEAK"].append(qid)
else:
    buckets["LEG_MISS"].append(qid)
```
The eval requests **`top_k = 10`** (`retriever.py:105`, `run.py:117`; the standard profile run does not
override it), so "the final list" is the **top-10**. Therefore `JUDGE_RANK_LOW` = **gold is *inside* the top-10,
just not at rank 1** — i.e. *above* the user/metric cutoff. A doc that fell *below* the cutoff is `CASCADE_LEAK`
(a leg had it) or `LEG_MISS` (no leg had it) — buckets 636 found are *small* (leak) or *639's* (leg-miss).

The stub's title and §"The idea" both say "the reranker scores it **below the result cutoff**" — that is
precisely the wrong direction. The mislabel is inherited: the projection annotates
`JUDGE_RANK_LOW → "FP2 Missed-Top-Ranked"` (`staged_recall_accounting.py:87`), but the *Seven Failure Points*
paper (arXiv 2401.05856) defines **FP2 verbatim** as *"the answer … did not rank highly enough to be returned to
the user … top-K are returned"* — i.e. **below the cutoff**, which maps to **cascade-leak**, not to
"in-window-but-mis-ordered." "In the top-10 but not first" is the instrument's *own* finer-grained category; no
surveyed taxonomy names it. The FP annotation and the stub prose should be corrected at design time (annotation
only; the bucket *key* is fine).

**Why this matters for the lever:** the problem is not "rescue a dropped doc into the window" (that is RGS /
recall-complete-pool / 639 work). It is the much narrower "the doc the CE *already scored* sits at rank 2-10."

## 3. Finding B — "dominant" is a cross-corpus mean, not a universal property

Per the §1 table, `judge_low` is the dominant bucket on **3 of 4** corpora — but one of those is **synthetic**
(needle), and on **legal it is not dominant at all** (leg-miss 0.28 ≫ judge-rank 0.10). So the *real, non-legal*
evidence base for "judge is the bottleneck" is exactly **two corpora**: scifact (academic) and enron (email),
both at `judge_low ≈ 0.253`. A two-corpus base is thin for committing a runtime lever — and §4 shows those two
corpora *disagree about whether the judge can even be the fix*.

## 4. Finding C — on the two real judge-dominant corpora, 643's prescribed lever contradicts settled findings

643's first-named lever is "a sharper / better-calibrated cross-encoder." The register's settled findings on the
*same two corpora* where the bucket dominates:

- **Email (enron) — the CE is the *cause*, not the cure.** F-002: `full` (CE on) 0.810 < `bm25_splade` (CE off)
  0.830 — CE *degrades* email by ~2% (343 Phase D: 3-5% across modes). F-008: "On email, CE **demotes** the
  relevant doc in 28 cases and **pushes it out of top-10** in 7 cases." The 636 enron profile was run **CE-on**,
  so its `judge_low 0.253` is *partly manufactured by the CE itself*. A *stronger* judge here makes the dominant
  bucket **worse**; the corpus-correct move is to apply the CE **less** (gate/disable), not more.
- **Academic (scifact) — the obvious judge knob is already inert.** F-006: "CE model upgrade produces **zero**
  measurable difference on **any** corpus" (minilm-512 ↔ gte-8192: scifact −0.1%). The literature (§7) confirms
  the *shape*: reranking gains shrink to ~0 once first-stage retrieval is strong (arXiv 2603.04816 scaling laws).
  So "swap in a sharper CE model" cannot capture scifact's headroom; only a *fundamentally different* judge (LLM
  listwise — §7 says it doesn't win rank-1) or *in-domain fine-tuning* (§8-B, labels wall) could.

This is the sharpest result of the investigation: **the lever the stub names first is, on the corpora where the
bucket it targets dominates, either harmful (email) or measurement-rejected (academic).** It also strains the
**D-005 regime-blind** framing the stub leans on — a single judge-strength dial cannot help academic *and* email,
because the two corpora want the dial turned opposite ways. A genuinely regime-blind judge lever would have to key
on a *runtime signal* (the CE's own confidence/margin), not on judge strength — see §8-A.

## 5. Finding D — the realizable prize is small, and only measured on synthetic data

The `judge_headroom_ceiling` (`leg_union_recall − final_ndcg`) is the gap a **perfect** judge could close over the
current pool: ~**0.158-0.163** on scifact/enron. But this is an upper bound that assumes the judge ranks gold #1
on *every* query where it is in the pool (nDCG → 1.0) — unrealistic. The only *realistic* probe is 636's §5
`judge_ceiling.py` live run: a local 9B LLM captured **~11%** of the ceiling (`headroom_realized 0.022`,
`capture_fraction ≈ 0.11`), with `top1_agreement 0.20` under input-order swap (F-025). Two caveats compound:
- that probe ran on the **synthetic needle only** (20 queries) — the realistic capture on scifact/enron is
  **unmeasured** (this is the central §9 gap);
- the 11% / 0.20-agreement numbers are textbook **LLM listwise position-bias** (arXiv 2310.07712; magnitude
  matches the surveyed 4-24% order-induced loss) — i.e. the *LLM* judge is intrinsically unreliable at rank-1,
  independent of headroom.

So the best evidence today says the realistic judge prize on real corpora is *single-digit-percent of a ~0.16
ceiling* (order ~0.02 nDCG). On scifact that is ~10× the cohort σ (CV 0.1-0.3%, register/391) so it would be
*detectable* if achieved — but "detectable" ≠ "worth a runtime lever," especially against the alternatives in §8.

## 6. The production judge code path (what actually happens to a top-10-not-rank-1 doc)

Mapped on `main`; the rerank orchestration lives in `KnowledgeSearchEngine.java` (package-private, `worker`
package — *not* `KnowledgeHttpApiAdapter`):

- **Recall-complete pool is default-ON** (`ResolvedConfigBuilder.java:1513` resolves `true`; N=**10**/leg at
  `:1514`; splice in `HybridFusionUtils.java:57-100`, applied `HybridSearchOps.java:481-492` +
  re-asserted `SearchExecutor.java:764-786`). ⇒ For the `judge_low` bucket (gold *is* in the top-10), the gold was
  inside the CE window and **was CE-scored**. The residual is a *pure CE-scoring* outcome, not a pool/recall gap.
  *(F-024 confirms it shipped default-on; the in-code comments at `HybridSearchOps.java:477`,
  `SearchExecutor.java:758`, `EnvRegistry.java:972` still say "default off" — stale, logged to observations.)*
- **CE window = top-20** (`KnowledgeSearchEngine.java:615-616`; presets set `crossEncoderWindow=0` ⇒ falls back to
  `rerankConfig.topK()=20`, `ResolvedConfigBuilder.java:1105`). The CE re-scores those 20 and the list is trimmed
  to the requested limit (10) **after** reranking (`:682-685`). So the CE is the **sole arbiter** of intra-top-10
  order.
- **Raw logits, no calibration, no threshold** (`CrossEncoderReranker.java:302-318` takes the positive-class
  logit verbatim; ordering is a plain descending sort `:272-276`). ⇒ 643's "*better-calibrated* CE" lever has **no
  integration point today**, and (§7) monotonic calibration cannot reorder anyway — only *learned* re-scoring
  (= fine-tuning) moves rank-1.
- **CE fully *replaces* intra-window order — no fusion⊕CE blend, no CE-confidence gate** (`:652-670`); on
  RPC/time-budget failure it falls back to the *original* order, never a blend. ⇒ The CE is all-or-nothing, which
  is *why* it can hurt email (no fallback to the better fusion order when the CE is unreliable). This is the
  missing seam §8-A names.
- **The production CE is pointwise** (scores each (query,doc) independently) ⇒ it has **no position bias**. The
  0.20 top1-agreement instability (§5) was the *LLM* probe, not the production judge. So the two candidate judges
  are **stable-but-weak (CE)** vs **unstable (LLM)** — neither is an obvious rank-1 win.
- Eligibility (`isRerankerEligible`, `:164-176`): CE is auto-disabled for NAVIGATIONAL queries, `<5` hits, or
  `avgContentLengthChars > 16000` (`:173-174`, `ResolvedConfigBuilder.java:1109`). LambdaMART runs first but is
  **inert** (no trained model ships, `:540-545`).

## 7. Literature verdict on 643's three named candidate levers

Sourced pass (full citations at the foot). Mapping each lever the stub records:

- **"sharper / better-calibrated cross-encoder."** *Weakest lever here.* Reranking gains vanish as first-stage
  retrieval strengthens (arXiv 2603.04816 scaling laws; corroborates F-006). Listwise LLM rerankers do **not**
  reliably beat a strong CE on **nDCG@1**, at ~9× cost / ~35× latency (arXiv 2403.10407: DeBERTa CE ≈ GPT-4).
  Calibration alone is monotonic ⇒ does not reorder. The *only* sound form of "better judge" is **in-domain
  fine-tuning with semi-hard negatives** (Pradeep ECIR 2022; arXiv 2411.02404, 2507.08336) — see §8-B.
- **"judge-guided recall loop (RGS)."** Real and effective — *Reranker-Guided Search*, arXiv **2509.07163**
  (+3.5-5.1 nDCG@10 at fixed judge budget); and reranker-as-feedback (ReFIT arXiv 2305.11744, TouR). **But its
  gain is recall-recovery** — it enlarges *what the judge sees*, helping **leg-miss / cascade-leak**. For the
  `judge_low` bucket (gold *already* in the window) it has little to add. ⇒ **This lever is mis-targeted at 643's
  own bucket; it belongs to 639.** A genuine scope-overlap to resolve.
- **"feeding the judge a better-formed candidate window."** This is essentially the **recall-complete pool**,
  which is **already shipped default-on** (§6). Mostly spent as a lever.

The field's highest-ROI lever for "relevant doc in top-10 but not rank-1" is **in-domain CE fine-tuning with
semi-hard negatives mined from the very judge-rank-low confusions the instrument logs** — the rank-1-vs-rank-2
decision *is* a decision-boundary problem, which hard-negative training sharpens (caveat: *too-hard* negatives
inject label noise and backfire — arXiv 2507.14619). Doctor-RAG (arXiv 2604.00865) endorses the *diagnose →
localize → repair-per-failure-type* architecture 636 already built, but it is about agentic multi-hop, not
single-shot rank-1.

## 8. Alternative directions (recorded, NOT chosen — design still deferred)

- **A. CE-confidence-gated fusion⊕CE blend (the one *regime-blind* judge lever that survives §4).** Today the CE
  *replaces* the fusion order outright (§6). Let the CE reorder only when its score *margin* is high; otherwise
  keep (or blend toward) the fusion order. This keys on a **runtime signal (the CE's own confidence)**, not a
  corpus assumption — *allowed* under D-005 (the same shape as D-004's per-query gate). It directly targets the
  email regression ("separate CE-right from CE-confidently-wrong" — the judge-side analog of D-004's open problem)
  and cannot, by construction, do worse than fusion. **This is the most promising candidate**; it needs the §9
  measurement to size the win and a margin-calibration study.
- **B. Cross-corpus hard-negative CE fine-tune (capability, not corpus-fit).** The field's canonical rank-1
  lever (§7). To stay D-005-compliant it must be trained across *diverse* corpora as a *general* capability
  improvement — not fit to scifact/enron. **Wall:** needs in-domain labels + a negative-mining pipeline; the
  engine is cold-start with no real user labels (F-021), and over-hard negatives backfire. Eval-corpus qrels are
  the only labels available, and training on them risks the exact corpus-overfit D-005 forbids. High-effort,
  high-risk; record but do not start without resolving the labels question.
- **C. Fusion score calibration *upstream* of the judge (cheap, test first).** If the gold consistently enters
  the CE window at a depressed *fusion* rank, recalibrating per-leg CC score scales can lift it without touching
  the judge. Cheap to A/B; may move some `judge_low` → `OK_RANK1` for free. (Note the overlap with the FW-001 /
  recipe-weight thread, superseded as a *router* but live as a *fixed policy*.)
- **D. Reframe — is `judge_low` even the right priority?** The instrument measures *where queries fail*, not
  *where the cheapest realizable gain is*. Against the realizable ~0.02 judge prize (§5): **extraction quality
  (F-009) is a measured 15-33% nDCG prize** and **leg-miss (639) is the dominant bucket on legal**. A neutral
  reading of the *same* instrument is that the judge bucket is **near its ceiling** and the engine's marginal
  attention is better spent on F-009 / 639. This is the strongest "question the premise" outcome and is put to
  the user in §10.

## 9. Required pre-design measurements (the design phase is gated on these)

The stub says "measurement already exists, so start at design." The investigation finds the *existing*
measurement is insufficient to choose a lever; three cheap measurements must precede any design:

1. **Run the §5 `judge_ceiling.py` live probe on `scifact` + `enron-qa`** (not just the synthetic needle). This
   is the single most important number: the *realistic* (not perfect) judge headroom on real corpora. If it is
   ~11% as on needle, the judge lever is near-dead and §8-D wins.
2. **Decompose the `judge_low` rank distribution** (rank-2 vs rank-3-10). A bucket that is mostly rank-2
   (nDCG contribution ~0.63, tiny headroom) is a near-solved state; one that is mostly rank-8-10 is a real
   mis-ranking. The aggregate rate hides this. (Trivial extension to the projection — it already has the ranks.)
3. **Per-corpus CE attribution: is the CE the *cause* or does it have *headroom*?** Re-run scifact/enron CE-on vs
   CE-off through the projection and check whether `judge_low` *rises* with the CE (email-style, ⇒ gate the CE) or
   *falls* (⇒ genuine judge headroom). F-008 already implies email; confirm and quantify both.

All three are eval-only, reuse existing instruments, and (post-644) can run from a worktree. Until they exist, a
judge **design** would be exactly the "build structure for an unmeasured case" the codebase forbids.

## 10. Recommendation to the user (decision needed)

The stub's premise — *the instrument points here, so design a judge lever* — does not survive contact with the
instrument's *own* §5 probe, the engine's settled CE findings (F-002/F-006/F-008), and the field literature. I see
two honest paths and recommend the first:

- **(Recommended) Re-scope 643 from "build a sharper judge" to "decide whether the judge bucket is worth a
  lever," gated on §9.** Concretely: run the three §9 measurements; *if* a real corpus shows realistic judge
  headroom above the noise floor that the CE cannot already capture, design **§8-A (CE-confidence-gated blend)**
  as the regime-blind lever (and fix the email regression as the acceptance test); *else* record judge-rank-low
  as **near-ceiling** and redirect engine attention to **F-009 (extraction, 15-33%)** and **639 (leg-miss)**.
- **(Alternative) Keep the stub's design-first stance** only if you accept §8-B (cross-corpus hard-negative CE
  fine-tune) as the lever and are willing to confront the labels/D-005 wall — a much larger, riskier program.

Either way, the stub's "sharper / better-calibrated cross-encoder" and "judge-guided recall loop" as written
should be retired: the first is harmful/inert on the relevant corpora, the second targets 639's bucket.

## Closure tasks (deferred with the design — not done in this investigation pass)
- Add a register **Finding** (≈F-026) capturing: judge-rank-low is the largest cross-corpus *failure-count*
  bucket but near its realizable ceiling; the obvious judge levers are dead (F-006) or harmful (F-002/F-008); the
  surviving lever is CE-confidence-gated blending (§8-A), gated on §9. Add the §9 items as an Open Question.
- Correct the `FP2` annotation (`staged_recall_accounting.py:87`) and this stub's "below the cutoff" prose.

## Sources (literature pass)
RGS arXiv 2509.07163 · ReFIT 2305.11744 / TouR · CE-vs-LLM rerank 2403.10407 · CE scaling-laws 2603.04816 ·
FIRST 2406.15657 · position-bias 2310.07712 (+ 2604.03642, 2411.04602) · hard-negative/distillation Pradeep ECIR
2022, 2411.02404, 2507.08336, 2507.14619, 2505.19274 · Seven Failure Points 2401.05856 (FP2 verbatim) ·
Doctor-RAG 2604.00865. *Caveats:* several 2026 IDs are recent/unreplicated; the CE-stops-helping quantification
leans partly on diminishing-return *shape* + vendor blogs — the engine's own F-006 is the more specific datum.

---

# Theorization — possibility space (2026-07-01)

> **Purpose per the assignment:** think *broadly* before the design is settled — reframings, the expanded lever
> space, tradeoffs, hidden assumptions, and whether 643 points to a broader principle / invariant / recurring
> system shape. **Nothing here is a chosen design.** These are candidates and hypotheses to weigh in the design
> phase (still gated on the §9 measurements). The §Investigation above is "what is true / what is wrong with the
> stub"; this section is "what *else* could the problem be, and what is the deeper shape."

## T1. Four reframings of the problem

The stub frames the problem as *"the judge mis-ranks a present answer; build a better judge."* Each reframing
below keeps the measured fact (answer in top-10, not rank-1) but changes what the *right response* is.

- **T1-a — the product may not value rank-1 the way the metric does.** `judge_low` is defined by nDCG/rank-1 on
  qrel'd benchmark corpora. But JustSearch's two real consumers tolerate non-first positions: an **interactive
  user** scans the visible top results (rank-2 is nearly free), and the **RAG/agent path** feeds the *top-k as a
  set* to an LLM that synthesizes over all of it (intra-window rank barely matters — what matters is the doc being
  *in the context budget at all*). So the bucket that looks "dominant" by count may be **near-worthless by product
  value**, while leg-miss / cascade-leak (answer *absent*) are where the product actually fails. This sharpens
  §8-D from "maybe deprioritize" to a hypothesis: **rank-1 is a benchmark objective; recall-into-the-window is the
  product objective, and `judge_low` is mostly the former.** (Testable: does promoting `judge_low` golds to rank-1
  change *answer correctness* on the RAG/agent eval, or only nDCG?)

- **T1-b — there is more than one judge.** The engine has a *cheap, stable, weak* judge (the pointwise
  cross-encoder, §6) and an *expensive, strong-ish, unstable* judge (the chat LLM, already resident for
  RAG/agent). 643 silently means "the CE." But the highest-value architecture may be **routing between judges**,
  not strengthening one — and the LLM's downstream *citation choices* are already a relevance verdict the system
  computes and **discards** (F-021 refinement: the agentic path persists `retrieved ⊃ grounding ⊃ cited` tuples).

- **T1-c — the valuable judge *output* may be a calibrated confidence, not a better order.** When the answer is
  present-but-not-first, what helps the user/LLM most is often "it's in here, here's how sure we are / scan
  deeper," not a one-slot reshuffle. The CE today emits **raw uncalibrated logits** (§6) — so the engine cannot
  say that. This connects `judge_low` directly to **Q-009** (no validated user-facing confidence exists). The
  same missing primitive (a calibrated relevance score) blocks several features — see T5.

- **T1-d — `judge_low` may be a candidate-*set* artifact, not a judge deficit.** The rank-1-vs-rank-2 decision is
  frequently a *near-duplicate* decision: the doc out-ranking the gold is often a redundant twin (the
  needle-burial corpus is literally built from near-identical filler). **639 owns near-duplicate collapse.** If
  639's dedup removes the distractors that out-rank gold, `judge_low` falls *without touching the judge*. So the
  "symmetric siblings" framing (639 = candidate-set, 643 = judge) under-states the coupling: **a chunk of 643's
  bucket is caused by the candidate-set quality 639 owns.** This is a scope-boundary the two designs must
  negotiate, not assume away.

## T2. The expanded lever space (map, not a choice)

Beyond the stub's three levers (all triaged dead/mis-targeted/spent in §7), the fuller space — each tagged by
*what it targets*, *label dependency*, *D-005 corpus-fit safety*, and *email-regression risk*:

| Direction | Targets | Needs labels? | D-005-safe? | Regression risk | Note |
|---|---|---|---|---|---|
| **CE-confidence-gated fusion⊕CE blend** (§8-A) | judge_low + email | no | yes (runtime signal) | **structurally ≤ fusion** | the standout; also a *perf* lever — see T3-a |
| Fusion score calibration upstream (§8-C) | judge_low | no | yes | low | cheap first test; moves golds up *before* the judge |
| Cross-corpus hard-negative CE fine-tune (§8-B) | judge_low | yes (qrels) | **at risk** | medium | capability *iff* trained on confusion-*types*, not corpus content (T4) |
| **LLM-citation → CE distillation** (new) | judge_low + leg recall | label-light (harvested) | yes-ish | medium | closes a loop on a *discarded* signal; circularity/recall-blind risk (F-021) |
| Dedup upstream (→ **639**) (T1-d) | judge_low via candidate set | no | yes | low | may fix part of 643's bucket for free |
| Judge ensemble / multi-signal (new) | judge_low | no | yes | low | combine fusion + CE + (cited?) signals; cf F-022 CC-fusion; F-021 warns *learned* fusion needs labels |
| Query-side rewrite (existing QU/expansion) | judge_low + recall | no | yes | low | QU disabled (contention), expansion blocked in hybrid — partial path only |
| Accept-and-present top-3 + confidence (T1-c) | dissolves bucket | no | yes | none | presentation lever; pairs with Q-009 |

The shape of the table is the finding: **the levers that survive D-005 + the no-labels reality + the email
asymmetry are the ones that *combine or gate existing signals* (blend, calibration, ensemble, dedup) — not the
ones that *build a stronger single judge*.** That is the same lesson as F-021 (learned-fusion-without-labels lost)
and F-022 (CC-fusion of existing legs won), pointing one level up — see T6.

## T3. Cross-cutting tensions and tradeoffs

- **T3-a — the confidence-gated judge dissolves the 643 ↔ 648 tension (key insight).** The CE is ~82% of query
  latency (640 §C-2); 647/648 exist to *reduce* it. A naive "stronger judge" (heavier CE / LLM rerank) **fights**
  the perf budget. But a **confidence-gated judge** — run the CE only when the cheap signal (fusion) is *uncertain*
  (legs disagree / flat top, the D-004 signals) — is **simultaneously the quality lever (643) and a perf lever
  (648)**: it avoids the email-style regressions (don't override confident-correct fusion) *and* skips the
  dominant cost on the many queries where fusion is already decisive. This is the Pareto move; it is the single
  idea that makes 643, 647, and 648 one coherent program instead of three competing ones. Worth foregrounding in
  any design.
- **T3-b — regression asymmetry is the governing constraint.** Any lever that makes the CE *more* influential
  risks email (F-002/F-008). Only structurally-bounded levers (blend that floors at fusion; gate that *removes*
  CE on confident queries) are safe by construction. This should be an **acceptance invariant**, not a
  post-hoc check: *"no email/legal regression beyond noise"* gates every candidate.
- **T3-c — prize vs noise vs effort.** Realizable judge prize ≈ 0.02 nDCG (§5), detectable on scifact (σ small)
  but possibly not elsewhere. The §9 probes are cheap; a *design* is not. ROI discipline says: do not commit
  design effort until a real-corpus probe shows headroom > noise that the CE cannot already capture.
- **T3-d — the capability/corpus-fit line is subtler than D-005 states, and 643 is the test case.** D-005 forbids
  "tuning for a guessed corpus." But training a judge on the *distribution of confusion-types* (near-dup
  distractor, paraphrase distractor, partial-overlap distractor) is regime-blind capability; training on *specific
  corpus content/vocabulary* is corpus-fit. The eval qrels give free hard negatives — using them to sharpen the
  general decision boundary may be allowed; using them to fit the 5-corpus eval set is not. **643 forces a
  sharper articulation of D-005's line** (see T6).

## T4. Hidden-assumptions ledger

1. *Rank-1/nDCG is the objective* — contested by T1-a (product tolerates non-first).
2. *"The judge" = the cross-encoder* — contested by T1-b (two judges; LLM citations discarded).
3. *The judge's job is to re-order* — contested by T1-c (calibrated confidence may be the higher-value output).
4. *639 and 643 are cleanly separable* — contested by T1-d (near-dup distractors couple them).
5. *"Dominant bucket" ⇒ "build a lever for it"* — contested by §8-D / T6-a (count ≠ cost).
6. *The measurement already suffices to start design* — refuted by §9 (the realistic probe never ran on real
   corpora; the rank distribution inside the bucket is unknown).
7. *A stronger judge is compatible with the perf budget* — refuted by T3-a (it fights 648; only a *gated* judge
   is compatible).

## T5. The hidden shared dependency: a calibrated relevance score

Four distinct items independently need the *same* missing primitive — a **calibrated** CE relevance score (the CE
emits raw logits today, §6):
- §8-A confidence-gated blend (needs a trustworthy CE margin),
- §8-C fusion calibration (needs comparable per-leg + CE scales),
- Q-009 user-facing retrieval confidence (needs a bounded, validated score),
- the T2 ensemble / LLM-citation distillation (needs calibrated targets).

This is a classic system-shape finding: **several blocked features share one un-built primitive.** Building
calibration *once* (an isolated, eval-validatable unit — Platt/isotonic on held-out qrels, or distribution
matching) is plausibly higher-leverage than any single consumer, and it is **D-005-safe and label-light** (it
recalibrates an existing signal; it does not invent a workload assumption). If the §9 probes say "act," the
calibration primitive — not a bigger model — is the likely first brick. (Caveat: monotonic calibration alone does
not *reorder*, §7 — it unlocks *gating/confidence/blending*, which is exactly what T1-c and §8-A want.)

## T6. Candidate broader principles / invariants (recorded as candidates — not adopted)

643 looks small but it surfaces several recurring system shapes worth naming for reuse:

- **T6-a — Dominant-*count* ≠ dominant-*cost* (cost-weighted attribution).** A failure-bucketing instrument that
  ranks levers by raw bucket *count* mis-prioritizes when buckets have unequal value. The recurring fix: every
  triage instrument should attach a *value/cost weight* per bucket so "dominant" means "dominant cost." `judge_low`
  is the proof-by-example — the most-counted bucket is among the least product-costly (T1-a). Generalizes past
  search to any diagnostic (cf the leak-gate, the perf families). **This may be the most reusable output of 643.**
- **T6-b — Recall-survival (upstream) vs rank-quality (downstream): the judge is where they diverge.** D-005 says
  "observe by recall-survival." 643 refines it: recall-survival is the right invariant *up to* the judge; *at* the
  judge the objective splits into rank-quality, and the product mostly stops caring at recall-survival. Conflating
  them (a flat 3-bucket taxonomy weighing leg-miss = judge-rank) is the mis-prioritization T6-a names. The funnel
  has a **phase change at the last stage**.
- **T6-c — Prefer combining/gating *calibrated existing signals* over strengthening a single component.** F-021
  (learned fusion lost without labels) + F-022 (CC-fusion of existing legs won) + §7 (a bigger judge doesn't win)
  + T2 (the surviving levers all combine/gate) all point here. The nuance F-021 adds: combination beats a stronger
  component *when the signals are calibrated/comparable* — which is why T5 (calibration) is the enabling primitive,
  not the fusion logic itself.
- **T6-d — The discarded-verdict principle.** The system already computes relevance verdicts it throws away (the
  LLM's citation choices; the CE's score margins; the legs' agreement). Harvesting an existing signal (F-021's
  "harvest, not build") is systematically cheaper and more real-query-faithful than building a new judge. Recurring
  prompt: *before strengthening a component, ask what relevance signal the system already produces and discards.*
- **T6-e — A lever that is Pareto across quality and cost beats two single-axis levers.** T3-a: the
  confidence-gated judge is both 643 and 648. The shape: when a quality problem and a cost problem share a cause
  (here: *running the strong judge indiscriminately*), one gate fixes both. Look for shared-cause Pareto moves
  before committing separate levers per axis.

## T7. What survives, regardless of which lever wins

Two things are robust across every branch above and are the natural *first* objects of the design phase (still
gated on §9, still not a design here):
1. **The realistic-headroom probe on real corpora + the in-bucket rank distribution** (§9-1, §9-2) — decides
   whether *any* judge lever is worth it (T3-c) and whether the bucket is near-ceiling (T1-a / T6-a).
2. **A calibrated relevance score** (T5) — the shared primitive behind the only D-005-safe, no-labels levers
   (blend, gate, confidence, ensemble), and the thing that turns the "two judges" and "discarded verdicts" ideas
   from prose into mechanism.

Everything heavier (cross-corpus fine-tune §8-B, LLM-citation distillation, judge routing) is a *second-phase*
bet that should be taken only if (1) shows real headroom and (2) is in place. The cheapest high-information next
step remains the §9 measurements — they are what convert this possibility space into a decidable design.

---

# Long-term design (2026-07-01)

> **This section is the settled result of the design theorization** (it supersedes the original purpose-only
> stub as 643's current intent). It is **general, not implementation-level** — no thresholds, formulas, or code.
> Method: scope-matched to the problem the investigation *actually* found, grounded in a code inventory of what
> already exists (cited below), conforming to existing seams rather than forking. **Nothing is implemented.**

## D-0: What the present problem actually is (and is not)

The investigation split 643's nominal problem into two with very different maturity:

- **(P-now) A present, measured defect:** the cross-encoder **replaces the fusion order unconditionally** — no
  confidence bound, no fallback except on RPC/timeout (`KnowledgeSearchEngine.java:652-670`). Because it is the
  sole arbiter of intra-window order with raw uncalibrated logits, it **regresses** results where it is unreliable
  — email −2…−5 % (F-002/F-008: it *demotes* the gold in 28 cases, ejects 7 from top-10) — and it runs **always**,
  at ~82 % of query latency (640 §C-2), even when fusion was already right. This is real, not speculative.
- **(P-maybe) An undecided opportunity:** whether *actively promoting* present-but-not-first golds to rank-1 is
  worth a lever. The realizable prize is small and measured only on synthetic data (§5: ~11 % of a ~0.16 ceiling);
  the obvious levers are dead (F-006) or harmful (F-002). **Undecided until the §9 real-corpus probe runs.**

A correct long-term design **builds the structure (P-now) requires** and makes that same structure the **gated
home** for (P-maybe) — without building (P-maybe)'s payload until measurement warrants it. The structure for
both is *the same seam*, which is why this is one design, not two.

## D-1: The design — a judge-arbitration seam keyed on a calibrated relevance confidence

Three structural elements. Each **extends or conforms to** an existing structure (citations are the inventory of
what already exists, not new code).

### E1 — A relevance-confidence signal (the shared primitive) — *warranted now*
> ⚠️ **Revised by Research pass 2 (below).** Originally written as "a *calibrated* [0,1] confidence (reuse the
> `CitationScorer` sigmoid)." The literature is clear that a sigmoid-on-logit is **not** a calibrated probability,
> every real calibrator needs labels this cold-start engine lacks, and **calibration does not transfer across
> corpora** — so the gate must key on a **relative, label-free** signal (score-margin + leg-agreement), not a
> fitted probability. Read this element together with §Research pass 2, which is authoritative where they differ.

A bounded [0,1] confidence (and a margin signal, e.g. top1−top2) derived from the CE's raw logits.
- **Conform, don't invent:** the repo already calibrates a cross-encoder-style logit — `CitationScorer.java:221-245`
  (sigmoid→[0,1] + threshold). Reuse that approach for the reranker rather than authoring a second calibration.
- **Emit as a projection of the canonical record, not a parallel field (553):** the CE score already lands in the
  canonical `SearchTrace` as a `CROSS_ENCODER` `HitStage` (`SearchTraceMapper.java:28-52`,
  `SearchTrace.java:151-156`). The calibrated confidence is that HitStage's `score` or a key in its `detail` map —
  and the transient `ceScoresByDocId` (`KnowledgeSearchEngine.java:654-658`) folds into the canonical record
  rather than spawning a second authority.
- **Why now (two present consumers, not speculation):** (a) E2 needs a confidence to gate on; (b) Q-009's
  user-facing confidence consumer **already exists and is waiting** — `RagQualitySignals`
  (`RagContextOps.java:636-659`) → `retrievalSignals.ts:41` literally renders an *"uncalibrated"* label today.
  Feeding it a calibrated score lets that label drop. Calibration is monotonic (it does not reorder by itself,
  §7) — its purpose is precisely to *unlock gating/confidence*, which is what E2 and Q-009 need.

### E2 — A judge-arbitration policy that consumes the confidence (the *refinement floor*) — *warranted now*
Bound the CE's influence so it **cannot leave a hit worse-ordered than fusion produced it, beyond what its
calibrated confidence justifies**; where confidence is low, fusion holds (or a confidence-weighted blend toward
fusion); generalize the existing RPC/timeout fallback to *"fall back to fusion whenever the judge is not
confident."*
- **Conform to the D-004 seam (don't fork):** this is the **CE-side instance** of D-004's *fusion-side* per-query
  arbitration (`HybridSearchOps.java:446-462`, gate `computeArbitrationAlpha:246-264`). Same shape — keyed on a
  **runtime signal** (here the CE's own confidence/margin, not a corpus assumption → D-005-allowed), keyword-
  neutral, one-directional/structurally-bounded, env-flagged (mirror the `index.hybrid.*` convention,
  `EnvRegistry.java:953-966`), **default-off until proven, then default-on** exactly as D-004 went (decision
  2026-06-24). D-004 even named the unsolved twin — *"separate dense-found-the-answer from dense-confidently-
  wrong"*; E2 is its judge-side analog — *"separate CE-right from CE-confidently-wrong."* Same seam, same hard
  sub-problem.
- **It fixes (P-now) and is a perf lever (the 643↔648 unification, T3-a):** a confidence floor stops the email
  regression by construction (can't drop below fusion); a low-confidence **skip** lets the CE — 82 % of latency —
  be bypassed on confident-fusion queries. So E2 is simultaneously the quality fix (643) and a latency lever
  (647/648): one gate, two axes (the shared-cause Pareto move, T6-e).

### E3 — The decision instrument extension (the gate for the contingent half) — *cheap, conforms*
Make the §9 measurements first-class so the *active-promotion* half of E2 is enabled on evidence, not assertion.
- **Extend the existing projection, don't add an instrument:** `staged_recall_accounting.produce` already computes
  per-query final rank `f_rank` (`:245-247`); add (i) the **in-bucket rank distribution** (rank-2 vs rank-3-10 —
  near-ceiling vs real mis-rank, §9-2) and (ii) a **value/cost-weight hook** so "dominant" means dominant-*cost*
  (T6-a). Registration is one module-level `PROJECTION` + one line in `projections/__init__.py` (base.py seam);
  a gate metric, if wanted, is one `MetricFamily(source_class="projection")` (precedent: `LEAK`,
  `metric_families.py:107-115`).
- **Run the existing §5 probe on real corpora:** `judge_ceiling.py` already exists; run it on `scifact` + `enron`
  (it has only ever run on the synthetic needle). This is the single number that decides (P-maybe).

## D-2: Scope boundary (what this design deliberately does and does not include)

- **IN (the present problem requires it):** E1 (calibration primitive — present consumers), **E2 as a floor**
  (regression-prevention + perf skip — fixes a measured defect), E3 (cheap decision instrument).
- **CONTINGENT (built only if E3's real-corpus probe shows headroom > noise):** **E2's active-promotion half**
  (pushing present-but-not-first golds up when the CE is confident).
- **OUT (structure for cases the problem does not yet include):** a stronger/heavier judge **model** (F-006: swaps
  ≈ 0); **LLM-as-reranker** (position-unstable, §5/§7; breaks the perf budget); **learned fusion** (F-021: no real
  labels); **in-domain CE fine-tune / LLM-citation distillation** (the field's "right" rank-1 lever, §8-B, but
  hits the cold-start labels wall + D-005 corpus-fit risk) — recorded as the **second-phase bet**, not this design.
- **Boundary with 639 (the coupling the "siblings" framing under-states, T1-d):** near-duplicate distractors that
  out-rank gold are **639's dedup**, not E2's job. E3's rank-distribution should *attribute* how much of
  `judge_low` is near-dup-driven (→ 639) vs genuine mis-rank (→ 643), so the two stubs partition the bucket by
  evidence rather than by assumption.

This is the scope match: the design adds exactly a **confidence signal + a bounded arbitration + a decision
instrument** — the structure (P-now) and the *decision* about (P-maybe) require — and defers the stronger-judge
machinery to the cases the problem does not yet contain. The change is "re-architect one seam" (the unconditional
CE replace → a confidence-bounded arbitration), not a rewrite — the same judgment 663 reached for its sibling seam.

---

# Reach & principle (2026-07-01)

> Per the assignment: step back and judge the design's reach — what existing seam it is an instance of (conform,
> don't fork), and what principle/invariant it reveals (name + scope + existing violations) **without building
> the generalized structure now**. Recognizing a general principle is kept separate from building general
> structure — deliberately, so the insight is captured without premature abstraction.

## It is an instance of existing seams — conform, do not fork

1. **D-004 per-query arbitration on runtime signals.** E2 is the CE-side instance of the same mechanism family
   (`HybridSearchOps` arbitration). Conform to its conventions (runtime-signal gate, keyword-neutral, one-
   directional, env-flag naming, default-off→default-on-by-measurement). Do **not** create a parallel
   "judge-gating" subsystem — extend the arbitration concept to the judge stage.
2. **Canonical-authority + governed-projection seam (553 / 595 / 623 / 640 / 663).** The calibrated confidence is a
   **projection of the canonical `CROSS_ENCODER` HitStage**, gated against re-fork — the same kernel that gives the
   FE a single `SystemHealthVerdict` (595/663, guarded by the `verdict-derivation` gate), `SearchTrace` its single
   per-hit record (553), and the eval its `metric_families` (640). Conform: retire the transient `ceScoresByDocId`
   into the canonical record; do not add a second score authority.
3. **In-repo calibration precedent (`CitationScorer` sigmoid+threshold).** Reuse, don't author a second
   calibration path.

That all three already exist is the strongest evidence the design is correctly scoped: it is **re-architecting one
seam by composing existing seams**, not introducing new architecture.

## The principle it reveals — *stage non-regression (the "refinement floor")*

**Statement.** Generalize D-005's **recall-survival** ("a narrowing stage must not drop a *correct candidate*") to
**property-survival**: *every stage that claims to improve a quality property must be structurally bounded so it
cannot leave that property **worse than its input**, with improvement gated on evidence.* The funnel's upstream
half owns **recall** (don't leak — D-005, recall-complete pool); the **judge** owns **rank-quality** (don't
regress below fusion — this design). They are the **same invariant** at different stages: *no stage may be worse
than its input on the property it owns.* 643 is therefore not a new principle bolted on — it is the **downstream
completion of D-005**, the half that was missing because recall-survival stops being sufficient exactly at the
last (judge) stage (T6-b: the funnel's phase change).

**Candidate scope — where else it applies (recorded, not built):**
- **Cross-encoder (current violation → E2 fixes it):** replaces fusion unconditionally; regresses email.
- **LambdaMART (latent violation):** GPL-trained LambdaMART *degrades* real queries (F-021) — a refinement floor
  would have caught it; it is inert today only because no model ships.
- **VLM extraction (possible violation, unguarded):** VLM text can be worse than Tika on some inputs (F-009); no
  floor guarantees extraction ≥ baseline.
- **Branch fusion / chunk merge (unverified):** adding the chunk branch should not regress whole-doc-only.
- **Query expansion (the conforming positive example):** already falls back to base on timeout/empty — it *has* a
  floor; the pattern to copy.

**Conform-vs-build decision.** Record the principle; **build only the CE instance now.** A generalized "stage-floor
framework" would be premature abstraction — only the judge stage's regression is a *present, measured* problem. The
others are candidate scope to revisit per their own evidence (and a natural future ArchUnit/gate target *if* a
second instance is shipped — not before, per `structural-defects-no-repeat` applied in reverse: one proven
instance warrants the one fix, not the general machinery).

## Secondary principle — *dominant-count ≠ dominant-cost* (cost-weighted attribution)

A failure-bucketing instrument that ranks levers by raw bucket **count** mis-prioritizes when buckets differ in
value; "dominant" should mean dominant-**cost**. `judge_low` is the proof-by-example (most-counted, least
product-costly — T1-a). **Candidate scope:** the leak-gate / perf families, FE error-alarm surfaces (which errors
warrant alarm), any triage instrument. **Existing violation:** `staged_recall_accounting`'s flat counts (E3 adds
the weight *hook* for 643's instance only). Record; do **not** build a general cost-weighting framework now.

## Closure tasks (deferred with implementation — updated from §Investigation)
- Register **Finding F-026**: judge-rank-low is the largest cross-corpus *count* bucket but near its realizable
  ceiling; obvious judge levers dead (F-006) / harmful (F-002); the design is a **confidence-bounded judge
  arbitration** (refinement floor) reusing D-004 + the 553 projection seam + CitationScorer calibration, with
  active promotion gated on §9. Add §9 items + the *stage-non-regression* principle as register entries
  (Open Question / Decision).
- Correct the `FP2` annotation (`staged_recall_accounting.py:87`) and any remaining "below the cutoff" prose.
- (Out-of-scope, logged to observations:) the stale "default off" comments for the recall-complete pool **and**
  the D-004 leg-arbitration (both resolve `true`), and the stale "MiniLM" javadoc.

---

# Research pass 2 — calibration & selective-reranking evidence (2026-07-01)

> **Why a second pass:** Pass 1 de-risked the *direction* (a stronger judge is not the lever). The general design
> (§Long-term design) then rested on two *assumed* mechanisms in fast-moving areas — a "calibrated confidence"
> (E1) and "confidence-gated/selective reranking" (E2). This pass de-risked the **mechanisms**, and it changed
> the design materially: it caught a wrong load-bearing assumption in E1. This section is **authoritative where it
> differs from §Long-term design**; the design is otherwise unchanged.

## What changed (the headline correction)

**A sigmoid on a cross-encoder logit is NOT a calibrated probability — it is a squashed, rank-preserving score.**
Neural rankers are not scale-calibrated by construction (a ranker may add an arbitrary constant to all scores
without changing order), so the absolute scale carries no probability meaning (Scale Calibration of Deep Ranking
Models, SIGKDD 2022; Penha & Hauff, EACL 2021). Every honest calibrator — Platt / isotonic / temperature / beta —
**needs labeled calibration data**, which this cold-start engine does not have (F-021: no real click labels). And
**calibration does not transfer across corpora**: score distributions are domain-dependent, "no single calibrator
is uniformly best across domains," and a threshold/calibration fit on one corpus blurs the boundary on another
(Cohen et al. *Not All Relevance Scores are Equal*, arXiv 2105.04651; recent stopping-rule work TASR arXiv
2606.13814). **⇒ A single corpus-agnostic *fitted* calibration — the thing E1 assumed by reusing the
`CitationScorer` sigmoid — is not achievable.** This is exactly the failure mode the in-house F-019 already warned
of (a "confidence signal" that is illusory).

## Revised E1 — a *relative, label-free* confidence signal (not a fitted probability)

The gate must key on a **relative, distribution-aware, runtime** signal computed from the query's *own* results,
not an absolute fitted P(relevant):
- **Score-margin / separation** (e.g. normalized top1−top2 of the CE scores) — how decisively the judge prefers
  its top pick;
- **Leg-agreement** — whether the retrieval legs concur (the engine already computes top-K doc-id Jaccard for
  D-004, `HybridSearchOps.java:284-299`) and whether the CE's top agrees with the fusion top;
- (optional, rigorous, label-free) **conformal coverage** for a *user-facing* confidence (Q-009) — CONFLARE
  (arXiv 2404.04287), Principled Context Engineering for RAG (arXiv 2511.17908) — but with the caveat that
  conformal coverage **degrades under corpus/query shift** (arXiv 2603.16817), so even this is trend-grade for an
  unknown workload, consistent with D-005's "auto-labels are trend-only."

This **strengthens the §Reach conformance**, it doesn't weaken it: a *relative margin/agreement* signal is exactly
D-004's existing rank/ratio-based, score-incomparability-aware shape — the judge gate reuses the **same family of
runtime signals** D-004 already computes, rather than introducing a fitted model. The `CitationScorer` sigmoid
stays usable only as a **bounded display** value (and only if labeled "relative/uncalibrated," as
`retrievalSignals.ts:41` already does for Q-009) — never as the gate's notion of truth.

## Revised E2 — gating signal, soft-blend, and the unproven claim

- **Selective/gated reranking is an established *efficiency* pattern** (early-exit, SIGIR'25
  10.1145/3726302.3729962; AcuRank arXiv 2505.18512; cascade theory SIGIR-ICTIR'22). The best-supported routing
  signals are **QPP and score-margin**; reranker **self-consistency** is a promising 2026 addition (arXiv
  2606.03535, for *listwise* rerankers — single-source). Leg-agreement is intuitive but I found no paper isolating
  it as a "should-I-rerank" predictor — treat it as a reasonable, in-house-available signal, not a borrowed result.
- **Interpolate, don't replace — but on comparable scales.** Blending the CE score *toward* the first-stage/fusion
  score demonstrably reduces "reranker-hurts" cases (TILDE; *Injecting BM25 Score as Text*, arXiv 2301.09728,
  ECIR'23) — but **raw-score interpolation is scale-fragile** (BM25 unbounded vs CE bounded), so the blend needs a
  normalized/rank-based combination, which loops back to the relative-signal requirement above. Prefer a **soft
  blend / hysteresis band** over a hard cutoff (a thresholded gate is most sensitive to exactly the boundary
  region where any confidence is least reliable — the "threshold blur" risk).
- **The central claim — "confidence-gating *reduces* reranker regressions" — is NOT established in the
  literature.** The cited works prove "gating preserves quality at lower cost," not "gating repairs cases the
  reranker would have hurt." It is plausible-by-construction (only reorder when confident ⇒ avoid low-confidence
  wrong reorderings) but **borrowed-evidence-free**. ⇒ Per `audit-without-test`, the gated path is a hypothesis
  until a **regression-rate eval** (with vs without the gate, on held-out BEIR-style corpora, measuring the *rate*
  at which reranking degrades a result — not just nDCG mean) is green. This is now a **mandatory acceptance test**,
  added to §9 as §9-4.

## Named design risks (carry into the build)

1. **"Calibrated confidence" is not honestly fittable here (headline).** Mitigation: relative/label-free signal;
   rename done (title + E1). Do not gate on a fitted probability.
2. **Threshold-region blur.** Even an on-average-good calibration is worst exactly at the gate boundary. Mitigation:
   evaluate by the *downstream decision metric* (Δ nDCG@10 / Δ leak-rate / regression-rate), not by ECE; soft
   blend/hysteresis over a hard cutoff.
3. **Interpolation re-imports the scale problem + the regression-reduction claim is unproven.** Mitigation:
   normalized/rank-based blend; the §9-4 regression-rate test gates the whole gated path.

## Out-of-scope note (recorded, not adopted)
If a *listwise* judge is ever revisited, the meaningful 2025 development is the **permutation-invariant
cross-encoder** (Set-Encoder, arXiv 2404.06912, ECIR'25) — it removes position bias *structurally* (unlike the
LLM listwise rerankers Pass 1 ruled out) but only *matches* a strong pointwise CE at rank-1, so it does not
reopen "avoid the LLM judge for rank-1." Recorded for the second-phase bet only.

## §9 addition (from this pass)
- **§9-4 (mandatory acceptance test):** an A/B regression-rate eval of the gated judge path vs always-rerank, on
  held-out corpora, measuring the *rate at which reranking degrades the result* (the email-style case), not just
  the nDCG mean. The gate ships only if it demonstrably lowers that rate without a recall/leak regression. This
  converts the design's central hypothesis into a green test (`audit-without-test`).

---

# Confidence-building pass (2026-07-01)

> Goal: reduce *implementation surprises* before any feature code — test the design's load-bearing
> assumptions, do not implement E1/E2/E3. Approved plan: `~/.claude/plans/agile-dazzling-zebra.md`.

## What dropped (verified myself, primary source)

- **U3 (integration) — RETIRED. The E2 floor is a Head-only change.** Verified at the main checkout:
  the Head-side CE site captures per-doc CE scores in `ceScoresByDocId` from `reranked.getScores()`
  (`KnowledgeSearchEngine.java:652-660`) → the **margin signal (top1−top2) is computable Head-side**; per-leg
  HitStage **rank+score are always-on** (`SearchResponseBuilder.java:290-303`) → **leg-agreement is
  Head-reconstructable** without serializing the Worker's discarded D-004 signals; the CE score already folds
  into the canonical `SearchTrace` (`SearchTraceMapper.java:43-50`) → E1 is a 553 projection, not a fork. The CE
  block fully replaces order with **no existing gate/blend** (`:661-670`) → E2 is greenfield. Only an optional
  one-line Worker add (explicit `fusion.rank`, robust against LambdaMART reorder — and LambdaMART is inert) would
  harden the floor. **Net: the warranted-now scope (E1 + E2-floor + perf-skip) needs no cross-process work.**
- **U6 (test feasibility) — partially dropped.** `staged_recall_accounting.produce` already computes per-query
  `f_rank` and holds per-qid bucket lists, so the in-bucket rank distribution + a CE-on/off rank-delta are
  derivable from run artifacts with no instrument rewrite (a read-only extension).
- **Coordination flag cleared.** The `.claude/worktrees/643-judge-rung-conformance` worktree is actually
  tempdoc-662 work (misleading name), not a competing 643 implementation.

## What did NOT drop — a new tooling gap + the un-run measurements

- **NEW SURPRISE (tooling): jseval does not persist per-hit CE scores or per-leg ranks.** `artifacts.py` writes
  only `predictedDocIds` / `recallAtK` / `ndcgAtK` / `effectiveMode` / `decisionKind` per query + a fused-order
  `*_run.trec`. So the **signal-separation probe (U2/B3) is not supported by existing eval artifacts** — it needs
  a throwaway API probe (`debug`/`include_detail=true`, parse per-hit `SearchTrace` HitStages for CE score + leg
  ranks) against a running backend, **or** a small artifact-writer extension. This is a concrete pre-impl task,
  not a blocker, but it was previously unaccounted for.
- **U1 (headroom/scope) and U2 (signal separability — the crux) remain UNTESTED.** B1 found: backend down, no
  surviving run dirs (`tmp/eval-results/` empty — the gitignored-ephemeral case 636 warned of), only a stale
  single-corpus index with a lock. So U1/U2 require a fresh CE-on (U1) and CE-on/off (U2) eval campaign on
  scifact + enron-qa + the B3 probe — i.e. the **§9 gating measurements themselves**. Per the plan's decision
  gate (avoid a contention-risky GPU campaign inside a confidence pass; these gate *whether to build the active
  half*, not *how to build the floor*), they are recorded as the **gated first step of implementation**, below.

## The gated first implementation step (exact runnable procedure)

Run before committing E2's *active-promotion* half (the floor + perf-skip do not depend on it):
1. Fresh eval (worktree-safe per 644), CE-on, leg modes, on **scifact** + **enron-qa**:
   `jseval run --start-backend --clean --pipeline --ce --embedding --splade --dataset {scifact,enron-qa} --modes vector,lexical,splade,hybrid`.
2. **U1:** read each run's `staged_recall_accounting.json` `judge_headroom_ceiling`; run `jseval judge-ceiling`
   (the §5 probe) on both — the realistic capture %, never measured off the synthetic needle. Plus the in-bucket
   rank distribution (throwaway script over `*_run.trec` + `qrels.json`).
3. **U2 (crux):** a CE-on vs CE-off pair per corpus + a throwaway probe capturing per-query {gold rank Δ (on−off),
   CE top1−top2 margin, Head-reconstructed leg top-K overlap}; measure whether the signal separates
   "CE-helped" from "CE-hurt" (target AUC ≳ 0.65). Overlap as bad as D-004's fusion-side ⇒ E2 gate likely
   infeasible ⇒ fall back to floor-only / defer active-promotion.
4. **§9-4 acceptance test** then becomes implementable: regression-rate (gated vs always-rerank) on held-out
   corpora.

## Sources (pass 2, verified to resolve)
Scale Calibration of Deep Ranking Models (SIGKDD 2022, 10.1145/3534678.3539072) · Penha & Hauff (EACL 2021) ·
Not All Relevance Scores are Equal (2105.04651) · CONFLARE (2404.04287) · Principled Context Engineering for RAG
(2511.17908) · Is Conformal Factuality Robust (2603.16817) · TASR (2606.13814) · Efficient Re-ranking via Early
Exit (SIGIR'25, 10.1145/3726302.3729962) · AcuRank (2505.18512) · Stochastic Retrieval-Conditioned Reranking
(SIGIR-ICTIR'22, 10.1145/3539813.3545141) · Can LLM Rerankers Predict Their Own Performance (2606.03535) ·
Injecting BM25 Score as Text (2301.09728, ECIR'23) · Set-Encoder (2404.06912, ECIR'25) · FIRST (2406.15657).
*Caveat:* 2026-dated items are recent/single-source; the "gating reduces regressions" claim is unsupported and is
ours to prove (§9-4).
