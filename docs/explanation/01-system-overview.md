---
title: System Overview
type: explanation
status: stable
description: "The 3-process architecture pattern."
---

# 01. System Architecture: The "Local-First Cloud"

JustSearch is built on a **Local-First Microservices** architecture. While it runs entirely on the user's local machine, it mimics the resilience and separation of concerns found in distributed cloud systems. This design handles the unique constraints of desktop environments—specifically OS file locking on Windows and UI responsiveness guarantees.

Functionally it is more than a search index: over the local corpus it layers an optional on-device **LLM agent** (cited Q&A, summarize, extract, and gated file actions — see [22. Agent System Architecture](22-agent-system-architecture.md)) and ships a **production MCP server** (`POST /mcp`) so external AI agents (Claude Code, Cursor, Claude Desktop) can drive that same search and retrieval — see [Production MCP Server](../reference/mcp-production-server.md). The three processes below are how that capability surface is delivered resiliently on the desktop.

## The 3-Process Model

The application is split into three distinct OS processes to ensure that a crash or heavy load in one component never brings down the entire system.

```mermaid
graph TD
    User[User] --> UI[Main Process\n(UI Host + Orchestrator)]
    UI -- gRPC + MMF --> Worker[Knowledge Server\n(Indexer + Search Engine)]
    UI -- HTTP --> AI[Inference Server\n(llama-server.exe)]
    
    subgraph "Main Process (Head)"
        Tauri[Tauri Shell]
        Lit[Frontend]
        Headless[Java Backend\nHeadlessApp.java]
    end
    
    subgraph "Knowledge Server (Body)"
        Lucene[(Lucene Index)]
        SQLite[(Job Queue)]
        Tika[Content Extractor]
    end
    
    subgraph "Inference Server (Brain)"
        Llama[Llama.cpp Server]
    end
```

### 1. The Main Process ("The Head")
*   **Entry Point:** `io.justsearch.ui.HeadlessApp`
*   **Modules:** `modules/ui` (Headless backend), `modules/ui-web` (Lit web-components frontend), `modules/shell` (Tauri desktop shell)
*   **JVM Heap:** Typically small (128MB-256MB).
*   **Role:** User Interface, Application Orchestration, and API Gateway.
*   **Key Responsibilities:**
    *   **Sidecar Host:** Runs as a child process of the Tauri shell.
    *   **Configuration Owner:** Loads `SSOT` configs and injects them into child processes.
    *   **Watchdog:** Monitors the health of `KnowledgeServer` and `llama-server`.
    *   **API Gateway:** Exposes the REST surface used by the UI (e.g. `/api/status`, `/api/health`, `/api/knowledge/*`, `/api/summarize/*`, `/api/inference/*`) and bridges to gRPC calls into the Knowledge Server when present.
    *   **Zero IO (index):** Crucially, it **never** touches Lucene index files, preventing `LockObtainFailedException` and preserving the “Worker owns Lucene” invariant.
    *   **Deterministic failure surfacing:** Knowledge Server startup failures are captured and surfaced via `/api/status` (so the UI can show “backend up, worker failed” instead of guessing).

### 2. The Knowledge Server ("The Body")
*   **Entry Point:** `io.justsearch.indexerworker.IndexerWorker`
*   **Module:** `modules/indexer-worker`
*   **Spawning Logic:** Managed by `WorkerSpawner.java`.
*   **JVM Arguments:**
    *   `-Xmx<dynamic>` (Configurable heap, typically larger for buffering).
    *   Note: `--add-modules=jdk.incubator.vector` was removed to enable JDK 25 AOT Cache
        full module graph optimization. Lucene uses scalar fallback. See tempdoc 269 §D4a.
*   **Role:** The heavy lifter. Handles all file indexing, text extraction, and vector search.
*   **Index ownership:** Owns Lucene + generation layout (`state.json`, `indices/<gen>/`) and orchestrates schema migrations (blue/green) when configured.
*   **Resilience:**
    *   **Auto-Restart:** If the process crashes (e.g., Tika parses a "poison pill" PDF), the Main process detects exit code != 0 and restarts it (up to 3 times).
    *   **Log Redirection:** `stdout/stderr` are redirected to `%DATA_DIR%/logs/worker.log`.

### Head→Worker Config Propagation

Configuration reaches the Worker subprocess through three channels:

1. **Config snapshot** (primary): `HeadlessApp` serializes the fully resolved config to `worker-config-snapshot.json` and passes the path via `-Djustsearch.worker.config_snapshot`. The Worker loads this at ordinal 450 during `ResolvedConfigBuilder.loadWorkerSnapshotFromSysprop()`. This carries the vast majority of config values.

2. **Blanket env var forwarding**: `WorkerSpawner` forwards all `JUSTSEARCH_*` environment variables from the Head process to the Worker via `ProcessBuilder.environment()`. Any env var the user sets reaches the Worker automatically.

3. **Explicit system property forwarding**: A declared set of properties (`WorkerSpawner.WORKER_FORWARDED_PROPS`) are forwarded as `-D` JVM args. These are properties consumed before the config snapshot loads (e.g., `data.dir`, `config`, `repo.root`) or by non-application code (ORT JNI native library loading).

**Adding a new config key:** Add it to `EnvRegistry`. If it must be visible as a JVM system property in the Worker (not just via `ConfigStore`), add it to `WorkerSpawner.WORKER_FORWARDED_PROPS`. Most keys do NOT need this — the config snapshot handles them.

**Divergence detection:** After gRPC handshake, the Head compares its config values against the Worker's effective values (reported via `HealthCheckResponse.effective_config`). Mismatches produce WARN logs. The curated comparison set is `EnvRegistry.CONFIG_DIVERGENCE_CHECK_KEYS`.

### 3. The Inference Server ("The Brain")
*   **Executable:** `llama-server.exe` (Native Binary, no JVM).
*   **Managed By:** `InferenceLifecycleManager.java`
*   **Arguments:** `-m <model_path> [--mmproj <mmproj_path>] --host 127.0.0.1 --port <port> -c <ctx_size> -ngl <gpu_layers> <vram-tuning-flags...>` (loopback bind is always injected as defense-in-depth).
*   **Role:** Providing Intelligence (LLM Chat & RAG).
*   **VRAM Management:**
    *   **Zombie Killing:** On Windows, `Process.destroy()` can leave VRAM locked. We use `taskkill /F /PID` to enforce release.
    *   **Hung/Crash Recovery:** The manager monitors health periodically and will hard-kill a hung owned process; it also restarts on crashes when in Online mode.
    *   **External Instance Adoption:** If a healthy `llama-server` is already listening on the configured port, the manager can **adopt** it after probing `GET /props` (prevents restart loops and avoids accidentally adopting unrelated HTTP services). Adopted servers are still health-monitored; if they die mid-session, inference switches to Offline.
    *   **Health & Props:** The manager polls `GET /health` during startup and reads `GET /props` (best-effort) to learn the *actual* `n_ctx` and `model_alias` for diagnostics.

## Design Philosophy

### "Verify, Don't Guess"
The system is built to be deterministic.
*   **Port Discovery (gRPC):** We don't guess ports. The Knowledge Server gRPC listener binds to port `0` (ephemeral) and writes the *actual* assigned port to a shared **Memory-Mapped File (MMF)**.
*   **Port Discovery (HTTP):** The UI-facing HTTP API is usually configured (default `33221`), but can also be ephemeral. The backend prints `JUSTSEARCH_API_PORT=<port>` to stdout and the desktop shell injects it (Tauri `api_port` command / bridge). In browser dev mode, if no explicit port is provided, the UI auto-discovers by scanning a small loopback range (currently `33221..33250`) and validating the `/api/status` payload.
*   **State Polling:** The frontend polls `/api/status` to determine if the backend is ready, rather than assuming it is after X seconds.
*   **Lifecycle Gate:** Automation uses `GET /api/health` as a **contract-tested gate** (schema v1). It returns HTTP `200` for `READY|DEGRADED` and `503` otherwise; `/api/status` remains the richer “what’s running?” payload.

### "One Owner" Policy
Data corruption on Windows is often caused by two processes trying to open the same file.
*   **Lucene Index:** Owned exclusively by **Knowledge Server**.
*   **Configuration:** Owned by **Main Process**, injected into Worker defaults.
*   **User Settings:** Owned by **Main Process**.
*   **Enforced by locks:**
    * `AppInstanceLock` prevents multiple app instances from sharing a single `dataDir`.
    * `IndexRootLock` prevents multiple Workers from mutating the same effective `indexBasePath` (important when `justsearch.index.base_path` is overridden).

### Graceful Degradation
The system is designed to work even if parts fail:
*   **No GPU:** Inference Manager detects VRAM shortage and refuses to start `Online Mode`, falling back to keyword search.
*   **Worker Crash:** UI remains responsive (running on Main Process), shows "Index Offline" state, and acts as a generic file browser until the Watchdog restarts the worker.

## Current implementation notes / living docs

Some detailed “current state” docs live under `docs/reference/` while the design is still evolving:

- **Schema migration architecture (stable)**: `docs/explanation/11-index-schema-migration.md`
- **UI user readiness verification notes (living checklist)**: `docs/reference/ui-user-readiness.md`
