---
title: "307: Development Workflow Inefficiencies"
type: tempdoc
status: done
created: 2026-03-15
updated: 2026-03-15
---

## Purpose

Recurring hands-on audit of the JustSearch dev workflow. Measures real
wall-clock times, identifies friction, tracks fixes, and re-measures to
confirm improvement.

---

## Round 1 — Findings and Fixes (2026-03-15)

Eight issues found, six fixed, two deferred:

| # | Finding | Severity | Fix | Result |
|---|---------|----------|-----|--------|
| F1 | Main branch didn't compile (3 errors) | CRITICAL | Fixed test constructors + EnvRegistry migration | `build -x test` passes |
| F2 | Config cache miss costs 22s per unique task combo | HIGH | Deferred — process/docs issue | — |
| F3 | MCP tools crash (`mainRepoRoot` undefined) + ownership mismatch | HIGH | Passed `mainRepoRoot` as param; session ID fallback in stop | Both tools verified live |
| F4 | Gate DAG blocks unit checks on backend startup (+38s) | MEDIUM | Removed edges for `ui-unit-tests` and `sleep-ratchet` | Saves ~38s per gate |
| F5 | No backend hot-reload (15-40s restart per change) | MEDIUM | Deferred — tracked in tempdoc 305 | — |
| F6 | 25 stale Gradle daemons | LOW | Not worth fixing | — |
| F7 | GET /api/knowledge/search returns silent 200 empty | LOW | Added explicit 405 handler | Verified live |
| F8 | 2 model discovery tests fail (env var leakage) | MEDIUM | Sysprop override isolates from `JUSTSEARCH_MODELS_DIR` | Both pass |

Also documented pre-merge compilation requirement in `branch-safety.md` and `CLAUDE.md`.

---

## Round 2 — Re-measurement (2026-03-15)

_To be populated with fresh measurements below._

### Build & Compile

| Operation | Round 1 | Round 2 | Change |
|-----------|---------|---------|--------|
| `gradlew help` (cache hit) | 1s | **0.9s** | — |
| `build -x test` (incremental, up-to-date) | 2-3s | **5s** | Warm but more tasks executed |
| `build -x test` (config cache miss) | 38s | — | Not re-tested (would need cold cache) |
| `spotlessApply` (up-to-date) | 3s | **8s** | Higher; first invocation stored new cache entry |
| `spotlessCheck` (up-to-date) | 2.5s | **2.6s** | — |
| `compileJava + compileTestJava` (up-to-date) | — | **1s** | Fast |
| `installDist` after edit (incremental) | 2s | **2s** | — |
| Config cache entries | 213 | **237** | +24 entries from this session's work |
| Active daemons | 1 | **1** | Stopped daemons: 25 → 2 |

### Test Execution

| Operation | Round 1 | Round 2 | Change |
|-----------|---------|---------|--------|
| `:modules:core:test` (cached) | 2.5s | **1s** | Faster (build cache hit) |
| `:modules:app-services:test` | 20s | **13s** | Faster |
| `:modules:indexer-worker:test` | 45s (2 failures) | **27s (all pass)** | 40% faster + F8 fix |
| Full `test` suite | ~1m 24s | — | Not re-tested (was blocked by F1) |

### Dev Stack Lifecycle

| Operation | Round 1 | Round 2 | Change |
|-----------|---------|---------|--------|
| `installDist` (up-to-date) | 2s | **1s** | — |
| Dev stack start (skipBuild, ready_worker) | ~40s | ~40s | — |
| Dev stack start (skipBuild, ready_http) | ~15s | ~15s | — |
| Search POST (with cross-encoder) | 1.2s | **1.8s** | Variance |
| GET /api/knowledge/search | 200 empty | **405 + error JSON** | F7 fixed |

### MCP Dev Tools

| Operation | Round 1 | Round 2 | Change |
|-----------|---------|---------|--------|
| `fetch_api_json` (no apiPort) | `mainRepoRoot is not defined` | **OK** | F3 Bug 1 fixed |
| `search_query` (no apiPort) | `mainRepoRoot is not defined` | **OK** | F3 Bug 1 fixed |
| `stop` (with sessionId) | OWNER_CONFLICT | OWNER_CONFLICT | Partial — see below |
| `stop` (without sessionId) | — | **OK** | F3 Bug 2 workaround |

### Remaining Issues

**F3 Bug 2 partial:** `stop` with explicit `sessionId` still fails because the MCP
caller's session ID (from Claude Code) differs from what `dev-runner.cjs` records
during `start` (resolved via env var / telemetry file fallback). Root cause: session
ID resolution is split across two processes with different resolution paths.

**Fix applied (post-Round 2):** MCP `stop` handler now always reads the holder session
from `active.json` instead of using `input.sessionId`. This ensures stop always
matches start. Committed but requires MCP server restart to verify.

**F2 (config cache):** 237 entries (up from 213). Each unique Gradle command line
creates a new entry. This is structural and won't improve without standardizing
the task combinations agents use.
