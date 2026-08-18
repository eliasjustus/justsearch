---
title: MCP Dev Tools Reference
type: reference
status: stable
description: "Dev orchestration tools for starting, monitoring, and verifying the JustSearch dev stack."
---

# MCP Dev Tools Workflow

The `justsearch-dev-mcp` server is the agent-facing control surface for the local development stack. It wraps the dev runner, selected Local API calls, search/ingest helpers, and AI runtime toggles.

This file is the **canonical inventory** of that surface: the tool list, the `fetch_api_json` endpoint keys, and the `api_call` allowlist. `scripts/ci/check-dev-mcp-doc-sync.mjs` asserts all three against the running server, so a drift between this page and `scripts/dev/justsearch-dev-mcp/server.mjs` fails the build instead of quietly misleading an agent (tempdoc 844 §6.3 measured four inventories, all wrong at once).

For the operational side — shared-stack ownership and contention, worktree FE serving, troubleshooting — load the `/dev-stack` skill. It links here rather than restating the inventory.

## Available Tools

The dev MCP surface exposes exactly these **12** tools:

| Tool | Purpose |
|------|---------|
| `justsearch.dev.start` | Start the backend and frontend dev stack. Readiness waiting is part of this tool via its wait options. |
| `justsearch.dev.stop` | Stop the active dev run and clean up owned processes. |
| `justsearch.dev.quick_health` | Fast orientation check for run/API/worker health, plus `foreignRuns` — backends it did not start. `detail: "full"` adds the dev-runner process/port/readiness payload. |
| `justsearch.dev.preflight` | Run dev preflight checks before heavier workflows. Takes `distFrom` so the dist checks run against the tree `start` will launch from. |
| `justsearch.dev.acquire_when_free` | Block until the shared stack is acquirable, then return how to take it (the documented remedy for `OWNER_CONFLICT`). |
| `justsearch.dev.tail_log` | Read recent backend, frontend, or runner log lines. |
| `justsearch.dev.fetch_api_json` | Fetch predefined JSON endpoints by key. |
| `justsearch.dev.api_call` | Call allowlisted Local API endpoints with explicit method/path/body. |
| `justsearch.dev.search_query` | Execute `POST /api/knowledge/search`. |
| `justsearch.dev.ingest` | Execute `POST /api/knowledge/ingest`. |
| `justsearch.dev.ai_activate` | Activate the online AI runtime. |
| `justsearch.dev.reload` | Trigger backend hot reload and report whether restart is required. |

Legacy underscore-style dev tool names and standalone readiness/listing/suggestion/cleanup tools are obsolete. Agents should use the dotted names above.

**Retired in tempdoc 844 P1** — do not reintroduce these names in tooling or prompts:

| Retired tool | Why | Use instead |
|---|---|---|
| `justsearch.dev.status` | 1 invocation in six weeks vs 61 for `quick_health`, whose description already told agents to prefer it. | `quick_health { detail: "full" }` |
| `justsearch.dev.agent_chat` | 0 invocations; superseded in practice by browser-driven UI validation and `jseval` harnesses. | `jseval`, or the chat API directly |
| `justsearch.dev.capture_evidence` | 0 invocations; the wrapper crashes on Windows with a libuv fail-fast. | `node modules/ui-web/scripts/capture-evidence-bundle.mjs` |
| `justsearch.dev.validate_evidence` | 0 invocations (its capture counterpart never produced a bundle to validate). | `node scripts/evidence/validate-evidencebundle-v1.mjs <bundleDir>` |

The **EvidenceBundle format itself is live and load-bearing** — only the two MCP wrappers were removed. `scripts/evidence/validate-evidencebundle-v1.mjs` still gates the `installer_verify` job.

## Standard Workflow

1. Orient with `justsearch.dev.quick_health` — the compact readiness check, and the first call after compaction.
   - Add `detail: "full"` when process state, ports, or runner metadata matter; it is the only mode that spawns a subprocess.
   - Read `foreignRuns` before concluding the machine is free. It lists JustSearch-shaped backends this dev-runner did **not** start (a `jseval` backend on `33221`, a bare `runHeadless`, an unattributed llama-server). The tri-state is load-bearing: `[]` = probed and found none, `null` = did not probe (`probe: false`), a non-empty array = these are running and none is the owned run. Ownership verdicts and `running` describe the dev-runner's own run only — before tempdoc 844 that made a "free" verdict precede a 100%-GPU neighbour and contaminated a measurement round.
2. Run `justsearch.dev.preflight` if the stack is not running.
   - Pass the **same** `distFrom` you will pass to `start` (a path, or a bare worktree name). Preflight then checks the dists in the tree `start` will launch from and reports it as `distCheckedRoot`; without it, preflight validated the invoking checkout while `start` used another — a false green.
3. Start the stack with `justsearch.dev.start`.
   - Use the tool's wait options instead of a separate wait-ready tool.
   - `waitTimeoutMs` may need to be higher than the default on cold machines or after clean builds.
   - `chatProfile?: "compact" | "standard"` (tempdoc 842) selects the llama-server chat model pair delivered as `JUSTSEARCH_CHAT_PROFILE` in the spawn env. Defaults to `compact` — dev stacks run the small dev-tier model unless told otherwise.
   - On `OWNER_CONFLICT`, `justsearch.dev.acquire_when_free` waits for the stack instead of a conflict → ask → manual-retry loop.
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

`justsearch.dev.fetch_api_json` accepts these endpoint keys, and only these. The key -> path mapping is the one in `FETCH_API_ENDPOINT_MAP` (`scripts/dev/justsearch-dev-mcp/server.mjs`), asserted by the doc-sync gate:

| Key | Endpoint |
|-----|----------|
| `status` | `/api/status` |
| `health` | `/api/health` |
| `effective_config` | `/api/debug/effective-config` |
| `debug_state` | `/api/debug/state` |
| `policy_effective` | `/api/policy/effective` |
| `inference_status` | `/api/inference/status` |
| `gpu_capabilities` | `/api/gpu/capabilities` |
| `ui_ready` | `/api/ui/ready` |
| `ai_runtime_status` | `/api/ai/runtime/status` |

Prefer these keys over generic URL calls when they cover the diagnostic need.

### Projection and size limits (tempdoc 844)

Both `fetch_api_json` and `api_call` accept `jsonPath`, sharing one implementation:

- Dot-path with array indices — `"llm.model_path"`, `"results[0].fields.path"`.
- On a **miss** the tool returns `error.code: "JSON_PATH_MISS"` naming the deepest segment that resolved plus `jsonPathAvailable` (the keys, or the array length, at that level), and **withholds the body**. Previously a miss discarded the parsed JSON and returned the raw `textTail` — the largest possible payload as the answer to a one-character typo.
- A malformed expression returns `JSON_PATH_INVALID` rather than silently missing.

`maxBytes` is a **read** budget, not an output budget. Exceeding it now truncates and returns `error.code: "RESPONSE_TRUNCATED"` with `truncated`, `bytesRead`, and `maxBytesLimit`; it used to fail the whole call with `response_too_large`. A truncated body does not parse as JSON, so lowering `maxBytes` cannot shrink a large response — use `jsonPath`, `outputMode: "compact"`, or `summaryOnly` for that. The same notice is emitted by `search_query` and `ingest`.

## Generic API Calls

`justsearch.dev.api_call` is allowlisted: any path not in the table below is refused. This is the complete list, mirroring `API_CALL_ALLOWLIST` (`scripts/dev/justsearch-dev-mcp/server.mjs`) — the doc-sync gate fails if the two diverge in either direction.

| Path | Methods |
|------|---------|
| `/api/settings/v2` | GET, POST |
| `/api/preview` | GET |
| `/api/indexing/roots` | GET, POST, DELETE |
| `/api/indexing-roots/substrate` | GET |
| `/api/indexing-roots/preview` | POST |
| `/api/indexing-jobs/failed/by-prefix` | GET |
| `/api/indexing/reindex` | POST |
| `/api/indexing/excludes/apply` | POST |
| `/api/indexing/migration/start` | POST |
| `/api/indexing/migration/cutover` | POST |
| `/api/indexing/migration/rollback` | POST |
| `/api/indexing/migration/pause` | POST |
| `/api/indexing/migration/resume` | POST |
| `/api/indexing/gc` | POST |
| `/api/inference/status` | GET |
| `/api/inference/mode` | POST |
| `/api/inference/reload` | POST |
| `/api/worker/restart` | POST |
| `/api/ai/install/status` | GET |
| `/api/ai/install/start` | POST |
| `/api/ai/install/cancel` | POST |
| `/api/ai/install/repair` | POST |
| `/api/ai/runtime/status` | GET |
| `/api/ai/runtime/activate` | POST |
| `/api/ai/runtime/deactivate` | POST |
| `/api/ai/packs/status` | GET |
| `/api/ai/packs/installed` | GET |
| `/api/ai/packs/preflight` | POST |
| `/api/ai/packs/import` | POST |
| `/api/policy/validate` | GET |
| `/api/policy/user/create` | POST |
| `/api/policy/user/allowlist/pack-manifest/add` | POST |
| `/api/diagnostics/export` | POST |
| `/api/knowledge/status` | GET |
| `/api/debug/events` | GET |
| `/api/debug/worker-log` | GET |
| `/api/telemetry/health` | GET |
| `/api/action-ledger` | GET |

When an endpoint is not allowlisted, update the dev MCP implementation and this table together instead of bypassing the tool. (Whether the allowlist should exist at all is an open question — tempdoc 844 §11.5 argues a control with a sanctioned `curl` bypass is a tax rather than a control. Until that is decided, this table documents what is, not what should be.)

## Search and Ingest

- Use `justsearch.dev.search_query` for search checks instead of constructing search requests manually.
- Use `justsearch.dev.ingest` for indexing targeted paths during dev investigations. Paths must be under the repo root.

## AI Runtime Tools

- Use `justsearch.dev.ai_activate` when an investigation requires the online local AI runtime. It takes an optional `chatProfile?: "compact" | "standard"` (tempdoc 842) — activation is when llama-server spawns, so it's the switch point for changing chat model pair; measured switch cost is single-digit seconds either direction.
- **The chat model is also runtime-configurable by explicit path — no installer pack-import or `-D` restart needed.**
  `POST /api/settings/v2` with `{"llm":{"modelPath":"<gguf>","gpuLayers":99}}`, then `ai_activate`. An explicit
  path is operator-owned and wins over the profile (tempdoc 842 precedence); prefer `chatProfile` unless you
  need a model outside the registry pairs.
- Use `justsearch.dev.reload` after backend changes. It reports whether hot swap worked and whether structural changes require a restart.
- Do not treat embedding readiness and online LLM readiness as the same thing. Embeddings are Worker-side; online chat/QA uses the app inference runtime.
- `justsearch.dev.quick_health` reports `aiActive` (real tri-state: `true`/`false` for a reachable stack, `null` when unreachable) plus a `model` block (`chatProfile`, `modelPath`) when the runtime reports realized chat identity, and a declared `freshness` block (tempdoc 637) aggregating build/index/binding/lock staleness sources.

## Start-Tool Error Codes

`justsearch.dev.start` can refuse to launch with one of these codes (see tempdoc 271 + 542 for the ownership and operation-lease models). The first four are admission-gate refusals; the last two are pre-launch refusals about the checkout being launched from:

| Code | Cause | Resolution |
|------|-------|------------|
| `OWNER_CONFLICT` | Another session holds a fresh lease on the stack; takeover policy is `deny` (the default). | Inspect `quick_health.ownership.holder`. With user approval, retry with `takeover: "warn"` — or call `acquire_when_free` and act on its `recommendedTakeover`. |
| `HANDSHAKE_REQUIRED` | The holder is running a `MUST_COMPLETE` op-lease (migration, bulk-reindex, index GC, etc.); `warn` takeover is upgraded to a sync handshake. Response includes `criticalOps[]`. | Wait for the op to complete (use the per-op `expectedDurationSec` to estimate), or escalate to `takeover: "force"` with user approval (records a `forcibly_interrupted_critical_op` disposition in the stop-report). |
| `REQUIRES_CONFIRMATION` | A `force` takeover hit an `UNSAFE_TO_INTERRUPT` op-lease. | Pass `--confirm-interrupt=<opId>` matching one of the `criticalOps[].opId` values in the response. The typed token guards against typo'd reclaims of unsafe-to-interrupt ops. |
| `RUN_NOT_FOUND` / `NO_API_URL` | The active run record references a runId that no longer exists or has no `apiBaseUrl`. | Call `quick_health` to re-orient; the run may have partially failed. |
| `DIST_NOT_BUILT` | The checkout being launched from has no Head dist (`modules/ui/build/install/ui/bin/ui.bat`) — typically a fresh worktree, or `skipBuild: true` without a prior `installDist`. `error.details` carries `distPath`, `repoRoot`, and `remedy`. | `node scripts/dev/prepare-worktree.cjs` in that checkout, or `./gradlew.bat :modules:ui:installDist :modules:indexer-worker:installDist`. Run `preflight { distFrom }` with the same value first — it checks the dists in the tree `start` will use. |
| `INVALID_DIST_FROM` | `distFrom` is neither the main repo nor a sibling worktree under `.claude/worktrees`, or that checkout has no `scripts/dev/dev-runner.cjs`. A **bare worktree name** (`"round14"`) is resolved against `.claude/worktrees/<name>`; when no such directory exists the message lists the names that do. | Pass a worktree name, a path to a sibling worktree, or the main repo root. |

Before tempdoc 844, the missing-dist case returned `UNHANDLED` — a fully-understood, recoverable condition classified as an unhandled exception on 16 of 20 measured `start` errors. It is now classified at the layer that detects it (`scripts/dev/dev-runner.cjs`), so severity and code match reality.

`quick_health.ownership.opLeases[]` (added tempdoc 542) surfaces the active critical op-leases on the holder so an agent can see what would be interrupted before requesting takeover.

The ownership/contention model those codes belong to — verdicts, leases, takeover policy, `distFrom`, campaign-length holds — lives in the `/dev-stack` skill, which is also where troubleshooting and worktree-FE serving live.
