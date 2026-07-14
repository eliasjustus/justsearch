---
title: Draining the Claude Code memory store into repo docs — 2026-07-14
status: active — Phase 1 (user-local deletions) done; Phase 2 (this PR) opens; Phase 3 (post-merge deletions) pending
created: 2026-07-14
updated: 2026-07-14
---

# Memory-store → docs conversion (2026-07-14)

## Thesis (owner)

The Claude Code auto-memory store (`~/.claude/projects/F--justsearch-public/memory/`)
is, for the most part, **documentation debt made visible** — each memory is a
symptom of a gap in the repo's own docs. The goal: drain every convertible memory
into the repo's canonical documentation structure (which is shared, gated, public,
and reaches subagents via the baseline brief), leaving only genuinely-private
residue. This makes the repo the single source of truth instead of a private
memory fork that will drift (the tempdoc 553 representation-fork class).

## Verified state (git evidence vs `origin/main`)

- `takeover/theorize/design/plan` skills are **public**; the public `takeover`
  skill embodies the pipeline → `user_prompt_library` memory is **superseded**.
- CLAUDE.md's model-routing paragraph (updated 2026-07-14) carries
  `delegate_at_diagnosis_end`'s substance and the "set explicit model / unset
  inherits parent" rule → both **already covered**.
- `docs/business/` + `narrative.md` are **absent** from `origin/main` and the
  local branch; content is funding + founder decisions → `project_direction` is
  genuinely **private** (home = sidecar), not doc-debt.
- `no-merge`, `dont-surface-orphaned-work`, `check-own-decisions`,
  `deep-research-cost`, the `ui_screenshot` method → genuinely **absent** →
  real conversions (this PR).

## Conversion map (15 memories)

| Memory | Verdict | Home |
|---|---|---|
| `delegate_at_diagnosis_end` | delete — covered | CLAUDE.md model-routing ¶ |
| `user_prompt_library_takeover_txt` | delete — superseded | public `.claude/skills/{takeover,theorize,design,plan}` |
| `subagent_model_guard_fable` | trim to machine sliver | rule-half in CLAUDE.md; hook doc in `agent-lessons.md`; keep gitignored `settings.local.json` policy |
| `dont_prematurely_conclude_infeasible` | convert | `agent-postmortems.md` §22 `premature-infeasible-verdict` |
| `no_merge_without_explicit_authorization` | convert (binding) | `branch-safety.md` Merge Workflow `no-merge-without-authorization` |
| `no_git_add_all_in_agent_worktrees` | trim + convert sliver (binding) | general rule already in `branch-safety.md`; sliver → `accepted-tracked-skills-no-removal` |
| `dont_surface_other_sessions_orphaned_work` | convert | `agent-postmortems.md` §18 |
| `tempdoc_takeover_no_auto_implement` | convert nuance | `agent-postmortems.md` §21 (workflow already in `takeover` skill) |
| `check_own_decisions_before_novel_gap_claims` | convert | `agent-postmortems.md` §19 |
| `deep_research_workflow_cost_gating` | convert | `agent-postmortems.md` §20 |
| `ui_screenshot_capture_method` | convert method | `.claude/skills/ui-check/SKILL.md` (sidecar context stripped) |
| `multi_pr_merge_mechanics` | delete after PR #176 | `agent-guide.md` §3.7 |
| `jseval_long_runs_detached_pattern` | delete after PR #176 | `jseval-pipeline-reference.md` |
| `project_direction_2026-07` | **stays private** | a private sidecar (outside this repo) |
| `reference_memory_hygiene_policy` | **stays (meta)** | governs the store; codifies the intake rule below |

**End state:** memory shrinks 15 → ~3 (`project_direction`, `memory_hygiene_policy`, trimmed `subagent_model_guard`).

## What this PR lands

- `agent-postmortems.md` — cases §18–§22 (+ index rows).
- `branch-safety.md` — two anchored binding rules
  (`no-merge-without-authorization`, `accepted-tracked-skills-no-removal`) +
  `tier-register.md` rows 38–39. No prose-tier changeset needed: a new anchor
  with a matching register row passes the gate outright (only tier *changes* /
  removals / unmatched anchors require a changeset — enforcer `verdictForRuleAnchor`).
- `ui-check/SKILL.md` — the reliable-capture method.
- This tempdoc.

## Intake rule going forward (the root-cause fix)

The repo is canonical for every project-scoped fact. Memory holds only (a)
private/relationship facts with no public home, and (b) pointers — never a second
copy. Before saving, check `agent-lessons.md`, `branch-safety.md`,
`agent-postmortems.md`, and the skills; if it belongs there, put it there. This is
codified in the surviving `reference_memory_hygiene_policy` memory; treat the
memory store like the observations inbox — a staging queue drained into durable
docs on a cadence, not a parallel store.

## Sequencing

- **Phase 1 (done, user-local):** deleted `delegate_at_diagnosis_end`,
  `user_prompt_library`; trimmed `subagent_model_guard`.
- **Phase 2 (this PR):** the conversions above.
- **Phase 3 (post-merge):** delete the converted + in-flight (#176) memories,
  update the `MEMORY.md` index, clean any dangling `[[wikilinks]]` in survivors.
