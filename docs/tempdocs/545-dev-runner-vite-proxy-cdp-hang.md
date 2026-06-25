---
title: "545 — Dev-runner Vite proxy hangs in-page fetch under CDP automation"
type: tempdoc
status: open
created: 2026-05-21
category: dev-runner / Vite / CDP / autonomous UI verification
related:
  - docs/tempdocs/541-composition-substrate-completion.md §11.3 (limitation flagged)
  - modules/ui-web/vite.config.js (apiProxyPlugin)
  - scripts/dev/dev-runner.cjs (dev FE launcher)
  - mcp__claude-in-chrome browser tools
---

# 545 — Dev-runner Vite proxy hangs in-page fetch under CDP automation

**Status**: open

## Why

During tempdoc 541's fix-pass Tier 3 UI verification, an attempt to render `BootPhasesPanel` end-to-end in the CDP-controlled tab failed at the fetch step: in-page `fetch('/api/boot/phases')` and `XMLHttpRequest` both hang and never return. The same URL works via curl-through-Vite-proxy from the terminal. The renderer eventually freezes on subsequent JS evaluation requests.

The likely cause (documented in 541 §11.3): Vite's `apiProxyPlugin` uses Node's `http.request` against the backend port. `http.request` uses the global agent, which has keep-alive. LogSurface's own `DiagnosticChannelCatalogClient` opens a long-lived SSE connection through the same proxy path. The SSE connection occupies one of the agent's sockets indefinitely; subsequent in-page fetches (which compete for the same agent's socket pool) queue behind the SSE forever, returning nothing.

This is a **dev-only environmental quirk** — production Tauri bypasses Vite entirely. But it limits autonomous UI verification: any agent attempting to verify FE behavior end-to-end against a backend that involves SSE elsewhere on the page will see hung fetches.

## Symptoms (reproducer)

1. Start dev stack via `mcp__justsearch-dev__justsearch_dev_start` (cold or warm).
2. Open a tab at `http://localhost:5173/#justsearch://surface/core.logs-surface`. LogSurface mounts and subscribes to DiagnosticChannelCatalogClient SSE.
3. From CDP/JS console: `fetch('/api/boot/phases')` or `new XMLHttpRequest()` GET against the same URL.
4. The promise never resolves; subsequent CDP `Runtime.evaluate` calls timeout after ~45s; renderer is effectively frozen.

curl on the same URL via Vite proxy works fine — confirming the proxy itself functions; the saturation is on the in-browser fetch path.

## Hypothesis to verify

The Vite `apiProxyPlugin` at `modules/ui-web/vite.config.js:127` calls `http.request({...})` without specifying an `agent` — defaulting to `http.globalAgent` with keep-alive enabled. A long-lived SSE stream holds one socket; the agent's `maxSockets` limit means new requests wait.

To confirm: log the socket count + request queue length in `apiProxyPlugin` over a session that exhibits the hang.

## Candidate fixes (ranked)

### A. Disable agent keep-alive in `apiProxyPlugin`

```js
const proxyReq = http.request({
  hostname: '127.0.0.1',
  port,
  path: '/api' + req.url,
  method: req.method,
  headers: { ...req.headers, host: `127.0.0.1:${port}` },
  agent: false,  // <-- new: per-request socket, no pool, no queue
}, ...)
```

Pros: one-line fix. Pros: production isn't affected (proxy is dev-only). Cons: slightly more socket churn under high request volume (irrelevant for a dev FE).

### B. Use a dedicated `http.Agent` with higher `maxSockets`

```js
const PROXY_AGENT = new http.Agent({ keepAlive: true, maxSockets: 50, keepAliveMsecs: 1000 })
// ...
http.request({..., agent: PROXY_AGENT}, ...)
```

Pros: keeps connection-reuse benefit for non-SSE traffic. Cons: still queues under enough concurrent long-polls; only delays the saturation point.

### C. Special-case SSE through a separate proxy pipeline

The SSE keep-alive connection is the offender. If the proxy detected `text/event-stream` responses and routed those through a dedicated agent (or `agent: false`), the rest of the FE's request queue would stay healthy.

Pros: surgical fix targeting the actual cause. Cons: more code; correct detection requires inspecting response headers, which means buffering.

**Recommended**: **A** (disable agent) for v1 — simplest, dev-only, no regression risk. Revisit **C** if profiling shows the socket churn is meaningful (unlikely on a dev workstation).

## Acceptance criteria

1. After the fix, in the same reproducer scenario (LogSurface open with SSE active), an in-page `fetch('/api/boot/phases')` from CDP resolves within ~500ms.
2. `BootPhasesPanel`'s fetched-state render path is end-to-end verifiable via `jseval ui-shot` or `mcp__claude-in-chrome` automation. (This closes the limitation flagged in tempdoc 541 §11.3.)
3. No regression in normal (non-CDP, non-SSE) FE behavior — manual testing of the dev FE at `http://localhost:5173/` shows no perceptible difference.

## Boundaries (not in scope)

- Tauri-side fetch behavior — unaffected; Tauri doesn't go through Vite.
- Other dev-runner concerns (worktree path resolution, hot-reload edge cases) — separate.
- `claude-in-chrome` MCP server itself — the fix is on the FE/dev-runner side, not the CDP automation.

## Verification

1. Reproducer + fix: enable LogSurface, fire fetch, observe resolution.
2. Run the original 541 fix-pass Tier 7 step 8 (re-screenshot panel after agent invocation transitions agent-tools-registration LAZY → READY). With this fix, the panel's render of the resolved state becomes fully observable through `jseval ui-shot`.
3. Cold check: `fetch('/api/boot/phases')` 100 times in a tight loop from CDP; all 100 complete; no hung promises.
