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

## What v2 must preserve (the parts that work and are load-bearing for the records)

Cohort/cell identity as the resume + pairing key; per-cell tool-call capture with
disallowed-tool/leak-suspect assertions; the calibrate-before-spend governance path; per-cell disclosed
retries as record fields; compatibility with `utility-comparison.v1` composition and the leak-scan /
judge / panel post-passes. The record shape is settled (624); only the execution substrate is in scope.

## Explicit non-goals

Not a redesign of the record, the governance gates, the judge, or the statistics — those are 624's and
they are done. Not a general-purpose eval framework: the scope is the expensive-agent-cell executor that
624's "one identity, three roles" analysis already established as the only place this investment pays.
