---
title: "Runbook: index.start-error"
type: runbook
status: stable
description: "Operator response when the indexer fails to start (worker spawn failure)."
---

# Runbook: `index.start-error`

The indexer failed to start. The supervisor was unable to bring the Worker process to a ready state at all — this is structurally distinct from `index.unavailable`, which is reported once the Worker is reachable but the index isn't serving.

## Symptoms

- Health view shows the **Indexer failed to start** banner.
- `/api/health/events/stream` emits an `AssertedCondition` with `id="index.start-error"` and `status=TRUE`.
- `/api/status` reports `WORKER_CONTROL_PLANE` in `NOT_READY` with reason `worker.spawn.failed`.

## Likely causes

- The Worker JAR or distribution is missing (e.g., `:modules:indexer-worker:installDist` not run since pull).
- The Worker exits during bootstrap because of a bad config (port already bound, missing models directory, missing JVM).
- A native dependency the Worker pulls in (ORT, llama-server) failed to load.
- File permissions prevent the Worker from writing to its data directory.

## Diagnostics

1. Read the embedded error detail in the `AssertedCondition` body — the supervisor records the spawn failure cause there.
2. Read the most recent Worker bootstrap attempt in `worker.log`. Spawn failures typically show within the first 50 lines after a restart timestamp:

   ```powershell
   Get-Content (Join-Path $env:LOCALAPPDATA 'JustSearch\logs\worker.log') -Tail 200
   ```

3. Verify the Worker distribution exists and looks intact:

   ```powershell
   Test-Path .\modules\indexer-worker\build\install\indexer-worker\bin\indexer-worker.bat
   ```

## Remediation

- **Missing distribution** — run `./gradlew.bat :modules:indexer-worker:installDist` (or `./gradlew.bat assemble` which wires it in).
- **Port already bound** — kill the orphaned process holding the gRPC port (see `Stop-Process -Id <PID> -Force` per `.claude/rules/agent-lessons.md`).
- **Models directory missing** — set `JUSTSEARCH_MODELS_DIR` to a populated directory (default is the repo's `models/`).
- **Native dep failure** — check the loaded library list in the Worker bootstrap log; common causes are CUDA driver mismatch or a stale ORT cache.

## Related

- `index.unavailable` — once the Worker is reachable but unhealthy (see [`index-unavailable.md`](index-unavailable.md)).
- Worker lifecycle: `docs/explanation/01-system-overview.md` (Body process role).
- Stale-distribution pitfall: `CLAUDE.md` "Common Pitfalls" — `installDist` is now wired into `assemble`, so a fresh `./gradlew.bat build` produces a runnable Worker.
