---
name: time-calibration
description: >-
  Estimate vs. actual implementation time for past tempdocs, to calibrate
  confidence about the current tempdoc's likely duration. Rare — run manually
  only.
---
<!-- generated from .claude/skills by scripts/docs/codex-skills-projection.mjs; do not edit -->

> Codex projection: `$skill-name` is the equivalent of a Claude `/skill-name` invocation. When this workflow names a Claude-only tool, use the available Codex capability that preserves the same policy and acceptance criteria.

Your task now is to analyze a random implemented tempdoc. Then I want you to evaluate, without any further info, how long its implementation you think it might've taken. Then I want you to investigate the git history and analyze how long it actually took. You can then repeat this multiple times and/or look at the git history from a general overview. The purpose of this is so you can better evaluate how long and how complex your tempdocs' work actually might be. At the end, based on your current context, I want you to evaluate how complex and how long the implementation of your tempdoc might take.
