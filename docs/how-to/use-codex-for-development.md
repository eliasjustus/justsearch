---
title: "Use Codex for JustSearch Development"
type: how-to
status: stable
description: "Install, authenticate, launch, and verify Codex CLI, the Codex IDE extension, or the ChatGPT desktop app against JustSearch's shared instructions, skills, hooks, MCP server, and agent roles."
audience: contributor
---

# Use Codex for JustSearch Development

The OpenAI terminal coding agent is **Codex CLI** (`codex`), not a separate
“ChatGPT CLI.” A ChatGPT subscription can authenticate Codex directly; using an
API key is an alternative billing path, not a requirement for subscription use.
The CLI, IDE extension, and ChatGPT desktop app share Codex configuration and
state under `~/.codex`.

## One-time machine setup

1. Install or update Codex CLI from the official package:

   ```powershell
   npm install -g @openai/codex@latest
   codex --version
   ```

2. Sign in with the ChatGPT account that owns the subscription:

   ```powershell
   codex login
   codex login status
   ```

   Do not put an OpenAI key, GitHub token, or login material in this repository.
   The `justsearch-dev` MCP server is local and needs no secret. GitHub work uses
   the existing authenticated `gh` CLI rather than a committed PAT.

3. Add the local OTLP exporter block from
   [Wire Codex CLI into the OTLP Sink](wire-codex-cli-into-the-otlp-sink.md) to
   `~/.codex/config.toml`. This is machine-local because repository config does
   not own telemetry destinations or credentials.

4. Open the repository once and trust it when Codex asks. Untrusted repositories
   intentionally ignore `.codex/config.toml`, `.codex/hooks.json`, and other
   project-local Codex layers.

## Start a task

Start from the checkout or worktree that should own the edits:

```powershell
Set-Location C:\path\to\justsearch
codex
```

In the IDE extension or desktop app, attach the same folder and make it the
primary working directory. Codex walks upward from the working directory to
discover `AGENTS.md`, `.agents/skills`, and `.codex` configuration.

At session start, follow `AGENTS.md`: read the compact cross-harness contract,
use `$justsearch-start` when the personal skill is installed (otherwise read the
three named orientation docs), and run the world-state command before changing
files. Feature work belongs in a dedicated worktree. Keep one task per distinct
outcome; resume an existing task when continuing the same outcome.

## Claude-to-Codex mapping

| Claude Code surface | Codex equivalent in this repository | Authority |
| --- | --- | --- |
| `CLAUDE.md` and always-loaded rules | `AGENTS.md` | `AGENTS.md`; Claude's hard-invariant block is generated from it |
| `.claude/skills/*` and slash skills | `.agents/skills/*`; invoke with `$skill-name` | `.claude/skills`; Codex tree is generated |
| `.mcp.json` local dev tools | `.codex/config.toml` → `justsearch-dev` | shared MCP server implementation |
| `.claude/settings*.json` hooks | `.codex/hooks.json` | `governance/agent-hooks.v1.json` |
| Claude agent types | `.codex/agents/{explorer,worker,reviewer}.toml` | Codex-native role files |
| Claude transcript telemetry | Codex rollout and OTel adapters | neutral agent-analytics ledger |

The mapping is behavioral rather than byte-for-byte. Unsupported Codex lifecycle
events are omitted explicitly, and Claude-only model/task hooks are excluded
with tested reasons. The shared safety guards, documentation hints, MCP session
injection, context warning, compaction context, and telemetry sink startup run
through the Codex hook adapter.

## Verify the integration

Run the deterministic repository checks after changing any agent surface:

```powershell
node scripts/ci/check-codex-agent-parity.mjs
node scripts/dev/test-dev-mcp-projection-live.mjs
node scripts/docs/prompt-surface-inventory.mjs
```

For a real-client check:

```powershell
codex mcp list
codex exec --json "Inspect AGENTS.md and report whether the justsearch-dev MCP server and repository skills are available. Do not edit files."
```

Expected results:

- `justsearch-dev` is enabled and exposes its twelve `justsearch.dev.*` tools.
- Project skills appear from `.agents/skills`.
- project hooks load without a parse error and can block a synthetic forbidden
  command in the adapter contract test.
- the Codex turn produces a normalized `gen_ai.system = codex-cli` metric when
  the machine exporter is configured.

If project MCP, skills, or hooks are all absent, check project trust first. If
only MCP is absent, run `codex mcp list` from the repository root and validate
`.codex/config.toml`. If hooks misbehave, set
`JUSTSEARCH_DISABLE_HOOKS=1` for recovery, capture the failure, and fix the
shared manifest or adapter rather than hand-editing generated wiring.

## Optional recent-chat import

Codex's interactive `/import` flow can copy selected Claude Code setup items
and up to 50 chats from the last 30 days. It does not delete the Claude source.
For JustSearch, do not import project instructions, hooks, MCP configuration, or
skills on top of this checked-in migration: that would create a second,
machine-local fork of the governed repository surfaces. Import only selected
recent chats when conversational continuity is useful. Work older than the
30-day window remains available in Claude's transcripts and in the repository's
tempdocs/history; it is not lost or required for Codex to understand current
project state.

## Regeneration contract

Use these commands after editing their authorities:

```powershell
node scripts/docs/agent-instructions-sync.mjs
node scripts/docs/skills-sync.mjs
node scripts/codegen/gen-agent-hooks.mjs
node scripts/codegen/gen-codex-hooks.mjs
node scripts/docs/llmstxt-generate.mjs
```

CI runs `check-codex-agent-parity.mjs`, the general hook-integrity gate, the
agent-analytics tests, and documentation generation checks. Never edit
`.agents/skills` or `.codex/hooks.json` directly.

## Official Codex references

- [Codex authentication](https://learn.chatgpt.com/docs/auth)
- [Import from another agent](https://learn.chatgpt.com/docs/import)
- [AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Skills](https://learn.chatgpt.com/docs/build-skills)
- [Hooks](https://learn.chatgpt.com/docs/hooks)
- [MCP servers](https://learn.chatgpt.com/docs/extend/mcp)
- [Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents)
