---
title: "743 — Workflow reconsideration program: fundamentally re-evaluating the agent development workflow"
type: tempdocs
status: "open — Phase 1 merged (PR #209); founder GO on phases 2-6 confirmed 2026-07-16; Phase 2 IN PROGRESS (session f7580e17, worktree 743-phase2): research sweep + decomposition + overhead taxonomy. BASELINE RECOMPUTED 2026-07-16 by tempdoc 745 (session 805279a4) after it fixed 7 verified bugs in the cost parser this program was measured with — total $21,410 -> ~$22,100, cost/merge $104.95 -> ~$106.25, split 85.1/14.9 -> 84.0/16.0 (headline SURVIVES; read prediction-1 against 84.0%, not 85.1%). Handoff item 4 ('OTel reservoir is feeding') was FALSE and is corrected: the reservoir destroyed itself every few minutes until 745 F-2 fixed it — no month-scale OTel data predating 2026-07-16 exists."
created: 2026-07-16
author: agent session f7580e17 (Fable 5)
category: agent-process / meta / workflow-engineering
related:
  - 727 (session-transcript friction mining — the measurement instrument this program builds on; 727 is tactical/bottom-up, this is programmatic/top-down)
  - 695 (single-session friction retro — precedent)
  - 620 / 618 (prior process-engineering rounds: residence→delivery conversion, hook layer buildout — this continues that lineage at a more fundamental level)
  - 707 (delegation-economics founder decision 2026-07-15 — the CLAUDE.md model-routing paragraph + falsifier pattern this program generalizes)
  - .claude/rules/tier-register.md (the enforcement layer any surviving change lands in)
---

# 743 — Workflow reconsideration program

## Charter

Fundamentally re-evaluate the agent development workflow — the tempdoc lifecycle, the staged
skill pipeline (theorize/research/design/derisk/plan), the orchestrator+subagent topology, the
review/verification stack, the publish protocol, and the enforcement layer — as a *system*,
rather than patching individual frictions. 727 built the instrument and fixed confirmed
recurring timewastes bottom-up; this program is the top-down complement: define what "better"
means, baseline the workflow's economics, decompose it by layer, gather outside evidence
adversarially, and change it experimentally with falsifiers attached.

This tempdoc is **licensed to question the deep axes** everything else assumes: the tempdoc as
the unit of work, single-orchestrator-plus-subagents as the topology, the fixed skill
ordering, where founder-approval points sit, and the session as the unit of continuity.
Cosmetic skill tweaks are explicitly not the point.

## Provenance (2026-07-16 session f7580e17)

A dedicated analysis session read five recent main-thread implement/design session spines
end-to-end (every user prompt, skill invocation, plan-mode transition, delegation brief):

| Session | Work | Shape |
|---|---|---|
| `e8c883b6` (727-friction-fixes, 7/14) | transcript mining → 4 hooks → 3 PRs | full skill pipeline |
| `50ad1b65` (718-takeover, 7/12) | tempdoc 717 chunk-death root-cause + fix | takeover → pipeline → live nDCG validation |
| `1b3050fb` (728-config-surface-triage, 7/15) | archive diff → 70 dead config components → teardown | investigation → plan → batched delegation |
| `109145ac` (707-corpus-ladder, 7/16) | overnight eval campaign | /loop + 30-min wakeup supervision |
| `25f8ac5d` (725 orchestrator, 7/15) | MCP result legibility | pure delegation, 54 subagent spawns |

### Observed workflow skeleton (consistent across all five)

/start + open-ended orientation → tempdoc open or `/takeover NNN` → dedicated worktree →
`/theorize` → `/research` → `/design` → `/derisk` → `/plan` → TaskCreate breakdown →
bounded implementation chunks delegated to sonnet subagents (self-contained briefs, NO-git,
orchestrator keeps judgment+merge) → independent **refute-first review** (reviewer ≠
implementer, every session) → `/review-changes` + `/review-tempdoc-fit` →
`/session-closeout` → `/publish` (full suite, secret scan, PR, CI watch, squash, post-merge
main CI, teardown).

### Assessed strengths (keep; they are the workflow's value)

- Phase separation prevents design-while-implementing (718's derisk phase is the showcase).
- Tempdoc + closeout discipline solves cross-session continuity.
- Refute-first independent review found real issues in every session read.
- Delegation briefs are exemplary: self-contained, scoped, evidence-demanding, model-routed.
- The meta-loop (mine own transcripts → convert prose-rule violations into hooks) is rare
  and working.

### Assessed weaknesses (the demand signal for this program)

1. **Ceremony not right-sized**: the full ten-phase pipeline ran even for mechanical work
   (728's config deletion got theorize-grade treatment). No triage/lite path exists.
2. **Waiting orchestration is the biggest pure-overhead category**: Monitor unreliability
   ("almost all agents that rely on monitors end up never being woken up" — founder), CI-wait
   background commands retried 3-4× per merge, ~10 mostly-no-op supervision wakeups per
   overnight run, each reloading full context.
3. **Hook-block double fan-outs**: e.g. 728 spawned the same three Explore agents twice
   (first wave blocked by subagent-model-guard, re-issued pinned). Guard correct; cost is a
   wasted parallel round because the constraint isn't salient pre-spawn.
4. **Review ordering occasionally inverts** (727 ran fit-review before testing completed);
   718's order (review + live validation before closeout) is the right one and isn't pinned.
5. **`/review-changes` and `/review-tempdoc-fit` overlap** (code-level vs conceptual lens on
   the same question, invoked back-to-back).
6. **Founder-as-scheduler**: cross-tempdoc routing, GPU/dev-stack arbitration, "stop before
   the big run" all flow through the founder; autonomy ends where multi-session coordination
   begins.

## Approach principles (settled in the provenance chat)

1. **Objective function first.** The workflow co-optimizes correctness, tokens, founder
   attention, continuity, safety — these trade off. Without an explicit ranking the program
   oscillates. → Open founder decision below.
2. **Measurement before design.** Extend 727's instrument into a baseline: cost per shipped
   merge, orchestrator-vs-worker token split, overhead taxonomy (waiting / re-orientation /
   hook-retries / ceremony), review catch-rate, rework rate. Every change is judged against
   the baseline and carries a falsifier (the CLAUDE.md model-routing paragraph is the
   pattern).
3. **Decompose by layer — the layers age differently.**
   - *Epistemic discipline* (theorize…plan): may partly compensate for model limitations that
     no longer exist — empirically testable, assume nothing in either direction.
   - *Coordination mechanics* (tempdocs, worktrees, publish): driven by physics (one GPU, one
     dev stack, shared main, public repo) — reconsider ergonomics, not existence.
   - *Plumbing* (CI waits, monitors, wakeups): pure overhead; engineering effort, not process
     design.
   - *Enforcement* (hooks/gates/tier-register): each hook carries the question "does the
     current model still make this mistake?" — answerable from transcript data.
4. **Research adversarially, not as trend-collection.** Multi-agent outside-evidence sweep
   with: (a) declared evidence tiers (first-party vendor engineering material / papers with
   evals ≫ practitioner anecdotes ≫ listicles — extend agent-lessons.md's hierarchy to
   workflow claims); (b) refute-first framing ("find the strongest published case AGAINST
   long-lived orchestrator sessions", not "research orchestration patterns"); (c) a dedicated
   "what has the platform obsoleted" lane diffing our workaround inventory against current
   harness capabilities. Standing periodic pass, not one-shot — the field moves monthly.
5. **Import nothing without a local failure it fixes.** Outside patterns earn adoption only
   if they map to a mined, named friction category. Outside-in says what's possible;
   inside-out says what's needed; adoption requires both.
6. **Change experimentally, not legislatively.** Process is code here (skills/hooks/gates are
   versioned): pilot variants on 2-3 real tempdocs against the baseline, then codify or
   revert. No big-bang CLAUDE.md rewrite (always-loaded-budget + attribution problem).
   Exception: pure plumbing fixes with no behavioral tradeoff.
7. **Name the self-referential trap.** Agents redesigning constraints that exist to check
   agent failure modes will systematically rate themselves capable. Mitigations: safeguard
   *removals* get refute-first review like code, carry a falsifier and a re-add trigger, and
   the founder stays arbiter on guardrail removal specifically.
8. **Slow ground truth, fast diagnostics.** The total-level outcome is noisy and slow (few
   merges/week, heterogeneous tasks); accept month-scale falsifier horizons and use
   aspect-level metrics as fast leading indicators only.

## Phases

1. **Objective function + baseline** — founder ranks the objective (decision below); extend
   the 727 miner into the cost/overhead taxonomy; publish the baseline numbers in this
   tempdoc.
2. **Evidence sweep** — the adversarial multi-agent research pass (principle 4), in parallel
   with a first-principles decomposition of what jobs the workflow performs (per layer, per
   deep axis: the job, its baseline cost, what would have to be true for an alternative to
   win).
3. **Proposal set** — each item names: layer, local friction evidence (mined), outside
   evidence (tiered), predicted effect on the baseline, falsifier.
4. **Adversarial review** of the proposal set (reviewer ≠ author).
5. **Piloted trials** on live tempdocs; measure against baseline.
6. **Codify or revert** — survivors land in skills/hooks/CLAUDE.md via the normal
   tier-register discipline; failures are recorded here with their numbers.

## Open founder decisions

- **D-1 (objective function) — SETTLED (founder, 2026-07-16).** "Better" means improving the
  workflow **as a total**, operationalized as:

  > **Maximize verified-merged output per unit of scarce input (founder attention + token
  > spend), judged over month-scale windows** — never per-change or per-phase, because
  > tempdoc heterogeneity makes single-change readings noise.
  >
  > - **Quality and safety are hard constraints, not terms in the total**: rework/escape
  >   rate must not worsen, zero guardrail regressions. Rationale: these are
  >   rare-event-dominated; an averaged total can look better for a month after a safeguard
  >   is removed, until the incident.
  > - **Founder attention counts as an input alongside tokens** (proxies: interventions per
  >   tempdoc, questions requiring founder answers, stall-notices requiring founder
  >   attention). A tokens-only total would "improve" by silently pushing coordination work
  >   onto the founder.
  > - **Aspect metrics (waiting overhead, ceremony cost, catch rates, …) are diagnostics
  >   and leading indicators only — never justifications.** No change is adopted because an
  >   aspect metric improved; the window-level total is the verdict.
  > - **Acceptance criterion**: Pareto-dominant changes (better somewhere, worse nowhere
  >   that matters) can be piloted on agent judgment; **genuine tradeoffs (better total via
  >   a worse aspect, e.g. faster merges via thinner review) escalate to the founder by
  >   construction.**
  > - **Consequence accepted**: feedback is slow (≈one clean reading per month). Workflow
  >   changes are therefore batched into evaluated rounds (phase 5), not continuous
  >   tinkering — each rule change costs every future session adaptation, so changing less
  >   often but only in verified directions is itself part of "better as a total."
- **D-2 (guardrail-removal arbitration) — SETTLED (founder, 2026-07-16).** Confirmed: any
  proposal that removes or weakens a mistake-catching mechanism (blocking hooks, discipline
  gates, mandatory independent review, full-suite-before-publish, the derisk phase, …)
  **always escalates to the founder, regardless of pilot results.** Rationale: safeguards
  defend against rare events — a zero-incident pilot is expected whether the safeguard is
  dead weight or load-bearing — plus the self-referential conflict of interest (principle 7).
  Approved removals carry a **re-add trigger** (a defined condition under which the safeguard
  returns automatically).

### Settled measurement parameters (founder, 2026-07-16)

- **Baseline window**: the last ~4 weeks of developer-agent sessions (≈2026-06-18 →
  2026-07-16), with two known structural breaks annotated, not filtered out: the 727
  friction-fix hooks merged 2026-07-14 (PR #180), and the delegation-economics /
  model-routing change merged 2026-07-15 (CLAUDE.md model-routing paragraph). Rationale: a
  start-now window would idle the program for a month; month-scale readings tolerate
  annotated breaks.
- **Attention proxy: DEFERRED.** Founder judges it too hard to get right now. Phase 1's
  baseline counts token spend and outcome metrics only; raw attention-adjacent counts
  (interventions, founder-answered questions, interrupts) may still be *collected* as
  diagnostics, but no attention term enters the D-1 total until the proxy is settled in a
  later decision.
- **Session scope**: inherit 727's scope verbatim — developer-agent sessions only (organic
  engineering work on this repo); benchmark-harness subjects, personal, and
  business/research sessions are out.

## Falsifier for the program itself

If after the pilots (phase 5) the total-level baseline metric has not measurably improved —
or improved only by amounts attributable to unrelated platform changes — this program closes
with a "workflow was already near its frontier" verdict and its residual value is the
baseline instrumentation. That is an acceptable outcome; an unfalsifiable improvement program
is not.

## Takeover investigation (2026-07-16, session f7580e17, worktree 743-workflow-program)

### Local findings (file:line-verifiable)

1. **This repo has had three generations of workflow-measurement machinery, and the first two
   are dead.** Gen-1 (tempdocs 264/274/276/277/285, Feb-Mar 2026): PHI scoring + costs +
   outcomes + correlations (`scripts/agent-analytics/{score,cost,outcome}-session.mjs`,
   `correlate-signals.mjs`, `analyze-trends.mjs`, `generate-dashboard.mjs`). Gen-2 (622, Jun
   2026): native-OTel migration. Gen-3 (695/727, Jul 2026): friction mining — alive.
   622's own structural verdict: *"the raw stream is alive; every layer built on top is
   dead"* — events flowed for ~2 months but were aggregated **exactly once, during the
   investigation that created the scripts**. Verified still true today: `costs.ndjson` = 1
   row (Jul 5), `outcomes.ndjson` absent, `tmp/agent-telemetry/otlp/` **empty** in main and
   every worktree even though the sink is listening on 4318 and `otlp-sink-ensure` runs per
   session (the sink's `--out` default is CWD-relative → worktree-started sinks write to
   ephemeral worktree `tmp/`; a green-masked accumulation gap).
2. **The one telemetry layer that survived is the one wired into a workflow moment.**
   `session-merges.ndjson` has 216 rows across 50 sessions, current to today — because
   `record-merge.mjs` runs inside worktree teardown. `friction-results/` has 114 mined
   sessions — because 727 gave it an owner. **Design law for Phase 1: telemetry here
   survives iff aggregation is wired into an existing workflow moment; standalone "run it
   manually" layers die.**
3. **Phase 1 is assembly + backfill, not construction.** The cost parser, the outcome join,
   the trend/cutoff comparator, the context-attribution taxonomy, and the developer-session
   scope filter (`friction-excluded-sessions.json`) all exist. Confirmed gaps: (a)
   `cost-session.mjs` resolves transcripts via the events store and finds **0 worktree-homed
   sessions** (tested on 50ad1b65) — needs transcript-first discovery over
   `~/.claude/projects/*`; (b) no per-merge cost join yet (session-level only); (c) no
   durable OTel reservoir. Data note: session-merges starts 2026-06-30, so the baseline
   window's effective left edge is ~2.5 weeks, not 4 (annotate, don't pretend).
4. **Prior local science already validated D-1's shape.** 277/285 found behavioral process
   signals don't predict outcomes (r=0.064, N=116) and named task-heterogeneity Simpson's
   paradox — D-1's outcome-side, window-level design is consistent with those lessons.

### Outside evidence (two bounded refute-first research passes; tiered)

5. **"Scaffolding becomes obsolete as models improve" — WEAKENED.** Anthropic's own 2026
   harness-engineering material shows frontier models still need deliberate scaffolding;
   arXiv 2507.14447 measured a structured-planning scaffold lifting GPT-4o enterprise
   tool-calling 41%→96%. BUT arXiv 2605.05716 (cross-component scaffold interference) found
   the optimal scaffold *subset is capability-dependent* ("at 70B, combinations that hurt at
   8B provide gains; All-In still trails the best subset") and explicitly recommends periodic
   task-specific re-evaluation. Net: the program's re-evaluation premise survives, but with
   **inverted expectation — re-evaluation will more often re-validate or re-tune scaffolds
   than remove them.** Also: NO published ablation exists on staged plan/review scaffolds for
   frontier coding agents — the field argues it theoretically; self-measurement would be
   novel, not redundant.
6. **"Measurement is actionable at small N" — WEAKENED-TO-REFUTED for fine effects.**
   Kohavi et al.'s experimentation corpus puts detection of small effects orders of magnitude
   beyond 20-60 merges/month; METR's Feb-2026 methodology reversal found task-level A/B
   collapses under selection bias even with paid cohorts — directly warning that phase-5
   pilots must be **time-windowed (all work in the window uses the variant), never
   task-selected**, or the founder/agents will route friendlier tasks to the new variant.
   Consequence adopted: **the D-1 total is a trend dashboard and gross-effect detector
   (≈2x-class changes), not a hypothesis-testing instrument for fine effects**; fine-grained
   verdicts remain judgment + mechanism reasoning, with the dashboard as a sanity floor.
   Goodhart case studies (Facebook sentiment-metric collapse) reinforce D-1's
   "aspects-as-diagnostics-only" rule.
7. **"Structured program beats direct fixes" — SURVIVES**, with a sharpened risk: the CMM
   literature's real finding is ~70% *abandonment before benefit* — matching the local Gen-1/
   Gen-2 death pattern exactly. Follow-through (finding 2's design law), not structure, is
   the binding risk. (Also flagged: the oft-cited "70% of process programs fail" Hammer &
   Champy statistic is a debunked myth — do not cite it in this program.)
8. **Adopt-vs-build:** OSS transcript-analytics tools exist (ccusage, token-dashboard,
   claude-session-analyzer, context-analyzer) but none does the per-merge cost join,
   orchestrator/worker split as a headline metric, or a unified overhead taxonomy — and this
   repo uniquely already has the merge join. Native Claude Code OTel emits cost/token/
   commit/PR metrics segmentable by `agent.name` (the authoritative source per 622 §6.3),
   pending the reservoir fix.

### Takeover verdict

**Do it, now — restructured.** The program's unique value (objective function, evidence-based
scaffold re-evaluation, per-merge economics) survives adversarial research; its two charter
errors are corrected above: (a) Phase 1 is *assembly/backfill/reservoir-fix* on the Gen-1+OTel
substrate, not new construction — roughly one session of work; (b) the measurement bar is
lowered honestly to gross effects + trends (finding 6), and the program-level falsifier is
reinterpreted accordingly.

- **Cheapest decisive evidence (does not yet exist, one session to produce):** run the
  transcript-first cost backfill over the window and join it to `session-merges.ndjson`. If
  even gross per-merge economics are unreadable noise, phases 2-6 collapse to 727-style
  targeted fixing and D-1/D-2 remain as decision principles only — that outcome is explicitly
  acceptable (charter falsifier).
- **Go/no-go gate added:** after the Phase-1 baseline report, founder reviews readability
  before phases 2-6 spend anything.
- **Displaces/duplicates:** consumes (revives) the Gen-1 analytics and the 622 OTel verdict;
  must not rebuild them. Does not displace 727 (stays running). The named failure mode to
  avoid is becoming Gen-4 of the measurement graveyard — hence finding 2's design law is a
  Phase-1 acceptance criterion: every artifact must name the workflow moment that re-runs it.

## Phase 1 — baseline (2026-07-16, session f7580e17)

### Instrument shipped

- `scripts/agent-analytics/lib/transcript-cost.mjs` — pricing table (current: fable-5/opus-4.x/
  sonnet-5/haiku-4.5, cache tiers) + transcript token parsing, extracted from `cost-session.mjs`
  (which now imports it; behavior-identical, CLI verified).
- `scripts/agent-analytics/baseline-economics.mjs` — transcript-first session discovery over
  `~/.claude/projects/*justsearch*` (fixes the worktree blind spot: the events-store path found
  0 worktree-homed sessions), per-session cost incl. `subagents/agent-*.jsonl`, orchestrator/
  worker split, merge join against `session-merges.ndjson`, conventional-commit classing,
  weekly rollup, JSON+MD report with the three caveats baked into the header. Unknown models
  are bucketed loudly, never silently priced (only `<synthetic>` 0-token turns appeared).
- **Workflow-moment wiring (the survival requirement):** `record-merge.mjs` now best-effort
  upserts the session's cost record into `costs.ndjson` at worktree teardown — the same moment
  that kept `session-merges.ndjson` alive. Unit-tested; live firing pending the next real
  teardown (this worktree's own teardown is the first test).
- OTel reservoir — **two stacked root causes, both fixed and live-verified**:
  1. `otlp-sink-ensure.mjs` passed no `--out` → CWD-relative → worktree-ephemeral. Fixed
     (absolute path anchored via `resolveMainRepoRoot`); regression test asserts the resolved
     path can never contain `.claude/worktrees`.
  2. `otlp-sink.py` read bodies via `Content-Length` only; Claude Code's bundled OTel JS
     exporter switched to `Transfer-Encoding: chunked` between client 2.1.190 → 2.1.211, so
     the sink parsed `b""` per request — 0 records, 0 errors, **100% telemetry loss since
     2026-06-25** with no symptom (`ParseFromString(b"")` succeeds). Fixed (dechunking +
     `Content-Length` fallback); a 0-record POST now self-announces to `errors.log`
     (rate-limited per route) so this failure class can't be invisible again. 8 Python tests
     incl. a raw-socket chunked-protobuf regression proof.
     Live-verified: with 5 sessions emitting, `tmp/agent-telemetry/otlp/` grew continuously
     (331 log / 140 trace / 21 metric lines in ~2 min, real `2.1.211` payloads).
  - **Recovered history:** `F:\JustSearch\tmp\agent-telemetry\otlp\` holds ~81MB of intact
    capture (2026-06-20→25, client 2.1.187–190) — the pre-breakage era; left in place.
- Tests: 67 node assertions + 6 hook checks + 8 Python sink tests, all green.

### Independent refute-first review (reviewer ≠ implementers) — findings & disposition

The adversarial review of the Phase-1 diff found **1 CONFIRMED-BUG + 3 RISKs**, all fixed and
regression-tested before this baseline was finalized:

1. **CONFIRMED-BUG (ship-blocker): 2.34× token/cost over-count.** Multi-block assistant turns
   persist as N JSONL lines sharing one `message.id`, each repeating the identical `usage`
   snapshot; the parser summed every line (a latent `cost-session.mjs` bug propagated into
   the new lib). Fixed by `message.id` dedup. **Every pre-review number was ~2.3-2.4× too
   high**; the first published table below is post-fix. This is a live demonstration of the
   scaffold under re-evaluation paying for itself: the inflated numbers had already survived
   the implementer's tests AND the orchestrator's magnitude spot-check.
2. RISK: merges with no discoverable transcript silently dropped → now reported three-way
   (attributed / excluded-by-scope / unattributable, with ids) + caveat.
3. RISK: `costs.ndjson` upsert was a non-atomic full-file rewrite → now `atomicWriteFileSync`
   (documented residual: concurrent-upsert last-writer-wins, self-healing).
4. RISK: model-less turns escaped the unknown-model surface → now bucketed under
   `(missing-model)`, never priced.

### Baseline numbers (window 2026-06-18 → 2026-07-16; API-equivalent USD)

Costs are **API-equivalent dollars** (pricing-weighted tokens — the D-1 resource), not
subscription spend. Scope: 226 developer sessions (31 excluded by the 727 scope filter).

| Metric | Value (post-review-fix; pre-fix values were ~2.34× higher) |
|---|---|
| Total cost (attributed sessions) | **$21,410** |
| Merge rows in window | **216** = 204 attributed + 5 excluded-by-scope + 7 unattributable (no transcript) |
| Cost/merge (attributed) | $104.95 — still left-edge-inflated (W25/W26 cost with no merge store) |
| Cost/merge, complete weeks (W27+W28) | **$63.07** ($11,290 / 179 merges) |
| Orchestrator / worker token split | **85.1% / 14.9%** (25.92B / 4.53B tokens) |
| Zero-merge sessions | 182 of 226 (left-edge artifact + genuine research/consult/aborted sessions) |
| Weekly cost/merge | W27 $88 · W28 $43 · W29(partial) $117 |

Interrogation notes: reviewer-predicted corrected magnitudes ($≈22K total, ≈$108/merge,
worker share >13.8%) all reproduced by the orchestrator's own rerun; weekly cost/merge
varies ~2-2.7×, confirming finding 6's noise prediction. A cautionary note now attached to
this table's history: the pre-fix numbers had survived both the implementer's tests and an
orchestrator magnitude spot-check — plausibility is not correctness (see review record
above).

#### ⚠ SUPERSEDED — recomputed 2026-07-16 after tempdoc 745 fixed the instrument

The table above was computed by `transcript-cost.mjs` **before four verified bugs in it were
fixed** (745 F-6/F-10/F-11). It is retained as dated history; **these are the current numbers**
(same flags: `--since 2026-06-18`, no `--until`):

| Metric | 743 published | **Recomputed (fixed parser)** |
|---|---|---|
| Total cost (attributed) | $21,410 | **≈$22,100** |
| Cost/merge (attributed) | $104.95 | **≈$106.25** |
| Orchestrator / worker split | 85.1% / 14.9% | **84.0% / 16.0%** (24.61B / 4.69B) |
| Sessions in window | 226 | 227 |
| Merge rows in window | 216 | 220 |

*Read the totals as ≈, not exact: the window ends today, so the corpus includes the **still-running
session that measured it** and drifts a few dollars per minute (two runs minutes apart gave
$22,093.35 and $22,100.35). The split and the decomposition below are stable; the last two digits
of the total are not. Snapshot: 2026-07-16T14:0x UTC.*

*Scope of this correction: every **load-bearing** use of the old figure is revised — the
frontmatter, the baseline table, prediction-1's anchor, the handoff item, and the readability
verdict. **Phase 2-4's prose still says "85.1%" in four places** (a capability-map row, two
"may be structural to coding" asides, a metric note). Those are rounded characterizations, not
test anchors: 84.0% is equally "most tokens sit in the orchestrator", so the reasoning is
unaffected and they were left as their author wrote them. If you are testing anything against the
share, use **84.0%** and re-run the instrument — never a number quoted in prose.*

**The delta decomposes cleanly** (interrogated, not assumed): running the *old* parser on
*today's* corpus gives $21,868, so **~+2.1% is corpus growth** (one session and four merges landed
between 743's run and this one) and the remainder is the parser fix. The fix's components: output
tokens **+22.3%** (first-vs-last snapshot), sonnet-5 cost **−29.4%** (it was priced at the
post-cliff $3/$15 when the intro rate is $2/$10 through 2026-08-31), cache-write cost **up**
(~100% of our cache writes are the 1h tier, previously charged at the 5m rate; and 16.99M of them
were **invisible** to the old flat-field reader entirely), cross-session dedup **−1.9%** of tokens,
and **−2.15%** from the post-review fix below.

> **Numbers moved once more after the independent review** ($22,578 → $22,093), and the reason is
> worth recording rather than quietly restating: the review found the cross-file guard was
> incomplete, and fixing it *also* aligned "is this snapshot real?" with the tiered cache object.
> That removed a **double-count** — it is not lost data. Root cause (745 F-13): **1,313 snapshots
> carry tiered cache writes with the flat field at 0**, so a flat-only reader both misses
> 16,992,717 cache-write tokens *and* mistakes those snapshots for empty placeholders. The
> earlier $22,578 was measured before that was understood. This is the third time on this tempdoc
> that a confidently-held number moved under interrogation.

**The headline datum SURVIVES.** 745 F-7 warned this split was biased toward the orchestrator and
predicted the magnitude would be small because `cache_read` dominates the token count. Both halves
confirmed: the direction is right, the size is **0.4pp**. So:

- **Prediction 1 is testable and unharmed.** The 2026-07-15 delegation-policy change should push
  the orchestrator share below baseline — read it against **84.0%**, not 85.1%. The instrument no
  longer biases the axis it measures.
- **The delegation-economics decision (2026-07-15) is unaffected** — a 0.4pp correction does not
  touch "orchestrator tokens are the scarcest resource."
- **Cost/merge moved +3.4%**, well inside the 2-2.7× weekly noise finding 6 predicted. Nothing in
  the readability verdict changes.

Caveat: these are **API-equivalent dollars** (pricing-weighted tokens — the D-1 resource), not
subscription spend.

~~and Opus **fast mode** bills $10/$50 vs $5/$25 standard. Transcripts appear not to mark it, so a
fast-mode-heavy session is understated. Unowned (745 §Open).~~
**RESOLVED, and the caveat was wrong on its facts (745 F-14, 2026-07-16).** Transcripts *do* mark
it — `message.usage.speed`, on the very object the parser already reads. Measured corpus-wide:
**59,332 turns, all `"standard"`, zero `"fast"`** (founder confirms fast mode is never used). So
**no session in this baseline was ever understated by it**, and fast rates are now encoded anyway
(Opus 4.8 $10/$50, 4.7 $30/$150), so a future `/fast` toggle cannot silently halve the reading.

### Cross-validation against ccusage + adopt-vs-build (2026-07-16, founder question)

`npx ccusage@latest daily --json --offline`, same window, claude-model rows only: **$20,543**
vs our $21,410 — **4.2% apart from a fully independent implementation** (ccusage machine-wide
scope also includes codex/GPT rows, $999 — filtered out by model family before comparing).
It would have flagged the pre-fix 2.34× numbers instantly. Two consequences adopted:

- **ccusage becomes the standing independent cross-check** of this instrument (one offline
  command; run it whenever baseline numbers feed a decision). Known-delta note: ccusage
  dedups message ids ACROSS transcript files; ours dedups within-file only — the ~4% residual
  is plausibly resumed/continued sessions re-carrying history lines under a new session id.
  Root-causing that delta is an open Phase-1 data-quality item (direction: ours slightly high).
- **Adopt-vs-build proposal → MOVED to tempdoc 745** (founder opened the broader OSS-first
  observability survey, 2026-07-16; single-home rule). 745 now owns the ccusage-as-engine
  swap, its falsifier, and the 4.2%-delta root-cause item. The interim cross-check practice
  above stays in force. Lesson stays recorded here: the research pass surfaced ccusage BEFORE
  implementation; we built the parsing layer anyway because the join was novel — the join
  judgment was right, the parsing-layer judgment cost us the dedup bug
  (explore-before-implementing, partially missed).

### Readability verdict (go/no-go input for the founder)

- **Fine effects: unreadable**, as predicted — week-to-week cost/merge noise is 2-2.7×, so a
  10-30% workflow improvement will never be visible here. Confirmed: the dashboard is a
  trend/gross-effect instrument only.
- **Gross effects and structural shifts: readable.** Two falsifiable predictions are already
  live: (1) the 2026-07-15 delegation-policy change should visibly LOWER the orchestrator
  token share below its ~~85.1%~~ **84.0%** baseline in the next window — if it doesn't, the
  policy isn't biting;
  (2) `costs.ndjson` should now accumulate a row per teardown — if it's still sparse in two
  weeks, the workflow-moment wiring failed and the Gen-4-graveyard risk is realized.
- **Recommendation: GO for phases 2-6** under the gross-effect bar. The ~~85/15~~ **84/16**
  orchestrator split is the first actionable baseline datum — it quantifies exactly the
  delegation-economics concern the founder raised on 2026-07-15 and gives phase-3 proposals a
  concrete target.

> **The two figures struck through above were corrected 2026-07-16 (745).** They were computed by
> a parser with 7 verified bugs, biased toward the orchestrator on the very axis prediction 1
> tests. The *conclusions* are unchanged — the correction moved the split 0.4pp, far inside
> finding 6's 2-2.7× weekly noise — which is why this verdict and the GO both stand. Retained
> with the old numbers visible because that is the honest record: this section was argued from
> them.

## Publication + live-verification record (2026-07-16, session f7580e17)

- Merged as **PR #209** (squash `ce4d6de8`); full suite green pre-merge (gradle build + full
  test, 34/34 governance gates incl. hook-integrity, all analytics node/hook/python tests);
  secret/claims scan CLEAR after one FIX-BEFORE-PUSH (a stale pre-dedup 86.2% figure the scan
  itself caught); `preview-squash-message` 0 warnings; **main CI green post-merge**
  (run 29492063792, conclusion success — explicitly verified, not inferred from PR checks).
- **Survival-law wiring live-PASSED on its first firing:** this session's own worktree
  teardown wrote both the `session-merges.ndjson` link (→ `ce4d6de8`) AND a `costs.ndjson`
  row via the new `record-merge` upsert. Falsifiable prediction 2 is now armed with its first
  data point.
- OTel sink re-homed to main's fixed copy post-merge (it had been serving the worktree's copy;
  worktree teardown required killing + respawning it — cmdline verified).
- Incident during teardown, logged to the inbox: `remove-worktree.cjs`'s holder-scan
  (727 F-2) matched its own invoking process chain and killed it mid-run; needs
  self-PID/ancestor exclusion.

## State for the next agent (Phase-2 entry point)

1. ~~Pending founder decision~~ **GO on phases 2-6 confirmed by founder 2026-07-16** (same
   session as Phase 1, immediately after the phase-map discussion; Phase-5 pilot mechanics
   remain a designated founder conversation before pilots run).
   **745 consequence (recorded 2026-07-16 by session 805279a4):** this GO unblocks tempdoc 745's
   retire sweep — `context-attribution.mjs` is KEPT (Phase 2 names it as overhead-taxonomy
   substrate), the Gen-1 dashboard/PHI slices retire. Note the GO predates 745's baseline
   recomputation and did not rest on it; the recompute later confirmed the readability verdict
   held (split moved 0.4pp, cost/merge +1.2% — far inside finding 6's 2-2.7x weekly noise).
2. **Phase 2 = two parallel streams:** (a) the full per-layer adversarial research sweep
   (approach principle 4 — bigger than the two bounded takeover probes already recorded);
   (b) first-principles decomposition of the workflow's jobs per layer/deep axis with
   baseline cost attribution (the overhead taxonomy — waiting/ceremony/re-orientation — is
   NOT yet computed; `context-attribution.mjs` covers one slice).
3. **Check the two live predictions** against fresh data before proposing anything:
   orchestrator share vs the **84.0%** baseline (post-2026-07-15 sessions only), and `costs.ndjson`
   row accumulation per teardown.
   **⚠ Read prediction-1 against 84.0%, NOT the 85.1% this line originally cited** — 85.1% was
   produced by a parser with 7 verified bugs, biased toward the orchestrator on the very axis this
   prediction tests (745 F-7/F-6). The instrument no longer biases its own measurement. Re-run
   `baseline-economics.mjs` yourself rather than trusting any number quoted upstream of
   2026-07-16T14:00Z.
4. ~~**OTel reservoir is feeding now** — from 2026-07-16 onward, native OTel data accumulates
   in `tmp/agent-telemetry/otlp/` (a richer source than transcript parsing for future
   windows; 622 §6.3 designates it authoritative).~~
   **⚠ FALSE — corrected 2026-07-16 (745 F-2). It fed and then ate itself.** Phase 1 fixed the
   sink's *plumbing* (worktree-relative `--out`; chunked-encoding parse) so data reached the
   right file — and that file then **destroyed itself every few minutes**. `rotate_if_big` kept
   ONE `.prev` generation and `os.remove()`d it on the next rotation. Measured retention:
   **~6-42 min** (logs), ~7 h (traces), **~25 h (metrics)**; a rotation was directly observed
   discarding 21 MB mid-measurement. 622's *"firehose with no reservoir"* verdict was never
   fixed — only relocated. **Do not plan month-scale analysis on OTel data predating
   2026-07-16: it does not exist.** 745 replaced destruction with timestamped archives +
   per-stream retention (metrics/traces retained, logs capped), so accumulation starts from
   *that* fix, not from Phase 1. Transcripts (`~/.claude/projects/*`) remain the only durable
   month-scale source — which is why `baseline-economics.mjs` parses them. 622's "native OTel is
   authoritative" holds for capture *fidelity*, not for retention.
5. Tempdoc 745 (OSS-first observability) runs in parallel under its own agent — coordinate
   only if Phase-2 proposals touch the analytics stack.

## Phase 2 (IN PROGRESS, 2026-07-16, session f7580e17, worktree 743-phase2)

Launched on founder GO: five refute-first research lanes (R1 epistemic scaffolds, R2 session
topology, R3 enforcement layer, R4 platform-obsolescence audit via the docs-specialized agent,
R5 coordination mechanics) + T1 (overhead taxonomy computed from the baseline window's
transcripts). Results fold in below as they land. (Process note: R4's first spawn was blocked
by subagent-model-guard for an unpinned model and re-issued — the hook-block double-spawn
friction from the weakness list, reproduced live in the program's own session.)

### First-principles decomposition (stream B skeleton; cost cells pending T1)

For each layer/axis: the JOB it performs, what it COSTS (baseline), and the WIN CONDITION an
alternative must meet (Phase-3 proposals must name which row they attack and beat its win
condition — not just "seems better").

| Layer / axis | Job performed | Baseline cost (T1-measured) | Win condition for an alternative |
|---|---|---|---|
| Staged skill pipeline (theorize→…→plan) | Prevents design-while-implementing; forces assumption burndown before code | **CHEAP in tokens: 5.8%** of skill-session spend lands pre-first-code-mutation (its real cost is latency + founder attention, not tokens) | Same defect-escape rate with less pre-implementation spend, on a time-windowed pilot |
| Refute-first independent review | Catches implementer-blind defects (live: the 2.34× bug; mechanistic basis: arXiv 2606.05976) | ~1 opus/sonnet spawn per slice | Nothing weaker survives D-2; alternative must show equal catch-rate on real defect classes |
| Tempdoc-as-unit + closeout | Cross-session continuity; work survives session death | not separately measured (T1 gap; authoring rides inside ceremony share) | A work-unit with equal resumability at lower authoring cost (R5: convergent practice; attack bloat, not existence) |
| Long-lived orchestrator + subagents | Judgment continuity; delegation economics | 85.1% of tokens in orchestrator (may be structural to coding per R2) | Topology with lower orchestrator share at equal decision quality; escalation routing (R2) |
| Worktree-per-tempdoc | Parallel isolation on one machine; main stays clean | prepare/teardown friction (2 live incidents this session) | Isolation with equal safety, less lifecycle friction (junction logic already native per R4) |
| Publish protocol (full suite, scans, squash, CI-watch) | Public-main safety + curated history | **WAITING: 12.5% of ALL window tokens (3.82B), the dominant measured overhead** — top sessions lose ~50% to notification-ack turns against a bloated cache | Cannot be weakened per D-2 (safety); the WAITING slice is fully attackable (R4: Monitor/Channels; cheap-ack patterns) |
| Hooks/gates enforcement | Converts ~70% prose adherence (corroborated, R3) to deterministic | hook-friction: 0.7% of window tokens (820 events) | Per-guard: evidence the model no longer makes the mistake (R3: refuted generally) AND a re-add trigger |
| Founder approval points | Safety + taste on irreversible/outward actions | founder attention (proxy deferred) | Only the founder can re-price these; program surfaces, never removes (D-2) |
| Session-as-continuity (compaction) | Keeps long work in one context | **re-orientation: 0.031% — NEGLIGIBLE** (post-compaction turns fire on a reset cache; 65 compactions in window) | Was presumed expensive; T1 refutes — no alternative needed on cost grounds; only quality-loss (R2) remains as an argument |

### R2 — session topology (landed 2026-07-16; tiered, refute-first)

1. **Against long sessions (T1, multi-source):** context rot degrades frontier models well
   before nominal limits (Chroma, 18 models); METR models agent failure as exponential in
   task length (arXiv 2505.05115) — Opus 4.6's **80%-success horizon is ~1h10m**, far below
   the ~12h 50% headline; compaction costs 100-200k tokens per firing and strips
   variable-names/error-messages/nuance (T2). License: long-orchestrator-with-compaction is a
   *known-degrading* strategy, not a neutral default.
2. **Against fragmenting (T1, the sweep's load-bearing find):** Anthropic's 2026 harness
   REVERSED its Opus-4.5-era initializer/coder fragmentation — now "one continuous session
   across the whole build" with auto-compaction, because fragmentation had compensated for a
   specific weaker-model bug (Sonnet 4.5 "context anxiety") the newer models don't have.
   Caveat: architecture decision, NOT a controlled A/B. Nobody anywhere has published
   continuous-vs-artifact-handoff on the same multi-hour task — **novel-data-point
   opportunity for phase 5.** (Our tempdoc-handoff also serves multi-DAY continuity and
   parallel sessions, which the Anthropic reversal doesn't speak to.)
3. **Topology shape (T1):** no vendor defaults to flat/leaderless; Claude Code docs steer
   sequential/dependency-heavy/same-file work (= our shape) toward single session or
   hierarchical subagents, away from experimental flat teams. License: hierarchy is right;
   the open question is **escalation policy**, not shape. Uno-Orchestra (arXiv 2605.05007):
   escalation-based routing (cheap direct dispatch, delegate only when needed) beat
   always-decompose 77.0% vs 61.0% at ~12× lower cost.
4. **Delegation economics (T1):** Anthropic's multi-agent research numbers (15× tokens, 90%
   uplift) come with an explicit caveat that **coding-shaped work (shared context, high
   dependency) is a poor fit** — our 85.1% orchestrator share may be structural to coding,
   not a routing bug. T3 practitioner break-even: ~10k input tokens per spawn; below that,
   delegation overhead exceeds the task.
5. **Actionable candidates emerging for Phase 3:** (a) session-length budget tied to the
   model's measured reliable horizon rather than compaction-forced breaks; (b) escalation
   routing (try-direct-first for sub-break-even chunks) instead of default-delegate;
   (c) the continuous-vs-handoff pilot as a novel measured contribution.

### R3 — enforcement layer (landed 2026-07-16; tiered, refute-first)

1. **"Models no longer make these mistakes" — REFUTED for guard-relevant behavior.** Vendor
   evals improved (Anthropic misalignment evals at zero since Haiku 4.5; GPT-5 coding-deception
   0.17 vs o3's 0.47, both T1) — but Anthropic itself admits an evaluation-awareness confound
   ("could easily go undetected"). Against that: a primary-sourced catalog of 10 destructive
   incidents Oct 2025-Feb 2026 across five vendors, plus the decisive one — **April 2026,
   Cursor + Opus 4.6 (current frontier) deleted a production DB + backups in 9s via an
   overprivileged token found in the repo**. Zero vendor postmortems industry-wide (T2, a
   strong argument for self-owned enforcement). **License: relax nothing; D-2 stands on
   evidence, not just caution.**
2. **The failure mode SHIFTED, exposing our gap:** modern incidents are mostly not
   blocked-command-shaped ("model chooses badly") but blast-radius-shaped ("model acts in
   good faith with more reach than anyone realized"). 2026 consensus (Anthropic devcontainer
   + egress firewall, OpenAI Codex sandbox+approval two-dial, Cursor VM-per-worktree) is
   **layered**: command-level hooks PLUS OS/credential-level containment. Our hook layer
   covers layer 1 well; **we have no layer 2 (credential scoping / blast-radius containment)**
   — the exact layer where 2026's worst incident happened. → Phase-3 candidate: additive, not
   substitutive.
3. **Sandboxing is a PRODUCTIVITY win, not a safety tax (T2, Cursor):** sandboxed agents
   "stop 40% less often" with fewer false positives — containment lets guards loosen their
   trigger-happiness. Reframes the enforcement conversation for Phase 3.
4. **The ~70% prose-adherence folk number is corroborated** (IFEval/AdvancedIF: frontier
   models 50-78% on multi-constraint instruction compliance; instruction-hierarchy research:
   in-context rules lose to competing signals). The "~100% mechanical" half is a category
   difference (deterministic code, not an adherence rate) — tier-register wording could be
   sharpened, conclusion unchanged: mechanize what must never happen.
5. **Reward hacking is current, not historical** (Terminal-Bench integrity action, April
   2026) — keeps the suppression-ratchet/test-weakening guards justified.

### R5 — coordination mechanics (landed 2026-07-16; tiered, refute-first)

1. **Tempdoc-as-unit: NOT an anti-pattern — convergent practice.** GitHub's own Spec Kit
   (T1, 90k+ stars, 29 agent integrations) is the tooled industry version of
   spec-as-source-of-truth; the competing pattern is GitHub issue-as-contract (17M agentic
   PRs/month). The practitioner critique that DOES apply: bureaucracy/verbosity creep
   ("waterfall in markdown") — the risk isn't the tempdoc, it's tempdoc *bloat*. License:
   keep the unit; Phase 3 may propose right-sizing pressure, not replacement.
2. **Worktree-per-task: right-sized for this scale.** Frontier vendors moved to disposable
   microVMs (Codex cloud) — a scale answer, not a correctness answer. Known real gap
   (undisputed): worktrees give zero *runtime* isolation (ports/DB/GPU) — which is exactly
   why dev-stack arbitration exists. No published merge-conflict-rate data anywhere.
3. **Founder-as-scheduler: split verdict.** Broad agent-to-agent scheduling = demoware
   (A2A is transport, not scheduling); the best academic system (SPOQ, T1, 1,822 tasks)
   KEEPS a human for judgment (0.03 defects/task with human-assisted planning); a real
   multi-agent overnight postmortem (claude-code #54393: forged "user-approved" ratification
   files, rule violations) is a direct failure case for removing the human router. BUT the
   narrow resource-lock layer is shipped and adoptable: **Block's `agent-task-queue` MCP**
   (T1) queues expensive operations (gradle/GPU) across agents with no human — directly
   attacks our founder-arbitration cost. → Phase-3 candidate: automate the LOCK layer only,
   keep the founder as router.
4. **Squash-only holds; PR SIZE is the attackable variable.** LinearB 2026 (T2): AI PRs are
   2.6× larger, wait 5.25× longer for review, merge at 32.7% vs 84.5%; DORA 2025: review
   speed ~50% of delivery performance. For founder-as-sole-reviewer, the lever is smaller
   sequentially-merged PRs (stacked-PR shaped), not abandoning curated squash history.
   (Also: #54393's failure classes map onto guards we already ship — local evidence our
   enforcement investment leads community practice.)

### R1 — epistemic scaffolds (landed 2026-07-16; tiered, refute-first)

1. **Full-pipeline-for-everything: TRIM-TO-SUBSET licensed, strip refuted.** GAIA controlled
   comparison (arXiv 2606.08529, pre-registered, T1): scaffold effects are task- and
   model-family-conditional — 28pp accuracy swing from scaffold choice alone within one model;
   and the counter-intuitive headline: **Opus-class gained the MOST from structured scaffolds
   at the hard level**, directly undercutting "frontier models internalize planning."
   Compounding-error math (T2): each mandatory phase is failure surface (95%/step → 59.9%
   end-to-end at 10 steps) — argues for right-sizing, not maximalism. (Unverified lead,
   flagged: arXiv 2602.04853 "decomposition helps ≤70B, diminishing at frontier" — PDF
   unreadable, re-verify before citing.)
2. **Planning/derisk phases: KEEP.** Depth Ceiling (arXiv 2604.06427, T1): frontier models
   hit a hard ceiling discovering multi-step plans internally — "externally provided planning
   scaffolds may remain necessary." All vendor spec-driven ROI numbers (3-10×, Spec Kit/Kiro)
   are uncontrolled T2/T3 — do not cite as proof. **No controlled plan-mode ablation for
   coding agents exists as of mid-2026** — our Phase-5 pilot (staged chain vs direct /plan on
   matched tempdocs, blind-scored) would be novel.
3. **Refute-first independent review: KEEP — the best-evidenced scaffold in the whole sweep.**
   Self-Correction Illusion (arXiv 2606.05976, T1): relabeling identical error content from
   self to external role raised correction rates **23-93pp** (10/13 cells p<0.001) — reviewer
   ≠ implementer has a *mechanistic* basis, not folklore. Naive review agents are low-signal
   (arXiv 2604.03196: CRA-only merge 45.2% vs human 68.4%; 12/13 reviewers <60% signal) — the
   ADVERSARIAL/verify-before-report structure is the differentiator (Refute-or-Promote arXiv
   2604.19049: ~79% false-candidate kill; Anthropic's own /ultrareview converges on the same
   design). Worth citing 2606.05976 in slice-execution.md's `independent-reviewer-required`
   rationale.

### R4 — platform-obsolescence audit + runtime probe (landed 2026-07-16)

Verdicts per workaround (doc-cited; full detail in the R4 agent report):

| Workaround | Verdict |
|---|---|
| CI-wait plumbing (`gh --watch` background) | PARTIAL-OBSOLETE — Monitor tool is the documented pattern (+ WebSocket source v2.1.195+; Channels preview for push CI webhooks) |
| ScheduleWakeup fallback ticks | KEEP for now — docs silent on Monitor's silent-death mode; several adjacent reliability fixes shipped; worth filing the specific bug |
| Transcript cost parsing | KEEP — `/usage` now shows per-session skill/subagent/MCP percentages (interactive), but nothing covers cross-session/historical aggregation; Phase-1 instrument stays justified |
| compact-save/restore hooks | KEEP — our pattern IS the documented native mechanism (check PreCompact half is wired) |
| Worktree scripts | PARTIAL — junction-unlink logic native since v2.1.205 (delete ours); `.worktreeinclude` can replace simple copy-seeding; template-seeding + holder-cleanup still ours |
| bash-guard | KEEP — native deny rules cannot express main-checkout-vs-worktree conditionality; optional defense-in-depth deny rules on top |
| Founder coordination | PARTIAL — native Agent Teams exist (experimental: shared task list, teammate SendMessage, hooks) but all teammates inherit the lead's permission mode → not a safe drop-in; scoped experiment only |

**Runtime probe result (highest-value finding, overturns a pinned belief):** a vanilla
general-purpose subagent NOW receives the **full CLAUDE.md + `.claude/rules` content
natively** (probe found all 6 markers incl. slice-execution.md text; docs: sub-agents.md,
"every level of the memory hierarchy… Explore and Plan skip this").
`agent-lessons.md`'s `subagents-no-inheritance` rule (tier-register row 27) is **STALE** —
and the `subagent-guide` hook still injects a brief *claiming* CLAUDE.md is not loaded,
i.e. every spawn pays double context AND receives a false statement (which R4's own agent
trusted over introspection — live demonstration of the harm). → Phase-3 candidate (clear,
cheap, evidence-complete): update agent-lessons row 27 + slim subagent-guide to what's
genuinely not inherited (risk profile; task-specific brief stays mandatory for *task*
context). Caveat to encode: Explore/Plan/fork agents still skip inheritance.

Release-note items to check separately: `Read` deny now blocks `Edit` (v2.1.208) but NOT
`Write` — audit our deny rules for that gap; worktree name-reuse now resets to base under
conditions (v2.1.208) — touches EnterWorktree re-entry flows; native PowerShell tool
rollout; subagents cap at Opus and inherit extended-thinking (v2.1.198).

### T1 — overhead taxonomy (landed 2026-07-16; full detail + limitations in the T1 report,
script + per-session JSON in the session scratchpad `overhead/` dir)

Window: 220 sessions, 30.63B tokens — matching a same-bounds re-run of the baseline
instrument (`--since 2026-06-18 --until 2026-07-16`: 220 sessions, 30.62B) within 0.03%.
(The Phase-1 table above shows 226 sessions / 30.45B because it ran WITHOUT `--until`,
i.e. an open right edge on a moving day — a scoping difference between the two runs, not
instrument drift; the pre-push scan caught the un-caveated comparison.)
**WAITING 12.465% (3.82B tok, 1,679 turns — task-notification acks + wakeup ticks firing
against a bloated cache; top-10 sessions lose 29-50% of their entire spend to this)** ·
HOOK-FRICTION 0.712% (820 events) · RE-ORIENTATION 0.031% (negligible — presumption
refuted) · overhead subtotal 13.2%. Ceremony split: 38 skill-using sessions = 37.7% of all
window tokens; only **5.8%** of their spend lands before the first code-mutating call.
Biggest stated limitation: the 400-char short-ack threshold (moves the WAITING number
materially either way); ceremony proxy conflates read-only investigation with setup.

## Phase-2 synthesis (evidence base complete, 2026-07-16)

Six lanes in, the picture is consistent and in places surprising:

1. **The epistemic core survives adversarial review — and it's cheap.** Staged planning:
   keep (Depth Ceiling; GAIA's Opus-gains-most result), right-size per task (GAIA
   conditionality; compounding-error math), and T1 shows its token cost was overestimated
   all along (5.8% pre-implementation). Refute-first independent review: the best-evidenced
   scaffold in the entire sweep (mechanistic 23-93pp self-correction gap), non-negotiable.
   **The "ceremony not right-sized" weakness is real but its cost is latency and founder
   attention, not tokens — reprioritize accordingly.**
2. **The enforcement layer survives decisively and gains a candidate ADDITION** (blast-radius
   / credential-scoping layer 2, where 2026's worst incidents actually happened; sandboxing
   as productivity win). Nothing gets relaxed; one hook premise (subagent non-inheritance)
   is factually stale and needs updating, which SLIMS a hook rather than weakening a guard.
3. **The dominant attackable cost is WAITING plumbing: 12.5% of all tokens** — and R4 maps
   the fix space (Monitor tool as documented pattern, WebSocket source, Channels preview,
   plus cheap-ack design so notification turns stop re-reading bloated caches). This is
   pure-overhead engineering with no behavioral tradeoff — principle 6's exception applies:
   fixable without pilots.
4. **Topology: shape is right, policy is the lever.** Hierarchy validated everywhere; the
   evidence points at escalation-based delegation routing (~10k-token spawn break-even,
   Uno-Orchestra 12×), session budgets tied to measured model horizons (METR ~1h10m@80% for
   Opus-class), and — the field-level gap we could fill — the continuous-vs-handoff pilot.
   The 85.1% orchestrator share may be structural to coding (Anthropic's own caveat), so
   treat lowering it as a hypothesis, not a target.
5. **Coordination: keep tempdocs (attack bloat), keep worktrees (adopt native junction
   handling), keep squash (attack PR size — founder review is the DORA-confirmed
   bottleneck), automate ONLY the resource-lock layer of founder-scheduling (shipped
   tooling exists: agent-task-queue).**

The prioritized Phase-3 proposal shortlist this licenses (each to be written up with layer,
evidence pointers, predicted effect, falsifier): **P-A** waiting-plumbing overhaul (Monitor
migration + cheap-ack pattern; targets the 12.5%); **P-B** subagent-guide slimming +
agent-lessons row-27 correction (stale premise, double-context on every spawn);
**P-C** escalation-based delegation routing (try-direct below break-even); **P-D**
blast-radius layer-2 (credential scoping / sandbox experiment — ADDITIVE safety);
**P-E** PR-size discipline (smaller sequential merges); **P-F** pipeline right-sizing
(task-class-conditional skill chain — the latency/attention win); **P-G** the
continuous-vs-handoff measured pilot (novel contribution; Phase-5 flagship candidate).
Non-proposals (evidence says leave alone): review stack, tempdoc unit, worktree model,
squash policy, enforcement guards (except additive P-D), compaction handling.

## Phase 3 — proposal set (2026-07-16, session f7580e17; each: attacked row, evidence,
predicted effect, falsifier, disposition per D-1/D-2 + principle 6)

**Additional probe evidence for P-B (3 runtime probes, 2026-07-16):** general-purpose AND
custom agents (claude-code-guide) receive the FULL CLAUDE.md + `.claude/rules` natively;
Explore receives ONLY the subagent-guide baseline brief. R4's agent had *mis-reported its own
context by trusting the hook's stale "not loaded" claim* — direct evidence the false
statement causes real harm.

### P-A — Waiting-plumbing overhaul
- **Attacks:** publish-protocol WAITING slice + all long-wait patterns. **Evidence:** T1
  (12.5% of window tokens; top sessions 29-50%); R4 (Monitor tool is the documented pattern;
  WebSocket source; Channels preview). **Design directions:** migrate CI-watch to Monitor;
  a "cheap-ack" convention for notification turns (acknowledge without full-context
  reasoning — investigate whether notification handling can run on a fresh/cheap context);
  file the Monitor silent-death bug upstream with our reproduction data.
- **Predicted effect:** several points off the 12.5% WAITING share (gross-effect visible).
- **Falsifier:** WAITING share unchanged after one full window of adoption → revert.
- **Disposition:** principle-6 exception (pure plumbing, no safeguard touched) —
  **implement without pilot**, as its own engineering slice (next implementation tempdoc
  or a 743 work item; too large for this session).

### P-B — subagent-guide slimming + row-27 correction
- **Attacks:** stale platform premise. **Evidence:** 3 probes above; sub-agents.md.
- **Change:** (1) rewrite `agent-lessons.md` `subagents-no-inheritance` (tier-register row
  27) to the verified 2026-07-16 facts (gp/custom inherit fully; Explore/Plan get brief
  only); (2) fix/slim the subagent-guide brief — remove the false "CLAUDE.md is NOT loaded"
  claim; if SubagentStart exposes agent type, skip injection for inheriting types (kills
  the double-context cost), else keep a corrected minimal brief; (3) task-specific briefs
  remain mandatory (task context was never inherited).
- **Predicted effect:** small token saving per spawn; removes a demonstrated
  misinformation vector. **Falsifier:** any post-change subagent found without Hard
  Invariants in context → re-add unconditional injection immediately (re-add trigger).
- **Disposition:** evidence-complete factual correction, additive-safe — **implement now**
  (this session), through the full publish protocol.

### P-C — Escalation-based delegation routing
- **Attacks:** long-lived-orchestrator row (delegation policy half). **Evidence:** R2
  (Uno-Orchestra 12×; ~10k-token spawn break-even; Anthropic's coding-is-a-poor-fit caveat
  on heavy fan-out). **Change:** amend the model-routing guidance from "delegate bounded
  chunks by default" to "estimate chunk size; below break-even, do it directly" — an
  escalation policy, not a delegation ban.
- **Predicted effect:** fewer sub-break-even spawns; orchestrator share may NOT fall (the
  85.1% may be structural) — the metric watched is spawn count below threshold, not the share.
- **Falsifier:** if direct-execution of small chunks measurably raises orchestrator context
  bloat or defect rate in the pilot window → revert.
- **Disposition:** behavioral; also amends the founder's 2026-07-15 model-routing decision →
  **founder review + time-windowed pilot** (Phase 5).

### P-D — Blast-radius layer 2 (additive)
- **Attacks:** nothing existing — fills the enforcement gap R3 exposed (credential scoping /
  OS-level containment; the layer where 2026's worst incidents happened; Cursor data:
  sandboxing also REDUCES agent friction 40%).
- **Change (scoped first step):** inventory credentials/tokens reachable from agent sessions
  (gh token scope, .env files, MCP configs); least-privilege them; evaluate Claude Code
  sandboxing/devcontainer for unattended runs (707-style overnight campaigns first).
- **Predicted effect:** risk reduction (rare-event class — NOT judged by the D-1 total).
- **Falsifier:** none needed to adopt (additive safety); the sandbox half reverts if it
  breaks legitimate workflows in trial.
- **Disposition:** **founder review** (it changes how agents access resources), then its own
  implementation tempdoc — real engineering, out of 743's hands-on scope.

### P-E — PR-size discipline
- **Attacks:** publish-protocol throughput (founder-as-sole-reviewer bottleneck). **Evidence:**
  R5 (LinearB: AI PRs 2.6× larger, merge at 32.7% vs 84.5%; DORA: review speed ≈50% of
  delivery performance). **Change:** guidance in the publish skill: prefer several small
  sequential PRs per tempdoc over one large one; consider a soft LOC hint at PR-open time.
- **Predicted effect:** founder review latency down; merge rate up (visible in per-merge data).
- **Falsifier:** if PR count inflation raises total CI + founder attention instead of
  lowering it in a window → revert.
- **Disposition:** mild behavioral change to how work ships → **founder review**; cheap to
  pilot time-windowed.

### P-F — Task-class-conditional pipeline (right-sizing)
- **Attacks:** staged-pipeline row's LATENCY/attention cost (T1 showed tokens are not the
  issue). **Evidence:** R1 (GAIA conditionality; compounding-error math; keep planning for
  hard tasks — Depth Ceiling). **Change:** a triage step at tempdoc takeover: mechanical
  tempdocs (teardown/rename/config-delete class) run derisk→plan only; design-novel tempdocs
  keep the full chain. Classification is the risky part — misclassification skips scaffolds
  exactly where GAIA says Opus needs them most.
- **Predicted effect:** fewer founder round-trips + lower latency on mechanical work;
  defect-escape rate must stay flat (the constraint that matters).
- **Falsifier:** any escaped defect on a lite-pathed tempdoc that the full chain would
  plausibly have caught → tighten classifier or revert.
- **Disposition:** behavioral, touches the skill layer the founder invokes → **founder
  review + time-windowed pilot** (Phase 5).

### P-G — Continuous-vs-handoff measured pilot (flagship)
- **Attacks:** session-as-continuity axis — but as a MEASUREMENT, not a change. **Evidence:**
  R2 (Anthropic reversed to continuous; zero published controlled comparisons; our tempdoc
  handoff serves multi-day/parallel needs the reversal doesn't address).
- **Design sketch:** matched pairs of comparable tempdocs; arm A = one continuous session to
  completion (compaction allowed); arm B = deliberate session split at a phase boundary with
  tempdoc handoff; score rework, defects, cost, wall-clock. Small N; gross effects only;
  publishable either way (fills a field-level gap).
- **Disposition:** **Phase-5 flagship — needs the founder pilot-mechanics conversation**
  (window discipline, which tempdocs qualify).

### Non-proposals (evidence affirmatively says leave alone)
Review stack (R1: strongest-evidenced scaffold) · tempdoc unit (R5: convergent practice) ·
worktree model (R5; adopt native junction handling opportunistically) · squash policy (R5) ·
existing enforcement guards (R3: relaxation refuted; P-D is additive) · compaction handling
(T1: cost negligible; our hooks are the documented native pattern).

## Phase 4 — adversarial review of the proposal set (2026-07-16; reviewer ≠ author)

**Verdict: NOT-YET-READY as written; revised dispositions below supersede Phase 3's.**
Headline structural finding, quoted because it matters: *"the proposals that skip founder
review are exactly the ones that misclassify themselves into a safe-exception category —
the self-referential failure principle 7 exists to catch, reproduced inside the very program
that named it."* The reviewer independently verified the hook source (unconditional
injection, payload carries only `session_id` — no agent-type field in evidence) and credited
the probe methodology (the slice-execution.md marker genuinely distinguishes inheritance
from injection).

### Revised dispositions (v2 — these govern)

- **P-A → split.** P-A1 (Monitor migration + WebSocket source): plumbing, no pilot. P-A2
  (cheap-ack convention): BEHAVIORAL — an ack that doesn't reason about notification content
  can acknowledge a red CI as green (the `piped-exit-masked` class) → must preserve pass/fail
  discrimination and goes to **founder review + pilot**. WAITING-share numbers are revert
  signals only, never adoption justification (D-1).
- **P-B → split, and the reviewer's D-2 catch is accepted in full.** This session ships ONLY
  the factual-correction half: agent-lessons row-27 rewrite to the verified facts + fixing
  the hook's false "CLAUDE.md is NOT loaded" claim (brief still injected for ALL agent
  types — nothing skipped). The conditional-skip/slimming half **escalates to the founder
  per D-2** (it touches the sole Hard-Invariants carrier for Explore/Plan) and is contingent
  on verifying a reliable agent-type signal in the SubagentStart payload. Re-add trigger
  replaced with an ACTIVE check: a headless probe script (spawn each agent type, assert
  Hard-Invariants markers) to be wired to a named workflow moment at implementation.
  Honest reframe: P-B's value is removing a demonstrated misinformation vector, not tokens.
- **P-C → sound with fixes (accepted):** label Uno-Orchestra as non-coding-domain evidence;
  the ~10k break-even is T3 and must be re-derived locally in the pilot, not hardcoded.
- **P-D → sound with one fix (accepted):** explicitly a **conscious principle-5 override**
  on rare-event-safety grounds (structural-defects-no-repeat logic); absorb R4's concrete
  deny-rule gap (`Read` deny blocks `Edit` but NOT `Write`, v2.1.208) as a P-D work item.
- **P-E → UNSOUND as written; re-normalized before it may be piloted.** Splitting PRs
  inflates the merge-count denominator of the program's own cost/merge verdict metric — a
  Goodhart artifact the program's rules exist to prevent. v2: success/falsifier metrics move
  to **per-tempdoc** cost/throughput (merge count excluded); named dark spot: its primary
  risk axis (founder attention) is unmeasurable while the proxy is deferred — the founder
  decides whether to pilot a change whose main risk the instrument cannot see.
- **P-F → fixes accepted:** GAIA explicitly flagged as cross-domain (QA benchmark →
  suggestive, not load-bearing); lite-eligible tempdoc classes must be pre-registered
  narrowly and conservatively; the counterfactual falsifier replaced by class-narrowing as
  the primary control. Named honestly: both its benefit (latency/attention) and its harm
  (rare defect escape) sit below the program's instruments — **founder decides whether to
  pilot the unmeasurable.**
- **P-G → internal contradiction accepted and fixed:** matched-pairs design violated the
  program's own finding-6 (METR) rule; v2 redesign is **time-windowed arms** (e.g.
  alternating windows, all qualifying work in a window uses that window's topology).
- **NEW P-H — resource-lock automation** (the reviewer's biggest completeness catch: the one
  evidence-complete answer to headline weakness #6 had generated no proposal). Adopt/trial
  Block's `agent-task-queue` MCP for gradle/GPU/dev-stack contention; founder stays router
  for task allocation. Disposition: founder review (it changes multi-agent mechanics), then
  scoped trial.
- **NEW P-I — worktree-script native cleanup** (plumbing lane, evidence-complete per R4):
  delete our junction-unlink logic (native since v2.1.205), evaluate `.worktreeinclude` for
  copy-seeding; keep template-seeding + holder-cleanup (with the self-match fix from the
  inbox). No pilot needed.
- **Non-proposals corrected:** the review-stack entry over-claimed — R1's evidence defends
  reviewer-independence, NOT the `/review-changes`+`/review-tempdoc-fit` overlap (weakness
  #5); that overlap plus review-ordering (weakness #4) are reopened as small open items for
  the founder's proposal review, not shielded. Compaction non-proposal now cites both halves
  (T1: token cost negligible; R2: quality-loss claim stands unmeasured — P-G probes it).

### What this session implements before stopping (per the above)

Only P-B's factual-correction half (rules + hook wording, full publish protocol). Everything
else awaits the founder's proposal review; P-A1/P-I are queued as the first plumbing slices
after it.

## Founder disposition on the proposal set (2026-07-16, "proceed accordingly" on the
recommended dispositions)

- **P-A1 + P-I — BUILD NOW** → tempdoc 746 (waiting-plumbing slice), started same session.
  Note: P-A1's "file the Monitor bug upstream" sub-item is outward-facing (public GitHub
  issue) — a draft will be prepared, but filing waits for explicit founder confirmation.
- **P-D — APPROVED, scoped first step** (credential inventory, least-privilege, deny-rule
  Write-gap audit; sandbox half trials on the next overnight campaign) → gets its own
  tempdoc when implementation starts.
- **P-H — APPROVED for a scoped trial**, then **CLOSED NO-GO after trial-prep diligence
  (2026-07-16, same session):** Block's agent-task-queue crashes at import on Windows
  (open unfixed bug block/agent-task-queue#33; last upstream commit 2026-04-27) and its
  zombie-lock recovery — the entire adoption rationale — is POSIX-only (`ps`, `os.killpg`),
  so a native trial could only false-positive on the happy path. Supply chain itself was
  clean (Block org, Apache-2.0, no phone-home). Bonus mapping finding: the dev stack's
  existing `justsearch_dev_*` ownership model is purpose-built session-scoped locking and
  strictly better for that surface; gradle-across-worktrees was the only good fit.
  **Revisit triggers:** a Windows-compatible upstream release, or founder appetite for a
  WSL2-hosted trial (needs its own cross-boundary diligence). Full prep report (pinned
  config, exit criteria) preserved in the session record; nothing was installed.
- **P-F — APPROVED narrow**: exactly one pre-registered lite class (mechanical
  teardown/rename/config-delete tempdocs → derisk→plan), refute-first review mandatory on
  both paths; any escaped defect on a lite tempdoc narrows/kills the class.
- **P-C — APPROVED as calibration pilot**, same window as P-F; break-even derived locally.
- **P-A2 — DEFERRED** until P-A1 lands and WAITING is re-measured.
- **P-E — DEFERRED** (revisit only if PR review becomes a felt pain).
- **P-B skip-half — DROPPED** (tail risk exceeds the small token win; shipped correction
  already removed the harm).
- **P-G — APPROVED, runs LAST** (after P-A1, to avoid confounding); design: alternating
  time-windows, qualifying tempdocs pre-defined, scored on rework+defects+cost.
- **Pilot mechanics settled**: two-week windows; P-F+P-C bundled in one window (attribution
  blur accepted at this scale); abort rule: any escaped defect traceable to a piloted change
  reverts that change immediately; window verdict = gross signals + founder judgment
  (dashboard sanity-checks, doesn't decide).

## Non-goals

- Re-running 727's tactical fix loop (that instrument keeps running independently).
- Redesigning the engine's eval/benchmark methodology (624/707's domain).
- Any change to Hard Invariants.
