---
title: MCP Dev Tools Reference
type: reference
status: stable
description: "Dev orchestration tools for starting, monitoring, and verifying the JustSearch dev stack."
---

# MCP Dev Tools Workflow

The `justsearch-dev-mcp` server is the agent-facing control surface for the local development stack. It wraps the dev runner, selected Local API calls, search/ingest helpers, evidence capture, and AI runtime toggles.

Use this reference for tool selection. Use the implementation in `scripts/dev/justsearch-dev-mcp/server.mjs` as the source of truth for schemas and endpoint allowlists.

## Available Tools

The dev MCP surface currently exposes exactly these tools:

| Tool | Purpose |
|------|---------|
| `justsearch.dev.start` | Start the backend and frontend dev stack. Readiness waiting is part of this tool via its wait options. |
| `justsearch.dev.status` | Inspect current dev-runner state and process metadata. |
| `justsearch.dev.tail_log` | Read recent backend, frontend, or runner log lines. |
| `justsearch.dev.fetch_api_json` | Fetch predefined JSON endpoints by key. |
| `justsearch.dev.api_call` | Call allowlisted Local API endpoints with explicit method/path/body. |
| `justsearch.dev.search_query` | Execute `POST /api/knowledge/search`. |
| `justsearch.dev.ingest` | Execute `POST /api/knowledge/ingest`. |
| `justsearch.dev.validate_evidence` | Validate an `EvidenceBundle`. |
| `justsearch.dev.capture_evidence` | Capture an `EvidenceBundle` from search/API/UI context. |
| `justsearch.dev.preflight` | Run dev preflight checks before heavier workflows. |
| `justsearch.dev.quick_health` | Fast orientation check for UI/API/worker health. |
| `justsearch.dev.stop` | Stop the active dev run and clean up owned processes. |
| `justsearch.dev.agent_chat` | Send a prompt to the built-in agent and return the transcript. |
| `justsearch.dev.ai_activate` | Activate the online AI runtime. |
| `justsearch.dev.reload` | Trigger backend hot reload and report whether restart is required. |

Legacy underscore-style dev tool names and standalone readiness/listing/suggestion/cleanup tools are obsolete. Agents should use the dotted names above.

## Standard Workflow

1. Start the stack with `justsearch.dev.start`.
   - Use the tool's wait options instead of a separate wait-ready tool.
   - `waitTimeoutMs` may need to be higher than the default on cold machines or after clean builds.
2. Orient with `justsearch.dev.quick_health` for a compact readiness check.
3. Use `justsearch.dev.status` when process state, ports, or runner metadata matter.
4. Use `justsearch.dev.fetch_api_json` for common read-only diagnostics.
5. Use `justsearch.dev.api_call` only when the endpoint is in the explicit allowlist.
6. Use `justsearch.dev.stop` when the run should be shut down.

## Prerequisites

Build the Worker distribution and UI assets before relying on the dev stack:

```bash
./gradlew.bat :modules:indexer-worker:installDist :modules:ui:assemble
```

If AI runtime behavior is part of the investigation, also verify model files, native runtime availability, and GPU/runtime prerequisites with the project-specific preflight scripts before drawing conclusions from failures.

Operational checks that are still worth doing before longer investigations:

| Area | Check |
|------|-------|
| Worker distribution | `modules/indexer-worker/build/install/indexer-worker/` should exist after the Gradle command above. |
| UI assets | `modules/ui-web/dist/` should exist when testing packaged/static UI behavior. |
| Models | Online LLM paths and Worker ONNX encoder assets must match the current settings/model manifest. Do not assume old GGUF embedding paths. |
| Runtime variant | CPU-only online runtime is valid but slow; GPU behavior requires a GPU-capable runtime variant and matching configuration. |
| Dev data | The default dev data directory is `modules/ui-web/.dev-data`; stale indexes there can hide ingestion/search changes. |

## Predefined JSON Endpoints

`justsearch.dev.fetch_api_json` accepts endpoint keys for common diagnostics. Current keys include:

| Key | Endpoint |
|-----|----------|
| `status` | `/api/knowledge/status` |
| `health` | `/api/health` |
| `effective_config` | `/api/config/effective` |
| `debug_state` | `/api/debug/state` |
| `policy_effective` | `/api/policy/effective` |
| `inference_status` | `/api/inference/status` |
| `gpu_capabilities` | `/api/gpu/capabilities` |
| `ui_ready` | `/api/ui/ready` |
| `ai_runtime_status` | `/api/ai/runtime/status` |

Prefer these keys over generic URL calls when they cover the diagnostic need.

## Generic API Calls

`justsearch.dev.api_call` is intentionally allowlisted. It is for Local API calls that are useful for development and safe enough for agent workflows.

Important allowlisted areas include:

| Area | Representative endpoints |
|------|--------------------------|
| Settings and preview | `GET/POST /api/settings/v2`, `GET /api/preview` |
| Index roots and indexing | `GET/POST/DELETE /api/indexing/roots`, `POST /api/indexing/reindex`, `POST /api/indexing/excludes/apply` |
| Index migration and GC | `POST /api/indexing/migration/start`, `cutover`, `rollback`, `pause`, `resume`, `POST /api/indexing/gc` |
| Inference runtime | `GET /api/inference/status`, `POST /api/inference/mode`, `POST /api/inference/reload` |
| Worker/offline control | `POST /api/worker/restart`, `POST /api/offline/process` |
| AI install/runtime/packs | `GET/POST /api/ai/install/*`, `GET/POST /api/ai/runtime/*`, `GET/POST /api/ai/packs/*` |
| Policy and diagnostics | `GET /api/policy/validate`, policy user allowlist calls, `POST /api/diagnostics/export` |
| Knowledge/debug/telemetry | `GET /api/knowledge/status`, `GET /api/debug/events`, `GET /api/debug/worker-log`, `GET /api/telemetry/health` |

When an endpoint is not allowlisted, update the dev MCP implementation and this reference together instead of bypassing the tool.

## Search, Ingest, and Evidence

- Use `justsearch.dev.search_query` for search checks instead of constructing search requests manually.
- Use `justsearch.dev.ingest` for indexing targeted paths during dev investigations.
- Use `justsearch.dev.capture_evidence` and `justsearch.dev.validate_evidence` when a task needs a durable evidence bundle, especially before changing retrieval, indexing, or evaluation behavior.

## AI Runtime Tools

- Use `justsearch.dev.ai_activate` when an investigation requires the online local AI runtime.
- Use `justsearch.dev.reload` after backend changes. It reports whether hot swap worked and whether structural changes require a restart.
- Do not treat embedding readiness and online LLM readiness as the same thing. Embeddings are Worker-side; online chat/QA uses the app inference runtime.

## Start-Tool Error Codes

The `justsearch.dev.start` tool's admission gate can refuse to launch with one of four error codes (see tempdoc 271 + 542 for the ownership and operation-lease models):

| Code | Cause | Resolution |
|------|-------|------------|
| `OWNER_CONFLICT` | Another session holds a fresh lease on the stack; takeover policy is `deny` (the default). | Inspect `quick_health.ownership.holder`. With user approval, retry with `takeover: "warn"`. |
| `HANDSHAKE_REQUIRED` | The holder is running a `MUST_COMPLETE` op-lease (migration, bulk-reindex, index GC, etc.); `warn` takeover is upgraded to a sync handshake. Response includes `criticalOps[]`. | Wait for the op to complete (use the per-op `expectedDurationSec` to estimate), or escalate to `takeover: "force"` with user approval (records a `forcibly_interrupted_critical_op` disposition in the stop-report). |
| `REQUIRES_CONFIRMATION` | A `force` takeover hit an `UNSAFE_TO_INTERRUPT` op-lease. | Pass `--confirm-interrupt=<opId>` matching one of the `criticalOps[].opId` values in the response. The typed token guards against typo'd reclaims of unsafe-to-interrupt ops. |
| `RUN_NOT_FOUND` / `NO_API_URL` | The active run record references a runId that no longer exists or has no `apiBaseUrl`. | Call `quick_health` to re-orient; the run may have partially failed. |

`quick_health.ownership.opLeases[]` (added tempdoc 542) surfaces the active critical op-leases on the holder so an agent can see what would be interrupted before requesting takeover.

## Shared-stack ownership & coordination (multi-agent worktrees)

Only one dev stack runs at a time (memory/port). The dev-runner tracks ownership in `tmp/dev-runner/active.json` (lease-based). Before starting, call `quick_health`; if a stack is running, its response carries `ownership.holder` + `ownership.verdict` + `ownership.recommendedAction` from one authority — act on the verdict rather than inferring from raw lease fields (tempdoc 606):

- `TAKEOVER_ABANDONED` — the owning session went silent; `start` self-serve-proceeds (no user prompt needed).
- `IDLE_HOLD` — the owner is alive but idle; the response recommends `takeover: "warn"`, self-authorizable without a user round-trip.
- `CONTENTION` — the owner is actively using the stack: the genuine ask-the-user case (the `OWNER_CONFLICT` error above). A `force` takeover needs explicit user direction.
- `acquire_when_free` blocks until the stack is acquirable and returns a `recommendedTakeover` — it replaces the conflict → ask → manual-retry loop.
- `ownership.provenance` + `rebuildFirst` flag when the running stack was built from a different worktree/commit than yours; `start { distFrom: "<worktree>" }` launches your own code on the one shared lease.
- `ownership.displacedNotice` surfaces at your next call if a stack you previously owned was taken over while you were away.

A stack abandoned past a grace period is reaped automatically (the supervisor self-terminates), so a long-gone session stops holding VRAM/ports. Stop the stack when you finish so other agents can use it.

## Live-validate a worktree's frontend (FE-only work)

To see *this worktree's* FE in a browser without starting your own stack, borrow the running backend (read-only) and serve the worktree's Vite:
```
node scripts/dev/serve-worktree-fe.cjs   # picks a free port, auto-detects the running backend
```
It serves from the worktree's `modules/ui-web` (the served code is the worktree's by construction) and prints the branch + backend it bound to — the sanctioned path for the contention/port/wrong-code frictions in tempdoc 618 §7 (no `start` needed, so it works even when another session owns the stack).

## Troubleshooting

- If startup is slow, check `justsearch.dev.tail_log` and retry `justsearch.dev.quick_health` before assuming the stack is broken.
- If a UI/API check fails, compare `quick_health`, `status`, and relevant predefined JSON endpoint output.
- If a generic API call is rejected, the endpoint is outside the dev MCP allowlist.
- If hot reload reports `structuralChangeDetected`, stop/start the dev stack instead of continuing to rely on hot swap.
- If search results look stale after field/catalog changes, reset or rebuild the dev index instead of debugging query behavior first.
- If AI activation fails, separate online runtime readiness from Worker encoder readiness; they use different processes and lifecycle controls.
