---
title: "Judge-stage ranking quality — the answer survives into the candidate set and reaches the cross-encoder, but the reranker ranks it below the cutoff (FP2 Missed-Top-Ranked). Tempdoc 636's cross-corpus recall-profile measured this as the DOMINANT failure bucket (JUDGE_RANK_LOW) across the engine's eval set, yet no lever owns it. The symmetric *judge-side* counterpart to 639's *candidate-set* side — and unlike 639, the measurement already exists (636's §5 judge-ceiling probe + the recall-profile)."
type: tempdocs
status: proposed — STUB / record-only (deferred; the next runtime lever 636's instrument points at — measurement done, design deferred)
created: 2026-06-24
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
