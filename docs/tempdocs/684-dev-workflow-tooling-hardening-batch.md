---
title: "Dev-workflow tooling hardening batch: (1) remove-worktree.cjs — fix the broken EPERM long-path delete fallback and let the tearing-down session pass its own session id to the record-merge step (today it attributes the merge to whichever session id happens to sit in the invoking checkout's current-session-id, or skips attribution entirely from a fresh worktree), (2) prepare-worktree.cjs — fix the gradlew spawn so the Gradle half actually runs (spawns bare 'gradlew.bat', which the shell does not resolve; npm-ci + config-seeding halves work). All three defects bit one real publishing session on 2026-07-07; all are on the same maintenance surface (scripts/dev + scripts/agent-analytics)."
type: tempdocs
status: "open — scoped, not started (each item carries its reproduction and acceptance)"
created: 2026-07-07
author: agent session 2026-07-07 (defects hit live during the 682 publish/teardown cycle; inbox-logged same day, batched here)
category: dx / agent-workflow / tooling
related:
  - 682-inherited-constants-stabilization-batch   # the session whose publish cycle hit all three defects
  - 618-agent-developer-velocity-friction         # the worktree-lifecycle friction register these tools came from
  - 665-observations-inbox-workflow-lifecycle-gaps # record-merge/fold run at the same merge boundary
---

# 684 — Dev-workflow tooling hardening batch

Three defects in the worktree/merge lifecycle tooling, all observed live in one session
(2026-07-07, the 682 publish), all small, batched because they share a maintenance surface.

## Item 1 — `remove-worktree.cjs`: EPERM fallback is broken

**Observed.** When any process holds a handle inside the worktree (in the live case: a
background shell whose cwd was the worktree), the node delete fails with EPERM — expected —
and the script's long-path fallback then throws `The filename, directory name, or volume
label syntax is incorrect` instead of retrying properly. The `\\?\` fallback path
construction is wrong, so a held-handle worktree fails removal twice and the operator has to
find and kill the holder manually.

**Work.** Fix the fallback path construction (or replace the fallback with a
retry-after-delay loop plus a clear "these processes hold the directory" report — the
Win32_Process cwd/commandline probe used manually in the live incident is a good shape).

**Acceptance.** A worktree held by a live process fails removal with an actionable message
naming the holder (or succeeds after the holder exits, via bounded retry); the fallback never
throws a path-syntax error. Regression-testable by holding a handle from a child process in a
test.

## Item 2 — `remove-worktree.cjs` / `record-merge.mjs`: session attribution is by-standing-file, not by-caller

**Observed.** The teardown's record-merge step reads
`tmp/agent-telemetry/current-session-id` from the invoking checkout. In a multi-session
environment that file belongs to whichever session last started there: the live incident
first attributed the 682 merge to a neighbouring session, then (rerun from a fresh worktree)
skipped attribution entirely. The same mechanism later filed an observation note into the
neighbouring session's shard. The tearing-down session has no way to pass its own identity.

**Work.** `record-merge.mjs` accepts an explicit session id (flag or env var) that overrides
the marker-file read; `remove-worktree.cjs` forwards it. `note-observation.mjs` already
resolves per-session shards — verify it takes the same override or document why not.

**Investigation addendum (2026-07-07, 681 publish session — root cause sharpened, fix
simplifies).** Live-probed in the affected environment:

- **The correct identity is ALREADY in every Bash invocation's env.** Both
  `CLAUDE_CODE_SESSION_ID` (harness-native, v2.1.200) and `JUSTSEARCH_AGENT_SESSION_ID`
  (repo export) carried the *caller's* id in the same shell where the marker file carried a
  neighbour's. The defect is therefore a **resolution-order inversion**, not missing plumbing:
  `resolveSessionId()` in `note-observation.mjs:47-53` checks the shared marker file FIRST and
  treats env as a *fallback* — designed for "file missing", not for "file stale", and the file
  is a last-SessionStart-wins single slot per checkout. (Worktree checkouts each have their own
  `tmp/agent-telemetry/`, which is why worktree-side attribution stays correct; only shared
  checkouts — main — misroute.) Third live instance same day: an observation note filed from
  the main checkout landed in the neighbour's shard via exactly this path.
- **Fix shape (smaller than the flag-forwarding design):** invert to env-first
  (`CLAUDE_CODE_SESSION_ID` → `JUSTSEARCH_AGENT_SESSION_ID` → marker file → worktree hash) in
  the ONE shared resolver, and make `record-merge.mjs` import that resolver instead of its
  private file-only read (`record-merge.mjs:28-34` is a mini-fork of the same logic today).
  `remove-worktree.cjs` then needs NO forwarding for the common case — its record-merge child
  inherits the caller's env. Keep the explicit `--session-id` flag as the escape hatch for
  headless/cron contexts where the env vars are absent.
- **Implementation probes owed:** (a) confirm `CLAUDE_CODE_SESSION_ID` presence in
  subagent-spawned Bash (parent's vs own id — either is defensible, but document which);
  (b) `export-session-env.mjs`'s own header calls its CLAUDE_ENV_FILE mechanism
  "broken on Windows (#27987)" yet `JUSTSEARCH_AGENT_SESSION_ID` was present — find the actual
  setter before relying on it (the harness-native var is the safer primary).

**Acceptance.** A session tearing down a worktree can attribute the merge to itself
regardless of what `current-session-id` contains; `session-merges.ndjson` entries from
teardown are correct by construction, not by residency luck. **Added per the addendum:** a
`note-observation.mjs` call from the main checkout while a foreign id sits in
`current-session-id` writes to the CALLER's shard (unit-testable by passing `env` to
`resolveSessionId`, which already takes it as a parameter).

## Item 3 — `prepare-worktree.cjs`: the Gradle half never runs

**Observed.** The script spawns plain `gradlew.bat`, which the spawn environment does not
resolve from cwd ("'gradlew.bat' is not recognized…"), so `installDist` fails every time on
this environment while the npm-ci and config-seeding halves succeed. `.\gradlew.bat` from the
same cwd works, and JAVA_HOME was a valid JDK 25 — this is spawn-path resolution, not a
toolchain problem.

**Work.** Spawn the wrapper via an explicit cwd-qualified path (and/or `shell: true` with the
`.\` prefix). Keep the failure loud if Gradle itself fails.

**Acceptance.** `node scripts/dev/prepare-worktree.cjs` completes both halves on a fresh
worktree on this environment; the failure mode "wrapper not found" is impossible.

## Out of scope

- The external-signal kills of long-running background shells observed the same day
  (environment behavior, cause unknown — tracked as a note in 682's close-out; no code target).
- Any redesign of the session-id/telemetry model beyond the explicit-override parameter.

## Verification map

Item 1: unit/manual repro with a held handle; message names the holder. Item 2: teardown from
a worktree with a foreign `current-session-id` present writes the caller's id. Item 3: fresh
worktree, one command, both halves green. Standard pre-merge: `./gradlew.bat build -x test`.
