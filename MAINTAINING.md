# Maintaining JustSearch — the agent-driven development process

JustSearch is developed in the open with AI coding agents (Claude Code) under a deliberate
discipline system. **This document is for maintainers. Contributors do not need it** — to
contribute, see [`CONTRIBUTING.md`](CONTRIBUTING.md) (clone → build → test). None of the machinery
below is required, and a fresh clone does **not** impose it on you.

We publish the machinery rather than hide it — for transparency, and because the
agent-discipline system is part of what JustSearch is. The principle is **present but opt-in**:
the apparatus is visible and documented, never forced on a contributor.

## The machinery (all published; none required to contribute)

- **`CLAUDE.md`** — project instructions auto-loaded by Claude Code (hard invariants, architecture,
  build commands). Contributor-grade by design.
- **`.claude/rules/`** — always-loaded discipline rules: general engineering discipline plus the
  maintainer-only operational rules listed below.
- **`.claude/skills/`** — task-specific playbooks, loaded on demand.
- **`governance/`, `gates/`, `scripts/{governance,ci}/`** — the discipline-gate kernel (CI checks).
- **`scripts/agent-analytics/`** — the discipline hooks (guards + hints) plus maintainer
  telemetry/analytics tooling.

## Maintainer-only operational setup (contributors can skip)

- **Parallel-agent worktrees** — multiple agent sessions, each in its own git worktree. See
  [`.claude/rules/branch-safety.md`](.claude/rules/branch-safety.md).
- **Shared local dev stack** — one local backend at a time, with an ownership/lease handshake. See
  the `/dev-stack` skill.
- **Hooks** — the universally-safe discipline guards/hints are the published wiring
  (`.claude/settings.json`). Maintainer-local analytics hooks — the telemetry sink, MCP
  session-injection, and the analytics dispatch pipeline — are wired only in a maintainer's own
  gitignored `settings.local.json`, not committed.
- **Telemetry** — local-only OpenTelemetry capture of agent sessions, for measuring
  agent-assisted development. It never leaves the machine.
