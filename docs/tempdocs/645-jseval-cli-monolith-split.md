---
title: "Split the jseval cli.py command monolith: every jseval tempdoc edits one ~3.5k-line file holding all CLI commands, making it a structural multi-agent collision hotspot — concurrent edits repeatedly cost effort and once nearly committed another agent's broken WIP into it (untracked-module reference → broken main). Decompose into per-command-group modules registered with the main group. STUB / idea-only."
type: tempdocs
status: proposed — STUB / idea-only
created: 2026-06-24
author: agent analysis — split out of tempdoc 635 §workflow-lessons #5 (the cli.py collision hotspot)
related:
  - 635-contamination-resistant-eval-corpus      # where the hotspot bit (workflow-lessons #5): the partial-stage near-miss + recipe
  - 644-eval-runs-from-a-worktree                 # sibling root-cause: eval-on-main forces the concurrent edits that make cli.py collide
---

> **STUB / idea-only.** Captures the problem + why it matters; **no implementation specifics** (which command
> groups, how to register them, migration order — that's the design phase). Origin: tempdoc 635's repeated,
> risky collisions on `cli.py`.

# 645 — Split the jseval CLI command monolith

## The idea
`scripts/jseval/jseval/cli.py` is a single ~3,500-line file containing **every** jseval CLI command (run,
gates, corpus-*, release, calibrate, agent-utility, …). Because essentially every jseval-touching tempdoc adds
or edits a command, they all edit this one file. The idea: **decompose `cli.py` into per-command-group
modules** (each registering its commands with the shared top-level group), so unrelated work lands in
different files.

## The purpose / why it matters
- **It removes a recurring multi-agent collision hotspot at its root.** With parallel agents forced to edit
  `cli.py` (compounded by eval-on-main, see 644), collisions are routine: blocked commits, bundled-foreign-work
  commits, and — this session — a **near-miss where a blind stage would have committed another agent's
  uncommitted `recall-profile` WIP** that referenced an *untracked* module, breaking `main` (635
  §workflow-lessons #5). The hand-rolled partial-stage recipe works but is error-prone; the structural fix is
  to stop concentrating all commands in one file.
- **It reduces per-edit cost and improves navigability** — finding/owning a command group, and reviewing a
  diff, is easier in a focused module than in a 3.5k-line monolith.

## Scope boundary (for the design phase, not decided here)
Out of scope for this stub: the **module decomposition** (which command groups, how they register with the
`main` Click group, shared-helper placement), the **migration order** (incremental vs one-shot, keeping the
public `python -m jseval <cmd>` surface identical), and whether this composes with any existing CLI-group
structure already in the file. This file records only the idea + purpose; the design picks the structure.
Until then, the operational mitigation stands (635 §workflow-lessons #5): `git diff cli.py` before editing,
partial-stage by hunk-marker, compile-check the staged subset.
