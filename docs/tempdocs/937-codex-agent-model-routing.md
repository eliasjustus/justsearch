---
title: "937 — Codex agent model routing"
type: tempdocs
status: done
created: 2026-09-06
updated: 2026-09-06
lane: agent tooling / Codex subagents
---

# 937 — Codex agent model routing

## Goal

Make Codex subagent economics an explicit, role-based project policy. The
orchestrator chooses a semantic role; each role pins the model and reasoning
effort that should execute it, and analytics verifies the model actually used.

## Acceptance contract

- [x] Unqualified Codex subagents default to `gpt-5.6-luna` at `high` effort.
- [x] `worker` and `explorer` explicitly pin `gpt-5.6-luna` at `high` effort.
- [x] A `complex_worker` role pins `gpt-5.6-sol` at `medium` effort and gives
      the orchestrator concrete escalation criteria.
- [x] `reviewer` explicitly pins `gpt-5.6-sol` at `high` effort.
- [x] Role-routed spawns use bounded `fork_turns`; the policy explicitly warns
      that full-history forks inherit the parent model and effort.
- [x] `AGENTS.md` and the canonical Codex workflow document explain when the
      orchestrator selects each role and how a worker requests escalation.
- [x] The Codex parity gate fails if a role, model pin, effort pin, sandbox, or
      project default drifts from the policy.
- [x] The Codex ledger derives real spawn lineage and actual reasoning effort
      from current rollout fields, with regression coverage.
- [x] Spawn economics reports Codex role/model/effort rows rather than folding
      every multi-agent child into an opaque parent-session row.
- [x] Relevant agent, analytics, prompt-surface, and documentation checks pass.

## Scope boundary

This change configures project-scoped Codex behavior. Claude model aliases and
Claude-specific delegation hooks remain owned by their existing harness
surfaces. No dev stack, merge, publication, or release action is in scope.

## Design

The parent orchestrator receives a closed role vocabulary through each custom
agent's `description`. Role files pin the economics, while
`developer_instructions` govern child behavior after selection. `worker` is
appropriate only when intended behavior and verification are already known.
Ambiguous root causes, cross-module contracts, concurrency or lifecycle work,
security boundaries, migrations, and a failed bounded-worker attempt route to
`complex_worker`. A child reports the evidence that requires escalation; it
does not silently select a more expensive model.

## Outcome and evidence

- `.codex/config.toml` now defaults unqualified children to Luna/high. The
  `explorer` and `worker` roles pin Luna/high, `complex_worker` pins Sol/medium,
  and `reviewer` pins Sol/high.
- `AGENTS.md` and `docs/how-to/use-codex-for-development.md` give the parent a
  closed semantic role menu and require `fork_turns = "none"` or a positive
  integer. A first live smoke deliberately exposed why this matters: two
  full-history forks inherited the Sol/high parent despite their role files.
- A second live `codex exec --json` smoke used bounded forks. Its child rollout
  metadata and `spawn-economics.mjs --since 2026-09-06T02:22:00Z --harness
  codex-cli` output independently reported exactly
  `explorer | gpt-5.6-luna | high` and
  `complex_worker | gpt-5.6-sol | medium`.
- `record.test.mjs` passed 18 assertions, `codex-adapter.test.mjs` passed 36,
  and `spawn-economics.test.mjs` passed 18. The Codex parity gate passed all
  eight checks and the live MCP projection passed all 16 applicable assertions.
- Documentation index, skill synchronization, canonical links, module graph,
  runtime configuration matrix, Markdown lint, prompt-surface inventory, and
  `git diff --check` passed. The prompt inventory reported 131 surfaces and no
  suspicious tokens.
- The full agent-analytics runner passed all 52 test files. Frontend typecheck
  passed, and the frontend unit suite passed all 469 files and 6,287 tests.
- Hosted PR CI, merge-group CI run `34007839088`, and published-main CI run
  `34008180922` passed. PR #689 merged through the queue as squash commit
  `7df0bae06075edab27dea01a479d23ed24fea7e4`.

## Verification residuals

- `check-tempdoc-numbers.mjs` reported an existing cross-worktree collision for
  tempdoc 919. Tempdoc 937 was not involved in the collision.
- No product dev stack was required. Local Gradle verification was unavailable
  because tempdoc 919 owned the repository's single Gradle lane with a
  long-running headless evaluation; the hosted build, integration, and unit-test
  lanes supplied the full isolated JVM proof before merge.

## Remaining work

No implementation work remains. Future sessions can use the role/model/effort
table in `spawn-economics.mjs` to compare acceptance success, rework, and token
use before changing the routing policy.
