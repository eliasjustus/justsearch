---
title: "Agent-eval executor v2: replace the claude-CLI subprocess shellout with an in-process executor so cell failures are observable, resumable, and forensically complete"
type: tempdocs
status: "open — STUB, no design or implementation. Trigger: the next certified agent-utility run or re-certification event (do not build speculatively). Spun out of tempdoc 624's certified-run session (2026-07-03), which hardened the subprocess executor enough to finish but demonstrated its structural ceiling."
created: 2026-07-03
updated: 2026-07-03
author: agent retrospective (624 certified-run session), filed by agent — STUB
category: agent-eval / jseval / execution-infrastructure
related:
  - 624-agentic-retrieval-eval-rebuild        # origin — the executor's current form (Inspect AI + claude -p subprocess) and every failure this stub cites
  - 673-agent-utility-standing-regression-ratchet  # a future consumer — the cheap standing gate runs through the same executor
  - 674-cross-family-grader-local-model-infrastructure  # sibling infrastructure in the same eval stack
principle: "a measurement cell whose failures are forensically blind cannot vouch for its own loss-accounting — the executor must be able to say WHY a cell died, not just that it did. 624's run-governance extended honesty to the run; this extends it to the cell."
---

> Noncanonical working tempdoc. STUB: goals and context only — no design decisions, no implementation
> specifics. The design pass should start from 624's own already-named option ("the in-process
> Agent-SDK executor... the second-time-you-run-this-at-scale move", named twice in 624 before the
> evidence below existed) and evaluate it against alternatives on its merits.

# 675 — Agent-eval executor v2 (in-process)

## Goal

Make the agent-utility eval's per-cell execution **observable, resumable, and forensically complete**,
so that a cell failure is always attributable from the record itself and never requires live
reproduction to diagnose. Today's executor (Inspect AI driving `claude -p` subprocesses,
`scripts/jseval/jseval/agent_utility_inspect.py`) was hardened enough during 624's certified run
(2026-07-03) to produce clean records, but the hardening treats symptoms of one structural property:
**the cell is a black-box subprocess**.

## Context — what the 2026-07-03 certified-run session demonstrated (all documented in 624, twenty-first/twenty-second passes)

- **Silent cell deaths with no forensics anywhere**: ~5%/cell under sustained 8-way load, rc=1, empty
  stderr, no result event, no CLI-side logs. Root cause never established; mitigated (bounded disclosed
  retry + `stdin=DEVNULL`), not explained. An unexplained failure mode in the measurement substrate is a
  standing credibility threat.
- **Timed-out cells lose their partial evidence** — the tool calls a cell made before its timeout never
  reach the record, a blind spot in the credibility bar's "every cell actually run" assertion.
- **Retry semantics interact badly with slot scheduling** (a retried timeout can hold a concurrency slot
  for twice the calibrated budget).
- **Resume and mid-run observability are brittle**: dirty-log refusals force fresh directories and
  re-spend; retry-rewrites scrub errored samples so mid-run state contradicts final state; the session's
  monitoring instruments were repeatedly misled by log-flush semantics.
- **No first-class run monitor exists** — the session hand-rolled four ad-hoc watchdogs, one of which
  (built on a projection not designed for partial logs, since fixed) aborted two healthy runs.
- **Usage/cost visibility is nonstandard** because the harness cannot see inside the subprocess (Inspect's
  own `model_usage` is empty for shell-out solvers; everything flows through hand-stashed metadata).

- **(Added 2026-07-03, post dead-config discovery)** Two parallel executors now exist with
  **asymmetric guards**: the Inspect runner gained config fail-fast + per-cell offered-MCP-surface
  assertion; the classic runner (`agent_retrieval_eval.run_agent_eval`) deliberately did not — a
  standing mechanism-fork whose halves will drift. Executor v2's scope must include resolving the
  two-runner question (consolidate, or explicitly retire one), not only replacing the subprocess layer.

## What v2 must preserve (the parts that work and are load-bearing for the records)

Cohort/cell identity as the resume + pairing key; per-cell tool-call capture with
disallowed-tool/leak-suspect assertions; the calibrate-before-spend governance path; per-cell disclosed
retries as record fields; compatibility with `utility-comparison.v1` composition and the leak-scan /
judge / panel post-passes. The record shape is settled (624); only the execution substrate is in scope.

## Explicit non-goals

Not a redesign of the record, the governance gates, the judge, or the statistics — those are 624's and
they are done. Not a general-purpose eval framework: the scope is the expensive-agent-cell executor that
624's "one identity, three roles" analysis already established as the only place this investment pays.

---

## Direction (2026-07-03, settled for the implementing session — design review done, fork resolved)

**The fork** was: deepen the Inspect integration vs. replace the executor wholesale with an
Agent-SDK loop. **Resolved: keep Inspect as the executor shell; replace the CELL's subprocess with an
in-process Agent-SDK session.** Rationale, in order of weight:

1. **The failure catalog is entirely cell-interior.** Every forensic blindness this doc cites (silent
   CLI deaths, lost timeout evidence, invisible tool results, the dead-config class itself) lives
   inside the `claude -p` subprocess boundary. Inspect's contributions (durable per-sample resume,
   adaptive concurrency, schema-valid EvalLog, epochs-as-seeds) all sit OUTSIDE that boundary and
   were empirically validated twice (624 Confidence-pass #2, the certified runs). Replacing the shell
   re-solves solved problems; replacing the cell dissolves the actual disease: an SDK session exposes
   the tool stream, tool RESULTS (which the trace capture never had), usage, and errors as objects —
   no stdout parsing, no pipe semantics, no config file that can silently not connect (the MCP
   surface is constructed programmatically and is assertable as a value, not via an init-event
   disclosure).
2. **The timing argument — do it NOW-ish, not later:** the arm-invalidation reset (twenty-third
   pass) means there is **zero comparability debt**: no valid with-tool history exists that an
   executor change would fracture. This is the cheapest moment this migration will ever have; after
   the Step-2 real run produces the first valid records, the cohort-comparability cost of swapping
   executors returns.
3. **Sequencing tension, resolved explicitly:** the campaign (624 pass 24) wants Step 1/2 soon. The
   Step-1 adoption pilot and Step-2 run may proceed on the hardened subprocess executor (its guards
   are now adequate for validity) — executor v2 is NOT a blocker for them. But if the implementing
   weeks allow, v2-first is preferred: it removes the ~5%/cell retry noise and the temporal-confound
   workaround below. Founder's scheduling call; both orders are sound.

**Design constraints settled here** (the implementing agent should not re-litigate these):
- **Interleave conditions within one concurrency pool** (fixes the max_tasks=1 temporal confound —
  serialized condition-tasks run arms in disjoint time windows, exposing arm comparisons to
  API-condition drift; a single pool over the full cell matrix restores contemporaneous arms at the
  calibrated concurrency).
- **Tool RESULTS enter the per-cell record** (bounded/truncated), not just tool calls — the
  mechanism analyses were blinded by call-only capture (annex 04's own caveat).
- **Per-cell wall-clock budget** replaces per-attempt timeout (a retried cell must not hold a slot
  for 2× the calibrated budget).
- **The two-runner question:** the classic runner (`agent_retrieval_eval.run_agent_eval`) is retired
  to smoke/diagnostic status the day v2 is trusted, with a deprecation note naming v2 — not silently
  kept as an unguarded fork.
- **Parity risks to verify, not assume** (the honest unknowns for the implementing session): SDK
  agent-loop behavioral parity with the CLI (system prompt, tool defaults, permission semantics),
  MCP-over-HTTP support parity, and cost/usage accounting equivalence — each gets a one-cell A/B
  probe against the CLI path before any full run migrates.
