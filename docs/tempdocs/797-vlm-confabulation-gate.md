---
title: "VLM confabulation gate — closing the fluent-and-wrong hole in the VDU extraction fallback tier"
type: tempdocs
status: "DRAFT charter — awaiting founder review; no implementation started"
created: 2026-07-29
author: agent
category: extraction / search quality
related:
  - 790-extraction-dropout-fallback   # the measurement this charter is sourced from (§H)
  - 677                                # the abstention cascade this charter says is structurally blind here
  - 786-extraction-quality-scorecard   # the extraction-tax framing
---

# 797 — VLM confabulation gate

## 1. Problem statement

Tempdoc 790's VDU/VLM fallback tier works: on the OHR-Bench PDF-live substrate it recovered
**64 of the 127 shipped-arm extraction dropouts** at the VLM tier, and its recoveries carry a
**median word-overlap of 0.93** against clean ground truth (790 §H.2). It also fires cleanly —
of the 873 documents the shipped arm extracted fine, **zero** end empty and none carries a
dropout marker; combined with 790 §F's 0 fires on 5184 scifact documents, the
zero-false-positive property holds on real bytes (790 §H.2, "Control").

The hole is in the tail. **VDU's p10 word-overlap is 0.00** — roughly **1 in 10 VLM recoveries
is text with no lexical relationship to the page it claims to transcribe** (790 §H.2, per-tier
fidelity). 790 §H.3 makes it concrete: `news/dude_ce4c991a…_p14` was "recovered" with 3861
alnum characters of fluent prose about a 1795 dollar coin, while the page's ground truth is a
Monsanto/Aroclor news story. The abstention gate passed it.

That output is now **indexed as searchable content, indistinguishable from real extraction**.
It is not an empty document, not a dropout, not marked degraded in any way a ranker or a user
can see. Downstream it is fluent, plausible, well-formed text — exactly the shape that
retrieves and reads convincingly. 790's own framing: *"the tier-2 leg works, and its failure
mode is confabulation, not silence."*

Silence was the problem 790 solved. Confabulation is the problem this charter is about, and it
is the worse of the two: an honest hole is legible to every downstream consumer, a confident
fabrication is legible to none.

## 2. Why the current abstention gate is structurally blind to this

The gate is not absent and it is not naive — `VduAbstentionGate` is a calibrated three-stage
cascade (`modules/app-services/src/main/java/io/justsearch/app/services/vdu/VduAbstentionGate.java`).
The blindness is structural, in how the stages are wired:

- **Stage 0** (`inputVerdict`, `VduAbstentionGate.java:151`) is a *pixel* check — it rejects
  pages carrying no textual signal at all. A real, legible page sails through by design.
- **Stage 1** (`outputVerdict`) is a three-band check on the model's **own self-reported
  confidence**: mean logprob, low-confidence-token fraction, finish reason
  (`VduAbstentionGate.java:94-124`). A fluent confabulation is, by construction, a
  *high-confidence* generation — the model is not uncertain, it is wrong.
- **Stage 2** (`agreementVerdict`, the re-sample self-consistency probe) is the one mechanism
  in the cascade that can separate "stable transcription" from "invented text": calibration
  measured legible pages at agreement **1.000** and confabulated ones at **0.047–0.379**
  (`VduAbstentionGate.java:126-134`). But it is **conditional on Stage 1 banding AMBIGUOUS** —
  `VduProcessor.java:299-306` runs the probe only inside `if (stage1Verdict.band() ==
  Band.AMBIGUOUS)`.

**So the check that would catch fluent-wrong output is gated behind a signal that fluent-wrong
output does not trip.** The self-consistency probe never runs on precisely the population it
was designed to separate.

A second, narrower factor is worth verifying before any design: the 677 calibration population
was `golden/synth-scan-v1` (n=30 confabulated, n=16 legible; `VduAbstentionGate.java:31-52`) —
confabulations elicited from *scan-like* inputs, which split into a short/refusal-shaped mode
and a long/confident mode. The OHR-Bench failure is a third shape: a real, content-bearing
page transcribed as fluent prose about something else. Whether the calibrated bands separate
that population at all is an open empirical question, not an assumption this charter makes.

**Corollary that must not be lost:** the 7 `vdu_rejected`-but-non-empty documents in 790 §H.2
have median overlap **0.00** — the gate *did* correctly veto those. The gate is not broken. It
has a specific, characterizable blind spot, and this charter is scoped to that blind spot only.

## 3. The production constraint that shapes every candidate

**Production has no ground truth.** The 0.93/0.00 fidelity numbers exist only because
OHR-Bench ships clean per-page text to compare against. On a user's machine there is no clean
text, no reference transcription, and no oracle. Any gate this charter eventually proposes
**must be reference-free or consistency-based** — it can compare the model against itself,
against another tier, or against priors derivable from the page image, and nothing else.

This rules out, up front, the whole family of "compare to the correct answer" designs, and it
is why the measurement plan (§5) must always report *both* the offline-with-ground-truth
number and the reference-free proxy's agreement with it.

## 4. Candidate mechanisms (with honest costs — none selected)

### (a) Cross-tier consistency where OCR output exists

Where the OCR tier (Tesseract via Tika) produced *any* text for the same page, compare it
against the VLM output — a confabulation should share almost no vocabulary with even a noisy
OCR pass, while a genuine transcription should overlap substantially.

- **Honest cost / limits:** the dropout population is largely defined by OCR producing nothing
  usable, so coverage is the whole question — this can only ever protect the subset where two
  tiers both spoke. 790 §H.2 shows 43 of the 127 dropouts were recovered by OCR and 64 by VDU,
  which suggests the tiers are substantially *disjoint* on exactly the documents at risk.
  Requires quantifying the overlap population before it can be costed at all. Cheap when
  applicable (no extra model call), useless where it is not.

### (b) Un-gate the existing Stage-2 self-consistency probe

The mechanism already exists and is already calibrated (§2). The candidate is not to build a
second decode — it is to change *when* the shipped one runs: on every VLM extraction, or on a
risk-selected subset, rather than only on Stage-1-AMBIGUOUS documents.

- **Honest cost / limits:** a second decode per document on the tier that already dominates
  wall-clock — 790 §H.5 measured VDU drain at **~41 s/document**, 252 documents in ~172
  minutes. Running the probe universally is close to a second VDU pass in the worst case
  (the current probe re-samples one page, so the true multiplier depends on page counts and
  is measurable, not assumable). It is off the ingest critical path (790 §H.5: the corpus was
  searchable at t = 1188 s while VLM recoveries landed afterwards), which softens but does not
  erase the cost. The calibrated agreement floor (0.5) was derived on the synth-scan
  population — it needs re-measurement on the OHR-Bench failure shape before it can be trusted
  at a new operating point. Risk of new false rejections on legitimately variable pages
  (tables, dense figures) is the thing to bound.

### (c) Provenance labelling in the index (mark, rank, filter — do not judge)

Instead of deciding truth, make the tier visible: content recovered by the VLM is marked as
such in the index, so it can be surfaced, down-weighted, or filtered by policy.

- **Status check before anyone builds this:** the substrate substantially exists. Both
  `extraction_method` and `vdu_status` are already indexed `keyword` fields with `docValues`
  and a `filter` role (`SSOT/catalogs/fields.v1.json`), written at index time
  (`IndexingDocumentOps.java:249`), updated to `VDU` on a successful VLM recovery
  (`GrpcIngestService.java:689`), and already read on the search path
  (`GrpcSearchService.java:688`). A tier label is also already derived for preview
  (`PreviewController.computeTextProvenance`, `PreviewController.java:328`). **This candidate
  is a policy-and-exposure question, not a new-field question** — anyone scoping it should
  start from what these fields already carry.
- **Honest cost / limits:** it does not stop a confabulation from being indexed or retrieved;
  it only makes the risk legible and actionable. Cheap and safe, and correspondingly weak on
  its own. Down-weighting VLM-recovered content by policy would also penalize the **~83% of
  recoveries that are useful** (790 §H.2 fidelity table: 92 of 111 at overlap ≥ 0.5) — a
  deliberate precision/recall trade that needs a number attached before it is made.

### (d) Reference-free confabulation heuristics from page-image priors

Score the *plausibility of the output given the image* without a second model call: text
length against page area and ink coverage, structural agreement (does a page with a table
layout yield table-shaped text), token-density priors, presence of the page's own visual
callouts. 790 §H.3's counter-example produced 3861 alnum characters — whether that is
anomalous for its page is exactly the kind of question this family asks.

- **Honest cost / limits:** near-free at runtime, and the weakest signal of the four. Highly
  likely to be corpus-shape dependent, with real false-positive risk on legitimately dense
  pages — and a false positive here throws away a genuine recovery, i.e. re-opens the dropout
  790 just closed. Would need its own calibration population and a pre-registered
  false-positive ceiling.

**Not a candidate: a second judge model.** Named only to record that it was considered and is
out of scope for this charter — it changes the runtime dependency footprint and inherits the
same fluency-bias failure it is meant to catch.

## 5. Measurement plan sketch

The substrate to evaluate against already exists and is committed: the OHR-Bench PDF-live
corpus recipe, per-file manifest, and reconstruction README under
`scripts/jseval/666-corpora/ohr-bench-pdf-live/` (built by
`scripts/search/fetch-ohrbench-pdf-corpus.py`; no corpus content committed). It is the right
substrate for three reasons 790 established: the 127-document dropout set is characterized
per-document, the clean ground truth exists for offline scoring, and the two control arms
reproduced 786 to the fourth decimal on a different backend (790 §H.4) — so a new arm is
comparable rather than a harness artifact.

Sketch, not a plan:

1. **Fix the evaluation population.** The 64 VDU-tier recoveries in the dropout set, plus the
   healthy-document control (the 873 documents the shipped arm extracted fine — a
   confabulation gate must not fire there either; 790's zero-false-positive property is the
   bar to preserve).
2. **Label ground truth once, offline.** Word-overlap against the clean text, which already
   separates the population into useful (≥ 0.5) / partial / noise (< 0.1) exactly as 790 §H.2
   does. This is the *scoring* oracle only — never an input to the gate.
3. **Score each candidate as a reference-free proxy** against that labelling, and report the
   confusion matrix, not an accuracy: recoveries wrongly dropped is the expensive error, since
   each one is a dropout re-opened.
4. **Pre-register the falsifier before running.** E.g. a candidate is worth pursuing only if it
   catches a stated fraction of the overlap-< 0.1 population at a stated ceiling on
   false rejections among the overlap-≥ 0.5 population. Numbers to be set by the founder, not
   derived after seeing results.
5. **Report end-to-end retrieval impact too.** 790 §H.4's 962-query three-arm setup is the
   existing harness; a gate that improves fidelity but costs nDCG has not obviously won.

Known measurement caveats inherited from 790 §H.7: no multi-seed repeats, no bootstrap CIs on
the pdf-live arm, no domain-stratified breakdown. Any campaign here should decide up front
whether it needs them.

## 6. Open questions for the founder

1. **Threshold policy — drop, label, or quarantine?** Three materially different products:
   *drop* (suspected confabulation is not indexed; the document reverts to an honest hole),
   *label* (indexed but marked, per §4c, with a ranking/filtering policy on top), or
   *quarantine* (indexed but excluded from default retrieval, surfaced only on explicit
   request). This choice drives everything else and is not an engineering call.
2. **What is the acceptable false-rejection rate?** Every recovery a gate drops re-opens a
   dropout 790 closed. Is a gate that catches 8 of 10 confabulations at the cost of 1 in 20
   good recoveries a win, or not?
3. **Is per-document extraction-confidence something the product should expose at all** — to
   the UI, to the MCP tool surface, or to neither?
4. **Does this rank against the other post-790 work**, or is a ~1-in-10 fluent-wrong rate an
   accepted risk for now? Note the exposure is bounded by how often the tier fires at all —
   127 of 1000 documents on this corpus, which is a scanned-PDF benchmark and not a claim
   about a user's document mix.

## 7. What this is not

A charter. No mechanism is selected, no threshold is proposed, no code is written, and no
number here is new — every measured figure is sourced from 790 §H, and every claim about the
gate's structure is sourced from the files cited inline. The §4 candidates are deliberately
unfiltered by preference; three of the four have a cost that could disqualify them outright,
and §4a may turn out to have almost no coverage. Design begins after the §6 questions are
answered.
