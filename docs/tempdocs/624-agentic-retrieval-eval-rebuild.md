---
title: "Agentic-utility eval rebuild: the agent-utility number as a cohort-identified, condition-paired comparison record — a projection over agent-eval runs cohort-identical on every axis except `condition`, conforming to the canonical-record + governed-projection seam (553/623/622); the comparison arm already exists (346) but lacks run identity, seeds, judge, and projection discipline, which is why '92% / 62%' is an identity-less fork"
type: tempdocs
status: implemented (machinery) — Track A retraction + the utility-comparison record + LLM-judge + condition B + run-governance/calibration shipped (see §As-built #1–4); the published number + business re-rooting remain gated on a credible record (§Open uncertainties). The §Problem…§Next-step header below is the original 2026-06-21 stub and predates the build.
created: 2026-06-21
updated: 2026-06-22
author: agent analysis (research-channel design theorization), filed by agent — STUB
category: search-quality / agent-eval / mcp / business-research-channel
principle: "a published measurement must trace to a cohort-identified reproducible run (623/625) — the agent-utility number is the extreme fork: hand-quoted across docs with no run identity and two unrelated metrics fused. Design resolution: agent-utility is its own canonical record (a condition-paired comparison), NOT a metric-family sibling of the 623 release — it conforms to the same canonical-record+governed-projection seam (553/622/623) and reuses its substrate (manifest identity, envelope, hardware projection, cited-baseline, coverage), but pairs on the `condition` axis (with/without tool) the single-cohort release object cannot model. Recurring sub-shape named: axis-relative cohort identity (a measurement is a projection over the equivalence class holding every axis fixed except its declared range-axis — `dataset` for 623, `time` for rank_diff, `condition` here)."
related:
  - ../business/research-channel/plan.md            # origin — decision 1 + deferred work
  - 366-agent-search-interface                       # source of the 92% (50q Haiku tool-design eval) + the "62%" query-fraction
  - 346-agent-retrieval-eval                          # the agent-retrieval eval seed
  - 623-reproducible-benchmark-release               # the release object this eval should produce into
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
