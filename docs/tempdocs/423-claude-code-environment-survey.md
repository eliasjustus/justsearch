---
title: "Claude Code Agent Environment Survey"
type: tempdocs
status: shipped
created: 2026-04-27
shipped: 2026-04-28
---

# 423 — Claude Code Agent Environment Survey

## Status

**SHIPPED.** Created 2026-04-27 as a standalone environmental reference;
expanded 2026-04-28 into a critical-analysis + probe-pass + implementation
pass. Code/config changes shipped per §14.20. Durable lessons promoted to
`.claude/rules/agent-lessons.md` (§14.21). Situational observations filed
to `docs/observations.md` (§14.22). Reading order for someone new: the
original §§1-13 survey is the canonical baseline; §14 records what
verification revealed; §14.20 is the executive summary of what changed.

## Goal

Capture every environmental factor that shapes an agent's behavior
in this checkout, so a fresh agent (or a human auditing one) can
predict why a tool call succeeds, fails, gets rewritten, or gets
blocked. The factors split into seven layers: **process**, **CLI
configuration**, **tools**, **hooks**, **skills/commands**, **MCP
servers**, and **session/telemetry**.

The CLAUDE.md, `.claude/rules/*.md`, and `agent-guide.md` already
cover the *normative* side ("what you should do"). This tempdoc
covers the *descriptive* side ("what's actually wired up, where, and
how it fires"), with explicit pointers into the implementation files.

---

## 1. Process & host

| Property | Value | Source |
|---|---|---|
| Working directory | `F:\JustSearch` (main worktree) or `.claude/worktrees/<name>/` (in-session worktree) | `pwd` |
| Platform | `win32`, Windows 11 Pro 10.0.26200 | system prompt |
| Shell | Git Bash (`/mingw64/bin/bash`) primary; PowerShell available via `PowerShell` tool | `$PATH` |
| Java | Temurin 25 JDK at `F:\scoop\apps\temurin25-jdk\current\bin` | `$PATH` |
| Node | Scoop-managed via nvm (`F:\scoop\apps\nvm\current\nodejs`) | `$PATH` |
| Python | Scoop `F:\scoop\apps\python\current` | `$PATH` |
| `CLAUDECODE` | `1` (signals Claude Code is running) | env |
| `CLAUDE_CODE_ENTRYPOINT` | `cli` | env |
| `CLAUDE_CODE_EXECPATH` | `C:\Users\<user>\.local\bin\claude.exe` | env |
| `JUSTSEARCH_AGENT_SESSION_ID` | per-session UUID (this run: `a0abc525-…`) | env, exported by SessionStart hook |

### Scoop shim quirk (Claude Code platform constraint)

Scoop installs to NTFS junctions under `F:\scoop\apps\<tool>\current\`.
The Claude Code process **cannot traverse those junctions** — `gh`,
`jq`, `imagemagick`, etc. invoked through their shims fail with
"Shim: Could not create process". Workaround: call the resolved real
path, e.g. `& "F:\scoop\apps\gh\2.90.0\bin\gh.exe"`. Discovery:
`(Get-Item F:\scoop\apps\<tool>\current -Force).Target`. Git is
unaffected because it is not a scoop install. Documented in
`.claude/rules/agent-lessons.md`.

### Bash quirks worth remembering

- Forward slashes everywhere; `/dev/null`, not `NUL`.
- `tmp/dev-runner/active.json` plus `tmp/agent-telemetry/` are the
  two state directories agents most often touch.
- `CLAUDE_ENV_FILE` sourcing is broken on Windows
  (anthropics/claude-code#27987) — see §7 for the file-based
  workaround the project ships.

---

## 2. CLI configuration & settings hierarchy

Claude Code merges configuration in five layers (managed → CLI
flags → local-project → project → user). On Windows the relevant
files for an agent in this repo are:

| Scope | File | Purpose |
|---|---|---|
| User | `%USERPROFILE%\.claude\settings.json` | personal global |
| Project (committed) | `.claude/settings.json` | shared with the repo |
| Local project (gitignored) | `.claude/settings.local.json` | personal overrides for this checkout |
| Project MCP | `.mcp.json` | MCP server definitions |

### `.claude/settings.json` (committed, shared)

```json
{
  "enableAllProjectMcpServers": true,
  "enabledMcpjsonServers": ["github"],
  "enabledPlugins": {
    "jdtls-lsp@claude-plugins-official": true,
    "typescript-lsp@claude-plugins-official": true
  }
}
```

### `.claude/settings.local.json` (gitignored, this user)

Highlights actually in effect today:

- `permissions.defaultMode = "bypassPermissions"` — tool calls are
  auto-approved without prompting. Equivalent to passing
  `--dangerously-skip-permissions`. Caveat: this lives in
  `settings.local.json`, **not** project settings, because Claude Code
  ignores `skipDangerousModePermissionPrompt` and friends in project
  settings to prevent untrusted repos from auto-bypassing.
- `permissions.allow` includes `Bash(*)`, `WebSearch`, `WebFetch(*)`,
  `Skill(*)`, `mcp__justsearch-dev__*`, `mcp__github__*`, `mcp__ide__*`.
- `enabledMcpjsonServers = ["justsearch-dev", "github"]` — narrows the
  set of MCP servers the user opts into beyond the project's allowlist.
- `enabledPlugins` overrides the project setting and **disables**
  `jdtls-lsp` and `typescript-lsp` for this user. Net effect: no LSP
  plugin runs.
- `autoMemoryEnabled: false` — auto-memory file `MEMORY.md` is not
  written by Claude Code itself, but `compact-save.mjs` still tries to
  read it from `~/.claude/projects/<repo-mangled>/memory/MEMORY.md` (a
  no-op when the feature is off).
- `hooks: { … }` — see §4 for the full inventory.

### Slash-command-style invocation (`/skill-name`)

`Skill(*)` is permitted, so any project skill in `.claude/skills/<name>/`
runs without prompt. Built-in CLI commands (`/help`, `/clear`, `/loop`,
`/start`, …) are dispatched separately and don't go through the Skill
tool. The user-typed `/start` you see above this report is the project
skill at `.claude/skills/start/SKILL.md`.

---

## 3. Tools available

The harness loads only a small set up front and surfaces the rest
through `ToolSearch`. The split matters because:

1. Calling a deferred tool by name without first fetching its schema
   fails with `InputValidationError`. Always fetch first via
   `ToolSearch` with `select:<name>` before first use.
2. Some tools are repo-specific MCP tools (`mcp__justsearch-dev__*`,
   `mcp__github__*`); others are platform-built-ins (`Bash`, `Edit`,
   `Read`, …).

### Always loaded at session start

`Agent`, `Bash`, `Edit`, `Glob`, `Grep`, `PowerShell`, `Read`,
`ScheduleWakeup`, `Skill`, `ToolSearch`, `Write`.

### Deferred (loaded via `ToolSearch select:<name>`)

`AskUserQuestion`, `CronCreate`, `CronDelete`, `CronList`,
`EnterPlanMode`, `EnterWorktree`, `ExitPlanMode`, `ExitWorktree`,
`Monitor`, `NotebookEdit`, `PushNotification`, `RemoteTrigger`,
`TaskCreate`, `TaskGet`, `TaskList`, `TaskOutput`, `TaskStop`,
`TaskUpdate`, `WebFetch`, `WebSearch`, plus
`mcp__claude_ai_Gmail__*`, `mcp__claude_ai_Google_Calendar__*`,
`mcp__claude_ai_Google_Drive__*`, and
`mcp__github__*` (40+ entries: PRs, issues, branches, files, code
search, repository search).

### Subagent dispatch

`Agent { subagent_type, prompt, isolation?, model?, run_in_background? }`
is the only entry point for parallel work. Subagent types available:

- `claude-code-guide` — Claude Code/SDK/API questions only.
- `Explore` — fast read-only codebase exploration.
- `general-purpose` — research and multi-step tasks (Tools: *).
- `Plan` — architect/planning agent.
- `statusline-setup` — configures status line.

Project-defined custom agents in `.claude/agents/` would be ignored:
the directory does not exist, and agent-lessons.md records
"`.claude/agents/` custom agents CANNOT override built-in agents
(#8697, #18212, #16594) — verified non-functional".

### Isolation modes

`Agent { isolation: "worktree" }` creates a temporary worktree for
the subagent. Auto-cleaned when the agent makes no changes; preserved
(path returned) otherwise. **Known platform bug:** worktree subagent
branches are cut from `origin/main`, not the calling session's HEAD
(claude-code#50850). Worktree subagent sessions are also transient —
not resumable via `claude --resume` (#42596).

---

## 4. Hooks (this project's behavior modifications)

Hooks are configured in `.claude/settings.local.json` and implemented
under `scripts/agent-analytics/hooks/`. Two roles:

- **Telemetry**: `dispatch.mjs` is wired to nearly every event and
  appends NDJSON to `tmp/agent-telemetry/events.ndjson` (async, never
  blocks).
- **Behavior**: synchronous guards/hints that block, modify, or
  annotate tool calls.

`scripts/agent-analytics/lib/event-writer.mjs` does the NDJSON write;
`input-summarizer.mjs` truncates tool args/responses for the log;
`telemetry-io.mjs` powers `analyze-session.mjs`,
`evaluate-session.mjs`, etc.

### Behavior-modifying hooks

| Hook | Event/matcher | What it does |
|---|---|---|
| `bash-guard.mjs` | `PreToolUse` / `Bash` | Blocks `git push --force` everywhere. Blocks `git checkout`, `git switch`, `git reset --hard`, `git clean -f`, `git restore .` only in the **main** worktree (detected via `.git` being a directory vs. a file). Blocks `sleep ≥1s` everywhere (allows `sleep 0.x` for polling backoff). Redirects bare `cat`/`head`/`tail`/`grep`/`rg` to Read/Grep. |
| `intervene.mjs` | `PreToolUse` / `Read`, `Edit` | For Read: tracks per-session unbounded-read counts. Auto-injects `limit: 200` on files >8KB without offset/limit (uses `permissionDecision: "allow"` + `updatedInput`). After **10 unbounded reads of the same file**, blocks further unbounded reads. For Edit: tracks edit counts (no blocking). |
| `repeat-guard.mjs` | `PreToolUse` / all | Blocks the **3rd consecutive identical** tool call (fingerprinted per-tool). Excludes Gradle commands. |
| `build-counter.mjs` | `PreToolUse` / `Bash` | Once `consecutiveFailures` reaches 3 on `gradlew` commands, blocks once with an advisory, then allows the next attempt (one-shot pattern, intentionally not a permanent block — would deadlock the agent). |
| `mcp-session-inject.mjs` | `PreToolUse` / `mcp__justsearch-dev__.*` | Auto-injects the parent session_id into every justsearch-dev MCP tool input as `sessionId`, closing the same-CWD identity gap. |
| `docs-regen-hint.mjs` | `PostToolUse` / `Edit\|Write` on `docs/explanation,reference,how-to,decisions/*.md` | Reminds to run `llmstxt-generate.mjs` and `skills-sync.mjs`. Pure annotation. |
| `ssot-hint.mjs` | `PostToolUse` / `Edit\|Write` on `SSOT/catalogs/*` or classpath copy | Reminds about the dual-copy sync. Pure annotation. |
| `lockfile-hint.mjs` | `PostToolUse` / `Edit` on `*build.gradle.kts` | Reminds to run `resolveAndLockAll`. Pure annotation. |
| `ui-shot-hint.mjs` | `PostToolUse` / `Edit\|Write` on `modules/ui-web/src/**/*.{ts,tsx}` | Looks up `scripts/jseval/jseval/ui_step_index.json`, lists affected ui-shot steps. Pure annotation. |
| `subagent-guide.mjs` | `SubagentStart` | Injects codebase-context hints (`additionalContext`) — large-file list, docs/llms.txt pointer, platform note, session_id forwarding for workflow wrappers. The native subagent prompt does **not** see CLAUDE.md, so this is the only project-aware briefing. |

### Lifecycle hooks

| Hook | Event | What it does |
|---|---|---|
| `export-session-env.mjs` | `SessionStart` | Writes session UUID to `tmp/agent-telemetry/current-session-id` (file-based fallback because Windows `CLAUDE_ENV_FILE` sourcing is broken — anthropics/claude-code#27987). On Unix, also appends `export JUSTSEARCH_AGENT_SESSION_ID=…` to `$CLAUDE_ENV_FILE`. |
| `compact-save.mjs` | `PreCompact` | Snapshots `MEMORY.md` summary + `git diff --name-only` + read/edit caches to `tmp/agent-telemetry/compact-state-<sid>.json`. Resets `read-counts-<sid>.json` to `{}` so post-compaction re-reads don't trip `intervene.mjs`'s hot-file cap. Deletes `repeat-buffer-<sid>.json` for the same reason. |
| `compact-restore.mjs` | `SessionStart` (only when `source==='compact'`) | Reads the compact state file, builds an orientation summary, **writes it to `.claude/rules/compaction-state.md`** (rules files survive turns where `additionalContext` does not — #15174), and emits an `additionalContext` fallback. Deletes the temp file. On non-compact starts, cleans up any stale rules file. |
| `auto-evaluate.mjs` | `SessionEnd` | Async, ~210s timeout. Runs `analyze-session.mjs` then `evaluate-session.mjs --model haiku` to LLM-judge the session ("complete/partial/failed/abandoned" + structured rubric) and append to `outcomes.ndjson`. Cost ~$0.001/session. |
| `dispatch.mjs` | every event | NDJSON writer; also tracks `consecutiveFailures` on Gradle commands for `build-counter.mjs`, and cleans up per-session state files on `SessionEnd`. |

### Hook-platform reminders

- Hook stdin format and decision-control conventions (`Exit 0` + JSON
  `hookSpecificOutput.permissionDecision: "allow"|"deny"|"ask"|"defer"`,
  `Exit 2` + stderr to block) are documented in
  [code.claude.com/docs/en/hooks](https://code.claude.com/docs/en/hooks).
- Parent-session hooks **do not fire inside subagents** (#237, #21460);
  subagents only see `SubagentStart` injection.
- `SessionStart`'s `additionalContext` is unreliable for persistent
  state — the project instead keeps durable rules in
  `.claude/rules/*.md`. `compact-restore.mjs` exploits this fact.

---

## 5. Skills, slash commands, instructions

### Project skills (15)

`.claude/skills/<name>/SKILL.md` files. All but `start` are tagged
`user-invocable: true` with TRIGGER prefixes that double as
auto-loading guidance. Each skill's body either inlines or
dynamically syncs from a canonical doc via
`scripts/docs/skills-sync.mjs`.

| Skill | TRIGGER (when to load) |
|---|---|
| `start` | session orientation + session-attribution self-check |
| `dev-stack` | starting/stopping dev stack, MCP dev tools, port conflicts |
| `jseval` | eval datasets, profiling, polling backend status, throughput |
| `ui-check` | editing React components, visual verification via screenshots |
| `api-record` | adding fields to Java records in `app-api`, `@RecordBuilder` |
| `ssot-catalog` | editing `SSOT/catalogs/`, fields.v1.json |
| `module-arch` | new modules, `settings.gradle.kts`, ArchUnit |
| `inference-runtime` | GPU detection, ORT sessions, VRAM, embedding/NER/SPLADE |
| `search-quality` | search orchestration, fusion, reranking, eval baselines |
| `docs-maintenance` | editing canonical docs (post-edit regen sequence) |
| `doc-audit` | periodic tempdoc-to-canonical drift analysis |
| `lockfile` | dependency changes, lockfile regeneration |
| `ci-triage` | build/test failure decision tree |
| `installer` | Tauri shell, NSIS, sandbox validation, model distribution |

### Mirror at `.agents/skills/`

`.agents/skills/` is a verbatim mirror of `.claude/skills/`. Same
subdirectories, same SKILL.md files. Likely an artifact of a tool that
expects the AGENTS.md convention; the canonical AGENTS.md just
delegates to CLAUDE.md.

### Slash commands

Only one: `.claude/commands/research uncertainties.md` — a generic
"now go research and update the doc" prompt. The space in the
filename is unusual (most slash commands use kebab-case); it works
because Claude Code resolves slash commands from
`.claude/commands/<name>.md`. Plus the deprecated user command
`.claude/scratch/research uncertainties.md` (legacy, unused).

### Lock file

`.claude/scheduled_tasks.lock` records `{sessionId, pid, acquiredAt}`
for the session that holds the cron/scheduling lease. Created by
`CronCreate` / `ScheduleWakeup` infrastructure; relevant only if you
need to know whether another session is the lease holder.

---

## 6. MCP servers

Defined in `.mcp.json` (project) and gated by
`enableAllProjectMcpServers` + `enabledMcpjsonServers` from settings.

### `justsearch-dev`

Local stdio MCP server: `node scripts/dev/justsearch-dev-mcp.mjs`.
Implementation under `scripts/dev/justsearch-dev-mcp/`
(`server.mjs`, `cli.mjs`, `files.mjs`, `log.mjs`, `paths.mjs`,
`schemas.mjs`). Tools registered (canonical names use dots; harness
exposes them as `mcp__justsearch-dev__justsearch_dev_<name>`):

`justsearch_dev_start`, `justsearch_dev_stop`, `justsearch_dev_status`,
`justsearch_dev_wait_ready`, `justsearch_dev_list_runs`,
`justsearch_dev_tail_log`, `justsearch_dev_fetch_api_json`,
`justsearch_dev_api_call`, `justsearch_dev_search_query`,
`justsearch_dev_suggest`, `justsearch_dev_ingest`,
`justsearch_dev_capture_evidence`, `justsearch_dev_validate_evidence`,
`justsearch_dev_cleanup`, `justsearch_dev_agent_chat`,
`justsearch_dev_ai_activate`, `justsearch_dev_reload`,
`justsearch_dev_preflight`, `justsearch_dev_quick_health`.

Tool inputs are auto-augmented with the session_id by
`mcp-session-inject.mjs` so concurrent agents in the same checkout
don't collide on file-based session identity. Lifecycle is
co-owned with `scripts/dev/dev-runner.cjs`, which writes
`tmp/dev-runner/active.json` so other agents/sessions can detect a
running stack and either take over (`takeover: "warn"`) or back off
(`OWNER_CONFLICT`).

### `github`

`cmd /c npx -y @modelcontextprotocol/server-github`, authenticated via
`$GITHUB_PERSONAL_ACCESS_TOKEN`. Surfaces ~25 deferred tools (PRs,
issues, branches, file operations, code/issue/repo/user search).
Spawned via `cmd /c` because the npx shim is a Windows batch file
that won't execute through `node` directly.

### Disabled / not present

- `mcp__ide__*` is in the local-settings allowlist but the `ide` MCP
  server isn't defined in `.mcp.json`. Allowlist is forward-looking;
  no tools come through from it today.
- Plugin MCPs are off because the LSP plugins are disabled in
  `settings.local.json`.

---

## 7. Session, telemetry, and persistence

### Session ID

`a0abc525-3eb3-45b5-a092-3074be17ac9f` for this run. Two persistence
channels:

1. `tmp/agent-telemetry/current-session-id` — single-line UUID.
   Read by workflow wrappers (`scripts/search/run-search-workflow.mjs`)
   to attribute long-running work to a session.
2. `JUSTSEARCH_AGENT_SESSION_ID` env var — exported only on
   Linux/macOS via `CLAUDE_ENV_FILE`; Windows uses the file above.

### Per-session state files (under `tmp/agent-telemetry/`)

| File | Owner | Purpose |
|---|---|---|
| `events.ndjson` | `dispatch.mjs` | append-only event log (rotates to `.prev`) |
| `turn-count-<sid>.txt` | `dispatch.mjs` | total tool calls this session |
| `read-counts-<sid>.json` | `intervene.mjs` | per-file `{total, unbounded}` |
| `edit-counts-<sid>.json` | `intervene.mjs` | per-file edit timestamps |
| `repeat-buffer-<sid>.json` | `repeat-guard.mjs` | rolling fingerprint window |
| `build-fails-<sid>.json` | `dispatch.mjs` + `build-counter.mjs` | `{consecutiveFailures, advisoryShown}` |
| `compact-state-<sid>.json` | `compact-save.mjs` | snapshot for post-compact restore |
| `errors.log` | `event-writer.mjs` | hook errors |
| `outcomes.ndjson` | `evaluate-session.mjs` | LLM-judge verdicts |
| `sessions/<sid>.json` | `analyze-session.mjs` | per-session report |

Stale files (>24h) are pruned on the first read of a new session by
`intervene.mjs`.

### Compaction protocol

- `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=65` is recommended (compresses at
  65% of context, leaving more working memory). Not currently set in
  this session's env.
- `compact-save.mjs` runs at `PreCompact`; `compact-restore.mjs` runs
  at the next `SessionStart` with `source==='compact'`.
- The "rules-file" channel is the durable one — the
  `additionalContext` fallback is best-effort because that channel
  doesn't survive subsequent compactions reliably (#15174).

### Hot-reload helper

`scripts/dev/HotSwapPush.java` plus `scripts/dev/dev-runner.cjs` enable
the `reload` ability surfaced by `justsearch_dev_reload`. It uses JDWP
on port 5005 (when the dev stack was started with `hotReload: true`)
to push method-body bytecode changes into the running JVM in 2-3s.

---

## 8. Worktrees & parallel sessions

- Main checkout: `F:\JustSearch` on branch `main`. **Never** switched.
- Worktrees: `.claude/worktrees/<name>/` on branch `worktree-<name>`.
- Active dirs visible right now: `381-model-distribution`,
  `389-streaming-heartbeat`, `406-lifecycle-refactor`,
  `410-ingestion-outcomes`, `411-workflow-signal-governance`, `412`,
  `413-embedding-observability`, `414-ort-session-observability`,
  `415-session-observability`, `417`. Up to 3-4 sessions run
  concurrently.
- `EnterWorktree` and `ExitWorktree` are deferred tools (load via
  `ToolSearch`).
- Subagents can run with `isolation: "worktree"` for parallel
  work; cleanup is automatic when no changes were made.
- `bash-guard.mjs` hard-distinguishes main vs. worktree by checking
  whether `.git` is a directory or a file — git creates a `.git` file
  pointer in worktrees, which is the cleanest test that doesn't shell
  out.
- After compaction, always re-verify `pwd` + `git branch --show-current`
  (see #27881 — `EnterWorktree` can create nested worktrees if CWD
  drifts after compact).

---

## 9. Knowledge cutoffs and model identity

- Model invoked: **Claude Opus 4.7**, 1M-token context
  (`claude-opus-4-7[1m]`). Knowledge cutoff: January 2026.
- Today is 2026-04-27 — about 4 months past cutoff. Anything dated
  Feb-Apr 2026 (incl. project-relevant releases like Claude Code
  v2.1.49 native worktree support, Code Kit v5.2 Opus 4.7 tuning,
  hook event additions like `TaskCompleted`/`TeammateIdle`) postdates
  training and must be verified via `WebSearch`/`WebFetch` before
  being treated as authoritative.
- Codebase-internal claims should be verified against
  `docs/explanation/` and `docs/reference/`, not against tempdocs
  (per `docs/tempdocs/README.md`).

---

## 10. Plugins, output styles, status line

- Project setting tries to enable `jdtls-lsp@claude-plugins-official`
  and `typescript-lsp@claude-plugins-official`. Local user settings
  override both to `false`. Net: **no plugins active.**
- No `--output-style` configured.
- No status line configured (the `statusline-setup` subagent exists
  for the user to invoke).

Plugin precedence rule (per platform docs): managed > local >
project > user; arrays for `enabledPlugins` are merged across scopes.
Marketplace plugins force-enabled by managed settings cannot be
overridden. None are force-enabled here.

---

## 11. Auto-included context surfaces

What lands in the agent's context **without an explicit tool call**:

1. **System prompt** — Claude Code's harness instructions (tone, tool
   policy, etc.).
2. **CLAUDE.md** — loaded fully (~20KB). The project bills it as the
   "hard invariants" doc.
3. **`.claude/rules/*.md`** — concatenated and loaded into the system
   prompt. Today: `agent-lessons.md`, `branch-safety.md`,
   `common-workflows.md`, `context-efficiency.md`,
   `deprecated-modules.md`, `hooks-reference.md`. (Also a transient
   `compaction-state.md` written by `compact-restore.mjs` immediately
   after compaction.)
4. **Available skills list** — names + frontmatter description, not
   full body. Bodies load only when the skill is invoked or the
   description triggers (per Claude Code's skill-resolution heuristic).
5. **Deferred tool list** — names only; schemas load via `ToolSearch`.
6. **`additionalContext` from `SubagentStart`** — only in subagents;
   from `subagent-guide.mjs`.
7. **Git status snapshot** — taken once at session start; may be stale
   if the session is long-running.

Things that are **not** auto-included: `docs/llms.txt`, individual
canonical docs, `AGENTS.md` (which only redirects to CLAUDE.md),
`docs/observations.md` (the inbox for noticed-but-out-of-scope
issues).

---

## 12. Quick-reference matrix: when does X fire?

| Trigger | Effect |
|---|---|
| Edit a file in `docs/{explanation,reference,how-to,decisions}/*.md` | `docs-regen-hint.mjs` reminds to re-run `llmstxt-generate` + `skills-sync` |
| Edit a file in `SSOT/catalogs/` or classpath copy | `ssot-hint.mjs` reminds about dual-copy sync |
| Edit a `build.gradle.kts` | `lockfile-hint.mjs` reminds to regenerate lockfiles |
| Edit a `modules/ui-web/src/**/*.{ts,tsx}` | `ui-shot-hint.mjs` lists affected ui-shot steps |
| Read same file unbounded 10× | `intervene.mjs` blocks further unbounded reads on it |
| Read a >8KB file with no offset/limit | `intervene.mjs` auto-injects `limit: 200` |
| Same tool+args 3× in a row | `repeat-guard.mjs` blocks (excludes Gradle) |
| `gradlew` fails 3× consecutively | `build-counter.mjs` blocks once with advisory; next run allowed |
| `git push --force` anywhere | `bash-guard.mjs` blocks |
| `git checkout/switch/reset --hard/clean -f/restore .` in main worktree | `bash-guard.mjs` blocks |
| `sleep ≥1` in any Bash command | `bash-guard.mjs` blocks; suggests jseval |
| Bare `cat`/`head`/`tail`/`grep`/`rg` outside a pipe | `bash-guard.mjs` redirects to Read/Grep |
| Any `mcp__justsearch-dev__*` call | `mcp-session-inject.mjs` adds `sessionId` to input |
| Subagent spawn | `subagent-guide.mjs` injects codebase hints (no CLAUDE.md visibility otherwise) |
| `PreCompact` | `compact-save.mjs` snapshots state, resets read counters |
| `SessionStart` after compact | `compact-restore.mjs` writes `.claude/rules/compaction-state.md` |
| `SessionEnd` | `auto-evaluate.mjs` runs Haiku judge, writes `outcomes.ndjson` |

---

## 13. Open questions / latent items

These are factors observed during the survey worth noting but not
worth fixing speculatively:

1. **`auto-evaluate.mjs` parses input from `process.argv[2]`** rather
   than stdin like every other hook. With the current settings.json
   wiring (`type: command`, `async: true`), stdin is the documented
   protocol; `argv[2]` will receive an empty string in most invocations.
   Worth verifying whether the SessionEnd async-hook path actually
   passes JSON via argv (some Claude Code versions do for async hooks,
   but this isn't documented). If stdin is correct, the script is a
   silent no-op.
2. **`.agents/skills/` mirror** — verbatim duplicate of
   `.claude/skills/`. If left in place, drifts as either side updates.
   Either symlink, generate from one source, or delete the mirror.
3. **`mcp__ide__*` permission allowlist with no IDE MCP server** —
   noise-level; harmless.
4. **`autoMemoryEnabled: false` but `compact-save.mjs` still reads
   MEMORY.md** — current behavior is fine (file absent → empty
   summary), but if auto-memory is ever re-enabled, the path
   construction at `compact-save.mjs:35` should be retested
   (string-mangling of `repoRoot` with colons → hyphens is fragile on
   Windows).

These are observations, not action items. The substantive learnings
worth promoting from this tempdoc are §3-§8; the rest is environmental
backdrop.

---

## 14. Verification pass (2026-04-28)

§§ 1-13 above were sourced from reading config and hook code. This
section logs **runtime probes** of the same surface, plus inspection
of the user-scope state I missed on the first pass.

### 14.1 Hook-behavior probes

Driven through `node scripts/agent-analytics/hooks/<hook>.mjs` with
crafted JSON on stdin. All eight assertions match the source-derived
description.

| Hook | Case | Expected | Observed |
|---|---|---|---|
| `bash-guard` | `git push --force` | exit 2, stderr "Force push is blocked" | ✓ |
| `bash-guard` | `sleep 5` | exit 2, stderr "sleep ≥1s is blocked" | ✓ |
| `bash-guard` | `sleep 0.3` | exit 0 | ✓ |
| `bash-guard` | bare `cat foo.txt` | exit 2, stderr redirect to Read | ✓ |
| `bash-guard` | `cat foo.txt \| head` | exit 0 (chained) | ✓ |
| `bash-guard` | `git checkout other` (main worktree) | exit 2 | ✓ |
| `bash-guard` | `git status` | exit 0 | ✓ |
| `intervene` | Read CLAUDE.md (~20KB, no offset/limit) | stdout `updatedInput.limit=200` | ✓ |
| `intervene` | Read AGENTS.md (~40 bytes) | no output | ✓ |
| `repeat-guard` | 3rd identical Glob | exit 2, stderr "Blocked: 3 consecutive identical Glob calls" | ✓ |
| `build-counter` | gradlew, `consecutiveFailures=3, advisoryShown=false` | exit 2, advisory | ✓ |
| `build-counter` | gradlew, `advisoryShown=true` | exit 0 (one-shot pattern confirmed) | ✓ |
| `mcp-session-inject` | mcp__justsearch-dev__* call with parent session_id | stdout merges `sessionId` into input | ✓ |
| `ssot-hint` | Edit `SSOT/catalogs/fields.v1.json` | `additionalContext` re: dual-copy | ✓ |
| `docs-regen-hint` | Edit `docs/explanation/01-system-overview.md` | `additionalContext` re: regeneration | ✓ |
| `subagent-guide` | SubagentStart | `additionalContext` with codebase hints | ✓ |
| `export-session-env` | SessionStart | writes session-id to `tmp/agent-telemetry/current-session-id` | ✓ — **destructive**: probe with a fake sid overwrites the live file |

The bash-guard regex scans full chained commands too — running my
probe via Bash failed because the literal `git push --force` substring
inside the probe args triggered the guard. Practical: the guard fires
on substrings even inside quoted strings.

### 14.2 `auto-evaluate.mjs` confirmed silently broken

| Invocation | Behavior |
|---|---|
| `echo '{...}' \| node …auto-evaluate.mjs` | exit 0, no work (`argv[2]` undefined → `session_id` missing → early exit) |
| `node …auto-evaluate.mjs '{"session_id":"x"}'` | runs `analyze-session.mjs` then `evaluate-session.mjs` |
| `tmp/agent-telemetry/outcomes.ndjson` | **does not exist** |
| `tmp/agent-telemetry/sessions/` | **does not exist** |
| `tmp/agent-telemetry/events.ndjson` | exists, 6178 lines |

The platform passes hook input via stdin (per
[code.claude.com/docs/en/hooks](https://code.claude.com/docs/en/hooks));
this script reads `argv[2]`. **No session in this repo has ever been
LLM-judged.** Fix is one-line: read stdin like every other hook in
the directory.

### 14.3 Settings hierarchy was missing two layers

§2 covered project + local but not the user-scope files that *are*
shipping behavior into this session.

`~/.claude/settings.json` (user scope, observed):

```json
{
  "permissions": { "defaultMode": "bypassPermissions" },
  "enabledPlugins": {
    "claude-notifications-go@claude-notifications-go": true,
    "frontend-design@claude-plugins-official": true
  },
  "extraKnownMarketplaces": {
    "claude-code-warp": { "source": { "source": "github", "repo": "warpdotdev/claude-code-warp" } },
    "claude-notifications-go": { "source": { "source": "github", "repo": "777genius/claude-notifications-go" } }
  },
  "skipDangerousModePermissionPrompt": true,
  "awaySummaryEnabled": false,
  "autoUpdatesChannel": "latest"
}
```

`~/.claude.json` (global config, ~31KB, ~50 keys). Most relevant:

- `oauthAccount.{accountUuid, organizationUuid, workspaceRole, billingType}` — OAuth state.
- `mcpServers.comfyui-mcp-server` — a global MCP server, not active in this project.
- `projects."F:/JustSearch"` — per-project state including:
  - `hasTrustDialogAccepted: true`, `hasCompletedProjectOnboarding: true`
  - `hasClaudeMdExternalIncludesApproved: false` — meaning CLAUDE.md `@<path>` includes **are not loaded** for this project. The `.claude/rules/*.md` mechanism is independent and *does* load. CLAUDE.md does contain `See @.claude/rules/context-efficiency.md` syntax; that line is a textual reference, not an active include.
  - `lastCost: 85.89` USD, `lastDuration: 25,474,369ms` (~7h), `lastTotalCacheReadInputTokens: 133.3M`, `lastTotalCacheCreationInputTokens: 1.7M` — most reads come from prompt cache.
  - `lastModelUsage`: Opus 4.7 dominant ($84.05/session), Haiku 4.5 secondary ($1.84) used for subagent/web-search work.
- `skillUsage` — per-skill counters across all sessions. Top observed: `start` (26), `jseval` (8), `inference-runtime`/`dev-stack` (4 each), `ui-check` (3), `api-record`/`docs-maintenance`/`loop`/`search-quality` (2 each). Plugin skills `claude-notifications-go:settings`, `frontend-design:frontend-design` also show usage — confirming user-scope plugins are active.
- `numStartups: 90`, `lastReleaseNotesSeen: 2.1.119` — Claude Code v2.1.119 is current.

Net precedence in this session, with values that actually take effect:

1. Managed: none observed.
2. CLI flags: not inspected; assume defaults.
3. Local project (`.claude/settings.local.json`) — primary source.
4. Project (`.claude/settings.json`) — `enabledPlugins: {jdtls-lsp: true, typescript-lsp: true}` overridden to `false` by local.
5. User (`~/.claude/settings.json`) — `enabledPlugins: {claude-notifications-go, frontend-design}` not overridden anywhere → **active**.

### 14.4 Plugins are active — §10 was wrong

Earlier §10 said "no plugins active." Wrong. Verified state:

| Plugin | Scope | Path | Active? |
|---|---|---|---|
| `jdtls-lsp@claude-plugins-official` | project (`F:\JustSearch`) | `~/.claude/plugins/cache/claude-plugins-official/jdtls-lsp/1.0.0` | **disabled by local settings** |
| `typescript-lsp@claude-plugins-official` | project | `…/typescript-lsp/1.0.0` | **disabled by local settings** |
| `claude-notifications-go@claude-notifications-go` | user | `~/.claude/plugins/cache/claude-notifications-go/claude-notifications-go/1.38.0` | **active** |
| `frontend-design@claude-plugins-official` | user | `~/.claude/plugins/cache/claude-plugins-official/frontend-design/unknown` | **active** |

**`claude-notifications-go` registers its own hooks** that fire
alongside the project hooks (manifest at `…/1.38.0/hooks/hooks.json`):

- `PreToolUse` matcher `ExitPlanMode|AskUserQuestion`
- `Notification` matcher `permission_prompt`
- `Stop`, `SubagentStop`, `TeammateIdle`

All run `bin/hook-wrapper.sh handle-hook <Event>` with a 30s timeout.
This means every Stop event in this repo triggers two hooks — the
project's `dispatch.mjs` and the plugin's wrapper. Same for
`SubagentStop`. Order is plugin-implementation-defined.

**`frontend-design`** ships only a `skills/frontend-design/SKILL.md`,
which appears in `skillUsage` — gets surfaced as a user-invocable
skill alongside the 14 project skills.

### 14.5 User-scope state directories I missed

The `~/.claude/` tree has more than just `projects/` and
`settings.json`. Observed today:

| Path | Contents | Purpose |
|---|---|---|
| `~/.claude/projects/F--JustSearch/<sid>.jsonl` | per-session transcripts (this session is 779KB and growing) | what `evaluate-session.mjs` reads |
| `~/.claude/projects/F--JustSearch/<sid>/` | companion dirs for some sessions (auxiliary state) | unclear contents |
| `~/.claude/sessions/<sid>/` | one dir per session | Claude Code's native session metadata (separate from project's events.ndjson) |
| `~/.claude/tasks/<pid>.json` | task list per process — **this is where TaskCreate/TaskUpdate state lives** | |
| `~/.claude/plans/*.md` | saved plans from `EnterPlanMode` | named like `412-phase-b.md`, `golden-meandering-lighthouse.md` |
| `~/.claude/file-history/` | global file-edit history across sessions | unclear retention |
| `~/.claude/shell-snapshots/snapshot-bash-<ts>-<rand>.sh` | per-Bash-invocation env captures | |
| `~/.claude/telemetry/<sid>/` | Claude Code's own session telemetry | distinct from `tmp/agent-telemetry/` |
| `~/.claude/session-env/` | session-scoped env vars | |
| `~/.claude/history.jsonl` | global command/prompt history (~387KB) | |
| `~/.claude/.credentials.json` | OAuth tokens (471 bytes) | |
| `~/.claude/backups/` | backups | |
| `~/.claude/plugins/{cache,marketplaces,installed_plugins.json,known_marketplaces.json,...}` | plugin state | install records and marketplace registry |

The tasks state being in `~/.claude/tasks/<pid>.json` (not project-local)
explains why TaskList is per-process: across sessions, tasks are
isolated by PID even when in the same checkout.

### 14.6 Worktree CLAUDE.md files differ — by design

Verified by diff: each `.claude/worktrees/<name>/CLAUDE.md` is the
copy committed on its branch, *not* a symlink to main. The current
worktrees differ from main by 1+ line each (e.g., `412/CLAUDE.md` is
missing the `UI explorations` row). A worktree agent reads its
branch's CLAUDE.md, not main's — and **this is intentional, not a
defect**: symlinking the worktree CLAUDE.md to main would mean
`git worktree remove` deletes the main copy too, since the symlink
target lives in the main checkout. The "each branch owns its own
policy" model is correct; drift is the natural cost. Worktrees catch
up via merge/rebase like any other code.

### 14.7 Repo-root state directories — characterized

§13 left these uncharacterized:

| Dir | Contents | Likely purpose |
|---|---|---|
| `.tools/buf/v1.62.1/buf` | pinned buf binary | protobuf compile pipeline |
| `.tmp/` | `dev-all.log`, `llama_disconnect_test_stream.{out,err}` | leftover dev-stack stdio captures from older runs (Dec 2025) |
| `.dev-data/` | `app.lock`, `entity-clusters.db*`, `jobs.db*`, `logs/`, `runtime/`, `telemetry/`, `worker_signal.lock`, `ui-screenshots/` | **Worker process state** when dev stack is running |
| `.tmp-fixture-gen/indexerworker/` | empty | leftover scaffolding from a fixture-generation step |
| `.dev-data` vs `modules/ui-web/.dev-data` | both exist | the `dev-stack` skill says the dev runner uses `modules/ui-web/.dev-data`; root-level `.dev-data` is from older config |
| `agent-review/installer/` | review notes | non-code artifacts from a tempdoc/review |
| `dist/` | empty | release output |
| `io/gradle/`, `net/ltgt/` | empty | **misplaced source dirs** — likely created by Gradle at the wrong CWD; safe to delete (would warrant an observation entry) |
| `reports/`, `build/`, `build-logic/` | Gradle output / build logic | |
| `.claude/scratch/` | empty | |

`.dev-data/` at repo root and `modules/ui-web/.dev-data/` are
different — the active one (per the dev-stack skill) is the one
inside `modules/ui-web/`. Root-level `.dev-data` may be stale.

### 14.8 Updated risk model

`bypassPermissions` is set both in user (`~/.claude/settings.json`)
and project local. With `skipDangerousModePermissionPrompt: true` at
user scope, no startup confirmation appears. Things this still does
not bypass:

- Permission rule `deny` lists (none configured).
- Managed-policy denies (none observed; would override).
- Trust-dialog gate on first project entry — already accepted.
- `hasClaudeMdExternalIncludesApproved` — `false`, so any future
  `@<path>` includes added to CLAUDE.md will prompt. The text-style
  `See @<path>` references aren't includes; not affected.
- MCP server enablement — gated separately by `enableAllProjectMcpServers`
  + `enabledMcpjsonServers`.

### 14.9 Corrections to earlier sections

| § | Original claim | Correct |
|---|---|---|
| §10 | "no plugins active" | claude-notifications-go and frontend-design both active at user scope |
| §11 | "CLAUDE.md … loaded fully" | True for the worktree's **own** CLAUDE.md; in worktrees this can drift from main |
| §11 | `compaction-state.md` is "transient" | Confirmed: `compact-restore.mjs` deletes it on non-compact session starts |
| §13 item 1 | auto-evaluate "if stdin is correct, the script is a silent no-op" | **Confirmed**: outcomes.ndjson and sessions/ do not exist; no LLM judging has ever happened in this repo |
| §7 | transcript path "(format unknown)" | `~/.claude/projects/F--JustSearch/<sid>.jsonl`, with companion `<sid>/` directory for some sessions |

### 14.10 Constraints affirmed by maintainer (2026-04-28)

Three items the critical-analysis pass flagged are **explicitly out of
scope** for change — by maintainer decision, not oversight. Future
agents should treat these as fixed parameters of the environment, not
defects:

1. **`bypassPermissions` is intentional and tested.** The maintainer
   has run sessions under it long enough to validate the safety
   model: hooks (`bash-guard.mjs` in particular) are the entire
   safety perimeter, and that's the desired tradeoff. Critiques along
   the lines of "this makes the hooks load-bearing" are correct but
   not actionable — the design choice is to make them load-bearing.
2. **CI is manual-only and stays that way.** Per ADR-0026 plus the
   2026-04-26 amendment. Self-hosted-runner availability constrains
   the design; agents land code on main with local-gate discipline
   only. Critiques about "regressions reach main silently" are correct
   *as a property* but the property is accepted.
3. **Worktree CLAUDE.md drift is by design — do not symlink.** Symlinking the
   worktree CLAUDE.md to the main checkout's copy would mean `git
   worktree remove` deletes the main copy too (the symlink target lives
   in the main checkout's working tree, not as a git-tracked file). The
   correct mental model is "each branch owns its own policy, same as
   its code." Worktrees that lag main on policy will catch up via merge
   or rebase, not by an out-of-band sync.
4. **Auto mode is declined.** The maintainer reviewed the auto-mode
   classifier alternative in §14.23.4 and chose to stay on
   `bypassPermissions`. The "auto vs bypassPermissions" trade-off is
   settled; future agents should not re-propose auto mode unless the
   maintainer explicitly reopens the question. The project's hook layer
   is the accepted safety perimeter.
5. **WSL / Linux subsystem will not be installed.** Windows-native
   workflows only. Recommendations that depend on WSL — the Linux
   sandbox, `wslInheritsWindowsSettings`, Linux-only seccomp guards,
   etc. — are out of scope. Native Windows + Git Bash + PowerShell are
   the supported execution environment.
6. **Claude Code version pinning is declined.** Pin → forget → drift.
   The maintainer prefers running latest and accepting the regression
   risk. The v2.1.119 known regressions (per the survival checklist)
   are noted as awareness, not as an action item.

These are not "Tier-N annoyances" — they are explicit design choices.
The §15 critical analysis below has been pruned accordingly.

### 14.12 External-consensus pass (2026-04-28)

Web research against the Apr 2026 Claude Code surface (v2.1.119
shipping, latest changelog) corrects several Tier-1/Tier-2 critiques
and confirms others. Four critiques flipped or downgraded.

#### 14.12.1 Subagents *do* see CLAUDE.md — partial reversal of Tier-1 #1

[Anthropic sub-agents docs](https://code.claude.com/docs/en/sub-agents)
and the
[Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts)
prompt-leak repo both confirm:

> "The subagent's system prompt replaces the default Claude Code
> system prompt entirely, and CLAUDE.md files and project memory
> still load through the normal message flow."

Project memory (MEMORY.md if enabled) also propagates: subagent
prompts include the first 200 lines.

What still doesn't propagate:

- **Hooks** — confirmed (#237, #21460). Bash-guard etc. don't fire in
  subagents. So a subagent *can* run `git reset --hard` in the main
  worktree. That part of the original critique stands.
- **`.claude/rules/*.md`** — undocumented. The platform docs mention
  CLAUDE.md and MEMORY.md explicitly; they don't mention rules files.
  Worth a probe in a future session (spawn a subagent and ask it to
  echo what it sees). Until verified, assume rules don't propagate.

Net (as of this section's writing): the "subagents are blind to project
rules" claim was overblown. They see CLAUDE.md, maybe see `.claude/rules/`,
definitely don't get hooks. The `subagent-guide.mjs` 5-line injection
mostly duplicates CLAUDE.md content.

**Update — see §14.16.1.** This conclusion was reversed by direct probe.
The third-party docs cited above were wrong: subagents do NOT see
CLAUDE.md, MEMORY.md, or `.claude/rules/`. The `subagent-guide.mjs`
injection is the *only* project-aware context. Don't trim it; expand it
(which §14.19 did).

#### 14.12.2 Compaction preserves CLAUDE.md — partial reversal of §13

[How Claude Code works](https://code.claude.com/docs/en/how-claude-code-works)
and
[okhlopkov.com/claude-code-compaction-explained](https://okhlopkov.com/claude-code-compaction-explained/)
confirm:

> "CLAUDE.md loads as part of the system prompt at the start of every
> session. Compaction doesn't touch it — it exists outside the
> conversation history. It's the only place that's guaranteed to
> survive any compression."
> "CLAUDE.md files are re-read from disk after every compaction."

So the project's hint-hook pattern (`docs-regen-hint`, `ssot-hint`,
`lockfile-hint`, `ui-shot-hint`) emits `additionalContext` messages
that *do* get summarized away — but the underlying rules survive in
CLAUDE.md and skills. The hints duplicate guidance that's already
guaranteed-persistent. The duplication is the cost; the rule loss is
not (the rule is in CLAUDE.md).

There's a [`compactPrompt` setting](https://blog.vincentqiao.com/en/posts/claude-code-compact/)
in `~/.claude/settings.json` that overrides the default compaction
prompt — e.g., `"Preserve ALL rules from CLAUDE.md verbatim"`. The
project doesn't use it. Adding it is essentially free.

There's also a "Compact Instructions" CLAUDE.md section convention
that auto-applies to both `/compact` and auto-compaction. Not used
either.

#### 14.12.3 Read silent truncation is a known platform problem

The project's `intervene.mjs` 200-line silent injection sits *on top
of* platform-level silent truncation that's already an open issue
class. Multiple Anthropic issues are open on this exact failure mode:

- [#28783](https://github.com/anthropics/claude-code/issues/28783) — "Read tool truncation causes agents to silently lose guardrails from instruction files"
- [#14888](https://github.com/anthropics/claude-code/issues/14888) — file read token limit not dynamic by model capability
- [#15687](https://github.com/anthropics/claude-code/issues/15687) — 25k-token limit too conservative
- [#20223](https://github.com/anthropics/claude-code/issues/20223) — line-number formatting adds 70% token overhead
- [#22699](https://github.com/anthropics/claude-code/issues/22699) — size-aware file reading / pre-flight checks
- [#40357](https://github.com/anthropics/claude-code/issues/40357) — Read tool size limit configurability

The platform itself silently truncates Read at:
- 2000 chars per line
- 2000 lines (undocumented; #6910)
- 25k tokens (varies by model/tier; #15687)

Project adds a 4th silent cap (200 lines >8KB files). All four are
silent-by-default. **The recommended fix in 2026 is the
`hookSpecificOutput.additionalContext` channel:** when injecting
`updatedInput`, also emit `additionalContext` saying "Read truncated
to N lines; total file is M lines" so the agent has visibility. Per
[v2.1.110 docs](https://code.claude.com/docs/en/hooks),
`additionalContext` from PreToolUse is now preserved even when the
tool fails — so this is the canonical channel. Project's `intervene.mjs`
emits only `permissionDecision: "allow"` + `updatedInput`; adding the
context line is a one-field change.

Note v2.1.119 also added `_meta["anthropic/maxResultSizeChars"]` for
MCP tools (up to 500K) — irrelevant to Read but useful for any future
project MCP that returns large blobs.

#### 14.12.4 Bash-guard regex naivety is a known anti-pattern

[Anthropic's official `bash_command_validator_example.py`](https://github.com/anthropics/claude-code/blob/main/examples/hooks/bash_command_validator_example.py)
uses tree-sitter for tokenization specifically to avoid the
parser-differential vulnerabilities that simple regex creates. From
the
[zread.ai bash security analysis](https://zread.ai/instructkr/claude-code/27-bash-security-and-sandbox):

> "Backslash-escaped operators create a parser-differential vulnerability
> where the internal shell-quote library and bash disagree on
> tokenization: `splitCommand` normalizes `\;` to a bare `;`, causing
> downstream re-parsers to see two commands where bash sees one."

Project's `bash-guard.mjs` uses `\bgit\s+push\b[^"']*(?:--force\b|-f\b)`
patterns. Concrete vulnerabilities by construction:

- A command like `echo "git push --force"` triggers the guard (false
  positive — confirmed during my probe).
- A command like `git push\n--force` would be missed (false negative;
  newline outside `\s` charclass).
- `g\\it push --force` likely passes (escaped char in middle of command).
- Multiline heredocs are not parsed; the regex sees the whole heredoc
  as one string.

For a hook that's load-bearing under `bypassPermissions`, regex is
the wrong tool. The Anthropic example imports `tree_sitter_bash`. The
project could either adopt that pattern or accept that bash-guard is
a soft guard, not a hard one.

#### 14.12.5 Hook overhead is small but accumulates

[claudefa.st hooks guide](https://claudefa.st/blog/tools/hooks/hooks-guide)
benchmarks:

| Hook type | Per-event cost |
|---|---|
| Command (Node script) | <5ms |
| Prompt (LLM call) | 300-2000ms |
| Agent (subagent) | 2-10s |

The project ships 9 PreToolUse hook entries firing on most calls.
Estimate: ~30-50ms cumulative overhead per tool call (Node startup
dominates). For a 1000-tool-call session, that's ~30-50s of pure hook
overhead. Acceptable; not a productivity issue.

`claude-notifications-go` adds another wrapper-shell + Go-binary
chain on `Stop`/`SubagentStop`/`Notification`/`TeammateIdle`. Windows
process-spawn is ~50-150ms. For a session with 50 subagent dispatches
that's ~5s extra. Also acceptable.

#### 14.12.6 Hooks dedupe by command string — confirmed

Per [code.claude.com/docs/en/hooks](https://code.claude.com/docs/en/hooks):

> "All matching hooks run in parallel, and identical handlers are
> deduplicated automatically — command hooks are deduplicated by
> command string."

`dispatch.mjs` and `claude-notifications-go/bin/hook-wrapper.sh` are
different commands → both fire on every `Stop`. By design, not a bug.
The earlier "two hook chains running on Stop — possible race" concern
is real (both write logs) but the dedup mechanism doesn't help here.

#### 14.12.7 Deferred tool first-call latency is a known platform issue

Per
[#33073](https://github.com/anthropics/claude-code/issues/33073),
[#44536](https://github.com/anthropics/claude-code/issues/44536),
[#27208](https://github.com/anthropics/claude-code/issues/27208),
[#31002](https://github.com/anthropics/claude-code/issues/31002):

- All built-in system tools became deferred behind `ToolSearch` in
  v2.1.69 (Jan 14, 2026, undocumented at the time).
- Cuts system-tool prompt overhead from ~14-16k tokens to ~968 tokens
  (85% reduction).
- First-call latency: deferred tools require a ToolSearch round-trip
  before invocation. Anthropic has open requests for warm-pool /
  hierarchical loading.

In this session, the always-loaded set (Bash, Edit, Read, etc.) is
larger than the v2.1.69 default — likely because the project's
permission allowlist + active tool history "warms" them. Not a
project decision; nothing to fix project-side.

### 14.13 Removal candidates (per maintainer's prompt) — partially superseded

> **Note:** Two items below were reversed during the probe pass (§14.16)
> and the implementation pass (§14.19). The "Trim `subagent-guide.mjs`"
> recommendation is **wrong** — subagents do not see CLAUDE.md, so the
> guide is not redundant. The "delete root `.dev-data/`" recommendation
> is also **wrong** — 4 production scripts reference it. See §14.17 for
> the corrected punch list and §14.19 for what shipped.

Items that are net-negative or net-zero and candidates for *deletion*
rather than modification. None require action; surfaced for triage.

#### Strong removal candidates (no behavioral loss)

| Item | Why removable | Cost of keeping |
|---|---|---|
| `auto-evaluate.mjs` | Confirmed broken (argv vs stdin) since unknown date. No `outcomes.ndjson` has ever been written. The downstream consumers (`score-session.mjs`, `correlate-signals.mjs`) operate on missing data. | Telemetry pipeline appears wired but isn't. Misleading. |
| `.agents/skills/` mirror | Claude Code reads `.claude/skills/`. `.agents/` is unread by the runtime. | 14 verbatim duplicate files that drift silently. |
| `mcp__ide__*` permission allowlist entry | No IDE MCP server defined; nothing flows through. | Dead allowlist line. |
| Empty `io/gradle/`, `net/ltgt/` at repo root | Likely created accidentally by Gradle running at wrong CWD. Java reverse-DNS package skeletons with nothing inside. | Repo cruft; confuses code search and module discovery. |
| `.tmp/` Dec 2025 stale logs (`dev-all.log`, `llama_disconnect_test_stream.{out,err}`) | 4 months old. | Cosmetic. |
| `.claude/scratch/research uncertainties.md` | Duplicate of `.claude/commands/research uncertainties.md` (legacy copy). | Redundant. |
| `subagent-guide.mjs` line "Codebase context: (1) Large files..." | Duplicates CLAUDE.md content that subagents already see. | Wastes context budget on repeat info. The unique value (session-id forwarding) is one line; the rest is redundant. |

#### Worth-evaluating removal candidates

| Item | Why removal might be right | Why keeping might be right |
|---|---|---|
| All four hint hooks (`docs-regen-hint`, `ssot-hint`, `lockfile-hint`, `ui-shot-hint`) | Each fires on every matching edit; the rule is already in CLAUDE.md / skills, which compaction preserves; the hint's `additionalContext` gets summarized away anyway. | They surface the rule *at the moment of edit*, not just at session start. For agents that didn't read CLAUDE.md carefully, the hint is the trigger. |
| `repeat-guard.mjs` | Modern Claude Opus 4.7 rarely loops on identical calls; the guard is most useful for older models. Fingerprint is shallow (100-char prefix for Edit, 200 for Bash). | Catches rare degenerate cases; cheap (<5ms). |
| `build-counter.mjs` | Same logic as repeat-guard for Gradle specifically. Could merge with repeat-guard or remove if the Gradle-failure spiral is rare in practice. | The one-shot advisory pattern is well-tuned and has prevented documented spirals. |
| `intervene.mjs` hot-file cap (10 unbounded reads) | Blocks legitimate work on large files. | Catches genuine context-thrashing. |
| `intervene.mjs` Read auto-`limit:200` injection | Compounds platform silent-truncation. Better fix: emit `additionalContext` with the limit so the agent sees it. | Real context-budget benefit. Removing it loses ~30-40% of context efficiency on large-file reads. |

#### Worth-keeping items the critical-analysis flagged but research vindicated

| Item | Reason to keep |
|---|---|
| `bash-guard.mjs` | Despite regex naivety (#14.12.4), it has zero false-negatives for the *common* destructive cases. The user has accepted `bypassPermissions` (§14.10 #1) so the guard is the perimeter. **Recommended improvement: anchor patterns + tree-sitter parser per Anthropic example, but don't remove.** |
| `mcp-session-inject.mjs` | Necessary correctness — the dev-runner's session attribution depends on it. |
| `compact-save.mjs` / `compact-restore.mjs` | The rules-file channel is still the right way to preserve session-specific state across compaction. CLAUDE.md handles rules; this handles work-in-progress. |
| `dispatch.mjs` + `events.ndjson` | Useful raw data even if the LLM-judge consumer is broken. Keep the producer; fix or remove the consumer. |

### 14.14 Recommended remove-don't-fix list (one-liner per item)

If the maintainer wants a single concrete punch list out of this
analysis:

1. **Delete** `auto-evaluate.mjs` and remove its hook registration. The pipeline it feeds is dead. 1 file + 1 settings entry.
2. **Delete** `.agents/skills/`. Unread mirror. ~14 files.
3. **Delete** empty `io/gradle/` and `net/ltgt/`. Repo cruft.
4. **Delete** `.claude/scratch/research uncertainties.md`. Duplicate of the active command.
5. **Delete** `mcp__ide__*` from the permissions allowlist in `.claude/settings.local.json`. Dead entry.
6. **Trim** `subagent-guide.mjs` to its one unique line (session-id forwarding); drop the codebase-context list that's already in CLAUDE.md.
7. **Clean** `.tmp/*.log` from December 2025.

That's roughly 30 lines of code/data deletion across 7 places, no
functional regression.

### 14.16 Pre-implementation probe pass (2026-04-28)

Following the meta-recommendation from §15, ran direct probes on every
low-confidence claim before committing to implementation. Several
recommendations flipped or strengthened.

#### 14.16.1 Subagent CLAUDE.md propagation: REVERSED — does NOT propagate

Two independent primary-source confirmations:

**Live probe.** Spawned an Explore subagent with an introspection
prompt asking it to quote CLAUDE.md, `.claude/rules/*.md`, and the
"Hard Invariants" string. Subagent reply (verbatim):

> "Of the files you asked about (CLAUDE.md, MEMORY.md, and the five
> `.claude/rules/*.md` files), **none are visible in my initial
> context**. What IS visible: system-reminder tags about codebase
> context (large files to handle with offsets, grep strategy, docs
> locations, platform details), user email, current date, and the
> working directory path."

The two visible system-reminders were:
1. `subagent-guide.mjs` SubagentStart additionalContext (the 5-line
   codebase hint).
2. The user-email + currentDate context the platform always injects.

**Anthropic's own subagent prompts** (via [Piebald-AI prompt-leak
repo](https://github.com/Piebald-AI/claude-code-system-prompts), which
extracts the binary's actual prompts per Claude Code version):

- [`agent-prompt-explore.md`](https://raw.githubusercontent.com/Piebald-AI/claude-code-system-prompts/main/system-prompts/agent-prompt-explore.md) — 575 tokens, **zero references** to CLAUDE.md, project memory, `.claude/rules`, hooks, additionalContext, or parent-session inheritance. The prompt is purely about the read-only exploration role + tool-name placeholders.
- [`agent-prompt-general-purpose.md`](https://raw.githubusercontent.com/Piebald-AI/claude-code-system-prompts/main/system-prompts/agent-prompt-general-purpose.md) — Even shorter. Same finding: no parent-context inheritance referenced. The prompt covers role, tool guidelines, file-creation policy. Nothing about CLAUDE.md.

There IS a [`agent-prompt-determine-which-memory-files-to-attach.md`](https://raw.githubusercontent.com/Piebald-AI/claude-code-system-prompts/main/system-prompts/agent-prompt-determine-which-memory-files-to-attach.md)
in the repo — Anthropic ships a *meta-subagent* that decides which
memory files to attach to other subagents on a query-by-query basis.
Cap: 5 files per query. **But that mechanism is for MEMORY.md-style
"memory files," not CLAUDE.md.** CLAUDE.md is loaded by the parent's
harness only.

**The third-party docs I cited earlier (techtaek, ofox.ai, etc.)
claiming "CLAUDE.md and project memory still load through the normal
message flow even when using subagents" are wrong, or they're
referring to a different kind of "subagent" (Slash-command-spawned?
Skill-spawned context forks?) than the `Agent` tool produces.**

#### 14.16.2 Implications for §14.13 / §14.14

This **reverses** Tier-1 #5 and **strengthens** Tier-3 #13. Updated
guidance:

| Item | Original | Updated |
|---|---|---|
| **§14.13 — `subagent-guide.mjs` line "Codebase context: (1) Large files..." as removal candidate** | "Duplicates CLAUDE.md content that subagents already see" | **REVERSED.** Subagents don't see CLAUDE.md. The 5-line guide is the *only* project-aware context they get. **Don't trim — consider expanding** with the project's Hard Invariants (~500 tokens) and a pointer to logging observations into `docs/observations.md`. |
| **§14.14 punch list item 6 — "Trim `subagent-guide.mjs`"** | "Drop the codebase-context list" | **Replace with: expand `subagent-guide.mjs` to include Hard Invariants + observations-inbox protocol + the "subagent has no hooks" warning.** That's ~400-600 tokens of additionalContext, well under the 10K limit. |
| **§15 #13 — "Document subagent risk profile"** | "Subagents lack hooks" | **Strengthened.** Subagents lack hooks AND CLAUDE.md AND `.claude/rules/`. The only safety perimeter the parent has does not extend to them. CLAUDE.md should warn the parent agent explicitly: *"do not delegate destructive operations or large-file work to subagents — they have none of the guards the parent does."* |
| **§14.12.1 conclusion** | "subagents are blind to project rules claim was overblown" | **Original claim was correct.** Subagents are blind to project rules. The "overblown" assessment was wrong. |

#### 14.16.3 Other probe results

**`auto-evaluate.mjs` git history:** Single commit (`9c5f0069c —
feat(analytics): automated session type classification (tempdoc 276)`).
Created with the bug, never modified. So the argv-vs-stdin choice
isn't a version-mismatch artifact — it was buggy from day one. Fix is
straightforward; no risk of breaking a "works on older versions" path.

**`io/justsearch/`, `net/ltgt/` empty dirs:** Confirmed empty
(`ls -la` shows only `.` and `..`). Zero git history (no commits ever
touched them — `git log --all -- io/ net/` empty). Not in `.gitignore`.
Untracked, empty, harmless. Safe to delete; no PR change needed since
they're not committed.

**Root `.dev-data/`:** Gitignored (per `.gitignore`: `.dev-data/`)
AND actively referenced by 4 production scripts:
- `scripts/dev/dev-runner.cjs`
- `modules/ui/src/main/java/io/justsearch/ui/ai/runtime/RuntimeActivationService.java`
- `scripts/ci/dag-runner-agent-battery.mjs`
- `modules/ui-web/scripts/dev-all.cjs`

**Do NOT delete root `.dev-data/`.** The earlier "may be stale"
guess was wrong. Both `.dev-data/` (root) and
`modules/ui-web/.dev-data/` exist by design depending on which entry
point is used.

**`agent-review/`, `.agents/`, `.tools/`, `.tmp/`:** All gitignored.
`agent-review/` and `.agents/` have zero git commits (never tracked).
**Removal is local-only, not a PR change.** No need for a tempdoc;
just `rm -rf` if desired.

**Stop-hook double-fire confirmed in transcript.** The `~/.claude/projects/F--JustSearch/<sid>.jsonl`
shows entries like:
```json
{"type":"system","subtype":"stop_hook_summary","hookCount":2,
 "hookInfos":[
   {"command":"node scripts/agent-analytics/hooks/dispatch.mjs"},
   {"command":"${CLAUDE_PLUGIN_ROOT}/bin/hook-wrapper.sh handle-hook Stop","durationMs":565}
 ],...}
```
~500-565ms per Stop. Two distinct commands → both run, no dedup
(confirms §14.12.6).

**Session version:** Transcript records `"version":"2.1.119"`,
`"gitBranch":"main"`. Confirms the user is on the v2.1.119 release
that has [known regressions](https://gist.github.com/yurukusa/a866b4cd2976486156a00c190c39cef6)
(auto-update break, silent model swap, resume-time crashes). Worth
considering pinning to v2.1.117 until v2.1.121+ stabilizes.

#### 14.16.4 Confidence delta (post-probe)

| Recommendation | Pre-probe confidence | Post-probe confidence | Direction |
|---|---|---|---|
| #1 — `intervene.mjs` truncation visibility | 80% / 65% | 75% / 75% | Marginally up; the channel mechanics are still partially unverified |
| #2 — Fix or delete `auto-evaluate.mjs` | 95% / 60% | 95% / 80% | Up — single commit history confirms day-one bug, fix is low-risk |
| #3 — `compactPrompt` setting | 70% | 70% | Unchanged; no probe ran |
| #4 — Compact Instructions section | 55% | 50% | Slightly down; primary-source `agent-prompt-conversation-summarization.md` not yet inspected |
| **#5 — Trim `subagent-guide.mjs`** | 75% / 50% | **0%** | **REVERSED.** Don't trim — expand. |
| #6 — Cleanup punch list (per row) | varied | varied | `.dev-data/`: 0% (don't delete). `io/`, `net/`: 95%. `.agents/`: confirmed gitignored, can delete locally. |
| #7 — Tree-sitter for bash-guard | 60% | 60% | Unchanged; needs Windows-binary probe |
| #8 — Probe `.claude/rules/` propagation | 95% | **DONE** | Confirmed: `.claude/rules/*.md` does NOT propagate to subagents either. |
| #9 — Triage observations inbox | 90% / 50% | 90% / 50% | Unchanged |
| #10 — Decide on hint hooks | 70% / 50% | 70% / 50% | Unchanged |
| **#13 — Document subagent risk** | 80% / 50% | **95%** | **Up significantly.** Now has primary-source backing. |

### 14.17 Updated punch list (post-probe)

Replace §14.14 with:

1. **Fix** `auto-evaluate.mjs` to read stdin (or delete it). Confidence 95%/80%. ~1 line either way.
2. **Add** `additionalContext` emission to `intervene.mjs` alongside the existing `updatedInput`. Confidence 75%. ~3 lines.
3. **Expand** (don't trim) `subagent-guide.mjs` to include Hard Invariants + observations-inbox protocol + "no hooks fire here" warning. Confidence 95%. ~30-50 lines added.
4. **Document subagent risk** in CLAUDE.md (subagents see neither hooks nor CLAUDE.md nor rules — all guards stop at the boundary). Confidence 95%. ~5 lines.
5. **Delete** empty `io/justsearch/`, `net/ltgt/`. Confidence 95%. Local-only.
6. **Delete** `.agents/skills/` mirror locally. Confidence 90%. Local-only (gitignored).
7. **Delete** `.claude/scratch/research uncertainties.md` if duplicate confirmed. Confidence 70%. Local-only.
8. **Remove** `mcp__ide__*` from `.claude/settings.local.json` allowlist. Confidence 80%. ~1 line.
9. **Add** `compactPrompt` to `~/.claude/settings.json` with concrete instructions. Confidence 70%. Free to try, low risk.
10. **Pin** Claude Code to v2.1.117 until v2.1.121+ stabilizes (per the survival checklist). Confidence 95% on the recommendation; user-discretion call.

**Removed from earlier punch list:**
- ~~Trim `subagent-guide.mjs`~~ — reversed, expand instead.
- ~~Delete root `.dev-data/`~~ — confirmed actively referenced.
- ~~Add Compact Instructions CLAUDE.md section~~ — pending more verification; not in canonical Anthropic docs.

### 14.19 Implementation pass (2026-04-28)

Punch list from §14.17 executed in this session. Outcomes:

| Item | Result |
|---|---|
| Delete `auto-evaluate.mjs` + remove SessionEnd hook entry | **DONE.** File removed; second SessionEnd hook block removed from `.claude/settings.local.json`. |
| Add `additionalContext` to `intervene.mjs` | **DONE.** Both the auto-`limit:200` injection and the hot-file cap block now annotate with file size / total lines. Probed: large file emits `additionalContext` with byte size; small file emits nothing; already-limited Read emits nothing; hot-file cap fires at the 11th unbounded read with line-count hint. |
| Expand `subagent-guide.mjs` | **DONE.** Grew from 6 lines (~360 chars) to a sectioned 2,744-char guide with Hard Invariants, Agent Discipline, Subagent-specific risk profile, Observations protocol, Tooling pointers, Reporting, Session attribution. Well under the 10K hook output cap. |
| Document subagent risk in CLAUDE.md | **DONE.** Added "Delegating to Subagents" subsection under "Agent Discipline" — covers no-bash-guard, no-repeat-guard/build-counter/Read-auto-limit, no-hint-hooks, the `isolation: "worktree"` branching gotcha (#50850), good vs risky subagent tasks. |
| Delete empty `io/`, `net/` | **DONE.** Both contained misplaced .class / .kt files (not just empty dirs as I initially claimed) — verified safe to delete: untracked, no git history, source content lives in `modules/` and Gradle's plugin cache. |
| Delete `.agents/skills/` mirror | **DONE.** Local-only; gitignored. |
| Verify `.claude/scratch/research uncertainties.md` duplicate | **N/A — recommendation invalid.** Re-inspection showed `.claude/scratch/` contains a `justsearch-releases` git clone (unrelated public-facing repo), not a duplicate of the commands file. My earlier "duplicate exists" claim was a misreading of my own probe output. Nothing to delete. |
| Remove `mcp__ide__*` (and `Skill(update-config)`) from `.claude/settings.local.json` allowlist | **DONE.** Both removed; `Skill(*)` already covers `Skill(update-config)`. |
| Add `compactPrompt` to `~/.claude/settings.json` | **NOT POSSIBLE.** The Claude Code settings schema in v2.1.119 (which the harness validates against on edit) **does not include a `compactPrompt` field.** The third-party 2026 references that claimed otherwise are wrong or were sourced from an unrelated tool. Schema-confirmed compaction-relevant fields: `autoCompactEnabled` (toggle), `autoCompactWindow` (size only). No way to override the compaction prompt via settings. **Recommendation withdrawn.** |

#### 14.19.1 New finding: `compactPrompt` does not exist

The Edit tool's settings-validation feature caught this immediately — Claude
Code rejects unknown fields on save with the full schema dumped in the
error. This is a useful platform feature for hook authors and worth
remembering: any settings-modification recommendation can be sanity-
checked by attempting it; a malformed key fails the validator with
the canonical schema in the error message.

The schema (v2.1.119) confirms what *is* available for compaction
behavior: `autoCompactEnabled` (boolean), `autoCompactWindow` (100k-1M).
Nothing for prompt customization.

If compaction-prompt control is genuinely needed, it would have to be
done via a `PreCompact` hook that intercepts and rewrites — but that's
substantially more work than a setting and not in the punch list.

The "Compact Instructions" CLAUDE.md section convention I cited from
third-party blogs (Tier 1 #4 in my recommendations) is also
unverified — it doesn't appear in the canonical schema, and the
Anthropic docs don't mention it. **Drop both #3 and #4 from the
recommendations.**

#### 14.19.2 Probe-vs-blogspot scorecard

The probe-driven methodology from §15 caught two wrong recommendations
that pure-research would have shipped:

1. **Trim subagent-guide.mjs (Tier 1 #5).** Reversed by §14.16.1 probe.
2. **Add compactPrompt to settings (Tier 1 #3).** Reversed by schema
   validation in §14.19.1.

Both were derived from third-party 2026 references (techtaek, ofox.ai,
blog.vincentqiao.com). Neither was confirmed by Anthropic-canonical
sources or by probe before being recommended. Methodology principle
re-confirmed: **third-party docs are rumor; primary sources or runtime
probes are the only reliable evidence.**

### 14.23 Extended investigation pass (2026-04-28)

After the implementation pass closed, ran a second-tier investigation
into surfaces the survey hadn't covered: built-in slash commands,
plan mode mechanics, auto mode vs bypassPermissions, hook `if`-filter,
CLAUDE.md `@`-import behavior, task persistence storage. Multiple
canonical-source confirmations corrected earlier guesses.

#### 14.23.1 CLAUDE.md is a user message, not system prompt

Per [Anthropic memory docs](https://code.claude.com/docs/en/memory):

> "CLAUDE.md content is delivered as a user message after the system
> prompt, not as part of the system prompt itself. Claude reads it
> and tries to follow it, but there's no guarantee of strict
> compliance, especially for vague or conflicting instructions."

Implications:

- CLAUDE.md competes with the actual user prompt for attention budget.
- The "200-line target per CLAUDE.md" recommendation is real. **This project's CLAUDE.md is ~291 lines** plus ~40KB of rules concatenated — well over the target. Splitting into path-scoped `.claude/rules/*.md` with `paths:` frontmatter would reduce per-session context cost.
- For instructions you want at the system-prompt level, use `--append-system-prompt` (per-invocation flag).

#### 14.23.2 The `@.claude/rules/context-efficiency.md` line in CLAUDE.md is dead text

Line 291 of CLAUDE.md reads: `See @.claude/rules/context-efficiency.md for agent guidance.` This **looks like** an `@`-import per the documented syntax. But:

- `@`-imports show an approval dialog the first time encountered.
- The user declined, setting `hasClaudeMdExternalIncludesApproved: false`.
- Result: the line is rendered as **literal text**. The model sees the text "@.claude/rules/context-efficiency.md" but no expansion happens.
- The rules file's content nonetheless loads, because `.claude/rules/*.md` files are auto-loaded by a separate mechanism (per the canonical docs).

So the rule content reaches the agent, but via two paths only one of which works (auto-load works; @-import is dead). **Recommendation: remove the `@` from line 291** to avoid confusion — make it just `See .claude/rules/context-efficiency.md` so the line accurately reflects what's happening. The content is already auto-loaded; the `@` only serves to mislead future readers (and agents who try to verify the include works).

#### 14.23.3 Hook `if`-filter is a real optimization the project doesn't use

Per [Anthropic hooks docs](https://code.claude.com/docs/en/hooks):

> "The hook only spawns if the tool call matches the pattern, or if a
> Bash command is too complex to parse… The `if` field is optional;
> without it, every handler in the matched group runs… **avoiding the
> process spawn overhead**."

The project's hooks all use only `matcher` (which dispatches by tool name) — never `if` (which narrows by tool input). Concrete optimization opportunities:

| Hook | Current | Proposed `if` | Savings |
|---|---|---|---|
| `build-counter.mjs` | matcher: `Bash`, fires on every Bash, exits early on non-Gradle | `if: "Bash(./gradlew*)"` | ~95% of Bash calls don't spawn the hook |
| `ssot-hint.mjs` | matcher: `Edit\|Write`, fires on every edit, exits early on non-SSOT | `if: "Edit(**/SSOT/catalogs/**)"` | ~99% of edits don't spawn the hook |
| `lockfile-hint.mjs` | matcher: `Edit`, fires on every edit | `if: "Edit(**/*build.gradle.kts)"` | ~99% don't spawn |
| `docs-regen-hint.mjs` | matcher: `Edit\|Write`, fires on every edit | `if: "Edit(docs/explanation/**\|docs/reference/**\|docs/how-to/**\|docs/decisions/**)"` | ~95% don't spawn |
| `ui-shot-hint.mjs` | matcher: `Edit\|Write`, fires on every edit | `if: "Edit(modules/ui-web/src/**)"` | ~90% don't spawn |

Per the docs, `if` patterns use the same syntax as permission rules. For Bash, the rule matches each subcommand after stripping `VAR=value` prefixes (so `if: "Bash(./gradlew*)"` matches `JAVA_OPTS=-Xmx2g ./gradlew build`).

**Estimated cumulative savings:** ~50 edits per session × 5 hooks × ~5ms Node spawn = ~1,250ms per session of pure hook overhead avoided, plus reduced `events.ndjson` spam. Concrete Tier-1 improvement that I missed entirely in the original survey. **One-line change per hook in `.claude/settings.local.json`.**

#### 14.23.4 Auto mode is the documented safer alternative to `bypassPermissions`

Per [Anthropic permission-modes docs](https://code.claude.com/docs/en/permission-modes) and the [Mar 2026 launch announcement](https://claude.com/blog/auto-mode):

- **`bypassPermissions`**: removes all checks; tool calls execute immediately.
- **`auto`**: classifier model reviews each tool call before execution. Blocks scope-escalation, untrusted-infrastructure access, and prompt-injection patterns. Allows everything else without prompting.

The user explicitly accepted `bypassPermissions` (§14.10). This isn't a recommendation to switch — it's a context update. The maintainer's accepted trade-off is "I trust my hooks; classifier overhead isn't worth it." That trade-off is legitimate. The `auto` mode option exists if the trade-off is ever reconsidered.

#### 14.23.5 Task persistence: `~/.claude/tasks/<sid>/<taskId>.json`

Earlier survey claimed tasks were `~/.claude/tasks/<pid>.json`. **Wrong.** Verified structure:

```
~/.claude/tasks/
├── 061c9afa-…/         # one dir per Claude Code session ID
│   ├── .lock           # session lock
│   ├── .highwatermark  # 2 bytes; max task ID seen
│   ├── 30.json         # one file per task
│   ├── 31.json
│   └── …
```

For my own session (`a0abc525-…`), tasks 30-34 are visible, matching the TaskCreate calls I made earlier in this session.

**`CLAUDE_CODE_TASK_LIST_ID`** env var lets you share a task list across sessions:

```bash
CLAUDE_CODE_TASK_LIST_ID=my-project claude
```

Tasks then go to `~/.claude/tasks/my-project/` instead of a per-session UUID dir. Useful for long-running multi-session work.

#### 14.23.6 Built-in `/btw` for context-free side questions

Newly relevant for agent workflows: `/btw <question>` asks Claude a question that:

- Has full visibility into the current conversation.
- Has **no tool access** (purely reasons from existing context).
- Is **ephemeral** — does not enter the conversation history.
- Answers in a dismissible overlay.

Per the docs: `/btw` is the inverse of a subagent. Subagent: full tools, empty context. `/btw`: full context, no tools.

Useful for the maintainer when they want a quick answer without polluting the parent context. Not directly an agent-affecting feature, but worth knowing.

#### 14.23.7 `InstructionsLoaded` hook event exists

Per [Anthropic hooks docs](https://code.claude.com/docs/en/hooks): `InstructionsLoaded` fires when CLAUDE.md / `.claude/rules/*.md` files are loaded. Anthropic recommends it for debugging path-specific rules:

> "Use the `InstructionsLoaded` hook to log exactly which instruction files are loaded, when they load, and why."

The project's `dispatch.mjs` doesn't subscribe to it. If anyone ever needs to debug "is rule X actually being loaded?" — wiring this hook is the canonical answer.

#### 14.23.8 Plan mode + Windows Shift+Tab regression

Per [Anthropic interactive-mode docs](https://code.claude.com/docs/en/interactive-mode) and [#17344](https://github.com/anthropics/claude-code/issues/17344):

- Plan mode activates via Shift+Tab twice OR `/plan` (v2.1.0+).
- **Windows regression in 2.1.3+:** Shift+Tab cycles only Edit → Auto-accept; plan mode missing. Use `Alt+M` or `/plan` to enter plan mode on Windows.
- Plan mode disallowed tools: `Agent`, `ExitPlanMode`, `Edit`, `Write`, `NotebookEdit`. Read-only exploration only.
- The `~/.claude/plans/` directory holds saved plans (e.g., `410-remaining-implementation.md`) when the agent calls `ExitPlanMode` to commit a plan.
- Verified existing plan in this project: `~/.claude/plans/410-remaining-implementation.md` is a real saved plan from past work.

#### 14.23.9 Other surfaces worth knowing about

| Surface | Quick note |
|---|---|
| `Ctrl+B` to background a Bash invocation | Output goes to a file, retrievable via Read. 5GB cap. `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1` to disable. |
| `Ctrl+T` to toggle the task list view | Shows up to 5 tasks in terminal status area. |
| `Esc+Esc` to rewind/summarize | Restore code/conversation to a previous point. |
| `Alt+P` (Win/Linux) to switch model mid-session | No prompt clear. |
| `Alt+T` to toggle extended thinking | Per-session toggle. |
| `Alt+O` to toggle fast mode | Opus 4.6 only; not relevant on 4.7. |
| `!` prefix in the input box | Bash mode — runs command directly, output joins context. Useful for quick `git status`, etc. |
| `@` prefix | File path autocomplete. |
| `Ctrl+O` | Toggle transcript viewer. |
| `Ctrl+X Ctrl+K` | Kill all background agents (twice within 3s). |
| `claudeMdExcludes` setting | Glob-skip CLAUDE.md files; useful in monorepos to skip other teams' files. Project doesn't need this currently. |
| `--append-system-prompt` flag | Inject text at the actual system-prompt level (not user message). Per-invocation only. |
| `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1` env var | When using `--add-dir`, also load CLAUDE.md from those dirs. |
| Auto memory at `~/.claude/projects/<project>/memory/MEMORY.md` | OFF in this project (`autoMemoryEnabled: false`). If enabled: first 200 lines / 25KB load each session, machine-local, shared across worktrees of same git repo. |

#### 14.23.10 Updated punch list (post-extended-investigation)

New Tier-1 priority items uncovered:

11. **Add `if`-filters to 5 hooks** in `.claude/settings.local.json` — `build-counter`, `ssot-hint`, `lockfile-hint`, `docs-regen-hint`, `ui-shot-hint`. ~5-10 lines of edits, ~95% reduction in spawn overhead for those hooks. **Confidence 90%** (documented feature, syntax verified against canonical docs).
12. **Remove the `@` from CLAUDE.md line 291** — it's dead text causing confusion. Change `See @.claude/rules/context-efficiency.md for agent guidance.` to `See .claude/rules/context-efficiency.md for agent guidance.` **Confidence 95%** (verified that approval flag is false, so the @ is non-functional).

Lower-priority opportunities surfaced:

13. **CLAUDE.md size is over the recommended 200 lines.** Splitting into path-scoped `.claude/rules/*.md` with `paths:` frontmatter would reduce per-session context cost. Half-day refactor; payoff is real but hard to measure without telemetry.
14. **`InstructionsLoaded` hook** could be wired to verify which rules are actually loading per session — debugging tool, not behavior change.

### 14.24 Recent Claude Code improvements not yet adopted (2026-04-28)

After §14.23 closed, ran a sweep of the v2.1.92 → v2.1.119 release range
([changelog](https://code.claude.com/docs/en/changelog) + 
[Week 15 digest](https://code.claude.com/docs/en/whats-new/2026-w15)) for
features the project hasn't picked up. Filtered for: Windows-applicable
(no WSL), maintainer-affirmed constraints respected (no auto mode, no
version pinning, no auto-CI). What's left is genuinely actionable.

#### 14.24.1 Tier-1 opportunities (concrete, low effort)

**A. Capture `duration_ms` from PostToolUse hooks (v2.1.119)**
PostToolUse and PostToolUseFailure hook inputs now include
`duration_ms` — tool execution time excluding permission prompts and
PreToolUse hooks. The project's `dispatch.mjs` builds a `post_tool_use`
event from `input.tool_response` but ignores `input.duration_ms`. One
line change in `dispatch.mjs` adds per-tool latency to events.ndjson.
Useful for spotting slow tools and detecting regressions. **Confidence
95%** — feature documented in changelog, schema field exists.

**B. Wire `InstructionsLoaded` hook**
[Anthropic's recommended debugging tool](https://code.claude.com/docs/en/hooks):
fires when CLAUDE.md / `.claude/rules/*.md` files are loaded (initial +
lazy). The project's `dispatch.mjs` could just dispatch this event
type — no new logic — and events.ndjson would record exactly which
rules each session sees. Most direct value: settles the "do worktree
CLAUDE.md files actually load as expected" question without further
probing. **Confidence 90%**.

**C. UserPromptSubmit `sessionTitle` (Week 15)**
UserPromptSubmit hooks can return `hookSpecificOutput.sessionTitle` to
auto-name sessions. The session-name field is already visible in
`~/.claude/sessions/<sid>.json` (e.g., `"name": "nativesessionhandle-observability"` —
that came from a different mechanism, but the hook channel is now
documented). The project's `dispatch.mjs` could derive a title from
the first prompt and set it. **Confidence 80%** — useful for
multi-session work but not load-bearing.

**D. Tune `skillListingMaxDescChars`**
The default skill-listing budget is 1% of context window OR 8K chars
(whichever first), with each entry capped at 1,536 chars.
[The character budget can strip keywords](https://medium.com/@unicodeveloper/10-must-have-skills-for-claude-and-any-coding-agent-in-2026-b5451b013051)
when many skills are present. The project has 14 internal + 2 plugin
= 16 skills. Default cap divides ~10K / 16 ≈ ~625 chars per skill,
under the 1,536 max. Some descriptions are already long (the `/jseval`
skill description is dense). Setting `skillListingMaxDescChars: 768`
in `~/.claude/settings.json` would put a hard floor that survives
skill growth. **Confidence 60%** — depends on whether truncation is
actually happening in practice; could probe by checking a session's
system prompt for trailing `...` on skill descriptions.

#### 14.24.2 Tier-2 awareness (no immediate action, worth knowing)

| Item | What it is | Why it matters |
|---|---|---|
| **Monitor tool** (v2.1.98, April 9) | Built-in tool that spawns a background watcher and streams events into the conversation. Replaces polling. | Already in deferred-tools list. The project's `jseval --pipeline` already avoids polling. But for ad-hoc work like "watch this log for errors," the agent should reach for `Monitor` instead of writing `while ! grep …; do sleep 0.5; done` patterns. |
| **`/loop` self-pacing** | Omit interval, agent self-paces or uses `Monitor` to skip polling. `ScheduleWakeup` is the dynamic-mode wake mechanism. | The user has used `/loop` 2 times per `skillUsage`. Self-pacing is the now-canonical mode for /loop work; agents should default to it instead of hard-coded intervals. |
| **`ConfigChange` hook event** | Fires when settings.json or related files change. Can block the change (except policy_settings). | Useful to detect mid-session drift. Project doesn't subscribe; not urgent — config rarely changes mid-session. |
| **PowerShell tool security hardening** (v2.1.89-90) | Fixed: trailing `&` background-job bypass, `-ErrorAction Break` debugger hang, archive-extraction TOCTOU, parse-fail fallback deny-rule degradation, PS 5.1 argument-splitting for double-quotes+whitespace. | The user has the PowerShell tool. v2.1.119 has the fixes. No action needed. |
| **`/agents` tabbed layout** | Running tab + Library tab with Run/View actions. | UX improvement. The user can hit Ctrl+T → see active subagents. |
| **`/cost` per-model breakdown** | Shows per-model and cache-hit breakdown for subscription users. | The user is on Claude Pro/Max. Useful for understanding session economics. |
| **`/release-notes` interactive picker** | Browse release notes by version. | UX. |
| **Hardened Bash permission tokenization** (Week 15) | Backslash-escaped flags, env-var prefixes, `/dev/tcp` redirects, compound commands now prompt correctly. | Anthropic's permission engine improved its parser. Doesn't help the project's regex-based `bash-guard.mjs`, but means the platform layer is more accurate. |
| **OS CA cert store trusted by default** | Enterprise TLS proxies work without setup. | N/A — user isn't behind a corporate proxy. |
| **Snyk ToxicSkills (Feb 2026)** | 13.4% of publicly-available skills had critical vulnerabilities. | Project skills are internal (safe). The user-scope `claude-notifications-go` plugin (community-published, GPL-3.0) is the only third-party-skill exposure. Worth one-time audit; not urgent. |
| **Settings resilience** | Unrecognized hook event names no longer cause the whole settings.json to be ignored. | Good safety net. The project's settings.local.json has many hook entries; this protects against a typo bricking the file. |

#### 14.24.3 Items declined or not applicable

| Feature | Why skipped |
|---|---|
| **Auto mode** | Maintainer declined (§14.10 #4) — sticking with bypassPermissions. |
| **WSL/Linux subsystem features** (`wslInheritsWindowsSettings`, Linux sandbox, seccomp guards) | Maintainer won't install WSL (§14.10 #5). |
| **Version pinning** (e.g., to v2.1.117 to skip v2.1.119 regressions) | Maintainer declined — pin → forget → drift (§14.10 #6). |
| **Ultraplan** (research preview, v2.1.101) | Cloud-based plan mode. The maintainer works locally; cloud round-trip not aligned. |
| **`/autofix-pr`** (Week 15) | Watches CI + review comments and pushes fixes until green. **Conflicts with the manual-CI policy** (ADR-0026): triggering it would put workflow runs on autopilot, defeating the policy intent. Declined. |
| **Bedrock/Vertex/Foundry setup wizards** | User authenticates via Claude Pro/Max OAuth. |
| **`CLAUDE_CODE_PERFORCE_MODE`** | Project is Git, not Perforce. |
| **Memory for Claude Managed Agents (public beta)** | SDK-only feature for the managed-agents harness; project uses Claude Code CLI. |
| **`listSubagents()` / `getSubagentMessages()` SDK functions** | TypeScript SDK only; project doesn't use the SDK. |
| **Subagent + SDK MCP server reconfiguration parallelization** | Performance improvement for SDK harness consumers. Project uses CLI directly. |
| **`/team-onboarding`** | Generates ramp-up guide for new teammates. Solo-maintained project; low priority. |
| **`CLAUDE_CODE_USE_POWERSHELL_TOOL`** opt-in toggle | The PowerShell tool is already enabled and visible in this session's tool list. |

#### 14.24.4 Anthropic built-in skills not enumerated in §5

Surfaced via a system-reminder skill listing during this session.
The original §5 only catalogued project skills + 2 user-scope plugin
skills. Claude Code also ships **8 built-in / platform skills**:

| Skill | What it does |
|---|---|
| **`update-config`** | Configures the Claude Code harness via `settings.json`. Anthropic's recommended path for hook/permission/env-var changes. Manual JSON editing works but goes through this skill is the maintained path. |
| **`keybindings-help`** | Customize keyboard shortcuts in `~/.claude/keybindings.json`. |
| **`simplify`** | Review changed code for reuse, quality, and efficiency, then fix issues. |
| **`fewer-permission-prompts`** | Scans transcripts for common read-only Bash/MCP calls, proposes a prioritized allowlist. Could be useful for the project — but maintainer is on `bypassPermissions` so allowlists aren't load-bearing. |
| **`loop`** | Recurring task scheduling. Maintainer has used it 2×. |
| **`schedule`** | Cron-style remote agent scheduling. |
| **`claude-api`** | Build/debug Claude API apps. Triggers on `anthropic`/`@anthropic-ai/sdk` imports. Not relevant to JustSearch (no SDK use). |
| **`init`** | Initialize a starter CLAUDE.md from codebase analysis. Project already has one, so N/A. |
| **`review`** | Review a pull request. |
| **`security-review`** | Complete a security review of pending changes on current branch. |

**Implications for the punch list:**

- For Tier-1 items #1 (`if`-filter), #3 (`duration_ms`), #4 (`InstructionsLoaded`), and #6 (`skillListingMaxDescChars`): the maintained path is `Skill(update-config)`, not direct JSON editing. The skill knows the schema and will validate properly. Future implementation should invoke `update-config` rather than hand-edit.
- **`security-review`** is shipped by Anthropic and could complement the project's `agent-review/` workflow. Worth knowing.
- **`loop`** and **`schedule`** are first-class platform features, not third-party. The maintainer's existing `/loop` usage is the canonical pattern.

#### 14.24.5 Re-prioritized punch list (post-research)

Combining §14.17 + §14.23.10 + §14.24.1 with strict confidence and effort filtering:

| # | Item | Effort | Confidence | Status |
|---|---|---|---|---|
| 1 | Add `if`-filters to 5 hooks (build-counter, ssot-hint, lockfile-hint, docs-regen-hint, ui-shot-hint) | ~10 lines in `.claude/settings.local.json` | 90% | NOT DONE |
| 2 | Remove `@` from CLAUDE.md line 291 (dead text) | 1-char edit | 95% | NOT DONE |
| 3 | Capture `duration_ms` in `dispatch.mjs` PostToolUse | ~3 lines | 95% | NOT DONE |
| 4 | Wire `InstructionsLoaded` hook to `dispatch.mjs` | ~5 lines added to settings.local.json + dispatch.mjs handler | 90% | NOT DONE |
| 5 | UserPromptSubmit `sessionTitle` derivation | ~10-20 lines | 80% | NOT DONE |
| 6 | Tune `skillListingMaxDescChars: 768` in `~/.claude/settings.json` | 1 line | 60% (needs verification of truncation) | NOT DONE |
| 7 | (deferred to its own tempdoc) Tree-sitter for `bash-guard.mjs` | ~half-day | 60% | DEFERRED |
| 8 | (deferred) CLAUDE.md size refactor with path-scoped rules | half-day | uncertain | DEFERRED |

Items #1-#4 are the highest leverage — concrete, ≥90% confidence, sub-30-line changes total.

### 14.25 Second implementation pass (2026-04-28)

Following §14.24's punch list, executed Groups A/B/C in one batch.
All probes passed.

#### 14.25.1 What shipped

| # | Item | File(s) | Probe result |
|---|---|---|---|
| 1 | `if`-filter on `build-counter.mjs` | `.claude/settings.local.json` | `Bash(./gradlew*)` — narrows from every Bash to Gradle-only |
| 1 | `if`-filter on `docs-regen-hint.mjs` | `.claude/settings.local.json` | `Edit(docs/**)` |
| 1 | `if`-filter on `ssot-hint.mjs` | `.claude/settings.local.json` | `Edit(**/SSOT/catalogs/**)` (matches both root and classpath copy) |
| 1 | `if`-filter on `lockfile-hint.mjs` | `.claude/settings.local.json` | `Edit(**/build.gradle.kts)` |
| 1+ | **Wired previously-unregistered `ui-shot-hint.mjs`** with `if: "Edit(modules/ui-web/src/**)"` | `.claude/settings.local.json` | The script existed but was never registered; the `hooks-reference.md` claim that it fired was wrong. Now it does. |
| 2 | Removed `@` from CLAUDE.md line 291 | `CLAUDE.md` | Line now reads `See \`.claude/rules/context-efficiency.md\` for agent guidance (auto-loaded as a project rule).` Truthful semantics. |
| 3 | `duration_ms` in `dispatch.mjs` PostToolUse | `dispatch.mjs` | Probe injected `duration_ms: 1234` into PostToolUse input; events.ndjson recorded `"duration_ms":1234`. |
| 4 | `InstructionsLoaded` handler in `dispatch.mjs` + settings registration | both files | Probe sent `InstructionsLoaded` event with `files: [...]`; events.ndjson recorded a new `instructions_loaded` event with `files`, `trigger`, `source` fields. |

Settings JSON validates clean. dispatch.mjs imports clean.

#### 14.25.2 Bonus finding: `ui-shot-hint.mjs` was orphaned

When wiring up the `if`-filters I noticed `ui-shot-hint.mjs` exists as
a script in `scripts/agent-analytics/hooks/` and is documented in
`.claude/rules/hooks-reference.md` as firing on edits to
`modules/ui-web/src/**/*.{ts,tsx}` — but it was **not registered as a
hook in `.claude/settings.local.json`**. So the doc claim and reality
diverged: every previous edit to a UI file was supposed to surface a
file-to-step hint via this hook, but never did.

Wired now. Future UI edits will see the hint per the
`scripts/jseval/jseval/ui_step_index.json` mapping.

This is the third instance in this investigation of "the documented
behavior didn't match runtime behavior" (alongside auto-evaluate
broken from day one and the third-party CLAUDE.md propagation
claim). Pattern worth noting: **never trust documented hook behavior
without confirming the hook is wired**.

#### 14.25.3 When the changes take effect

`.claude/settings.local.json` is read at session start. The current
session loaded the old config; **the new `if`-filters and the
`InstructionsLoaded` hook will take effect on the next session.** The
`dispatch.mjs` change for `duration_ms` is read fresh on every hook
invocation, so it applies immediately for any PostToolUse event the
platform sends with that field.

Verifying the `if`-filter behavior end-to-end (i.e., that the platform
actually skips spawning hooks when the input doesn't match) requires
a new session. The next session's `events.ndjson` should show:

- `pre_tool_use` events for `Bash` calls *not* containing `gradlew`,
  but **no** corresponding `node scripts/agent-analytics/hooks/build-counter.mjs`
  invocation in the system stop_hook_summary entries — confirming
  the hook didn't spawn.
- Similarly for `Edit` calls outside the `if`-pattern scopes.
- An `instructions_loaded` event near the start of every session,
  listing the actually-loaded CLAUDE.md and rules files.

That last one is the high-value diagnostic — it'll definitively
answer "do worktree CLAUDE.md files actually load as expected" and
"do `.claude/rules/*.md` propagate to subagents" without further
manual probing.

#### 14.25.4 Items remaining from §14.24.5

| # | Item | Status |
|---|---|---|
| 1-4 | `if`-filters (×4 + bonus ui-shot wiring), CLAUDE.md edit, `duration_ms`, `InstructionsLoaded` | **DONE** |
| 5 | UserPromptSubmit `sessionTitle` derivation | Skipped per maintainer recommendation |
| 6 | `skillListingMaxDescChars` tuning | Deferred — needs probe of next session's transcript to verify whether truncation is happening |
| 7 | Tree-sitter for `bash-guard.mjs` | Deferred to its own tempdoc |
| 8 | CLAUDE.md size refactor | Deferred to its own tempdoc |

### 14.20 Final closing summary (2026-04-28)

**The deliverable was a comprehensive environmental survey.** The
survey expanded into a probe-and-implement pass; both are complete.

#### What shipped (code/config changes)

| Change | Type | Verified |
|---|---|---|
| Deleted `auto-evaluate.mjs` + its SessionEnd hook entry | code/config | hook chain still loads; `bash-guard`, `subagent-guide`, `intervene` probed post-change |
| `intervene.mjs` now emits `additionalContext` alongside `updatedInput` for the auto-`limit:200` injection, and a line-count hint for the hot-file cap | code | probed: 4 cases pass |
| `subagent-guide.mjs` expanded from 6 lines / ~360 chars to 2,744 chars across 7 sections (Hard Invariants, Agent Discipline, Subagent risk profile, Observations protocol, Tooling pointers, Reporting, Session attribution) | code | probed: emits valid JSON, well under the 10K hook output cap |
| CLAUDE.md gained a "Delegating to Subagents" subsection under Agent Discipline | docs | content verified against §14.16 probe findings |
| Deleted `io/justsearch/`, `net/ltgt/` (misplaced `.class`/`.kt` cruft) | local fs | confirmed untracked, no git history, source content lives elsewhere |
| Deleted `.agents/skills/` mirror | local fs | gitignored, never tracked |
| Removed `mcp__ide__*` (no IDE MCP server) and redundant `Skill(update-config)` from `.claude/settings.local.json` | config | JSON valid post-edit |

#### What did not ship (and why)

| Recommendation | Outcome | Reason |
|---|---|---|
| Trim `subagent-guide.mjs` to its one unique line | **REVERSED** | §14.16.1 probe + Piebald-AI primary source confirmed subagents don't see CLAUDE.md or rules. Expanded instead. |
| Delete root `.dev-data/` | **REVERSED** | §14.16.3 grep confirmed 4 production scripts reference it actively. |
| Delete `.claude/scratch/research uncertainties.md` (duplicate) | **N/A** | Re-inspection showed scratch contains a `justsearch-releases` git clone, no duplicate exists. My initial claim was a misreading of my own probe output. |
| Add `compactPrompt` to `~/.claude/settings.json` | **NOT POSSIBLE** | The Edit tool's settings validator rejected the field with the full v2.1.119 schema. `compactPrompt` is third-party-doc folklore, not a real Claude Code setting. |
| Add "Compact Instructions" CLAUDE.md section | **WITHDRAWN** | Same source class as `compactPrompt`; no canonical Anthropic backing. Not added. |
| Pin Claude Code to v2.1.117 | **DECLINED** | Maintainer noted version pins get forgotten and aren't worth the discipline cost. |

#### Three reversed third-party-derived recommendations

- "Subagents inherit CLAUDE.md via normal message flow" → false (probe + primary source).
- "Use `compactPrompt` setting to customize compaction" → false (not in schema).
- "Add Compact Instructions CLAUDE.md section" → unverified (not in schema, not in canonical docs).

All three came from the same class of source: 2026 third-party blogs (techtaek, ofox.ai, blog.vincentqiao.com). None held up against probes or schema validation.

#### Methodology lesson, repeated

For Claude Code platform claims, the reliable evidence chain (best to
worst):

1. Runtime probe (Edit tool schema validation, hook execution, subagent introspection)
2. [Anthropic's official docs](https://code.claude.com/docs/en/) and [GitHub issues](https://github.com/anthropics/claude-code/issues)
3. [Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts) — extracted from binary per version
4. Anthropic's [`bash_command_validator_example.py`](https://github.com/anthropics/claude-code/blob/main/examples/hooks/bash_command_validator_example.py) and similar published examples
5. Third-party 2026 blogs/refs — useful for orientation, not for committing to changes

#### Open items not addressed in this pass

| Item | Why deferred |
|---|---|
| Tree-sitter for `bash-guard.mjs` (§15 Tier 2 #7) | Half-day engineering task; deserves its own tempdoc. The substring-match weakness is documented; no probe of Windows tree-sitter native bindings was done. |
| Periodic triage of `docs/observations.md` (§15 Tier 2 #9) | Workflow change requires maintainer decision on cadence. |
| Hint hooks rate-limit experiment (§15 Tier 2 #10) | The hints currently fire per matching edit. Rate-limiting would need state in `tmp/agent-telemetry/` and per-session reset logic. Cheaper to live with the cost or delete entirely; no clean middle ground without telemetry on whether the hints actually course-correct agents. |

These are documented for future work, not silently dropped.

#### Status

This tempdoc started as an environmental survey, expanded into a
critical analysis, then into a probe pass and an implementation pass.
The work is **complete** as of 2026-04-28. Recommend frontmatter
status → `shipped`.

The substantive learnings worth keeping live in `.claude/rules/agent-lessons.md`
(see §14.21). The situational observations live in `docs/observations.md`
under `## Inbox` (see §14.22).

### 14.21 Promoted to `.claude/rules/agent-lessons.md`

Durable findings (apply to every session, not just this work):

- Subagents do **not** inherit CLAUDE.md, MEMORY.md, or `.claude/rules/*.md`. Their system prompt is replaced; project context only reaches them via the `subagent-guide.mjs` SubagentStart hook.
- Subagents do **not** inherit parent-session hooks. No bash-guard, no repeat-guard, no intervene auto-limit, no build-counter.
- The Edit tool validates `~/.claude/settings.json` against the canonical Claude Code schema on save — useful for sanity-checking whether a setting field exists before relying on it. The error message dumps the full schema.
- `claude-notifications-go` plugin (user-scope) registers Stop/SubagentStop/Notification/TeammateIdle hooks that **double-fire** alongside project hooks (~500-565ms per Stop).
- The Read tool has multiple silent truncation layers: 2000 char/line, 2000 lines, 25k tokens (varies by model). Project's `intervene.mjs` adds a 200-line cap >8KB. Now annotates with `additionalContext` so truncation is visible.
- Primary source for subagent system prompts: [Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts) — extracted from binary per Claude Code version.

### 14.22 Filed to `docs/observations.md`

Situational items (not durable lessons; this-checkout housekeeping):

- `claude-code-warp` marketplace registered in `~/.claude/settings.json` `extraKnownMarketplaces` but no plugin from it is enabled — dead registration.
- `frontend-design@claude-plugins-official` is active at user scope, surfaces a `frontend-design` skill alongside the 14 project skills. Worth knowing when an agent is told "use the project skills."
- The `claude-notifications-go` plugin's Stop hook doubles every Stop event; if Stop latency ever becomes a complaint, this is the surface to look at.
- `outcomes.ndjson` and `tmp/agent-telemetry/sessions/` never existed (auto-evaluate.mjs was buggy from day one). No historical session-quality data available.

---

## Sources

### Anthropic platform docs (canonical)

- [Hooks reference](https://code.claude.com/docs/en/hooks)
- [Settings hierarchy](https://code.claude.com/docs/en/settings)
- [Sub-agents](https://code.claude.com/docs/en/sub-agents)
- [Plugins](https://code.claude.com/docs/en/plugins)
- [How Claude Code works](https://code.claude.com/docs/en/how-claude-code-works)
- [Compaction](https://platform.claude.com/docs/en/build-with-claude/compaction)
- [Tool search tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)
- [Bash command validator example](https://github.com/anthropics/claude-code/blob/main/examples/hooks/bash_command_validator_example.py)
- [Changelog](https://code.claude.com/docs/en/changelog) — v2.1.119 reference, Apr 23 2026

### Anthropic GitHub issues (live problems)

- Hooks/subagents: [#237](https://github.com/anthropics/claude-code/issues/237), [#21460](https://github.com/anthropics/claude-code/issues/21460), [#50850](https://github.com/anthropics/claude-code/issues/50850), [#42596](https://github.com/anthropics/claude-code/issues/42596), [#27881](https://github.com/anthropics/claude-code/issues/27881), [#15174](https://github.com/anthropics/claude-code/issues/15174)
- Read truncation: [#28783](https://github.com/anthropics/claude-code/issues/28783) (silent guardrail loss), [#14888](https://github.com/anthropics/claude-code/issues/14888), [#15687](https://github.com/anthropics/claude-code/issues/15687), [#20223](https://github.com/anthropics/claude-code/issues/20223), [#22699](https://github.com/anthropics/claude-code/issues/22699), [#40357](https://github.com/anthropics/claude-code/issues/40357), [#6910](https://github.com/anthropics/claude-code/issues/6910)
- Deferred tools: [#33073](https://github.com/anthropics/claude-code/issues/33073), [#44536](https://github.com/anthropics/claude-code/issues/44536), [#27208](https://github.com/anthropics/claude-code/issues/27208), [#31002](https://github.com/anthropics/claude-code/issues/31002), [#32485](https://github.com/anthropics/claude-code/issues/32485)
- Custom agents not overriding built-ins: [#8697](https://github.com/anthropics/claude-code/issues/8697), [#18212](https://github.com/anthropics/claude-code/issues/18212), [#16594](https://github.com/anthropics/claude-code/issues/16594)
- Windows env: [#27987](https://github.com/anthropics/claude-code/issues/27987)

### Third-party 2026 references (consensus + best practices)

- [techtaek — context discipline 2026](https://techtaek.com/claude-code-context-discipline-memory-mcp-subagents-2026/)
- [Piebald-AI/claude-code-system-prompts](https://github.com/Piebald-AI/claude-code-system-prompts) — prompt leak, per-version
- [okhlopkov — compaction explained 2026](https://okhlopkov.com/claude-code-compaction-explained/)
- [Vincent Qiao — /compact deep dive](https://blog.vincentqiao.com/en/posts/claude-code-compact/)
- [Nick Porter — post-compaction hooks for context renewal](https://medium.com/@porter.nicholas/claude-code-post-compaction-hooks-for-context-renewal-7b616dcaa204)
- [zread.ai — Bash security and sandbox](https://zread.ai/instructkr/claude-code/27-bash-security-and-sandbox)
- [claudefa.st hooks guide — overhead benchmarks](https://claudefa.st/blog/tools/hooks/hooks-guide)
- [v2.1.119/v2.1.120 survival checklist](https://gist.github.com/yurukusa/a866b4cd2976486156a00c190c39cef6) — known regressions

### Local files

- `.claude/settings.json`, `.claude/settings.local.json`, `~/.claude/settings.json`, `~/.claude.json`
- `.mcp.json`, `.claude/rules/*.md`
- `scripts/agent-analytics/hooks/*.mjs`
- `scripts/dev/justsearch-dev-mcp/server.mjs`
- CLAUDE.md, AGENTS.md, `docs/tempdocs/README.md`
- `~/.claude/plugins/{cache,marketplaces,installed_plugins.json}`
