---
title: "743 — Workflow reconsideration program: fundamentally re-evaluating the agent development workflow"
type: tempdocs
status: "open — Phase 1 COMPLETE and merged (PR #209, squash ce4d6de8, main CI green 2026-07-16); BASELINE RECOMPUTED 2026-07-16 after tempdoc 745 fixed 4 verified bugs in the cost parser it was measured with — total $21,410 -> ~$22,100, cost/merge $104.95 -> ~$106.25, split 85.1/14.9 -> 84.0/16.0 (headline survives; read prediction-1 against 84.0%). Handoff item 4 ("OTel reservoir is feeding") was FALSE and is corrected: the reservoir destroyed itself every few minutes until 745 F-2 fixed it. D-1/D-2 settled; founder GO on phases 2-6 GRANTED 2026-07-16 (after 745 recomputed the baseline on a fixed instrument — the verdict survived the correction); next: Phase 2 via /takeover 743 in a fresh session"
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
  token share below its 85.1% baseline in the next window — if it doesn't, the policy
  isn't biting;
  (2) `costs.ndjson` should now accumulate a row per teardown — if it's still sparse in two
  weeks, the workflow-moment wiring failed and the Gen-4-graveyard risk is realized.
- **Recommendation: GO for phases 2-6** under the gross-effect bar. The 85/15 orchestrator
  split is the first actionable baseline datum — it quantifies exactly the delegation-economics
  concern the founder raised on 2026-07-15 and gives phase-3 proposals a concrete target.

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

1. ~~**Pending founder decision:** go/no-go on phases 2-6 (GO recommended in the readability
   verdict above). Get it confirmed at kickoff.~~
   **✅ SETTLED — founder: GO on phases 2-6 (2026-07-16).** Granted *after* tempdoc 745 recomputed
   this program's baseline on a fixed instrument — so the GO rests on numbers that survived the
   correction (split moved 0.4pp, cost/merge +1.2%, both far inside the 2-2.7x weekly noise finding 6
   predicted), not on the pre-fix figures the readability verdict was originally argued from.
   Consequence for 745: its retire sweep is unblocked — `context-attribution.mjs` is KEPT because
   Phase 2 names it as the overhead-taxonomy substrate; the Gen-1 dashboard/PHI slices retire.
2. **Phase 2 = two parallel streams:** (a) the full per-layer adversarial research sweep
   (approach principle 4 — bigger than the two bounded takeover probes already recorded);
   (b) first-principles decomposition of the workflow's jobs per layer/deep axis with
   baseline cost attribution (the overhead taxonomy — waiting/ceremony/re-orientation — is
   NOT yet computed; `context-attribution.mjs` covers one slice).
3. **Check the two live predictions** against fresh data before proposing anything:
   orchestrator share vs 85.1% baseline (post-2026-07-15 sessions only), and `costs.ndjson`
   row accumulation per teardown.
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

## Non-goals

- Re-running 727's tactical fix loop (that instrument keeps running independently).
- Redesigning the engine's eval/benchmark methodology (624/707's domain).
- Any change to Hard Invariants.
