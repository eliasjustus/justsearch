<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. Extract domain-specific lessons to a skill before growing this file. -->

# Agent Lessons — Claude Code Platform

Cross-cutting platform constraints. Project workflow lessons live in canonical docs and skills; substrate-discipline reference cases live in [`docs/reference/contributing/agent-postmortems.md`](../../docs/reference/contributing/agent-postmortems.md).

## Claude Code platform constraints

- **Subagents do NOT inherit `CLAUDE.md`, `MEMORY.md`, or `.claude/rules/*.md`** (verified — Piebald-AI prompt-leak + live introspection). But a *baseline* brief — the Hard Invariants (live-projected from CLAUDE.md), platform, and subagent risk profile — IS auto-injected by the `subagent-guide` SubagentStart hook (tempdoc 620 Part V). So brief the *task-specific* part inline. <!-- rule:subagents-no-inheritance -->
- **Parent session hooks do NOT fire inside subagents** ([#237](https://github.com/anthropics/claude-code/issues/237), [#21460](https://github.com/anthropics/claude-code/issues/21460)). (Observability caveat, tempdoc 622: subagent *interiors* are nonetheless visible via native Claude Code OpenTelemetry — the Task subagent's spans nest under the parent's `claude_code.tool` span and its cost attributes carry `query_source:subagent`+`agent.name`, empirically confirmed — so the hook-layer blind spot is closeable at the OTel layer, not at the hook layer.) <!-- rule:parent-hooks-dont-fire-in-subagents -->
- **`additionalContext` from SessionStart hooks is unreliable** for persistent state — use `.claude/rules/` files instead.
- **`.claude/agents/` custom agents cannot override built-in agents** ([#8697](https://github.com/anthropics/claude-code/issues/8697), [#18212](https://github.com/anthropics/claude-code/issues/18212), [#16594](https://github.com/anthropics/claude-code/issues/16594)).
- **Agent tool `model` parameter works** (restored v2.1.72) — `model: "haiku"` for cheap search, `model: "sonnet"` for moderate work.
- **`Read` tool has silent truncation layers**: 2000 chars/line, 2000 lines, 25k tokens (varies by model). Claude Code also auto-limits Reads on files >8 KB to 200 lines without offset/limit. Use offset/limit explicitly when reading rules/guardrail content.
- **`Edit` tool validates `~/.claude/settings.json`** against the canonical schema. Probe via Edit if you're unsure whether a documented setting exists — the validator returns the schema on rejection.
- **Scoop shim junctions are unreachable from this session.** `(Get-Item <junction>).Target` returns the target, but `Test-Path <junction>\bin` returns `False`. Symptom: `Shim: Could not create process …`. Workaround: call the binary via its resolved path, e.g., `& "F:\scoop\apps\gh\2.90.0\bin\gh.exe" workflow run ci.yml`. Do NOT reinstall scoop packages — it's a session permissions quirk, not corruption.
- **Too many open tabs hang the claude-in-chrome extension during live UI validation; a restart clears it.** There is no Chrome-process restart tool, and killing `chrome.exe` would destroy the user's entire browser session, so avoid both. Recover by draining the MCP tab group instead: close its tabs with `tabs_close_mcp` one at a time (parallel closes race — verified, they leave the group in an inconsistent state), then recreate a clean group with `tabs_context_mcp { createIfEmpty: true }`. Closing the group's last tab auto-removes it, so drain-then-recreate is a clean soft-restart of the automation surface without touching the user's real browsing session.
- **`browser_batch` chaining rapid navigations races the SPA boot** (tempdoc 618 §8). A batch of hash-route navigations + a screenshot can capture a blank page because the app has not mounted yet. Issue one navigation, poll for readiness (or use the `wait` action), then screenshot — act-then-read, not act-act-act-read.
- **The `bash-guard` block on `sleep >= 1s` also catches a benign one-shot wait** (tempdoc 618 §8) — e.g. `sleep 4` to let Vite warm. Restructure as a condition-poll (`while ! curl -sf <url>; do sleep 0.2; done`) or the browser `wait` action; the guard is correct, it just costs one extra turn for a legitimate wait.
- **A command piped through `tail`/`grep`/`head` reports the pipe's exit code, not its own** (tempdoc 618 §10a). A backgrounded `./gradlew build -x test | tail -25` can notify "exit 0" while the build actually FAILED — one step from fast-forwarding `main` on a red build. Run a command whose exit matters bare, or `set -o pipefail`, or assert on its output text (`BUILD FAILED`); the harness surfaces only the last pipe stage's code. Also delivered at the moment of relevance by the non-blocking `pipe-mask-hint` (PreToolUse/Bash) — the residence→delivery conversion this rule motivated (618 settlement; tier-register row 37). <!-- rule:piped-exit-masked -->
- **A persistent background server runs as the bare `run_in_background` main process** (tempdoc 618 §11d). Wrapping it cost 3 tries to keep `serve-worktree-fe` alive: `timeout N …` self-kills at N s, and `node … | grep &` orphans the inner process on shell exit. Launch it as the bare main command — no `timeout` wrapper, no trailing `&`, no pipe.

## Verifying Claude Code claims (evidence chain, best to worst)

1. Runtime probe — Edit-tool schema validation, hook execution with crafted JSON, subagent introspection.
2. Anthropic's [official docs](https://code.claude.com/docs/en/) and [GitHub issues](https://github.com/anthropics/claude-code/issues).
3. [Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts) — actual subagent prompts extracted from the binary.
4. Anthropic's [`bash_command_validator_example.py`](https://github.com/anthropics/claude-code/blob/main/examples/hooks/bash_command_validator_example.py) and similar published examples.
5. Third-party blogs — useful for orientation, **not for committing to changes**.

## Named substrate-discipline principles

Each handle resolves to a paragraph in [`docs/reference/contributing/agent-postmortems.md`](../../docs/reference/contributing/agent-postmortems.md).

- **`audit-without-test`** — A subagent audit's narrow lifecycle claim is unverified until a regression test exercises the claimed property. Static audits are hypotheses.
- **`wrong-gate`** — `grep` the set-site of any flag your change depends on; symbol-exists ≠ gate-fires-in-target-scenario.
- **`substrate-without-consumer-flavors`** — C-018 governs NEW substrate slots, not type-system refactors. A rename or partition is not a new field for nobody to read.
- **`independent-review-required`** — A second-agent (≠ implementer) static review of substrate-shipping commits is good practice. (Was gate-enforced via the `independent-review` discipline gate; that gate was retired — tempdoc 530 §Remediation — so this is honor-system guidance now.)
- **`static-green ≠ live-working`** — Substrate with a user-facing reader needs all three verification tiers: compile + unit tests, independent static review, live-stack against running dev stack.
- **`verdict-is-gate`** — Treat an independent reviewer's verdict as a strong signal, not a casual discussion item. (Historically gate-enforced; now honor-system after the `independent-review` gate's retirement.)
- **`catalog-verbatim`** — When picking up a feature ID from a candidate-catalog slice, read the catalog row verbatim before scoping.
- **`wire-emitter-elision`** — For every newly-added component on a wire-emitted type, verify the production emitter serializes it.
- **`ai-offline-isnt-a-wall`** — Before declaring any verification tier unavailable, check whether you have a tool that provides it. `ai_activate` loads the LLM in ~11s.
- **`standalone-capability-stays-stuck`** — When a class lazily creates a `Capability` because a dependency isn't ready yet, and that dependency later holds its own Capability instance, the late-bind step must bridge them via `addListener` (mirror initial state synchronously, then forward transitions). "I created the right object" ≠ "the right object's state will be updated." Pattern at `HeadAssembly.connectKnowledgeServer` (`AppFacadeBootstrap` pre-519).
- **`unreachable-seed-green`** — A unit-test seed that doesn't mirror the real producer's data flow gives confident-but-wrong green; mirror the producer's seed, or live-verify reload/persistence behaviour.
- **`subset-isnt-the-suite`** — A hand-picked subset of gates/tests passing is not "the gates passed"; run the full kernel + full suite before declaring done, not at merge.
- **`green-masked-destructive`** — When a passing verification depends on an environment precondition, test the adverse precondition too; a green the environment happened to satisfy can hide the destructive branch.
