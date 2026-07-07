---
title: "Agent-utility standing regression detection -- a high-contrast smoke-corpus gate over a governed projection (a diagnostic smoke test, NOT a contamination 'canary'; the leak-gate template) that watches the agent-mediated tool-drive layer the existing gates miss; deliberately NOT a fifth accuracy ratchet (the realistic accuracy delta is too noisy to gate, and that estimation stays tempdoc 624's job)"
type: tempdocs
status: "implemented (v1 + D8/D9 design-conformance fixes, 2026-07-02) -- jseval utility-gate / utility-gate-derive shipped and now closes its own design-conformance gap. History: v1 shipped a baseline pinned from committed util-smoke records + the search-engine-hint MCP-surface trigger; a post-implementation code review found and fixed two defects (a --update-baseline merge-scope bug, a missing input-schema check -- see §Post-implementation review fix); a second, design-level critical review then found v1's shipped baseline ALSO pinned a real public benchmark corpus (mixed/multihop-rag, MultiHop-RAG COLM 2024) alongside the fabricated util-smoke corpus, violating D1/D4/D5/D7's 'fabricated smoke corpus only' scope lock, plus two smaller gaps (no cli_version drift handling per D5, no PASS/FAIL/INCONCLUSIVE verdict per R-4). D8 (corpus-authenticity admission gate, enforced not just documented) and D9 (cli_version-drift -> INCONCLUSIVE verdict) are now IMPLEMENTED and verified, including a genuine live end-to-end dev-stack run (real spend $0.144) during which D9's drift check fired correctly on organically-occurring real data. D10 (growing the fabricated corpus for real statistical power) remains explicitly deferred, separately-scoped follow-up work by the user's own scope decision. See §D8/D9 implementation (2026-07-02) for the full record + verification evidence. Not yet a PR -- stays in the 624-agent-utility-hardening worktree pending an explicit go-ahead to publish. Spun out of tempdoc 624's remaining-work planning after a direct question surfaced that the plan only covered a one-time publication-grade run (§M.8), never the ongoing regression-detection practice every other quality dimension in this repo already has. The design pass found the obvious 'fifth accuracy ratchet' framing was the wrong instrument (the realistic delta is noise-dominated even at full n) and settled instead on a smoke-corpus detection gate reusing the existing ratchet-kernel + record-consumer seams -- see §Long-term design."
created: 2026-07-02
updated: 2026-07-02
author: agent investigation (gap identified mid-conversation while evaluating tempdoc 624's remaining-work cost/time expenses -- the plan had no answer to "isn't this eval something JustSearch will run regularly, not once")
category: search-quality / agent-eval / observability / ratchets / mcp
related:
  - 624-agentic-retrieval-eval-rebuild   # source of the utility-comparison.v1 record, the judge, and the calibration machinery this ratchet reuses; NOT the same goal -- 624 answers "is this true, credibly, once", this tempdoc answers "did this recently change without anyone noticing"
  - 640-engine-performance-budget-latency-throughput-footprint   # perf-gate precedent -- same ratchet shape (baseline file + gate command + ratchet_kernel.py + hook nudge)
  - 636-retrieval-buried-signal-long-documents   # leak-gate precedent -- same shape, plus the origin of the jseval/projections/ registry pattern 624 §T.4 already conforms to
  - 674-cross-family-grader-local-model-infrastructure   # sibling tempdoc, NOT the same concern -- 674 validates judge trustworthiness once (infrequent, credibility-grade); this tempdoc catches regressions often (cheap, routine)
principle: "the agent-mediated layer -- whether an LLM can still successfully DRIVE the JustSearch MCP tool after a surface/loop change -- is a real quality surface no existing gate covers (relevance/leak measure RAW retrieval, not agent-mediated use). It deserves standing detection like the other four axes. But the design pass established that the right instrument is not a fifth copy of the accuracy ratchet: the realistic with-tool-vs-baseline accuracy delta is noise-dominated and near-null even at full publication n, so a cheaper version of it cannot resolve any regression short of catastrophic. The correct shape is to ENGINEER THE MEASUREMENT to fit the existing cheap/low-variance gate kernel -- a high-contrast smoke corpus (fabricated facts un-gettable without retrieval) yields a decisive, stable control statistic that the existing projection + ratchet-kernel seams gate exactly the way leak-gate already gates a projection. Detection and estimation are two consumers of one canonical record (utility-comparison.v1), separated at the governed-projection seam."
---

# 673 -- Agent-utility standing regression detection

> NOTE: Opened 2026-07-02 as "a fifth accuracy ratchet," spun out of tempdoc 624's remaining-work planning.
> Investigation + theorization + a long-term-design pass have since run (all 2026-07-02); implementation has
> not. The design pass **changed the shape**: the realistic accuracy delta is too noise-dominated to gate, so
> this is now a *smoke-corpus detection gate* over a governed projection, reusing the leak-gate template —
> see §Long-term design (which supersedes the original framing in the earlier sections). It stays deliberately
> scoped separately from 624: 624's remaining work is a one-time, expensive, publication-grade *estimation*
> (§M.8's credibility bar); this tempdoc is the missing routine, cheap, ongoing *detection* counterpart.
> The filename slug keeps the original "-ratchet" for stable cross-references from 624/674; the instrument is
> a detection gate, not a fifth accuracy ratchet.
>
> TERMINOLOGY: "smoke corpus" here = a tiny engineered *diagnostic smoke test* (software sense). It is
> deliberately **not** called a "canary" — in LLM-eval that word means contamination-detection strings (the
> opposite concern). See §External research pass R-2. An early theorization section still says "smoke set";
> read it as the same thing.

## Why this tempdoc exists

While costing out tempdoc 624's remaining work, a direct question exposed a gap in the plan: the ~$163/
4.5hr 5-seed calibration re-run was being treated as *the* deliverable, but nothing in the plan asked what
happens the next time someone changes the MCP tool surface, the agent loop, or a retrieval-config default.
Every other quality dimension this codebase measures already answers that question with a standing ratchet
(`jseval relevance-gate`, `perf-gate`, `leak-gate`, `llm-gen` via `llm-gate`) -- four commands sharing one
kernel (`jseval/ratchet_kernel.py`: load baseline -> resolve run -> compare -> report), each backed by a
pinned baseline file (`scripts/jseval/{relevance,perf}-ratchet-baselines.v1.json`,
`leak-gate-baselines.v1.json`, `llm-gen-ratchet-baselines.v1.json`), each nudged by the `search-engine-hint`
PostToolUse hook whenever relevant source changes. Agent-utility has none of this. A register check (grep
for `utility-gate`/`utility_gate`/`agent-utility.*ratchet` across `docs/tempdocs/`) found zero existing
owner -- this is a real, previously-unclaimed gap, not a duplicate of anything already planned.

## Overarching goal

Agent-utility regressions should be caught the same way a relevance or performance regression is caught
today: automatically, cheaply, at the moment the responsible code changes -- not only visible if and when
someone commissions another expensive publication-grade run. This is what makes the $163 spend (and any
future repeat of it) a one-time credibility investment rather than a number that silently goes stale the
moment anything agent-facing changes again.

## This tempdoc's main goal (scope)

1. **Design a cheap, routinely-runnable variant of the utility-comparison measurement**, distinct from
   §M.8's publication-grade bar -- almost certainly a much smaller n and seed count than the ≥5 seeds/
   n≥100 floor 624 requires for a *claim*, accepting more noise in exchange for being affordable often.
   Haiku-tier only (per the existing benchmark model-cost policy), reusing the existing EM-auto-pass /
   local-Qwen judge -- **not** the cross-family panel (674) -- since a routine regression gate needs an
   already-trusted, cheap judge, not a fresh judge-trustworthiness validation every run.
2. **Ship `jseval utility-gate`** (naming TBD -- match the existing `*-gate` convention), sharing
   `ratchet_kernel.py` the same way the other four do, backed by a new
   `scripts/jseval/utility-ratchet-baselines.v1.json`.
3. **Decide the trigger cadence deliberately, not by default.** The existing four gates are *nudged*
   (developer-triggered via a hook), not auto-blocking on every CI run -- almost certainly correct here too,
   given every gate invocation costs real LLM-agent money, unlike the other four gates' local-inference-only
   cost. Extend `search-engine-hint` (or add a sibling hook) to nudge after changes to
   MCP/agent-loop/retrieval-config surfaces specifically -- narrower than the existing hook's current
   trigger set, since not every retrieval-engine edit is agent-utility-relevant.
4. **A re-pin mechanism** (`--update-baseline` or equivalent) for deliberate, intentional changes, matching
   the other four gates' convention.

## Explicit non-goals

- **Not** the one-time publication-grade credibility-bar run itself -- that stays tempdoc 624's own §M.8
  work, unaffected by this tempdoc.
- **Not** judge-trustworthiness validation (cross-family calibration) -- that's tempdoc 674's job. This
  tempdoc's gate uses whatever judge 674 (or 624's existing machinery) has already established as trusted;
  it does not re-validate the judge on every run.
- **Not** a redesign of the underlying `utility-comparison.v1` record or `utility_judge.py` machinery --
  this tempdoc is a *consumer* of that machinery (same relationship perf-gate/relevance-gate have to the
  eval-run and scoring code they gate), not a fork of it.

## Open design question to resolve first

What n/seed count is actually affordable at routine cadence, and how often is "routine"? The other three
LLM-independent gates (relevance/perf/leak) are effectively free to run often since they're local-inference
only; this one is not, since every cell costs a real haiku-tier agent call. Sizing this against real,
current per-cell cost data (already measured in 624's own calibration work) is the first concrete step,
before any code is written.

## Recommended model/effort for the design pass

Sonnet-5, medium effort. The shape to follow already exists in this codebase four times over (relevance/
perf/leak/llm-gen) -- this is applying an established, working pattern to a new axis, not inventing a new
one. The one genuinely open call (routine-cadence n/seed sizing under real cost constraints) is a bounded
sizing question, not an architecture question.

## Investigation pass 2026-07-02 (agent) — findings before any design

Autonomous investigation of the machinery, the four precedent gates, and the **real measured 624 data**
this ratchet would consume. No design or code written; this section records what I found and the questions
it raises, for founder resolution before the design pass begins. Primary-source citations throughout; all
paths are in this worktree (`.claude/worktrees/624-agent-utility-hardening`).

### F0 — Machinery inventory (confirms the "consumer, not fork" framing)

The pieces the tempdoc names all exist and match its description:

- **Shared kernel** `scripts/jseval/jseval/ratchet_kernel.py` — `load_baselines_doc` / `resolve_run_dir` /
  `finalize_report` / `run_gate`, exactly the load→resolve→read→evaluate→report flow (kernel docstring
  lines 1–14). A new family reuses these; per-family `evaluate` stays in its own module.
- **Four precedent gates**: `relevance_gate.py`, `perf_gate.py`, `leak_gate.py`, `llm_gate.py`. Each pairs a
  pure `evaluate(baselines, source_doc, …)` with a **projection** function that derives the baseline from a
  *measured green run* (`leak_gate.derive_baselines`, `llm_gate.project_bench_to_llm_baselines`,
  `perf_gate.project_run_to_perf_baselines`) — never a hand-typed floor. Exit codes 0/1/2 uniform.
- **The utility record** `utility_comparison.v1` (`utility_comparison.py`): per-cell paired arms keyed
  `(corpus, agent_model)`, per-query `{correct, cost_usd, unique_tokens, num_turns}`
  (`agent_utility_run.py:39–50`), aggregated to `accuracy{baseline,with_tool,delta,mcnemar_p,seed_envelope}`
  + bootstrap-CI `cost_usd`/`tokens_unique`/`turns` deltas (`_stats_from_pairs`).
- **The `search-engine-hint` hook** already has two trigger classes (retrieval-engine → relevance/perf/leak;
  inference-path → llm-gen; `search-engine-hint.mjs:22–44`). Neither covers the MCP-tool-surface / agent-loop
  path — the tempdoc's proposed narrower trigger is a coherent third class.

**So the mechanical claim holds: building `utility-gate` is applying a proven 4× shape, low architectural
risk.** The reservations below are all about the *statistics and metric choice*, not the plumbing.

### F1 — The metric this gate would trip on is noise-dominated and near-null *even at full publication n* (the load-bearing finding)

I read the actual leak-free/judged records from the real 2026-07-02 calibrated run
(`scripts/jseval/624-run-2026-07-02/out-*-leak-free-judged/utility-comparison.v1.json`). The headline
accuracy delta (with-tool − baseline), the natural thing a utility-gate would gate:

| corpus | baseline | with_tool | delta | McNemar p | n_paired | seeds | with_tool seed-stdev |
|---|---|---|---|---|---|---|---|
| `golden/battlefield-en-v1` | 0.797 | 0.716 | **−0.081** | 0.307 | 74 | 3 | 0.054 |
| `golden/battlefield-de-v1` | 0.817 | 0.732 | **−0.085** | 0.263 | 71 | 3 | 0.101 |
| `golden/synth-scan-v1` | 0.500 | 0.000 | −0.500 | 0.500 | 4 | 3 | 0.000 |

(scan is `n=4` — a broken corpus per 624 §As-built #6, Tika OCR bug; ignore it as signal.) The pooled
cross-corpus leak-free effect is **delta −0.094, p=0.055** (624 status line, eighth/ninth pass).

Two things follow, and they are the crux:

1. **The effect is not significant even at the full bar.** §M.8 requires ≥5 seeds / n≥100 for a *claim*; the
   real 3-seed/n≈74 run lands p≈0.26–0.31 per corpus, p≈0.055 pooled. The signal is small and borderline.
2. **The per-seed accuracy noise is the same magnitude as the effect.** With-tool seed-stdev is 0.05–0.10;
   the delta is 0.08. A 3-seed mean has a standard error ≈ stdev/√3 ≈ 0.03–0.06 — i.e. the confidence band
   on a single run's delta already spans zero and then some.

**A ratchet is only meaningful when run-to-run noise ≪ the regression it must detect.** Here, at the *full*
publication n, noise ≈ the entire effect. The tempdoc's §Scope item 1 and its "Open design question"
explicitly propose going **cheaper — smaller n, fewer seeds** — which *widens* that band further. The
consequence: a cheap `utility-gate`'s detectable-regression floor would be so high that only a *catastrophic*
swing (the tool returns nothing / the agent stops invoking it, moving the delta by tens of points) could trip
it above noise. Anything subtler is indistinguishable from seed variance. This is not a tuning detail — it is
a question of whether the instrument can do the job at any affordable cadence.

### F2 — The catastrophic case (the only thing a cheap variant *could* reliably catch) is already covered far more cheaply

If the realistic detection floor is "the tool grossly broke," note that failure mode is already caught by
$0, deterministic means that fire on the same edits:

- `mcp_tool_surface_hash` (`agent_manifest.py:48`) already fingerprints the MCP tool surface — a change is
  detectable without any paid run.
- `RuntimeContractTest.java` (app-api) and `McpProtocolHandlerTest.java` (ui) already assert the MCP/runtime
  contract.
- `relevance_gate` + `leak_gate` already catch retrieval-quality / recall collapse at the retrieval layer.
- `util-smoke/` is a **$0.20–0.60** contamination-free micro-run (README) — a single live agent A-vs-C smoke
  that would swing hard if the tool stopped answering, at ~1% the cost of a statistically-shaped gate.

So the paid, high-variance accuracy ratchet would largely *duplicate* coverage that cheaper tiers already
provide for the only regressions it can actually resolve. That is the opposite of the other four gates, which
catch *subtle* movements (a few % nDCG, a latency-ratio band) that nothing else sees.

### F3 — Reconsider *what* to gate, not just the n (proposed reframing of the "Open design question")

The tempdoc frames the single open question as sizing: "what n/seed is affordable." I'd argue the sharper
question is **the metric**. Accuracy-delta is the *worst-conditioned* quantity in the record for a tripwire:
paired-binary, near-null, McNemar-noisy. The same per-query record carries lower-variance signals that track
"did the MCP/agent surface regress" more directly and stabilize at far smaller n:

- **Tool-invocation / retrieval-call success rate** — did the agent successfully call the tool at all
  (a schema/description regression shows here immediately, deterministically, near-zero-variance).
- **Turns-to-answer / num_turns** and **cost/token per query** — already bootstrap-CI'd in the record;
  efficiency regressions (the tool got harder to drive) are tighter than accuracy.
- **Absolute with-tool accuracy floor** instead of the *paired* delta — drops the McNemar pairing noise
  entirely (you lose the counterfactual, but a *regression tripwire* doesn't need the counterfactual the way
  a *credibility claim* does).

For a **claim** (624 §M.8) the paired delta is right. For a **routine regression gate** it may be exactly the
wrong choice. This is worth settling before design, because it changes the baseline schema and the run shape.

### F4 — Premise scrutiny: "exactly as regression-prone as those four" is half-true

The tempdoc's `principle` asserts agent-utility "is exactly as regression-prone" as relevance/perf/leak/
llm-gen and so "deserves the same ratchet." Two halves:

- **True:** the *risk that an edit silently moves the number* is real and currently unwatched. Coverage of the
  agent-mediated layer is a genuine gap (relevance/leak measure *raw* retrieval, not how well an LLM *drives*
  the tool — a real, uncovered surface).
- **Not true:** the four precedents are all **local-inference, low-variance, ~$0, deterministic-ish**.
  Agent-utility is **paid, high-variance, near-null-signal**. Equal *regression risk* does not imply equal
  *detectability per dollar*. The analogy justifies wanting coverage; it does **not** by itself justify
  copying the same accuracy-ratchet instrument. The tempdoc treats the instrument choice as settled ("apply
  an established pattern"); F1–F3 argue it is the actual open question.

### F5 — Cost reality of the *nudge* (tempdoc item 3)

Per-query haiku cost is **$0.10–0.14** (624 §R6, `624:569`). Even a deliberately-cheap 1-seed/n≈30/2-arm run
is ≈ $6–8 per corpus, ≈ $20–25 across the en/de/scan set — **per gate invocation**, and that is real recurring
money, unlike the four existing hooks which nudge $0 local runs. A `search-engine-hint` sibling that fires on
every MCP/agent-loop edit therefore trains developers to either spend repeatedly or learn to ignore the
nudge. Whatever the design lands on, the trigger has to be *much* rarer than the existing hooks (e.g. only on
`mcp_tool_surface_hash`-affecting changes, or explicitly manual/periodic), not "every agent-adjacent edit."

### Open questions to resolve with the founder before the design pass

1. **Instrument (F1/F3):** Given the effect is noise-comparable at full n, do we gate the paired
   accuracy-delta at all — or gate a lower-variance proxy (tool-call success / turns / cost / absolute
   with-tool floor)? This is the decision that most changes the design.
2. **Value-over-existing-tiers (F2):** If a cheap variant can only reliably catch catastrophic breakage, and
   `mcp_tool_surface_hash` + contract tests + relevance/leak + the $0.20 util-smoke already catch that — what
   *marginal* regression is `utility-gate` uniquely positioned to catch, and is it worth per-run agent spend?
3. **Cadence/cost (F5):** Manual-only, periodic (e.g. pre-release), or hook-nudged on a *narrow*
   surface-hash-affecting trigger? The other four are effectively-free-so-nudge-freely; this one is not.
4. **Sizing (the tempdoc's original question), conditioned on #1:** only answerable once the *metric* is
   chosen — a proxy-rate gate needs far less n than an accuracy-delta gate.

My recommendation for the design pass: **do not port the accuracy-ratchet shape by default.** Lead with a
cheap, deterministic MCP-surface/behavioral-proxy tripwire (near-$0, catches the surface regressions the
existing gates miss), and treat any *paid accuracy* measurement as the deliberate, periodic §M.8-adjacent run
it already is — not a routine gate. This still delivers the tempdoc's goal (agent-utility regressions caught
at edit time) without building a paid instrument whose noise floor exceeds the effect it measures. Pending
founder confirmation on the four questions above.

## Theorization pass 2026-07-02 (agent) — directions, tradeoffs, and a candidate principle

Breadth-first theorization building on the investigation above. This is deliberately a *menu of directions
and the questions that separate them*, not a chosen design — the aim is to surface the framings, tradeoffs,
hidden assumptions, and the recurring system-shape this problem may belong to, so the eventual design pass
starts from a wide field rather than the first-obvious "port the pattern" move.

### T1 — The central reframe: *detection* is not *estimation*

The four existing ratchets and the 624 publication run look similar but answer two different questions:

- **Estimation** (624 §M.8): *what is the true marginal utility of the tool, with a credible confidence
  interval?* Needs an unbiased effect estimate → pairing, high n, seed replication, a calibrated judge.
- **Detection** (this tempdoc's real job): *did the number move away from a known-good state?* Needs only a
  **stable, low-variance control statistic** — not an unbiased effect estimate.

This is the statistical-process-control distinction: you can detect that a process *shifted* without ever
knowing its true mean, provided your control statistic is stable. F1 showed the accuracy-delta is a *terrible*
control statistic (near-null, noise ≈ effect). But that is an indictment of *that statistic*, not of the goal.
Reframing the tempdoc from "a fifth accuracy ratchet" to "**standing change-detection for the agent-utility
surface, instrument TBD**" opens the design space that "apply the established pattern" closes prematurely.

### T2 — Reframe: gate the *cause/invariant*, not the composite *outcome*

Agent-utility is a composite: `utility ≈ f(retrieval quality, tool-surface usability, agent-loop behavior,
judge, corpus, external agent)`. The existing gates already cover *retrieval quality* (relevance/leak) and
the *judge* is 674's concern. What's genuinely uncovered is the **tool-surface usability + agent-loop** layer
— can an LLM still *drive* the tool after a description/schema/loop change. The cheapest, lowest-variance way
to watch that is to gate the *factor* directly rather than the noisy composite outcome:

- The record already fingerprints the surface: `mcp_tool_surface_hash` (`agent_manifest.py:48`) and
  `cli_version`/`agent_model_version` (`agent_manifest.py:97–134`). A **change-detector on the surface hash**
  is $0, deterministic, zero-variance, and fires exactly when the uncovered layer changes.
- This matches an existing grain in the kernel itself: `compare_engine_sets` / `assert_cohort_engines`
  (`ratchet_kernel.py:58–129`) already *refuse a comparison* when a structural precondition (the realized
  engine set) differs. The agent-utility analog is "assert the tool/agent surface is unchanged; a change is
  the signal." Gating an invariant is what makes the other cheap gates cheap.

"Gate the cause, not the symptom" also rhymes with the repo's own `fix-root-causes-not-symptoms` culture: the
composite accuracy is the symptom; the surface identity is nearer the cause.

### T3 — A menu of solution directions (with the tradeoff that separates each)

Ordered roughly cheap→expensive. Not mutually exclusive; several compose.

1. **Surface/contract change-detector.** Gate `mcp_tool_surface_hash` + the runtime contract
   (`RuntimeContractTest`, `McpProtocolHandlerTest` already exist). *Tradeoff:* catches *that* the surface
   changed, never *whether the change hurt* — it's a "look here now" tripwire, not a quality verdict. Cheapest
   possible; near-zero false-negative on structural breakage, high false-positive on benign renames.
2. **High-contrast smoke corpus.** A tiny, engineered set (util-smoke-style: fabricated facts un-gettable
   without retrieval) where with-tool *should* be ~100% and without-tool ~0%. *Tradeoff:* you engineer the
   corpus to **maximize** signal-to-noise instead of fighting a realistic corpus's noise — so a handful of
   queries gives a decisive, low-variance "the agent can still drive the tool at all" verdict for ~$0.20–1.
   Does **not** measure realistic marginal utility (that's the point — different instrument for a different
   job). Risk: a smoke set can pass while realistic utility silently rots (Goodhart).
3. **Low-variance behavioral proxy.** Gate tool-invocation/retrieval-call **success rate**, **turns-to-answer**,
   or **cost/token** rather than accuracy — all already in the per-query record and far tighter than a
   paired-binary McNemar delta. *Tradeoff:* proxies for usability, not correctness; a tool can be called
   successfully and still return worse content (but *that* is what relevance/leak already guard).
4. **Absolute with-tool accuracy floor** (drop the pairing). *Tradeoff:* removes McNemar pairing noise and the
   "is it the baseline or the tool that moved" ambiguity, at the cost of the counterfactual — acceptable for a
   *tripwire*, not for a *claim*.
5. **Sequential / adaptive sampling** (SPRT-style): run until the pass/fail decision is confident *or* a
   dollar cap is hit, instead of a fixed n. *Tradeoff:* spends little when the tool clearly works, more only
   near the boundary — directly converts the tempdoc's "what fixed n is affordable" into "what *budget cap* is
   affordable," which is the more honest control for a paid, variable-signal gate.
6. **Common-random-numbers variance reduction:** reuse the *same* query/seed draws for baseline and HEAD so
   query-difficulty differences cancel. *Tradeoff:* can materially shrink the effective noise — a lever that
   might partially rescue even the accuracy metric, and one that would help **624's own publication run** too,
   independent of this gate.
7. **Control-chart / drift (CUSUM) over a rolling history** rather than one baseline+tolerance. *Tradeoff:* a
   regression is a *sustained* shift, robust to a single bad draw — but it needs an accumulated *history* of
   runs, which is expensive to build for a paid axis. Tension with the cost reality (F5).
8. **Two-tier: free tier triggers paid tier.** The $0 change-detector (#1) is the *trigger*; a surface/contract
   change is what nudges a human to consider a paid confirmation run (#2/#3). *Tradeoff:* none really — it
   resolves F5's "nudge trains spend-or-ignore" by making the routine tier free and the paid tier deliberate.
   This mirrors 624's own staged D-2 philosophy (cheap phase gates the expensive phase).

### T4 — Hidden assumptions worth naming before committing

- **A1 — "there is a credible baseline to ratchet against."** 624's §M.8 bar is **not yet cleared**; the best
  current estimate is neutral-to-negative and non-significant. **You cannot ratchet a number you have not yet
  credibly established** — re-pinning (`--update-baseline`) on a noisy near-null would pin a coin flip, not a
  floor. This is a real *sequencing dependency* on 624 the tempdoc's non-goals gloss over ("uses whatever
  judge 624 established as trusted" assumes 624 finished; it hasn't).
- **A2 — "the responsible code is ours."** The agent under test is an external, versioned dependency (the
  record captures `cli_version` / `agent_model_version` precisely because it drifts). A utility "regression"
  can be caused by a third-party model/CLI update with *zero* JustSearch change — so an edit-triggered gate can
  red on a change that didn't cause the movement. The other four gates pin local models and have no such
  confound. This is the sharpest risk: the whole "nudge when the responsible code changes" premise assumes we
  control the system under test, and here we control only half of it.
- **A3 — "cheaper means a smaller-n version of the same measurement."** Cost can instead drop by changing
  *what* is measured (proxy / smoke / hash), not *how much*. The tempdoc conflates the two.
- **A4 — "higher is better and the good state is known."** With substitution currently neutral-to-harmful, the
  direction of "regression" is itself ambiguous (did the tool get worse, or file-tools get better?). A ratchet
  presumes a settled monotone "better."

### T5 — Candidate broader principle / recurring shape (named, not yet doctrine)

Two candidate invariants this problem surfaces, both plausibly reusable beyond agent-utility:

1. **A reused kernel carries implicit preconditions; check they still hold for the new instance.** The
   `ratchet_kernel` unifies four gates that *all* share an unstated regime: **cheap, low-variance, local,
   deterministic-ish**. Agent-utility violates all three (paid, high-variance, stochastic, part-external).
   "Add a fifth `evaluate()`" would produce a gate that *looks* like its siblings and silently doesn't work,
   because the kernel's hidden contract is broken. The generalizable lesson — a sibling of the repo's
   projection-vs-fork discipline — is: **before reusing an abstraction, verify the abstraction's implicit
   assumptions survive the new case; a new axis in a different cost/variance regime may need a different
   kernel (e.g. a stochastic-gate kernel with budget-capped sequential sampling and power-aware thresholds),
   not the same one.**
2. **Tier gates by cost; let the cheap structural tier trigger the expensive stochastic tier.** Wherever a
   quality axis is expensive/noisy to measure directly, gate a *cheap invariant that changes when the
   expensive number could change*, and use *that* as the trigger for a deliberate paid measurement. This is
   already latent in the codebase (the surface/engine-set homogeneity checks; 624's staged phases); agent-
   utility is the case that makes it explicit.

Both are candidates, offered for the design pass to accept, sharpen, or reject — not settled doctrine, and
deliberately not yet written into `CLAUDE.md`/rules (that gate is `before-appending-to-rules`).

### T6 — The one pre-design experiment that would settle the most

Before choosing any instrument, compute an **operating-characteristic / power curve** from the variance
already measured in 624's run: *given per-seed stdev ≈ 0.05–0.10, what regression size is detectable at what
n / dollar cost / false-positive rate?* This turns the tempdoc's "what n is affordable" from a guess into a
decision with numbers — and it may reveal that a paid accuracy gate is only viable for, say, ≥15pp swings, at
which point directions T3-#1/#2/#3 (cheap, structural, high-contrast) become the obvious choice on their
merits rather than by argument. It costs ~nothing (reuse existing logs) and is the highest-information first
step.

## Long-term design — settled direction (2026-07-02, agent design pass)

This is the design the problem actually calls for, matched to its real scope and grounded in seams that
already exist in the codebase (so it extends them rather than forking a parallel mechanism). It supersedes the
original "fifth accuracy ratchet" framing in the frontmatter/scope above, which the investigation (F1) and
theorization (T1–T5) showed is the wrong instrument. It is intentionally general, not implementation-level.

### D1 — Scope lock: what this gate is *for* (and what it is not)

The one quality surface no existing gate covers is the **agent-mediated layer**: whether an LLM can still
successfully *drive* the JustSearch MCP retrieval tool — invoke it, get a usable result, and answer from it —
after a change to the MCP tool surface (descriptions/schemas), the agent loop, or a retrieval-config default.
`relevance_gate` / `leak_gate` measure **raw** retrieval quality (nDCG, recall-survival); neither exercises an
agent driving the tool. That gap is real and worth a standing watch.

- **In scope:** cheap, routine *detection* that this agent-mediated layer regressed.
- **Out of scope (stays 624):** *estimation* of the realistic marginal utility of the tool with a credible CI
  — the publication-grade §M.8 run. Detection ≠ estimation (T1).
- **Out of scope (stays 674):** validating the *judge's* trustworthiness. This gate uses an already-trusted
  cheap judge; on the smoke-corpus substrate below the judge barely matters (fabricated facts → exact-match scores).

### D2 — The design in one sentence

A new `utility-gate` reads a **low-variance control statistic** that already lives in the canonical
`utility-comparison.v1` record, compares it against a **baseline derived from a measured green run** using the
**existing `ratchet_kernel`** — the *same structure the other four gates use* — measured on a **high-contrast
smoke corpus** engineered so the statistic is decisive rather than on a realistic corpus where it is near-null.
(The §Pre-implementation confidence pass corrected one detail: the gate reads the composed *record* directly,
`relevance_gate`-style, **not** the `projections/` registry — that registry is wired into `jseval run`, not the
utility pipeline. The correction *shrinks* the build.)

### D3 — Why this shape: it conforms to three existing seams and forks none

- **Canonical-record consumer seam** (tempdoc 553/622/623). Estimation already lives in the canonical
  `utility-comparison.v1` record; detection becomes a *second consumer* of that same record — the way
  `relevance_gate` is a second consumer of a release run's summary. The control statistic (the condition-C
  absolute pass rate) is **already a field in the record** (`measured[corpus][model].accuracy.with_tool`, or
  `arms.substitution_c` on a 3-condition run), so the gate is a thin comparator, not a new derivation. No new
  record, no fork of `utility_comparison.py` / `utility_judge.py` (honoring this tempdoc's non-goals). (An
  earlier draft framed this as reusing the `projections/` registry like `leak_gate` does; the confidence pass
  found that registry is invoked only by `jseval run` — `run.py:403` — not the utility pipeline, so reading the
  record directly is both correct and smaller.)
- **`ratchet_kernel` seam** (tempdoc 640 K). Load baseline → resolve run → read projection → `evaluate` → emit
  0/1/2. Reused verbatim; the baseline is *derived from a measured green run*, never hand-typed (the
  `leak_gate.derive_baselines` / `project_*_to_*_baselines` discipline). No new kernel.
- **Trigger seam** (`search-engine-hint`). A narrow *third* trigger class — edits that change the MCP tool
  surface / agent loop specifically — nudges this gate, distinct from the retrieval-ranking and inference-path
  classes that hook already carries.

### D4 — The control statistic (the F1 fix): what the projection emits

Not the paired McNemar accuracy delta (the worst-conditioned quantity in the record). On the smoke-corpus substrate:

- **Primary — an absolute with-tool success floor.** The smoke corpus is built so with-tool ≈ 1.0 and
  without-tool ≈ 0.0 by construction, so a simple "with-tool answered correctly" rate is a stable, decisive
  floor — no pairing, no McNemar noise, no "did the baseline move or the tool move" ambiguity.
- **Secondary (where the tool-call trace exists) — a tool-drive success rate:** the agent invoked the MCP
  tool at least once and it returned without error. Note a real constraint recorded in the machinery: the
  Inspect-AI execution path does not capture individual tool-call blocks (`agent_utility_run.py` As-built #7
  follow-up note), so this secondary signal is available only on the `run_agent_eval` (stream-json) path; the
  primary floor works on both. The design leans on the primary and treats the secondary as an optional
  enrichment, not a dependency.

Both are near-deterministic and directly diagnostic of the uncovered layer — a broken tool description or a
loop regression drops them visibly; the raw-retrieval gates would not move.

### D5 — Why the smoke-corpus substrate is the load-bearing design choice (it dissolves three hidden assumptions)

Choosing a high-contrast, engineered corpus over a realistic one is not a shortcut — it is what makes a
standing gate *possible at all*, and it resolves the theorization's own objections without new infrastructure:

- **Dissolves F1 / A3 (noise & instrument).** Signal is huge by construction, so run-to-run variance is small
  → the measurement stays inside the *cheap/low-variance regime the existing kernel was built for*. **No
  stochastic-gate kernel is needed** (see the reach note P2 below — the principle is honored by reshaping the
  input, not by building a heavier instrument).
- **Dissolves A1 (no credible baseline yet).** "with-tool ≈ 100% / without ≈ 0% on fabricated facts" is
  independently and cheaply establishable *today* — it does **not** wait on 624 clearing its realistic-claim
  §M.8 bar. The routine gate and the credibility claim measure different things and can proceed independently.
- **Mitigates A2 (external-agent confound).** A huge-contrast signal is robust to third-party model/CLI drift
  (a model update will not move a decisive smoke floor from ~100% to ~0% unless something genuinely broke),
  whereas a near-null realistic delta would be swamped by it. The gate still **records the `cli_version` /
  `agent_model_version`** already captured in the manifest and treats a model/CLI change as a *re-baseline
  trigger*, not a regression — the honest handling of the half-of-the-system we don't control.

What the smoke corpus honestly does **not** cover: realistic average marginal utility. It answers "can an agent
still drive the tool," not "does the tool help on typical work." That boundary is stated plainly in the gate's
`coverage` so no downstream reader (or public claim) mistakes a green smoke run for a utility claim — the same
`coverage.does_not_measure` honesty the `utility-comparison.v1` record already carries.

### D6 — Cadence, matched to cost

The paid run is **deliberate / periodic + nudged**, never auto-blocking every CI push: it costs real
agent-call money (~$0.20–1 on the small smoke corpus, per the util-smoke fixture) and needs a live dev stack + the
`claude` CLI. The **nudge is free** (the surface-change trigger); *running* the gate is the deliberate step.
This mirrors how the other four gates are nudged-not-blocking, with an even narrower, cost-aware trigger.

### D7 — Deliberately NOT built (scope discipline — structure the problem does not yet require)

- **No realistic-corpus paid accuracy ratchet.** That is 624's estimation job and would reintroduce F1.
- **No sequential-sampling / control-chart / rolling-history machinery** (T3-#5/#7). Those earn their
  complexity only for a *noisy realistic* gate, which this design specifically avoids building.
- **No new "stochastic-gate kernel."** Recognized as a principle (P2), not built — the smoke corpus keeps us in the
  existing kernel's regime, so the general structure is not yet required.
- **No re-implementation of the record/judge machinery.** Consumer, not fork.

The reusable fixture (a high-contrast smoke corpus) and one registered projection + one `evaluate` function
+ one baseline file + one narrow hook trigger is the whole surface area — small, because the seams already
exist and the hard part was choosing the *right measurement*, not building new infrastructure.

## Design reach — conformance and candidate principles (2026-07-02)

Stepping back from the design to judge how far it reaches.

**Conformance (this is an instance, not a parallel).** The design deliberately reuses four seams the existing
gates already stand on — canonical-record, governed-projection registry, `ratchet_kernel`, and the
`search-engine-hint` trigger — with `leak_gate` as the direct structural template. It introduces no
parallel gate mechanism. Where the codebase offered a usable seam, the design extends it rather than
replacing it.

**Candidate principle P1 — "Engineer the measurement to fit the instrument's regime, rather than building a
new instrument for an ill-conditioned signal."** When an outcome is too noisy or too costly to gate directly,
prefer changing *what* or *where* you measure — a high-contrast fixture, a structural proxy — to bring the
signal into the cheap/stable/deterministic regime your existing gate machinery already handles, over wrapping
heavier statistical machinery around the noisy measurement. *Candidate scope:* any future temptation to "gate a
stochastic or expensive outcome." *Already latently instantiated:* `llm_gate` tames variance with
warmup-discard + medians rather than a new kernel; the util-smoke fixture already exists as a high-contrast
probe. *Existing violations:* none serious — the flag is that the *original framing of this very tempdoc* (a
fifth accuracy ratchet) would have violated it. Recorded as a candidate; **not** promoted to `CLAUDE.md`/rules
here (that is the `before-appending-to-rules` gate's call, and one instance is not yet a doctrine).

**Candidate principle P2 — "A reused kernel carries implicit preconditions; honor them by reshaping the input,
or you genuinely need a different kernel."** A shared abstraction (here `ratchet_kernel`) silently assumes a
regime — cheap, low-variance, local, deterministic-ish. A new axis must either satisfy that regime (this
design's smoke-corpus route) or get its own kernel; it must never masquerade as a sibling while breaking the
contract, because it will *look* correct and silently not work. *Candidate scope:* any reuse of a shared
abstraction across a regime boundary; a sibling of the projection-vs-fork discipline already in `CLAUDE.md`.
*Where it applies next:* tempdoc 674's cross-family judge panel is another paid/stochastic axis — the same
"does the reused machinery's regime still hold" question applies there before it reuses the eval substrate.
Recorded as a candidate, not built.

**Not a new principle — an already-established seam, noted for conformance.** "Detection and estimation are two
consumers of one canonical record, separated at the governed-projection seam" is simply the existing
canonical-record/governed-projection discipline (553/622/623) applied: relevance already shows it (the 623
release object is the public claim; `relevance_gate` is the CI guard; both are projections of the same runs).
Agent-utility conforms to it rather than inventing a variant.

## External research pass (2026-07-02) — aligning with active prior art

The internal-architecture core of this design (reuse the projection registry + `ratchet_kernel` + hook seams)
needs no external input. But three of its choices sit on *actively-researched* ground where checking the
current field was worthwhile: (a) gating an agent-eval metric in CI, (b) the diagnostic-set terminology, and
(c) the metric vocabulary + the model-drift confound (A2). A bounded web pass (2025–2026 sources) produced
four adjustments and several validations. **No external code, text, or assets were copied into the repo — this
was terminology-alignment and prior-art citation only, so the license-and-notices check is not implicated;
sources are linked below for attribution.**

- **R-1 — the shape is an established practice, not an invention.** "Evaluation-driven development" and wiring
  a fixed eval suite into CI as a release gate (Promptfoo, Braintrust) is a named, adopted pattern for exactly
  the failure this tempdoc targets — "when models update, prompts change, or tool APIs evolve, automated evals
  catch regressions before production"
  ([Braintrust, CI/CD evals](https://www.braintrust.dev/articles/best-ai-evals-tools-cicd-2025)). *Effect:*
  positions the gate as conforming to an external practice; no design change.
- **R-2 (terminology fix, applied above) — "canary" means the opposite here.** In LLM-eval, *canary* denotes
  **contamination-detection strings** (the BIG-bench canary GUID; a 2026 method literally named
  [CANARY](https://arxiv.org/abs/2606.01695)) — an identifier that proves test data leaked into training. The
  field's term for "a fast, engineered pass/fail check that catches breakage pre-release" is **smoke test**
  ([canary-release vs smoke-test](https://www.getunleash.io/blog/canary-release-vs-smoke-test);
  [contamination-canary overview](https://llm-stats.com/blog/research/what-is-a-contaminated-llm)). Since this
  doc is public, calling our fixture a "canary" would actively mislead a domain reader into the
  contamination-detection meaning. **Renamed throughout to "high-contrast smoke corpus" (a diagnostic smoke
  test).** This is the one substantive prose change the research pass forced.
- **R-3 (metric vocabulary) — align to pass@1 / Pass^k.** The standard agent-tool metrics (tau-bench, BFCL v4)
  are **pass@1 task-completion** and, for robustness under stochasticity, **Pass^k / Pass@k** across repeated
  trials ([Spheron: BFCL/tau-bench](https://www.spheron.network/blog/tool-calling-benchmarks-bfcl-tau-bench-latency-optimization/)).
  *Effect:* the design's D4 "absolute with-tool success floor" is a pass@1-style rate on the smoke corpus, and
  the principled multi-trial version is Pass^k — naming it in field terms makes the gate legible to outside
  readers and avoids reinventing a metric. The "tool-drive success rate" (did the agent invoke the tool at
  all) is deliberately *coarser* than pass@1 and should be labeled as such, not conflated with task completion.
- **R-4 (A2 validation + a refinement) — model-version drift is a named, active problem, and non-determinism
  persists at temperature 0.** "Model drift occurs when upstream provider updates change behavior without any
  application-code change… regular eval runs detect drift by comparing current scores against historical
  baselines" ([Braintrust eval guide](https://www.braintrust.dev/articles/llm-evaluation-guide);
  [Quantifying non-deterministic drift, arXiv 2601.19934](https://arxiv.org/abs/2601.19934)). This is exactly
  A2, and the field's handling matches D5's "treat a model/CLI change as a re-baseline trigger, compare vs a
  historical baseline." **Refinement adopted:** the most on-point prior art,
  [AgentAssay (arXiv 2603.02601)](https://arxiv.org/abs/2603.02601) — "token-efficient regression testing for
  non-deterministic AI agent workflows" — uses a **three-valued verdict (PASS / FAIL / INCONCLUSIVE)** grounded
  in hypothesis testing, plus **SPRT** and **variance-calibrated trial budgets** to cut trials ~78%. Our gate's
  exit `0/1/2` should adopt the *meaning* of that third state: an underpowered/too-noisy run is **INCONCLUSIVE**
  (exit 2, "could not decide"), never a false FAIL — which is precisely why the high-contrast smoke corpus
  matters (it keeps runs decisive so INCONCLUSIVE stays rare). No new machinery; a semantic sharpening of the
  existing exit-2 path.
- **R-5 (deferred directions have published validation).** AgentAssay's SPRT + adaptive-trial-budget +
  behavioral-fingerprinting is a rigorous blueprint for the *noisy-realistic* gate this design deliberately
  does **not** build (D7 / T3-#5). *Effect:* strengthens the deferral — if a realistic-corpus gate is ever
  warranted, there is a citable method to adopt rather than invent, and building it now would be premature.

Net: the research pass validated the design's shape and its deferrals, corrected one genuinely misleading term
(canary→smoke), and gave the gate's metric + exit-code semantics field-standard names (pass@1/Pass^k;
PASS/FAIL/INCONCLUSIVE). None of it changed the architecture — it sharpened the vocabulary and confirmed the
scope line. (Judge-reliability best practice — ~80% agreement vs 20–30 human labels before a judge gates —
also surfaced; it reinforces the 673↔674 boundary rather than changing this gate, which leans on the
already-trusted cheap judge and a smoke corpus where exact-match largely suffices.)

## Pre-implementation confidence pass (2026-07-02) — de-risking before building

A read-only investigation (plus reuse of *existing* util-smoke run artifacts already on disk — no new paid
runs) against the five uncertainties that could surprise implementation. Result: all five resolve favorably or
with a build-*shrinking* correction. Primary-source evidence cited.

- **U1 (the load-bearing empirical premise) — RESOLVED, FAVORABLE.** Existing util-smoke runs already record
  condition-C behavior. On the 2-query fixture, condition C (JustSearch-only) scored **2/2 = 100%**
  (`util-smoke/out/agent-eval-C-haiku.json`) — the tool *does* drive successfully through an agent. Better, a
  larger 3-seed/n=100 validation run (`util-smoke/floor-inspect/utility-comparison.v1.json`, corpus
  `mixed/multihop-rag`) records the **condition-C absolute pass rate = 0.92 with a seed-envelope stdev of just
  0.0153**. That is the crux: the *absolute C-floor is a low-variance quantity* (~1.5% at n=100/3-seed), unlike
  the near-null paired *delta* that F1 correctly rejected. Two design facts confirmed by the same data:
  (a) condition A (file-tools-only) *also* scored 2/2 = 100% on the tiny corpus, proving the gate must use the
  **single-arm C floor, not a C-vs-A delta** (there is no vs-A contrast on a small corpus — exactly D4/D5);
  (b) the regression this gate must catch (a broken tool surface → the agent can't retrieve → the floor
  collapses by *tens* of points) is huge relative to the ~1.5–5% floor noise, so **even a cheap floor catches
  it** — dissolving F2's "only catches catastrophic" worry, because agent-drive regressions *are* large-signal
  by nature (subtle ranking drops stay `relevance_gate`/`leak_gate`'s job). One honest correction to D6's cost:
  the "~$0.20–1" figure was the 2-query toy; a *statistically stable* floor wants n≈30–100 — still cheap
  (~$4–12 single-seed at ~$0.10–0.14/query, ~$36 at n=100/3-seed), but not sub-dollar. The cost/stability curve
  is now measured, not guessed.
- **U2 (integration) — RESOLVED, with a build-shrinking correction.** The `projections/` registry's
  `run_all` is invoked **only** by `jseval run` (`run.py:403`), *not* by the utility pipeline
  (`commands/utility.py`), so agent-utility runs emit no `projections/` dir. The gate therefore reads the
  composed `utility-comparison.v1.json` **record directly** (`relevance_gate`-style), and the control statistic
  is already a field in that record — so this is *smaller* than "add a projection." D2/D3 corrected above.
- **U3 (hook trigger) — RESOLVED.** The MCP tool surface is exactly two files:
  `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java` and `McpProtocolHandler.java` — and
  `mcp_tool_surface_hash` (`agent_manifest.py:48`) hashes precisely that `tools/list` payload
  (name+description+inputSchema). So the hook's narrow *third* trigger class is the glob
  `modules/ui/**/api/mcp/**` — far narrower than the existing retrieval/inference classes, as tempdoc item 3
  predicted.
- **U4 (CLI + baseline mechanics) — RESOLVED, mechanical.** Gates are registered in one `COMMANDS = [...]`
  list in `commands/gates.py` (line 729); `leak-gate` + `leak-gate-derive` (gates.py:236 / :332) are the exact
  template for `utility-gate` + `utility-gate-derive`, `--update-baseline`, and the default baseline-file path.
  No new plumbing.
- **U5 (secondary signal) — RESOLVED, confirms design.** The Inspect execution path does not capture
  per-tool-call detail (`agent_utility_run.py` As-built #7 note), so the optional *tool-drive success rate*
  needs the stream-json `run_agent_eval` path; the primary C-floor works on both. The design correctly leans on
  the primary and treats the secondary as optional enrichment.

**Net effect on the design:** unchanged in shape, *smaller* in build (record-consumer, not projection-registry),
and its single load-bearing empirical assumption is now backed by real measured variance (C-floor 0.92±0.015)
rather than hope. The one residual is the cost/stability point (a stable floor is ~$4–36/run, not sub-dollar) —
a cadence/budget input for D6, not an architecture risk.

**Confidence rating for the remaining (implementation) work: 8/10.** The build is small and every seam it reuses
is now verified end-to-end against source; the instrument's variance is measured and favorable. The two points
keeping it below 9–10 are both *empirical calibration*, not design risk: (1) the exact n/seed and floor−tolerance
that balance cost vs false-positive rate still want one deliberate green calibration run to pin (the same
`--update-baseline` step the other gates have); (2) the model/CLI-drift re-baseline policy (A2) is sound in
principle but unexercised. Neither can break the architecture; both are first-run tuning.

### Implementation surface (files the remaining work will touch — for cross-worktree coordination)

Enumerated so a parallel-agent overlap check is a glance, not a re-derivation. **Bold = shared infra other
worktrees may also edit** (merge-conflict surface); the rest are net-new files unique to this work.

- CREATE `scripts/jseval/jseval/utility_gate.py` — pure `evaluate(record, corpus, model)` + `derive_baselines(...)` (leak_gate template).
- CREATE `scripts/jseval/utility-ratchet-baselines.v1.json` — the pinned baseline file.
- CREATE a small smoke-corpus fixture + queries (extend `scripts/jseval/util-smoke/` or a sibling) and tests under `scripts/jseval/tests/`.
- **MODIFY `scripts/jseval/jseval/commands/gates.py`** — add `cmd_utility_gate` (+ `-derive`) to the single `COMMANDS = [...]` list (~line 729). *Highest-contention file: every gate lives here.*
- **MODIFY `scripts/agent-analytics/hooks/search-engine-hint.mjs`** — add the narrow `modules/ui/**/api/mcp/**` third trigger class.
- **MODIFY (only if the gate/hook are registered as rules)** `CLAUDE.md` Pre-merge table, `.claude/rules/hooks-reference.md`, `.claude/rules/tier-register.md` (+ a `prose-tier-register` changeset). *Always-loaded shared files — high conflict risk.*
- READ-ONLY (no edits): `utility_comparison.py`, `agent_manifest.py`, `ratchet_kernel.py`, `leak_gate.py`, `modules/ui/.../api/mcp/McpToolSurface.java`.

### Cross-worktree coordination (snapshot 2026-07-02, active worktrees + open PRs)

No *blocking* long-term interference — the remaining work is additive. The real overlaps are shared-registry
merge surfaces (mechanical, resolved by rebasing after whoever merges first), not design conflicts. By number
proximity **and** by file overlap (the latter catches out-of-range worktrees the number window misses):

- **`commands/gates.py` `COMMANDS` list — 3 branches append to it** (`worktree-643-judge-arbitration`,
  `worktree-643-judge-rung-conformance`, `worktree-644-capability`). My `utility-gate` appends to the same
  single list → predictable append-conflict, mechanical. (643/644 are *out* of the 653–693 number window —
  caught only by file overlap, per the brief.)
- **`ratchet_kernel.py` — `worktree-644-capability` edits it** (the kernel this gate *reuses*). Semantic risk
  only if 644 changes `run_gate`/`finalize_report`/`assert_cohort_engines` signatures. Mitigant: 644's
  engine-set homogeneity code is already in main/this base and 644's branch is ~2 days idle → low residual;
  build against current kernel, re-verify if 644 lands more.
- **`CLAUDE.md` + `.claude/rules/tier-register.md` — `worktree-664-publish-gate` (both) and `644` (CLAUDE.md)**.
  Only bites *if* this work registers a Pre-merge-table / tier-register row. 664 (in-range, a governance
  "publish-gate") is the most likely tier-register co-editor → coordinate the row addition if both land.
- **No overlap at all** on `search-engine-hint.mjs`, `.claude/rules/hooks-reference.md`, or
  `modules/ui/**/api/mcp/**` across any active worktree — the hook edit and the surface the gate watches are
  currently conflict-free.
- **Topical (not file) adjacency:** 655 (MCP conformance/capability policy) owns the MCP tool surface this
  gate *watches*; a surface change there is the re-baseline trigger the gate is *designed* to react to, not a
  conflict (dependency direction, already largely landed). 674 (sibling) and 624 (parent, = this branch) own
  the eval substrate this gate consumes read-only.
- Open PRs (#41–43 dependabot, #24 docs-662, #21 docs-653, #12 codex-651) touch none of this work's files.

## Implementation (2026-07-02)

Built per §Long-term design's D1 scope-lock (record-consumer, detection-not-estimation, smoke-not-realistic)
and §Pre-implementation confidence pass's corrections (record-consumer, not a `projections/` registry entry).
Stayed in the `624-agent-utility-hardening` worktree; no PR opened (not requested).

**Shipped:**
- `scripts/jseval/jseval/utility_gate.py` — `evaluate()` + `derive_baselines()`, mirroring
  `leak_gate.py`/`llm_gate.py`. `_c_floor(cell)` extracts condition C's absolute accuracy, always
  preferring `arms.substitution_c` when present so a 3-condition (A/B/C) cell whose top-level
  `accuracy` reflects a DIFFERENT primary arm (B) is never misattributed to C — a real extraction
  subtlety only visible once the real `abc-validate` record was inspected (its `primary_arm` is
  `"addition_b"`, top-level `accuracy.with_tool=0.8` is B's, and C's real 1.0 lives in
  `arms.substitution_c`). Covered by a dedicated regression test.
- `scripts/jseval/jseval/commands/gates.py` — `utility-gate` (reads a record + baseline, inline
  `--update-baseline` with the `baseline_shift` relaxation guard, `lower_is_better=False` since
  `c_floor_min` is higher-is-better, unlike leak's ceiling) and `utility-gate-derive` (batch-derive
  from multiple records), both registered in `COMMANDS` and added to the `changeset-new --gate` choice
  list. `commands/inventory.generated.json` regenerated (surface-lock test green, 72 commands).
- `scripts/jseval/utility-ratchet-baselines.v1.json` — pinned from the two committed util-smoke
  records, **no new paid run required**: `golden/util-smoke` (c_floor_min 1.0, tolerance 0.5 — the
  n=2/1-seed toy fixture, wide on purpose to avoid single-query flapping) and `mixed/multihop-rag`
  (c_floor_min 0.92, tolerance 0.05 — the n=100/3-seed corpus, tight because its measured seed stdev
  is only 0.0153; this is the gate's real low-variance detection instrument).
- `scripts/jseval/tests/test_utility_gate.py` — 10 tests: pass/regression/tolerance-band cases,
  un-pinned skip, malformed-baseline and missing-cell data errors, the arms-vs-top-level extraction
  (including the primary-arm-is-B trap above), `derive_baselines` is measured, and a cross-check of
  `_c_floor` against all three real on-disk util-smoke records (`out/`, `floor-inspect/`,
  `abc-validate/`) — guards the extraction logic against drifting from the real record shape.
- `scripts/agent-analytics/hooks/search-engine-hint.mjs` — a third, narrow trigger class
  (`modules/ui/src/main/java/io/justsearch/ui/api/mcp/**`) nudging `utility-gate`, explicitly framed
  as paid/deliberate/periodic (unlike the other two free-local-inference nudges) per §D6/§F5.
  Manually verified: fires with the utility nudge on `McpToolSurface.java`; silent on an unrelated
  `api/health/` path.
- `.claude/rules/hooks-reference.md` + `.claude/skills/jseval/SKILL.md` — extended the
  `search-engine-hint` entry and the "Standing ratchets" section to a fifth gate.

**Verification (all green):**
- `python -m pytest scripts/jseval/tests/test_utility_gate.py` — 10/10 passed.
- Full suite `python -m pytest scripts/jseval/tests` — 1418 passed, 2 failed (pre-existing,
  `test_correction_probe.py`, a missing data file absent on `main` too — confirmed unrelated, logged
  as an observation, not fixed per `log-pre-existing-issues`).
- `python -m jseval.commands.inventory --check` — inventory OK (72 commands).
- Live CLI against the real committed records: `utility-gate --record util-smoke/out/... --corpus
  golden/util-smoke` → exit 0; `--record util-smoke/floor-inspect/... --corpus mixed/multihop-rag` →
  exit 0; a synthetically C-lowered copy of the floor-inspect record → exit 1 (regression detected
  correctly); `--update-baseline` on a lowered record → refused by the relaxation guard (exit 1, no
  file write) with a clear "needs a justified changeset" error; on a raised record → accepted (file
  updated). The baseline file was regenerated clean from real data afterward so no synthetic test
  values were left pinned.
- Hook: crafted stdin JSON for both a triggering and a non-triggering `Edit` path, confirmed via
  direct `node` invocation (no test harness needed — matches the hook's existing manual-verification
  precedent for `search-engine-hint`, which had no `.test.mjs` before this change either).
- No user-visible UI surface exists for this work (dev-tooling/eval-CI only) — browser validation is
  N/A, as scoped in the implementation plan.
- Pre-existing WIP in this shared worktree (`docs/tempdocs/624-*.md`, VDU/`HeadAssembly` changes,
  `commands/utility.py`, `utility_judge.py` + its test) was left untouched — confirmed via the
  session's opening `git status` and re-checked against the final diff.

**Known gap, left open deliberately:** the baseline is pinned from *existing* committed records, not a
fresh live run — so it has never been exercised through the full `agent-eval` → `utility-compose` →
`utility-gate` path end-to-end against a live dev stack. This was the plan's explicitly optional,
separately-gated step (needs a free shared dev stack + ~$0.20–0.60 spend) and was not run this pass.
The committed-record path already validates the gate's logic and extraction correctness; the live
path validates plumbing only, at a small, real dollar cost — a good first live-stack smoke check
before this gate's first real re-pin.

## Post-implementation review fix (2026-07-02)

A critical review pass (implementer-independent re-read of the diff against tempdoc 673's design, per
`critical-analysis-pass`) found two real defects in `cmd_utility_gate`'s `--update-baseline` path, both
now fixed and regression-tested:

1. **Merge-scope bug (safety-relevant).** `--update-baseline` derives a candidate baseline from the
   *whole* input record via `derive_baselines`, but a single `utility-comparison.v1` record can
   legitimately measure **multiple corpora** (`compose_utility` groups cells by `(corpus, model)` across
   all `run_summaries` handed to it — nothing restricts one record to one corpus). The original code ran
   the `baseline_shift.assert_baseline_not_relaxed` justification guard for only the one `--corpus` named
   on the command line, then wrote back `proj["baselines"]` **in full** — silently re-pinning every OTHER
   corpus present in the same record with zero relaxation check. This defeated the exact "a floor can't be
   silently relaxed without a classified, justified changeset" discipline the gate's own inline comment
   claims to provide, for any corpus other than the one explicitly named. Not caught by the original test
   suite because no fixture had two corpora in one record. **Fix:** the merge is now scoped to exactly the
   named `corpus` key (`merged_baselines[corpus] = proj["baselines"][corpus]`), and the file's own
   top-level metadata (`note`/`tempdoc`/custom `tolerance_default_abs`) is preserved on an existing file
   rather than clobbered by `**proj`. Regression-tested end-to-end via a real `CliRunner` invocation
   (`test_update_baseline_cli_scopes_relaxation_guard_to_one_corpus`) with a synthetic two-corpus record
   where the untargeted corpus carries a large simulated regression — asserts its pinned floor is
   byte-for-byte unchanged after the call, and live-verified once more by hand against the real baseline
   file (a scratch multi-corpus record confirmed `mixed/multihop-rag` stayed at 0.92 despite the record
   carrying 0.10 for it).
2. **Missing schema validation.** Neither `utility_gate.py` nor the CLI checked
   `record.get("schema")`. A real, existing sibling schema in this codebase,
   `utility-comparison-cross-corpus.v1`, has a genuinely different `measured` shape (keyed by *model*, not
   *corpus*) — pointing `--record` at one would silently proceed rather than fail closed, since v1's
   design is explicitly scoped to the standard per-corpus schema only (§D1/§D3). **Fix:** added
   `utility_gate.check_schema()`, wired into `evaluate()` (exit 2 on mismatch), `cmd_utility_gate`'s
   `--update-baseline` path (exit 2 before any write), and `derive_baselines`/`cmd_utility_gate_derive`
   (skip + `WARN:` print per mismatched input file, mirroring `leak-gate-derive`'s existing WARN
   convention for unusable inputs — a batch continues past one bad file rather than crashing). Five new
   tests cover both the pure-function and CLI-batch paths.

All 16 tests in `test_utility_gate.py` pass (10 original + 6 new), and the full jseval suite was
re-verified green (no new failures beyond the 2 pre-existing/unrelated `test_correction_probe.py` ones
already logged). The real committed baseline file was regenerated clean from the real util-smoke records
after live-testing (the manual end-to-end verification of fix #1 legitimately wrote to it, same as the
first implementation pass's discipline).

## Design-conformance critical review (2026-07-02) — findings before the fix design

A second, independent critical pass re-read the *implementation* against the *tempdoc's own design intent*
(not just against its own code, the way §Post-implementation review fix did) and found the implementation does
**not** fully satisfy the settled design in §Long-term design:

1. **Major — the shipped gate pins a baseline for a real corpus, not a fabricated smoke corpus, for HALF the
   gate.** D1/D4/D5/D7 are unambiguous and repeated: the substrate MUST be "a high-contrast smoke corpus
   (fabricated facts un-gettable without retrieval)," and D7 explicitly rules out "No realistic-corpus paid
   accuracy ratchet... would reintroduce F1." The implementation pinned baselines for `golden/util-smoke`
   (correctly fabricated) **and** `mixed/multihop-rag` — confirmed via `coverage.contamination_class` to be
   **MultiHop-RAG (COLM 2024)**, a real public academic benchmark (`contamination_class: "public-pre-cutoff"`,
   vs. util-smoke's own `"private-synthetic"`). It was picked because ONE calibration run happened to show low
   seed-variance (0.0153) — but that was a property of that one sample, not a structural guarantee from
   engineered content the way util-smoke's fabricated facts are. This is exactly the F1/A3 reasoning trap the
   tempdoc itself warns against, and it is self-inconsistent with `utility_gate.py`'s own module docstring,
   which describes the substrate as "**the** high-contrast, fabricated-facts smoke corpus (`util-smoke/`)"
   (singular) while the shipped baseline gates a second, non-fabricated corpus under the same instrument.
2. **Moderate — the model/CLI-version drift mitigation (D5's third bullet) was designed but never built.** D5:
   "the gate still records the `cli_version`/`agent_model_version`... and treats a model/CLI change as a
   re-baseline trigger, not a regression" (A2 is named "the sharpest risk"). Neither the baseline schema nor
   `evaluate()` captures or checks these fields — a routine Claude Code CLI upgrade would currently read as an
   indistinguishable straight FAIL.
3. **Minor — the PASS/FAIL/INCONCLUSIVE exit-code vocabulary from R-4 was never adopted.** The research pass
   explicitly commits: "Our gate's exit 0/1/2 should adopt the *meaning* of that third state... a semantic
   sharpening of the existing exit-2 path." Exit 2 is still framed generically as "a data problem" everywhere,
   with no INCONCLUSIVE language in code, docstrings, or the report shape.

## Long-term design for the fixes (2026-07-02) — theorization, not yet implemented

Grounded investigation before design: `coverage.contamination_class` is **already a real, existing per-record
field** (`utility_comparison.py:189/298/310`; observed values `"private-synthetic"` vs. `"public-pre-cutoff"`,
free-form but conventionalized via `commands/utility.py`'s `--contamination-class` CLI option, no formal enum)
— so finding #1's fix needs **no new schema**, only a new consumer-side assertion on a field that already
carries exactly the right signal (it was put there for a different original purpose — labeling data-leak risk
for 624's own *claim* — but is exactly the right marker for "is this corpus engineered/fabricated" too, a nice
reuse). `ratchet_kernel.compare_engine_sets`/`assert_cohort_engines` (tempdoc 644) already establishes a proven
tri-state `(ok / skip / mismatch)` shape for "refuse a ratchet comparison when a structural precondition
differs between the run and the baseline" — this is the exact shape findings #2/#3 need, one level deeper than
D5 anticipated (D5 argued the smoke corpus keeps the *statistics* inside the kernel's cheap/low-variance
regime; this is a second, narrower kind of precondition this gate needs of its *own*, mirroring what 644 built
for the retrieval-engine cohort). `corpus_generate.py` / `corpus_certify.py` / `corpus_fidelity.py` all exist
(624's own axis-renderer + certify/fidelity pipeline for battlefield-scale corpus construction) — confirming a
proper util-smoke corpus expansion has a real, existing pipeline to go through rather than hand-authoring more
markdown files.

### D8 — Corpus-authenticity admission gate (fixes finding #1)

Two parts, one immediate and one durable, deliberately separated (the same "correction vs. prevention"
distinction 624 itself uses for its own defects):

- **Immediate corrective action:** drop the `mixed/multihop-rag` entry from the shipped baseline file. This
  alone restores D1/D4/D5/D7 compliance today. The remaining `golden/util-smoke` entry is honest — small (n=2)
  and structurally correct — rather than large and secretly non-compliant.
- **Durable guard (prevents recurrence, converts prose into a checked invariant):** add a construction-time
  admission check to `derive_baselines`/`cmd_utility_gate_derive`/`cmd_utility_gate --update-baseline` — refuse
  (by default) to pin or re-pin a baseline from a record whose `coverage.contamination_class` is not the
  established "engineered/fabricated" value (`"private-synthetic"`, matching util-smoke's own convention), with
  a clear error naming the mismatch. This is a *pin-time eligibility check* ("can this corpus even become a
  baseline"), a different concern from D9's *comparison-time trust check* ("is this comparison against an
  already-pinned baseline trustworthy") — mirroring 624's own explicit distinction between construction-time
  exclusion and post-hoc detection for its descriptor-collision defect: prevention belongs at the point where
  the bad state would be created, not only checked after the fact. An explicit override flag (mirroring
  `leak_gate`'s `--allow-engine-mismatch`, e.g. `--allow-realistic-corpus`) keeps this from being an absolute
  wall if a founder later deliberately wants a broader corpus set — visible and opt-in, never silent.
- **What this does NOT do:** it doesn't retroactively make `mixed/multihop-rag`'s existing n=100/3-seed
  measurement useless — that data remains valid evidence for 624's own *estimation* work (it already lives in
  `util-smoke/floor-inspect/`, a calibration-validation artifact, not a smoke-gate fixture); it simply stops
  being eligible as *this gate's* detection substrate, which is the correct boundary per D1.

### D9 — Agent-identity drift as a genuine third verdict state (fixes findings #2 and #3 together)

The insight that unifies these two findings: they are the same underlying gap. R-4 wanted a real third state;
D5 named a concrete case that needs one (the agent-under-test is a partially-external, drifting dependency).
Rather than patch each separately, give INCONCLUSIVE real teeth by making version drift its natural home:

- Extend the baseline schema with (nullable, backward-compatible) `cli_version` / `agent_model_version` per
  corpus entry, pinned by `--update-baseline` the same way `c_floor_min` already is — closing D5's own loop
  ("a model/CLI change is a re-baseline trigger": running `--update-baseline` after a deliberate CLI upgrade
  naturally re-pins both the floor *and* the new expected identity in one step).
- Add an identity-homogeneity check to `evaluate()`, shaped exactly like `compare_engine_sets`: `(ok / skip /
  mismatch)` — `skip` when either side lacks a recorded identity (backward-compatible no-op for old baselines,
  matching `compare_engine_sets`'s own "an old release... doesn't break existing baselines" reasoning),
  `mismatch` when the record's `cohort.cli_version`/`agent_model_version` (a real, existing record-level field
  — confirmed at `cohort.cli_version`, e.g. `"2.1.183 (Claude Code)"`) disagrees with the pinned identity.
- Verdict composition ties the two together: a floor drop **with** a recorded identity mismatch is
  **INCONCLUSIVE** (exit 2 — "can't trust this as a regression; the agent under test changed too, re-baseline
  before reading this as JustSearch's fault"), not a false FAIL. A floor drop **without** a mismatch (versions
  match, or no identity was ever pinned) stays a genuine FAIL (exit 1), unchanged from today. A pass stays PASS
  regardless. This directly operationalizes A2 ("the sharpest risk... we control only half the system under
  test") as a real behavioral distinction, not just a documented caveat.
- Add a `report["verdict"]` field (`"PASS"`/`"FAIL"`/`"INCONCLUSIVE"`, derived from `exit_code` + the new
  identity check) so the report is self-documenting without touching the exit-code contract other tooling
  depends on — a small, low-risk way to satisfy R-4's "adopt the *meaning*" without new machinery, matching its
  own explicit "no new machinery; a semantic sharpening of the existing exit-2 path" framing. The *existing*
  exit-2 causes (malformed baseline, unsupported schema, missing cell) also map to INCONCLUSIVE under this
  field — a coherent umbrella, not a special case bolted on only for identity drift.
- **Known granularity limit, inherited from the canonical record's own schema, not invented by this fix:**
  `coverage`/`cohort` are record-level fields; a record spanning multiple corpora shares one contamination
  class and one identity for all of them. This is an existing property of `utility_comparison.compose_utility`,
  not a gap this design needs to separately solve — D8's admission check and D9's identity check both operate
  at the same per-record granularity the canonical record already provides.

### D10 — Deferred: grow util-smoke's own fabricated corpus for real statistical power (NOT required for correctness)

D8's immediate fix leaves the gate *honest* but *statistically weak* — n=2 with a 0.5-wide tolerance barely
distinguishes "broken" from "half-broken." The tempdoc's own confidence pass already named the right target
("a *statistically stable* floor wants n≈30–100"). The correct long-term move is **not** to reach for a
second, real corpus again (that is exactly D8's mistake) but to grow the *same kind* of engineered, fabricated
fixture — more Mortimer-Flux-style facts, not a borrowed benchmark — sized to n≈30–50, built through the
**existing** corpus-construction pipeline this codebase already has for exactly this purpose
(`corpus_generate.py`'s axis-renderer pattern + `corpus_certify.py` + `corpus_fidelity.py`, the same machinery
624 uses for its own battlefield corpora), rather than hand-authoring more markdown files ad hoc. This is
real, separate engineering work — explicitly **not** bundled into D8/D9's correctness fixes, and not required
to close findings #1–#3 (dropping `mixed/multihop-rag` alone closes #1); it is required for the gate to be
*useful* at the power level the tempdoc's own evidence says is needed, not merely *correct*.

### Reach — what this reveals

Two applications of principles already named in this tempdoc's own §Design reach, not new ones:

- **P1 lands on itself.** P1 ("engineer the measurement to fit the instrument's regime") was named for the
  *statistical* substrate choice (D4/D5). Finding #1 shows the same discipline needs to be *enforced*, not just
  *decided once* — a construction-time admission check (D8) is what keeps a future well-intentioned "just add
  one more corpus for better power" edit from silently reintroducing exactly the violation this review found.
  Recognizing a principle and building the structure that enforces it are different steps (per this tempdoc's
  own separation of concerns) — D8 is the belated second step for P1.
- **A sibling of P2, one layer down.** P2 was about the *shared kernel's* implicit preconditions (cheap/local/
  low-variance). D9 shows the *new gate itself* also has implicit preconditions on its subject (a stable agent
  identity) that need the same "assert it before trusting the comparison" treatment `assert_cohort_engines`
  already gives the retrieval-engine cohort — the same shape, recurring one layer further from where P2 first
  named it. Worth watching for again in 674's cross-family judge panel (another partially-external subject).

## Pre-implementation confidence pass (D8/D9/D10) — 2026-07-02

A read-only investigation (no code changes, no live/paid run) against the five uncertainties that could
surprise D8/D9's implementation. Result: all five resolved, two produced real scope corrections to the
theorized design — recorded here so implementation starts from verified facts, not the original guesses.

- **U1 (highest-priority, was potentially design-invalidating) — RESOLVED, with a scope correction.**
  `agent_model_version` is a real field on the raw per-run manifest (`agent_manifest.py:97/134`,
  `agent_utility_run.py:80`), but `compose_utility`'s `cohort = {...}` projection
  (`utility_comparison.py:233–244`) never includes it — only `git_sha`, `cli_version`,
  `mcp_tool_surface_hash`, `judge`, `prompt_template_hash`, `decoding`, `eval_limits`,
  `search_config_cohort_key`, `hardware`. **D9 is corrected: track `cli_version` only, not
  `cli_version`/`agent_model_version` as originally theorized.** Adding `agent_model_version` to the
  canonical record would mean touching `utility_comparison.py`'s cohort projection, which is explicitly out
  of scope (this tempdoc's own non-goal: "not a redesign of the underlying `utility-comparison.v1` record").
  `cli_version` alone is still the main real-world drift signal D5 was written about (a Claude Code CLI
  version bump), so this narrows D9 without hollowing it out.
- **U2 — RESOLVED.** Exactly four files reference `mixed/multihop-rag` as *this gate's* concern and need
  editing alongside the baseline-file fix: `search-engine-hint.mjs:126` (hook nudge example),
  `commands/gates.py:414` (`--corpus` help text), `hooks-reference.md:101` (doc example), and
  `utility-ratchet-baselines.v1.json` itself. Six other files reference `multihop-rag` too
  (`qu_v3_eval.py`, `commands/utility.py`, `test_agent_utility_run.py`,
  `.claude/skills/search-quality/SKILL.md`, etc.) but are pre-existing, general-purpose uses of the corpus
  slug (the corpus's own retrieval-eval registration, unrelated test fixtures) — confirmed unrelated,
  correctly left untouched. D8's "immediate corrective action" is four files, not one.
- **U3 — RESOLVED.** Exactly three existing test fixtures flow through `derive_baselines`/the CLI
  derive/update-baseline paths and lack a `coverage.contamination_class` field: `_RECORD_2COND`,
  `_RECORD_3COND` (`test_derive_baselines_is_measured_not_hand_typed`,
  `test_derive_baselines_skips_cross_corpus_records`), and `_RECORD_MULTI_CORPUS`
  (`test_update_baseline_cli_scopes_relaxation_guard_to_one_corpus`). All three need
  `"coverage": {"contamination_class": "private-synthetic"}` added before D8 ships, or three currently-green
  tests break. **One composition risk flagged for the implementation pass:** `_RECORD_MULTI_CORPUS` is the
  fixture that specifically regression-guards the merge-scope bugfix (§Post-implementation review fix #1) —
  D8's new admission check must not itself *reject* that fixture (which would silently stop exercising the
  merge-scope regression test rather than fail loudly), so the coverage field must be added correctly to
  make it pass both checks, and the implementer should re-run that specific test after adding D8 to confirm
  it's still exercising the original scenario, not skipping it.
- **U4 — RESOLVED, favorable.** `cohort.cli_version` is byte-for-byte identical
  (`"2.1.183 (Claude Code)"`, 21 chars) across all three real committed records (`out/`, `floor-inspect/`,
  `abc-validate/`). Plain string equality is a safe first-pass comparator; no normalization needed for v1.
  (Caveat: only 3 samples from one session/timeframe — can't rule out a future CLI changing its version
  string's format, but that risk is inherent to any version-string comparison, not a design flaw here.)
- **U5 — RESOLVED, mechanical.** `cmd_utility_gate_derive`'s existing schema WARN-skip loop
  (`gates.py:513–518`, added in §Post-implementation review fix #2) is a proven, minimal pattern: call a
  pure check function per input file, `WARN: skipping <path>: <reason>` and `continue` on failure. The same
  shape already exists a second time in `cmd_utility_gate`'s single-record `--update-baseline` path
  (schema-checked before deriving). A contamination-class check slots into both call sites as one more `if`
  block calling a new pure helper — no new structure, a mechanical duplication of an already-twice-proven
  pattern.

**Accepted limitation, stated explicitly (not silently assumed):** `coverage.contamination_class` is a
free-form string set by the operator's `--contamination-class` CLI flag at `utility-compose` time
(`commands/utility.py`), not independently verified against the corpus's actual content. D8's admission
guard defends against *accidental* misuse (the exact mistake this review is fixing — reaching for a
convenient corpus without checking its provenance), not a *deliberately or mistakenly* mislabeled corpus at
compose time. A stronger guarantee (e.g. verifying corpus content against a registered synthetic-corpus
manifest) would be real additional infrastructure and is not proportionate for v1.

**Net effect on the design:** D8 and D9 both remain sound and small — every seam and file they touch is now
verified against source, not assumed. The one real scope change is D9 narrowing to `cli_version`-only
(dropping `agent_model_version`, which the record schema was already silently not carrying). D8's blast
radius grew slightly (4 files, not 1) but each is a small, well-understood edit.

**Confidence rating for the remaining (D8/D9) implementation work: 8/10.** Every uncertainty resolved
favorably or with a small, well-understood scope correction (not a redesign). What keeps it below 9–10: (1)
D9's identity-drift verdict-composition logic touches `evaluate()`'s existing multi-branch early-return
control flow, and inserting a new check in the right place without disturbing the five existing exit-2 paths
wants care (a plausible source of a subtle ordering bug, not a design risk); (2) D8's admission-check default
behavior (hard-refuse vs. an escape-hatch flag) and D9's exact `report["verdict"]` field shape are still
implementation-time judgment calls the theorization named but didn't pin down to the last detail. Neither
can invalidate the design; both are ordinary implementation-detail risk.

## D8/D9 implementation (2026-07-02)

Built exactly per the approved plan. Stayed in `624-agent-utility-hardening`; no PR opened. Scope confirmed
with the user before starting: D8+D9 only (the load-bearing correctness fixes); D10 (corpus growth) stays
explicitly deferred follow-up work.

**D8 — corpus-authenticity admission gate:**
- `scripts/jseval/jseval/utility_gate.py` — `REQUIRED_CONTAMINATION_CLASS = "private-synthetic"` +
  `check_admission(record)`, mirroring `check_schema`'s exact shape. Wired into `derive_baselines`'s
  per-record skip loop (a new `allow_realistic_corpus: bool = False` parameter). Pin-time only —
  `evaluate()` is untouched by this check, as designed (it trusts whatever's already pinned).
- `commands/gates.py` — `--allow-realistic-corpus` (mirroring `leak_gate`'s `--allow-engine-mismatch`)
  added to both `cmd_utility_gate --update-baseline` and `cmd_utility_gate_derive`; both refuse by default
  (exit 2 / `WARN: skipping ...` respectively) and thread the override through to `derive_baselines`.
  `--corpus` help text no longer names `mixed/multihop-rag`.
- `utility-ratchet-baselines.v1.json` regenerated clean — `mixed/multihop-rag` dropped; only
  `golden/util-smoke` (already-compliant) remains pinned.
- `search-engine-hint.mjs` and `hooks-reference.md` — the `mixed/multihop-rag` example dropped from both
  (the only two files the confidence pass's U2 identified beyond the baseline file itself; `jseval/SKILL.md`
  checked and confirmed to have no such reference).
- Live-verified: derive-including-the-realistic-corpus WARN-skips it by default and includes it with
  `--allow-realistic-corpus`; `--update-baseline` targeting the realistic corpus is refused (exit 2, file
  untouched) by default and succeeds with the override.

**D9 — cli_version drift as a genuine third verdict state:**
- `compare_cli_version(current, pinned)` added, mirroring `ratchet_kernel.compare_engine_sets`'s tri-state
  shape exactly (`ok`/`skip`/`mismatch`). `derive_baselines` now pins `cli_version` per corpus (from
  `record["cohort"]["cli_version"]`) alongside `c_floor_min`/`agent_model`.
  `evaluate()`'s regression branch: a floor drop **with** a `cli_version` mismatch downgrades
  `exit_code` from 1 (FAIL) to 2 (INCONCLUSIVE); a floor drop with a match or no recorded identity stays a
  genuine FAIL (backward-compatible, unchanged from v1's behavior). A **pass always stays a pass**
  regardless of drift — the identity check is always computed and recorded in `report["checks"]` for
  transparency, but never escalates a pass or invents a new failure mode on its own, exactly as designed.
- `evaluate()` refactored to a thin public wrapper around a private `_evaluate()` so `report["verdict"]`
  (`"PASS"`/`"FAIL"`/`"INCONCLUSIVE"`) is stamped in one place, without touching any of the function's
  existing early-return branches — directly addressing the confidence pass's own flagged implementation
  risk. The existing data-problem exit-2 paths (malformed baseline, unsupported schema, missing cell) also
  read as `"INCONCLUSIVE"` under this field, a coherent umbrella rather than a special case.
- One real scope correction from the confidence pass carried through cleanly: tracks `cli_version` only,
  not `agent_model_version` (confirmed absent from the canonical record's cohort projection).

**Tests (`test_utility_gate.py`, 33 total — 17 new):** the 3 fixtures the confidence pass's U3 flagged
(`_RECORD_2COND`, `_RECORD_3COND`, `_RECORD_MULTI_CORPUS`) now carry `coverage.contamination_class`;
the flagged composition risk was checked directly — `test_update_baseline_cli_scopes_relaxation_guard_to_one_corpus`
still passes and still exercises its original merge-scope-bugfix purpose, not silently short-circuited by
the new admission check. New coverage: `check_admission` accept/reject/missing-coverage;
`derive_baselines` skip-by-default and `--allow-realistic-corpus` override, at both the pure-function and
CLI level; `compare_cli_version`'s three states; FAIL-vs-INCONCLUSIVE-vs-PASS-stays-PASS under regression +
drift combinations; `report["verdict"]` correctness; `derive_baselines` capturing `cli_version`.

**Verification — all green, including a genuine live end-to-end run (user-approved, small bounded cost):**
- `pytest tests/test_utility_gate.py` — 33/33 passed. Full suite `pytest tests/` — 1445 passed, 2 failed
  (the same pre-existing/unrelated `test_correction_probe.py` failures, unchanged from before this pass).
- `python -m jseval.commands.inventory --check` — OK (72 commands; unaffected, only new options).
- **Live dev-stack smoke run** (`quick_health` confirmed the stack was free, `preflight` ready): started the
  stack from this worktree's dist, ingested `util-smoke/corpus`, ran real condition-A and condition-C
  `agent-eval` calls against the live MCP server (haiku, 2 queries each — both 100% accuracy; total real
  spend **$0.144**, under the ~$0.20–0.60 budget), composed the pair via `utility-compose`
  (`--contamination-class private-synthetic`), then ran `utility-gate` against the fresh record —
  **PASS, exit 0**. Notably, the live record's genuinely-current `cli_version` ("2.1.198 (Claude Code)")
  organically differed from what the older committed baseline had pinned ("2.1.183 (Claude Code)") — D9's
  `agent-identity-stable` check correctly fired with status `"warn"` on real, unstaged data, surfacing the
  drift transparently without affecting the PASS verdict, exactly as designed. This is stronger evidence
  than a synthetic test: the exact real-world scenario D9 exists for occurred naturally during verification
  and was handled correctly. Stack stopped cleanly afterward; the committed baseline file was confirmed
  untouched by the live run (all live artifacts stayed in the scratchpad).
- No user-visible UI exists for this work — browser validation is N/A, as scoped from the outset.

## Status

Implemented (v1 + D8/D9 design-conformance fixes, 2026-07-02). D8/D9 close the load-bearing
design-conformance gap identified in §Design-conformance critical review — the shipped `utility-gate` now
pins baselines only from fabricated/engineered smoke corpora (enforced, not just documented) and correctly
distinguishes a genuine regression from the agent-under-test's own CLI having drifted. D10 (growing the
fabricated corpus for real statistical power) remains explicitly deferred, separately-scoped follow-up work
— not started, by the user's own scope decision this pass. Stays in the `624-agent-utility-hardening`
worktree; **no PR opened** pending an explicit go-ahead. Remaining: D10 (deferred), and — per §D6 — a
founder decision on periodic release-gate cadence beyond the hook nudge.

