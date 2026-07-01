---
title: "Judge-stage arbitration — bound the cross-encoder by a *relative* relevance-confidence signal (score-margin + leg-agreement, NOT a fitted per-corpus calibration — see Research pass 2) so it cannot regress below fusion (a 'refinement floor') and can be skipped when fusion is already confident; gate any *active* rank-promotion on real-corpus headroom. Reframes the original 'sharper judge / ranked-below-cutoff' stub: JUDGE_RANK_LOW is *in-window-not-first* (not below-cutoff), the obvious judge levers are dead/harmful (F-006 swaps≈0, F-002 CE hurts email), and the present defect is the CE replacing fusion *unconditionally*. Conforms to the D-004 per-query arbitration seam + the 553 canonical-record/projection seam; reveals the *stage-non-regression* principle (extends D-005 recall-survival to property-survival)."
type: tempdocs
status: IMPLEMENTED IN FULL (2026-07-01, worktree `643-judge-arbitration`) — superseding an earlier PARTIALLY-IMPLEMENTED status line left over from a mid-session pass. E1 (the computed per-query confidence signal), a confidence-driven E2 (replacing the earlier static-alpha floor), and perf-skip are all built, tested, and live-verified end-to-end against a real backend (register F-026; see §E1/E2/perf-skip implementation + §9-4 acceptance test). Two rounds of critical re-review (code-level, then measurement-methodology) found and fixed real issues, including a top-2 CE-margin bug and a trec-based-rank blindness in a widely-used jseval projection (root cause logged as an observation, out of this tempdoc's scope to fix register-wide). The `## What is deferred (Stage 3 — NOT built)` section below is now historical — it described the pre-E1/E2/perf-skip state and is superseded by the later implementation. Default-on remains a deliberate future decision, not part of this closure. See §Closure (bottom of doc) for the current, final summary.
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

---

# Implementation closure (2026-07-01, worktree `643-judge-arbitration`)

> Implemented per the approved plan (`~/.claude/plans/agile-dazzling-zebra.md`). This section is the as-built
> record: what shipped, what was measured live, what is deferred, and why. Full evidence + caveats are in
> register **F-026** (`docs/reference/search-quality-register.md`) — this section summarizes and points there.

## What shipped (Stage 1 + Stage 2 — unconditional)

- **Stage 1a — per-hit judge signals persisted in jseval.** New `provenance.extract_judge_signals(hit)`
  (sibling of `extract_hit_evidence`, keeps BM25/SPLADE ranks **separate** rather than collapsed — needed for
  leg-agreement) + a `judgeSignals` list on every `{mode}_per_query.json` entry (`artifacts.py`). Tested
  (`test_provenance.py`, `test_artifacts.py`) and **live-verified** against a real backend (scifact run: real
  per-hit `bm25_rank`/`dense_rank`/`splade_rank`/`fusion_score`/`ce_score` values observed in the artifact).
- **Stage 1b — in-bucket rank histogram.** `staged_recall_accounting.produce()` now emits
  `aggregate.judge_rank_histogram` (`rank_2`/`rank_3_5`/`rank_6_10`/`rank_11_plus` counts within
  `JUDGE_RANK_LOW`) — additive, existing keys unchanged (`leak_gate` etc. unaffected). Tested + **live-verified**:
  real scifact data showed a substantively-spread distribution, not near-ceiling (see F-026).
- **Stage 2 — the judge-stage refinement floor.** New static pure helper
  `KnowledgeSearchEngine.blendPreRerankAndCrossEncoder(preRerankScores, crossEncoderScores, alpha)`: min-max
  normalizes both score arrays within the CE window and sorts by `alpha·preRerankNorm + (1−alpha)·ceNorm`.
  `alpha=0` is provably identical to today's CE-only order (normalization is monotonic). Wired into
  `KnowledgeSearchEngine`'s rerank block, gated by new config `justsearch.rerank.judge_blend_enabled` /
  `judge_blend_alpha`, threaded through the full chain (`EnvRegistry` → `ResolvedConfigBuilder.buildReranker` →
  `ResolvedConfig.Ai.Reranker` → `RerankerConfig` record + `from()` + `DISABLED`). **Default off** — a no-op by
  construction when disabled. Unit-tested (`KnowledgeSearchEngineBlendTest` — 7 cases incl. the floor
  qualitatively suppressing a marginal CE flip while still yielding to a decisive one) and **live-verified**:
  Head + Worker config-snapshot logs both confirmed the flag/alpha propagate, and toggling it measurably changed
  real ranking decisions on a live scifact run (see F-026).
- **A correctness bug caught by the critical-analysis pass before it shipped:** the first wiring sized the CE
  score array to `min(topK, reranked.getScoresCount())`, which would have silently dropped candidates between
  that bound and `topK` from the result set entirely if the two counts ever diverged. Fixed to size by `topK`
  always, defaulting a missing CE score to `0f` — matches the existing code's tolerance for that mismatch
  (`ceScoresByDocId`'s loop) without truncating the candidate list.
- **Annotation correction:** `staged_recall_accounting.py`'s `FP_MAPPING` and `recall_profile.py`'s
  `_RECOMMENDATION` had `CASCADE_LEAK`/`JUDGE_RANK_LOW`'s Seven-Failure-Points labels backwards (§Finding A
  above) — corrected; `JUDGE_RANK_LOW` no longer claims a canonical FP match it doesn't have, and its
  recommendation text no longer points at the dead/harmful levers this investigation ruled out.
- **Docs:** `docs/reference/configuration/environment-variables.md` (the two new flags) and
  `docs/reference/search-quality-register.md` (Finding **F-026**, a Measurement-Gaps row, a Q-013 cross-reference)
  updated; `llmstxt-generate.mjs` / `skills-sync.mjs` regenerated.

## What was measured live (summary — full numbers + caveats in F-026)

Worktree-isolated eval (tempdoc 644), GPU RTX 4070, **reranker on CPU** (capability warning, not GPU — noted
caveat). `beir/scifact`, 300 queries, hybrid mode:

| Run | final nDCG | judge_low_rate | rank histogram (2 / 3-5 / 6-10) |
|---|---|---|---|
| CE-on, floor OFF (today) | 0.7512 | 0.27 | 28 / 39 / 14 |
| CE-on, floor ON (α=0.5) | 0.7490 | 0.28 | 28 / 34 / 22 |
| CE-off | 0.7584 | — | — |

U2 signal-separation (CE-on vs CE-off rank delta vs {CE margin, leg-Jaccard}): **9 non-neutral queries** (1
helped, 8 hurt, 261 neutral, 30 no-gold) — margin AUC 0.75, Jaccard AUC 0.69 (right direction, too thin to trust).

**`mixed/enron-qa` — the decisive corpus for this design (F-002/F-008's largest CE-hurts signal) — was
unavailable in this environment** (`datasets/mixed/enron-qa/corpus.jsonl` missing; real email data, not
auto-downloadable). This is the dominant residual gap.

## What is deferred (Stage 3 — NOT built)

> **Superseded (2026-07-01):** this section describes the state at the end of the initial implementation
> pass, before the doc's own later `§Conceptual re-review` found this framing was itself a scoping error
> (there is no separate "Stage 3, deferred" bucket — see that section). Confidence-gating (perf-skip) and a
> confidence-*driven* E2 were subsequently built in full — see `## E1/E2/perf-skip implementation + §9-4
> acceptance test`. Left as-is below (append-only history), not rewritten.

Confidence-gating (perf-skip when fusion is confident) and active rank-promotion (let a confident CE dominate)
are **not implemented**. Per the plan's decision rule, the evidence gathered is directionally encouraging but
statistically too thin (n=9) and missing its most decisive corpus to justify an active behavioral change. **Do
not build Stage 3 from this state** — first re-run the U1/U2 measurements on `mixed/enron-qa` when available
(procedure: `docs/reference/search-quality-register.md` F-026 "Open follow-up"). This is an honest, expected
outcome the plan explicitly anticipated, not a failure to execute.

## Verification performed
- `python -m pytest` (jseval, full suite minus one pre-existing unrelated failure): **1061 passed**.
- `./gradlew.bat :modules:configuration:test :modules:reranker:test :modules:app-services:test`: all green.
- Live end-to-end: config propagation confirmed in Head + Worker logs; real ranking behavior change confirmed
  via the rank histogram shift between floor-on/off runs on a real backend + real corpus.
- **Browser / dev-stack UI validation (real UI, required for user-visible work):** built a fresh `installDist`
  from this worktree, started the dev stack (`scripts/dev/dev-runner.cjs`), and drove the real `shell-v0` chat/
  search UI via Chrome. A live query ("AI capabilities") returned correct, well-formed results; the "Why this
  result?" evidence panel showed the full per-stage breakdown firing correctly end-to-end — `Sparse (BM25) · #1 ·
  2.66`, `Dense (vector) · #1 · 0.57`, `Fusion · 1.00`, **`Cross-encoder · 0.30`** — confirming the CE reorder
  path (which my Stage 2 change sits inside, default-off) executed with zero console errors and zero regressions
  in the default (unchanged) behavior. Dev stack stopped cleanly after.

## Post-implementation critical-analysis pass (2026-07-01)

> The bidirectional-pass discipline: a second look at the shipped diff, asking "what would catch what tests
> missed?" Found one substantive design-vs-implementation mismatch and two smaller correctness gaps, all fixed
> in the same session before closure.

**Finding 1 (high) — the floor read the wrong pre-rerank signal in exactly the cases it exists to protect.**
The blend wiring read only the `"fusion"` HitStage. Verified against `HitProvenanceProjector.java` directly:
`"fusion"` is **absent** for single-leg presets (`attachSingleLeg` passes `fusionMethod=null` — affects
BM25-only/`text`, dense-only/`vector`, SPLADE-only/`splade`, three of the four CE-eligible presets) and
**stale** whenever chunk-branch fusion ran (`attachBranchFusion` — the doc's true final score lives on a
separate `"branch-fusion"` stage; `"fusion"` there still reflects only the whole-doc branch's own internal
score). Per register F-014, chunk merge fires on **all** EnronQA queries — the exact corpus this design targets.
Left unfixed, the floor would have been a silent no-op for single-leg presets and would have read a stale
signal on the very `mixed/enron-qa` re-measurement this doc recommends as the next step.
- **Fix:** new `SearchTraceMapper.protoStageScoreAny(sr, wireIds...)` — tries each candidate stage id in
  priority order (`"branch-fusion"`, `"fusion"`, then whichever single leg stage is present), returning the
  first *present* one (presence, not value — a genuine `0.0` fusion score is never mistaken for "absent").
  Wired into `KnowledgeSearchEngine`'s blend block in place of the single-id read. Tests:
  `SearchTraceMapperTest.java` (7 cases: branch-fusion-wins, fusion-fallback, each of the three single-leg
  fallbacks, nothing-present→0f, real-zero-is-honored).
- **F-026's already-recorded scifact numbers are unaffected** — that run used `hybrid` mode with
  `chunk-merge: SKIPPED_SHORT_CORPUS` (confirmed live in the browser-validation curl output), i.e. `"fusion"`
  was present and not stale for that specific measurement. No retraction needed; this is a forward-looking
  correction for the presets/corpora not yet measured — including the recommended `mixed/enron-qa` follow-up.

**Finding 2 (medium) — the identical blind spot in the measurement tooling.**
`provenance.extract_judge_signals()`'s `fusion_score` field had the same single-stage read, which would have
fed the same wrong signal into any future analysis (including the very re-measurement Finding 1 flags).
**Fix:** the same branch-fusion-then-fusion fallback, checked by value (`is not None`) so a `"branch-fusion"`
stage that is present-but-scoreless correctly falls through rather than reporting `None`. Tests added to
`TestExtractJudgeSignals` (`test_provenance.py`).

**Finding 3 (low-medium) — a non-neutral defensive default.**
When `i >= reranked.getScoresCount()` (a mismatch the code's own comment calls "not expected in normal
operation"), the missing CE score defaulted to `0f`. Real CE scores observed live are raw logits, frequently
negative (`-0.50`, `-0.66`, `-0.86`) — `0f` reads as an artificially *high* score, which could wrongly promote
a candidate the CE never actually judged. **Fix:** a two-pass build in the wiring block computes the minimum
*observed* CE score first; missing entries default to that minimum ("tied for worst known"), never an implicit
boost. This is a defensive branch inside `doSearch()`'s wiring (not the pure blend helper) at the same testing
boundary LambdaMART's equivalent array-building loop already sits at — fixed + commented, no new
integration-test infrastructure manufactured for it (consistent with existing precedent, not a new gap).

**No security or privacy issue found.** Verification: full targeted + jseval test suites green (1065 jseval
tests, +4 new, 14 new Java test cases across `SearchTraceMapperTest`/`KnowledgeSearchEngineBlendTest`).

**Regression-safety re-run.** Re-ran the exact scifact CE-on floor-on eval after the fix:

| | pre-fix | post-fix | delta |
|---|---|---|---|
| `final_ndcg` | 0.7490 | 0.7551 | +0.0061 |
| `judge_low_rate` | 0.28 | 0.28 | 0 |
| `judge_rank_histogram` | `{2:28, 3-5:34, 6-10:22}` | `{2:27, 3-5:36, 6-10:21}` | total unchanged (84), ±1-2 queries shifted between adjacent bins |
| `leak_rate` | 0.033 | 0.023 | ~1-2 queries |

Not byte-identical, but the **code-path is provably unchanged** for this case: scifact-hybrid never triggers
chunk-branch fusion (`chunk-merge: SKIPPED_SHORT_CORPUS`, confirmed live), so `protoStageScoreAny` finds no
`"branch-fusion"` stage and falls through to `"fusion"` — the exact same value `protoStageScore` returned
before (proven directly by the `fallsBackToFusionWhenNoBranchFusion` unit test). The residual numeric wobble
(same total judge-low count, a couple of queries shifted ±1 rank bin, nDCG delta well inside the ~1.25% swing
already observed across every scifact re-index earlier in this session — 0.7512→0.7584→0.7490 on identical
configs) is attributable to normal re-indexing/embedding non-determinism, not the fix. The unit-test proof is
the primary regression-safety evidence here; the eval re-run is corroborating, not decisive, given this
corpus's known noise floor.

## Conceptual re-review (2026-07-01) — the shipped floor is not what D-1/D-2 designed

A fresh, full re-read of this doc's own §Long-term design against what actually shipped found a real gap
distinct from the code-correctness findings above: **D-2 scopes E1 (the confidence-signal primitive) and
E2's perf-skip as "IN — the present problem requires it," unconditional — not deferred.** What shipped is a
**static, operator-configured `alpha` blend with no computed confidence signal driving it at all.** This
means:
- The shipped floor **reduces** the CE's influence uniformly; it does not deliver the design's stated
  guarantee ("cannot leave a hit worse-ordered than fusion... beyond what its calibrated confidence
  justifies") — that guarantee specifically requires the blend weight to respond to per-query confidence,
  which nothing computes today.
- **Perf-skip** — explicitly scoped as part of "E2 as a floor," and named in T3-a/T6-e as the key Pareto
  insight unifying 643 with 647/648 — was not built at all, and was mislabeled under "Stage 3, deferred" in
  the implementation plan, when D-2 never made it contingent.
- **E1 itself** — the "shared primitive," scoped as warranted *now* with two present-day consumers (E2's
  gate, and Q-009's FE surface) — was never built anywhere, production or eval-tooling, beyond the raw
  ingredients (per-hit CE score + leg ranks persisted in jseval for measurement only).
- U1's own designated primary number (the live §5 LLM-judge-ceiling probe's realistic capture-fraction on
  real corpora — called "the single most important number") was never run; the weaker, already-available
  AI-free ceiling was substituted without flagging the substitution.

**Net: what shipped is a real, safe, tested risk-reduction, correctly labeled in code and tests — but it is
not "the refinement floor" this tempdoc's title and design describe.** The remaining, actual feature work is
E1 (a computed per-query relative-confidence signal) + a confidence-*driven* E2 (replacing the static alpha)
+ perf-skip, still gated on a properly-run U1/U2.

## Confidence-building pass 2 (2026-07-01) — de-risking the actual remaining work

Before building E1/real-E2/perf-skip, this pass tested the load-bearing assumptions for that work
specifically (approved plan, no feature code changed). Findings:

- **CU2 (`mixed/enron-qa` availability) — REVERSED from "unavailable."** The corpus was wrongly written off
  as a hard environment limitation. `scripts/search/convert-enronqa-to-beir.py` already exists, is fully
  documented, and its default args (`--user dasovich-j --max-queries 300`) exactly match the register's
  established baseline. `huggingface.co` is reachable from this environment; only the `datasets` pip package
  was missing. **Acquired successfully this pass** — `datasets/mixed/enron-qa/` now exists (5485 docs, 300
  queries, 300 qrels, verified via jseval's own loader) and is available in this worktree going forward.
  - **Side effect caught and fixed:** `pip install datasets` pulled a newer `click` that broke jseval's own
    CLI entirely (a crash inside click's own `handle_parse_result`). Pinned back to
    `click>=8.1.3,<8.2.2` (satisfying the pre-existing `inspect-ai` constraint); full jseval suite (1065
    tests) re-verified green after the pin. Logged: `huggingface-hub` nominally wants `click>=8.4.0`, an
    unresolved-but-apparently-harmless pip warning — watch for it if `datasets`/HF functionality is extended.
  - **Unrelated pre-existing bug found along the way:** bare `python -m jseval --help` (no subcommand)
    crashes with `UnicodeEncodeError` on a Greek σ character when stdout isn't UTF-8-aware (e.g. redirected
    on Windows/cp1252); subcommand help (`jseval run --help`) is unaffected. Logged to observations, not fixed
    (out of scope).
- **CU1 (crux) — real enron-qa evidence gathered; still not decisive.** Ran the CE-on/CE-off pair on the
  newly-acquired corpus (hybrid nDCG: CE-on 0.7146, CE-off 0.7166 — CE hurts, direction matches F-002) and
  the U2 signal-separation script: **12 non-neutral queries** (4 helped, 8 hurt; margin AUC 0.625, Jaccard AUC
  0.75) — bigger than scifact's n=9 but still thin. **Pooling scifact + enron-qa** (n=21: 5 helped, 16 hurt):
  **margin AUC = 0.65** (exactly at this doc's own stated threshold), **Jaccard AUC = 0.617** (below it). The
  signal is directionally consistent across both corpora (always > 0.5) but sits right at the boundary
  between "build it" and "don't" — genuinely ambiguous, not a clean answer either way.
- **CU3 (live §5 judge-ceiling probe) — attempted, cleanly failed, root-caused.** `backend.start_backend
  (llm=True)` fails: `native-bin/llama-server/llama-server.exe` is absent in this worktree (jseval's raw
  backend path doesn't auto-stage it the way `dev-runner.cjs start` does per branch-safety.md), **and** the
  configured model (`Qwen_Qwen3.5-9B-Q4_K_M.gguf`) is absent from main's `models/` too — two independent
  missing artifacts, not a VRAM/config problem. Unlike enron-qa, no small fix was found or attempted (a
  multi-GB model download is a materially heavier, separately-permissioned action, not attempted here). **The
  realistic-headroom number this doc calls "the single most important" remains unmeasured**, now for a
  precisely diagnosed reason rather than an assumption.
- **CU5 (leg-agreement staleness) — confirmed as a real, scoped gap, not assumed away.** The earlier
  confidence-building pass's "leg-agreement is trivially Head-reconstructable" claim is incomplete:
  `HitProvenanceProjector.attachChunkMerge` writes chunk-branch leg ranks into a **separate** `chunk-merge`
  detail-map (keys `chunk_sparse_rank`/`chunk_vector_rank`/etc., gated by `include_detail=true` — not
  currently requested by jseval's request builder), distinct from the always-on top-level `sparse-retrieval`/
  `dense-retrieval` stages (the whole-doc branch only). A correct, chunk-aware leg-agreement signal needs to
  request `include_detail` and union both sources per-hit. Bounded and addressable, but a real design item a
  future E1 must include — not free, as previously assumed.
- **CU4 (wire-schema cost) and CU6 (perf-skip integration) — reconfirmed low-risk via static inspection**,
  consistent with the earlier pass: `HitStage.detail` is a genuinely open `map<string,float>`
  (`additionalProperties: {type: number}` in the JSON schema) — a new confidence key needs zero schema regen;
  `crossEncoderSkipReason`/`crossEncoderApplied` are consumed only within `SearchTraceMapper.java` and
  `KnowledgeSearchEngine.java` — a new skip reason is a contained addition.

## E1/E2/perf-skip implementation + §9-4 acceptance test (2026-07-01)

Built the actual remaining feature work per the confidence-building-pass-2 plan, mirroring D-004's
`HybridSearchOps.computeArbitrationAlpha` shape exactly:

- **E1** — `KnowledgeSearchEngine.computeJudgeArbitrationAlpha(window, crossEncoderScores, baseAlpha,
  fusionProtectAlpha)`: computes the CE margin (normalized top1−top2 gap) and a leg-agreement Jaccard
  (`sparse-retrieval`/`dense-retrieval` top-K doc-ids), with the CU5 chunk-merge bailout (a `"chunk-merge"`
  HitStage present → leg signal untrustworthy → fall back to `baseAlpha`, don't intervene). Returns
  `Math.max(baseAlpha, fusionProtectAlpha)` only when the CE margin is thin AND legs agree; otherwise
  `baseAlpha` unchanged — structurally bounded to never trust the CE *less* than today's unconditional
  baseline, only ever protect *more* via fusion.
- **E2** — wired into the existing blend call site (`KnowledgeSearchEngine.java` ~861): when
  `judgeArbitrationEnabled` is on, `alphaToApply` is computed via E1 instead of reading the static
  `judgeBlendAlpha()`; off (default) is a byte-identical no-op to the shipped floor.
- **Perf-skip** — a SEPARATE, stricter gate (`isFusionDecisiveForSkip`), gated by its own
  `judgeArbitrationSkipEnabled` flag: skips the CE RPC entirely (pre-RPC, so only the leg-agreement half of
  E1's signal is available — no CE margin exists yet) when legs are decisively agreeing. Deliberately
  inverts E1's "unknown → don't intervene" convention: for perf-skip, an inconclusive signal means "not
  decisive enough to skip" (call the CE as normal), since skipping a query the CE would have fixed is a
  sharper risk than re-weighting it.
- **Config chain** — `judgeArbitrationEnabled` / `judgeArbitrationAlphaDiverge` (default 0.85) /
  `judgeArbitrationSkipEnabled`, threaded through the same 4-file chain as `judgeBlendEnabled`/
  `judgeBlendAlpha` (`EnvRegistry` → `ResolvedConfigBuilder` → `ResolvedConfig.Ai.Reranker` →
  `RerankerConfig`). All three default `false`.

**A real bug caught by writing the arbitration test class, not by the implementation review itself:** the
initial top-2 CE-margin scan seeded `top1`/`top2` from `ceNorm[0]` instead of a proper sentinel
(`Double.NEGATIVE_INFINITY`). This silently zeroed the margin whenever the window's true top CE score
happened to sit at pre-rerank position 0 (not a rare case — a good fusion order correlates with a good CE
order more often than chance). Caught by `KnowledgeSearchEngineArbitrationTest.decisiveMargin_returnsBaseAlpha`
failing unexpectedly; fixed and all suites re-verified green. This is exactly the class of defect
`bidirectional-pass`'s post-implementation critical-analysis step exists to catch, and did.

### §9-4: A/B regression-rate acceptance test

Per-query regression rate: for each judged query, reconstruct the pre-rerank (fusion) gold rank from
`judgeSignals.fusion_score` and compare it to the FINAL gold rank. **Methodology finding (logged to
observations, out of scope for this tempdoc):** jseval's own `{mode}_run.trec` and aggregate nDCG/recall are
**blind to CE/blend list-reordering** — the CE/blend stage only ever reorders the result list, never rewrites
each hit's top-level `score` field (true even in the pre-tempdoc-643 baseline), and both
`_write_trec_run`'s re-sort and `ir_measures`' ranking key off that unrewritten score. Only per-query
`predictedDocIds` (true API response order) reflects the real post-blend order — this analysis uses that
field, not the trec file or the run's own aggregate metrics.

Ran hybrid CE-on (`judge_blend_enabled=true`) on `beir/scifact` and `mixed/enron-qa` (300 queries each),
once with `judge_arbitration_enabled=false` (today's shipped static floor, α=0.5) and once `=true`
(α_diverge=0.85):

| Corpus | Config | Regressed | Improved | Regression rate |
|---|---|---|---|---|
| scifact | ARB_OFF | 15/300 | 28/300 | 5.00% |
| scifact | ARB_ON | 11/300 | 31/300 | 3.67% |
| enron-qa | ARB_OFF | 22/300 | 46/300 | 7.33% |
| enron-qa | ARB_ON | 22/300 | 45/300 | 7.33% |

Aggregate counts alone would read as "no effect on enron-qa" — interrogating which *specific* queries
regressed (not just the count) tells a different, more accurate story:

- **scifact: net positive, 5:1 fix:break ratio.** Arbitration fixed 5 previously-regressed queries
  (`1041, 213, 238, 535, 971`) and caused exactly 1 new one (`831`) — net −4 regressions, matching the
  aggregate delta exactly.
- **enron-qa: a wash, 1:1 fix:break ratio, not a conservative no-op.** Arbitration fixed 2 queries
  (`q_206, q_294`) and caused 2 new ones (`q_219, q_273`) — net 0. This is NOT the CU5 chunk-merge bailout
  firing (checked directly: `chunkMergeApplied=false` for all 300 queries in this run, contradicting this
  doc's own earlier assumption that F-014's "chunk merge fires on all 300 enron-qa queries" would explain a
  no-op here) — the gate genuinely evaluated real leg-agreement/CE-margin signal on every query and it
  simply wasn't discriminative enough to net a benefit. This matches confidence-building-pass-2's own
  measurement almost exactly: the pooled scifact+enron-qa signal-separation AUC sat "right at the boundary"
  (margin AUC=0.65, Jaccard AUC=0.617) — a wash on the weaker-signal corpus and a real win on the
  stronger-signal one is the predicted shape, not a surprise.

**Conclusion:** the mechanism is never net-harmful on either corpus (worst case is a wash, not a regression)
and is measurably net-positive on scifact. Per this plan's own non-goals and D-004's default-off →
measure → default-on template, **defaults stay off** — this evidence supports that default-on is a
reasonable follow-up decision once `judge_blend_enabled` itself has its own default-on case made (F-026's
existing wobble/noise caveat on the underlying floor still applies), not that this plan should flip it
unilaterally.

## Second critical-analysis pass (2026-07-01) — findings on the E1/E2/perf-skip phase

A dedicated critical-review request against this phase's own diff (E1/E2/perf-skip, config chain, tests,
§9-4 evidence) found no security/privacy issues, but four real findings, all now fixed:

1. **[Major] F-026's own "Floor OFF vs Floor ON" evidence used trec-based rank, which is structurally blind
   to the floor's list-only reordering.** Recomputed directly on the archived run pair that produced the
   original numbers: the headline `judge_low_rate` barely moves when corrected (0.270→0.267 for Floor OFF),
   but the floor-effect claim itself was wrong — trec-based comparison showed only 12/300 queries shifting
   bucket (exactly the published "5 queries rank_3_5→rank_6_10"), which is provably noise between the two
   eval runs, not the floor's effect (trec-order cannot see it at all). True final-order comparison shows
   **58/300** queries shift — the floor's real per-query effect is ~5x larger than reported, though its
   aggregate direction is not distinguishable from this corpus's own documented run-to-run wobble from a
   single-run comparison. Fixed: F-026 corrected in place (search-quality-register.md) with the true
   numbers and an honest account of what the original claim actually measured. My own §9-4 numbers above
   were unaffected (that script used `predictedDocIds` from the start). Root cause
   (`staged_recall_accounting.py`'s trec-preference) logged as an observation for a future dedicated
   tempdoc — fixing it register-wide is out of this tempdoc's scope (confirmed with the user).
2. **[Moderate] §9-4's own regression-rate numbers had a narrower survivorship-bias**: the hybrid preset's
   CE window defaults to 20 (`rerankConfig.topK()`), but the jseval runs used the default display page of
   10 — so a query where CE/blend pushes gold from a would-be-visible rank to a still-invisible rank beyond
   position 10 was undetectable. Fixed: re-ran all 4 §9-4 configs with `--top-k 20` (matching the CE
   window); results below.
3. **[Minor] Doc-precision gap**: `JUSTSEARCH_RERANK_JUDGE_ARBITRATION_ENABLED`'s doc claimed a blanket
   "requires judge_blend_enabled," true only for the alpha-computation effect — perf-skip (gated by the
   same flag) is independent of it. Fixed in `EnvRegistry.java`, `RerankerConfig.java`, and
   `environment-variables.md`.
4. **[Minor-moderate] No test guarded the wiring itself** (only the extracted pure functions were tested).
   Fixed by extracting the two inline gating decisions into `resolveBlendAlpha`/`shouldSkipCrossEncoder`
   (package-private static methods, zero RPC mocking needed) and adding gating tests for each flag
   combination in `KnowledgeSearchEngineArbitrationTest.java`.

### §9-4 re-measured with `--top-k 20` (supersedes the original §9-4 numbers above)

Re-ran all 4 configs (hybrid CE-on, 300 queries each) with `--top-k 20` matching the CE window default,
closing the display-page survivorship-bias gap. (The `arb_regression_rate.py` sentinel for "gold absent
from the window" also had to be fixed from a hardcoded `11` — correct only at `--top-k 10` — to
`max(window_sizes) + 1`, since 11 is a valid real rank at window width 20.)

| Corpus | Config | Regressed | Improved | Regression rate | vs. original (top-k 10) |
|---|---|---|---|---|---|
| scifact | ARB_OFF | 17/300 | 33/300 | 5.67% | 5.00% |
| scifact | ARB_ON | 12/300 | 31/300 | 4.00% | 3.67% |
| enron-qa | ARB_OFF | 25/300 | 49/300 | 8.33% | 7.33% |
| enron-qa | ARB_ON | 26/300 | 49/300 | 8.67% | 7.33% |

Both corpora's rates rose under the wider window, confirming the predicted direction (the original numbers
were a conservative undercount). The per-query fix/break breakdown:

- **scifact: cleaner win than originally measured.** Arbitration fixed 5 queries (`1041, 213, 535, 971,
  1226`) and caused **zero** new regressions (vs. 1 new regression in the original top-k-10 measurement) —
  net −5, a strictly one-directional improvement with no offsetting cost.
- **enron-qa: a small net negative, not an exact wash.** Arbitration fixed 1 query (`q_181`) and caused 2
  new ones (`q_159, q_166`) — net +1 regression (26 vs 25). The original top-k-10 measurement reported an
  exact wash (2 fixed, 2 caused); the corrected, wider-window measurement shows a thin but real net
  negative on this corpus. The magnitude (1/300 = 0.33pp) is not a strong signal either way, and is
  consistent with the borderline pooled AUC already measured (confidence-building-pass-2) — but it means
  the honest characterization is "roughly neutral, slightly unfavorable," not "never net-harmful."

**Corrected conclusion (supersedes the original §9-4 conclusion above):** the mechanism is a clean,
one-directional win on scifact and a small, thin net negative on enron-qa — not "never net-harmful on
either corpus." This does not change the shipping decision (defaults stay off regardless, per this plan's
own non-goals and D-004's template), but it is a more honest characterization of the evidence than the
original write-up gave, and reinforces that a corpus with weak underlying signal (enron-qa, per the
borderline AUC) should not be expected to show a clean win just because scifact does.

## U1: live judge-ceiling probe result (2026-07-01)

With the user's explicit authorization, downloaded the packaged-default chat model
(`Qwen_Qwen3.5-9B-Q4_K_M.gguf`, 5.5GB, SHA-256 verified against `model-registry.v2.json`'s manifest value)
and ran the actual U1 probe — `jseval judge-ceiling` (tempdoc 636 §5), previously blocked three separate
times on a missing model file.

**Infrastructure gaps found and fixed along the way** (none of this had ever been exercised live before):
- `llama-server.exe` was not staged anywhere on this machine — jseval's raw backend path
  (`jseval dev --llm`) does not auto-stage it the way `dev-runner.cjs start` does. Fixed by running the
  project's own `stageLlamaServerFromPrebuilt`/`stageLlamaServer` Gradle tasks and copying the result into
  both `native-bin` locations the Head process checks (`modules/ui/native-bin/llama-server/` and
  `<worktree-root>/native-bin/llama-server/` — `runHeadlessEval` resolves the latter, `dev-runner.cjs` the
  former; they're not the same path).
- `judge_ceiling.py`'s `make_chat_rank_fn` had `max_tokens=512`, too small for a realistic leg-union pool
  (observed up to 29 candidates on scifact) — truncated the JSON response mid-string. Fixed to 2048 (paired
  with bumping `timeout` from 120s→300s to match observed CPU throughput at the time).
- More importantly: `judge_ceiling_report`'s loop had no per-query error handling — a single query's
  malformed/truncated response aborted the *entire* probe (discarding every other query's already-completed,
  already-paid-for LLM call). Fixed to catch `AIUnavailable` per query, skip just that query, and only
  degrade the whole probe if *every* attempted query fails (genuine unavailability, not an isolated hiccup).
  Both fixes are covered by new tests in `test_judge_ceiling.py` (11 tests total, up from 9).
- The CUDA-accelerated llama-server variant's initial build failed with a transient `SSLHandshakeException`
  fetching its prebuilt zip from GitHub's release CDN — confirmed transient (a sibling download in the same
  build succeeded, and a bare retry with zero code changes succeeded cleanly) via `curl` reaching the same
  URL fine both before and after. Staged the CUDA variant too once retried successfully — GPU inference
  (RTX 4070, 33/33 layers offloaded, confirmed via `nvidia-smi` VRAM delta) is dramatically faster than the
  CPU baseline and is what actually produced the results below.

**Methodology finding while first attempting the probe (worth recording as its own lesson):** the first
run used `judge-ceiling` without `--corpus-dir` (no `docs.jsonl` existed for the BEIR-materialized scifact
corpus, which jseval keeps as per-doc `.txt` files instead). This degrades the probe to *text-light* mode —
the LLM ranks candidates using **only their bare numeric doc-ids**, no actual document content. That run
produced `top1_agreement: 0.0` (the order-swap position-sensitivity guardrail) — a value low enough to be a
red flag in itself, not a real finding: an LLM with zero content to reason about can only be reacting to
prompt position, not relevance. Built a minimal `docs.jsonl` from the corpus's `.txt` files (663 doc-ids —
exactly the ones this run's candidate pool referenced) and re-ran with `--corpus-dir` pointed at it. This is
the credible run; the text-light run is not reported as a finding, only as the reason the credible run's
methodology was tightened.

**Correction (2026-07-01, later the same day) — the first-reported result below was wrong, and the corrected
result reverses its conclusion.** While building an unrelated AI-free cross-check (`ce_replay_report`, see
`## Sibling-thread reconciliation`), an implausible number in that new probe led to interrogating the shared
scoring function both probes use, `_score_ranking`. It was passing this run's full corpus `qrels.json`
(300 scifact queries) straight into the nDCG computation, but this run only judged 40 of them (a capped
`--max-queries` run) — the scorer silently treated every one of the other ~260 corpus queries as a
zero-relevance miss and folded that into the mean. The result was diluted by roughly the ratio of judged to
total corpus queries. This affects `_score_ranking` for every caller, not just this probe, and was fixed at
that single root cause with a regression test (`scripts/jseval/jseval/judge_ceiling.py`,
`test_capped_run_qrels_does_not_dilute_the_score`). The probe was re-run against the exact same archived
model outputs' inputs (same run, same corpus text, a fresh live call since the raw per-query rankings
were not persisted) with the fix in place.

**Original (wrong) result, kept for the record:** `llm_ndcg=0.111` vs `final_ndcg=0.831`,
`capture_fraction=−6.06` — reported as "this local model performs dramatically worse than the pipeline."
That conclusion does not survive the fix below and should not be relied on.

**Corrected result** (scifact, 40-query sample, GPU-accelerated, real document text, 38/40 queries succeeded):

| Metric | Value |
|---|---|
| `final_ndcg` (current shipped pipeline) | 0.831 |
| `llm_ndcg` (this local model reranking the leg-union pool) | **0.874** |
| `judge_headroom_ceiling` (AI-free, perfect-judge upper bound) | 0.119 |
| `headroom_realized` (llm_ndcg − final_ndcg) | **+0.042** |
| `capture_fraction` (headroom_realized / ceiling) | **+0.357** |
| position-sensitivity (`top1_agreement`, forward vs. reversed order) | 0.658 |

**This reverses the earlier conclusion.** `top1_agreement=0.658` is the same kind of reasonable,
non-degenerate value as before (the model is meaningfully consistent across presentation order, not reacting
to prompt position alone), so this remains a credible measurement, not an artifact of the fix overcorrecting.
The corrected reading: **this local model, used via the same single structured-JSON listwise reranking call,
outperforms the current shipped pipeline on this 40-query sample** (nDCG 0.874 vs 0.831) and captures a real,
positive **36% of the AI-free theoretical ceiling** — a meaningful, decision-relevant amount of headroom, not
a rounding error. This is also the more internally-consistent result: a smaller-scale pointwise sanity check
run earlier in this same investigation (Confidence-building pass 3, R4) already showed this same model
correctly identifying the gold document with a clear score margin on nearly every query it was asked about —
that finding was always in tension with the originally-reported catastrophic listwise failure, and the tension
is resolved now that the failure is known to have been a measurement artifact, not a real result.

**What this settles, revised:** D-2 scoped "a stronger/heavier judge model" as OUT, on the CE-model-swap
evidence (F-006, ≈0 nDCG difference) and cost/complexity grounds. U1 was meant to be the one number that could
overturn that scoping decision by showing real, exploitable headroom from a genuinely capable judge. The
corrected measurement now says it can: a 36% capture on a real corpus is a concrete, positive signal that a
local LLM judge is not dead on arrival the way the original (wrong) number suggested. This does **not**, on
its own, mean D-2's exclusion should be reversed immediately — one 40-query sample, one corpus, one specific
prompt design, and the known cost of a listwise call per query (the field's own caution about LLM-rerank
latency, cited earlier in this tempdoc's own investigation) are all real reasons to want a larger, more
rigorous measurement before committing design effort. But the honest state is: **the evidence base D-2 rested
on for excluding a stronger judge has changed, and this should be treated as a live, re-open question for a
future pass, not a closed one.** The three open Theorization-pass-2 threads (R1, R4, default-on) already
carried this tempdoc's remaining decisions forward as explicitly undecided; this correction adds "should D-2's
LLM-judge exclusion be revisited" to that same undecided set — it is not something this correction unilaterally
decides.

## Theorization pass 2 — the remaining threads (2026-07-01)

> Purpose: think broadly about what a second critical-analysis pass surfaced (three gaps between the
> design's own framing and what was verified) plus what U1's result changes about the rest of 643's open
> threads. **Nothing here is a chosen design or an implementation** — it is possibility space for a future
> pass to draw on, in the same spirit as the earlier `Theorization — possibility space` section.

### R1. The "cannot do worse than fusion" gap has at least three different resolutions, not one

The design's own rationale (§8-A) claims the arbitration "cannot, by construction, do worse than fusion."
What is actually built is a continuous blend, which cannot offer that as a per-query, hard guarantee — only
a bounded-trust one ("never trusts the judge more than the pre-existing baseline"). Three different ways a
future pass could close this gap, with different tradeoffs:

- **Say the true thing instead of changing the mechanism.** Soften the claim to match what a blend can
  actually promise — an expected-value risk reduction, not a per-query invariant. Cheapest option; costs
  nothing but honesty, but doesn't get any closer to the originally-desired property.
- **Replace the continuous blend with a discrete switch** (fully trust the judge, or fully trust fusion,
  chosen by the confidence signal, never a weighted mix). This delivers something closer to a literal
  per-query floor. The tradeoff: a hard switch can flip discontinuously as the confidence signal crosses its
  threshold, which is a form of instability a smooth blend does not have — swapping one risk (residual
  judge influence when "protecting" fusion) for another (order instability near the threshold boundary).
- **Bound the *damage*, not the *trust*.** Instead of blending scores, cap how far the judge is allowed to
  move a candidate in rank position (e.g., at most K places), regardless of how large its score gap is. This
  is a genuinely different mechanism shape — a distance-capped adjustment rather than a score blend — and
  is the option that could deliver something closest to the originally-imagined guarantee ("this cannot move
  more than K ranks away from where fusion put it"), at the cost of being a new primitive rather than an
  extension of the existing D-004-style blend seam the current design deliberately reused.

None of these is obviously right; they trade off differently against the reuse-the-existing-seam principle
that motivated the current design in the first place.

### R2. What U1's result changes about the unfinished half of E3

The design's decision instrument (E3) asked for two things: an in-bucket rank breakdown (already present in
the eval projection, independent of this tempdoc's later work) and a cost-weight hook (never built). U1 has
now effectively answered the question the cost-weight hook existed to help decide — whether the judge
bucket is worth further attention relative to its neighbors — with a direct negative measurement instead of
a cost-weighted priority score. That reframes the hook's remaining value: it is no longer something *this*
tempdoc's own decision depends on, but it may still be worth building as a *general* capability the next
bucket-prioritization decision (extraction quality, or the candidate-set/leg-miss side) could reuse, since
the underlying problem it names — a triage instrument that counts failures without weighting them by
product cost — is not specific to the judge stage.

A related, cheaper thread: the ad hoc analysis built for the regression-rate measurement and for assembling
real document text for the live probe were both one-off scripts, not committed to the evaluation tooling.
Because they were built to work around the *same* underlying limitation (a widely-used projection field
that is blind to a stage's actual effect when that stage only reorders results without rescoring them),
promoting a `predictedDocIds`-based analysis into reusable tooling could simultaneously close out this
tempdoc's own leftover instrumentation debt *and* the previously-identified, more general instrumentation
gap — one fix serving two open threads instead of two separate ones.

### R3. The perf-skip latency claim may need a different number than "a benchmark"

The design justifies the skip mechanism on two axes — it protects quality *and* it should reduce latency,
since the judge stage is most of a query's cost. The quality axis has real, measured evidence behind it now;
the latency axis does not. But the uncertain part may not be "does skipping save time" — skipping a network
call trivially saves that call's time by construction, and does not need a benchmark to prove. The genuinely
unknown number is *how often* the skip actually fires on realistic traffic, since that determines whether
the trivially-true per-skip saving adds up to anything that matters in aggregate. A firing-rate measurement
across representative queries may be a cheaper, more direct way to close this gap than a full latency
benchmark, and would also make the quality and perf axes comparable on the same underlying signal (how
often the confidence gate actually engages).

### R4. An untested alternative for the judge-ceiling question itself

U1 tested exactly one way of asking a local model to judge relevance: present the whole candidate list at
once and ask for a full re-ordering in a single response. That is also the hardest version of the task for
a model to do reliably — a single-shot listwise judgment is a different (and, per the literature already
surveyed in this tempdoc's own investigation, generally less reliable) task than asking simpler questions
one at a time: score each candidate independently on a fixed scale, or compare two candidates directly
against each other. U1's negative result is evidence against *this specific* way of using a local model as
a judge; it is not evidence against every way a local model could be asked to help. A future pass that
wants to reopen this question at all should test a narrower question first, since it is cheaper to fail
fast on than a full listwise redesign.

### R5. A related tension worth naming, not resolving: does one global on/off default even fit the evidence?

The measured picture is corpus-dependent in shape (a clean win on one real corpus, a thin loss on another),
which sits awkwardly against a design that ships a single global on/off switch. Making the switch itself
corpus-aware would need something to key on at runtime — which runs straight into the same constraint this
tempdoc's own research pass already established for the confidence signal itself: a fitted, per-corpus
calibration does not transfer and is the wrong shape for a cold-start engine with no stable notion of "which
corpus is this." Whether there is a *runtime*, label-free way to approximate "this workload looks like one
where the signal has been reliable so far" — as opposed to a static per-corpus setting — is an open
question this tempdoc surfaces but does not answer.

### R6. Candidate principles this pass surfaces (recorded, not adopted)

- **A claimed guarantee is only as strong as what its acceptance test can actually check.** A hard invariant
  ("cannot regress") and an expected-value risk reduction ("regresses less often, on average") need
  different kinds of evidence to accept — the first needs a proof or an exhaustive check, the second needs a
  statistical measurement with an honest error bar. Naming which kind of claim a design is actually making,
  up front, would have caught R1's gap earlier than a later re-review did.
- **Instrumentation planned in a design is easy to quietly defer as one-off scripts under time pressure,
  without anyone deciding to skip it.** A design phase that budgets for a decision instrument, and an
  implementation phase that answers the same question with throwaway analysis instead, can both look
  complete individually while leaving the actual instrument unbuilt — the same shape as a subagent audit's
  claim resting on an unwritten test, but for measurement tooling rather than test coverage.

### R7. What does not need re-litigating

U1's result does not reopen D-2's exclusions (a stronger/heavier judge model, LLM-as-reranker, learned
fusion, in-domain fine-tuning) — if anything it strengthens the case for leaving them out, since the one
concrete attempt at a stronger judge measured *worse* than doing nothing further. The open threads above are
about the instrumentation and framing *around* the shipped mechanism, not about whether the shipped
mechanism's basic shape (confidence-gated blend, reusing the existing arbitration seam) was the right choice.

## Confidence-building pass 3 (2026-07-01) — de-risking the R1-R4 threads before any design is chosen

> Purpose: reduce genuine uncertainty about the Theorization pass 2 threads (R1-R4) before any of them
> become a chosen design, using reprocessing of data already on disk and one small bounded experiment —
> **no feature implementation happens in this pass.** R5/R6 are open questions, not implementation-adjacent,
> and are not addressed here.

**R1 finding — the arbitration mechanism's actual footprint is narrow.** Re-deriving the exact confidence
gate against the already-archived §9-4 per-query data (both `--top-k 20` runs) shows the mechanism only
ever diverges from the pre-existing baseline behavior on a small minority of queries: the fusion-protect
branch fires on 13/300 (4.3%) of scifact queries and 7/300 (2.3%) of enron-qa queries. Every other query
gets exactly the same behavior it always had. This narrows what a hard-switch alternative (one of R1's three
options) would actually change: it is a small, low-blast-radius population, not a systemic behavior change
— which lowers the risk of trying it, but also means it would not move the aggregate numbers by much either
way. It does not resolve *which* of R1's three options is right, only that the stakes of picking one are
smaller than they first appeared.

**R3 finding — perf-skip's real firing rate, measured for the first time.** The same reprocessing shows the
skip condition would trigger on about 5% of queries on both real corpora (15/300 on scifact, 17/300 on
enron-qa — corrected from an initially-reported 15/300 on both; see the E3 implementation note below for how
the discrepancy was caught). This is the first time this number has existed anywhere — it was previously
asserted, never measured. A firing rate this low means the latency benefit, while real and mechanically
guaranteed once it fires, is modest in aggregate on these two corpora — a one-in-twenty-queries win, not the
dramatic reduction "most of a query's cost" framing might suggest at first read. Whether that is still worth
having depends on how cheap the check itself is (very cheap — it reads signals already computed) rather than
on the size of the win.

**R2a finding — the decision-instrument's remaining integration point still exists exactly as described.**
Reading the current evaluation-tooling code confirms a same-shaped feature (a cross-mode metric sourced from
this same projection file) is already live and working, so the described way to add a cost-weighted metric
is a real, unblocked path, not something that has drifted since it was first proposed.

**R2b finding — promoting one-off analysis scripts into reusable tooling fits existing conventions cleanly.**
The evaluation command-line tool already has several commands shaped exactly like what a durable version of
the regression-rate analysis would need (a data directory, a run directory, a report file), and none of the
existing commands already do this — there is a clear, unclaimed place for it to live.

**R4 finding — a small, bounded live experiment materially changes the picture.** U1 tested exactly one way
of asking the local model to judge relevance (rank a whole candidate list in one call) and found it failed
badly. A tiny follow-up experiment — scoring each candidate independently on a fixed 0-10 scale instead of
asking for one full re-ordering — was run on the same model, same corpus, a handful of real queries. The
result: on the queries where a scorable answer existed in the pool, the correct document was scored highest
(or a very close second) in every case, with a clear separation from the other candidates' scores. This is
a tiny, non-benchmark sample and should not be read as a finished result — but it is a clear, concrete signal
that the earlier catastrophic failure was a property of *asking for one big reordering in a single response*,
not necessarily a property of what the model can distinguish when asked a narrower question. This raises
confidence that R4 is worth a real, larger measurement later rather than a dead end — while also being
clear that this alone does not reopen the decision to keep a stronger judge out of scope, since scoring N
candidates independently is N model calls instead of one, a real cost this small check did not attempt to

> **Correction (2026-07-01, later the same day):** the "earlier catastrophic failure" this paragraph explains
> away as a listwise-formulation problem turned out to be a measurement bug, not a real listwise result at all
> — see `## U1: live judge-ceiling probe result`'s correction. The corrected listwise result is also
> reasonably strong (36% capture), which is actually more consistent with this paragraph's own pointwise
> finding than the original miscalculated contrast was. The comparative point this paragraph makes (a narrower
> per-candidate question may be easier for a small model than a full reordering) may still be worth testing on
> its own merits, but it is no longer motivated by "listwise catastrophically fails, pointwise doesn't" — both
> now show a real, positive signal.
weigh against the tiny benefit U1 already showed was on the table even in a best case.

**What did not change.** Nothing in this pass revisits whether the shipped mechanism's basic shape was the
right choice, or whether the corpora tested are representative enough to generalize from — those remain as
open, unresolved questions regardless of the above.

**Overall confidence rating for the remaining work: 6/10.** The instrumentation gaps (R2a/R2b) are now
low-risk, well-understood, small pieces of work with a clear, unblocked path — confidence there is high.
The measurement gaps (R1/R3) are closed for the two corpora already measured, but generalizing them further
would need the same kind of reprocessing repeated elsewhere, which is a known, bounded cost, not an unknown
one. The open item pulling the rating down from higher is R4: the sanity check is a genuine, encouraging
signal, but it is four data points, not a corpus-scale measurement, and it is the one thread here that
points toward *new* scope (a different judge-prompting shape) rather than tidying up existing scope — that
is a meaningfully different, less certain kind of next step than the other three.

> **Correction (2026-07-01, later the same day):** "the tiny benefit U1 already showed" above is no longer
> accurate — U1's corrected result (see its own section) is a real, positive 36% capture, not a near-zero one.
> The rating and its reasoning are left as written above (the underlying uncertainty — one small sample, one
> corpus, not yet a corpus-scale measurement — has not changed), but the *direction* of the evidence pointing
> at R4 is now more encouraging than this paragraph describes, not less. A future pass reading this rating
> should weight R4 accordingly.

**Recommended effort for whichever direction is chosen next:** the R2a/R2b instrumentation work (a metric
family + a CLI command, following two directly-precedented existing patterns) is mechanical, well-scoped,
low-risk work — a mid-tier model at standard effort is a reasonable fit, since the main risk is following
convention precisely, not judgment calls. Deciding *whether* to chase R1's floor-guarantee gap further, or
running a real corpus-scale version of R4's pointwise experiment and deciding what to do with the result, is
a different kind of task — it involves weighing tradeoffs this pass surfaced but did not resolve (hard
switch vs. blend; N-calls cost vs. quality signal) against the project's own settled constraints (the perf
budget, D-005, the cold-start no-labels reality) — that calls for a stronger model at higher reasoning
effort, since a wrong call there would be a design mistake, not an implementation bug.

## E3 implementation (2026-07-01)

D-2's third and last "IN scope" structural element — the decision instrument — is now built, closing the
one item that remained from the original design after E1/E2/perf-skip.

**Cost-weighted judge-rank-low severity.** The existing rank-distribution breakdown (rank-2 vs rank-3-5 vs
rank-6-10, already present) now has a derived `judge_low_cost_weight` field alongside it — a `[0,1]` weighted
average using a documented default weighting (rank-2 counts as near-free, rank-6-10 as full cost, matching
this tempdoc's own product-tolerance finding), operationalizing "dominant-count ≠ dominant-cost" as one
number instead of leaving it as an unweighted rate. Registered as a metric family alongside the existing
leak-rate family, following the same shape. No new data collection — it's a pure function of numbers already
computed. Live-verified against a real 40-query run (`judge_low_cost_weight: 0.4`, matching the histogram
arithmetic exactly), not just unit-tested.

**A durable judge-arbitration report tool**, replacing the one-off scripts written for the earlier acceptance
test and the confidence-building passes: re-derives the arbitration gate's branch split and the perf-skip
gate's firing rate from a run's already-archived per-query data, and compares two runs' true result order
directly (bypassing the same trec-preference blind spot this tempdoc already found and worked around once).
Verified against the already-known reference numbers from earlier this session and reproduced them exactly on
scifact — but on enron-qa, it caught a real discrepancy in the earlier ad hoc measurement: the perf-skip
firing rate is actually **17/300** (5.7%), not 15/300 as recorded earlier in Confidence-building pass 3 above.
The earlier script had incorrectly skipped the perf-skip check on the same condition that (correctly) short-
circuits the unrelated alpha-branch calculation, even though the two gates read different signals. This is
now corrected above, and is itself a small, concrete demonstration of exactly the risk Theorization pass 2's
R2 named — a one-off script can silently carry a bug that only a second, independent, tested implementation
catches.

No user-visible surface changed (this is evaluation tooling only, consumed by future measurement passes, not
by the running product) — no UI/browser validation applies. Verified via unit tests, a live pipeline run, and
a full build.

## Sibling-thread reconciliation (2026-07-01)

A second, independent implementation thread on this same tempdoc was found — authored earlier and separately
(2026-06-24 through 2026-06-30, before the thread that produced everything from `## Investigation — takeover
pass` onward), unaware of this one and unaware it existed. It reached the same core design (gate the
cross-encoder's reorder on its own confidence, never regress below fusion, ship default-off) independently,
but implemented it differently: a binary trust/don't-trust switch keyed only on the CE's own raw score margin,
versus this thread's continuous blend keyed on both the CE margin and cross-leg agreement. Neither had been
merged or published at the time of comparison, so this is a reconciliation between two unpublished threads,
not a live conflict.

That sibling thread's own closing self-review concluded its runtime mechanism was built ahead of the evidence
that would justify it, and recommended retracting it, keeping only its diagnostic instrument. Weighing the two
threads: this one carries more direct evidence (the §9-4 real-corpus acceptance test, critically re-reviewed
twice; the U1 live-judge measurement; the now-complete E1/E2/E3 scope) and one additional real capability
(perf-skip, unifying the quality lever with a latency lever) that the sibling thread does not have. This
thread is the one carried forward; the sibling's runtime mechanism is not adopted.

**The crux measurement neither thread had run, run now.** Both threads separately named the same missing
number: does the cross-encoder's own confidence signal actually discriminate, at the level of individual
queries, "reordering the fusion result helped the correct answer's rank" from "reordering hurt it" — as
opposed to only checking whether the aggregate score moved. Using already-archived data (the pre-existing
baseline behavior, arbitration disabled), this was computed directly: the raw margin signal alone reaches an
AUC of essentially 0.56 on both scifact and enron-qa — barely better than chance, and a significance test
finds this is not distinguishable from chance at either (p≈0.20-0.26). This thread's actual combined gate
(margin plus leg-agreement, the real condition that decides when to intervene) shows a promising direction on
enron-qa specifically — every query where the gate would protect fusion also turned out to be a query the raw
reorder would have hurt — but on only 3 such queries; scifact's equivalent population (6 queries) does not
even show the favorable direction. **Honest reading: this measurement remains genuinely unresolved, not
merely unmeasured.** Both mechanisms' gates fire rarely enough (roughly 1 in 20-40 queries) that neither of
the two 300-query corpora available currently has the statistical power to confirm or refute the premise
either way. This is itself a useful, decision-relevant fact, not a null result to shrug off — it says a
confident answer to this question needs either a much larger query set or a corpus specifically constructed
to contain more low-confidence-judge cases, not more analysis of the data already on hand.

**What else the sibling thread surfaced, and what was and wasn't carried forward.** It built a richer
diagnostic — classifying *why* a judge-rank-low query happened (a tie in the gold labels, the judge never
seeing the candidate at all, the judge's own preference being overridden, an upstream fusion demotion, or
none of the above) rather than only *how far off* rank-one it landed, which is what this thread's own
cost-weighted severity field measures. The two are complementary, not competing, but the cause breakdown was
not ported here — it adds diagnostic depth without changing any decision already made. It also ran a
three-round investigation into whether the judge's input *window* (not the judge model itself) was silently
excluding the correct answer's text — found apparent evidence of this on a synthetic corpus, then in a third
pass discovered the synthetic corpus's construction made that evidence an artifact (the string it searched
for wasn't actually present in the gold document at all), and retracted the finding. That arc is not repeated
here; its methodological lesson — validate what an eval probe is actually testing before drawing a conclusion
from it — is one this thread's own U1 measurement already followed (using a real corpus with real relevance
judgments, not a synthetic construction), but is worth naming as a real risk in this kind of work generally.

**One concrete, genuinely useful thing it built that this thread did not have: an AI-free realizable-headroom
replay of the actual production judge**, as opposed to this thread's own U1 (which asked whether a *different*,
local LLM judge would do better). Replaying the judge that is actually shipped, over the full candidate pool,
needs no model download and no live LLM backend — and the sibling thread's own measurement of it on scifact
found essentially zero realizable gain, independently corroborating this thread's own U1 conclusion by a
completely different, much cheaper method. Built directly into this thread (`ce_replay_report` /
`jseval ce-replay`, `scripts/jseval/jseval/judge_ceiling.py`), not copied from the sibling worktree, following
this thread's own established module and testing conventions.

**Building it caught a real, previously-unnoticed bug that also affects the earlier U1 result.** The first
live attempt produced an implausible, near-catastrophic number; interrogating it (not just reporting it) found
the cause: `_score_ranking`'s nDCG computation was passed the full corpus's `qrels.json` unfiltered, but on a
capped (`--max-queries`) run that file still holds every corpus query, not just the ones actually judged —
`ir_measures` silently scores every un-judged query as 0 and folds it into the mean. On this run (40 of 300
scifact queries judged), that diluted the result by roughly the ratio of judged to total queries. Confirmed
directly: after filtering the qrels to only the queries actually present before scoring, the same run's
`ce_replay_ndcg` moved from an implausible 0.11 to 0.86 — matching `final_ndcg` (0.86) almost exactly, and
now closely corroborating the sibling thread's own near-zero finding (`capture_fraction` ≈ −0.03 here vs their
0.004). Fixed at the shared root cause (`_score_ranking` itself, so every caller is protected, not just this
one), with a regression test. **This same function is what U1's live-LLM measurement used, on the same kind
of capped run — so U1's originally-reported numbers are also dilution-affected and are being recomputed with
the fix; see the U1 section for the corrected result once it is in.**

## Closure
643's purpose-only stub is now: investigated, theorized, designed, research-validated, **implemented in
full** (E1 confidence signal, E2 confidence-driven blend, perf-skip, all default-off; the earlier static
floor is now the E1/E2 mechanism's own `baseAlpha` fallback, not a separate parallel implementation), and
**critically re-reviewed twice, with all code-level and measurement-methodology findings fixed in-session**
(including the top-2 margin bug caught while writing tests for this final phase, and the F-026 trec-blindness
correction above). §9-4's acceptance test (re-measured at `--top-k 20`, the authoritative numbers) found the
mechanism a clean, one-directional win on scifact and a small, thin net negative on enron-qa — not "never
net-harmful either corpus" as an earlier draft of this paragraph stated before the wider-window correction.
**The live judge-ceiling probe (U1) is now measured** (see `## U1: live judge-ceiling probe result` above,
2026-07-01) — closing the doc's last open measurement gap, though not with the result first reported. A
dilution bug in the shared nDCG scoring function (found and fixed via the sibling-thread ce-replay cross-check,
see `## Sibling-thread reconciliation`) meant the first U1 number was wrong; the corrected result is a real,
**positive** signal (this local model captures 36% of the AI-free ceiling, `capture_fraction=+0.357`,
outperforming the current pipeline on this sample). This does not, by itself, overturn D-2's decision to keep
a stronger/heavier judge model out of scope — one 40-query sample on one corpus is not enough for that — but
it does mean the evidence D-2's exclusion rested on has changed, and whether to revisit that exclusion is now
an open question for a future pass, not something this correction settles either way.
**E3 — the last of D-2's three "IN scope" structural elements — is now built** (see `## E3 implementation`
above, 2026-07-01): a cost-weighted judge-rank-low severity field and a durable, tested judge-arbitration
report tool replacing the earlier one-off analysis scripts. All three of D-1's structural elements (E1, E2,
E3) are now implemented, tested, and live-verified — the design is complete, not just the two most novel
pieces of it.
Default-on remains a deliberate follow-up decision, not part of this closure, as do the three open threads
Theorization pass 2 raised (the floor-guarantee framing gap, the pointwise-judge alternative, and the
corpus-adaptive-default tension) — none of them were part of D-2's decided scope, and Confidence-building
pass 3 explicitly left them for a future, separate decision. Everything D-2 scoped as this tempdoc's own work
is closed.
