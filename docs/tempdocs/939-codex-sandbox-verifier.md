---
title: "Dual-harness sandbox verifier: Codex as an in-sandbox agent"
type: tempdocs
status: "IMPLEMENTED — staging + docs landed; first graded Codex round pending"
created: 2026-09-06
updated: 2026-09-06
charter: "let the operator start either Claude Code or Codex on the staged sandbox share and say start, with the same charter, the same start skill, and the same host-side graders"
related:
  - 805-round-11-token-freshness-and-consequence-honesty
  - 806-round-12-locked-state-truthfulness
  - 727-session-transcript-friction-mining
---

# 939 — Dual-harness sandbox verifier

## Why

Two reasons, in order of weight:

1. **Independence.** `sandbox-CLAUDE.md` "Independence invariant": the guest
   never grades itself. A verifier from a different lab than the committing
   agent is the strongest form of "reviewer is not the committer".
2. **Fidelity.** The Codex desktop app's Computer Use drives the real window
   (focus, menus, keyboard, clipboard) the way a user does. The native
   PowerShell tier synthesizes input and proves the pixels are right, not that
   a user can get there. For a release-validation round the former is the
   point. (Owner pilot 2026-09-06: MSIX sideload, sign-in and the Computer Use
   plugin all worked in a Store-less Windows Sandbox — verified empirically,
   not from issue trackers.)

Not a reason: benchmark rankings. The owner decided the model; this tempdoc
does not relitigate it.

## What was wrong

The graders were already harness-agnostic (`check_coverage.py`,
`check_golden_parity.py`, `check_token_health.py`, `collect-evidence.ps1`,
`gui/`, `mcp-client/` only read files). The **entry points** were not: the
share staged `CLAUDE.md` + `.claude/` only. Codex reads `AGENTS.md`, a trusted
`.codex/config.toml`, and `.agents/skills/` — none present — so a Codex session
on the mapped folder had no charter, no permissions setup, and no `$start`.
Two quieter defects on top:

- Codex stops reading `AGENTS.md` at `project_doc_max_bytes` (default 32 KiB)
  and drops the rest **silently**. The charter is ~66 KB.
- The repo's own `.codex/config.toml` declares a `required = true` MCP server
  backed by `scripts/dev/`, which is not in the sandbox — copying it would fail
  every session start (same class as the stripped Claude hooks).

## What changed

`scripts/sandbox/sandbox-launch.py`:

- `stage_charter_entry_points()` — the charter is staged as **both**
  `CLAUDE.md` and `AGENTS.md`, same bytes.
- `stage_codex_settings()` — writes a generated, credential-free
  `<share>/.codex/config.toml` (`render_codex_config()`: `gpt-5.6-sol`,
  `approval_policy = "never"`, `sandbox_mode = "danger-full-access"` — Windows
  Sandbox is the isolation boundary, mirroring the Claude `bypassPermissions`
  staging — `project_doc_max_bytes = 262144`, Computer Use allowlist for
  `JustSearch.exe`) and stages `sandbox-start-SKILL.md` to
  `.agents/skills/start/SKILL.md` so `$start` == `/start`. **Fail-closed**: if
  the staged `AGENTS.md` exceeds the cap the config declares, staging exits 1.
- `KICKOFF.md` generator and the console tail name both harnesses; the install
  authority moved to `sandbox-environment.md` "Agent harness".

Docs, with one audience rule applied throughout: **the charter is read by an
agent that is already running inside a harness**, so install / sign-in /
trust / kick-off instructions are operator-facing and live in
`sandbox-environment.md` + `KICKOFF.md`; the charter tells the agent only what
differs once running (Codex: confirm the charter was not truncated — "can you
see the last section?" — before doing anything). The pre-939 charter already
had this flaw in miniature (it told Claude Code how to install Claude Code).

GUI guidance now edges toward computer use: if the harness has it, drive the
GUI with it; the native PowerShell tier is the guaranteed floor and the way to
get the evidence PNG on disk under its coverage filename when a tool cannot
save to a chosen path. Coverage credit is by filename token
(`check_coverage.py`), which is the one hard constraint either way.

Tests: `scripts/sandbox/test_sandbox_launch_codex.py` (6) — both entry points
byte-identical to the charter, real charter under the cap and over Codex's
default (so the cap line is load-bearing, not decorative), config
credential-free and free of repo-tooling references, oversized `AGENTS.md`
exits 1, and the repo `.codex/config.toml` is not what gets staged.

Also fixed: `.agents/skills/installer/SKILL.md` §8.1 claimed the launcher
staged `AGENTS.md` / `.agents/` / `.codex/` before any of it existed.

## Open — verify on the first graded Codex round

1. **`project_doc_max_bytes` honored from project-level config.** Documented
   as a config key; whether the project layer governs it is UNVERIFIED. The
   charter's "can you see the last section" check catches it either way;
   fallback is the same line in the guest's `%USERPROFILE%\.codex\config.toml`
   (in `sandbox-environment.md`).
2. **Computer Use app-id form.** `always_allowed_app_ids = ["JustSearch.exe"]`
   follows the documented example shape (`mspaint.exe`); confirm the prompt
   does not still fire.
3. **Can Computer Use save a screenshot to a chosen path?** Decides whether
   `snap.ps1` is only a fallback or the evidence path in practice. The skill
   text already covers both.
4. **Rate limits over a multi-hour round** on the pinned model.
5. The Claude-for-Chrome negative note in the charter is Claude-specific and
   reads as noise under Codex — left labelled, not removed.
