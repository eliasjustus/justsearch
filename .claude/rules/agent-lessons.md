<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. Extract domain-specific lessons to a skill before growing this file. -->

# Agent Lessons — Claude Code Platform

Cross-cutting platform constraints. Project workflow lessons live in canonical docs and skills; substrate-discipline reference cases live in [`docs/reference/contributing/agent-postmortems.md`](../../docs/reference/contributing/agent-postmortems.md).

## Claude Code platform constraints

- **Subagent CLAUDE.md inheritance is AGENT-TYPE-DEPENDENT** (re-verified 2026-07-16, 3 live introspection probes, tempdoc 743 Phase 2 — supersedes the older "no inheritance" claim, which the platform obsoleted): general-purpose and custom agents (e.g. claude-code-guide) now receive the FULL `CLAUDE.md` + `.claude/rules/*.md` natively; **Explore/Plan (and fork) agents still receive NONE of it** — for those, the `subagent-guide` SubagentStart hook's baseline brief (Hard Invariants live-projected from CLAUDE.md, platform, risk profile) is the ONLY project context. The hook currently injects for all types. Task-specific context was never inherited for any type — so the inline task brief in the Agent prompt remains mandatory regardless. Do not trust a subagent's own report about what's in its context if it conflicts with a probe — a subagent was observed repeating the hook's (then-stale) claim over its own context contents. <!-- rule:subagents-no-inheritance -->
- **Parent session hooks do NOT fire inside subagents** ([#237](https://github.com/anthropics/claude-code/issues/237), [#21460](https://github.com/anthropics/claude-code/issues/21460)). **Verified exception (2026-07-12 probe):** a parent PreToolUse/`Agent` hook DOES fire on a subagent's *nested* spawn — `subagent-model-guard.mjs` blocked a sonnet subagent's unpinned child. Unverified for other tools/events; re-probe first. (Observability caveat, tempdoc 622: subagent *interiors* are nonetheless visible via native Claude Code OpenTelemetry — the Task subagent's spans nest under the parent's `claude_code.tool` span and its cost attributes carry `query_source:subagent`+`agent.name`, empirically confirmed — so the hook-layer blind spot is closeable at the OTel layer, not at the hook layer.) <!-- rule:parent-hooks-dont-fire-in-subagents -->
- **`additionalContext` from SessionStart hooks is unreliable** for persistent state — use `.claude/rules/` files instead.
- **`.claude/agents/` custom agents cannot override built-in agents** ([#8697](https://github.com/anthropics/claude-code/issues/8697), [#18212](https://github.com/anthropics/claude-code/issues/18212), [#16594](https://github.com/anthropics/claude-code/issues/16594)).
- **Agent tool `model` parameter works** (restored v2.1.72) — `model: "haiku"` for cheap search, `model: "sonnet"` for moderate work.
- **`Read` tool has silent truncation layers**: 2000 chars/line, 2000 lines, 25k tokens (varies by model). Claude Code also auto-limits Reads on files >8 KB to 200 lines without offset/limit. Use offset/limit explicitly when reading rules/guardrail content.
- **`Edit` tool validates `~/.claude/settings.json`** against the canonical schema. Probe via Edit if you're unsure whether a documented setting exists — the validator returns the schema on rejection.
- **A worktree-copy and main-checkout copy of the "same" file don't share `Edit`'s read-state** — re-read the exact path before editing it (tempdoc 618 §11e / 727 F-7a; `edit-reread-hint` flags this). <!-- rule:edit-reread-cross-root -->
- **Scoop shim junctions are unreachable from this session** (symptom: `Shim: Could not create process …`). Call the binary via its resolved path instead, e.g. `& "F:\scoop\apps\gh\2.90.0\bin\gh.exe" workflow run ci.yml`. Don't reinstall scoop packages — it's a session permissions quirk, not corruption.
- **`browser_batch` chaining rapid navigations races the SPA boot** (tempdoc 618 §8). A batch of hash-route navigations + a screenshot can capture a blank page because the app has not mounted yet. Issue one navigation, poll for readiness (or use the `wait` action), then screenshot — act-then-read, not act-act-act-read.
- **The `bash-guard` block on `sleep >= 1s` also catches a benign one-shot wait** (tempdoc 618 §8) — e.g. `sleep 4` to let Vite warm. Restructure as a condition-poll (`while ! curl -sf <url>; do sleep 0.2; done`) or the browser `wait` action; the guard is correct, it just costs one extra turn for a legitimate wait.
- **After a branch is pushed once, catch up to a moving base with `git merge`, not `git rebase`** (tempdoc 695). Rebasing a pushed branch rewrites remote commits, so updating it needs a force-push, which is blocked with no exception (`branch-safety.md`'s `bash-guard.mjs` table). Recover a bad rebase with `git reset --hard origin/<your-branch>`, then `git merge origin/<default-branch>` instead. Under the live merge queue (829 R4), this is only needed for long-lived branch maintenance, not for merging — the queue itself integrates against the moving base, so `strict` up-to-date-before-merge no longer blocks a stale branch. Two related `gh` CLI merge/CI-wait quirks live in `agent-guide.md`'s History Publication section (narrower scope, not always-loaded).
- **A command piped through `tail`/`grep`/`head` reports the pipe's exit code, not its own** (tempdoc 618 §10a). A backgrounded `./gradlew build -x test | tail -25` can notify "exit 0" while the build actually FAILED — one step from fast-forwarding `main` on a red build. Run a command whose exit matters bare, or `set -o pipefail`, or assert on its output text (`BUILD FAILED`); the harness surfaces only the last pipe stage's code. Also delivered at the moment of relevance by the non-blocking `pipe-mask-hint` (PreToolUse/Bash) — the residence→delivery conversion this rule motivated (618 settlement; tier-register row 37). <!-- rule:piped-exit-masked -->
- **A persistent background server runs as the bare `run_in_background` main process** (tempdoc 618 §11d). Wrapping it cost 3 tries to keep `serve-worktree-fe` alive: `timeout N …` self-kills at N s, and `node … | grep &` orphans the inner process on shell exit. Launch it as the bare main command — no `timeout` wrapper, no trailing `&`, no pipe.
- **Windows bulk edits corrupt UTF-8 via cp1252 round-trips** (tempdoc 742: a worker's bulk rename mangled non-ASCII in 47 Java files; only 3 suite assertions could notice). Subagent multi-file-edit briefs MUST mandate Edit/Write tools or node UTF-8 scripts — never PowerShell `Get-/Set-Content` rewriting — and the orchestrator checks the diff adds no unintended non-ASCII (`git diff | grep -P '^\+.*[^\x00-\x7F]'`). <!-- rule:utf8-bulk-edits -->
- **Time-to-complete is an architecture signal.** A delegated fix landing in minutes is probably safe to take; an hour-plus for a "simple" request is telling you about the code, not the agent — read the diff and architecture before merging.
- **Tracked background tasks are killed at ~60 minutes, and `TaskStop` does not kill child bash loops** (2026-07-22, certification campaign: caused concurrent-driver corruption). The pattern that held: a detached `Start-Process` driver plus self-terminating (<590s) polls.
- **Session cwd drifts into pub/agent worktrees after `cd`-in-compound-commands and persists across turns** — four incidents in one arc, including a worktree removed from inside itself and a possible cause of a locked sibling worktree's mid-run destruction. Remedy that held: prefix repo-root-dependent commands with an absolute `cd`, and run `remove-worktree` only from the repo root.

## Verifying Claude Code claims (evidence chain, best to worst)

1. Runtime probe — Edit-tool schema validation, hook execution with crafted JSON, subagent introspection.
2. Anthropic's [official docs](https://code.claude.com/docs/en/) and [GitHub issues](https://github.com/anthropics/claude-code/issues).
3. [Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts) — actual subagent prompts extracted from the binary.
4. Anthropic's [`bash_command_validator_example.py`](https://github.com/anthropics/claude-code/blob/main/examples/hooks/bash_command_validator_example.py) and similar published examples.
5. Third-party blogs — useful for orientation, **not for committing to changes**.

## Named substrate-discipline principles

Each handle below resolves to a full case paragraph in
[`docs/reference/contributing/agent-postmortems.md`](../../docs/reference/contributing/agent-postmortems.md),
which is the authority — read it there. Only the handle list lives here, so the two do not drift:

`audit-without-test` · `wrong-gate` · `substrate-without-consumer-flavors` ·
`independent-review-required` · `static-green ≠ live-working` · `verdict-is-gate` ·
`catalog-verbatim` · `wire-emitter-elision` · `ai-offline-isnt-a-wall` ·
`standalone-capability-stays-stuck` · `unreachable-seed-green` · `green-masked-destructive`

- **`subset-isnt-the-suite`** — A hand-picked subset of gates/tests passing is not "the gates passed"; run the full kernel + full suite before declaring done, not at merge. Also delivered at the moment of relevance by the non-blocking `merge-full-suite-hint` (PostToolUse/Bash `git merge`); the worked case is postmortem #13. <!-- rule:subset-isnt-the-suite -->
- **`green-masked-destructive`** — When a passing verification depends on an environment precondition, test the adverse precondition too; a green the environment happened to satisfy can hide the destructive branch.
