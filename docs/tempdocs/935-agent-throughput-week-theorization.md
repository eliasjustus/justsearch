---
title: "935 — Agent throughput, week of 2026-08-29..09-05: are drawback-free improvements possible?"
type: tempdocs
status: "THEORIZED 2026-09-05 — no design, no implementation; verdict in §6"
created: 2026-09-05
updated: 2026-09-05
charter: "take the external week-scale throughput analysis (Codex + Claude transcripts and telemetry, 2026-08-29 10:10 UTC to 2026-09-05 10:10 UTC) and decide whether any meaningful improvement exists that carries no meaningful drawback"
related:
  - 886-agent-token-efficiency-review        # token/context-residency axis; ranked levers
  - 887-improvement-landscape-register       # the search-timeout episode ran in this lane
  - 927-publish-latency-retrospective        # CI timeout ratchet, first sighting
  - 929-publishing-process-waste-deep-investigation  # F4 timeouts-as-capacity, F6 polling, §4.6 subscription waiting
  - 743-workflow-reconsideration-program     # delegation economics falsifier (judge by 2026-09-14)
  - 618-session-transcript-friction-mining   # "residence is not delivery"; piped-exit-masked origin
---

# 935 — Agent throughput week: theorization

## 0. Question

An external analysis of one week of agent activity on the development machine (both harnesses,
original transcripts plus native telemetry, hash-verified sources, 20/20 arithmetic checks)
concluded: a handful of minute-scale execution mistakes are real; the large aggregates (CI
watching, subagent waiting, compaction, a 52-hour "residual sampling" interval) are occupancy,
not recoverable time; nothing justifies a model switch, weaker verification, or a measurement
platform. Its artifacts live outside the repository and are not reproduced here.

This tempdoc asks one narrower question: **is any meaningful improvement possible that carries
no meaningful drawback?** It theorizes; it does not design.

## 1. What the analysis established, and what was already known

| Mechanism the analysis found | Already found by | New in this week's evidence |
|---|---|---|
| Full governance kernel run repeated to recover its exit status | 618 §10a (`piped-exit-masked`), `pipe-mask-hint` hook | The rerun was **deliberate**: the agent knew the pipe masked the exit and chose a second 10-minute run over `set -o pipefail`. Also: the hook's build-command list does not include `node scripts/governance/run.mjs`. |
| Expensive UI capture rerun over output path / mixed JSON / wrong schema key | — | Two 5-minute captures lost to result handling, one to a real locator timeout. |
| Recursive `grep -r` / `find` traversing `.claude/worktrees` and `node_modules`, three timeouts | 887 §T ("any repo-wide grep must exclude worktrees") | Those trees are gitignored, so `rg` and `git grep` already skip them. The failure is tool choice, not missing ignore rules. |
| CI lane timeout 15 → 20 → 30 minutes | 927, 929 F4 | Nothing new; the analysis marks it "fixed", 929 already calls that a capacity ratchet. |
| Ten-minute foreground poll with an unchanged indexed-document counter | 929 F6, §4.6 | The loop used 0.9-second sleeps: compliant with the sleep guard's letter, not its intent. |
| Subagent wait that produced real review defects | 743 | Confirms the wait is not waste. |
| 238 Codex compactions, ~65 s each | 886 lever 1 (bound context per call) | Frequency (roughly one per 3.4 turns) is the number that matters, not the summed seconds. |
| Residual sampling/stream/harness interval ~60% of turn time | 886 §3 (context residency) | Token-per-second from the retained token events could have bounded it; the analysis stayed wall-clock only. |

Two observations the analysis did not draw:

1. **Four of the five Claude incidents happened inside subagents.** The only main-session
   incident was a 3 h 22 min human decision on a security-sensitive question, which is not
   waste. Every avoidable mechanism occurred where the repository's hooks do not fire
   (`parent-hooks-dont-fire-in-subagents`). Combined with 886's finding that 84k of 99k API
   calls and 88% of spend are subagent calls, the guardrail layer is delivered to the surface
   that produces the fewest incidents.
2. **This is the fifth agent-waste investigation in one week** (886, 887, 927, 929, and the
   external one). Their findings overlap heavily. The investigation program itself is now a
   plausible top-five consumer of the resource it studies.

## 2. Framings

**2a. Waste as wall-clock (the analysis' frame).** Correct that most agent-hours are
occupancy: overlapping spans, hosted work being watched, reviews producing findings. Under this
frame the recoverable total is minutes. The frame is also the least relevant one when three or
four sessions run in parallel and the owner's attention, not agent time, is the bottleneck.

**2b. Waste as tokens and context residency (886's frame).** The recoverable dimension.
Every foreground poll, unchanged-status narration, and 60k-character read is re-presented on
each subsequent call. This frame turns the "polling is harmless occupancy" conclusion around:
polling is harmless in wall-clock and expensive in context. 886 already owns this axis; this
tempdoc adds nothing to it beyond the subagent-locus observation.

**2c. Waste as guardrail reach.** The recurring shape: the rule exists (in `agent-lessons`,
in a hook, in a register), the incident happens anyway, because the delivery surface (main loop
hooks, always-loaded prose) is not the occurrence surface (subagent shells). 618 named the
prose version of this "residence is not delivery"; the subagent version is "delivery is not
reach". The `subagent-guide` SubagentStart brief is currently the only channel that reaches a
subagent shell, and it carries hard invariants and discipline, not execution hygiene.

**2d. Waste as expensive-operation repetition.** The analysis' own concentration numbers
(1.3% of calls, 60-70% of tool time) say the unit to count is not seconds but *reruns of
operations that cost minutes*. Under this frame the week had roughly four: one governance
kernel rerun, two UI captures, one CI lane. Each had a distinct handling failure and a shared
root: the expensive operation did not leave a durable, addressable, schema-known result that a
second cheap read could consume. This is a property of the operations, not of the agents.

**2e. Investigation as waste.** A retrospective costs orchestrator tokens, produces a tempdoc,
and its recommendations survive only if a workflow moment re-runs them (743's "design law").
Five in a week with converging findings is a signal that the marginal retrospective is now
below its payback line. The analysis' own break-even arithmetic applies to itself.

## 3. Candidate directions, with drawback assessment

Each direction is rated on whether it has a *meaningful* drawback: cost, adherence risk,
false positives, correctness risk, or being another unread record.

| # | Direction | Meaningful drawback? | Notes |
|---|---|---|---|
| D1 | Extend `pipe-mask-hint`'s build-command patterns to `node scripts/governance/run.mjs`, `node scripts/ci/*.mjs`, and `jseval ui-*` captures | **No.** Advisory hook, regex + test-corpus row, fail-open. | Would not have reached the observed incident (subagent). Still closes the same hole for the main loop. |
| D2 | Add three execution-hygiene lines to the `subagent-guide` baseline brief: preserve exit status with `set -o pipefail` instead of rerunning; search with `rg`/`git grep` (gitignore-aware), never `grep -r`/`find` from the repo root; write expensive-tool artifacts to an explicit absolute path and read the actual schema before parsing | **No, with a known ceiling.** Prose-tier (~70%), brief has a ~10K char cap, and subagent-guide is deliberately minimal. | The only channel that reaches subagent shells today. Cheap; directly targets 3 of 4 subagent incidents. |
| D3 | Re-probe whether the current harness runs PreToolUse hooks inside subagents | **No.** One crafted-JSON probe. | Lesson dated 2026-07-12; if the platform changed, D1-class hooks gain reach and D2 becomes redundant. Verify before believing either way. |
| D4 | Emit per-producer wall time from `run.mjs --produce-inputs` (the kernel's input builders include a Gradle-backed dead-code producer; the same step is what exhausted the CI lane) | **No.** Pure observability, one line per producer. | Enables 929 §4.5 (timeouts with attribution) for both the local 10-minute run and the CI lane. Precondition for any later caching decision; not the caching itself. |
| D5 | Cache producer outputs by input hash so unchanged producers are skipped | **Yes.** Staleness and correctness risk on a gate kernel; needs a hash contract per producer. | Do not do without D4 evidence naming one dominant producer. |
| D6 | Stall detection in the existing readiness wait: N unchanged samples → exit nonzero with reason | **Mild.** False stalls on legitimately slow phases (large-document embedding). Needs a threshold with evidence. | 929 §4.6 already frames the ideal as subscription. Not drawback-free. |
| D7 | Tighten the sleep guard to catch sub-second loops | **Yes.** Blocks legitimate backoff polls; the guard's purpose is to force condition-polls, which the 0.9 s loop was. | Leave it. The problem was the ten-minute foreground budget, not the sleep length. |
| D8 | Raise or keep raising CI lane timeouts | **Yes.** 929 F4: capacity ratchet without attribution. | D4 is the substitute. |
| D9 | Feed the subagent-locus finding (4/5 incidents, 88% spend) into the 743 delegation falsifier judgment due 2026-09-14 | **No.** It is an input to a scheduled decision, not a change. | The falsifier asks whether cost-per-merge improves without rework rising; this week supplies the incident side. |
| D10 | Compactions-per-turn as the compaction metric instead of summed seconds | **Only if it gains a consumer.** Otherwise it is another unread number (872: 565 notes, none read). | Fold into `baseline-economics.mjs` if anywhere, since that already re-runs at merge time. Not standalone. |
| D11 | Build the proposed append-only acceptance/attribution record | **Yes.** Three analytics layers already retired; the proposal names no reader. | Not until a workflow moment consumes it. |
| D12 | Stop rule for waste retrospectives: no new one until the previous one's top recommendation has shipped and been measured | **No.** Policy, zero code. | The meta-lever from §2e. |
| D13 | Faster model / lower reasoning effort to shrink the 52-hour residual | **Yes.** Uncontrolled; the residual is not shown to be inference. | The analysis is right to refuse this. |
| D14 | Token-per-second bound on the residual from retained Codex token events | **No** as a one-off read. | Would settle whether D13 is even a candidate. Cheap, read-only, not urgent. |

## 4. Hidden assumptions worth testing

- **That agent wall-clock is the scarce resource.** With parallel sessions and a human
  bottleneck, an agent slot idling on a watch may cost nothing. 929 separates attention,
  wall-clock, tokens and CI compute as axes; the external analysis collapsed to wall-clock.
- **That hooks are the enforcement layer.** They enforce where the orchestrator acts. If
  most shells run in workers, the "~100% adherence" claim for hooks (CLAUDE.md
  `before-appending-to-rules`) describes a minority of executions. D3 tests whether this is
  still true of the platform.
- **That the expensive operations are inherently expensive.** A kernel input build that runs
  Gradle to enumerate dead JVM code is expensive by construction, not by nature. D4 makes the
  breakdown visible; nobody has looked.
- **That the sleep guard converts waiting into work.** It converted a `sleep 10` into
  `sleep 0.9` loops. The behavior it wanted (bounded, transition-aware waiting) is a
  different mechanism (929 §4.6).
- **That "not demonstrated" means "small".** The analysis selected cases purposively and never
  measured recurrence. Its exploratory cohorts (119 foreground-to-background timeouts, 45
  terminated timeouts) are the cheap recurrence signal it left unread. A sample classification
  of those would turn case studies into a rate. Until then, "minute-scale" is a floor, not a
  size.

## 5. Broader shape

**Occurrence surface versus delivery surface.** Rules, hints and guards are delivered where
the orchestrator acts; incidents cluster where workers act. This is the same defect class as
618's "residence is not delivery" one level down. The invariant it suggests: *every
must-rule should name the surface it reaches, and a rule whose occurrence surface it cannot
reach is prose, whatever tier it claims.* The tier register could carry a "reach" column;
this tempdoc does not design that.

**Expensive operations owe a durable result.** 2d's four reruns share a root that is a
property of the tools: no stable artifact path, no separated stderr, no declared schema, no
exit status alongside output. 929's "publication as a monotonic evidence transaction" is the
same principle for CI. A general form: any operation above a minute should be re-readable
without re-execution. This is a tool-contract standard, not an agent-behavior rule.

**Retrospectives need a stop rule.** A program that has produced five converging analyses
in seven days is past the point where the next one pays for itself. The break-even test the
analysis applied to fixes applies to itself.

## 6. Verdict on the question

**Yes, but the set is small and its meaning is "reruns of expensive operations prevented",
not hours per week.** The directions with no meaningful drawback are D1, D2, D3, D4, D9, D12
and the read-only D14. Together they are well under a day of work and touch one hook regex,
one baseline brief, one probe, one timing line, and two judgments. None of them is
"meaningful" on the analysis' wall-clock frame; each is meaningful on the expensive-rerun and
guardrail-reach frames, which are the frames this week's evidence actually supports.

Everything larger (producer caching, stall detection, an acceptance record, model routing)
carries a drawback that the current evidence does not yet outweigh. The honest ordering is:
D3 and D4 first, because their results decide whether D1/D2 and D5 are the right shape.

## 7. What would change this verdict

- D3 shows hooks now fire in subagents → D2 is unnecessary; D1 alone suffices.
- D4 shows one producer dominates the kernel build → D5 becomes worth its drawback.
- A recurrence count from the timeout cohorts shows the search/result-handling mechanisms
  recur weekly → D2 graduates from prose to a hook or tool contract.
- D14 shows the residual sampling interval runs at inference-plausible token rates → D13
  re-enters as a controlled experiment, not before.

## 8. Explicitly not proposed

No timeout increases, no guard weakening, no model switch, no acceptance-record build, no
sixth retrospective. The analysis was right about all of these; this tempdoc only disagrees
with where it stopped.
