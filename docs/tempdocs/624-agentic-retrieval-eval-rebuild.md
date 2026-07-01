---
title: "Agentic-utility eval rebuild: the agent-utility number as a cohort-identified, condition-paired comparison record — a projection over agent-eval runs cohort-identical on every axis except `condition`, conforming to the canonical-record + governed-projection seam (553/623/622); the comparison arm already exists (346) but lacks run identity, seeds, judge, and projection discipline, which is why '92% / 62%' is an identity-less fork"
type: tempdocs
status: implemented (machinery) — Track A retraction + the utility-comparison record + LLM-judge + condition B + run-governance/calibration shipped (see §As-built #1–4); the published number + business re-rooting remain gated on a credible record (§Open uncertainties). The §Problem…§Next-step header below is the original 2026-06-21 stub and predates the build. 2026-07-01: gating condition satisfied (agent/MCP wedge committed — see 654 direction note); hardening assignment briefed (§Assignment 2026-07-01). 2026-07-02: methodology plan delivered (§Methodology plan) — resolves U0 (does the honest realistic-arm number help the wedge) as the plan's first-class design driver, not a footnote; battlefield revised to real corpus scale + OCR-only content + multilingual as load-bearing stress tests, not deferred extras; a contingency-claim design (token/cost-at-scale, stratified capability-coverage, honest null) is specified in advance. 2026-07-02 (same day, second pass): §Design theorization settles the long-term design for the plan's four remaining-work items — descriptor-collision (construction-time exclusion + combinatorial descriptor space, not just detection), the OCR-only corpus member (a new `corpus_generate.py` axis renderer, reusing the existing certify/fidelity gates unchanged), judge human-calibration (recognized as a third instance of a recognized-but-unbuilt human-calibration-set pattern alongside register Q-007/Q-009 — named, not shared-built yet), and stratified capability-coverage (a governed projection of `utility-comparison.v1`, conforming to the `jseval/projections/` registry's principle without joining its registry). A targeted §External research pass (same day, third pass) sharpened judge-calibration (dual-rater agreement against a human-disagreement baseline, not a bare judge-vs-one-human kappa — citing arXiv 2510.09738) and OCR-content construction (a lightweight from-scratch scan-rendering step, not an external VLM/diffusion pipeline — citing arXiv 2605.08838/2602.21824), and deliberately did not re-research the already-recently-checked design areas. 2026-07-02 (same day, fourth pass): §Critical-analysis pass found and fixed four real gaps (missing M-to-T cross-references; §M.7a's stratified-coverage design being an undisciplined cherry-picking vector, closed by §M.8 item 7; the judge-calibration design left in an addendum instead of §M.4 itself; the invisible non-run engineering cost in §M.9) — and named one real, unresolved trade-off it did NOT fix: the credibility bar has grown across every pass and no leaner interim floor has been defined as an explicit alternative to the full bar, a founder cost/rigor call, not a design gap. 2026-07-02 (same day, fifth pass): a live §Confidence pass (throwaway experiments + code reads, no feature implementation) found §T.2's core premise (a plain OCR-rendered scan structurally blocks a file-reading agent) was WRONG — Claude Code's `Read` tool reads plain scans via multimodal vision; a genuinely degraded scan does block it, corrected in place as a calibration problem, not a renderer. Also found and fixed a new baseline-fairness gap (tool-restriction bypass via a spawned subagent; the harness's "isolated" claim doesn't cover the operator's own global Claude Code config) and confirmed §T.1's combinatorial-descriptor-expansion is near-mandatory (43-94% distractor-descriptor duplication measured at realistic scale), not conditional. Judge-model availability and real ingest-extraction behavior remain unverified (no dev-stack access this session). Still no eval run authorized — founder decisions (§M.9) remain open.
created: 2026-06-21
updated: 2026-07-02
author: agent analysis (research-channel design theorization), filed by agent — STUB
category: search-quality / agent-eval / mcp / business-research-channel
principle: "a published measurement must trace to a cohort-identified reproducible run (623/625) — the agent-utility number is the extreme fork: hand-quoted across docs with no run identity and two unrelated metrics fused. Design resolution: agent-utility is its own canonical record (a condition-paired comparison), NOT a metric-family sibling of the 623 release — it conforms to the same canonical-record+governed-projection seam (553/622/623) and reuses its substrate (manifest identity, envelope, hardware projection, cited-baseline, coverage), but pairs on the `condition` axis (with/without tool) the single-cohort release object cannot model. Recurring sub-shape named: axis-relative cohort identity (a measurement is a projection over the equivalence class holding every axis fixed except its declared range-axis — `dataset` for 623, `time` for rank_diff, `condition` here)."
related:
  - ../business/research-channel/plan.md            # origin — decision 1 + deferred work
  - 366-agent-search-interface                       # source of the 92% (50q Haiku tool-design eval) + the "62%" query-fraction
  - 346-agent-retrieval-eval                          # the agent-retrieval eval seed
  - 623-reproducible-benchmark-release               # the release object this eval should produce into
  - 635-contamination-resistant-eval-corpus           # corpus machinery/generator this plan's battlefield revision builds on
  - 664-eval-corpus-integrity-and-verified-identity   # the descriptor-collision blocker this plan promotes to a v1 prerequisite
  - 655-mcp-conformance-and-capability-policy         # the owner of any tool-surface lever this eval's failure analysis motivates (§M.7a)
  - 636-retrieval-buried-signal-long-documents        # origin of the jseval/projections/ registry pattern (stratified_metrics, staged_recall_accounting) §T.4 conforms to
  - 639-candidate-set-integrity-ann-recall-and-result-dedup  # adjacent unbuilt stub sharing §T.1's near-duplicate-detection mechanism (different consumer — production candidates, not corpus generation)
---

> NOTE: Noncanonical working tempdoc. Originated as a **STUB** capturing an idea identified in
> `docs/business/research-channel/plan.md` (decision 1 + deferred work). **Investigated 2026-06-21**
> (§Investigation below) — primary-source verification against `main` + external-landscape research.
> The §Problem…§Next-step above is the original stub; the §Investigation that follows verifies its
> claims, records the critical analysis, and lists open questions for the (still-deferred) plan.
> **No implementation plan or design work yet** (per assignment).

# 624 — Agentic-retrieval eval rebuild

## Problem (one paragraph)

The strategy/business docs lead the developer/MCP wedge with a **"92% accuracy / 62% cheaper"
agent-utility benchmark**. Traced to source (tempdoc 366): "92%" is a **50-query Haiku-class eval of
MCP tool-interface design** (`366:382,529`); the "62%" at `366:185` is a **query-fraction**, not a cost
reduction; the only cost delta is **answer-first vs. search-explore agent behaviour** on the *same*
backend (`366:171-174`) — an internal tool-design measurement, **not a JustSearch-vs-anything
comparison**. As an external claim it would not survive the adversarial research audience. It is
quarantined in `research-channel/plan.md` until rebuilt. Agentic-retrieval evaluation is a genuinely
hot, under-standardised 2026 topic — so the rebuild is worthwhile, but only as a real measurement.

## What is missing (general shape, not implementation)

- A **defined comparison arm** (against *what* — a no-retrieval baseline? a naive keyword backend? a
  cloud RAG? state what the cost/accuracy is measured *against*).
- **Larger n** well beyond 50, and **separated** accuracy and cost claims (they answer different
  questions and currently get fused).
- Production into the **623 benchmark-release** object so the number is cohort-identified and
  reproducible, not hand-quoted.

## Gating / sequencing

This is **audience-specific** — valuable only if the developer/MCP-first spearhead is actually chosen
(`research-channel/plan.md` decision 1 / `go-to-market/`). Do not rebuild on spec; rebuild when the
agent/MCP wedge is committed.

## Next step (not done here)

Investigate the existing `agent_retrieval_eval.py` surface + tempdoc 366/346, then write the plan.

---

# Investigation (2026-06-21)

> Primary-source verification pass against `main` + an external-landscape research pass (2025–2026
> agentic-eval literature). Goal per assignment: fully understand the idea/motivation/solution, verify
> every load-bearing claim against code/docs, think critically (question assumptions, suggest
> alternatives). **No implementation plan or design work yet.** Every internal claim cites `file:line`;
> external claims cite URLs.

## A. Verdict in one paragraph

The **problem is real and the headline diagnosis is correct**: the externally-asserted "92% accuracy /
62% cheaper" is a conflation of two unrelated numbers from an *internal tool-interface* eval, it would
not survive an adversarial research audience, and it had already begun leaking into external-facing
materials. **But the stub's own framing of "what is missing" is itself partly
wrong: the comparison arm it says is missing was *already built* in tempdoc 346** (`jseval agent-eval`,
conditions A/B/C × model tiers — code lives at `scripts/jseval/jseval/agent_retrieval_eval.py`), and its
final GPU run produced a *real* with/without-JustSearch delta (**~75 % accuracy, −34 % cost, −39 %
tokens vs file-tools-only**) that is the honest — and much weaker, heavily-caveated — number the "92 %"
should never have been. So the rebuild is not "build the missing arm"; it is **"the arm exists at
toy-grade rigor (n=20, one model, one seed, no run-identity, contaminated public corpus, substring
scoring) — harden it to a number a hostile reviewer can reproduce, and stop asserting the conflated one
*now* (quarantine ≠ rebuild)."** The external landscape confirms this is a genuinely under-standardized
2026 topic, so the rebuild is worthwhile — but as a token-efficiency-led, multi-seed, judge-scored,
identity-bearing measurement, not an accuracy headline over public news. Details below.

## B. Claims that hold up (verified against code/docs)

1. **"92 %" is a tool-interface-design number, not a with/without comparison.** It is the Phase-4/5
   accuracy of the 50-query Haiku eval whose *independent variable was the MCP tool-schema design*
   (lean vs bloated schemas, answer-first vs search-explore), all on the *same* JustSearch backend —
   `366:380-382` (`| Accuracy | 92% | 91.8% |`), `366:469-499` (the schema-bloat A/B that *defines* what
   the eval varied). It contains **no no-JustSearch arm at all.** Verified.

2. **"62 %" is a query-fraction, not a cost reduction.** `366:184-186`: *"The 38% adoption is not a
   failure — it reflects that 62% of queries genuinely benefit from multi-turn exploration."* It is
   `100 % − 38 %` answer-first-adoption. Verified. The only genuine cost delta in 366 is **answer-first
   $0.023 vs search-explore $0.069** (`366:173-174`) — an internal comparison of two *agent strategies
   on the same backend*, ≈ −67 %, also not a JustSearch-vs-anything number. So "62 % cheaper" maps to
   **none** of the real numbers; it is the query-fraction misread as a cost.

3. **The conflated claim is widely forked AND already drifting.** It appears across several internal
   strategy / positioning / funding drafts (in the private business sidecar). Two independent
   confirmations that the fork is *actively rotting*, exactly as tempdoc 625 predicts: (a)
   `docs/reference/mcp-production-server.md:151` states **"94 % on 50-query eval"** — a *different* number
   from the 92 % everywhere else; (b) a live reconciliation `TODO(founder)` already flags that the numbers
   disagree. This is the projection-vs-fork drift class, caught in the act.
   *(Grant-application specifics generalized for the public release — go-public scrub.)*

4. **The substrate the stub wants to "produce into" is real and was built for siblings.** Tempdoc 623's
   release object shipped and is metric-family-parametric by construction: `release.py:20-24` — *"The
   metrics map is family-keyed, NOT nDCG-hard-wired … an extraction-quality sibling reuses this exact
   object with {WER, CER, route_accuracy, …}."* `release.v1.json:50-52` already carries a first-class
   `coverage.does_not_measure` negative-space field. So agent-utility is a plausible **third metric
   family** beside retrieval-quality and the named extraction-quality sibling (623 §F / E4). Verified —
   but see C-2 for why it is a *cousin*, not a drop-in sibling.

5. **The external topic is genuinely hot and under-standardized in 2026.** The named MCP/agent benchmark
   suite measures tool *invocation/orchestration*, not marginal retrieval *utility*: MCP-Bench
   ([2508.20453](https://arxiv.org/abs/2508.20453)), MCP-Universe ([2508.14704](https://arxiv.org/abs/2508.14704)),
   τ/τ²-bench ([tau2-bench](https://github.com/sierra-research/tau2-bench)), BFCL V4
   ([gorilla blog](https://gorilla.cs.berkeley.edu/blogs/15_bfcl_v4_web_search.html)). The tempdoc-346
   gap ("benchmarks test invocation, not utility") **is still substantially real** — confirming 624's
   premise that the angle is worth a real measurement.

## C. Critical findings (the part that changes the plan)

### C-1 — The comparison arm is **already built**; reframe the work from "build" to "harden" *(most important)*

The stub's "What is missing → a defined comparison arm" reads as if no with/without eval exists. It does:
`jseval agent-eval --condition A|B|C --model …` (`cli.py:2068-2110`) with **A = file tools only, B = file
+ JustSearch, C = JustSearch only** (`agent_retrieval_eval.py:886-1078`). Its final GPU run (tempdoc
`346:657-666`) is a *clean* with/without delta: **A 63.2 % / $0.125 vs C 75.0 % / $0.082 → +11.8 pp acc,
−34 % cost, −31 % turns, −39 % tokens.** That — not "92 %/62 %" — is the honest agent-utility result that
already exists. → **The rebuild's real job is rigor, not construction.** The stub's three "missing"
bullets are better stated as five *deficiencies of the existing run*: (i) n=20, (ii) one model (haiku),
(iii) single seed/run, (iv) no cohort identity / standalone JSON, (v) public-news corpus +
substring scoring. (The stub's `related` already lists 346 as "the seed," so the author knew of it — but
the §Problem/§Missing text doesn't integrate that it already answers the "comparison arm" ask.)

### C-2 — "Produce into the 623 release" is **not composition** — agent-utility needs its own cohort identity, on different axes

The agent-eval writes **standalone JSON** (`agent-eval-{condition}-{model}.json`, `cli.py:2107`) with
**no `manifest_hash`, no `config_cohort_key`, no manifest at all** (`agent_retrieval_eval.py` imports no
`manifest`/`release` module). 623's release is a projection *over `jseval run` manifests*; agent-eval
produces none. Worse, its cohort axes are **disjoint from 623's**: a credible agent-utility cohort must
pin {**agent model id**, **Claude-Code CLI version**, **MCP tool-surface hash**, **condition**, corpus
identity, **judge model+version**, **prompt template**, **seed set**} — *none* of which is in 623's
`config_cohort_key` (which hashes the *search-pipeline* config: `release.py:120-130`). So agent-utility is
a **cousin record with its own identity definition**, not a drop-in sibling reusing `config_cohort_key`.
The stub's "production into 623 so it is cohort-identified" silently assumes the identity surface
transfers; it does not. This is the one place "compose what exists" breaks — and it breaks bigger than
623's own C-1 break did.

### C-3 — Non-determinism is the dominant cost driver, and the stub omits seeds entirely

The stub says "larger n well beyond 50" but never mentions **repeats/seeds** — which, for an *agent*
ablation, matter as much as n. 366's own data shows run-to-run swings of **~6 pp accuracy** (`366:531`,
86 % vs 92 % "within run-to-run variance") and wild turn-count variance at temperature 0 (`366:511-514`,
Q10 = 34 turns vs 4 turns, same answer). External literature converges on **≥5 seeds (≥10 a practical
floor)**, **McNemar's paired test** on discordant pairs, and **bootstrap/Wilson CIs** — noting single-run
pass@1 varies **2.2–6.0 pp** and that **sub-8–10 pp deltas at n=100 are within noise**
([playbook 2605.00428](https://arxiv.org/html/2605.00428v1),
[randomness 2602.16666](https://arxiv.org/pdf/2602.16666)). Consequence: a credible cell count is
*conditions × models × corpora × **seeds***, so "n well beyond 50" understates the real cost by the seed
multiplier. The favorable +11.8 pp from a single n=20 run (346) would **not** clear a McNemar bar.

### C-4 — The favorable numbers are the **substitution** arm (C), which models a scenario nobody runs

346's −34 %/+12 pp is **Condition C = JustSearch only, file tools disabled** (`agent_retrieval_eval.py`
condition C disables `Read,Grep,Glob`). The **realistic** scenario — an agent that *already has* file
tools and *also* gets JustSearch — is **Condition B**, and 346 found B is **more expensive** because the
agent uses *both* tool sets and doesn't prefer `retrieve_context` (`346:453-463`: *"condition B is MORE
expensive, not less … the agent treats retrieve_context as an additional tool, not a replacement"*). So
an honest "adding JustSearch makes your agent cheaper" claim must rest on **B**, where the evidence is
*negative*; the favorable **C** answers "if you *replace* your file tools," which no real deployment does.
This is a trap even for the 346 numbers, and the stub doesn't flag which condition is the honest headline.

### C-5 — Corpus contamination cuts against the credibility goal, and JustSearch's real moat is unused

MultiHop-RAG is public news from **Sept–Dec 2023** — inside every current model's training cutoff. 346
flags this (`346:231-246`) but ships the accuracy headline anyway. The validity problem is specific:
contamination **inflates the without-tool (A) arm** (the model answers from memory, not files), which
*understates* JustSearch's benefit — so the true delta may be larger, but the *measurement is not
trustworthy* either way, and a reviewer will reject it. External practice (RARE, CMU 2025
[pdf](https://www.cs.cmu.edu/~sherryw/assets/pubs/2025-rare.pdf); AntiLeak-Bench, ACL 2025) mandates
post-cutoff / private / perturbed corpora + abstention canaries. **JustSearch's structural advantage —
its real use case is *private local files*, contamination-free by construction — is exactly what the
public-corpus eval throws away.** The strongest design move is to measure on a post-cutoff or
private/synthetic corpus (or at minimum lean on the null-query abstention canaries 346 already has).

### C-6 — Scoring (substring containment) is below the 2026 bar

`_score_answer` (`agent_retrieval_eval.py:82-94`) is exact substring match (+ abstention phrases for
null). For free-form agent answers, 2025–2026 practice is **LLM-as-judge** for correctness/faithfulness
(SIGIR-2025 LiveRAG used Claude-3.5-Sonnet as judge, [2507.04942](https://arxiv.org/pdf/2507.04942)),
with the judge from a **different model family** than the agent, **dual-order** to cancel position bias
(>10 pp swing, [2602.02219](https://arxiv.org/pdf/2602.02219)), and a **human-κ spot-check**. A hybrid
(cheap EM as high-precision auto-pass → judge the misses) is the credible floor. Substring scoring on
short answers will be the first thing a hostile reviewer attacks.

### C-7 — Quarantine is *more urgent* than rebuild, and the stub fuses the two

The stub gates the whole thing on "rebuild only when the agent/MCP wedge is committed." But the two
actions are separable and have *different urgencies*: **(a) stop asserting the conflated number** is
urgent and unconditional — it is already forked across internal positioning/funding drafts (B-3, in the
private business sidecar) — whereas **(b) build the replacement measurement** can stay gated on the wedge decision. The
research-channel plan already says "quarantine" (`plan.md:205`), but the number still sits in those
internal drafts. → The plan phase should split these: a near-term *correction/retraction* pass over the forked docs
(replace "92 %/62 %" with either nothing or the honest, caveated 346 token-efficiency line) that does
**not** wait on the wedge; and the gated rebuild proper.

## D. Alternative designs worth weighing in the plan (not chosen here)

- **D-1 — Lead with token-efficiency, not accuracy.** The retrieval-tool win is most robust on
  *tokens-to-answer* (346: −39 % tokens; external analog ~98 % token reduction,
  [token-budget](https://blakecrosley.com/blog/agent-code-search-token-budget)). Crucially, token
  efficiency is **contamination-robust** — memorization inflates the without-tool *accuracy* but not how
  many tokens it burned reading files. So "JustSearch cuts an agent's tokens-to-answer by ~40 % at equal
  or better accuracy" is a *more defensible* headline than any accuracy number, and survives C-5. Caveat:
  the prompt-caching confound must be controlled (report cached vs fresh tokens separately, or disable
  caching for the measurement run; the two arms have different prompt structure → different cache-hit
  rates → naive `tokens×price` is invalid).

- **D-2 — Make Phase 1 (retrieval-only) the primary publishable artifact; treat Phase 2 (agent) as a
  gated, higher-cost adjunct.** 346 Phase 1 (evidence recall@K / MRR over MultiHop-RAG qrels, no LLM, $0,
  deterministic — `agent_retrieval_eval.py:206-392`) is **contamination-proof** and slots into 623's
  release as *just another retrieval-quality metric family* with almost no new identity machinery — the
  cheap, safe path. The expensive, non-reproducible, contamination-fragile Phase-2 agent comparison is
  the part that needs the heavy rigor (C-2…C-6). Staging the work this way de-risks it.

- **D-3 — Report an accuracy×cost Pareto frontier / cost-per-correct-answer, not two separated scalars.**
  The stub's "separate the accuracy and cost claims" is right that they answer different questions, but
  the credible 2026 framing folds them: **cost-per-correct-answer** + a frontier plot, with per-query
  *distributions* (median + p95), since agent token cost is heavy-tailed
  ([HAL 2510.11977](https://arxiv.org/pdf/2510.11977)).

- **D-4 — Position against named prior art rather than claiming first-mover.** The closest published
  analogs to cite (so the artifact reads as a contribution, not a boast): **FRAMES** (clean retrieval
  delta 0.40→0.66, [2409.12941](https://arxiv.org/abs/2409.12941)), **BFCL-V4 web-search** (with/without
  tool precedent), and **Sourcegraph CodeScaleBench** (the *direct structural twin* — grep/read baseline
  vs MCP search tools — whose own published caveat is that the swap-the-backend benchmark "in practice
  was not enough on its own": [blog](https://sourcegraph.com/blog/codescalebench-testing-coding-agents-on-large-codebases-and-multi-repo-software-engineering-tasks)).
  The genuine, defensible novelty is narrow and worth stating precisely: *marginal retrieval-tool utility
  over a user's **private/local** files, MCP-exposed, vs generic file tools* — the private-corpus angle
  is the contamination moat no public-news benchmark can claim.

## E. Open questions for the plan phase (do not resolve here)

1. **Sibling or cousin? — RESOLVED in §Design (D.2): cousin.** Agent-utility is a *separate canonical
   record* conforming to the same seam, reusing 623's substrate but NOT cramming into the single-cohort
   release object (which `compose()`-refuses cross-cohort sets and models scalars, not paired deltas). It
   adds an `agent_cohort_key` *beside* `config_cohort_key`, not a generalization of it. (The remaining
   open questions 2–6 are genuine plan-phase decisions; this one the design settles.)
2. **Which condition is the published headline** — B (realistic, currently unfavorable) or C (favorable,
   unrealistic)? Or report both transparently and let token-efficiency (D-1) carry the claim? (C-4.)
3. **Which corpus** — keep MultiHop-RAG as a *labelled, contamination-flagged illustrative* set, and add
   a post-cutoff / private / synthetic *primary* corpus? (C-5.) Does a private corpus undermine external
   *reproducibility* (the stranger can't fetch it)? Tension with 623 decision-2's reproducibility bar.
4. **Seed/repeat budget** — how many seeds × models × conditions × corpora is affordable, and what is the
   minimum that clears a McNemar + CI bar? (C-3.) This sets the real $ cost and is the gating economic
   question.
5. **Judge** — adopt LLM-judge (which family, to avoid self-preference vs the agent-under-test) or stay
   substring + publish the judge-vs-substring agreement as a caveat? (C-6.)
6. **Sequencing vs 623/625** — the near-term *correction* pass (C-7) is independent of the wedge; confirm
   it can proceed regardless. The rebuild proper stays gated on `research-channel/plan.md` decision 1.

## F. Scope boundaries (unchanged, reconfirmed)

- Agent-utility eval rebuild → **this tempdoc (624)**.
- The benchmark-release *object* it produces into → **623** (built; metric-family-parametric).
- Generalized "every asserted number traces to a run" enforcement → **625** (record-only). The drift
  evidence found here (B-3: 92 %→94 % already, the founder TODO) is a **second concrete instance** of the
  fork biting — relevant to 625's stated trigger (*"build only when the register fork bites a second
  time"*), though that is 625's call, not 624's.

## Next step → see §Design below

Investigation complete. The §Design section that follows theorizes the correct **long-term design**
(general, not implementation-level). The **build** stays gated on the agent/MCP wedge decision
(`research-channel/plan.md` decision 1); the §C-7 correction pass over the forked docs is the one
separable, near-term, ungated action.

---

# Design (2026-06-21)

> Long-term design theorization (general, not implementation-level). Goal per assignment: the *correct*
> structure, scoped to exactly what 624's problem requires — no short-term fixes, no structure for cases
> the problem does not yet include. The §Investigation above is the evidence base; this section is the
> structural conclusion. **Still no build** (gated on the wedge); this records the design so it is ready
> when the gate opens.

## D.0 — The problem, restated structurally

624's problem is **not** "we lack an agent-utility measurement" — one exists (346: `jseval agent-eval`,
conditions A/B/C × models, final delta ~75 %/−34 %/−39 % tokens). The problem is that **every
agent-utility number JustSearch asserts is an identity-less fork**: the published "92 %/62 %" traces to
no run at all (it was lifted from a different eval, §B-1/B-2), and even the honest 346 number is a
**standalone JSON file with no `manifest_hash`, no cohort identity, single seed, substring-scored, over a
contaminated public corpus** (`agent_retrieval_eval.py` writes `agent-eval-{condition}-{model}.json`,
imports no `manifest`/`release` module). So the structural defect is the **absence of a
cohort-identified, reproducible, projection-rooted record for the agent-utility metric family** — the
exact defect 623 fixed for the *retrieval-quality* family. 624 is the **agent-utility instance of the
same seam**, and the design is: *what is that record, and how does it relate to 623's?*

## D.1 — What already exists to conform to and reuse (do NOT rebuild)

- **The seam.** *A single canonical measurement record → governed/derived projections → a
  fork-prevention discipline* — the kernel first proven for search-execution (tempdoc 553/549), named
  `canonical-authority-and-projection` for telemetry (622), and made the *measurement-domain* instance by
  623. 623 §Design-reach already states the long-term home for a *new* measurement consumer: *"a new
  member of the existing surface-register family … not a parallel mechanism."* That is the instruction
  this design follows.
- **623's substrate (reuse wholesale).** `manifest.py` (run identity + cohort hash + embedded envelope);
  `calibrate.py` (per-cohort mean±σ envelope); `env_fingerprint.py` (the *stable* hardware subset — GPU
  name / VRAM / driver / ORT version — projected out of the volatile snapshot, `release.v1.json:32-39`);
  the `repro.v1.json` source+sha256 digest seam; and the release object's **honesty fields** —
  `external_baselines` (cited constants, never re-run, `self_reproduced:false`), `coverage.does_not_measure`
  negative-space, `confidence_tier` (`release.py:18-31`, `release.v1.json:50-52,55-153`).
- **A paired-diff projection already exists.** `projections/rank_diff.py` + `compare_runs.per_query_diff`
  compare two runs that are **cohort-identical, differing only on the *time* axis** (regression vs the
  latest prior in-cohort run, `rank_diff.py:1-31,66-75`). This is the same machinery a condition-paired
  comparison needs — paired per-query diff over runs that hold all-but-one axis fixed.
- **The MCP tool surface is a real contract.** `McpToolSurface.java` (source of truth per
  `docs/reference/mcp-production-server.md:15`) is the tool set the eval exercises — so the
  agent-identity input "which tools were available" has a concrete, hashable referent.

## D.2 — The one real decision: separate canonical record, NOT a metric-family sibling of the 623 release

623 made its release object **metric-family parametric** and explicitly invited siblings
(`release.py:20-24` — the extraction-quality sibling reuses the object with `{WER, CER, …}`). The
tempting move is therefore "agent-utility is just a third metric family in the release." **That is
wrong, and 623's own logic says so.** Three structural mismatches make the release object the wrong
home:

1. **Single-cohort vs. paired.** A release is *one* `config_cohort_key` ranged over corpora;
   `compose()` **refuses** any cross-cohort set (`release.py:278`). Agent-utility is inherently a
   **pair** of arms (with-tool / without-tool) that must be compared. Worse, the without-tool arm (A)
   does not exercise the JustSearch search pipeline at all, so it has **no meaningful
   `config_cohort_key`** — the release's cohort model literally does not type-check arm A.
2. **Scalar-per-corpus vs. delta+CI+distribution.** `release.measured` is `{corpus: {metrics:
   scalar-map}}` (`release.v1.json:155-198`). Agent-utility's headline is a **paired delta with a
   confidence interval** (Δaccuracy + McNemar CI) plus **per-arm cost/token distributions** (median +
   p95, heavy-tailed). Cramming a delta-shape into a scalar-scorecard either distorts the
   retrieval-quality schema or forks a polymorphic branch inside one file.
3. **Different reason to change** (the decisive AHA test 623 §C-4 *itself* invoked to split ablation
   rows from headline rows). A retrieval-quality release changes when the **search pipeline** changes.
   Agent-utility changes when the search pipeline **or** the **agent model** **or** the **Claude-Code
   CLI** **or** the **MCP tool surface** **or** the **judge** changes. Unifying two records with
   different change-drivers is precisely the over-DRY the projection-vs-fork rule warns against.

→ **Conclusion: conform to the *seam*, not the *object*.** Agent-utility is a **separate canonical
record** that *reuses 623's substrate modules* (identity, envelope, hardware projection, cited-baseline,
coverage) but is its own composed object — a *new member of the measurement-record family*, exactly the
move 623 §Design-reach sanctioned. This is the opposite of a "parallel mechanism": it reuses every
shared primitive and only diverges where the value-shape genuinely diverges (paired, not single-cohort).

## D.3 — The design: the agent-utility comparison record

A **utility-comparison record** is *a projection over a set of cohort-identified agent-eval runs that
are identical on every identity axis except `condition`* — the structural twin of 623 (which projects
over runs identical on every axis except `dataset`). Four pieces:

**(a) Give every agent-eval run a manifest — with an *agent* cohort identity.** Today there is none.
Each `(corpus, agent-model, condition, seed)` cell becomes a manifest-bearing run. Its cohort identity
(`agent_cohort_key`) pins the axes the search-only `config_cohort_key` omits:

  - the underlying **search `config_cohort_key`** (reuse 623's function verbatim — the JustSearch backend
    *is* part of the identity for the with-tool arm; null/`n/a` for arm A),
  - **agent model id** (haiku/sonnet/opus + version),
  - **Claude-Code CLI version**,
  - **MCP tool-surface hash** (over the `McpToolSurface` contract),
  - **judge identity** (judge model + version + prompt — see (c)),
  - **prompt-template hash**, **corpus identity** (source + sha256, reuse `repro.v1.json` seam),
  - **decoding config** (temperature, max_tokens) and **eval_limits** (per-query token/step/cost budget),
  - and the **`condition`** + **seed** as the two *range* axes (not folded into the key — they are what
    the projection ranges over and collapses).

This is the §C-2 gap closed: a second, agent-specific cohort key sitting *beside* `config_cohort_key`.
**Precision from the confidence pass (R1/R2):** "reuse the manifest substrate" means reuse the *hashing
helpers* (`_sha256_canonical`, `_git_sha_full`, the volatile/cohort split) and build a **parallel
agent-manifest builder** — *not* call `build_manifest`, which is search-backend-coupled (its identity
inputs are live `/api/*` snapshots + index fingerprints that don't exist for an agent run, and *cannot*
exist for arm A). And the search `config_cohort_key` **co-varies with `condition`** (real for C/B, null
for A), so it is **recorded on the with-tool arm but excluded from the *pairing* key** — else A and C
would never pair. The pairing key is `agent_cohort_key` minus `{condition, search-config-component}`.
This field set is **not invented here** — it maps onto the emerging 2025–26 community-consensus *mandatory*
agentic-eval identity set (HAL provenance: tool/MCP version + judge + harness + cost/traces;
"Evaluation Cards" agentic-minimal: `temperature`/`max_tokens` + `harness`/`eval_plan`/`eval_limits` —
§D.5). The with/without-tool design makes pinning the **MCP tool-surface** and **judge version**
load-bearing specifically, not optional.

**(b) Pair on `condition`; aggregate over `seed`.** The record's domain is the equivalence class holding
`agent_cohort_key` fixed and ranging over `{condition, seed, corpus, model}`. The projection:
  - **collapses `seed`** into a per-cell estimate + its envelope (extend the 400/`calibrate.py`
    ±2σ concept from "reruns of one config" to "seeds of one cell" — same machinery, the variance is just
    larger, §C-3);
  - **pairs `condition`** (A vs C, and the honest B) per `(corpus, model)` into the headline **delta with
    a confidence interval** (the credibility floor the research audience requires), reusing the
    `compare_runs.compare` machinery — which is **metric-parametric** (`compare_runs.py:14,21,34`) and
    already emits paired delta + bootstrap CI + Cohen's d_z + p-value, so it applies directly to the
    continuous cost/token deltas. **Precision (R3):** the `rank_diff` *wrapper* is not reused (it
    discovers a prior run by identical `manifest_hash`; agent-utility pairs by `condition`, a different
    discovery), and the binary `correct` metric needs **McNemar added** as the apt paired test — an
    addition to the comparator, not a rewrite of it;
  - reports **cost/tokens as per-arm distributions** (median + p95) with the **prompt-caching confound
    controlled** (cached vs fresh tokens separate, or caching disabled for the measurement run — §C-6/D-1),
    since the durable, contamination-robust claim is token-efficiency (D-1), not accuracy.

**(c) Carry honesty as *fields*, not prose** — the conflated claim failed precisely because it could not
express its own limits. The record reuses 623's honesty fields and adds the agent-eval-specific ones:
  - `external_baselines` as **cited constants** (FRAMES 0.40→0.66, BFCL-V4 with/without, Sourcegraph
    CodeScaleBench — §D-4) — `self_reproduced:false`, pinned by source+version, never re-run;
  - `coverage.does_not_measure` — states the **substitution-vs-addition** caveat (the favorable number is
    the C arm, §C-4) and the corpus's **contamination class** as a first-class label
    (`public-pre-cutoff` / `post-cutoff` / `private-synthetic`, §C-5) rather than a buried footnote;
  - `confidence_tier` + the seed count + CI width, so a reader sees the statistical power on the page.

**(d) Re-root the asserted numbers as projections of it.** The business/positioning/funding numbers
("92 %/62 %" and the drifted "94 %") become **derived views of the latest green utility-comparison
record**, never hand-typed — the identical move 623 made for the retrieval-quality fork. This is what
structurally retires the §B-3 drift (92 %→94 %, the founder's reconcile-TODO): there is one source of
agent-utility truth, and every doc number is downstream of it.

## D.4 — Scope judgment (why not more, why not less)

**In scope (structure the problem genuinely requires):** run identity for agent-eval (the core missing
piece — without it any number is still a fork); the condition-pairing + seed-aggregation projection (the
claim *is* a comparison, and the research audience *is* the most adversarial — a single-run scalar would
not survive, which is the whole point); the honesty fields; and the projection-rooting of the asserted
numbers. These are not gold-plating — each maps to a specific way the current number fails the audience.

**Out of scope (structure the problem does not yet include):**
- A **build-time enforcement gate** that fails CI when *any* asserted number lacks a cohort trace — that
  is tempdoc **625**'s generalized enforcement, deliberately deferred until the fork bites a *second*
  time (the §B-3 drift is arguably that second bite, but the call is 625's, not 624's).
- An **in-app model-card / nutrition-label** rendering of agent-utility — that is 623's E1 (a different
  *consumer* of the record), not the record itself.
- A **hosted leaderboard**, **multi-model-tier sweeps** beyond the tier actually published, or a
  **generalized "axis-parametric cohort key" primitive** (see §Design-reach — recognized, not built).
- The **statistical methods** themselves (McNemar, bootstrap) are *consumer* implementation detail, not
  new structural authority; the design only requires the record to *carry* the fields they populate
  (CI, seed-set, per-arm distributions).

**Gating (unchanged):** the whole build remains gated on the MCP-wedge decision. This section records the
target structure; it does not authorize the build. The ungated, near-term action is still only the §C-7
correction pass.

## D.5 — External-standards conformance (2026 research pass)

> A focused second research pass asked one design-relevant question: my whole thesis is *conform to
> existing canonical-record shapes, don't fork* — does that extend to an **external** community standard
> for recording an eval result? Verdict: **there is no container standard to conform to, but there is a
> field-set consensus to meet and a cheap export projection to emit.** None of this redesigns the record;
> it adds two overlays that are *themselves projections of it* — recursively on-seam.

- **No ratified result-artifact standard exists; the space is fragmented.** The two gravity wells are
  **UK AISI Inspect AI `EvalLog`** (most schema-formal — JSON-Schema-backed via `inspect log schema`,
  agent-capable, the rising default among agent/safety labs:
  [eval-logs](https://inspect.aisi.org.uk/eval-logs.html)) and **EleutherAI lm-evaluation-harness**
  (`results.json` + `samples.jsonl`; widest adoption; serializes commit hash + task version + seed +
  per-task stderr). HELM is internal-only; **OpenAI Evals is being deprecated** (read-only 2026-10-31) —
  do *not* target it. → **The internal-seam architecture (D.2) stands unchanged: there is no external
  object to conform to.**
- **Move 1 — emit an Inspect-`EvalLog`-compatible export *as a projection*.** This is the on-thesis
  refinement: an Inspect-format export is *literally another governed projection of the canonical record*
  (the same way the register headline and ratchet floor are projections in 623). It buys citability /
  third-party ingestibility — the entire point of the research channel — at projection cost, with **no
  change to the canonical record**. (lm-eval's `results.json`+`samples.jsonl` pair is a secondary export
  target if reviewers expect it.)
- **Move 2 — treat the community *field set* as a coverage checklist** (not a container). 2025–26
  consensus on the *mandatory* agentic-eval identity fields — **HAL** (Holistic Agent Leaderboard) logs
  tool/MCP version + judge + harness version + cost + full traces by default
  ([2510.11977](https://arxiv.org/pdf/2510.11977)); **"Evaluation Cards"** sets an agentic minimal of
  `temperature`/`max_tokens` + `harness`/`eval_plan`/`eval_limits`
  ([2606.09809](https://arxiv.org/html/2606.09809v1)). The `agent_cohort_key` (D.3a) already covers this;
  the checklist just makes the coverage auditable.
- **External corroboration of the whole 624/625 thesis.** That same Evaluation-Cards study found
  **96.5 % of reported (model, benchmark, metric) results lack ≥1 minimal reproducibility field**
  ([2606.09809](https://arxiv.org/html/2606.09809v1)). The "asserted numbers are identity-less forks"
  problem 624 opened with is **not a JustSearch quirk — it is the field-wide default**, which both raises
  the stakes (the audience has seen the failure mode) and makes *meeting* the floor a genuine
  differentiator. Direct ammunition for 625's principle.
- **Genre still unstandardized — new adjacent prior art to cite.** No named standard protocol for
  "retrieval-tool marginal utility over local files" has landed (open space, novelty intact), but the
  pass surfaced closer analogs than §D-4 had: **PHMForge** (2026 — runs the *exact* MCP-tools-vs-text-RAG
  ablation, [2604.01532](https://arxiv.org/html/2604.01532)), **A-RAG** (ablates *removing* retrieval
  tools, [2602.03442](https://arxiv.org/html/2602.03442v1)), and the **memory-as-tools 2026 line**
  (MemoryAgentBench; LongVidSearch — converging on "agents that *call* retrieval beat agents *fed*
  pre-fetched context," which is 624's thesis). Fold these into D-4's positioning.

## Design reach — the recurring shape

**This design is an instance of a seam the system already names — conform, don't fork.** The
utility-comparison record is a `canonicalRecord`; the business/positioning/funding numbers are its
`projections`; re-rooting them removes the fork. This is the **agent-utility instance of
`canonical-authority-and-projection`** (553 SearchTrace / 559 evidence / 622 telemetry / 623 retrieval
quality — and the current engine-reliability cycle's 626 reconciler / 627 recovery-contract / 628
dispatcher). The codebase is *actively converging* on *single canonical authority + governed consumers +
fork-prevention*; 624 is its **agent-measurement** instance. Nothing new at the seam level.

**The reach extends past this repo — the principle is field-wide.** The 2026 finding that **96.5 % of
published (model, benchmark, metric) results lack ≥1 minimal reproducibility field**
([2606.09809](https://arxiv.org/html/2606.09809v1), §D.5) is the same *asserted-number-as-fork* failure
the seam prevents, observed across the entire ML-evaluation literature — not a JustSearch-local defect.
That is external corroboration that **625's generalized principle (every asserted measurement traces to a
cohort-identified run) is a real invariant of measurement systems, not a codebase quirk** — and a reason
the eventual research artifact, by *meeting* the floor the field overwhelmingly misses, is itself the
differentiator (the trust signal the channel is built on, `research-channel/plan.md` decision 5).

**But the design also sharpens a *sub-shape* inside that seam, worth naming plainly:**

> **Axis-relative cohort identity.** *A measurement is a projection over the equivalence class that holds
> every identity axis fixed except one **declared range-axis**; the choice of range-axis is what defines
> the measurement's kind.* The cohort key is therefore **parametric in which axis is excluded**.

Where it already applies in the tree (these are *existing partial instances*, each currently
hand-rolling its own pairing key):
- **623 release** — range-axis = `dataset`; `config_cohort_key` = "manifest minus the dataset family"
  (`release.py:120-130`). *(scorecard-over-corpora)*
- **`rank_diff` projection** — range-axis = `time`; pairing key = "same `manifest_hash`, earlier
  timestamp" (`rank_diff.py:66-75`). *(regression-over-time)*
- **624 utility-comparison** — range-axis = `condition`; pairing key = "same `agent_cohort_key`, other
  arm". *(delta-with/without-tool)*
- **Latent future instances:** cross-**hardware** comparison (623 deferred it as "separate releases" —
  that *is* the hardware-axis instance) and cross-**model-tier** comparison.

**The latent generalization (named, deliberately NOT built):** there is no single "cohort key parametric
in the excluded axis" primitive — `release.py` and `rank_diff.py` each reimplement the
"hold-all-but-one-axis-fixed" logic ad hoc, and 624 would be a third reimplementation. By the rule of
three, a *third* hand-rolled instance is the natural trigger to extract a shared
`cohort_key(manifest, exclude_axis=…)` primitive. **But not now:** 624's problem needs exactly *one*
range-axis (`condition`); building the axis-parametric primitive today would be structure for the
hardware/model-tier cases the present problem does not include — the same "recognize the principle,
don't build the abstraction" discipline 625 applies to the enforcement gate. **Existing state w.r.t. the
principle:** the two prior instances don't *violate* it (each is a correct single-axis projection); they
merely **don't yet share** the primitive — which is fork-of-mechanism risk, not fork-of-authority risk,
and is appropriately left until the third instance forces it. Recorded here; the build trigger is the
hardware-axis or model-tier comparison actually being needed, not 624's launch.

## Next step (still deferred — unchanged boundary)

Design recorded. The **build** is gated on the agent/MCP wedge decision (`research-channel/plan.md`
decision 1); when it opens, resolve the §E open questions against this design and write the
implementation plan. The one ungated, near-term action remains the §C-7 correction pass over the forked
docs (retract/replace "92 %/62 %" and reconcile the drifted "94 %").

---

# Confidence pass (2026-06-21) — de-risking the remaining work

> Read-only investigation pass (no feature work) to convert the design's load-bearing *reuse* and
> *feasibility* assumptions into verified facts before any future build. Mirrors 623's W1–W5 closure.
> Every row cites `file:line` or a command output.

| # | Assumption | Outcome | Evidence |
|---|---|---|---|
| **R1** | Agent-eval runs reuse the `manifest.py` substrate | **PARTIAL — reuse helpers, build a parallel builder (design text sharpened).** `build_manifest` identity is built from live `/api/*` snapshots (`manifest.py:_STATE_ENDPOINTS:85-92`) + index fingerprints (`_COMMIT_METADATA_IDENTITY_FIELDS:72-81`) — none exist for an agent run, *cannot* for arm A. Reusable: `_sha256_canonical:95`, `_git_sha_full:103`, the volatile/cohort split (`:51-63`). → "reuse the hashing helpers + parallel agent-manifest," not "call `build_manifest`." D.3a corrected. | `manifest.py:51-92,95-112` |
| **R2** | `agent_cohort_key` = "all axes except `condition`" is well-defined | **RESOLVED — real precision fix.** The search-config component **co-varies with `condition`** (real for C/B, null for A). If left in the pairing key, A and C never pair. Fix: record it on the with-tool arm, **exclude it (with `condition`) from the pairing key**. Pairing key = `agent_cohort_key` − `{condition, search-config-component}`. D.3a corrected. | derived from `release.py:config_cohort_key:120-130` + R1 |
| **R3** | `compare_runs`/`rank_diff` reusable for condition-pairing | **MOSTLY GREEN — reuse comparator, add one test.** `compare_runs.compare` is **metric-parametric** (`:14,21,34`) and already emits paired delta + bootstrap CI + Cohen's d_z + p-value over any `{qid:{metric:val}}` — applies directly to continuous cost/token deltas. The `rank_diff` *wrapper* is NOT reused (pairs by identical `manifest_hash`; we pair by `condition`). Binary `correct` needs **McNemar added**, not a rewrite. D.3b corrected. | `compare_runs.py:14-70`; `rank_diff.py:66-75` |
| **R4** | "Cousin not sibling" forced; 623 landed | **VERIFIED (both).** `compose()` raises `ComposeError` unless all runs share one `config_cohort_key` and each has an embedded `manifest` (`release.py:282-305`) → single-cohort by construction, cannot hold a paired A/C delta. 623 machinery is on `main` (`git log`: `ec1aa52eb feat(623)…`) and **green** (`pytest tests/test_release.py` → 17 passed). | `release.py:282-305`; git log; pytest |
| **R5** | The `jseval agent-eval` harness still runs on current `main` | **VERIFIED — CLI contract holds.** Live CLI is **v2.1.183**; every flag the harness builds (`--strict-mcp-config`, `--mcp-config`, `--permission-mode`, `--output-format stream-json`, `--disallowedTools`, `--max-budget-usd`, `--add-dir`, `--resume`) is still present in `claude --help`. The argv (`agent_retrieval_eval.py:932-952`) uses only current flags. (End-to-end *execution* still needs auth + dev stack + corpus — not run here; Tier-4 smoke not required, R5 settled statically.) | `claude --version/--help`; `agent_retrieval_eval.py:932-952` |
| **R6** | A credible matrix is affordable | **QUANTIFIED.** Committed haiku runs: A $0.137/q, C $0.1335/q (`agent-eval-*-haiku.json`, n=20). Per-query basis ≈ $0.10–0.14 (haiku), est. ~$0.45 (sonnet), ~$1.80 (opus). **Envelopes:** haiku-only floor (50q × 2 cond × 5 seeds = 500 runs) ≈ **$70**; haiku-only fuller (100q × 3 cond × 5 seeds) ≈ **$200**; full 3-tier (50q × 3 cond × 5 seeds) ≈ **$1.8k**; 3-tier fuller (100q) ≈ **$3.6k** — ×N for multiple corpora; + cheap LLM-judge (~$0.01–0.02/answer). **Strongly favors the staged D-2 path** (cheap Phase-1 recall + a haiku-only Phase-2 floor first; multi-tier sweep separately gated). | committed result JSONs; Claude pricing |
| **R7** | The prompt-caching confound is controllable | **GREEN (measurable).** The harness already parses `cache_creation_input_tokens` vs `cache_read_input_tokens` separately (`agent_retrieval_eval.py:1024-1025,1034-1035`); `cache_creation` ≈ unique new content (346's stated metric). No `--no-cache` flag exists, but **separate measurement suffices** for the D-1 token-efficiency headline (report `cache_creation` as the durable unique-content metric). | `agent_retrieval_eval.py:1024-1035`; `claude --help` (no cache toggle) |
| **R8** | `McpToolSurface` exposes a hashable contract | **GREEN-ish (small new surface).** The surface is a deterministic, enumerable "five eval-informed tools" set (`McpToolSurface.java:31-40`); a hash over the `tools/list` response is a stable identity input. No ready-made `surface_version` constant → computing the hash is a small addition, not a blocker. (Aside: line 35 hard-codes "94% accuracy, tempdoc 366" — the §B-3 drift reached production Java.) | `McpToolSurface.java:31-40,35` |

## Net surprises caught (changed the design text)

1. **R1 — "reuse `manifest.py`" was overstated.** It is helper-reuse + a *parallel* builder; `build_manifest` is search-backend-coupled and undefined for arm A. (D.3a corrected.)
2. **R2 — the pairing key was under-specified and would have failed.** The search-config component co-varies with `condition` and must be excluded from the pairing key, or A/C never pair. This is the kind of silent join-bug a build would have hit. (D.3a corrected.)
3. **R3 — the comparator reuse was imprecise.** `compare_runs.compare` (metric-parametric) is reused; the `rank_diff` wrapper is not; McNemar is an addition for the binary metric. (D.3b corrected.)
4. **R6 — the cost was unquantified; now bounded.** ~$70 (haiku floor) → ~$3.6k (full 3-tier) — which independently validates staging (D-2) as the right first move.

Everything else (R4/R5/R7/R8) confirmed-and-manageable. No finding invalidates the design; three sharpen it, one (R6) quantifies the gating economics. The committed n=20 artifacts also show a **weaker** delta (+5 pp, ~equal cost) than 346's GPU-run prose (+11.8 pp, −34 %) — direct evidence for the single-seed-variance concern (C-3) and another reason the rebuild must carry seeds + CIs.

## Confidence rating — remaining work: **7 / 10**

- **Mechanics of the design (substrate reuse, identity, pairing, comparator): ~8.** All four reuse claims
  were exercised against code this session; the substrate exists, is green, and is reusable with the three
  named corrections. The one genuinely-new surface (the agent-manifest builder + `agent_cohort_key`) is
  small and well-specified after R1/R2.
- **Held to 7 by three non-mechanical realities** (all already named as plan-phase open questions, none a
  code-risk): (1) **corpus validity (E-3/C-5)** — a corpus that is *both* contamination-resistant *and*
  redistributable-for-reproduction may not exist, forcing a synthetic/compromise that is itself a design
  decision; (2) **judge reliability (E-5/C-6)** — LLM-judge adds a real confound whose calibration is an
  open research area; (3) **cost/seed economics (R6)** — a *full* multi-tier matrix is ~$2–4k, a user
  budget call, not an engineering one.
- **Not a code risk:** the build is wedge-gated, and the favorable framing (token-efficiency over accuracy,
  staged Phase-1-first) de-risks the parts that *are* uncertain. The residual is methodological and
  economic, not structural.

**Reading:** the *engineering* is well-understood and low-surprise after this pass (the substrate is real,
green, and reusable; the harness still runs; the costs are bounded). The remaining uncertainty is
deliberately the part 624 left to the plan phase — corpus, judge, and spend — which are decisions, not
unknowns about the code.

---

# As-built (2026-06-21) — implemented in worktree `624-agent-utility-eval` (not merged)

Implemented per the approved plan, in branch `worktree-624-agent-utility-eval`. The user lifted the gate
and delegated the Track-B decisions with "prefer the full design"; decisions taken: **corpus =
MultiHop-RAG labelled `contamination_class: public-pre-cutoff`** (a contamination-flagged *illustrative*
set — the post-cutoff/private *primary* corpus stays a recorded follow-up; it needs its own construction
+ budget); **judge = hybrid substring-EM + optional LLM-judge** with the judge identity recorded in the
cohort key (`judge_identity(kind=…)`); **spend = the haiku-only floor cap** (no multi-tier sweep).

## Track A — correction pass (done)
Removed the conflated "92%/62%" (and drifted "94%") from every assertion site — across the internal
strategy / positioning / external-facing drafts (in the private business sidecar), plus the public
`McpToolSurface.java:35` and `mcp-production-server.md:151` — replacing each with the qualitative
"reproducible eval in development" line. Left untouched: the legitimate 366 schema-bloat A/B finding
(`mcp-production-server.md:106`, `search-quality-register.md:489` — relative 92→71, correctly
attributed). Grep proof: zero remaining utility-claim assertion sites.

## Track B — the agent-utility comparison record (done; structurally tested)
New/changed under `scripts/jseval/`:
- **`jseval/agent_manifest.py`** — the parallel agent-manifest builder (reuses `manifest._sha256_canonical`
  / `_git_sha_full`, NOT `compute_manifest` — R1), `agent_cohort_key`, `pairing_key`,
  `mcp_tool_surface_hash`, `judge_identity`.
- **`jseval/compare_runs.py`** — added `mcnemar()` (exact-binomial / chi²-continuity) for the binary
  accuracy delta; the metric-parametric `compare()` is reused unchanged for the continuous cost/token
  deltas (R3).
- **`jseval/utility_comparison.py`** — the `compose_utility` composer (sibling to `release.py`; refuses
  mixed harness cohort + mixed with-tool search config), emitting `utility-comparison.v1`: cohort identity,
  per-`(corpus,model)` accuracy delta + McNemar p + seed envelope, per-arm cost/token **distributions
  (median+p95)**, `coverage` (substitution-vs-addition caveat + `contamination_class`), `CITED_BASELINES`
  (FRAMES/BFCL-V4/CodeScaleBench/PHMForge/A-RAG, `self_reproduced:false`), `confidence_tier`+`seed_count`.
- **`utility-comparison.v1.schema.json`**, **`jseval/inspect_export.py`** (Inspect-EvalLog projection, D.5),
  **`jseval/agent_utility_run.py`** (glue: result → compose-ready summary; `unique_tokens` =
  `cache_creation_input_tokens`, R7/D-1).
- **`jseval/cli.py`** — `agent-eval --seeds N` (seed-labelled repeats) + a new **`utility-compose`** command.
- **`tests/test_utility_comparison.py`** — 12 fixture tests (no live claude) proving cohort-key invariances,
  the R2 pairing property (A↔C pair; search-config + condition excluded), McNemar, the composer's record +
  refusals + seed envelope, and the Inspect export.

**Verification surfaced:** `python -m pytest scripts/jseval/tests` → **902 passed**; the new
`test_utility_comparison.py` → **12 passed**; `./gradlew.bat :modules:ui:compileJava` → **BUILD
SUCCESSFUL** (the Java change is comment-only).

## Design refinement vs §D.3a (recorded honestly)
§D.3a sketched `agent_cohort_key` as *including* the agent model + corpus. The implementation **excludes**
them: a utility-comparison record *ranges over* `{corpus × model × condition × seed}` (corpus/model are
reported per-cell exactly like 623's datasets), so the cohort key hashes only the harness identity held
fixed across the whole record. This is *more* faithful to the 623 analogy (the cohort key excludes the
range axes) and is what lets one record span model tiers. The §C-2/R2 search-config exclusion is unchanged
and is now enforced by `compose_utility`.

## Live verification (done — takeover-authorized)
A real, cohort-identified `utility-comparison.v1` record was produced end-to-end against the live dev
stack + `claude` CLI (the user authorized a stack takeover from a concurrent agent). On a contamination-
free 2-doc synthetic corpus (`scripts/jseval/util-smoke/`, fabricated facts the agent cannot know),
2 queries × condition A (file tools) vs C (JustSearch MCP only), 1 seed:
- **cohort_key** `634ccced…`, git `2b95ba9d`, cli `2.1.183`, judge `substring-em`, contamination
  `private-synthetic`, tier `C`.
- **accuracy** 1.0 → 1.0 (delta +0; McNemar p=1.0 — no discordant pairs); **unique-tokens** median
  7334 → 17266 (delta_mean +9931.5, CI95 [247, 19616]); **cost** median $0.025 → $0.046.
This is an *honest, unflattering* smoke (the with-tool arm used **more** tokens — the agent over-explored,
and condition C disables only Read/Grep/Glob so it fell back to `Bash cat`, a pre-existing 346 eval-design
limitation noted in `coverage`). The point proven is the **record path**, not a favorable number — and
per design the business-doc numbers were **NOT** re-rooted from it (they stay "in development" until a
credible multi-seed MultiHop-RAG run exists; the floor data-run proper needs the 609-article corpus per
`scripts/jseval/util-smoke/README.md`). Artifacts committed under `scripts/jseval/util-smoke/out/`.

## Throughput — the floor run is nested-CLI-latency-bound (measured 2026-06-22)

A partial MultiHop-RAG floor run (50q × 3 seeds × {A,C}, `--parallel 3`) was launched on the real
609-article corpus and **killed after condition-A seed 0** once the wall-clock was measured — because the
projection was ~**3 hours**, not the ~40 min first guessed. The measurement is the useful artifact:

- **~80 s per raw query** on the real corpus (condition A seed 0 = 50 q at `--parallel 3` in **~22.5 min**;
  the agent greps/reads ~**40 K tokens/query**, **$0.149/q**, 89.4 % accuracy, 3/50 hit the 180 s timeout).
- A full 50q × 3-seed × 2-condition matrix projects to **~3 h** wall-clock / ~$50 — fine on cost, but it
  **holds the shared dev stack for hours**, which is the real constraint.

**The cost is a property of the *agent-harness cohort* (nested-CLI latency × matrix size), independent of
JustSearch's retrieval quality** — so floor-run feasibility is a throughput-engineering question, and the
levers are knobs on the run, not the product. In rough order of leverage:

1. **Raise `--parallel`** (dominant lever — already wired). Each query is an independent `claude -p`
   subprocess; wall-clock ≈ `queries × per-query ÷ parallel`. `3 → 6–8` roughly halves it, bounded by API
   rate limits and local RAM for N concurrent `claude` processes.
2. **Add a `--no-reflection` flag** (highest-leverage *code* change, small). The harness auto-runs a
   reflection `claude --resume` per with-tool (B/C) query (`agent_retrieval_eval.py:1040`), ~doubling
   condition C's calls — and the composer never consumes it. Gating it off ~halves C's time + cost.
3. **Trim the 180 s-timeout tail.** A few queries that time out dominate a parallel batch; a lower
   per-query budget (e.g. 90 s) trims the tail, dropping the hardest queries as recorded errors.
4. **Right-size the matrix.** Seeds buy the envelope + McNemar power; n buys resolution. A floor
   *demonstration* needs ~2 seeds × 30–50 q; 3 seeds × 50 q is overkill at ~80 s/query. Scale seeds only
   when the delta sits near the CI boundary.
5. **Parallelize across seeds** (the `--seeds` loop is currently sequential — `cli.py`). Seeds are
   independent and could run concurrently, but that multiplies the concurrent-`claude` count, so it trades
   against lever 1's rate-limit ceiling rather than adding free speed.

**Recommended floor recipe** (≈35 min, genuine multi-seed): `--parallel 6 --seeds 2 --max-queries 50`,
plus the `--no-reflection` flag once added. The infra (download/extract MultiHop-RAG, ingest, run, compose)
is all proven this session; only the wall-clock/stack-hold needs right-sizing.

---

# Execution design (2026-06-22) — the agent-utility run as a resumable, cohort-keyed cell matrix

> The §Throughput note above measured the problem and listed tactical levers (raise `--parallel`,
> `--no-reflection`, trim the timeout tail…). Those are short-term symptom-fixes. The *structural* problem
> is that the run's **execution model does not match the shape of its work**. This section settles the
> long-term design (general, not implementation-level) and conforms it to a seam the codebase already names.

## The problem, structurally
A utility-comparison record is fed by a **matrix of independent, expensive, network-bound cells** —
`{corpus × agent_model × condition × seed × query}`, each one nested `claude -p` subprocess (~80 s,
~$0.15). The current execution mismatches that shape three ways:
- **Parallelism is per-(condition, seed), not over the matrix.** A `ThreadPoolExecutor` runs queries
  *within one* (condition, seed) (`agent_retrieval_eval.py:1061`), while seeds loop sequentially
  (`cli.py`) and conditions are separate manual CLI invocations. The matrix is never one parallel unit.
- **No per-cell checkpoint/resume.** Phase-2 has none; a multi-hour run that dies loses everything and a
  re-run redoes every cell. (Phase-1's `_save_checkpoint` is **save-only** — it persists progress but never
  loads-and-skips-done despite its "resume-friendly" comment, `:767`.)
- **An unused reflection runs inline per B/C cell** (`:1040`), ~doubling the with-tool arm for data the
  composer never reads.

## The design: the cell is the unit — keyed by identity, idempotently checkpointed, fanned out as one pool
1. **Enumerate the matrix as a flat cell list.** A cell's identity = the `agent_cohort_key` (the harness
   identity already built, §D.3a) **+** its range tuple `(corpus_sig, model, condition, seed, query_id)`.
   The matrix becomes explicit data, not nested loops + manual CLI chaining.
2. **One bounded-concurrency pool over *all* cells** (not per-seed). Wall-clock ≈
   `(cells × per-cell) ÷ concurrency`, so conditions/seeds/queries parallelize together — the throughput
   fix (`--parallel` collapses to the single pool cap; `bench_concurrency.py` already exists to tune it
   against the rate-limit/RAM ceiling).
3. **Per-cell idempotent checkpoint = resume.** Each completed cell persists its result keyed by its cell
   identity (one record per cell), **idempotent on the cell key**; a resume enumerates the matrix and
   **skips cells whose key already has a result**, running only the remainder. A long run becomes
   survivable, and "add 2 more seeds / another corpus" costs only the new cells. This is **not new
   machinery** — it is `bisection.register_run`'s "idempotent on identity, survives retry-after-failure"
   (`bisection.py:91-111`) and `materialize`'s `skip_existing` (`materialize.py:41`), at cell granularity.
4. **Reflection becomes an optional enrichment cell**, gated off by default (not part of the measurement).
5. **The composer is unchanged.** `compose_utility` already groups by cohort/corpus/model and pairs on
   condition; it projects over the checkpointed cell set instead of per-(condition,seed) summary files.

This **subsumes** the tactical levers: `--parallel` = the pool cap; `--no-reflection` = the reflection-cell
gate; cross-seed/condition parallelism + resume fall out of the cell model for free.

## Scope judgment (why this, not more, not less)
**In** — matrix enumeration, one concurrency pool, per-cell idempotent checkpoint/resume, optional
reflection. The present problem (a floor run that is both impractical at ~3 h *and* fragile — a death at
hour 2 loses everything) genuinely requires **both** the cross-matrix pool (throughput) and the cell
checkpoint (resilience); `--parallel` alone fixes neither the fragility nor the seed/condition
serialization. **Out** (over-scope for a few-thousand-cell job): a distributed job queue, a persistent
runner daemon, a generalized "run any matrix" framework, retry-with-backoff beyond "re-run skips done", a
DB. And the retrieval-quality `jseval run` does **not** get this — its cells are fast in-process searches;
only the nested-CLI agent-eval has the expensive-cell problem that warrants it.

## Design reach — *one identity, three roles*
**This conforms to a seam the codebase already names, at a new granularity.** The
idempotent-identity-keyed-append pattern already exists — `bisection.register_run` (idempotent on
`(manifest_hash, run_dir)`, explicitly "matters when `jseval run` is retried after a failure") and
`materialize`/`ingest` `skip_existing`. The cell executor is that exact pattern at cell granularity,
**reusing the cell identity built for the record**. It is also the same shape as the harness's own
fan-out+resume (the Workflow tool's concurrency-capped `pipeline`/`parallel` with journal resume keyed by
call identity) — conform, don't fork.

**The principle, named plainly:**

> **One identity, three roles.** The cohort/cell identity that makes a measurement a *projection*
> (provenance) is also the natural key for *idempotent execution/resume* (skip-done) and the *unit of
> parallelism* (fan-out). When the unit of work is expensive, deriving execution + checkpoint from the same
> identity that derives the record collapses three concerns into one key.

**Where else it applies / existing state:**
- **`jseval run` (retrieval quality):** `manifest_hash` already serves projection (release) + the
  append-only run-index — but **not** resume (a multi-corpus run redoes all corpora on failure). The
  principle applies, but cells are cheap, so the payoff is low and it is **correctly not built there**. The
  principle predicts exactly *where* the payoff lives — expensive cells.
- **The agent-utility run (624):** expensive cells → high payoff → the present problem. The one place the
  principle is worth building, which is why it is built here and nowhere else.
- **Existing half-application (a latent gap, not fixed now):** Phase-1's `_save_checkpoint` has the
  checkpoint but not the identity-keyed skip-done — the principle half-applied. Named so the gap is legible;
  not built, because Phase-1's cells are cheap.
- **Adjacent theme (different domain):** "recover from partial failure without losing or redoing work" is
  the eval-execution instance of the resilience the engine-reliability cycle embodies for the index (627
  recovery-contract / 628 corruption-recovery). Same instinct — survive a mid-flight death — different
  substrate; noted, not unified.

**Deliberately not built now:** a generalized matrix-executor shared across retrieval-eval and agent-eval
(YAGNI — only the agent-eval has expensive cells). Recognize "one identity, three roles" and its candidate
scope; build the cell executor only for the agent-utility run, where the present problem requires it.

## External-standards pass (2026) — conform the executor to Inspect AI, don't build it bespoke

> The design above concluded "build a thin cell-matrix executor." A focused research pass (the
> execution-engine analog of §D.5's result-format pass) asked the one question my own "conform, don't fork"
> rule demands: is there an *external* standard execution engine for resumable agent-subprocess eval
> matrices that a bespoke executor would fork? There is — **UK AISI Inspect AI** — and it flips the
> recommendation from *build* to *conform*.

**Inspect AI is the standard for exactly this shape**, and its model maps onto the cell matrix almost
one-to-one:
- **`eval_set` durable resume** — re-running skips completed samples ("the log directory is the durable
  record of which tasks are completed"; a fully-done re-run is a no-op) — my §3 per-cell-resume, native.
- **Drives arbitrary subprocess/CLI solvers** — a custom solver shells out to `claude -p` per sample;
  first-class sandbox + a `max_subprocesses` lane; `max_samples` is explicitly meant to exceed
  `max_connections` for sandbox/subprocess-bound work — exactly the ~80 s `claude -p` profile.
- **Adaptive concurrency by default** (Q3 — supersedes a static cap; ~20 in-flight/model, grows to a cap,
  backs off on 429s). The 2025-26 standard; my "static cap + one-shot tuner" was already behind it.
- **Emits the EvalLog** §D.5 already wanted as the export — so conforming the *executor* and conforming the
  *format* collapse into one move.

**The matrix → Inspect mapping (clean, because I set the sample id):**
- dataset = `corpus × query`; **`sample.id` = the cell identity** (stable/explicit — sidesteps Inspect's
  shuffle-fragile auto-increment ids);
- **solver/task = `condition`** (A = file-tools agent, C = JustSearch-MCP agent);
- **`epochs` = `seed`** (Inspect's built-in repeat-each-sample-N-times *is* the seed envelope);
- **task-args = the cohort identity** (model / CLI-version / MCP-surface / judge / decoding) — so a config
  change creates a new task/log and never silently reuses a stale completed sample (Q2's #1 cache pitfall,
  the omitted-*version* bug — which `agent_cohort_key` already guards);
- **`compose_utility` is unchanged and stays authoritative** — Inspect replaces only the *executor*; the
  canonical-record projection (pairing + McNemar + distributions + coverage) reads the EvalLogs.

**Revised recommendation:** the long-term executor should **run through Inspect AI** (a `claude -p` solver,
condition-as-task, seed-as-epoch, cell-as-sample-id, cohort-as-task-args) rather than the bespoke harness
shipped in §As-built — getting resume + adaptive concurrency + EvalLog for free and conforming to the AISI
standard instead of re-implementing it. The bespoke thin-executor is the **fallback** only if the
cell-id→sample-id mapping ever fights the identity scheme (the research found it does not, since I control
the id). This is the same conform-don't-fork move §D.2 made for the record *object*, now for *execution*;
the dependency cost (adding `inspect-ai`) is the honest tradeoff, and it buys away the hardest parts
(resume, adaptive concurrency, the log format).

**Reach refinement — the principle is validated, not displaced.** The convergent 2025-26 eval-cache key is
*exactly* my cohort/cell identity (sample-id + model-version + decoding + prompt + tools + judge), and the
industry's #1 stale-cache bug is omitting the **version** fields — which `agent_cohort_key` already
includes. Conforming to Inspect *realizes* "one identity, three roles" rather than competing with it: the
one key becomes the Inspect sample-id (resume), the task-args (log/checkpoint segregation), and the
composer key (projection) — same identity, three roles, now carried by the standard engine. Q4 also
confirmed the scope floor: durable-agent (intra-trajectory) checkpointing (LangGraph/Temporal/DBOS) is a
*different* concern, correctly excluded — at ~$0.15/cell re-run-whole-cell is right, and a black-box
`claude -p` exposes no trajectory to checkpoint anyway.

---

# Confidence pass #2 (2026-06-22) — verifying the Inspect-conformance design against the real library

> The execution design above (conform to Inspect AI) rested **100% on a research subagent's reading of
> Inspect's docs** — never run. This pass installed `inspect-ai 0.3.240` and exercised the load-bearing
> claims with throwaway probes (no feature code; the only edit is this section). Every row is VERIFIED
> against a probe or a named residual.

| # | Assumption | Outcome | Evidence |
|---|---|---|---|
| **A1** | `inspect-ai` installs here; a solver can cleanly spawn `claude -p` per sample | **VERIFIED.** `inspect-ai 0.3.240` installs on **Python 3.14.4** (cp314 wheels exist); a plain `@solver` doing `subprocess.run(["claude","-p",…])` ran end-to-end (returned `BANANA`, scored `C`). **Caveat:** heavy dep footprint — ~80 transitive packages (boto3, fastapi, uvicorn, textual, aiohttp…), and it pulls numpy 2.5.0 vs jseval's 2.4.4. Adoption cost is real. | venv install log; toy subprocess-solver run |
| **A2** | `eval_set` resume skips completed samples (keyed by sample id) | **VERIFIED.** Two `eval_set` runs on the same `log_dir`/task: solver-invocation side-effect counter stayed at **2** (run-2 skipped both completed samples). The task-arg-change → new-run half was not directly observed (a probe-ordering error) → small **residual**, low risk (`task_args` is captured in identity). | echo-solver counter probe |
| **A3** | `epochs = seeds` exposes per-epoch results | **VERIFIED.** `epochs=2` over 2 samples → **4 distinct sample records**, `epoch ∈ {1,1,2,2}`, each with its **own** scores. The seed-envelope + per-seed-McNemar mapping holds (seed ≙ epoch). | epochs probe |
| **A4** | the EvalLog exposes per-sample correctness / cost / tokens | **VERIFIED, with one wrinkle.** `EvalSample` carries `output` + `scores` + `metadata` + `model_usage`. BUT **`model_usage` is EMPTY for a shell-out solver** (Inspect only tracks calls *it* makes; the `claude -p` subprocess is invisible). → cost/tokens must be **extracted from the `claude -p` JSON and stashed in sample `metadata`/`store`** — which the existing harness already does, so the logic transfers. | subprocess-solver log: `metadata.claude_cost_usd=0.057`, `model_usage=EMPTY` |
| **A5** | my **shipped** `inspect_export.json` is a valid EvalLog | **INVALID — latent bug found.** Validating the committed `util-smoke/out/utility-comparison.inspect.json` against the real `EvalLog` model gave **8 errors**: `eval.dataset` + `eval.config` required-missing; metric values must be `EvalMetric` objects, not bare floats. → the hand-rolled export is Inspect-*flavored*, not conformant. **Fix = let Inspect write the log natively (run-through-Inspect), or build from real `EvalSpec`/`EvalMetric` models** — which *reinforces* the run-through-Inspect recommendation rather than the hand-rolled export. | `EvalLog.model_validate()` → 8 errors |
| **B1** | a ~35-min floor run's concurrency won't hit rate limits | **VERIFIED (good headroom).** 8 concurrent `claude -p` ran in **27 s wall** (~7× over ~185 s serial) with **0 errors/429s** → 8-way concurrency is safe; the `--parallel 6–8` floor recipe is achievable. | 8-way concurrent burst probe |
| **B2** | the MultiHop-RAG A-vs-C delta is real, not memorized | **QUANTIFIED — moderate contamination.** Closed-book accuracy = **38 % (3/8)**, concentrated on famous entities (SBF ×2, Altman); the model **abstains** ("no access to the specific article") on the other 5 rather than hallucinating. → accuracy is **partially confounded** (~38 % answerable from memory regardless of retrieval), so **token-efficiency must lead** (the design's D-1 call, now evidence-backed) and 38 % is the honest contamination floor to publish. | 8 closed-book MultiHop-RAG queries |

## Net surprises caught
1. **A5 — the shipped Inspect export is schema-invalid** (a real latent bug in committed code). It is a stopgap, not a real EvalLog; the correct path is to let Inspect write the log (run-through-Inspect) — which the design already recommends, now with a concrete reason.
2. **A4 — Inspect does NOT give shell-out usage "for free"** (`model_usage` empty); cost/tokens come from the `claude -p` JSON into sample metadata. Minor, and the existing extraction logic transfers.
3. **A1 — the dependency footprint is heavy** (~80 transitive deps). A real adoption-cost input to the run-through-Inspect-vs-thin-bespoke decision (not a blocker; a judgment).
4. **B2 — contamination is ~38 %**, validating "lead with token-efficiency, not accuracy" with a number.

Everything load-bearing (A1–A4 mechanics, B1 concurrency) **verified against the real library**; the
design's core — Inspect can drive a `claude -p` solver, resume by sample id, map seeds to epochs, and
surface per-sample results — holds. The residuals are minor/known (the export-bug fix, the usage-metadata
wrinkle, the unobserved task-arg-segregation half, the dep footprint).

## Confidence rating — remaining work: **8 / 10**

- **Execution refactor (conform to Inspect): ~8.5.** The mechanics are now empirically confirmed, not
  doc-derived: the library installs on this Python, drives a `claude -p` solver ergonomically, resumes by
  sample id, and maps seed→epoch with per-epoch records. Held below 9 by the shipped-export bug (known
  fix), the usage-in-metadata wrinkle, and the heavy-dependency adoption cost (a real judgment, not a
  surprise).
- **Floor data-run: ~8.** Concurrency headroom is confirmed (8-way, 0 errors), so the ~35-min recipe is
  real; the validity threat is now *quantified* (38 % closed-book), and the design already routes around it
  (token-efficiency lead). The residual is inherent (a truly clean accuracy headline needs a post-cutoff
  corpus — a known plan-phase decision, not a surprise).
- **Held to 8 overall by the two non-mechanical plan-phase decisions** the design always deferred: the
  **judge** (LLM-judge model choice — still unbuilt, a decision not a risk) and the **contamination-resistant
  corpus** (a build-or-accept call). Neither is a code-surprise; both are owner decisions.

**Reading:** the execution design is now low-surprise — the one design I'd built purely on second-hand
research is verified to behave as claimed, with a couple of small known corrections folded in. The residual
uncertainty is deliberately the methodology/decision part (judge, corpus), not the engineering.

---

# As-built #2 (2026-06-22) — the Inspect-conformant executor + a real floor record

The execution design shipped **through Inspect AI** (the long-term-best choice; the dep is an opt-in
`jseval[agent]` extra so core jseval stays at its 6 deps). New on `worktree-624-agent-utility-eval`:
`jseval/agent_utility_inspect.py` (a `claude -p` solver, `substring_scorer`, `agent_utility_task` with the
cohort identity in task-args, `run_utility_eval` via `eval_set`), `agent_utility_run.eval_logs_to_summaries`
(EvalLogs → the `compose_utility` projection), the `jseval utility-run` CLI, the `agent` extra, and a real
Inspect round-trip test. **Deleted `inspect_export.py`** (confidence-pass A5: schema-invalid category error;
Inspect writes real logs now). Full suite **902** + utility **12** green. Integration corrections found while
building (all in-code): `eval_set` needs `@task`+distinct-args; the `.eval` zip recorder breaks on Windows
fsspec paths → `log_format="json"`; skip the `eval-set.json` index when reading logs.

## The real floor result (50q × 3 seeds × {A, C}, MultiHop-RAG, haiku, via Inspect `eval_set`)
cohort `ceea017b…`, git `7548e494`, cli `2.1.183`, seed_count 3, contamination `public-pre-cutoff`,
n_paired = **100**.

| Metric | A (file tools) | C (JustSearch MCP) | Delta | Significant? |
|---|---|---|---|---|
| **Accuracy** (McNemar) | 0.87 | 0.92 | **+0.05** | **No** — p = 0.27 (fixes 9 / breaks 4) |
| **Unique tokens** (median) | 32,206 | 20,526 | **−13,378 (≈ −40 %)** | **Yes** — CI95 [−17746, −9341] excludes 0 |
| **Cost USD** (median) | $0.103 | $0.092 | −$0.025 (≈ −24 %) | — |

Seed envelopes are tight (A 0.871 ± 0.025; C 0.920 ± 0.015). **The result confirms the design's central
call:** the **token-efficiency win is significant** (CI excludes 0) while the **accuracy delta is not**
(McNemar p = 0.27) — exactly what the ~38 % contamination (B2) predicts, which is *why* the design leads
with token-efficiency, not accuracy. This is an honest, unflattering-where-it-should-be record, produced by
the standard harness — not a marketing number.

**Caveats recorded (honesty fields + here):** (1) **timeouts** — the 180 s/cell limit under concurrency-8
contention timed out ~13 % of A cells and ~26 % of C cells (C is slower: retrieval + reasoning + MCP),
excluded as invalid; the paired McNemar uses the both-arms-completed intersection (n=100, fair), but the
per-arm cost/token *distributions* exclude each arm's timeouts asymmetrically — a longer timeout / lower
concurrency is the fix (the throughput note's "trim the timeout tail" lever, now observed live). (2)
**sparse-only retrieval** — the dev index had BM25+SPLADE but legacy-blocked dense vectors, so C's retrieval
was sparse-only (a floor, not a ceiling). (3) **substitution arm** — C disables file tools, the favorable
arm (coverage field). **Business-doc numbers were NOT re-rooted from this floor run** (contamination-flagged
+ accuracy non-significant) — they stay "in development," as designed. Artifacts under
`scripts/jseval/util-smoke/floor-inspect/`.

---

# Run-governance design (2026-06-22) — the run must be as governed as the number it produces

> The floor run *worked* but was **operationally untrustworthy**: it ran on a degraded (sparse-only,
> non-pinned) index, picked timeout/concurrency blind → silently excluded **13 % of A / 26 % of C** cells
> (asymmetric, biasing), and shipped with a **hand-set** `confidence_tier="C"` and **no computed
> comparability verdict** at all. Diagnosis: I built the *measurement* to the canonical-record + projection
> + honesty seam, but left the *run that produces it* ungoverned. This section theorizes the correct
> long-term shape (general, not implementation-level). It is **mostly reuse**: the retrieval eval already
> governs its run; the agent-eval must conform.

## The problem, structurally
A governed record produced by an **ungoverned run** is a record that cannot vouch for itself. The
retrieval eval (`jseval run`) governs its run on three axes the agent-eval simply **ignored**:
- **Readiness** — `preflight.py` gates a run on dist/models/no-stale; `comparability.ReadinessResult` gates
  trust. The agent-eval ran condition C against a `BLOCKED_LEGACY`, dense-disabled index with **no readiness
  check** and a **hand-labelled** `search_config_cohort_key` (the real `/api/status` embedding+schema
  fingerprints were right there, un-read).
- **Comparability** — `comparability.determine_comparability(readiness, ann_proof, error_count,
  query_count, max_error_rate=0.05)` already returns `comparable=False` when the **error rate exceeds 5 %**.
  My run's **26 % exclusion is 5× that threshold** — it would be flagged *not comparable*. I never called it;
  the record asserts `comparable` per the composer's default and a hand-set tier.
- **Calibration** — `calibrate.py` runs a cheap repeated pre-pass to bound a cohort's noise (±2σ envelope)
  *before* trusting a measured delta. The agent-eval picked timeout/concurrency from a guess and learned the
  26 % consequence *after* spending $30 + 40 min.

## The design — govern the run by conforming to (and minimally extending) those three seams
1. **A pre-run calibration + readiness pass** (sibling of `calibrate.py`; conforms to `preflight` +
   `ReadinessResult`). A micro-pilot (a handful of cells) that: (a) gates on **retrieval readiness**
   (dense+sparse) and **pins the real `config_cohort_key`** from `/api/status` — refuse-or-record if
   degraded; (b) measures **p95 cell latency + cost** → sizes `timeout` (~2×p95) and `concurrency` (where
   latency stops degrading) and **projects total $/time** for an operator gate; (c) runs the query set
   **closed-book** and **drops the memorizable queries** (the §B2 contamination, now a structural matrix
   filter, not a prose caveat — the kept set is exactly the retrieval-relevant queries, which also makes
   accuracy cheaper to power). Same "cheap pre-pass conditions the expensive run" seam as the envelope;
   different output (run-params + readiness + filtered queries instead of σ).
2. **Comparability from loss-accounting, paired-extended** (conforms to `comparability.py`). The composer
   computes **per-arm `n_attempted / n_completed / n_excluded`**, feeds `determine_comparability` per arm,
   and **adds the one thing the paired case needs the single-arm gate lacks: the A/C exclusion *asymmetry***
   (if the arms drop different queries, the paired-n shrinks and the per-arm distributions skew). The
   record's `comparable` verdict + `confidence_tier` are **derived** from this, never hand-set — a 26 %
   asymmetric-exclusion run is *structurally* flagged. This is the tempdoc's *"honesty as fields"*
   principle extended from corpus-caveats to **execution losses**.
3. **A live-state projection** (conforms to `compose_utility` / the `projections/` seam, run live).
   `jseval utility-status <log-dir>` projects over the *partial* EvalLogs → completion %, per-arm exclusion,
   running cost, emerging delta + CI — the canonical projection, not grepped stdout. This makes the
   ad-hoc poll I hand-rolled this session a first-class surface, and crucially exposes the solver errors
   Inspect **swallows into `metadata.error`** (the misleading `error_lines=0` heartbeat).

## Scope judgment (why this, not more, not less)
**In** — the three above. Each maps to a specific way *this* run was untrustworthy (degraded index, blind
params, invisible asymmetric loss, hand-set tier), and each is ~reuse of an existing seam + the one
paired-arm extension. The closed-book filter is a small, high-value query-conditioning step. **Out**
(over-scope): a generalized "run-governance framework" across all evals (recognize, don't build); a hosted
dashboard; and the deeper **in-process Agent-SDK executor** (replacing `claude -p` subprocesses — it would
erase the subprocess/path/usage-capture/Windows-glue friction that caused most debug cycles, but it couples
to the SDK and is the *"second time you run this at scale"* move; named, not built, per 625's discipline).
A **dedicated ephemeral JustSearch instance** (own port/dataDir, avoiding the shared-stack takeover) is an
operational conformance to the dev-runner's existing custom-dataDir support — worth doing, not new structure.

## Design reach — *governance must extend to the run, not just the record*
**The principle, named plainly:**

> **A measurement's governance must extend to the operation that produces it.** Cohort identity, readiness,
> calibration, and a trust-gate apply not only to the *number* but to the *run* — a governed record from an
> ungoverned run cannot vouch for itself. The run is a first-class governed object, not a black-box batch.

**It conforms to seams already in the tree** — `comparability` (trust-gate), `calibrate` (pre-pass), and
`preflight` (readiness) are exactly this principle for the *retrieval* eval. So this is not a new mechanism;
it is the agent-eval **finally inheriting** the run-governance the retrieval eval already has, plus the one
paired-arm extension (exclusion asymmetry) the single-arm gate genuinely lacks. It also echoes, in the eval
domain, the engine-reliability cycle's "govern the *operation*, not just the state" (626 reconciler / 627
recovery-contract / 628 detect→classify→recover) — same instinct, different substrate.

**Where else it applies / existing state:**
- **The retrieval eval itself is only *partially* governed.** `comparability` gates its error-rate, but
  there is **no parameter-calibration gate** (it doesn't size its own run) and readiness is checked at
  *stack-start* (`preflight`), not bound into the *record's* comparability with the real index fingerprints.
  So even the conform-target half-applies the principle — a latent gap, named not fixed.
- **Any expensive, lossy, environment-dependent measurement** (the 623 §F extraction-quality sibling; a
  future model-card run) inherits the same need: govern the run or the number is unvouched.
- **The violation this design fixes** is 624's own floor run — the proof-by-example.

**Deliberately not built now:** the generalized run-governance framework, and the in-process SDK executor.
Recognize the principle (govern the run) + conform the agent-eval to `comparability`/`calibrate`/`preflight`
with the paired extension; build the framework only when a *second* eval needs it (625's recognize-vs-build
discipline). The insight — *I governed the output and forgot the process* — is captured without becoming
premature abstraction.

---

# Confidence pass #3 (2026-06-22) — verifying the run-governance design (mostly from free floor data)

> Read-only pass before implementing the run-governance design. Mined the **committed floor EvalLogs**
> (~3 MB, real per-cell latency/exclusion/correctness at concurrency-8) + a few cheap probes (~$1.50) +
> code reads. Every row VERIFIED from data/`file:line` or a named residual. Three concrete design
> corrections fell out.

| # | Assumption | Outcome | Evidence |
|---|---|---|---|
| **A2** | exclusion asymmetry is real + has a definable metric | **VERIFIED — real, biasing.** A excludes 13 %, **C 27 %**; excluded-query **Jaccard overlap = 0.42** (C drops 12 queries A doesn't); **paired-n retention ≈ 66 %** (34/50 per seed → the record's n=100). Metrics for the gate: per-arm rate + excluded-set Jaccard + paired-n retention. All exclusions are exactly **180 s ⇒ confirmed timeouts.** | floor logs |
| **B1** | a *cheap* pilot predicts the full run *(the crux)* | **RESOLVED — with a correction.** Sequential (no-contention) cond-A p50 = **44 s** vs concurrency-8 p50 = **78 s** → contention ~**1.8×** inflates median; a low-conc pilot **underestimates**. Completed p95 ≈ **155 s** sat right under the 180 s timeout (too tight). → **the pilot must run at the *target* concurrency**, and set `timeout ≈ 2× contended-p95` (~300 s). | 5-cell sequential probe vs floor |
| **B2** | the closed-book filter isolates retrieval-dependent queries + leaves power | **VERIFIED — strong.** 50 closed-book → 36 % correct → 32 retained. C-beats-A delta: full **+0.03** (n=44) → **retained +0.07** (n=27); **dropped −0.04** (n=17, both near-perfect). The filter ~**doubles the signal** and removes the contaminated noise. **Caveat:** retained n is small → needs a *larger* query pool (filter the full 2 556, not 50) for accuracy power. | 50 closed-book probe × floor logs |
| **A1** | `comparability.determine_comparability` is reusable | **VERIFIED REUSABLE.** It takes plain dataclasses (`types.py:43-66`): synthesize `ReadinessResult(passed=…)`, pass `AnnProofResult(status="NOT_APPLICABLE")` (the FAIL-check skips), feed per-arm `error_count/query_count`. → call it **per-arm**; add the A2 asymmetry check alongside. Genuine reuse, not a parallel fn. | `types.py`; `comparability.py:8-37` |
| **B3** | pin `config_cohort_key` from `/api/status` | **RESOLVED — corrected source.** `/api/status` exposes `indexSchemaFpCurrent` (the corpus-*dependent* `index_schema_fp`, which `config_cohort_key` **excludes**), not the config-global fps it hashes (`schema_fp/similarity_fp/boosts_fp/grammar_hash`). → pin from **`/api/debug/commit-metadata`** (the manifest's existing source) + reuse `release.config_cohort_key`. | status JSON vs `release.py:120-140` |
| **D2** | pilot `eval_set` then full won't confuse resume | **RESOLVED.** Same-cohort same-dir **reuses** (4 calls → 4, resume works); a different-param pilot is a distinct task anyway. Design: pilot writes a **separate throwaway log dir**. | mock-solver experiment |
| C | live-status projection works on partial logs | **already de-risked** — this session's manual polls *were* `eval_logs_to_summaries` over partial logs. | this session |

## Design corrections the evidence forced
1. **Calibrate at the *target* concurrency, not low concurrency** (B1) — a sequential pilot underestimates
   median latency ~1.8×; the pilot must pay the contended latency on a few cells. Timeout ≈ 2× contended-p95
   (the floor's 180 s was below the 155 s p95 — too tight).
2. **Pin `config_cohort_key` from `/api/debug/commit-metadata`, not `/api/status`** (B3) — status carries the
   wrong (corpus-dependent) fingerprints.
3. **Comparability = `determine_comparability` *per arm* (reuse) + a paired asymmetry check** (A1+A2) — the
   single-arm gate genuinely lacks the paired axis; the metrics are now concrete (Jaccard, paired-n retention).
4. **The closed-book filter needs a larger query pool** (B2) — it works (doubles the signal) but 32/50 retained
   is underpowered; filter the full MultiHop-RAG, not a 50-slice.

## Confidence rating — remaining work (run-governance): **8.5 / 10**
- **Comparability + loss-accounting + asymmetry (A1/A2): ~9.** Reuse confirmed; the asymmetry is real with
  defined metrics from real data.
- **Calibration pass (B1): ~8.** The crux risk (cheap pilot underestimates) is *confirmed*, and the fix is
  clear and cheap (pilot at target concurrency). Works with the correction.
- **Closed-book filter (B2): ~9** mechanically (verified to sharpen the signal); the only residual is *power*
  (needs a bigger pool) — a scale decision, not a code risk.
- **B3 / D2 / live-status: ~9** — corrected source + resume + the already-proven projection.
- **Held to 8.5** only because the four corrections must be implemented and the accuracy-power question is a
  methodology/scale call (larger corpus). No load-bearing engineering surprise remains — the crux is resolved.

**Reading:** the run-governance design is low-surprise after this pass — it is mostly reuse of
`comparability`/`calibrate`/`preflight`, the one genuinely-new piece (paired asymmetry) is validated against
real data, and the crux (pilot predictiveness) is resolved with a clear correction rather than left to hope.

---

# As-built #3 (2026-06-22) — run-governance shipped (loss-accounting + calibration + live-status)

The run-governance design shipped on `worktree-624-agent-utility-eval`, **mostly as reuse** of the retrieval
eval's own seams. Three pieces:

1. **`jseval/utility_governance.py`** — `compute_loss_accounting(log_dir)` (per-arm
   `n_attempted/completed/excluded` + excluded-query-sets from the EvalLogs) + `paired_comparability` (reuses
   `comparability.determine_comparability` **per arm** with `ann_proof=NOT_APPLICABLE`, plus the paired
   **exclusion-Jaccard + paired-n-retention** asymmetry check). `compose_utility` gained a `governance` param
   → the record carries a `comparability` block and **derives `confidence_tier` from the verdict** (never
   hand-set).
2. **`jseval/utility_calibrate.py` + `jseval utility-calibrate`** — readiness gate
   (`readiness.check_search_ready`), real `config_cohort_key` pin (`manifest.capture_state_snapshots` +
   `release.config_cohort_key`, B3), **pilot at target concurrency** → `timeout ≈ 2× contended-p95` (B1),
   closed-book filter (B2). Emits `calibration.json`; `utility-run --calibration` consumes it (timeout /
   concurrency / filtered queries / pinned key, and threads the readiness verdict into the run's
   comparability — `readiness ∧ error_rate`).
3. **`jseval utility-status <log-dir>`** — live projection over partial logs (completion %, per-arm exclusion
   — exposing the timeouts Inspect swallows — and the emerging delta).

## Validation (all tiers; no UI surface, so no browser)
- **Free, against the committed floor logs** — `paired_comparability` flags the real run **`comparable=False`**
  (arm_C 0.267>0.15, Jaccard 0.42<0.5, retention 0.667<0.7). The hand-set tier is now a *derived, reasoned*
  verdict. `utility-status` reproduces it + the +0.05 / −40 % deltas.
- **Unit** — `compute_loss_accounting` + `paired_comparability` + the `compose_utility` governance integration
  (synthetic + a real mock `eval_set`); suite **904 + utility 14** green; `gradlew build -x test` green (no
  Java change).
- **Live** (dev stack, MultiHop-RAG, ~$5) — `utility-calibrate` end-to-end: the **readiness gate correctly
  FAILED** on the still-degraded (BLOCKED_LEGACY dense) index — *the exact degradation the floor run silently
  ran on* — pinned a **real `config_cohort_key`** (`90c8b818…`), calibrated **timeout=195 s** from a
  target-concurrency pilot, closed-book-dropped 5/10. `utility-run --calibration` then consumed it; with the
  calibrated timeout the cells **did not time out → `COMPARABLE=True`** (the calibration *fixes* the floor's
  exclusion), and with the failed-readiness threaded → **`COMPARABLE=False`** (a run on a degraded index is
  correctly not-comparable).

## Two correctness fixes the live validation forced (invisible to unit tests)
- **Stable filtered-query path** — `--calibration` first wrote the retained-query subset to a *random
  tempfile*, making `queries_path` (an Inspect task-identity arg) vary across invocations and breaking resume.
  Fixed to a deterministic path next to the calibration.
- **Deterministic `eval_set_id`** — `eval_set` defaults to a *random* set id per process; pinned it to a hash
  of the run config so the set identity is stable for cross-invocation resume.
- **Known Inspect limitation (logged to inbox):** re-invoking a **fully-completed** `eval_set` still errors
  `log file not associated with a task` (needs `--log-dir-allow-dirty`); the partial-crash resume path uses
  the now-pinned `eval_set_id`. Within-run resume (eval_set concurrency) is unaffected.

**Net:** the agent-eval now governs its *run* the way it already governed its *number* — calibrate before
spending, gate on readiness, account for losses, derive the trust verdict — by conforming to
`comparability`/`readiness`/`manifest`, with one validated paired-arm extension. Deferred owner-decisions
(LLM-judge, post-cutoff corpus, business re-rooting, in-process SDK executor) remain out of scope.

---

# As-built #4 (2026-06-22) — credibility-completing machinery (LLM-judge + condition B)

Closes the two pieces an alignment pass found unbuilt — the parts that make the number "one a hostile
reviewer can't dismiss" (C-6 scoring + C-4 the honest arm). Both **mostly reuse**. The corpus, larger-n
spend, and **re-rooting stay owner-gated and were NOT done** (the goal was the *capability*, not a published
number — and re-rooting from a floor run is your standing block).

## The LLM-judge (C-6 / E-5) — hybrid EM→judge, post-hoc, dual-order
- **`jseval/utility_judge.py` + `jseval utility-judge <log-dir>`** — runs **post-hoc over the EvalLogs** (the
  agent answers + targets are already stored, so judging is decoupled + re-judgeable without re-running
  agents). **EM auto-passes**; the **EM-misses** are judged by the **local Qwen3.5-9B** via the JustSearch
  API's `/v1/chat/completions` — *a different model family than the claude agent* (the self-preference
  control) — **dual-order**, abstaining to EM on disagreement. Writes a `judge-overlay.json` (final verdicts
  + judge identity into the cohort key + the **EM-vs-judge agreement rate**, the E-5 caveat as a field).
  `eval_logs_to_summaries` consumes the overlay (`final` supersedes EM; cohort `judge` = `hybrid-em-llm`).
  Graceful: no endpoint → EM-only, recorded honestly. Reuses `agent_retrieval_eval._score_answer` + its
  `/v1/chat/completions` call pattern + `agent_manifest.judge_identity`.

## Condition B (C-4) — the realistic "addition" arm, un-pooled
- The executor already ran B; the **composer pooled `{B,C}`**. `_compose_cell` now reports baseline **A** vs
  the SEPARATE **substitution C** and **addition B** arms (`arms.{substitution_c,addition_b}` + `primary_arm`),
  back-compat (top-level = C when present; the floor record still composes).

## Validation (all tiers; no UI → no browser)
- **Unit** — judge dual-order + abstain + EM-fallback; composer B/C separation. Suite **907 + utility 17**
  green; no Java change.
- **FREE, on the committed floor logs** (judge live, no agent re-run) — EM-pass 219, **20 misses judged, 2
  rescued** (substring false-negatives), dual-order **agreement 0.90**, 2 disagreements abstained. EM→judged:
  A 0.87→0.88, C 0.92→**0.93**; **delta stable at +0.05, p unchanged** — *the result is robust to the scoring
  method*, itself a credibility signal.
- **LIVE A/B/C** (5q × haiku, judged, sparse index) — proves B executes and the un-pooled arms surface the
  honest finding C-4 predicted: **substitution C +0.20 acc / −10.4k tokens** vs **addition B +0.00 acc /
  −2.1k tokens** — the realistic "already have file tools" scenario gives ~no accuracy gain and far smaller
  token savings, now its **own number**, not hidden in a pooled with-tool arm.

## Still owner-gated (capability built; the published number is yours to make)
Contamination-resistant *primary* corpus (E-3; closed-book filter is the partial substitute), multi-tier /
larger-n spend (E-4), and **re-rooting the business numbers** (D.3d — blocked; needs a credible record +
sign-off). The machinery (judge + B + governance + calibration) now makes such a record *producible*; the
floor/validation numbers remain "in development" and were **not** re-rooted.

---

# Open uncertainties (2026-06-22, post-§As-built-#4) — the path to a credible, re-rootable number

The machinery is built. What remains is **not engineering** — it is the chain of decisions + measurements
that turn the machinery into a *credible published number*. The three owner-gated items are **one dependency
chain** — corpus (input) → spend (engine) → re-root (endpoint) — gated by one question. This section
supersedes §E with the state the floor + judge + B runs now give it.

## §E revisited — what the as-builts closed
- **E-1 (sibling vs cousin) — CLOSED** (§D.2: separate canonical record).
- **E-2 (which condition is headline) — PARTLY CLOSED**: §As-built-#4 reports B (addition) and C
  (substitution) as *separate* arms; *which to headline* is still a positioning call (→ U-A2).
- **E-5 (judge) — CLOSED**: hybrid EM→local-LLM judge (Qwen3.5-9B, different family), dual-order, shipped;
  EM-vs-judge agreement 0.90 recorded; the EM-vs-judged delta is stable (+0.05), so the result is robust to
  the scoring method.
- **E-3 (corpus), E-4 (seed/spend), E-6 (sequencing) — OPEN**, sharpened below.

## U0 — the overarching uncertainty (resolve FIRST): does the honest number *help* the wedge?
The data already warns: the **realistic** arm (B — agent that already has file tools *and* gets JustSearch)
showed **+0.00 accuracy / ~8 % token savings**, vs the favorable substitution arm (C) at **+0.20 / ~40 %**
(§As-built-#4, n=5; the floor's token-efficiency was the only *significant* signal). If the honest realistic
number is weak, more corpus + spend buys a *better-measured weak number*; the move may be to **re-frame the
wedge, not measure harder**. This gates whether the corpus/spend investment is worth making at all. Partly
data (a sharper B-arm measurement), partly a positioning judgment (owner).

## A — Re-rooting (endpoint): mostly definitional / strategic (owner)
- **U-A1 — the credibility *bar* is undefined.** "A credible record" needs concrete gates (contamination-free
  corpus? judge? *significant* token-efficiency? B reported? n ≥ ?, seeds ≥ ?). *I can propose; owner approves.*
- **U-A2 — the claim *shape*** (token-efficiency vs accuracy; substitution-C vs the honest addition-B). Sets
  what number we must produce. *Positioning — owner.*
- **U-A3 — does *any* honest number clear the bar?** (U0 applied to re-rooting — rigor can't rescue a small
  effect, only state it honestly.)
- **U-A4 — external timeline** (the retracted NLnet-draft number — is an application deadline forcing this?). *Owner.*

## B — Spend (engine): economic + statistical
- **U-B1 — budget ceiling** (~$70 haiku-floor → ~$3.6k 3-tier-fuller, R6). Sets the matrix. *Owner.*
- **U-B2 — power: what n reaches significance for the observed effect, and is it affordable?** Floor accuracy
  delta +0.05 is non-significant at n≈100; if the true effect is ~5 pp, McNemar may need n≈500+ (maybe
  unaffordable). **Resolvable now, free, from the floor data** — tells us whether accuracy is *ever* worth
  funding or whether to lead with token-efficiency.
- **U-B3 — is accuracy-power spend needed at all?** Token-efficiency is *already* significant at floor n;
  if it is the headline (D-1), accuracy-n may be wasted spend (better aimed at more corpora). *Depends on U-A2.*
- **U-B4 — tier sensitivity** — does sonnet/opus change the conclusion vs haiku, or is the 10–15× cost wasted?
  *A cheap 1–2-query probe is indicative (needs the dev stack).*

## C — Corpus (input): construction + a real tension
- **U-C1 — the reproducibility-vs-contamination tradeoff (E-3, the core fork).** Contamination-proof *and*
  redistributable may not coexist: private files (clean, unshareable → stranger can't reproduce), synthetic
  (shareable, clean, artificial), post-cutoff public (shareable, contamination decays as models update).
  *Design call — I recommend, owner chooses.*
- **U-C2 — does a synthetic corpus preserve *realistic* multi-hop retrieval difficulty?** Too toy → the delta
  is meaningless. **Resolvable by a small feasibility pilot I can build** (~10 synthetic docs → is the A-vs-C
  delta non-trivial + the queries genuinely retrieval-dependent, via the closed-book filter).
- **U-C3 — generation + validation protocol + cost for adequate n** (100+ quality multi-hop QA pairs is itself
  an LLM-generation + QC task). *I can scope.*
- **U-C4 — self-generation contamination** (if an LLM builds the corpus, does the agent-under-test "know" the
  patterns?). Needs a generation method that breaks that. *Design.*

## Resolution map
| Resolvable now (free / cheap, no owner input) | Needs owner decision |
|---|---|
| **U-B2** power analysis from the floor data (n-for-significance) — *free, highest leverage* | **U-A1** the credibility bar (I propose → owner approves) |
| **U-C2** synthetic-corpus feasibility pilot (~10 docs) | **U-A2 / U0** the claim shape + does the honest number help the wedge |
| **U0/U-A3** a sharper B-arm measurement (modest $, needs the stack) | **U-B1** the budget |
| **U-B4** tier-sensitivity probe (cheap, needs the stack) | **U-C1** the reproducibility-vs-contamination tradeoff |

## Recommended sequence
Resolve **U-B2 (power) + U-C2 (synthetic feasibility) first** — both nearly free, and they convert the two
biggest *unknowns* ("is accuracy ever affordable?" / "can a contamination-free corpus even measure this?")
into facts, which is exactly what the owner needs before the genuinely-theirs calls (bar, budget, claim,
corpus tradeoff). The stack-dependent probes (B-arm, tier) follow once the dev stack is back. **No re-rooting
until U-A1's bar is set and a record clears it.**

---

# Live-run attempt on the 635 clean corpus (2026-06-25) — integrated + viable member found, but the run FAILED on the concurrency-contention timeout class (the calibration lesson, self-applied too late)

The MCP-wedge being chosen + **tempdoc 635 shipping a clean, closed-book-certified corpus suite** (and explicitly handing "a credible agent-accuracy number §F-2 → 624") unblocked the run. Attempted it; **the integration works, but the credible number was not produced** — the run failed on the exact contention-timeout class the run-governance calibration exists to prevent, which I did not apply.

## What worked (the integration is sound)
- **635 → agent-eval is a thin adapter.** `materialize.materialize` turns 635's `docs.jsonl` into a `.txt`
  corpus-dir (condition A + ingest); 635's `queries.json` feeds `utility-run --queries` **as-is**
  (query+answer). Ingest via `ingest.ingest_and_wait` (`/api/knowledge/ingest`). A **fresh dataDir** gives a
  clean **dense-100%** index (avoids the legacy-blocked-dense trap), and the 635 retrieval ceiling
  **reproduces** ("power station in the upper wetlands" → the synonym-bridged doc at rank #1 — dense bridges
  the synonyms, lexical/grep can't).
- **MCP dev tools were disconnected this session** → drove the full stack via `dev-runner.cjs start/stop`
  directly, ingested via the API, and used **EM scoring** (the 635 answers are exact synthetic tokens, e.g.
  `ochre quartzine 0002`, so substring-EM is high-precision; the LLM-judge is deferred — it needs inference,
  which needs `ai_activate`, an MCP tool that was gone).

## The two real findings
1. **The 2-hop members are too hard for the haiku agent.** Condition C failed the prose member **0/3**
   *despite 20+ turns* of genuine multi-turn retrieval. The bottleneck is the agent **chaining 3 hops**, not
   retrieval (retrieval works). So the multi-hop 635 members don't yield a clean agent-utility delta on haiku
   (both arms fail). **`needle-burial-v1` (the only 1-hop member, 20 q) is the viable vehicle** — its
   condition-C pilot **succeeded 3/4** (the haiku agent *can* answer 1-hop synonym-bridged queries with
   JustSearch, where grep can't). A **clean per-member index** is required (members share synonym templates,
   so a mixed index confounds).
2. **The full A/B/C × 5-seed needle run FAILED at concurrency 8 — condition C ~87 % timeout exclusion**
   (52/60 C cells hit the 300 s timeout; A 60 %, B 69 %). Cause: **concurrency-8 contention** (8 `claude -p`
   agents + the dev stack + GPU) inflated each many-turn C cell past the timeout. The **low-concurrency pilot
   (3/4) underestimated the contended latency** — this is the **verbatim B1 finding of §Confidence-pass #3**
   ("calibrate at the *target* concurrency; a low-concurrency pilot underestimates ~1.8×"). I *documented*
   that lesson and *built* `utility-calibrate` to prevent it, then ran `utility-run` **directly at
   concurrency 8 without the calibrate pass** — a substrate-discipline miss: *building the guard is not
   applying it.* (Killed at ~220/300 cells on the owner's call; stack stopped; not restarted.)

## The corrected run (specified, NOT run — per the owner)
Re-run on `needle-burial-v1` at **low concurrency (≈3) + a longer per-cell timeout (≈420 s)** — or, properly,
*through* `jseval utility-calibrate` (which sizes the timeout from a target-concurrency pilot and gates
readiness) → then `utility-run --calibration`. At concurrency 3 the cells **complete instead of timing out**;
same per-cell cost (~$45), but it finishes with data, not exclusions. The expected shape is the honest
**ceiling existence-proof**: A (grep) fails the synonym bridge; C/B (JustSearch) succeed. Token-efficiency
stays the durable headline; EM suffices for scoring on the synthetic exact-token answers (LLM-judge as a
later refinement when inference is available).

**Net:** the corpus prerequisite (635) is satisfied and the integration + 1-hop viability are proven; the
credible number is one *corrected* (calibrate-first / low-concurrency) run away. The lesson for next time is
already on the page — **run the eval through `utility-calibrate`, do not hand-set concurrency.**

---

# Assignment (2026-07-01) — harden to an externally defensible number (brief only; plan reserved)

The §Gating / sequencing condition above ("rebuild only when the agent/MCP wedge is committed") is
now satisfied: the product-center question is resolved **agent-runtime-first** (tempdoc 654,
Direction note 2026-07-01). This tempdoc is re-activated as the **highest-priority eval work**.

**Goal.** Turn the existing condition-paired machinery (§As-built) into a measurement that survives
a hostile external reviewer: adequate n (well beyond 20), multi-seed, uncontaminated + identity-verified
corpus (reuse the 664 corpus-identity machinery; 641 is the contamination-corpus stub), separated
accuracy and cost claims, a defensible judge protocol, and a strong (non-strawman) baseline arm.

**Boundaries.**
1. No number publishes until it clears the README's methodology bar — the record stays internal until then.
2. The comparison battlefield is heterogeneous personal/team documents (mixed formats, multilingual,
   OCR'd scans) — explicitly **not** code navigation, where native agent file-exploration is a known-strong baseline.
3. The baseline arm must be the strongest reasonable file-tools-only configuration, not a weakened one.

**Process note.** The methodology/design pass is deliberately NOT delegated with this brief — it is
reserved for a dedicated high-effort planning session (the failure mode being guarded against:
a plausible-but-exploitable eval design). Implementation agents should not begin design work from
this brief alone.

---

# Methodology plan (2026-07-02) — hardening path to an externally defensible number

> This is the dedicated planning session the 2026-07-01 Assignment reserved. **Planning only — no
> implementation, no eval runs, no code changes in this pass.** Sourced from a primary-source read of this
> tempdoc in full, tempdocs 635 (contamination-resistant corpus, full arc through its 2026-06-24 As-built),
> 664 (eval-corpus/ratchet-provenance integrity, complete through its thirteenth pass), 641 (regeneration
> stub, deferred), 625 (provenance-generalization stub, deferred), and the current state of
> `scripts/jseval/jseval/{agent_manifest,agent_retrieval_eval,agent_utility_inspect,agent_utility_run,
> utility_comparison,utility_governance,utility_calibrate,utility_judge,corpus_certify,corpus_fidelity,
> corpus_identity,corpus_generate,suite_profile}.py` and `modules/ui/.../McpToolSurface.java`, verified
> directly rather than trusted from this tempdoc's own (now ~10-day-old) citations. Tempdocs 366/346 were
> not re-read in full — their load-bearing claims are already file:line-verified above (§B/§C) and nothing
> in this plan depends on a fact from them this tempdoc hasn't already checked. `docs/business/
> research-channel/plan.md` **does exist in this repo** (confirmed by listing) — no sidecar/founder lookup
> was needed for that boundary condition.
>
> Two real, previously-unflagged defects in the *current* harness were found during this pass and are
> load-bearing for the plan below (§M.1) — not carried over from any prior pass's citations.
>
> **Correction to this session's own opening framing, folded in before anything below is read as fact.**
> The "~75% accuracy / −34% cost / −39% tokens" preliminary figure quoted in this tempdoc's §Assignment
> background is the **substitution arm (C vs A: an agent with *no* file tools at all, retrieval-only)** —
> the favorable but less realistic comparison. The **realistic arm (B: an agent that already has generic
> file tools, *plus* JustSearch)** measured **+0.00 accuracy / roughly 8% token savings** in this tempdoc's
> own preliminary data (§Open uncertainties, `624:1172-1216`; the As-built #4 live A/B/C test: "addition B
> +0.00 acc / −2.1k tokens" vs "substitution C +0.20 acc / −10.4k tokens"). This tempdoc's own §Open
> uncertainties already names the consequence directly as **U0** — *"does the honest number help the
> wedge?"* — and flags that if the realistic effect is genuinely small, the right move may be to **re-frame
> the claim, not measure harder**. A sibling tempdoc, **667** (worktree `claude-science-benchmark-release`,
> not yet merged — `docs/tempdocs/667-benchmark-release-external-baselines-and-research-md.md`), independently
> caught the same conflation in a first draft of the repo's public `RESEARCH.md` and corrected it; the
> current public `RESEARCH.md` (repo root, that worktree) states the +0.00/~8% realistic figure and the
> +0.20/~40% substitution figure side-by-side, honestly, and frames U0 as the open research question a grant
> would fund resolving — not a result to be flattered. **This plan's job is to design the measurement that
> answers U0 honestly, and its claim framing (§M.7-M.8) must not say anything rosier than what `RESEARCH.md`
> already states publicly.** §U0 below makes this the plan's first-class driver, not a footnote.

## U0 — resolved as this plan's first-class design driver (not a footnote)

**The question this whole plan exists to answer, stated plainly:** does JustSearch's retrieval measurably
help an agent that *already has* competent file tools (the realistic deployment — condition B), or does the
honest, properly-powered number come back near-null? Every design choice below is organized around
answering this **honestly**, not around finding a configuration that flatters the substitution arm (C)
instead. If a well-designed measurement finds B is genuinely ~null, **that is a product finding, not a
methodology failure** — the response is to re-scope the claim (§M.11) or feed the finding back into product
work (tool-surface design, §M.11), not to quietly re-center the substitution arm as the headline.

**(a) Design the battlefield where generic file tools should structurally fail.** A small, clean-text,
single-language corpus (this plan's original §M.2 draft — `needle-burial-v1` + a German sibling, ~280 docs
each) is close to the worst possible design for resolving U0: 635's own live re-run on exactly this shape of
corpus found **"a read-and-reason agent... bridges *some* synonyms and never mis-extracts"** at small scale
— i.e., a capable agent's ability to just *read everything* erodes the very asymmetry retrieval is supposed
to exploit. §M.2 is revised below to make four axes *load-bearing stress-tests of U0*, not deferred nice-
to-haves: **corpus scale** (large enough that whole-corpus reading is infeasible — hundreds to low
thousands of docs, matching an actual personal/team-file deployment, not a demo-sized set), **degraded-scan
content** (documents legible to a purpose-built extraction pipeline but not to a casual multimodal read —
**corrected by the §Confidence-pass (fifth pass) below**: a simple rendered scan is *not* a categorical
block, Claude's own `Read` tool reads it via vision trivially; the real, empirically-confirmed asymmetry is a
**degradation level tuned to defeat casual vision-reading while still surviving a real extraction pipeline**,
which is a genuine tuning problem, not "render text to an image"), **semantic/non-keyword questions** (already
planned — paraphrased, zero-lexical-overlap queries), and **multilingual content** (already planned — the
German sibling; Hard Invariant #6's own showcase). If JustSearch doesn't win on this harder battlefield, §M.7
attack #3 was too easy to answer at the smaller scale and this plan's job is to say so, not soften the test.

**(b) The contingency branch is not optional — see §M.11.** This plan must state, in advance, what claim
is honestly available if B's effect stays at or near +0.00 even on the harder battlefield: a token/cost
claim conditioned on *scale* (not the flat "~8%" from a toy corpus), a **capability-coverage** claim (what
fraction of queries were *only* answerable via retrieval, as a distribution rather than an aggregate delta),
or an honest null with product implications. §M.11 works this out; it is not a fallback appended after a
disappointing run, it is designed now, before the run, so the write-up cannot quietly discard it if the
result is not the more publishable one.

**(c) What could move the real number is a product question, not just a measurement question** — see
§M.11's discussion of tempdoc 655 (MCP conformance/capability policy) as the correct home for any tool-
surface change this measurement's failure analysis motivates.

## M.0 — What this plan reuses verbatim vs. what it decides

Everything below is either (a) already-built machinery this plan conforms to without change, or (b) a
genuine open decision this plan resolves or explicitly defers to the founder. Nothing here proposes new
architecture — the §As-built passes above already built the record shape, the run-governance, the judge,
and the corpus-certification gates. The gap between "machinery exists" and "a credible number exists" is
entirely: (i) two small bug fixes, (ii) one corpus decision, (iii) one small new piece (judge human
calibration), and (iv) actually spending the run at adequate n/seeds through the governance path that
already exists but has never yet been used end-to-end without a live failure.

**Reused verbatim (verified live in this pass, current on `main`):**
- `agent_manifest.py` — `build_agent_manifest`/`agent_cohort_key`/`pairing_key`/`mcp_tool_surface_hash`/
  `judge_identity` (`agent_manifest.py:36-179`).
- `utility_comparison.py` — `compose_utility` (the record composer; unchanged, cousin-not-sibling of 623).
- `compare_runs.py` — `compare()` (metric-parametric, bootstrap CI + Cohen's d_z) + `mcnemar()`.
- `utility_governance.py` — `compute_loss_accounting` + `paired_comparability` (per-arm `comparability`,
  reused from `comparability.determine_comparability`, plus the exclusion-Jaccard + paired-n-retention
  asymmetry check) → the record's `confidence_tier` is derived, never hand-set.
- `utility_calibrate.py` — `check_readiness` / `pin_config_cohort_key` / `calibrate_timeout` (target-
  concurrency pilot) / `closed_book_filter` (`utility_calibrate.py:29-133`).
- `agent_utility_inspect.py` — the Inspect-AI `eval_set` executor (cell = sample id, seed = epoch, cohort =
  task-args) — empirically verified against the real library (Confidence-pass #2, A1-A5).
- `utility_judge.py` — hybrid EM-auto-pass → local Qwen3.5-9B judge (different model family than the
  claude agent under test), dual-order, abstain-on-disagreement; agreement 0.90 measured on the floor logs.
- `corpus_identity.py` (`corpus_signature` — the one identity: `sha256(corpus.jsonl + qrels/test.tsv)`,
  shared by metadata/release/agent record since 635's Issue-1 unification), `corpus_certify.py` (memory-axis
  gate), `corpus_fidelity.py` (retrieval-difficulty-axis gate: nDCG band + shortcut-leak probe),
  `corpus_generate.py` (the fabricated-world generator, now regeneration-determinism-verified per 664).

**Decided in this plan (§M.1-M.9 below):** the baseline-arm spec and its two bug fixes; which corpus/suite
member(s) constitute the v1 battlefield and the blocking prerequisite on them; how contamination/identity
compose; the judge-calibration gap; n/seed targets; the reproducibility artifact shape (confirmed, not
changed); the exact claim sentence and its red-team; the concrete credibility bar; and the explicit
founder-decision list.

## M.1 — Baseline fairness (decision area 1)

**Condition A, as currently implemented, is already a materially stronger baseline than "grep":**
`agent_retrieval_eval.py:942-945` / `agent_utility_inspect.py:~60-67` run condition A with an isolated temp
cwd, `--add-dir <corpus_dir>`, an empty `--strict-mcp-config` (no MCP tools at all), and
`--permission-mode bypassPermissions` — which leaves the agent with Claude Code's **full default toolset
minus MCP**: `Read`, `Grep`, `Glob`, **`Bash`**, `Edit`, `Write`, `WebFetch`, `WebSearch`. This is not a
literal-grep strawman — a haiku agent with `Bash` can run `rg`, `find -exec`, `awk`, or a one-line Python
script, which is a fair rendering of "a competent agent with file-exploration tools." **Keep this
configuration as the baseline arm**; do not weaken it.

**Two real defects found in this pass that must be fixed before the arm is defensible externally:**

1. **WebFetch/WebSearch are not disallowed in *any* condition** — confirmed by grep: zero
   `disallowedTools`/`allowedTools` lines reference either tool anywhere in `agent_retrieval_eval.py` or
   `agent_utility_inspect.py`. On a corpus with any correlate on the public web, condition A (and B) can
   silently "cheat" via a live internet lookup instead of grounding in the corpus — the mirror-image of the
   memorization-contamination problem this whole tempdoc exists to fix, and one this tempdoc's own prior
   passes never flagged. It is moot for a fully fabricated, unsearchable-entity corpus (needle-burial-v1,
   635-self-demo-v1) but must be closed structurally, not left to corpus-luck. **Fix:** add
   `WebFetch,WebSearch` to every condition's `--disallowedTools`, and — because a hostile reviewer will ask
   "how do you know it didn't just search the web" — assert it from data, not config: the harness already
   parses a `tool_calls` list per query (`agent_retrieval_eval.py:969`); add a per-run assertion (surfaced
   into the record's honesty fields) that zero `WebFetch`/`WebSearch` invocations occurred in any condition.
2. **Condition C's `--disallowedTools Read,Grep,Glob` does not disallow `Bash`**, so the "JustSearch-only"
   substitution arm can still read files via shell — already observed live in this tempdoc's own floor run
   (§Throughput: "condition C disables only Read/Grep/Glob so it fell back to Bash cat"). The arm's own
   premise ("no native file tools") is therefore not actually enforced today. **Fix:** extend condition C's
   `--disallowedTools` to include `Bash`, and reuse the same `tool_calls` assertion to confirm zero
   non-MCP file-access calls occurred in C.
3. **[Found by the §Confidence-pass below, not in the original pass]** A blocked tool can be routed around by
   spawning a **subagent** with its own independent toolset — a live probe found that disallowing
   `WebSearch` alone let the agent invoke the `Agent`/`Task` tool to launch a general-purpose subagent running
   a personal `deep-research` skill, which then pursued the same blocked capability indirectly (and looped
   long enough to time out the probe). **Fix:** add `Agent,Task` to every condition's `--disallowedTools`
   alongside `WebFetch,WebSearch`(,`Bash` for C) — confirmed live in this pass that with all five disallowed,
   the agent used only `Glob`/`Read` and answered correctly from the local file, even when the prompt
   explicitly tempted it toward a URL fetch or a shell command.
4. **[Found by the §Confidence-pass below]** The harness's own "isolated" claim (`agent_retrieval_eval.py`'s
   docstring: "the agent sees no project-specific config — just a vanilla Claude Code instance") describes
   avoiding **repo-level** `CLAUDE.md` contamination via a fresh temp cwd — it does **not** establish that the
   agent is also isolated from the **operator's own user-level** `~/.claude` skills/plugins/slash-commands,
   which are session-global, not cwd-scoped. This pass's own probe was contaminated exactly this way (a
   personal `deep-research` skill fired unprompted). Two different operators running "the same" eval could
   get systematically different agent behavior depending on what's registered in their own global Claude Code
   config — a reproducibility/fairness risk this tempdoc had not previously named. **Fix (or at minimum,
   document):** either verify the real harness's isolation mechanism also isolates user-level config (not
   just repo-level), or record in the record's honesty fields which operator-config surface the run assumes
   is minimal/vanilla, so a re-run by a different operator can check whether that assumption holds for them.

**Arm selection.** Keep exactly two arms in the *headline* comparison:
- **A — file-tools-only** (fixed above): the baseline.
- **B — file-tools + JustSearch MCP** ("addition"): per tempdoc 654's Direction note, the product is
  consumed by an agent that *already has* file tools and adds JustSearch via MCP — B is the deployment-
  realistic scenario, not C. This is also 624's own prior finding (§C-4): C answers "if you replace your
  file tools," which no real deployment does.
- **C — JustSearch-only** (substitution) stays a **secondary, diagnostic** arm (useful for the retrieval-
  ceiling/profile work 635 already does), reported but never headlined as "the" agent-utility number, per
  §C-4's standing finding.

**A fourth "naive keyword backend" arm does not earn its cost.** Condition A already has `Bash`-mediated
`ripgrep`/`find`/`grep` access, so a separate keyword-only arm would be largely redundant with what A can
already do; if a keyword-vs-semantic split is later wanted, derive it as a diagnostic projection over A's
own transcripts (did the agent already reach for a keyword tool, and did it succeed?) rather than adding a
fourth run condition and its own seed/cost multiplier.

**Model tier:** haiku for every arm in v1, per the binding jseval skill policy (2026-06-23 — "run agentic
benchmarks on cheap models only for now... every eval command already defaults to `--model haiku`... higher
batch on the cheap model is fine, encouraged even... multi-tier sweeps require explicit user budget
sign-off"). Using the same tier across A/B/C is also a fairness requirement in its own right — a
tier-mismatched comparison would confound the condition delta with a model-capability delta. Multi-tier
scope is a founder decision (§M.9 U-Founder-3), not decided here.

## M.2 — Battlefield: corpus/task composition (decision area 2)

**Fixed by the brief:** heterogeneous personal/team documents — mixed formats, multilingual, OCR'd scans —
explicitly not code navigation.

**What currently exists, mapped against that requirement:**
- **`635-self-demo-v1` suite (4 members: prose/code/tabular/German).** Per 635's own 2026-06-24 As-built,
  only `synth-multihop-prose-v2` is a credible retrieval *ceiling* (paraphrase queries defeat lexical 0.13,
  dense rescues to 0.70+ on the default-on engine). The **code, tabular, and German members are
  grep-trivial** (nDCG@10 = 1.0 under the fidelity gate's own head-only qrels) because their queries name
  the target entity **verbatim** — 635 explicitly diagnosed this as unfixable without a query-semantics
  redesign ("there is no paraphrase for an exact function/entity name... deferred"). **Decision: do not use
  the code/tabular/German members of this suite for the agent-utility number** — a corpus where both arms
  score ~100% by construction produces zero signal, and using it would itself be the "cherry-picked easy
  corpus" attack (§M.7 #3) made real. They remain valid for the retrieval-quality *profile* (their original
  purpose), just not for this measurement.
- **`golden/needle-burial-v1`** (636/664; 280 docs, 20 queries, English, 1-hop, synonym-bridged paraphrase)
  is this tempdoc's own last-identified "viable vehicle" (§Live-run attempt, 2026-06-25): its 2-hop siblings
  were too hard for a haiku agent (0/3 despite 20+ turns — a model-reliability ceiling, not a retrieval
  failure), but the 1-hop member succeeded on a small pilot (3/4) and the retrieval-side semantic bridge is
  independently verified (rank-1 recovery on grep-defeating paraphrases, both in 635's re-run and 636's
  engine fix). **This is the strongest current candidate for the v1 battlefield's English member.**
- **Multilingual / OCR'd / mixed-format requirement — not yet met by anything that exists.** No current
  corpus combines an *agent-QA* answer field (required for the agent-utility record) with genuine OCR noise
  or format heterogeneity; the OHR-Bench corpora (`mixed/ohr-bench-*`) have real OCR noise but are
  public/contaminated retrieval-quality corpora with no answer field, and are not certified for agent-eval
  use. Building a genuinely representative "mixed-format, OCR'd, multilingual personal files" battlefield is
  new construction work, not reuse — and 635's own history (four review passes, multiple dead ends, before
  even one clean *English* corpus was both contamination-free and non-trivial) is direct evidence that this
  is a multi-session research-grade effort, not a quick extension.

**Decision for v1, revised per §U0 — scale and OCR-only content are stress-tests, not deferred extras.**
`golden/needle-burial-v1`'s *generator* (`corpus_generate.py`) is the right machinery to reuse — chain-first
multi-hop, hard-negative distractors at a tunable ratio, a `lang` knob already exercised for
`synth-multiling-de-v1` — but its **committed scale (280 docs)** and **clean-text-only content** are exactly
the two properties §U0(a) identifies as the weakest setup for resolving whether B's effect is real. The v1
battlefield should therefore be:
1. **A scaled-up English member** — generate at a size where a file-reading agent cannot plausibly read the
   whole corpus in a session (hundreds to low-thousands of docs; the generator's `n_chains`/`doc_words`
   parameters already control this, per 664's regeneration-determinism work re-deriving them from doc counts
   — no new mechanism, a parameter choice). This directly answers the confound 635's own live re-run
   diagnosed ("a read-and-reason agent... at this corpus size").
2. **A generated German sibling at the same scale** (`lang=de` + `semantic=True`) — the multilingual stress
   test, reusing the same generator knob already proven for `synth-multiling-de-v1`.
3. **An OCR-only-accessible member — genuinely new construction, not deferred.** Per §U0(a), a document a
   file-reading agent cannot read at all without going through the ingest/extraction pipeline is the one
   condition where "read the files instead" is not a fair fight, and is the strongest possible test of
   whether JustSearch's structural advantage is real. Concretely: render a subset of the generator's
   fabricated documents to synthetic scanned-page images (the same technique OHR-Bench-style corpora already
   use, but reusable here because the *fabricated content* stays contamination-free even though the
   *rendering* borrows a public rendering method) and route them through the existing VLM/Tika extraction
   path at ingest time; a file-reading agent given the same folder sees unreadable image files (or, if
   `Read` degrades to garbage/binary, an unusable answer), while JustSearch's ingest pipeline already
   extracts and indexes them. This is real, non-trivial construction work (closer in cost to 635's original
   multi-pass corpus-build effort than to a parameter tweak) — named explicitly rather than silently dropped,
   with the founder decision below.

**Founder decision this revision surfaces (§M.9 U-Founder-1, sharpened):** the honest choice is between (a)
building all three members above before any v1 run — the battlefield §U0 actually calls for, at real
construction cost and calendar time — or (b) running the scaled-text + multilingual members first (cheaper,
still a real improvement over the original 280-doc/clean-text-only draft) and treating the OCR-only member
as an explicitly-named, not-yet-built stress test, with the v1 claim text stating that omission plainly
(§M.7/§M.8) rather than implying the full "heterogeneous... OCR'd scans" battlefield was tested. This plan
recommends (b) only as a sequencing choice, not a scope reduction — the OCR member should still ship before
any claim is treated as answering U0 completely, since it is the condition where the answer is least
deniable if JustSearch wins and most informative if it doesn't.

**A blocking prerequisite this plan surfaces, not previously flagged as blocking:** 664's twelfth pass
measured the descriptor-collision check **FAILING on all 5 procedurally-generated corpora**, including
`needle-burial-v1` (24 colliding groups / 51 of 280 docs / **7 of the 20 gold chains** share a descriptor
with a distractor) — logged by 664 as "not in this item's scope to fix (a deeper generator-logic change),
left as-is." For a retrieval-quality profile that is a tolerable caveat; for **this** measurement it is not:
a third of the gold set has a distractor document a hostile reviewer can point to as indistinguishable
from (or literally answering) the "gold" query, which directly corrupts the paired accuracy metric this
plan's headline depends on (§M.7 attack #2). **This plan promotes that fix from a named-but-deferred defect
to a blocking prerequisite** for the v1 corpus (§M.10 step 2) — either fix the generator's descriptor pool
to guarantee non-collision, or filter the 7 affected gold chains from the query set before the certified-n
run (the cheaper interim option, at the cost of a smaller n).

## M.3 — Contamination and corpus identity (decision area 3)

*(§T.1 below has the settled design for the descriptor-collision fix this section's corpus-certification
requirement depends on — read it before implementing this section's gates.)*

Reuse verbatim, no new structure needed:
- **One identity**: `corpus_identity.corpus_signature()` — shared by the corpus's `metadata.json`, any
  retrieval-quality release, and the agent-utility record (unified since 635's Issue-1 fix; verify it is
  still one signature across all three for the v1 corpus before the real spend, since a fork here would
  quietly reintroduce the exact defect 635 fixed once already).
- **Two certification gates** (635 D.5 + suite as-built): the **memory axis** (`corpus_certify.py`,
  closed-book claude-CLI probe — must show ≈0 accuracy) and the **fidelity axis** (`corpus_fidelity.py`,
  stack-bound nDCG band + shortcut-leak probe — must reject trivial or broken corpora). Both must PASS,
  plus the (664) **descriptor-collision** and **regeneration-determinism** checks, before a corpus is used
  for this measurement — the collision check currently FAILS (§M.2) and is the blocking item.
- **`contamination_class`** (`private-synthetic` for both the English and German members) is recorded on
  the corpus metadata **and** propagated into `agent_cohort_key` via `agent_manifest.build_agent_manifest`,
  so the record self-declares its contamination posture rather than requiring a reader to trust prose.

**Decision: synthesize (generate), not regenerate-from-public or assemble-from-private.** This continues
635/636's already-resolved direction: fabricated facts have no decay/treadmill cost (635 §C-4), and a
synthetic-shareable corpus is the one point in the design space that satisfies both contamination-resistance
and internal reproducibility (635 §C-3) without needing a stranger to fetch private files. Post-cutoff public
is rejected on the same maintenance-budget grounds 635 already established (no CI staffing for a refresh
treadmill, ADR-0026). Nothing new to decide here beyond confirming the already-settled 635 direction still
holds — it does.

## M.4 — Judge protocol (decision area 4)

**Already built and reused verbatim:** hybrid EM-auto-pass → local Qwen3.5-9B judge (a different model
family from the haiku agent under test — the self-preference control), dual-order (cancels position bias),
abstain-to-EM on disagreement (`utility_judge.py`). Measured: EM-vs-judge agreement 0.90 on the committed
floor logs (As-built #4).

**Gap this plan closes:** the original brief asked for "calibration against human-labeled samples" — this
was never built. Plan (revised per §T.3/§External-research-pass — see there for the settled design and its
citations): draw a stratified sample of judge verdicts (**n=30-50**, oversampling EM-disagreement cases
specifically, since that is where judge error concentrates) and have **≥2 independent human raters** (not
one) label each item correct/incorrect; compute both **judge-vs-human-majority agreement** and
**human-vs-human inter-rater agreement** (Cohen's kappa for exactly 2 raters, Krippendorff's alpha for 3+) on
the *same* sample, and report both — the human-vs-human figure is the natural-disagreement baseline the
judge's own agreement should be read against, not a bare number in isolation. **Report the kappa/alpha
alongside its own confidence interval (bootstrap or the standard analytic formula), not as a bare point
estimate** — at n=30-50 the estimate itself is imprecise, and the write-up must show that imprecision rather
than let a single kappa number read as more certain than it is; treat it as a directional reliability signal
at this n, not a pass/fail gate (a tighter estimate is a real cost/rigor trade-off, §M.9). This is small, new,
and — per the brief's own wording — not optional: until it exists, "0.90 agreement" is a self-consistency
statistic (judge agrees with the cheap fallback), not a validated accuracy figure against ground truth. Who
performs the human labeling (and whether a second independent rater is feasible) is a founder decision
(§M.9 U-Founder-4).

**Disagreement reporting:** already structurally present (the overlay records agreement rate + abstentions);
extend with the new kappa/alpha + CI fields, not a new record.

## M.5 — Statistical adequacy (decision area 5)

- **n:** 624's own power analysis (Confidence-pass U9 on the MultiHop-RAG floor; 635's U9 on the same data)
  found that the observed ~5pp accuracy effect needs **~390 paired queries for 80% power**, ballooning past
  1000+ if the true effect is smaller — an accuracy-adequate n is not affordable at v1 scope. **Do not
  chase accuracy significance as the v1 target.** Size n instead for the **token-efficiency** claim, which
  was already significant (bootstrap CI95 excludes 0) at n=100 on the MultiHop-RAG floor. **Recommend
  n≈100-150 paired queries** (bounded by the German sibling's likely smaller query count) as the v1 floor,
  reporting accuracy directionally with its real (likely non-significant) McNemar p — never suppressed or
  reframed to imply significance it lacks.
- **Seeds:** ≥5 (the community-consensus floor 624 cites, and the figure already used in 624's own R6 cost
  envelope and 664/635's regeneration-determinism work). Report as a per-cell envelope (mean ± calibrated
  stdev), the same ±2σ concept `calibrate.py` already generalizes to agent seeds.
- **Multi-model scope:** haiku-only for v1 (binding cost policy, §M.1); out of scope pending a founder
  budget call (§M.9 U-Founder-3).
- **Accuracy/cost separation:** already structurally enforced by `compose_utility`'s shape — McNemar for
  the binary accuracy metric, bootstrap CI + Cohen's d_z for the continuous cost/token metrics via
  `compare_runs.compare`. No new statistical machinery is needed; the discipline required is in the
  write-up (§M.7), not the code: report both with their own significance test, never one blended sentence.

## M.6 — Reproducibility (decision area 6)

No new record shape needed — `utility-comparison.v1` (this tempdoc's own D.2/D.3, reaffirmed as a *cousin*
of 623's release object, not a sibling) already carries `agent_cohort_key`/`pairing_key` (axis-relative
identity: everything fixed except `condition`), the run-governance fields (readiness, loss-accounting,
paired-comparability, a *derived* `confidence_tier`), corpus identity, judge identity, and cited external
baselines (`self_reproduced:false`). Execute through Inspect AI's `eval_set`
(`agent_utility_inspect.py`) — durable per-cell resume, adaptive concurrency, `sample_id` = cell identity,
`epochs` = seed, task-args = cohort identity — already built and empirically verified against the real
library (Confidence-pass #2). **Re-verify the MCP tool-surface hash before the real run**:
`McpToolSurface.java`'s `KNOWN_TOOLS` is now **6** tools (`justsearch_answer`, `justsearch_search`,
`justsearch_browse`, `justsearch_ingest`, `justsearch_status`, `justsearch_runtime_manifest`) — one more
than the "five eval-informed tools" this tempdoc's own R8 confidence-pass cited; `mcp_tool_surface_hash`
must be recomputed against the current surface, not assumed from a ~10-day-old citation (a small, concrete
instance of `tempdocs-are-dated-history` applied to this tempdoc's own prior passes).

No public-facing rendering (a model-card page, a benchmark table) is built as part of this plan — that is
a distinct future consumer, correctly out of scope per this tempdoc's own D.4.

## M.7 — Claim framing and red-team (decision area 7)

**Candidate v1 claim sentence** (pending the founder's sign-off on the bar in §M.8):

> "On a held-out, closed-book-certified, contamination-free corpus of buried-fact retrieval queries
> (English + German, synonym-bridged paraphrases with zero lexical overlap to their evidence), an agent
> with JustSearch's MCP retrieval added to its existing file tools (Read/Grep/Glob/Bash) uses **[X]% fewer
> unique tokens** to answer (bootstrap CI95 [...], n=[...], ≥5 seeds) than the same agent with file tools
> alone; accuracy moved by **[Δ]** (McNemar p=[...])."

This leads with token-efficiency (the metric with an actual chance of surviving at affordable n), states
the real accuracy delta and its real (likely non-significant) p-value rather than omitting it, and states
the corpus scope narrowly (buried-fact paraphrase retrieval, not "heterogeneous personal files" generally).

**Red-team — attack, and whether the design (as of this plan) answers it:**

| # | Attack | Answered? |
|---|---|---|
| 1 | "Your baseline is a strawman" | **Yes**, once §M.1's WebFetch/WebSearch fix lands — condition A has full shell access, not literal grep. Until then, the *inverse* attack (#1b below) is open. |
| 1b | "Your baseline could cheat via a live web search" | **Not yet** — this is the WebFetch/WebSearch gap this plan found; open until fixed and the zero-web-call assertion is verified on a real run. |
| 2 | "Your corpus is contaminated" | **Partially** — closed-book gate passes today; **not fully answered until the descriptor-collision fix (§M.2/M.3) lands**, since a reviewer inspecting `docs.jsonl` today would find real gold/distractor collisions on 7/20 chains. |
| 3 | "You cherry-picked an easy corpus" | **Partially** — the fidelity gate exists specifically to reject trivial corpora, and 3 of 4 `635-self-demo-v1` members were in fact rejected by the project's own gate (a genuine credibility signal). **Not answered for breadth** — v1's scope is narrowly English+German buried-fact retrieval, not the full "heterogeneous personal files" claim; the claim text must say so. |
| 4 | "Judge bias / self-preference" | **Partially** — different model family + dual-order is built and measured (0.90 self-agreement); **not answered against ground truth until the human-calibration kappa (§M.4) is computed.** |
| 5 | "Seed luck / single-run fluke" | **Yes by design** (≥5 seeds, envelope, McNemar over the full paired set) — contingent on actually running at that count, which has not yet happened. |
| 6 | "Why not compare against X (cloud RAG, keyword-only, a bigger model)" | **Answered for cited baselines** (FRAMES/BFCL-V4/CodeScaleBench/PHMForge/A-RAG, recorded `self_reproduced:false` — honest reference points, not re-run); **a keyword-only arm was considered and rejected** (§M.1 — redundant with condition A's own Bash access); **multi-model-tier is explicitly out of v1 scope**, a founder budget call. |
| 7 | "The realistic arm shows ~0 accuracy gain — overselling" | **Answered by the claim text itself** (states B's real, possibly-null accuracy delta and its real p-value, leads with token-efficiency) — the residual risk is a later marketing draft dropping this framing, a process/discipline risk, not a design gap. |
| 8 | "n is too small for the accuracy claim" | **Answered by not claiming accuracy significance** as the headline; the token-efficiency claim's n-adequacy is real and will be re-verified on the new corpus. |

Where the table says "not yet/partially," that is the honest state of the design today, not a hidden gap —
attacks 1b, 2 (until the collision fix), 3 (breadth), and 4 (until human-calibration lands) are the concrete
items §M.10 must close before publication. **Whatever the run finds for condition B, this claim text (and
any future revision of it) must not say anything rosier than the current public `RESEARCH.md`** (tempdoc 667,
worktree `claude-science-benchmark-release`) already states — that document already discloses the +0.00/~8%
realistic figure vs. the +0.20/~40% substitution figure side-by-side and frames U0 as the open research
question. A future v1 result that is *more* favorable than today's preliminary data is good news to report
honestly with its own new evidence; a future draft that quietly reverts to headlining condition C would be
the exact regression tempdoc 667's own critical-analysis pass already caught and fixed once.

## M.7a — Contingency claims if the realistic arm (B) stays near-null (§U0's resolution, designed in advance)

Per §U0(b), this is designed now, not appended after a disappointing run. If, even on the harder battlefield
in §M.2 (real scale, OCR-only content, multilingual), condition B's accuracy delta stays at or near +0.00
with a non-significant McNemar p, three honestly-available claim shapes exist — evaluated here so the
write-up cannot silently pick the most flattering one after the fact:

1. **Token/cost-at-scale, not the flat "~8%" toy-corpus figure.** 624's own D-1 finding (token-efficiency is
   contamination-robust) still holds regardless of the accuracy outcome — but the *magnitude* on a toy-sized
   corpus (~8%) is a weak number precisely because a small corpus lets a file-reading agent avoid burning
   many tokens too. On the real-scale corpus §M.2 now calls for, the token/cost gap between "read enough of
   a thousand-document corpus to find the answer" and "retrieve the relevant passage" should widen
   substantially — this is a **testable, not asserted**, prediction: report the scale-conditioned token/cost
   delta with its own bootstrap CI, and if it does *not* widen at scale, say that plainly too (it would be a
   second genuine finding, not a failure of this plan).
2. **Capability-coverage, not an aggregate accuracy delta.** An aggregate ~0 accuracy delta can hide a real
   effect on a *subset* of queries — e.g., queries requiring the OCR-only or cross-lingual content, where
   file-tools structurally cannot compete, vs. queries any competent agent answers equally either way. Report
   the **fraction of queries condition A got wrong that condition B got right** (and vice versa — the
   McNemar discordant-pair breakdown `compare_runs.mcnemar()` already computes) **stratified by battlefield
   dimension** (scale bucket, OCR-only vs. text, language) rather than only as one pooled number. This
   reuses existing machinery (the discordant-pair counts are already computed for the McNemar test; this is
   a reporting cut, not new statistics) and is the more informative claim if the pooled delta is null: "no
   aggregate effect, but a real effect concentrated in [specific structural conditions]" is a defensible,
   interesting, non-oversold finding.
3. **An honest null, with the product-side follow-up named, not hidden.** If neither (1) nor (2) shows a
   real effect anywhere, the honest claim is that a properly-powered measurement found no realistic-arm
   benefit at v1's battlefield scope — exactly the outcome `RESEARCH.md`/667 already names as a live
   possibility. In that case, the useful next step is not to re-run the same design hoping for a different
   answer (§U0's own warning against "measure harder" as a substitute for "re-frame") but to feed the failure
   analysis into **why** — which queries B lost or drew on, and whether a **tool-surface** change would
   plausibly help. That question belongs to tempdoc **655** (MCP conformance and capability policy), which
   already owns the tool-surface's design/certification layer and explicitly warns against ad hoc lifecycle-
   tool additions motivated by a single report ("do not start by adding `justsearch_delete`, `justsearch_
   reindex`... without capability/default-deny semantics first," `655:35-37`). Any product lever this
   measurement's failure analysis suggests — e.g., better result-explain/orientation surfaces so an agent
   trusts and uses retrieved context instead of falling back to reading everything, in the spirit of the
   `justsearch_status`/`justsearch_runtime_manifest` orientation tools already shipped — should be scoped and
   reviewed through 655's conformance frame, not implemented as a one-off reaction to this eval's numbers.

## M.8 — The credibility bar (boundary a — concrete, proposed for founder sign-off)

The record stays internal until **all** of the following hold:
1. **Corpus:** descriptor-collision check passes (0 gold-affecting collisions) on every corpus used — via
   §T.1's construction-time exclusion, not a post-hoc filter of the current defect; fidelity gate passes
   (non-trivial, in-band nDCG, 0 shortcut leaks); memory gate passes (≈0 closed-book accuracy); the
   battlefield includes the real-scale and multilingual members at minimum (§M.2, generated per §T.1's
   descriptor-space design), with the OCR-only member (§T.2's axis-renderer design) either included or its
   absence stated plainly in the claim text (§M.2/§U0). Currently: memory gate passes on the existing small
   `needle-burial-v1`; collision check currently FAILS (blocking); the scaled/OCR members do not yet exist.
2. **Baseline:** WebFetch/WebSearch/**Agent/Task** disallowed (confirmed live in the §Confidence-pass this
   fifth item is necessary, not merely thorough — a blocked WebSearch was routed around via a spawned
   subagent) + a zero-web-call assertion holds on every cell actually run; condition C's Bash leak fixed (or C
   dropped from any published number entirely, kept diagnostic-only); the run's operator-level Claude Code
   config (skills/plugins) is confirmed minimal or explicitly recorded, since "isolated" only verifiably means
   repo-level `CLAUDE.md`-free, not global-config-free (§Confidence-pass finding).
3. **Judge:** the human-calibration kappa/alpha (§M.4/§T.3, dual-rater, reported with its own CI) is computed
   and reported — whatever the value; no minimum kappa/alpha threshold is proposed here (that is itself a
   founder call, §M.9).
4. **Statistics:** ≥5 seeds and n≥100 paired queries *actually completed* (not merely planned); token-
   efficiency CI excludes 0; accuracy reported with its real McNemar p, never reworded to imply significance
   it does not have.
5. **Reproducibility:** the record is cohort-identified (`agent_cohort_key`/`pairing_key` populated),
   `paired_comparability` returns `comparable=True` (no asymmetric-exclusion flag), and is rerunnable via
   `jseval utility-run --calibration` against the committed corpus at a stated CLI/model version.
6. **Claim text:** matches §M.7's red-teamed shape (states real p-values, leads with the significant metric,
   states scope/`coverage.does_not_measure` explicitly) — checked against this list before any doc, deck, or
   grant application quotes it.
7. **Per-stratum claims (§M.7a/§T.4) clear their own bar independently — they do not get a lower bar than
   §M.7a's item 6 because they look directionally nice.** Any capability-coverage or scale-conditioned claim
   promoted from the stratified projection into the write-up must carry its own CI/significance test computed
   on that stratum's own n, not borrow credibility from the pooled result. A favorable-looking stratum at an
   underpowered per-stratum n (a real risk once ~100-150 total queries are split across 3-4 strata — §M.5's
   n target was sized for the *pooled* claim, not for per-stratum power) is reported as directional/exploratory
   only, explicitly labeled as such, never promoted to a headline figure on its own. This item exists because
   §M.7a's stratified-reporting design, left undisciplined, is itself a new way to cherry-pick a favorable
   slice — the exact failure mode this whole plan otherwise guards against.

## M.9 — Explicit founder decisions (boundary c — not decided here)

- **U-Founder-1 (corpus breadth/sequencing, sharpened by §U0):** build the real-scale English + German
  members first and treat the OCR-only member as an explicitly-named follow-up (recommended sequencing,
  §M.2) — or hold the v1 run entirely until all three (scale, multilingual, OCR-only) exist? The OCR-only
  member is genuinely new, non-trivial construction (closer to 635's original multi-pass corpus-build effort
  than a parameter tweak); the founder call is timeline/cost, not whether it's worth building — §U0 argues it
  is the condition where the answer is least deniable either way.
- **U-Founder-2 (run budget):** confirm spend for the calibrated 5-seed/n≥100 haiku run at the *real* battlefield
  scale this revision now calls for (hundreds-to-low-thousands of docs, not the original 280-doc draft) — this
  is larger than 624/635's ~$50-150 small-corpus envelopes (§M.5/§M.2); a fresh cost estimate against the
  actual chosen scale should be produced before this line item is signed off, not assumed from the smaller
  corpus's historical numbers. **The run's dollar cost is not the whole cost** — §T.1-T.4 add real
  *engineering* work ahead of the run (the collision fix + conditional descriptor-space expansion, the OCR
  axis renderer, the dual-rater judge-calibration protocol, the stratified-coverage reporting code), each
  individually small but collectively a real, multi-item lift beyond "run the existing harness longer." The
  founder should weigh this alongside the run's own $ cost, not evaluate them separately — this plan does not
  produce a combined estimate (that needs a real engineering-time assessment this planning pass isn't scoped
  to make), and flags that gap explicitly rather than implying the run's cost is the only number that matters.
- **U-Founder-3 (model-tier scope):** stay haiku-only for v1 (recommended, matches the binding cost
  policy), or fund a sonnet/opus tier sweep?
- **U-Founder-4 (judge calibration reviewer):** should the founder personally label the ~30-50 sample
  (§M.4), or is a second-agent/independent-reviewer label acceptable? Is there a minimum judge/human kappa
  the founder wants as a hard gate, or is "report whatever kappa results" sufficient (this plan's default)?
- **U-Founder-5 (timeline):** is there an external deadline (this tempdoc's own §U-A4 flagged a possible
  grant/application-driven timeline) forcing a faster sequence than §M.10 below assumes?
- **U-Founder-6 (`research-channel/plan.md`):** confirmed present in this repo at
  `docs/business/research-channel/plan.md` — no sidecar lookup was needed for this pass. Say so explicitly
  if a re-read of its current decision-1 status is wanted before the run in §M.10 begins.

## M.10 — Recommended execution sequence (order only — not authorization to start any step)

1. Resolve §M.9 U-Founder-1's sequencing call (scale+multilingual now / OCR-only later, or all three
   up front) — everything below assumes the recommended sequencing.
2. Fix the descriptor-collision generator defect per **§T.1's settled design** (gold-reserved exclusion in
   the distractor draw, first; the combinatorial descriptor-space expansion only if the chosen real scale
   requires it) — promoted from 664's deferred item to a blocking prerequisite for every generated corpus
   this plan uses (§M.2/M.3).
3. Fix the two condition-A/C tool-allow-list defects (§M.1) — small, isolated code change.
4. Generate + certify the real-scale English member and the German sibling at the same scale
   (`corpus_generate.py`'s existing `n_chains`/`doc_words`/`lang=de`/`semantic=True` knobs — parameter
   choices, not new machinery) through the certify+fidelity dual gate (§M.2/M.3), on top of step 2's fix.
5. Construct the OCR-only member per **§T.2's settled design** (a new `axis="scan"` renderer; §M.2 item 3) —
   genuinely new work; timing per U-Founder-1's sequencing decision, but not skipped.
6. Build the judge human-calibration step per **§T.3's settled design** (dual-rater, judge-vs-human-majority
   **and** human-vs-human agreement, both with a reported CI) — small, new; needs a founder decision on who
   labels (§M.9 U-Founder-4), including whether a second independent rater is feasible.
7. Run `jseval utility-calibrate` (readiness + target-concurrency timeout pilot + cohort-key pin) **then**
   `jseval utility-run --calibration` at the calibrated concurrency — this tempdoc's own explicit, twice-
   learned lesson ("run the eval through `utility-calibrate`, do not hand-set concurrency") — gated on
   §M.9 U-Founder-2/3's budget sign-off (re-costed at real scale, not the small-corpus envelope).
8. Compose + judge + report per §M.7a's three contingency shapes (token/cost-at-scale, stratified
   capability-coverage per **§T.4's settled design**, honest null) — do not pick only the most flattering one
   after seeing the result, and hold every per-stratum claim to §M.8 item 7's own significance bar.
9. Check the result against every item in §M.8 (all seven), including the per-stratum discipline in item 7;
   if any item fails, iterate or explicitly narrow the claim text — do not publish until the bar clears, and
   do not publish anything rosier than `RESEARCH.md` (667) already states without new evidence to support it.
10. Update the search-quality register and this tempdoc with the final disposition (whatever it is).

**This plan authorizes none of the above.** It is the design the 2026-07-01 Assignment asked for; a
follow-up go/no-go on §M.9's founder decisions is the next step, not a continuation of this pass.

---

# Design theorization (2026-07-02) — long-term design for the plan's four remaining-work items

> General design only — no implementation, no code changes, no eval runs. This pass takes the four
> genuinely open items the methodology plan (§M above) surfaced — the descriptor-collision fix, the
> OCR-only corpus member, the judge human-calibration step, and stratified capability-coverage reporting —
> and asks the question the assignment poses: what is the *correct long-term design* for each, does
> existing machinery already cover it, and where does the answer conform to (rather than fork) a seam this
> codebase already has. Sourced from a direct read of `corpus_generate.py` (descriptor assignment, `:62-126`),
> `jseval/projections/{base,stratified_metrics,staged_recall_accounting}.py`, and the adjacent stubs 639
> (candidate-set integrity) and 646 (event-sourced tempdoc) — read in full for this pass, both short,
> purpose-only stubs with no implementation to reconcile against. 636 and 553 were not re-read in full;
> their load-bearing content is already file:line-verified across this tempdoc's and 635's/664's own prior
> passes, and this pass's targeted greps confirmed the one new fact it needed from each (636's
> `staged_recall_accounting.py` registration; 553's canonical-record framing already carried by every
> citing tempdoc). **Public-claims note (this repo is public):** nothing below describes a shipped
> capability — every item is stated as a design for future work, not a certification or compliance claim.

## T.1 — Descriptor-collision: a construction-time guarantee, not a better detector

**What exists today, read directly.** `corpus_generate.py:_sem_for` (`:115-126`) assigns each gold chain a
**deterministic, cycled** `(type, place)` pair — `types[idx % len(types)]` / `places[idx % len(places)]` —
but assigns each **distractor** an **independent uniform draw from the same pool** — `rng.choice(types),
rng.choice(places)` — with no exclusion of the gold-reserved combinations. This is the exact mechanism
behind 664's measured defect (7 of 20 gold chains in `needle-burial-v1` share a descriptor with a
distractor). 664's own fix was **detection**, not correction: `corpus_certify.descriptor_collision_report()`
finds and fails on collisions after generation, but the generator itself still produces them — 664 explicitly
scoped the generator fix out ("a deeper generator-logic change, left as-is").

**Why detection-only is the wrong long-term shape, and what already establishes the right one.** Tempdoc
635 §D.5 already settled this exact question for a *different* corpus property (contamination-resistance):
*"the strongest guarantee is construction-time provability... generate from a combinatorial/fabricated space
so instances were never in training by construction... closed-book is then a sanity check + self-generation
guard, not the primary guarantee."* Collision-freedom is structurally the same shape of property — and the
fix available here is *stronger* than contamination's, because it is fully mechanical: **exclude the
gold-reserved combinations from the distractor sampling pool.** `_sem_for`'s distractor branch should draw
from `pool - gold_reserved` (sampling without replacement, or rejection-sampling against the reserved set),
which makes a gold/distractor collision **structurally impossible**, not merely detected after the fact.
`descriptor_collision_report`'s exact-match check is then correctly demoted to what 635 D.5 already modeled:
a **regression sanity check** (did the exclusion logic actually run correctly this time), not the sole
proof of collision-freedom.

**The scale dimension this plan's own §M.2 revision introduces, and why it changes the design.** The fixed
`(type, place)` catalog is small — 12 types × 26 English places = 312 combinations (312 for German too, a
matched pool). §M.2's real-scale battlefield (hundreds–low-thousands of docs) can approach or exceed that
ceiling for `n_chains` alone, before any distractor draws — at which point *no* sampling discipline over a
312-slot table can avoid collisions, because the table itself runs out. The correct response, again
conforming to 635 D.5's already-cited pattern rather than a bespoke fix, is to make the descriptor space
**combinatorial rather than enumerated** — add an orthogonal, synonym-paired axis (e.g. a numbered/ordinal
qualifier with its own English/German synonym pair, following the same "doc surface phrase / query synonym"
shape already used for type and place) so the achievable non-colliding descriptor count scales
multiplicatively with corpus size instead of being capped at a hand-authored table. This is real, bounded
content-authoring work (a third synonym-pair table, one more renderer branch) — not a new mechanism.

**Design (in scope, matched to the problem, nothing more; item 2's "conditional" framing corrected by the
§Confidence-pass below — it is now measured as near-mandatory, not a hedge):**
1. Gold-reserved exclusion in the distractor draw (`_sem_for`) — small, mechanical, closes the defect 664
   measured, at any scale up to the pool's current size.
2. A third combinatorial descriptor axis — **measured as required almost immediately at any real battlefield
   scale, not merely "if needed"**: `generate()`'s own semantic-mode cap (`n_chains = min(n_chains,
   len(sem_places))`, `corpus_generate.py:311`) limits gold chains to ≤26, so real-scale total doc count can
   only come from raising `distractor_ratio` — and a throwaway simulation (§Confidence-pass, item 3) found
   that at `n_chains=26` and even the generator's *current default* `distractor_ratio=6`, **43% of distractors
   already share a descriptor with another distractor**, rising to 63-94% at the ratios needed to reach a few
   hundred to ~800 total docs. The third axis is therefore a near-certain prerequisite for the real-scale
   member, not a contingent decision to defer to build time.
3. `descriptor_collision_report`'s exact-match check stays as the sanity/regression guard, unchanged.
   Escalating it to embedding-based near-duplicate detection (664's own already-named escalation path,
   reusing the in-house `gte-multilingual-base` embedder) is correctly deferred — the present problem's
   content stays templated even at real scale (a wider template, not free-form text), so the escalation
   trigger 664 named ("the generator's content ever becomes less templated") has not fired.

## T.2 — The OCR-only corpus member: a new axis renderer, not a new pipeline

`corpus_generate.py` already has an extension point for exactly this: `generate(..., axis="prose", ...)`
dispatches to per-axis renderers (`:282` on; prose/code/tabular renderers already exist, `:222-282`,
alongside the `lang` knob). §M.2's degraded-scan member is the same shape of extension — a renderer that
takes the same fabricated, chain-first, closed-book-certified content and **outputs it as a rendered document
image** **instead of** a `.txt`/`.md` file — not a new corpus-construction pipeline. Two consequences of
reusing this seam rather than inventing one:
- **The certification gates are unchanged.** The memory-axis gate (`corpus_certify.py`) certifies the
  *answer*, never the on-disk file format; the fidelity-axis gate (`corpus_fidelity.py`) already requires a
  real `jseval run` ingest, which already routes through the production Tika/VLM extraction path for
  whatever file type is on disk — no new ingest machinery is needed for an image-rendered corpus member,
  only content that happens to exercise the path that already exists.
- **The rendering step is NOT small and self-contained, corrected by the §Confidence-pass below.** A
  live probe this pass ran found Claude Code's own `Read` tool answers correctly from a *plain* rendered
  scan via multimodal vision — a simple render does not block a file-reading agent at all, falsifying the
  original "renders as unreadable/binary" assumption. A *heavily degraded* render (small font, low contrast,
  blur, rotation, noise) did genuinely defeat `Read`'s own vision extraction in the same probe — so the
  premise is salvageable, but the renderer must be **tuned to a specific degradation band**: bad enough to
  defeat a casual multimodal read, good enough that the production Tika/VLM ingest path can still extract it
  (this second half is **unverified** — no dev-stack access this pass, §Confidence-pass item 7). This changes
  the renderer from "one axis-renderer function" to "an axis-renderer plus an empirical tuning pass against
  two different extraction capabilities," a real scope increase this design under-estimated originally.

**Design (matched to the problem, scope corrected):** add one new axis renderer (e.g. `axis="scan"`) to the
existing dispatch, reusing every other piece of the generator/certify/fidelity/suite-profile machinery
unchanged — **but budget it as a tuning exercise (render at varying degradation levels, test against both
Claude's own `Read` and the real ingest pipeline, find the band that defeats the first and survives the
second), not a one-line renderer.** No new gate, no new record shape, no new ingest path — the added cost is
calibration effort, not new architecture.

## T.3 — Judge human-calibration: recognized as a third instance of an already-named, still-unbuilt pattern

The methodology plan (§M.4) calls for a stratified human-labeled sample + a Cohen's-kappa figure to validate
`utility_judge.py`'s hybrid EM→Qwen3.5-9B judge against ground truth. Looking for prior art in this
codebase's own register surfaces that this is not a new *shape* of need:

- **Q-007** (search-quality register): *"build labeled dataset from 50q eval: (query, context) →
  answerable? Measure classifier precision/recall"* — for the context-sufficiency classifier. Unbuilt.
- **Q-009** (search-quality register): *"build a small labeled (query, context, answer-supported?) set...
  measure whether `best_chunk_score`/`score_gap` separate well-grounded from weak answers"* — for the
  retrieval-confidence signal. Unbuilt.
- **This tempdoc's §M.4** — a small labeled `(query, candidate_answer) → correct?` sample, to compute
  judge-vs-human kappa.

Three independent surfaces, each needing the identical shape of artifact: a small, stratified,
human-labeled calibration set, scored against an automated signal via an agreement statistic. By this
codebase's own repeatedly-invoked rule-of-three convention (625, 646, 664 all use it explicitly to decide
when a recognized pattern earns shared structure), **three real instances is the naming trigger** — but
naming is not building. None of the three is concurrently being implemented today (Q-007/Q-009 remain open
questions; only this tempdoc's instance has an active plan), and their record shapes differ enough (context-
sufficiency labels vs. confidence labels vs. correctness labels) that forcing one shared module now would be
structure ahead of a real second *consumer*, the same over-eager move 664's own ninth pass explicitly
declined to make for its corpus-certification-data readers. **Design:** build only this tempdoc's own
instance (§M.4, unchanged), but give its labeled-sample record a small, stable, versioned shape (`{item_id,
candidate_output, human_label, rater_id, labeled_at}` plus whatever judge-specific fields it needs) rather
than an ad hoc one-off script's output — so that if Q-007 or Q-009 is picked up next, adopting the same
record shape (not necessarily the same code) is the cheap path, and the *third* time this need is actually
built is the real trigger to extract a shared `human_calibration` helper (sample stratification + kappa
computation), not this pass.

## T.4 — Stratified capability-coverage: a governed projection of the existing record, not a new one

§M.7a's contingency design (report the McNemar discordant-pair breakdown stratified by battlefield
dimension — scale bucket, OCR-vs-text, language — rather than only a pooled delta) needs a home. The
retrieval-quality side of this codebase already has the exact shape of machinery this needs, read directly:
`jseval/projections/base.py` defines a `Projection` (`name`, `schema_version`, a **pure**
`produce(run_dir) -> dict` function) registered in a module-global registry and auto-invoked by
`run_all_discovered` at end-of-run, failure-quarantined (one projection's exception never blocks another,
`base.py:121-176`). `stratified_metrics.py` is exactly this shape applied to the "decompose an aggregate
metric into buckets" problem — per-query-length and per-first-relevant-rank strata over a retrieval run,
with an explicit design constraint worth carrying over verbatim: *"projections are pure functions over
artifacts"* — a stratification dimension that would require live computation (e.g. NER at projection time)
was deliberately rejected in favor of ones derivable from already-recorded fields (`stratified_metrics.py:14-18`).
`staged_recall_accounting.py` (636/D-005) is the same shape again, one level richer (a self-reconciling
decomposition against the harness's own recorded values).

**The one real design decision.** `utility_comparison.compose_utility` is **not** a `jseval run`-artifact
consumer — it was deliberately kept a cousin, not a sibling, of the retrieval-eval record (§D.2 above), so it
does not literally belong in the `jseval/projections/` registry, which is scoped to `run_dir` artifacts. But
the *principle* the registry embodies — a pure, versioned, failure-quarantined decomposition of an aggregate
outcome into structurally-meaningful buckets, computed from data already recorded, never live-computed —
applies without modification. **Design:** a stratified-coverage capability is a **pure post-composition
function over an already-composed `utility-comparison.v1` record's per-cell data** (each cell already
carries its corpus/battlefield-dimension tag, since that is how §M.2's multi-member battlefield is
organized), producing the per-stratum McNemar/CI breakdown as an **additive field** on the same record —
mirroring `stratified_metrics.py`'s bucket-marginal output shape, versioned the same way (a `schema_version`
on the new field), but implemented as a function `utility_comparison` calls, not a registry entry
`jseval/projections/` would discover (the two systems' artifact model differs enough — `run_dir` vs. a
composed record — that literally sharing the registry would be forcing a fork of the registry's own
contract, not reuse of it). This is "conform to the *principle*, not the *object*" — the identical move this
tempdoc's own §D.2 already made once (agent-utility is a cousin record, not a 623-release sibling) applied
one level deeper, to a *component* of that cousin record rather than the record itself.

## Reach

**Principle 1 — construction-time guarantee over detection-time gate, for any governed synthetic-corpus
property.** 635 §D.5 established this for contamination-resistance specifically; §T.1 shows the identical
shape applies to collision-freedom, a different corpus-quality axis. Stated plainly as the general rule:
*for a property this codebase certifies about a generated corpus, prefer a generator design that makes the
property true by construction (an exclusion, a combinatorial space, a disjoint partition); keep a
behavioral/statistical detection check only as the sanity/regression guard that the construction actually
held — never as the sole guarantee.* **Where this already holds:** contamination-resistance (635, built),
collision-freedom (§T.1, designed here). **Where it does not yet, named but not fixed:** the fidelity gate's
difficulty band and shortcut-leak checks (`corpus_fidelity.py`) are currently **detection-only** — nothing in
the generator *guarantees* a chain is non-shortcuttable or correctly difficulty-banded by construction, only
a post-hoc nDCG measurement and a single-doc-answerable probe catch a violation after generation. This is a
real, named gap under the same principle, not a defect this pass fixes — recorded as a candidate for a future
pass, the same way 664 recorded its own partial-application gaps without building them immediately.

**Principle 2 — a recognized-but-deferred "human-calibration-set" shape, now at its rule-of-three trigger for
*naming*, not yet for *building*.** Q-007, Q-009, and §T.3's judge-calibration instance are three independent
surfaces needing the same artifact shape (small stratified human-labeled sample vs. an automated signal,
scored by an agreement statistic). Recorded here as a named candidate for a shared `human_calibration` helper
(stratified sampling + kappa) — the trigger to actually build it is a *third concurrently-active*
implementation (not merely a third named need), which has not happened; §T.3 builds only its own instance,
shaped so adopting a shared helper later is cheap.

**Principle 3 — aggregate outcomes over a knowingly heterogeneous population default to a governed
stratified decomposition, not only a pooled scalar.** This is not a new principle — it is the same instinct
`jseval/projections/stratified_metrics.py` and `staged_recall_accounting.py` (636/D-005) already embody on
the retrieval-quality side, and the same "regime-blind capability, not corpus-fit" stance D-005 names as a
standing engine-development rule. §T.4 is its **agent-utility-eval instance** — worth stating plainly because,
until this design, the agent-utility record was itself a small violator of the very principle its retrieval-
quality sibling already enforces (a single pooled per-(corpus,model) delta, no stratification). Where else
this would apply: any future canonical record composing over a population with structurally distinct
sub-populations (e.g. a future extraction-quality sibling record composing over document types) inherits the
same default — recognized as a candidate, not built.

**All four items are extensions of the one seam this tempdoc has conformed to throughout
(canonical-record + governed-projection, 553/622/623/635/664) — none forks it.** §T.1 strengthens an existing
governed *identity* property (like corpus_signature/regeneration-determinism) rather than adding a new
record; §T.2 adds a range within the existing corpus *artifact* abstraction (still governed by the same two
gates) rather than a new pipeline; §T.3 adds an honesty *field* to the existing utility-comparison record
rather than a new record; §T.4 adds a governed *projection* of the existing record rather than a new one.
Nothing here required inventing new structure at the seam level — matching this tempdoc's own established
discipline (D.2, D.3, D.4 above all made the identical move).

## External research pass (2026-07-02) — targeted, not blanket

> Before treating §T.1-T.4 as final, this pass asked which parts touch a genuinely fast-moving external
> field versus parts already checked recently. **Deliberately not re-researched:** §T.1 (descriptor-
> collision/combinatorial corpus construction) and §T.4 (stratified/disaggregated reporting) — both had a
> dedicated, cited 2025-2026 external pass within the same tempdoc lineage in the last ~10 days (this
> tempdoc's own Confidence-passes; 635 §D.5/External-research-pass; 664's External-research-pass); repeating
> so soon is low marginal value and none of §T.1/T.4's design decisions above turned out to depend on
> anything that could plausibly have changed in that window. §M.5's statistics (McNemar/seeds/power) and the
> Inspect-AI execution design were similarly checked recently (this tempdoc's own D.5/Confidence-pass #2) and
> were not re-checked. **Researched:** §T.3 (judge calibration against human labels — never externally
> checked before; only judge *protocol*, not calibration *methodology*, had a prior pass) and §T.2 (synthetic
> scanned-document construction — genuinely new work with zero prior check; F-009's citations measure *real*
> OCR noise, not *constructing* synthetic contamination-free scans). Citations below are paraphrased
> summaries with links, matching this tempdoc's and the codebase's existing citation style — **no external
> text, code, model weights, or datasets were copied into this repository or this tempdoc**; nothing in this
> pass adds a dependency or an asset.

**T.3 — judge calibration.** ["Judge's Verdict"](https://arxiv.org/abs/2510.09738) (2510.09738, evaluating 54
LLMs as RAG/agentic-response judges against human labels) confirms Cohen's kappa (two raters) /
Krippendorff's alpha (three-plus) as the standard agreement statistics — matching §T.3/§M.4's existing
design — but adds a sharper method worth adopting: **"correlation alone is insufficient... a Turing-test-for-
judges" comparing the judge's agreement level against the natural disagreement *between humans themselves***
(their z-score framing: a judge that is suspiciously more self-consistent than humans are with each other,
`z > 1`, is flagged distinctly from one whose agreement pattern looks human-like, `|z| < 1`). Current
industry best-practice guides (e.g. futureagi.com's 2026 LLM-judge-calibration posts — blog-tier, not
peer-reviewed, cited accordingly) recommend 100-300 labeled items with 2-3 human raters for a *general-
purpose* judge calibration.

**Design correction folded in (sharpens, does not replace, §T.3/§M.4):** use **≥2 independent human raters**
on the stratified sample, not one — compute both **judge-vs-human-majority agreement** and
**human-vs-human inter-rater agreement** on the *same* sample, and report both in the record (the
human-vs-human figure is the natural-disagreement baseline the judge's own agreement should be read
against, not a bare kappa in isolation). On sample size: this design's hybrid EM-auto-pass architecture means
the judge only ever runs on **EM-disagreement cases**, a narrower and more error-concentrated population than
the general "any production trace" case the 100-300 figure targets — so §M.4's existing n=30-50,
stratified toward disagreement cases, is a deliberately smaller, targeted sample proportionate to this
design's narrower judge-invocation surface, not an underpowered version of the general-purpose figure. This
reasoning should be stated in the record itself when built, not just asserted from this tempdoc.

**T.2 — synthetic OCR-only content.** The 2026 landscape has real, active work here:
[SeedRG](https://arxiv.org/pdf/2605.08838) (2605.08838) generates leakage-free RAG benchmarks by grounding
questions in *real* documents while proving they require retrieval (not parametric memory) — an alternative
point in the design space to this project's fully-fabricated-corpus choice, and one whose own stated failure
mode is directly relevant: it flags that **naive entity substitution during generation "can introduce
evaluation artifacts"** — independent, current (2026) corroboration of exactly the descriptor-collision risk
class §T.1/664 already found and are fixing, from a completely different benchmark-construction effort.
[DocDjinn](https://arxiv.org/abs/2602.21824) (2602.21824) is a VLM-plus-diffusion pipeline for synthesizing
realistic *handwritten* documents at training-data scale (140k+ samples); its own abstract does not state a
license for the released code/data, and its scope (handwriting synthesis, training-set-scale generation) is
a mismatch for this design's actual need (rendering already-fabricated typed text into a scan-like image at
evaluation scale, a handful of documents).

**Design correction folded in (T.2, sizing not re-scoping):** do **not** adopt DocDjinn or any comparable
heavyweight VLM/diffusion pipeline — both the license ambiguity and the scale/scope mismatch rule it out,
and the present problem (make already-fabricated content unreadable to a text-only agent, readable to the
production Tika/VLM ingest path) is fully served by a much lighter, from-scratch rendering step (plain text →
an image via basic scan-style degradation — font choice, rotation, noise, blur — a well-documented, generic
technique with no single tool/license to attribute, confirmed generically across multiple current OCR-
benchmark-construction sources in this pass's search results). **Do not adopt SeedRG's real-document-
grounded approach either** — this project's synthetic-fabrication choice was already reasoned through in
635 §D.4/§C-4 specifically to avoid the ongoing refresh/decay treadmill a real-document-grounded corpus would
re-introduce (no CI staffing for that, ADR-0026); SeedRG demonstrates a competitive alternative exists in the
field, but not one that fits this project's specific maintenance constraint. Recorded as an alternative
design considered and knowingly not chosen, not a gap.

**Net effect:** no design in §T.1-T.4 is overturned; §T.3 gains a concrete, citable refinement (dual-rater
calibration against a human-disagreement baseline, not a bare judge-vs-one-human kappa) and a reasoned
sample-size justification; §T.2 gains a concrete construction technique (lightweight rendering, not a
heavyweight external pipeline) and an explicit reason for declining a plausible-looking alternative
(SeedRG). No new dependency, asset, or external code enters the repository from this pass.

---

# Critical-analysis pass (2026-07-02, fourth pass) — does this plan still serve its own goal?

> Requested review, not a new design pass: does everything above still serve the stated purpose (an
> externally defensible agent-utility number, U0 resolved honestly), or has it drifted — via scope creep,
> internal inconsistency, or reduced usability for whoever implements it next? Five real issues found; four
> fixed directly in the sections above (cheap, safe, textual); one is a genuine, unresolved scope/cost
> trade-off, named here rather than silently absorbed.

## Issues found and fixed (already applied above)

1. **The T-section designs weren't wired back into the M-section's own action items.** §T.1-T.4 were
   appended *after* §M.1-M.10 and referenced M by pointer, but M.2/M.3/M.10 — the sections an implementer
   would actually open first — never pointed forward to T's settled designs. An implementer starting at
   §M.10 (the execution sequence) could execute step 2 ("fix the descriptor-collision defect") without ever
   discovering §T.1 contains the actual design for *how*. **Fixed:** added pointers from §M.3/§M.10 (steps
   2, 5, 6, 8, 9) to §T.1-T.4, and from §M.8's items 1/3/7 to the same.
2. **§M.7a's own contingency design — stratified capability-coverage — was, left undisciplined, a new
   cherry-picking vector.** The whole plan exists to stop a favorable-but-unrepresentative number from being
   headlined (§C-4's original finding about condition C); §M.7a's per-stratum reporting, added to give the
   plan an honest fallback if the pooled result is null, could just as easily let a future draft headline
   "wins on the OCR-only stratum" from an underpowered slice of ~100-150 total queries split three or four
   ways — the identical failure mode one level down. **Fixed:** added §M.8 item 7, requiring any promoted
   per-stratum claim to independently clear its own significance/CI bar, not borrow credibility from the
   pooled result.
3. **The judge-calibration design (§M.4) recommended a bare kappa against one human rater without checking
   whether n=30-50 gives that estimate any real precision, or whether one rater is even the right comparison.**
   The external-research pass found the answer (compare against human-vs-human agreement, not a single
   human's label, and report the estimate's own CI) but left it sitting in an addendum rather than folded
   into §M.4 itself, where an implementer would actually look. **Fixed:** rewrote §M.4 in place with the
   dual-rater design and the CI-reporting requirement, not just cross-referenced from a later section.
4. **§M.9's run-budget item asked the founder to sign off on the run's dollar cost without ever surfacing
   that §T.1-T.4 also added real, non-trivial *engineering* work ahead of that run** (a construction-time
   generator fix, a conditional descriptor-space expansion, a new corpus-rendering axis, a dual-rater
   calibration protocol, and stratified-reporting logic) — none individually large, but never summed, and a
   founder reading only the run's $-estimate would be signing off on a fraction of the real lift. **Fixed:**
   added an explicit note to §M.9 U-Founder-2 naming this gap rather than pretending the run cost is the
   whole cost.

## The one issue found and NOT fixed — a genuine, unresolved trade-off, named rather than absorbed

**Scope has grown, monotonically, across every one of this tempdoc's last four passes, and nobody has yet
asked whether all of it is still proportionate.** The original brief (§Assignment 2026-07-01) asked for a
methodology plan. §U0's correction (rightly) expanded the battlefield from a small clean-text corpus to
real-scale + OCR-only + multilingual. §Design theorization (rightly) added four more design items on top of
that. §External research pass (rightly) added a second human rater and a CI requirement to the judge step.
Each addition was individually justified — none of the four fixes above found anything that should be
*removed* — but the cumulative effect is that "the minimum bar for a v1 number" is now substantially larger
than the original brief's implicit scope, and this plan has never once asked "is there a leaner floor
number worth publishing sooner, distinct from the full bar" as its own explicit question — it only offers a
*sequencing* choice within the full bar (§M.9 U-Founder-1: build OCR later, not build OCR never). **This is
not fixed here because resolving it is a real founder trade-off (calendar time and engineering cost against
how much of the credibility bar can be relaxed for a first, more provisional release), not a design question
this pass can answer unilaterally** — but it is named plainly rather than left for a reader to notice on
their own, which is exactly the failure mode this critical-analysis pass exists to catch. If the founder
decides the full bar is too much before any number ships, the honest next design question is **"what is the
smallest defensible subset of §M.8's bar that still avoids §M.7's red-teamed attacks" — not decided here.**

## Does the design still serve its purpose, and how well

**Yes, directionally — the core answer to U0 (does the realistic arm's number help the wedge) is still the
thing every section is organized around, and nothing found in this pass points at that framing being wrong.**
The four fixes above close real gaps (a missing implementation pointer, a new cherry-picking vector, an
underpowered/uncompared calibration statistic, an invisible cost) without changing any load-bearing decision.
**The one open item — proportionality of the now-larger bar versus a leaner interim option — is real and
not this pass's to resolve**, and is the honest limit of what a planning-only pass can settle without the
founder's own judgment on cost versus rigor. Confidence this plan, as it now stands, would survive an
implementing agent picking it up cold: **7/10** — the content is sound and now properly cross-linked, but
the tempdoc's own length (a genuine instance of the "event-sourced tempdoc" drift concern named in 646 — a
long, actively-revised document with no single current-state fold) means a first-time reader still has to
read multiple dated passes to reconstruct which recommendation is current, rather than finding one place that
says so. Adding that fold is itself more structure than this single-tempdoc, still-unimplemented plan
currently needs (646's own trigger — recurring pain across *several* large active tempdocs — has not fired
for this document alone); named here as a known cost of this document's format, not fixed.

---

# Confidence pass (2026-07-02, fifth pass) — converting §T.1-T.4's assumptions into verified facts

> Read-only investigation + throwaway/scratch experiments (mirrors this tempdoc's own established
> "Confidence pass" discipline, used four times already in this lineage). **No implementation of the
> tempdoc's actual feature work**: no code in `corpus_generate.py`/`utility_judge.py`/`utility_comparison.py`
> was touched; no corpus, test, or generated content was committed. All experiments ran in the session
> scratchpad and are discarded. §T.1/T.2/M.1/M.8 above were corrected in place where this pass's evidence
> contradicted them; this section is the evidence log.

| # | Assumption | Outcome | Evidence |
|---|---|---|---|
| **1** | An OCR-only-rendered document structurally blocks a file-reading agent (§T.2's core premise) | **FALSIFIED for a plain render; CONFIRMED for a tuned degraded render.** A live condition-A-style `claude -p` probe (haiku, empty MCP config, `--add-dir` scoped to a directory holding one rendered PNG of a fabricated fact) called `Read` directly on the `.png` and answered correctly — Claude Code's `Read` tool reads images via multimodal vision; a simple scan is not a categorical block. A second probe with heavy degradation (13pt font, low contrast, Gaussian blur, 6.5° rotation, 8% salt-and-pepper noise) made the same agent correctly report it could see text but not read it, and ask for a clearer copy — confirming a *tuned* degradation band can genuinely defeat casual vision-reading. **§T.2 corrected above**: this is a calibration problem (find the band that defeats `Read` but survives the real Tika/VLM ingest path), not a one-line renderer. | live `claude -p` transcripts (scratchpad, discarded); `render_scan.py`/`render_scan_hard.py` |
| **2** | `--disallowedTools WebFetch,WebSearch,Bash` is still valid and actually suppresses those tools live | **CONFIRMED, plus one new finding.** `claude --help` confirms the flag and syntax are current (CLI 2.1.198). A live probe with `--disallowedTools WebFetch,WebSearch,Bash` and a prompt explicitly tempting a URL fetch/shell command produced zero invocations of those tools (only `Glob`/`Read`), answering correctly from the local file. **New finding, not previously flagged**: an earlier attempt with only `WebSearch` disallowed was routed around by the agent spawning a general-purpose **subagent** (`Agent`/`Task` tool) that invoked a personal `deep-research` skill, looping long enough to time out the probe — confirming tool-restriction bypass via subagent delegation is real, and that "isolated" (repo-level `CLAUDE.md`-free) does not mean isolated from the operator's own **user-level** `~/.claude` skills/plugins. **§M.1/§M.8 corrected above** to add `Agent,Task` to the disallow list and to flag the operator-config isolation gap. | live `claude -p` transcripts (scratchpad, discarded); `claude --help` output |
| **3** | The gold-reserved-exclusion fix (§T.1) eliminates collisions, and the combinatorial-expansion step is only conditionally needed | **CONFIRMED (collision elimination) + CORRECTED (necessity, not a condition).** A throwaway 2-axis simulation of `_sem_for`'s logic found 13 gold/distractor collisions with today's behavior vs. **0 across 20 seeds** with the fix (`pool - gold_reserved` sampling) at the committed corpus's scale. Separately: `generate()`'s semantic-mode cap (`n_chains = min(n_chains, len(sem_places))`, `corpus_generate.py:311`) limits gold chains to ≤26 regardless of the requested `n_chains` — so real-scale total doc count can only come from raising `distractor_ratio`, and at `n_chains=26` with even the generator's **current default** `distractor_ratio=6`, 43% of distractors already duplicate another distractor's descriptor (rising to 63/74/82/94% at ratios 10/15/20/30, i.e. ~280-800 total docs). **§T.1 corrected above**: the combinatorial expansion is a near-certain prerequisite for the real-scale member, not a contingent "if needed" decision. | throwaway `collision_sim.py`/`dup_check.py` (scratchpad, discarded) |
| **4** | Stratified capability-coverage (§T.4) is "a pure post-processing function over already-recorded data" | **CONFIRMED.** Full read of `utility_comparison.py:280-386` (`_arm_comparison`) confirms every input to `compare_runs.mcnemar()`/`compare()` is built from `per_query` dicts already keyed by qid (`a_pq`/`c_pq`, `:307-336`) before the discordant-pair computation runs, and `compose_utility` already groups cells by `(corpus, agent_model)` before `_compose_cell` executes. A stratified breakdown needs only a qid→stratum lookup filtered into this already-available data — no new run-time record. | full read, `utility_comparison.py:207-417` |
| **5** | The judge human-calibration design (§T.3) fits `utility_judge.py`'s structure without restructuring; the local judge model is loadable now | **PARTIALLY CONFIRMED.** Full read of `utility_judge.py` confirms `write_overlay` (`:155-158`) writes a flat, additive-friendly JSON dict, and `judge_logs`'s `stats` block (`:139-145`) already has the exact shape a `human_kappa`/`human_alpha`/`ci` field would extend cleanly. **Not checked**: whether the local Qwen3.5-9B judge model is actually loadable right now — the `justsearch-dev` MCP server (needed for `ai_activate`/a live health check) was not connected in this session; not forced via a manual workaround given the multi-agent dev-stack ownership concerns in branch-safety.md. | full read, `utility_judge.py` (all); MCP tool search returned no dev-stack tools this session |
| **6** | `corpus_generate.py`'s generation step scales cleanly to real battlefield size | **CONFIRMED, no bottleneck.** Real (unmodified) `generate()` run at `n_chains=20/ratio=6` (420 docs), `n_chains=26/ratio=10` (858 docs), and `n_chains=26/ratio=20` (1638 docs) completed in 0.033s/0.062s/0.114s respectively — linear, trivially fast at any scale this plan needs. Output written to a `tempfile.TemporaryDirectory()`, discarded automatically. | live timing run against the real `jseval.corpus_generate.generate` (no repo files touched) |
| **7** | The production Tika/VLM ingest path extracts usable text from a tuned degraded-scan image (the other half of item 1's calibration problem) | **NOT CHECKED — blocked by tooling, not attempted.** Per the approved plan, this item was conditional on item 1 confirming a genuine block existed (it did, for the degraded case) — but the `justsearch-dev` MCP server was not connected this session, so no dev-stack lifecycle/ownership check was available. Rather than manually start the stack outside that tooling (a real, less-reversible action in a multi-agent environment, per branch-safety.md), this was left undone and is now the single most important remaining unknown for §T.2's tuning problem — it determines whether a degradation band that defeats `Read` even *can* survive real extraction, which the design currently only assumes. | none this pass — explicitly deferred |

## What this pass changed vs. confirmed

**Confirmed as designed, no change needed:** items 2 (mechanism, modulo the new Agent/Task finding), 4, 6.
**Corrected in place (§T.1/§T.2/§M.1/§M.8 above):** item 1 (T.2's core premise was wrong for a plain render;
salvageable but now a calibration problem, not a renderer), item 3 (the combinatorial descriptor-space
expansion is now measured as near-mandatory, not conditional), and a genuinely new baseline-fairness finding
neither this tempdoc nor its four prior passes had surfaced (subagent-based tool-restriction bypass, and the
operator-config isolation gap). **Left open, blocked by unavailable tooling this session:** item 5's live
model-availability check and item 7's ingest-extraction check — both need dev-stack access this session
didn't have; both are now explicitly the first things to check when that access exists, not silently assumed.

## Confidence rating — remaining implementation work: **6/10** (revised down from this tempdoc's own prior,
untested design confidence, because item 1 found a real, load-bearing premise was wrong)

- **Mechanics confirmed solid (~8-9):** the collision fix, the stratification design, and generation-scale
  feasibility are now empirically grounded, not just reasoned — all three came back exactly as designed or
  better-specified (item 3's exact breaking point is now a measured number, not a guess).
- **§T.2 (the OCR/degraded-scan member) is the one place this pass found the design was genuinely wrong, not
  just under-specified** — a categorical "block" framing that a five-minute live probe falsified. The
  salvage path (a tuned degradation band) is plausible and half-confirmed (defeats `Read`), but its other
  half (survives real extraction) is unverified and is exactly the kind of assumption that, if wrong, would
  mean this battlefield member cannot be built as designed at all — a real, not cosmetic, risk to carry into
  implementation.
- **Two items (judge-model availability, ingest-extraction) remain unverified for a reason outside this
  pass's control** (no dev-stack tooling this session), not because they were skipped as low-priority —
  named explicitly so an implementer does not assume they were checked.

