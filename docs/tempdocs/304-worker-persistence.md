---
title: "304: Worker Persistence Across Head Restarts"
type: tempdoc
status: done
created: 2026-03-14
updated: 2026-03-14
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and
> tests before promotion.

# 304: Worker Persistence Across Head Restarts

## Problem

The developer iteration loop is: edit → build → start → verify. Every Head restart
kills the Worker via `WindowsJobObject` (`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`), forcing
a full Worker cold start: JVM spawn (~1.7s) + class loading + Lucene open (~250ms) +
model loading (~1.5s background) + gRPC bind (~180ms). Tempdocs 286 and 302 optimized
the Worker startup from 2066ms to 894ms (-57%), but the fundamental cost remains: every
Head restart pays the full Worker init.

If the Worker survived Head death and the Head reconnected to the existing Worker on
restart, dev iteration becomes: restart Head only (~460ms) → reconnect. The ~894ms
Worker startup is eliminated entirely for the common case (Head-only code changes, UI
changes, config changes).

## Estimated impact

- **Current end-to-end**: Head 460ms + Worker wait ~894ms = ~1354ms (with tempdoc 302)
- **With Worker persistence**: Head 460ms + reconnect ~100ms = ~560ms
- **Saving: ~794ms per restart cycle** (59% of current e2e)
- **Effective saving over original baseline**: 2460ms → 560ms = **-1900ms (-77%)**

## Architecture context

### Why the Worker dies with Head today

1. **`WindowsJobObject`** (`modules/app-util/.../WindowsJobObject.java`): Uses Win32
   `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` — when the Job Object handle is closed (Head
   exit or crash), the OS kills all assigned processes (Worker).

2. **Heartbeat "suicide pact"** (`MmfWorkerSignalBus.shouldDie()`): Worker polls MMF
   bytes [8-15] for Head heartbeat. If stale for >5s (after 15s startup grace), Worker
   self-terminates. Backup mechanism for when Job Object isn't available.

3. **Production correctness**: Prevents orphaned Workers holding Lucene write locks
   after Head crash. Essential for production — user cannot have a zombie Worker
   preventing the next app launch.

### What would need to change

The kill-on-close guarantee must be disabled in dev mode while preserving it in
production. The Head must discover and reconnect to an existing Worker instead of
always spawning a new one.

## Key questions to investigate

- [ ] How does `WorkerSpawner` create the `WindowsJobObject`? Can it be conditionally
  disabled via a system property or env var?
- [ ] How does `KnowledgeServerBootstrap.start()` orchestrate spawn → port discovery →
  connect? Where would reconnection logic be inserted?
- [ ] What state does the Head hold about the Worker that would be stale after a
  reconnection? (gRPC channel, client stubs, health check state)
- [ ] Can `MmfWorkerSignalBus.shouldDie()` heartbeat threshold be extended or disabled
  in persistent mode?
- [ ] What happens when the Head's code changes but the Worker's doesn't? The Worker
  is running old JARs. Is there a classpath fingerprint that can detect this mismatch?
- [ ] What happens when a Worker code change requires a Worker restart? Dev-mode
  `installDist` changes JARs — the persistent Worker would be running stale code.
- [ ] How does `AppInstanceLock` interact? Does the Head hold a lock that prevents
  reconnection?
- [ ] Does the gRPC `RemoteKnowledgeClient` support reconnection to an existing server,
  or does it assume a fresh channel?

## Implementation sketch

### Dev-only flag

```
-Djustsearch.dev.worker.persistent=true
```

Or environment variable `JUSTSEARCH_WORKER_PERSISTENT=true`.

### WorkerSpawner changes

1. Before spawning, check MMF for existing Worker port
2. If port found, probe gRPC health → if healthy, skip spawn, return existing port
3. If not healthy or not found, spawn new Worker as usual
4. When `persistent=true`, skip `WindowsJobObject` creation (or create without
   `KILL_ON_JOB_CLOSE`)
5. Extend heartbeat stale threshold or disable heartbeat check in persistent mode

### Head reconnection flow

```
HeadlessApp.main():
  settings → telemetry → policy → spawn Worker (async)
                                   ├─ check MMF for existing port
                                   ├─ if found: probe health, validate PID
                                   │   ├─ healthy: reconnect (skip spawn)
                                   │   └─ unhealthy: spawn new
                                   └─ if not found: spawn new
  facade → api → portEmit → late-bind
```

### Orphan protection

- `preflight` already checks for stale processes — extend to detect orphaned persistent
  Workers
- Add a classpath fingerprint check: Worker reports its classpath hash via gRPC health
  response. Head compares against current `installDist` hash. If mismatch, kill and
  respawn.
- Add a max-idle timeout: persistent Worker self-terminates after N minutes without a
  Head connection (configurable, e.g., 30 min)

## Risks

- **Stale Worker code**: After `./gradlew build`, the Worker JAR changes but the
  persistent Worker runs old code. Need classpath mismatch detection + automatic restart.
- **Lucene write lock**: If the persistent Worker crashes without releasing the lock,
  the next Worker can't start. Mitigated by existing `IndexRootLock` stale detection.
- **Memory leak**: Long-lived Worker accumulates state (ORT sessions, Lucene segments,
  SQLite connections). Needs monitoring or periodic restart.
- **Complexity**: Two startup paths (fresh spawn vs reconnect) increase the surface area
  for bugs. Dev-only flag limits blast radius.

## Prior art

- Tempdoc 286 Strategy B — initial description of this approach
- VS Code Extension Host — persists across window reloads
- Gradle Daemon — JVM persists across build invocations
- IntelliJ IDEA — services persist across project reloads

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Design proposal for Worker persistence across Head restarts. Current architecture has separate restart-able processes per ADR-0001; the proposal's decision point was reached (proposal was either absorbed or rejected — the current 3-process model is the stable answer).

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

