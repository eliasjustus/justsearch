---
title: "922 — Manual Codex skill ownership and Claude-semantic cleanup"
type: tempdocs
status: done
created: 2026-09-03
updated: 2026-09-03
lane: agent tooling / Codex migration
---

# 922 — Manual Codex skill ownership and Claude-semantic cleanup

## Goal

Make `.agents/skills` a manually maintained Codex-native skill tree. Remove the
Claude-to-Codex verbatim projection boundary and correct the operational
Claude-only instructions that survived the initial migration, while preserving
historical evidence and cross-harness governance references where they remain
factually necessary.

## Acceptance contract

- [x] `.agents/skills` is documented as the manually maintained Codex skill
      authority; `.claude/skills` remains the Claude Code skill authority.
- [x] `skills-sync.mjs` continues to refresh canonical documentation embedded
      in Claude skills but no longer writes or checks `.agents/skills`.
- [x] The obsolete Codex skill projection generator and equality assertion are
      retired without weakening the remaining Codex configuration/hook checks.
- [x] All 27 Codex skills lose the generated-projection notice and generic
      compatibility disclaimer.
- [x] Copied operational assumptions in `goal`, `publish`, `derisk`, `plan`,
      `review-changes`, `takeover`, `inference-runtime`, `jseval`, `installer`,
      `ui-check`, and `dev-stack` are replaced with Codex-native guidance.
- [x] Retained Claude references in governance, maintenance, CI-triage, and
      evidence-heavy skills are clearly historical or cross-harness, never
      executable Codex instructions.
- [x] The personal `$justsearch-start` skill discovers the active repository
      root and describes the shipped Codex skill, hook, and MCP surfaces.
- [x] All skill frontmatter is valid, the four explicit-only policies remain,
      relevant documentation checks pass, and a manual semantic scan finds no
      unclassified Claude-only operational instruction.

## Scope boundary

This work does not reconcile or modify the user's existing main checkout.
Repository changes are made only in the dedicated
`codex/manual-codex-skills` worktree based on published `origin/main`.

## Outcome

- `.agents/skills` is now the hand-authored Codex authority. The obsolete
  Claude-to-Codex projection generator was removed, and `skills-sync.mjs`
  continues to maintain only canonical-document sections in Claude skills.
- All 27 Codex skills lost the projection notice. Goal persistence, publication
  waiting, model guidance, plan/review behavior, visual verification, sandbox
  setup, long eval runs, and skill invocation syntax now use Codex-native
  semantics.
- `governance`, documentation maintenance, CI triage, and evidence registers
  retain only explicit cross-harness or historical Claude references.
- The personal `$justsearch-start` skill now resolves the active Git root,
  treats startup invocation as optional, runs world state, and verifies the
  repository's Codex skills, hooks, configuration, and MCP surface.

## Verification

- Skill Creator `quick_validate.py` in UTF-8 mode: 27 repository skills plus
  the personal `$justsearch-start` skill passed.
- Inventory: 27 `SKILL.md` files; four `allow_implicit_invocation: false`
  policies (`collision-check`, `goal`, `payback`, `time-calibration`); zero
  projection-notice files.
- `check-codex-agent-parity`: 8/8 checks passed.
- `check-workflow-triggers`, `check-tempdoc-numbers`,
  `check-always-loaded-budget`, `agent-instructions-sync --check`,
  `skills-sync --check`, `llmstxt-generate --check`, canonical link validation,
  canonical Markdown lint, JavaScript syntax checks, and `git diff --check`
  passed.
- Manual semantic scan found no unclassified Claude-only operational command,
  tool, lifecycle instruction, or executing-agent model recommendation.
