---
name: collision-check
description: >-
  Check nearby tempdoc numbers, active worktrees, and open PRs for numeric or
  file-level collisions with the current tempdoc's remaining work. Rare — run
  manually only.
---
<!-- generated from .claude/skills by scripts/docs/codex-skills-projection.mjs; do not edit -->

> Codex projection: `$skill-name` is the equivalent of a Claude `/skill-name` invocation. When this workflow names a Claude-only tool, use the available Codex capability that preserves the same policy and acceptance criteria.

Identify the number in your tempdoc's filename. Then analyze tempdocs whose filename numbers are within 20 of your own tempdoc's filename number, but only if they were modified within the last 5 hours. Also inspect active worktrees named after tempdocs in that same number range, and include the tempdocs associated with those worktrees in your analysis. Determine if any current tempdocs/work could interfere with your remaining work. Also check file-level overlap: list active worktrees (`git worktree list`) and open PRs, and check whether any of them touch the same files/paths your remaining work needs — shared infra, scripts, or config files are common ground even between tempdocs whose numbers aren't close, so don't rely on number-proximity alone to catch this. Once you have done this, answer me with the relevant findings.
