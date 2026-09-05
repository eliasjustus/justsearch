---
title: "eval validity lane: does the instrument measure what we think — question-level discrimination (the email null), judge error rate (local-model re-judge), and the noise floor / power at n=60"
type: tempdocs
status: "EXECUTED (2026-07-21, same day — orchestrator-run: opus worker for §A.1/§A.3/§A.4 + mechanism probe worker + inline judge adjudication). Judge audit closed at zero cost; see §E Results."
created: 2026-07-21
author: "agent (Fable orchestration), founder-directed analysis program (umbrella: 762)"
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

## §E. Results (2026-07-21)

Artifacts: `tmp/analysis-624/764/` (per-qid outcome matrix across 14 runs,
email qid classification, power tables, dead/ceiling lists, mechanism
findings). Every ITT number reproduced the umbrella §A table exactly.

**Structural finding that reframed the lane:** the production scorer is
`substring_scorer` (gold string ⊆ answer), NOT an LLM judge. §A.2 therefore
collapsed from a re-judging campaign to adjudicating 4 FN suspects — done
inline, all four resolved as CORRECT rejections: 3 were number-only answers
whose numeric suffix is derivable from the designer's name (the
`corpus_generate.py:320-323` naming leak) with zero tool calls ever touching
the hop-2 doc (verified across all 3 epochs each — the agents fabricated
file citations around pattern-guesses); 1 was a give-up with coincidental
word hits. **Effective judge error ≈ 0/1,269 completed cells; the v5 ITT
table needs no correction.** The scorer's full-string requirement is
currently the ONLY thing neutralizing the naming leak — the leak dies in the
767 rebuild.

**Email verdict: the null is a vocabulary confound, not a domain finding.**
The corpora are identical by construction (same generator/seed/gold
templates; only the 960 real distractors differ). The fabricated questions'
architecture/geography vocabulary floods legal text (`spa` = 22% of legal
docs) and is near-absent from Enron — so baseline grep drowns in legal
(43/60 baseline cells open neither gold doc) and cruises in email. Once both
gold docs are found, both corpora solve at 74–100% — finding, not
extraction, is the bottleneck; the with-tool uplift's mechanism is "the
engine cuts through distractor flood grep can't." Recommendation upgraded
from replace-or-demote to **rebuild with domain-matched (camouflaged)
vocabulary** — tempdoc 767; demoting would bank an artifact as a domain
finding.

> **[CORRECTED 2026-07-21 — tempdoc 767 §H.0]** The paragraph above mixes a
> finding that stands with an explanation that does not. Read it as follows.
>
> **REFUTED — the vocabulary confound.** The heading "the null is a vocabulary
> confound, not a domain finding", the claim that the questions' vocabulary
> "floods legal text and is near-absent from Enron", and the `spa` = 22% of
> legal docs figure are all withdrawn. The source measurement
> (`tmp/analysis-624/764/mechanism/grep_flood.csv`) has two independent
> defects: it is a **substring** match, not a token match (`spa` was matching
> `space`/`newspaper`/`disparate`/`aerospace` — the token `\bspa\b` occurs in
> **0 of 199** real CLERC opinions), and it compares legal **per-file** against
> Enron **per-line**, as the CSV's own header admits
> (`legal_10k_staged_files_matching(of 10001)` vs
> `enron_raw_lines_matching(of 150000 sampled)`). Unit-normalized,
> token-boundary, per-document: `spa` is 0.25% legal vs 0.26% enron — **0.96×,
> not 247×**; across all 20 payload terms the operational grep asymmetry is
> **4.39×**, and 0/20 queries in either domain return >1% of the host corpus.
>
> **STANDS — the behavioural asymmetry.** "43/60 baseline cells open neither
> gold doc" vs 6/60 in email, "cruises in email", accuracy **0.033 legal vs
> 0.533 email**, and "once both gold docs are found, both corpora solve at
> 74–100% — finding, not extraction, is the bottleneck" are **unaffected**.
> These come from `findings.csv`, produced by two preserved, reproducible
> scripts (`measure_perq_gold.py`, `measure_reads.py`) over real eval logs.
> The refuted flood numbers have **no** preserved script — they were ad-hoc
> shell. The phenomenon is real; the explanation is not. The behaviour is
> therefore **currently unexplained**, and tempdoc 769's M1 measurement is the
> leading candidate to explain it.
>
> **REFUTED — the recommendation's justification.** "Demoting would bank an
> artifact as a domain finding" is no longer supported: the behaviour was
> never shown to be an artifact. The 767 rebuild proceeds on two
> **independently verified** payload defects instead of the vocabulary thesis —
> payload enumerability (one literal `_FILLER` phrase selects **280/280** gold
> docs) and a live entity-uid→gold-value coupling (`Tasdell272` → `…0272`,
> exploited in §E above). 767's own framing has since moved from camouflage to
> **payload integrity**: §H.3 measured host-derived camouflage making the
> cross-domain asymmetry *worse* (4.39× → 10.5×).

**Power/noise:** exact McNemar at n=60 reliably sees Δ≥0.20 (power 0.89 at
discordant rate 0.25) but is badly underpowered for Δ≤0.15 (n@80%: 92 for
Δ=0.15, 208 for Δ=0.10; 32 for Δ=0.25). Test-retest: legal-1k Δ sd 0.024
(real signal), email sign-unstable inside noise. §P.5's "+0.255 vs +0.167"
was paired-vs-ITT, not test-retest — never pool the two estimands.

**Dead/ceiling:** legal-10k has 9/20 dead qids (never answered by any arm in
any campaign — the haiku hop-2 floor), halving its visible delta (0.094
full vs 0.172 live, 1.8×); legal-1k 4/20; email ~0; ceiling = 0 everywhere.
Superseded as a standalone lever by the 767 rebuild (which replaces the
question set wholesale) — recorded here as the dilution mechanism.
