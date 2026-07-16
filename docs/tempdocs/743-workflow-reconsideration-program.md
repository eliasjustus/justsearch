---
title: "743 — Workflow reconsideration program: fundamentally re-evaluating the agent development workflow"
type: tempdocs
status: "open — charter drafted; objective-function decision under discussion with founder (see Open founder decisions)"
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

- **D-1 (objective function).** What "better" means. Founder's initial position (2026-07-16):
  improve the workflow **as a total**, not per-aspect. Concretization under discussion in the
  provenance chat; to be recorded here once settled.
- **D-2 (guardrail-removal arbitration).** Confirm: proposals that *remove* a safeguard
  require explicit founder approval regardless of pilot results (per principle 7).

## Falsifier for the program itself

If after the pilots (phase 5) the total-level baseline metric has not measurably improved —
or improved only by amounts attributable to unrelated platform changes — this program closes
with a "workflow was already near its frontier" verdict and its residual value is the
baseline instrumentation. That is an acceptable outcome; an unfalsifiable improvement program
is not.

## Non-goals

- Re-running 727's tactical fix loop (that instrument keeps running independently).
- Redesigning the engine's eval/benchmark methodology (624/707's domain).
- Any change to Hard Invariants.
