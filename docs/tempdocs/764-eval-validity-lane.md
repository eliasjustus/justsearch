---
title: "eval validity lane: does the instrument measure what we think — question-level discrimination (the email null), judge error rate (local-model re-judge), and the noise floor / power at n=60"
type: tempdocs
status: "chartered (2026-07-21). Awaiting lane orchestrator pickup."
created: 2026-07-21
author: agent (Fable orchestration), founder-directed analysis program (umbrella: 762)
category: eval-analysis / eval-design
related:
  - 762-agent-utility-analysis-program   # umbrella: priors §P, data §D, constraints §C — READ FIRST
  - 763-retrieval-attribution-lane       # feeds B5 judge-error candidates to this lane
  - 707-pillar1-inband-utility-corpus    # how the questions were fabricated
---

> Charter. Execute after reading 762 §P/§D/§C. This lane's product is a verdict
> on the instrument itself: which questions are broken, how often the judge is
> wrong, and how big a true effect must be before n=60 can see it. Every
> "free number" (accuracy recovered by fixing the eval rather than the engine)
> is a §L lever row for 762.

# 764 — eval validity lane

## §A. Questions

1. **Do the email questions discriminate?** The no-tool arm already scores
   0.533 at email-1k (grep suffices), and the null is replicated 5× (762 §P.2).
   Hypotheses to separate: (a) questions answerable from filename/single-doc
   surface (no retrieval needed); (b) gold answers over-represented in few docs
   (grep-friendly); (c) with-tool arm actively harmed (distraction/latency);
   (d) judge leniency differs by arm. If the email strata cannot discriminate,
   the next campaign should replace or re-fabricate them — a campaign-design
   lever, not an engine problem.
2. **How often is the judge wrong, and in which direction, per arm?** Judge =
   scoring model over (question, gold, answer). Method: re-judge a stratified
   sample with the LOCAL model (`ai_activate`, ~11s load, zero API cost;
   dev-stack takeover authorized), plus a small-API-budget sonnet tiebreak on
   local-vs-original disagreements (762 §C.2). Include 763's handed-over B5
   candidates. Deliverable: estimated FP/FN rate with CIs + the corrected ITT
   table if judge errors were fixed — is any stratum's conclusion sensitive?
3. **What is the noise floor at n=60?** Test-retest across the replications we
   own: Step-2, v4 (tmp\confirm), v5 (tmp\confirm2), Phase-2 probe — same
   strata, same design. Quantify: per-stratum Δ variability (legal-1k showed
   +0.255 vs +0.167 across campaigns — 762 §P.5), McNemar power curves at
   n=60 for true effects of +0.10/+0.20/+0.30, and the n required to certify
   the 762 §T target magnitudes. This number feeds the next campaign's budget
   directly (n per stratum × cost per cell from lane 765).
4. **Ceiling/floor audit**: per-qid difficulty spread — how many questions did
   NO arm in ANY campaign ever answer (dead weight diluting every delta), and
   how many did every arm answer (ceiling)? A question set where 40% of items
   are unanswerable-by-anyone compresses all deltas toward zero.

## §B. Method notes

- Per-qid outcome matrix: build (qid × campaign × arm → correct/incorrect/
  exhausted) from the per-run records (`out/utility-comparison.v1.json` per
  stratum; v4 equivalents under `tmp\confirm`). Judge scores and error_class
  are per-cell fields (762 §D). This matrix is the lane's core artifact —
  commit it (CSV/JSON) with the tempdoc.
- Question audit: read the actual question + gold from
  `707-corpora/<corpus>/<size>-verbose/fabricated-queries.json`; classify each
  email qid against hypotheses (a)/(b) by checking gold-doc dispersion in the
  corpus dir (how many docs contain the answer string — a grep is legitimate
  here, it mirrors the A-arm's capability).
- Judge re-run: the judging prompt/scorer lives in the jseval Inspect task
  (`jseval/agent_utility_inspect.py` area — verify at pickup); replicate it
  against the local model via the dev-stack agent/chat API, holding the rubric
  fixed. Do NOT redesign the rubric in this lane — measure the current one.
- Power analysis: exact McNemar (the design's test); simulate from observed
  discordant-pair rates, not textbook defaults.

## §C. Deliverables & acceptance

1. **Email verdict**: per-hypothesis evidence + a recommendation (keep / re-
   fabricate / replace email strata) with the expected effect on headline
   numbers. Acceptance: every email qid classified, with dispersion numbers.
2. **Judge error report**: FP/FN rates per arm with sample sizes; sensitivity
   re-computation of the v5 ITT table; list of definitively mis-judged cells.
3. **Power table**: minimum detectable effect at n=60; required n for the §T
   targets; recommended n + strata for the next campaign.
4. **Dead/ceiling question list** with per-qid evidence.
5. **§L rows for 762**: each finding as a lever ("re-fabricate email questions
   → removes a −0.117 drag on the pooled story"; "n=120 needed to certify
   +0.15 at 80% power"; etc.).
6. Implementation log here; out-of-scope finds → observation shards.

## §D. Constraints & practicalities

- Inherit 762 §C. Local model via `ai_activate` is the default judge-audit
  instrument; small API spend only for tiebreaks/spot-checks.
- step2-powered worktree is read-only source data; jseval env per 762 §D.
- Inspect logs are large — use `inspect_ai.log.read_eval_log` / jseval helpers,
  never raw-Read.
- Statistical work is main-loop judgment; transcript/qid bulk reads are worker
  tasks (pin models per CLAUDE.md routing).
