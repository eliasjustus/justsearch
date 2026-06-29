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

## Public main publication

Public `main` is a curated project-history surface, not the transcript of an
agent branch. Normal maintainer and agent work happens in branches or worktrees,
review happens in pull requests, and the merge result on `main` is one edited
squash commit.

Before merging a PR, make the PR title and body suitable as the public commit
title and body. Keep the summary focused on the durable project change and put
verification in the testing section. Branch checkpoint commits, investigation
commits, and retry commits should stay in branch/PR history rather than landing
as separate `main` commits.

Use the default publication path for ordinary work, grouped Dependabot updates,
and tempdoc-heavy agent work:

1. Work on a branch or isolated worktree.
2. Open a PR and let the public CI fact lanes report.
3. Edit the PR title/body into the intended public commit message.
4. Squash merge the PR after the required checks are green.
5. Let GitHub delete the source branch after merge.

For noisy or dependency PRs, preview the default public squash message before
merge:

```powershell
node scripts/ci/preview-squash-message.mjs --repo eliasjustus/justsearch --pr <number>
```

Rare non-squash publication is a maintainer exception, not a standing lane. Use
it only when the intermediate commits are themselves durable public review units
or the branch topology has independent public meaning. Record the reason in the
PR before changing repository settings for the exception.

The expected repository settings are declared in
`scripts/ci/repo-history-policy.v1.json`. Maintainers can verify live settings
with:

```powershell
node scripts/ci/check-repo-history-policy.mjs --repo eliasjustus/justsearch --branch main
```
