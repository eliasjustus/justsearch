---
title: "257: Backend Startup Architecture"
type: tempdoc
status: done
created: 2026-03-04
updated: 2026-03-04
---

# 257: Backend Startup Architecture

## Goal

Replace the 4-layer detached-supervisor startup chain with a direct-launch architecture
that starts the backend in ~6s, detects readiness via HTTP, and cleanly stops via PID file.

**Before:** 26s startup, broken detection (302s timeout), orphaned processes on stop.
**After:** 5.0s startup, reliable detection, clean stop with zero orphans.

## Current state

### Architecture (4 layers)

```
dag-runner-agent-battery.mjs        (orchestrator, DAG steps)
  └─ dev-runner-lifecycle.mjs       (detacher, spawns supervisor detached)
       └─ [detached] dev-runner.cjs (supervisor, long-lived, manages backend+frontend)
            ├─ [shell] gradlew.bat :modules:ui:runHeadless
            │    └─ java HeadlessApp         (~26s to HTTP 200)
            │         ├─ Worker subprocess
            │         └─ LocalApiServer on :PORT
            └─ npm run dev (frontend)
```

### What each layer does

**HeadlessApp.java** — The actual backend. Starts in ~4s (JVM) + ~3s (Worker). Signals
readiness two ways: writes `<dataDir>/runtime/api-port.txt` and prints
`JUSTSEARCH_API_PORT=<port>` to stdout. Exposes `/api/status` (HTTP 200 when ready).
Blocks on a `CountDownLatch` until shutdown hook fires.

**dev-runner.cjs** — Long-lived Node.js supervisor. Spawns Gradle with `shell: true`,
parses `JUSTSEARCH_API_PORT=<n>` from stdout, probes HTTP, spawns frontend, then writes
`active.json` and `run.json` as state files. `status` command reads `active.json` to
report state. `stop` command reads `active.json` to find PIDs, uses `taskkill /T /F`.

**dev-runner-lifecycle.mjs** — Short-lived wrapper for DAG compatibility. Spawns
dev-runner.cjs `detached: true`, polls `dev-runner.cjs status --active --json` every 2s,
exits 0 when readiness confirmed. Has a fallback: after 5 NO_ACTIVE_RUN polls, reads port
file directly and probes HTTP.

**dag-runner-agent-battery.mjs** — Runs a 6-step DAG. `start-backend` invokes
`lifecycle.mjs start`. Extracts `apiPort` from JSON stdout, patches `__API_BASE_URL__`
placeholder in downstream steps.

### What's broken

The `detached: true` spawn stalls dev-runner.cjs's event loop on Windows (known, unfixed:
nodejs/node#21825, #36808). The supervisor never writes `active.json`, so:

1. **Detection takes 302s** without the fallback (waits for full timeout)
2. **Stop can't find PIDs** — `active.json` never written, `stop --active` returns NO_ACTIVE_RUN
3. **Orphaned processes** — Java, dev-runner, and frontend left running after battery exits
4. **`--reuse-backend` broken** — calls `status --active`, which needs `active.json`

The direct-detection fallback (port file + HTTP) reduces startup to ~27s but leaves stop
broken and orphans accumulating.

### Timing breakdown

| Phase | Duration | Avoidable? |
|-------|----------|------------|
| Gradle cold JVM + config | ~10s | Yes — skip Gradle |
| Gradle task graph walk (UP-TO-DATE) | ~10s | Yes — skip Gradle |
| JVM launch + HeadlessApp init | ~4s | CDS could save ~1s |
| Worker subprocess | ~3s | No |
| API bind + ready | ~0.3s | No |

**77% of startup time is Gradle overhead.** `installDist` already produces a standalone
distribution with start scripts. After `./gradlew.bat build`, the backend can launch
directly from `build/install/ui/bin/ui`.

## Key constraint: DAG step must exit

The DAG scheduler (`dag-scheduler.mjs`) spawns each step as a child process and waits for
it to exit before running dependents. `start-backend` must exit 0 for `configure-model`,
`activate-ai`, etc. to proceed. The backend must survive that exit.

This means the launcher **cannot** stay foreground. It must:
1. Spawn the backend as a detached process
2. Poll for readiness
3. Write state (PID file, port) for later stop
4. Exit 0

The `detached: true` spawn is unavoidable. But the Node.js event loop stall only affects
**Node.js child processes** — Java doesn't have a libuv event loop. Spawning `java.exe`
directly with `detached: true, stdio: 'ignore'` should work fine (the Java process runs
independently of the parent's event loop state).

## Target architecture

```
dag-runner-agent-battery.mjs        (orchestrator, unchanged)
  └─ backend-launcher.mjs start     (short-lived, spawns Java detached, exits after ready)
       └─ [detached] java HeadlessApp  (from installDist, no Gradle)
            ├─ Worker subprocess
            └─ LocalApiServer on :PORT

  └─ backend-launcher.mjs stop      (short-lived, reads PID file, kills tree)
```

### Design principles

1. **Detach Java directly, not Node.js.** Spawn `java.exe` with `detached: true` — no
   Node.js event loop to stall. Eliminates dev-runner.cjs entirely.
2. **No Gradle in the hot path.** Launch `java` directly with wildcard classpath from
   `build/install/ui/lib/*`. Gradle runs once during build, not on every startup.
3. **HTTP readiness, not stdout parsing.** Port file for discovery, HTTP probe for
   readiness. `stdio: 'ignore'` — no pipes at all.
4. **PID file for stop.** Launcher writes `<dataDir>/runtime/backend.pid` after spawn.
   Stop reads it and calls `taskkill /PID /T /F`. No `active.json` indirection.
5. **No state files beyond PID + port.** No `run.json`, no `active.json`, no run-ID
   directory hierarchy. The backend is either running (PID alive + HTTP 200) or not.

### What this eliminates

- Gradle from startup (saves ~20s)
- stdout pipe parsing (the broken mechanism)
- Node.js-to-Node.js detach (the root cause of the stall)
- `active.json` / `run.json` indirection (never written in the broken path)
- dev-runner.cjs for battery use (stays for interactive `dev` command)
- 4 WriteStream log files managed by dev-runner.cjs

### What stays unchanged

- **HeadlessApp.java** — No changes needed. Already writes port file and exposes `/api/status`.
- **dag-runner-agent-battery.mjs** — Interface stays the same: `start-backend` step runs
  a script, gets JSON with `apiPort` on stdout. `--reuse-backend` probes HTTP directly.
- **dev-runner.cjs** — Stays for interactive `npm run dev` use. Battery stops using it.
- **Frontend** — Not in scope. Battery doesn't need the frontend.

### Launch mechanism: `java -cp lib/*` (not `ui.bat`)

The `installDist` output includes `build/install/ui/bin/ui.bat`, but it has a fatal flaw:
the explicit classpath line (4878 chars with 117 JARs) exceeds `cmd.exe`'s 8191-char limit
after `%APP_HOME%` path expansion. The batch script fails with "The input line is too long."

Instead, the launcher spawns `java` directly with wildcard classpath:
```
java --sun-misc-unsafe-memory-access=warn --enable-native-access=ALL-UNNAMED \
  -cp build/install/ui/lib/* io.justsearch.ui.HeadlessApp
```

This avoids the cmd.exe line length limit and eliminates the `shell: true` requirement.

### PID tracking: `cmd.exe` vs `java.exe`

When spawning `ui.bat` via `shell: true`, `child.pid` is the `cmd.exe` PID. The batch
script runs `java.exe` inline (line 77: `"%JAVA_EXE%" ... HeadlessApp %*`), NOT via
`start /b`, so `cmd.exe` stays alive as the parent process for the lifetime of Java.

**Tree kill is safe:** `taskkill /PID <cmd-pid> /T /F` kills `cmd.exe` → `java.exe` →
Worker subprocess (the entire tree). This is the same pattern dev-runner.cjs uses
(lines 803-832), proven in production.

**Port-owner fallback:** If the tree kill somehow fails (e.g., cmd.exe already exited),
query the port owner via `Get-NetTCPConnection` and kill that PID directly. This is
already implemented in dev-runner.cjs lines 327-347 and 856-862 — we can reuse the
pattern.

## What was implemented

### Phase A: JVM args in `applicationDefaultJvmArgs`

Added `--sun-misc-unsafe-memory-access=warn` and `--enable-native-access=ALL-UNNAMED` to
`application {}` block in `modules/ui/build.gradle.kts`. These are baked into the
installDist start scripts and also passed by the launcher directly.

### Phase B: `backend-launcher.mjs`

New file: `scripts/lib/bench/backend-launcher.mjs` (~260 lines). Three commands:

**`start`:** Spawns `java` directly (not `ui.bat` — see classpath issue below) with
`detached: true, stdio: 'ignore'`. Wildcard classpath from `build/install/ui/lib/*`.
Writes PID file, polls port file + HTTP every 500ms, exits 0 with JSON on readiness.

**`stop`:** Reads PID file → `taskkill /PID /T /F`. Polls port closure 10s. Fallback:
queries port owner via `Get-NetTCPConnection` if tree kill fails. Cleans up PID/port files.

**`status`:** Reads PID file → `process.kill(pid, 0)`. Reads port file → HTTP probe.

### Phase C: DAG runner wiring

`scripts/ci/dag-runner-agent-battery.mjs`:
- `start-backend` and `stop-backend` steps now invoke `backend-launcher.mjs`
- `--reuse-backend` probes multiple well-known data directories:
  1. The battery's own timestamped data dir
  2. The interactive dev default (`modules/ui-web/.dev-data`)

### Phase E: Clean up

Deleted 6 investigation scripts from `tmp/`. Left dev-runner.cjs and
dev-runner-lifecycle.mjs unchanged — they're still used for interactive `npm run dev`.

## Key files

| File | Role |
|------|------|
| `scripts/lib/bench/backend-launcher.mjs` | **New.** Foreground launcher, replaces lifecycle+dev-runner for battery |
| `scripts/lib/bench/dev-runner-lifecycle.mjs` | Current lifecycle wrapper (to be superseded) |
| `scripts/dev/dev-runner.cjs` | Current supervisor (stays for interactive use, battery stops using it) |
| `scripts/ci/dag-runner-agent-battery.mjs` | Battery DAG orchestration (wire new launcher) |
| `modules/ui/build.gradle.kts` | Add JVM args to `applicationDefaultJvmArgs` |
| `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java` | Backend entry point (no changes needed) |

## Verification results

| Test | Result |
|------|--------|
| `backend-launcher.mjs start` | **5.0s** (was 302s with old chain) |
| `backend-launcher.mjs stop` | Clean kill, zero orphans |
| `backend-launcher.mjs status` | Correct alive/dead reporting |
| Full battery (6 steps, 1 scenario) | All PASSED, 40.7s total |
| `--reuse-backend` (pre-started backend) | Detected, start/stop skipped, 18.5s total |
| Backend survives `--reuse-backend` run | Still running after battery exits |
| Orphan check after all tests | Zero JustSearch Java processes |
| Unit tests (`dag-runner-agent-battery.test.mjs`) | 101/101 pass |

## Status

- [x] Research: root cause, industry patterns, installDist feasibility, PID tracking
- [x] Phase A: JVM args in `applicationDefaultJvmArgs`
- [x] Phase B: `backend-launcher.mjs` (direct Java launch, wildcard classpath)
- [x] Phase C: Wire into DAG runner + multi-dir `--reuse-backend` probe
- [x] Phase D: Verified end-to-end (see table above)
- [x] Phase E: Clean up investigation scripts
