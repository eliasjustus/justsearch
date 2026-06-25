---
title: "MCP Dev Tools — Critical Analysis & Improvements"
type: tempdoc
status: done
created: 2026-03-03
---

> NOTE: Noncanonical doc (implementation tracker). May drift. Verify against code.

# MCP Dev Tools — Critical Analysis & Improvements

## Context

The `justsearch-dev-mcp` server (`scripts/dev/justsearch-dev-mcp/server.mjs`, ~1950 lines)
provides 14 tools (was 16 before Step 1) for agent interaction with the dev stack. This
tempdoc captures a critical analysis of the MCP tooling from the agent's perspective — what
works, what doesn't, and what the real cost/benefit tradeoffs are.

Key files:
- `scripts/dev/justsearch-dev-mcp/server.mjs` — tool registration + HTTP helpers
- `scripts/dev/justsearch-dev-mcp/schemas.mjs` — Zod v4 input/output schemas
- `scripts/dev/justsearch-dev-mcp/cli.mjs` — dev-runner process spawning
- `scripts/dev/justsearch-dev-mcp/paths.mjs` — path resolution + loopback enforcement
- `scripts/dev/justsearch-dev-mcp/files.mjs` — file I/O helpers
- `scripts/dev/dev-runner.cjs` — underlying lifecycle supervisor

---

## Research Context

External research conducted on the MCP protocol, Claude Code's MCP integration, production
server design patterns, and verified against the actual installed SDK.

### Protocol capabilities (MCP spec 2025-06-18)

- **`notifications/tools/list_changed`** — spec supports dynamic tool sets at runtime.
  **However, Claude Code's implementation is broken** (issue #4118, 64+ reactions, assigned
  but open). Dynamic toolsets are off the table until this is fixed.

- **Tool annotations** — `readOnlyHint`, `destructiveHint`, `idempotentHint`,
  `openWorldHint` are spec-level hints. **Claude Code uses `readOnlyHint: true` to gate
  parallel tool execution.** Without it, all MCP calls default to sequential.

- **Server `instructions` field** — passed via `ServerOptions` in `McpServer` constructor.
  Claude Code injects into system prompt since v1.0.53 (#3312). **Survives compaction**
  (system prompt is re-sent on every API call). Not currently used.

- **MCP Resources** — agent cannot proactively read them (#11054), subscriptions not
  implemented (#7252). Not useful for agent-initiated data fetches.

- **MCP Prompts** — invisible to the agent (#11054). Cannot solve post-compaction recovery.

- **`structuredContent` + `outputSchema`** — SDK 1.27.1 supports in `registerTool()`.

### SDK verification (installed versions)

- **`@modelcontextprotocol/sdk` 1.27.1** — supports annotations, outputSchema, instructions
- **`McpServer` constructor:** `new McpServer(info, { instructions: '...' })` confirmed
- **`registerTool()` config:** `{ description, inputSchema, outputSchema, annotations }`
- **`ToolAnnotations`:** `readOnlyHint`, `destructiveHint`, `idempotentHint`,
  `openWorldHint`, `title`

### Zod-to-JSON-Schema behavior (experimentally verified)

All findings below confirmed by running actual SDK code (not just source analysis).

- **Zod v4 path used** (not `zod-to-json-schema` library). SDK detects Zod v4 schemas and
  uses `z4mini.toJSONSchema()` directly.

- **`z.string().uuid()` emits BOTH `format: "uuid"` AND the full regex pattern:**
  ```json
  {"type":"string","format":"uuid","pattern":"^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-...)$"}
  ```
  The UUID regex is ~55 tokens per field, ~660 tokens across 12 tools.

- **`z.string().meta({ format: 'uuid' })` emits ONLY `format`:**
  ```json
  {"type":"string","format":"uuid"}
  ```
  This is the clean replacement — produces the format hint the model needs without the
  verbose regex. Trade-off: loses Zod-level `.uuid()` runtime validation. Acceptable for
  `runId` since the server validates UUIDs via `resolveRunId()` / `safeReadRunJson()`.

- **`$ref` only deduplicates within a single tool's schema.** Each `registerTool()` call
  converts `inputSchema` independently. Cross-tool dedup is not possible.

- **`z.discriminatedUnion()` is BLOCKED for inputSchema.** Confirmed experimentally:
  `normalizeObjectSchema()` drops it to `{ type: 'object', properties: {} }` on the wire.
  The Zod v4 native converter does produce valid `oneOf` JSON Schema, but the SDK's
  wrapper discards it. **Composite tools must use flat `.object()` schemas.**

### Annotations and instructions (experimentally verified)

- **Annotations appear correctly on the wire.** `tools/list` returns:
  ```json
  {"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}
  ```

- **`instructions` field delivered in `initialize` response:**
  ```json
  {"protocolVersion":"2025-11-25","capabilities":{...},"serverInfo":{...},
   "instructions":"WORKFLOW: quick_health -> start -> search -> stop"}
  ```
  Client stores as `client._instructions`, accessible via `client.getInstructions()`.

- **Claude Code injects `instructions` into the system prompt** (confirmed since v1.0.53,
  July 2025; issue #3312). Format: "MCP Server Instructions: The following MCP servers
  have provided instructions... - <server-name>: <text>". The system prompt is re-sent
  on every API call and **survives compaction**. With Tool Search active, `instructions`
  becomes even more important — it's the model's primary signal about the server when
  tool schemas are deferred.

### Actual token cost measurement

Estimated per-tool token cost based on schema analysis (tool wrapper + description +
JSON Schema properties + constraints + UUID patterns):

| Tool | Tokens | UUID cost | Notes |
|------|--------|-----------|-------|
| `start` | 129 | 0 | Enhanced: +waitLevel, +startTimeoutMs, +waitTimeoutMs |
| `status` | 110 | 55 | |
| ~~`list_runs`~~ | ~~75~~ | ~~0~~ | Dropped in Step 1 |
| ~~`wait_ready`~~ | ~~156~~ | ~~55~~ | Merged into `start` in Step 1 |
| `tail_log` | 198 | 55 | |
| `fetch_api_json` | 206 | 55 | 9-value endpoint enum |
| `api_call` | 218 | 55 | 6 params |
| `search_query` | 231 | 55 | 8 params, largest input schema |
| ~~`suggest`~~ | ~~196~~ | ~~55~~ | Dropped in Step 1 |
| `ingest` | 190 | 55 | |
| `validate_evidence` | 131 | 0 | No runId |
| `capture_evidence` | 259 | 55 | 7 params + scenario regex |
| `stop` | 133 | 55 | Enhanced: optional runId, +clean, +orphan kill |
| `agent_chat` | 280 | 55 | Longest description (71 tokens) |
| `ai_activate` | 194 | 55 | |
| ~~`cleanup`~~ | ~~154~~ | ~~55~~ | Merged into `stop` in Step 1 |
| **Total (pre-Step 1)** | **~2,860** | **~660** | **1.4% of 200k context** |
| **After Step 1** | **~2,435** | **~495** | 4 tools dropped, 2 added (net -425 tokens est.) |
| **After Step 2** | **~1,885** | **0** | UUID patterns stripped (net -550 tokens) |
| **After Step 5** | **~1,885** | **0** | Compact mode default saves per-call, not schema tokens |

**Key insight: total cost is ~2,860 tokens, not 8,000-20,000.** The research estimates
were based on servers with much more verbose descriptions and complex schemas. At 1.4% of
context, this is well below the 10% Tool Search auto-activation threshold.

**UUID patterns accounted for 23% of total cost** (~660 tokens across 12 tools). This was
the single most efficient compression target — **now eliminated** (Step 2).

### Design pattern consensus

- **Anthropic engineering guidance:** "MCP servers are not thin wrappers around your existing
  API." Design tools around what the agent wants to achieve.

- **Recommended tool count:** 5-12 per server. Beyond 15, split into servers.

- **Error messages are recovery prompts.** Every `isError: true` result is injected into
  context. Include what happened, why, and the exact recovery chain.

- **Output optimization:** `response_format` enum (concise/detailed), explicit pagination
  cues, field filtering. Always preserve IDs in compact mode.

- **Discriminated union limitation changes the consolidation calculus.** The composite tool
  approach (A2) must use flat schemas with all params optional + an `action` discriminator.
  This may be worse than separate tools for model comprehension if the parameter sets are
  divergent. GitHub's 4→1 consolidation worked because the tools had similar params. Our
  lifecycle tools (start needs ports/dataDir/clean; stop needs force; status needs nothing)
  have divergent params.

---

## Part A: Structural Problems

### A1. Context cost vs usage frequency mismatch — PARTIALLY RESOLVED

**Severity: Medium (downgraded from High after token measurement)**
**Fix (partial):** UUID patterns stripped (Step 2, -550 tokens). Tool consolidation (Step 1, -425 tokens).
Remaining: A2 consolidation checkbox and Tool Search checkbox are low priority.

16 MCP tools cost ~2,860 tokens total — 1.4% of a 200k context window. This is meaningful
but not the crisis originally estimated (8,000-20,000 range from research averages was
inaccurate for this server).

The agent's core development loop (read/edit/format/compile/test) uses none of these tools.
Dev stack tools are needed ~10-20% of sessions. But at ~2,860 tokens, the idle-session
cost is moderate — roughly equivalent to 40 lines of code.

UUID regex patterns account for 660 tokens (23% of total). Several niche tools add
~500 tokens with near-zero usage:
- `suggest` — 196 tokens, no agent use case identified
- `validate_evidence` — 131 tokens, used once per 20+ sessions
- `list_runs` — 75 tokens, used only when state is confused

**Proposed fix (revised after measurement):**

- [x] **Strip UUID regex patterns.** Replace `z.string().uuid()` with
  `z.string().meta({ format: 'uuid' })`. Experimentally verified: produces
  `{"type":"string","format":"uuid"}` — no regex pattern, saves ~55 tokens per field,
  ~550 tokens total across 10 input schemas (revised down from ~660/12 after Step 1 dropped
  4 tools and added 2 without `runId` inputs). Additionally 16 UUID patterns exist in output
  schemas — currently invisible but would matter if `outputSchema` is enabled (Step 5).
  Trade-off: loses Zod runtime `.uuid()` validation, but `runId` is already validated by
  `resolveRunId()` / `safeReadRunJson()` downstream. *(Step 2)*
- [x] **Drop `suggest` tool** (196 tokens saved, no agent use case). *(Step 1)*
- [x] **Drop `list_runs`** (75 tokens saved, marginal utility). *(Step 1)*
- [ ] **Consolidation (A2) saves ~1,000-1,400 tokens** (roughly half the tool set removed).
  Worth doing for workflow reasons but the token savings alone don't justify the effort.
- [ ] Tool Search (`ENABLE_TOOL_SEARCH=true`) is overkill at 1.4% — the overhead of the
  discovery round-trip per tool category exceeds the savings.

---

### A2. Tools optimize for the wrong workflow

**Severity: High — fundamental misalignment (unchanged)**

The misalignment is real regardless of token cost. The tools are thorough lifecycle
orchestrators, but the agent's actual needs are: "start the stack", "is it ready?",
"search something", "test agent chat", "stop". The 16-tool surface creates selection
confusion and forces multi-step workflows where single calls would suffice.

**Proposed fix (revised after discriminatedUnion finding):**

`z.discriminatedUnion()` is blocked by the SDK (empty schema on wire). Composite tools
must use flat `.object()` schemas. This limits the consolidation approach.

**Revised consolidation strategy — moderate, not aggressive:**

Instead of merging lifecycle tools into one composite `dev_stack` tool (which would have
a flat schema with ~15 optional params and poor model comprehension), keep separate tools
but improve them:

- [x] **Merge `start` + `wait_ready` → enhanced `start`.** Add `waitLevel` param (default
  `ready_worker`). Start internally polls `waitReady()` before returning. Eliminates B3
  (two-step flow). Also added `startTimeoutMs` (default 600s) and `waitTimeoutMs` (default
  60s). *(Step 1)*
- [x] **Merge `stop` + `cleanup` → enhanced `stop`.** Add optional `clean` param
  (`none | soft | hard`, default `none`). Make `runId` optional (auto-resolve active run).
  Eliminates B2. After stop, probes inference port for orphaned llama-server (C2 fix).
  *(Step 1)*
- [x] **Add `quick_health` tool.** Returns `{running, runId, apiPort, uiPort, httpReady,
  workerReady, aiActive}` with optional HTTP probes (`probe` param, default true).
  Filesystem-first — no subprocess spawn. *(Step 1)*
- [x] **Add `preflight` tool.** Checks: `workerDist`, `noStaleRun`, `modelsDir`,
  `noInferenceOrphan`. Answers "am I ready to start?" Scope limited to reliable filesystem
  checks + inference port orphan detection (C2 fix). *(Step 1)*
- [x] **Drop `suggest`** (no agent use case). *(Step 1)*
- [x] **Drop `list_runs`** (marginal utility; agent can use `status`). *(Step 1)*
- [x] **Drop standalone `wait_ready`** (subsumed by enhanced `start`). *(Step 1)*
- [x] **Drop standalone `cleanup`** (subsumed by enhanced `stop`). *(Step 1)*

**Result: 16 → 14 tools** (drop 4, add 2). Net reduction of 2 tools, plus cleaner
workflows. If further consolidation is desired later (e.g., merging evidence tools), it
can be done incrementally.

| Keep (enhanced) | Keep (unchanged) | Drop | Add |
|-----------------|------------------|------|-----|
| `start` (+waitLevel) | `fetch_api_json` | `wait_ready` | `quick_health` |
| `stop` (+clean,optional runId) | `api_call` | `cleanup` | `preflight` |
| | `search_query` | `suggest` | |
| | `ingest` | `list_runs` | |
| | `tail_log` | | |
| | `validate_evidence` | | |
| | `capture_evidence` | | |
| | `agent_chat` | | |
| | `ai_activate` | | |
| | `status` | | |

---

### A3. Tool descriptions don't encode workflow knowledge — RESOLVED

**Severity: Medium — agents lose context after compaction**
**Fix:** All 14 descriptions rewritten + server `instructions` + 6 error messages improved (Step 4).

The tool description for `start` is:
> *"Start the JustSearch dev stack (backend + frontend) via scripts/dev/dev-runner.cjs."*

This tells the agent nothing about prerequisites, next steps, expected duration, or
failure recovery. After compaction, all workflow knowledge is lost.

Error messages have the same problem. `NO_ACTIVE_RUN` says *"Use justsearch.dev.start
first"* — the real recovery chain is: check build -> start -> wait for worker -> retry.

**Proposed fix:**

- [x] **Add server `instructions` field** (A7). Primary mechanism for workflow knowledge.
  Persists in prefix across compaction. *(Step 4)*
- [x] **Enrich per-tool descriptions.** Lead with "when to use" (not "what it does").
  Keep to 1-2 sentences; put parameter docs in schema `describe()`, not description. *(Step 4)*
- [x] **Make error messages full recovery chains.** Pattern: "Cannot search: dev stack not
  running. Recovery: (1) call start, (2) wait for ready_worker, (3) retry." *(Step 4)*

---

### A4. Output verbosity wastes context — RESOLVED

**Severity: Medium — cumulative context drain**
**Fix:** Compact output mode (default), `jsonPath`, `summaryOnly`, `grepPattern` (Step 5).

Every tool call returns a JSON envelope via `toToolResult()` with pretty-printed 2-space
indentation and metadata (`ok`, `runId`, `endpoint`, `url`, `statusCode`).

`fetch_api_json` with `debug_state` returns 200+ lines. `search_query` includes full field
objects per hit. `status` returns more than "is it running? what port?"

**Proposed fix:**

- [x] **Add `outputMode: "full" | "compact"` parameter** to verbose tools. Default `compact`.
  Applied to `fetch_api_json`, `search_query`, `api_call`. *(Step 5)*
- [x] In compact mode: omit `url`, `statusCode`, reduce metadata to `{ok, runId}`. *(Step 5)*
- [x] **For `fetch_api_json`: add `jsonPath` parameter** to extract a subtree
  (e.g., `"llm.model_path"`). Implementation: simple dot-path split
  (`path.split('.').reduce((obj, key) => obj?.[key], data)`) — no dependency needed. *(Step 5)*
- [x] **For `search_query`: add `summaryOnly` boolean** returning `{totalHits, tookMs}`. *(Step 5)*
- [ ] **Use `structuredContent` + `outputSchema`** where supported. *(Deferred — `structuredContent`
  already works via `toToolResult()`, formal `outputSchema` registration deferred.)*

---

### A5. Post-compaction state blindness — MOSTLY RESOLVED

**Severity: Medium — affects every long session**
**Fix:** Server `instructions` include "After compaction: call quick_health" (Step 4).
`quick_health` tool provides cheap re-orientation (Step 1). `NO_ACTIVE_RUN` errors
include recovery chains (Step 4). Remaining: `.claude/rules/compaction-state.md` defense-in-depth.

After compaction, the agent doesn't remember dev stack state. Auto-resolution helps
(most tools resolve active run silently), but `NO_ACTIVE_RUN` errors are cryptic.

**Proposed fix:**

- [x] **Server `instructions`** (A7) should include: "After compaction, call `quick_health`." *(Step 4)*
- [x] **`quick_health` tool** (A2) — cheap orientation call. *(Step 1)*
- [ ] **Continue `.claude/rules/compaction-state.md`** as defense-in-depth.
- [x] **Improve `NO_ACTIVE_RUN` error messages** with full recovery chains (A3). *(Step 4)*

---

### A6. Missing tool annotations — RESOLVED

**Severity: High — silently degrades performance**
**Fix:** All 14 tools annotated with `readOnlyHint`, `destructiveHint`, `idempotentHint`,
`openWorldHint` (Step 3).

Zero tool annotations set. Claude Code uses `readOnlyHint: true` to gate parallel
execution. Empirical study: 11.1% multi-tool messages with annotation vs 6.1% without.

**Proposed fix:**

- [x] `readOnlyHint: true` on: `status`, `tail_log`, `fetch_api_json`,
  `search_query`, `validate_evidence`, `capture_evidence`, `agent_chat`,
  `quick_health`, `preflight` *(Step 3)*
- [x] `readOnlyHint: false, destructiveHint: false` on: `start`, `ai_activate`, `api_call` *(Step 3)*
- [x] `readOnlyHint: false, destructiveHint: true` on: `stop`, `ingest` *(Step 3)*
- [x] `idempotentHint: true` on: `status`, `fetch_api_json`, `search_query`,
  `quick_health`, `preflight` *(Step 3)*
- [x] `openWorldHint: false` on all tools *(Step 3)*

---

### A7. No server `instructions` field — RESOLVED

**Severity: Medium — workflow knowledge has no persistent home**
**Fix:** `instructions` field added to `McpServer` constructor (~150 words) (Step 4).

The `instructions` field persists in the cached prefix across compaction. Currently unused.

**Proposed fix:**

- [x] Add `instructions` to `McpServer` constructor. Content (~150 words):
  - Tool set overview (14 tools): lifecycle, orientation, data, agent, AI runtime, monitoring, evidence
  - Workflow: `quick_health` → `preflight` (if not running) → `start` → use → `stop`
  - Prerequisites: `./gradlew.bat build` must succeed before `start`
  - Durations: 3-5 min cold start, ~30s warm
  - After compaction: call `quick_health` to re-orient
  - Common errors: `NO_ACTIVE_RUN` → start then retry; `RUN_NOT_FOUND` → omit runId
  *(Step 4)*

---

## Part B: Individual Tool Issues

### B1. Start tool timeout chain insufficient for cold starts

**Severity: High — blocks cold-start workflow**

120s timeout vs 3-5 min Gradle cold start. Dev-runner hasn't written `active.json` when
MCP tool times out.

**Fix:** *(Implemented in Step 1)*
- [x] Added `startTimeoutMs` parameter (default 600s — raised from 360s per C1 investigation)
- [x] Passed through to `runCliJson` timeout

---

### B2. `stop` and `cleanup` require explicit `runId`

**Severity: Medium — subsumed by A2**

**Fix:** *(Implemented in Step 1)*
- [x] `runId` optional in `StopInputSchema`, auto-resolves via `resolveRunId()`
- [x] `cleanup` merged into `stop` via `clean` param

---

### B3. Two-step start+wait flow

**Severity: Medium — subsumed by A2**

**Fix:** *(Implemented in Step 1)*
- [x] `waitLevel` added to `start` (default `ready_worker`)
- [x] `waitTimeoutMs` added (default 60s)
- [x] Start returns `readiness` object and `waitReadyTimeout` flag

---

### B4. DevRunnerStartJsonSchema field name mismatch in recovery path — RESOLVED

**Severity: Low → High (was actually a parse crash, not cosmetic)**
**Fix:** Mapped `apiPortActual→apiPort`, `uiPortActual→uiPort`, added `dataDir` (Step 6).

**Proposed fix:**
- [x] Map `apiPortActual` -> `apiPort` and `uiPortActual` -> `uiPort` in recovery

---

### B5. `IngestOutputSchema` requires `error` on success — RESOLVED

**Severity: Low**
**Fix:** Made `error` optional in success branch (Step 6).

**Proposed fix:**
- [x] Make `error` optional in success branch

---

### B6. Agent chat SSE timeout is inactivity-based — RESOLVED

**Severity: Low**
**Fix:** Added `totalTimeoutMs` with `setTimeout` wrapper. Clarified `timeoutMs` description (Step 6).

**Proposed fix:**
- [x] Document that `timeoutMs` is inactivity timeout
- [x] Added `totalTimeoutMs` with separate `setTimeout()`

---

### B7. search_query missing pagination cursor — RESOLVED

**Severity: Low**
**Fix:** Added `cursor` to input schema and POST body (Step 6).

**Proposed fix:**
- [x] Add `cursor: z.string().optional()` to input schema
- [ ] Append explicit text cue when `nextCursor` present

---

### B8. Worktree state directory sharing — RESOLVED

**Severity: Low**
**Fix:** Added `repoRoot` to `run.json` in dev-runner.cjs (Step 6).

**Proposed fix:**
- [x] Include `repoRoot` in `run.json` for collision detection

---

### B9. `fetch_api_json` jsonPath error crashes output schema validation (NEW)

**Severity: Medium — found during live testing (2026-03-04)**

When `jsonPath` resolves to `undefined`, `jsonOk` becomes `false` and `ok` becomes `false`,
but no `error` object is provided. The `ok: false` branch of `FetchApiJsonOutputSchema`
requires `error: ToolErrorSchema`. Result: Zod parse crash, MCP tool returns raw validation
error instead of a useful message.

**Fix:** Added `jsonPathError` with descriptive message ("jsonPath X resolved to undefined.
Check the path against the full response."). Propagated through `effectiveError` to the
schema parse.

- [x] Add error object when jsonPath extraction fails (commit `4d907d0c`)

---

### B10. `stop` cannot kill orphans without active run — RESOLVED

**Severity: Medium — found during live testing (2026-03-04)**
**Fix:** Extracted `probeAndKillInferenceOrphan()` helper. `stop` now calls it before
returning `NO_ACTIVE_RUN`. Returns `{ok: true, inferenceOrphanKilled: <pid>}` when orphan
killed (commit `4d907d0c`).

When an orphaned llama-server holds port 8080 but no `active.json` exists, `stop` returns
`NO_ACTIVE_RUN` without attempting orphan cleanup. `preflight` correctly detects the orphan
but can only report it. The agent must fall back to manual PowerShell commands.

This is the most common orphan scenario — crashes and hard kills leave llama-server running
but destroy the active run tracking.

**Proposed fix:**
- [x] Extract orphan probe into `probeAndKillInferenceOrphan()` helper
- [x] Call helper in `!effectiveRunId` early return path
- [x] Simplify post-stop orphan probe to use same helper

---

## Implementation Plan

### Dependency-driven order (revised)

The discriminated union blocker and lower-than-expected token cost change the
implementation strategy. Aggressive consolidation (16→6) is no longer justified.
The plan is now moderate consolidation (16→14) + quality improvements across all tools.

A2 (tool consolidation) still goes first because A6 (annotations), A7 (instructions),
A3 (descriptions), and A4 (output modes) all apply to whatever tools exist afterward.
But the consolidation is smaller and lower-risk.

**Branch:** `feat/254-mcp-dev-tools-improvements` (worktree at `../JustSearch-wt/mcp-improvements`)

### Step 1: Moderate tool consolidation (A2) — COMPLETE

Merge `start`+`wait_ready`, merge `stop`+`cleanup`, add `quick_health`+`preflight`,
drop `suggest`+`list_runs`+`wait_ready`+`cleanup`. Net: 16→14 tools. Subsumes B1, B2, B3.
Also implements C1 (600s timeout), C2 (orphan detection), C3 (port-based access via `apiPort`).

**Commit:** `feat(254): MCP tool consolidation — 16 tools to 14` on branch
`feat/254-mcp-dev-tools-improvements` (worktree `../JustSearch-wt/mcp-improvements`).

**Verification:** Module imports clean, MCP smoke test confirms 14 tools, harness updated.

#### Step 1 design decisions (resolved)

**Timeout strategy for enhanced `start`:** Two separate params, not one combined.
- `startTimeoutMs` (default `600_000`) — how long to wait for dev-runner to emit first JSON.
  Replaces the hardcoded `120_000` (B1/C1 fix). 600s covers worst-case Windows cold starts
  with antivirus scanning.
- `waitTimeoutMs` (default `60_000`) — how long to poll readiness after start succeeds.
- `waitLevel` (default `'ready_worker'`) — reuses existing `ReadyLevelSchema`.
- If wait times out, return `ok: true` with `waitReadyTimeout: true` — the stack started,
  it just isn't ready yet.

**Cleanup in enhanced `stop`:** Two sequential subprocesses, not one combined.
- First: `buildDevRunnerArgsStop` + `runCliJson` (oneshot, 45s).
- Then (if `clean !== 'none'`): `buildDevRunnerArgsCleanup` + `runCliJson` (oneshot, 60s).
- The CLI commands are separate subcommands — cannot be combined in one invocation.
- Result merges both: `stopOut.cleanup = { ok, ... }`.

**`quick_health` HTTP probes:** Two checks when `probe: true` (default):
- `httpGetStatusCode(apiBaseUrl + '/api/status', 1200)` → `httpReady`
- `httpGetStatusCode(apiBaseUrl + '/api/health', 1200)` → `workerReady`
- `aiActive`: always `null` in Step 1 (no reliable source in `run.json`). Defer to later.
- No subprocess spawn — entirely filesystem + optional HTTP. Much faster than `status` tool.

**`preflight` semantics:** "Am I ready to start?"
- `workerDist`: `fsp.lstat()` on `modules/indexer-worker/build/install/indexer-worker/bin/indexer-worker`
  (also `.bat` on Windows).
- `noStaleRun`: read `active.json`, if exists check PIDs via `process.kill(pid, 0)`.
  All PIDs dead → stale. No `active.json` → clean.
- `modelsDir`: `fsp.readdir('models/')` non-empty.
- `details` record gives human-readable per-check explanation.

#### Step 1 file-level changes (implemented)

**`schemas.mjs`:**
- Extend `StartInputSchema` (line 17): add `waitLevel`, `startTimeoutMs`, `waitTimeoutMs`
- Extend `StopInputSchema` (line 34): make `runId` optional, add `clean: CleanModeSchema`
- Add `QuickHealthInputSchema` / `QuickHealthOutputSchema`
- Add `PreflightInputSchema` / `PreflightOutputSchema`
- Remove: `WaitReadyInputSchema`, `WaitReadyOutputSchema`, `CleanupInputSchema`,
  `SuggestInputSchema`, `SuggestOutputSchema`, `ListRunsInputSchema`,
  `ListRunsOutputSchema`, `RunSummarySchema`
- Keep `DevRunnerCleanupJsonSchema` (needed by enhanced `stop`)

**`server.mjs`:**
- Update imports (lines 9-58): drop removed schemas, add new ones
- Update `safeReadRunJson` error msg (line 330): s/list_runs/status/
- Add `resolveApiBaseUrl(repoRoot, { runId, apiPort })` helper — shared by all HTTP-only
  tools (C3 fix). If `apiPort` given → `http://127.0.0.1:${apiPort}`. If `runId` →
  read run.json → `apiBaseUrl`. Replaces per-tool `safeReadRunJson` + apiBaseUrl extraction.
- Enhanced `start` (lines 345-404): parse new params, `startTimeoutMs` default 600s (C1),
  pass to `runCliJson`, call `waitReady()` after success, embed readiness in result
- Remove handlers: `list_runs` (447-550), `wait_ready` (552-585), `suggest` (956-1042),
  `cleanup` (1914-1942)
- Enhanced `stop` (lines 1346-1372): optional `runId` via `resolveRunId()`, sequential
  cleanup subprocess when `clean !== 'none'`, then probe inference port and kill orphaned
  llama-server if detected (C2 fix)
- Add `preflight` handler (before `stop`) — includes inference port orphan detection (C2)
- Add `quick_health` handler (after `stop`, before `agent_chat`)
- Update 8 HTTP-only tool handlers to use `resolveApiBaseUrl` and accept `apiPort` (C3)

**`justsearch-dev-mcp-harness.mjs`:**
- Update tool list assertion (lines 155-168): 14 expected tools + negative assertion on 4 dropped
- Update start test (lines 171-184): pass `waitLevel: 'ready_http'`, timeout 440s
- Remove `wait_ready` test (lines 186-200)
- Remove `list_runs` test (lines 218-228)
- Add `preflight` smoke test (before start): assert well-formed response
- Add `quick_health` smoke test (after start): assert `running: true`
- Update `stop` test (lines 435-443): no explicit `runId` (test auto-resolve)

#### Step 1 reusable infrastructure (implemented)

| Function | Location | Used by |
|----------|----------|---------|
| `resolveRunId()` | server.mjs:288 | enhanced stop, quick_health, resolveApiBaseUrl |
| `readRunJson()` | cli.mjs:335 | resolveApiBaseUrl, quick_health, preflight |
| `readJsonFileNoSymlinks()` | files.mjs:38 | quick_health, preflight |
| `httpGetStatusCode()` | server.mjs:60 | quick_health, preflight (orphan probe) |
| `ensureLoopbackUrl()` | paths.mjs:63 | resolveApiBaseUrl, quick_health |
| `toToolResult()` | server.mjs:272 | all new handlers |
| `waitReady()` | server.mjs:243 | enhanced start (internal) |
| `buildDevRunnerArgsStop()` | cli.mjs:353 | enhanced stop |
| `buildDevRunnerArgsCleanup()` | cli.mjs:366 | enhanced stop (cleanup phase) |
| `coerceExitAwareOk()` | cli.mjs:372 | enhanced stop |

New helper (implemented):
- `resolveApiBaseUrl(repoRoot, { runId, apiPort })` — consolidates run-state-to-URL
  resolution for all 8 HTTP-only tools (C3)

### Step 2: UUID pattern stripping (A1) — COMPLETE

Replace `z.string().uuid()` with `z.string().meta({ format: 'uuid' })` in all 26 instances
(10 in input schemas, 16 in output schemas). Saves ~550 tokens in input schemas.

**Commit:** `feat(254): strip UUID regex patterns` on branch
`feat/254-mcp-dev-tools-improvements`.

**Verification:** Grep confirms 0 remaining `.uuid()` instances. MCP smoke test confirms
all 10 input schemas emit `format: "uuid"` without regex patterns.

#### Step 2 file-level changes (implemented)

**`schemas.mjs`:** Single `replace_all` of `z.string().uuid()` → `z.string().meta({ format: 'uuid' })`
across all 26 instances (10 input, 16 output schemas).

### Step 3: Annotations (A6) — COMPLETE

Added annotations to all 14 `registerTool()` config objects, grouped into 4 shapes:
- **Group A** (read-only + idempotent): `status`, `fetch_api_json`, `search_query`, `quick_health`, `preflight`
- **Group B** (read-only, not idempotent): `tail_log`, `validate_evidence`, `capture_evidence`, `agent_chat`
- **Group C** (mutating, non-destructive): `start`, `ai_activate`, `api_call`
- **Group D** (mutating, destructive): `stop`, `ingest`

**Commit:** `feat(254): add tool annotations` on branch
`feat/254-mcp-dev-tools-improvements`.

**Verification:** Grep confirms 14 `annotations:` entries in `server.mjs`.

### Step 4: Server instructions + descriptions + error messages (A7, A3) — COMPLETE

- Added `instructions` field to `McpServer` constructor (~150 words): tool categories,
  workflow sequence, prerequisites, durations, post-compaction guidance, error recovery.
- Rewrote all 14 tool descriptions to lead with "when to use" (not "what it does").
- Improved 6 error messages with recovery chains: 3× `NO_ACTIVE_RUN` (→ "call start then
  retry"), 1× `NO_ACTIVE_RUN` in stop (→ "call quick_health to verify"), 1× `RUN_NOT_FOUND`
  (→ "omit runId to auto-resolve"), 1× `NO_API_URL` (→ "call quick_health to check").

**Commit:** `feat(254): add server instructions, rewrite descriptions, improve error messages`
on branch `feat/254-mcp-dev-tools-improvements`.

**Verification:** Module imports clean. Version bumped to `0.2.0`.

### Step 5: Output optimization + log filtering (A4, C4) — COMPLETE

- Added `OutputModeSchema` enum (`full | compact`) to `schemas.mjs`.
- Added `outputMode` (default `compact`) to `fetch_api_json`, `search_query`, `api_call`.
  In compact mode: strips `url`, `statusCode`, and tool-specific metadata.
- Added `jsonPath` to `fetch_api_json` — dot-path extraction via `.split('.').reduce()`.
- Added `summaryOnly` to `search_query` — returns only `{ok, runId, totalHits, tookMs}`.
- Added `grepPattern` to `tail_log` — regex line filter with try/catch for invalid patterns.

**Commit:** `feat(254): output optimization` on branch
`feat/254-mcp-dev-tools-improvements`.

**Verification:** Module imports clean. Grep confirms 3 `outputMode` handlers in `server.mjs`.

#### Step 5 file-level changes (implemented)

**`schemas.mjs`:**
- Added `OutputModeSchema = z.enum(['full', 'compact'])`
- Added `grepPattern: z.string().optional()` to `TailLogInputSchema`
- Added `jsonPath: z.string().optional()` + `outputMode` to `FetchApiJsonInputSchema`
- Added `summaryOnly: z.boolean().optional()` + `outputMode` to `SearchQueryInputSchema`
- Added `outputMode` to `ApiCallInputSchema`

**`server.mjs`:**
- `tail_log` handler: grepPattern filtering after `tailTextFileNoSymlinks()` returns
- `fetch_api_json` handler: jsonPath extraction after JSON parse, compact stripping after output schema validation
- `search_query` handler: summaryOnly stripping (deletes results/facets/cursor/correction/url/statusCode/query),
  else compact stripping (deletes url/statusCode/query)
- `api_call` handler: compact stripping (deletes url/statusCode)

### Step 6: Remaining bug fixes (B4, B5, B6, B7, B8) — COMPLETE

B1 (start timeout) subsumed by Step 1. B2, B3 subsumed by Step 1.

**Commit:** `fix(254): remaining bug fixes B4-B8` on branch
`feat/254-mcp-dev-tools-improvements`.

**File-level changes:**
- `schemas.mjs`: B5 (error optional), B6 (totalTimeoutMs + timeoutMs describe), B7 (cursor)
- `server.mjs`: B4 (recovery field mapping + dataDir), B5 (conditional error spread),
  B6 (consumeAgentSse totalTimeoutMs + setTimeout wrapper), B7 (cursor in POST body)
- `dev-runner.cjs`: B8 (repoRoot in run.json)

### Step 7: Agent chat sampling overrides (C6) — DEFERRED

Deferred to agent evaluation work. The MCP-side schema changes are trivial, but the Java
backend changes (AgentController, AgentRequest, AgentLoopService) touch sensitive agent loop
code and belong with model evaluation improvements, not MCP tooling.

See C6 investigation below for the full 5-layer threading analysis.

### Step 8: GGUF metadata tool (C7) — DROPPED

Not worth the implementation cost. Niche use case (new model evaluation only), and the
existing workaround (Python script) is adequate.

### Step 9: Live testing fixes (B9, B10) — COMPLETE

Found during live smoke testing (2026-03-04):
- B9: jsonPath error crashes output schema validation — fixed
- B10: `stop` can't kill orphans without active run — fixed (extracted helper, early-return probe)

**Commit:** `fix(254): jsonPath error handling + orphan kill without active run (B9, B10)`
on `main` (`4d907d0c`).

**File-level changes:**
- `server.mjs`: B9 (jsonPathError + effectiveError routing), B10 (probeAndKillInferenceOrphan
  helper, early-return orphan probe, simplified post-stop probe)

**Total: ~5 sessions** (Steps 1-6: ~4 sessions, Step 9: ~0.25 sessions).
**Progress: Steps 1-6 and 9 complete.** Steps 7-8 deferred/dropped. Tempdoc complete.

---

## Part C: Agent Lived Experience (2026-03-03)

This section captures friction encountered during actual development sessions — not
theoretical analysis, but what specifically went wrong and wasted context or time. Each
issue references the relevant Part A/B item where applicable.

### C1. Cold-start timeout makes `start` tool unusable (references B1, A2) — RESOLVED

**Impact: High — forces fallback to bash every cold start**
**Fix:** `startTimeoutMs` defaults to 600s in enhanced `start` (Step 1).

The `start` tool has a 120s MCP-level timeout. Gradle cold-starts take 3-5 minutes.
Every time the dev stack isn't already running, the MCP `start` tool is useless. The
agent must fall back to `dag-runner-agent-battery.mjs` (which has its own `--start-timeout-ms`
flag) or raw bash with `./gradlew.bat :modules:ui:runHeadless`. This means the MCP tool
designed for the most common lifecycle operation is the one least likely to succeed.

**What actually happened:** During the Qwen3.5-9B model evaluation sessions, every battery
run used the DAG runner instead of MCP tools because the dev stack was cold. The MCP `start`
tool was never successfully called for a cold start.

**What would fix it:** The enhanced `start` from A2 with `startTimeoutMs` defaulting to 360s.
But even 360s may not be enough — some cold starts hit 5+ minutes on Windows with antivirus
scanning. Consider 600s or making it configurable per-session.

---

### C2. Orphaned processes create invisible GPU contention (not in A/B) — RESOLVED

**Impact: High — causes cascading failures that look like model bugs**
**Fix:** `preflight` detects inference orphans; enhanced `stop` probes inference port and
kills orphaned llama-server via PowerShell (Step 1).

When a battery run fails mid-execution or the agent kills a dev-runner, `llama-server.exe`
can survive and hold 8-14GB of VRAM. The next `start` or `ai_activate` then fails with
cryptic errors ("Failed to apply runtime overrides", "process exited before becoming healthy
(exit code 1)"). There is no MCP tool to detect or kill orphaned GPU processes.

**What actually happened:** During bounded-thinking battery testing, the first attempt failed
because a previous run's llama-server was still holding the GPU. Diagnosing this required:
1. Suspecting GPU contention (not obvious from the error message)
2. Running `powershell Get-Process | Where-Object {$_.ProcessName -like "*llama*"}`
3. Running `Stop-Process -Force` to kill it
4. Retrying the battery

This consumed ~15 minutes and ~2,000 tokens of context on process management that an MCP
tool could have handled in one call.

**What would fix it:** The proposed `preflight` tool (A2) partially addresses this — it
checks for stale `active.json` with dead PIDs. But it doesn't cover llama-server processes
that outlive their parent dev-runner. A `gpu_cleanup` action or an explicit PID kill list
in the `stop` tool would be more complete.

---

### C3. Battery lifecycle is invisible to MCP tools (not in A/B) — RESOLVED

**Impact: Medium — MCP tools become useless during the most complex workflows**
**Fix:** `apiPort` parameter added to 8 HTTP-only tools via `resolveApiBaseUrl` helper.
Allows direct port-based access without `active.json` tracking (Step 1).

When running `dag-runner-agent-battery.mjs`, the battery script manages its own dev-runner
instance internally. The MCP server's `active.json`-based run tracking doesn't see this
process. All MCP tools that require an active run (`status`, `tail_log`, `fetch_api_json`,
`search_query`) fail with `NO_ACTIVE_RUN`.

This creates an inversion: during the most complex debugging sessions (model evaluation,
battery analysis), when MCP tools would be most valuable for introspection, they're
completely unavailable.

**What actually happened:** During battery runs, checking backend state required:
- Reading battery output JSON files directly (via `Read` tool)
- Writing custom comparison scripts (`tmp/compare-batteries.mjs`, `tmp/compare-thinking.mjs`)
- Running bash commands to inspect processes

The MCP `fetch_api_json` with `ai_runtime_status` was useful when the dev stack was running
standalone, but useless during battery runs when the battery controlled the lifecycle.

**What would fix it:** Either:
1. Battery writes a compatible `active.json` that MCP can discover, or
2. MCP tools accept a `port` parameter as an alternative to `runId`, or
3. A dedicated `battery_status` / `battery_results` MCP tool

Option 2 is simplest and most general.

---

### C4. No log filtering wastes context on large outputs (references A4) — RESOLVED

**Impact: Medium — forces repeated large reads or bash grep fallback**
**Fix:** `grepPattern` parameter added to `tail_log` — regex line filter (Step 5).

`tail_log` returns raw log content with no filtering. When checking whether `--reasoning-budget`
was passed correctly to llama-server, or looking for specific error patterns, the agent must
either:
1. Tail a large log and manually scan it (wastes context)
2. Fall back to bash with `grep` on the log file path (which requires knowing the path,
   and the path is inside the MCP run directory structure)

**What actually happened:** Checking llama-server launch arguments required reading the
Java source code (`LlamaServerOps.java`) and the config code rather than just grepping the
backend log for the actual command line. A `grepPattern` parameter on `tail_log` would have
made this a one-call operation.

**What would fix it:** Add `grepPattern: z.string().optional()` to `tail_log`. Apply as a
line filter before returning results. This is a 5-line addition to the existing handler.

---

### C5. Post-compaction disorientation is real and costly (references A5)

**Impact: Medium — burns 200-500 tokens on re-orientation every compaction**

After every compaction, the agent has no idea whether the dev stack is running. The
`.claude/rules/compaction-state.md` file preserves git state but not dev stack state.
The typical recovery sequence is:
1. Call `status` → get `NO_ACTIVE_RUN` error or a stale result
2. Check if processes are actually running via bash
3. Decide whether to start fresh or connect to existing

This is exactly the workflow that `quick_health` (A2) would eliminate. The tool doesn't
exist yet, and the workaround (multiple bash calls) costs more context than the tool would.

---

### C6. `agent_chat` is a black box for model-behavior debugging (not in A/B)

**Impact: Medium — limits agent-level debugging to battery-only workflows**

During the Qwen3.5-9B investigation, a key question was: "Does enabling thinking mode
improve or hurt agent quality?" The `agent_chat` tool runs the full agent loop, but:
- Cannot configure thinking mode (`enable_thinking` / `reasoning_budget`)
- Cannot set `max_completion_tokens` per request
- Cannot specify model path (uses whatever the backend has configured)
- Cannot see raw LLM completions (only final agent response + tool calls)

This meant all thinking-mode experiments required full battery runs (30+ minutes each)
instead of quick single-prompt tests via MCP.

**What would fix it:** Add optional `samplingOverrides` to `agent_chat`:
```
samplingOverrides: { maxTokens?, reasoningBudget?, enableThinking? }
```
These could be passed through to the agent loop's `SamplingParams` without changing the
core agent logic.

---

### C7. No GGUF metadata inspection tool (not in A/B)

**Impact: Low-Medium — rare but painful when needed**

Model debugging sometimes requires inspecting GGUF metadata — particularly the embedded
chat template, which controls tool call format, thinking mode defaults, and system message
positioning. During the Qwen3.5-9B investigation, extracting the chat template required
writing a 60-line Python script (`tmp/read-gguf-template.py`) to parse GGUF binary format.

This is a niche need (only when evaluating new models), but when it's needed, the
alternative is significant: write a binary parser from scratch or search the web for an
equivalent tool.

**What would fix it:** A `model_info` tool that reads GGUF metadata keys from a model file
path. Core implementation is ~40 lines (the binary format is well-documented). Return:
model name, parameter count, quantization, chat template, and vocabulary size.

---

### C8. Error messages don't explain what went wrong (references A3) — RESOLVED

**Impact: Medium — every error requires investigation instead of recovery**
**Fix:** All 6 error messages rewritten with recovery chains (Step 4).

MCP tool errors consistently describe *what failed* without explaining *why* or *how to
recover*. Examples from this session:

| Error | What the agent sees | What actually happened |
|-------|--------------------|-----------------------|
| "Failed to apply runtime overrides" | Generic failure | llama-server couldn't start because GPU was occupied by orphan |
| `PROCESS_EXITED` exit code 1 | Process died | CUDA out-of-memory or port conflict |
| `NO_ACTIVE_RUN` | No run found | Battery manages its own dev-runner, not tracked by MCP |

Each of these required 3-5 investigative tool calls (bash process listing, port checking,
file reading) to diagnose. Error messages with root-cause hints would eliminate most of
this investigation.

---

### Summary: friction taxonomy

| Category | Issues | Root cause | Status |
|----------|--------|------------|--------|
| **Timeout mismatch** | C1 | Tool timeouts don't match real-world durations | **Step 1** |
| **Invisible state** | C2, C3, C5 | MCP can't see processes/state it didn't create | **C2,C3: Step 1; C5: Step 4** (instructions + quick_health) |
| **Missing parameters** | C4, C6 | Tools lack filtering/configuration that agents need | **C4: Step 5**; C6: deferred to agent eval |
| **Missing tools** | C7 | Niche but painful gaps in tool coverage | Dropped |
| **Unhelpful errors** | C8 | Errors describe symptoms, not causes or recovery | **Step 4** |
| **Schema validation** | B9 | jsonPath error path missing required error object | **Step 9** |
| **Orphan lifecycle** | B10 | `stop` requires active run to kill orphans | **Step 9** |

C1 (cold-start timeout), C2 (orphan detection), and C3 (battery visibility via `apiPort`)
are all resolved in Step 1. C4 (log filtering via `grepPattern`) resolved in Step 5.
C5 (post-compaction) addressed by server `instructions` + `quick_health` (Steps 1+4).
C8 (error messages) resolved in Step 4. C6 (`agent_chat` overrides) deferred to agent
evaluation work. C7 (GGUF metadata) dropped. B9 and B10 found during live testing (Step 9).

---

## Part C Investigation Results (2026-03-03)

Codebase investigation to determine the correct fix for each Part C issue.

### C2 investigation: orphan mechanism

llama-server PID lives only in `LlamaServerOps.process` (JVM memory). No PID file.
`stopRun()` in dev-runner.cjs uses `taskkill /PID <backendRootPid> /T /F` which kills
the JVM hard — shutdown hooks don't run, so `LlamaServerOps.stopLlamaServer()` never
executes, and llama-server survives holding GPU VRAM.

Existing infrastructure in dev-runner.cjs:
- `isTcpListening(port)` — TCP connect probe (lines 279-297)
- `getPortOwnerWindows(port)` — PowerShell `Get-NetTCPConnection` → PID (lines 327-347)
- `execPowerShell(command)` — general PowerShell executor (lines 299-311)

Default inference port: 8080 (`InferenceConfig.java` line 84), configurable via
`JUSTSEARCH_SERVER_PORT` env var.

**Correct fix — two integration points:**
1. **`preflight`:** Probe inference port (default 8080) via `httpGetStatusCode()` (already
   in MCP server). If listening, warn: "orphaned llama-server detected on port 8080".
   Detection only — no kill. Report the port and suggest `stop` with cleanup.
2. **Enhanced `stop`:** After killing the backend process tree, probe the inference port.
   If still listening, shell out to PowerShell to get the PID and kill it. The MCP server
   can use `child_process.execFile('powershell', [...])` — or better, add a
   `dev-runner.cjs kill-orphan-inference` CLI command that reuses the existing
   `getPortOwnerWindows()` + `taskkill` helpers.

The inference port must be discoverable. Options:
- Read from `JUSTSEARCH_SERVER_PORT` env var (set by `resolveAiDevEnv()`)
- Hardcode 8080 as fallback (it's the default)
- Store in `run.json` (would require dev-runner change)

### C3 investigation: what tools actually need from run.json

| Category | Tools | What they need |
|----------|-------|---------------|
| **HTTP-only** (need only `apiBaseUrl`) | `fetch_api_json`, `api_call`, `search_query`, `ingest`, `agent_chat`, `ai_activate`, `wait_ready` (→ enhanced `start`) | `apiBaseUrl` string |
| **File-dependent** (need `runId` for paths) | `tail_log` (log path), `capture_evidence` (attachment paths + `apiBaseUrl` + `uiUrl`) | `runId` + optionally `apiBaseUrl` |
| **CLI-driven** (pass `runId` to dev-runner) | `status`, `stop`, `cleanup` | `runId` |
| **No run state** | `start`, `validate_evidence`, `preflight`, `quick_health` | Nothing / filesystem only |

All HTTP helpers (`httpGetStatusCode`, `httpGetTextLimited`, `httpPostJsonLimited`) accept
raw URL strings. `ensureLoopbackUrl()` validates any `127.0.0.1:<port>` URL identically.

**Correct fix — new shared helper:**
```
resolveApiBaseUrl(repoRoot, { runId, apiPort })
  → if apiPort given: return `http://127.0.0.1:${apiPort}`
  → if runId given (or resolved from active.json): read run.json, return apiBaseUrl
  → if neither: return null (NO_ACTIVE_RUN)
```
Add optional `apiPort: z.number().int().positive().optional()` to the 8 HTTP-only tools'
input schemas. File-dependent and CLI-driven tools still require `runId`.

This is cross-cutting but mechanical — every HTTP-only tool already does:
```js
const rr = await safeReadRunJson(repoRoot, input.runId);
const apiBaseUrl = String(rr.runJson?.apiBaseUrl || '').trim();
```
Replace with:
```js
const apiBaseUrl = await resolveApiBaseUrl(repoRoot, { runId: input.runId, apiPort: input.apiPort });
```

### C6 investigation: what needs to change for sampling overrides

Current chain (5 layers, all closed):
1. `AgentChatInputSchema` (`.strict()`) → rejects unknown fields
2. MCP handler body → sends only `{ messages, maxIterations }`
3. `AgentController.handleRunStream()` → reads no sampling fields
4. `AgentRequest` → has no sampling fields
5. `AgentLoopService.callLlmWithTools()` → uses `SamplingParams.AGENT` (temp=0.6,
   top_p=0.95) and `DEFAULT_MAX_TOKENS` (1024) hardcoded

The llama-server call layer **already supports** all parameters:
- `temperature`, `topP` → sent directly in JSON body (`OnlineModeOps.java` line 360)
- `enableThinking` → sent as `chat_template_kwargs.enable_thinking` (line 378)
- `maxTokens` → sent as `max_tokens` (line 352)

**Correct fix — thread through all 5 layers:**
1. `AgentChatInputSchema` — add optional `temperature`, `topP`, `enableThinking`,
   `maxTokens` fields
2. MCP handler — include in POST body
3. `AgentController` — read from request JSON
4. `AgentRequest` — add `SamplingParams samplingOverride`, `Integer maxTokensOverride`
5. `AgentLoopService.resolveAgentSampling()` — merge override with preset

This is a cross-language feature (JS MCP + Java backend). Should be a **separate step**
from Step 1, not folded in.

### C7 (GGUF metadata): deferred

Niche, self-contained tool. Not investigated. Can be added as a standalone tool in any
step without affecting the rest of the plan.

### Impact on implementation plan

| Item | Plan impact | When |
|------|-------------|------|
| C1 (timeout default) | `startTimeoutMs` default 600s | **Step 1 — DONE** |
| C2 (orphan detection) | `preflight` + `stop` probe inference port, kill orphan | **Step 1 — DONE** |
| C3 (`apiPort` param) | `resolveApiBaseUrl` helper + `apiPort` on 8 tools | **Step 1 — DONE** |
| C4 (`grepPattern`) | Add to `tail_log` handler | **Step 5 — DONE** |
| C6 (sampling overrides) | Cross-language feature: MCP + Java backend | **New Step 7** (separate) |
| C7 (GGUF metadata) | New self-contained tool | **New Step 8** or deferred |
| C8 (error messages) | Already covered by Step 4 | **Step 4 — DONE** |

---

All items marked "experimentally verified" were tested by running actual SDK code with
`InMemoryTransport`, not just source analysis.

### High confidence

- **A6 (annotations)** — experimentally verified: annotations appear on wire exactly as
  set. Mechanical work adding `annotations` object to each `registerTool()` call.
- **A1 (UUID stripping)** — experimentally verified: `z.string().meta({ format: 'uuid' })`
  produces clean output. Mechanical find-and-replace in `schemas.mjs`.
- **A7 (instructions)** — experimentally verified: `instructions` string appears in the
  `initialize` response. Claude Code injects it into the system prompt (confirmed since
  v1.0.53, #3312) and it **survives compaction**. Content writing is the effort.
- **B1-B8 (all bug fixes)** — code locations identified, fixes straightforward.
- **A2 enhanced `start`** — adding `waitLevel` and calling `waitReady()` internally.
- **A2 enhanced `stop`** — making `runId` optional, adding `clean` param.
- **A2 drop tools** — removing `registerTool()` calls and schema exports.
- **Flat object schema for any composite tool** — experimentally verified:
  `z.object({ action: z.enum([...]), ...optionalParams }).strict()` produces correct
  wire schema. `z.discriminatedUnion()` does NOT (empty schema on wire).

### Medium confidence → now resolved

- **A2 `quick_health`** — **Implemented in Step 1.** Filesystem-first with optional HTTP
  probes (`probe: true` default). Returns `{running, runId, apiPort, uiPort, httpReady,
  workerReady, aiActive, inferenceOrphan}`.

- **A2 `preflight`** — **Implemented in Step 1.** Checks `workerDist`, `noStaleRun`,
  `modelsDir`, `noInferenceOrphan`. Build freshness excluded (unreliable).

- **A4 `jsonPath`** — simple dot-path split covers the use cases. No edge cases expected
  for the `fetch_api_json` endpoints (all have simple object paths like `llm.model_path`).

- **A3 (descriptions/error messages)** — content quality hard to verify pre-deployment.

### Low confidence (remaining unknowns)

- **Whether the moderate consolidation (16→14) is the right stopping point.** Judgment
  call, not technical.

- **Whether annotations measurably improve parallel execution for this workflow.** The
  effect is modest in general (11.1% vs 6.1%); for dev-stack work where tools are
  usually called one at a time, the benefit may be small. But annotations are zero-cost
  to add, so the question is moot — add them regardless.

### Previously low confidence, now resolved

- ~~Whether Claude Code injects `instructions` into context~~ — **Yes**, confirmed since
  v1.0.53 (#3312). Injected into system prompt, survives compaction.

### Research blockers to monitor

- **Claude Code #4118** (`listChanged` broken) — enables dynamic tool sets when fixed
- **Claude Code #14258** (PostCompact hook) — enables MCP-native compaction recovery
- **Claude Code #7252** (resource subscriptions) — enables ambient state resources
