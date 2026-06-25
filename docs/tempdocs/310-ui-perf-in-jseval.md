---
title: "310: UI Performance Timing, MSW Mock Mode, and Legacy Perf Deletion"
type: tempdoc
status: done
created: 2026-03-16
updated: 2026-03-16
---

# 310: UI Performance Timing, MSW Mock Mode, and Legacy Perf Deletion

## Purpose

Add UI latency measurement (`keystroke_to_results_paint`,
`click_to_preview_visible`) to jseval via `playwright-python`. This is the
last eval/measurement capability not yet in jseval.

## Background

The existing `scripts/perf/` suite measures UI timing through a complex
Node.js pipeline: `run-perf-suite-win.ps1` → `capture-evidence-bundle.mjs`
→ Playwright Chromium → injected `__jsUiPerf` JS → `perf-report.json`.
~5,500 lines across PS1/MJS.

The actual measurement is simple: inject a JS timing harness into the browser
via `addInitScript`, trigger a user interaction (keystroke or click), wait for
the result to render, then read back the timing from `performance.measure()`.
All timing happens inside the browser — the Python/JS driver is just the
orchestrator.

## What to measure

Two UI latency metrics, each with a breakdown:

**`keystroke_to_results_paint`** — user types a query, how fast do results appear:
- `event_to_response_ms` — keydown to HTTP response received
- `response_to_dom_visible_ms` — response to DOM elements visible
- `dom_visible_to_next_paint_ms` — DOM visible to next browser paint frame
- Total: `duration_ms`

**`click_to_preview_visible`** — user clicks a result, how fast does preview render:
- Same breakdown structure

**SLO targets** (from legacy):
- keystroke_to_results_paint: 300ms
- click_to_preview_visible: 200ms

## Implementation

### New dependency

Add `playwright` to `pyproject.toml` as an optional dependency:
```toml
[project.optional-dependencies]
ui = ["playwright>=1.40"]
```

Install: `pip install -e ".[ui]"` then `playwright install chromium`.

### New module: `jseval/ui_perf.py`

The injected JS harness (`UI_PERF_MARKS_INIT_SCRIPT`) is the same JS used
by the Node version — it runs in the browser, not in Python. Embed it as
a string constant.

```python
async def measure_ui_latency(
    ui_url: str,
    api_base_url: str,
    *,
    iterations: int = 4,
    warmup: int = 1,
    timeout_ms: int = 30000,
) -> dict:
    """Measure keystroke-to-paint and click-to-preview latency."""
```

Flow per iteration:
1. Navigate to `ui_url`
2. Wait for search input visible
3. Arm keydown → type query → wait for search response → wait for result
   rows visible → mark DOM visible → finishAfterNextPaint → record timing
4. Arm click on result row → click → wait for preview response → wait for
   preview visible → mark DOM visible → finishAfterNextPaint → record timing
5. Clear search input for next iteration

After all iterations, compute p50/p95/p99 for each metric.

### CLI: `jseval ui-perf`

```
jseval ui-perf [--ui-url http://localhost:5173]
               [--api-base-url http://127.0.0.1:8080]
               [--iterations 4] [--warmup 1]
               [--output-dir ...]
```

Requires: running dev stack with UI (Vite dev server) + indexed documents.

### DOM selectors (from existing test IDs)

- `[data-testid="search-input"]` — search textarea
- `[data-testid="search-result-row"]` — result rows
- `[data-testid="inspector-pane"]` — preview pane

### Output schema

```json
{
  "schema": "ui-perf.v1",
  "iterations": 4,
  "warmup": 1,
  "measurements": [
    {
      "kind": "keystroke_to_results_paint",
      "iteration": 0,
      "duration_ms": 219.7,
      "breakdown_ms": {
        "event_to_response_ms": 161.6,
        "response_to_dom_visible_ms": 31.2,
        "dom_visible_to_next_paint_ms": 23.7
      }
    }
  ],
  "summary": {
    "keystroke_to_results_paint": {
      "count": 4, "p50_ms": 220, "p95_ms": 270, "p99_ms": 280,
      "mean_ms": 235, "slo_ms": 300, "slo_pass_rate": 1.0
    },
    "click_to_preview_visible": {
      "count": 4, "p50_ms": 62, "p95_ms": 65, "p99_ms": 68,
      "mean_ms": 63, "slo_ms": 200, "slo_pass_rate": 1.0
    }
  }
}
```

## Implementation status

**DONE.** Implemented in `jseval/ui_perf.py` (~280 lines). 275 tests pass
(269 existing + 6 new).

| File | Lines | Description |
|------|-------|-------------|
| `jseval/ui_perf.py` | 280 | Browser-injected timing via Playwright async API |
| `jseval/cli.py` | +18 | `ui-perf` subcommand |
| `pyproject.toml` | +1 | `ui = ["playwright>=1.40"]` optional dep |
| `tests/test_ui_perf.py` | 95 | Pure-function tests for result building + formatting |

### How the measurement works

1. `_setup_test_corpus()` — creates alpha.txt + beta.txt via httpx, ingests
   via `/api/knowledge/ingest`, polls `/api/status` until IDLE, verifies
   searchable via `/api/knowledge/search`
2. `_run_browser_measurements()` — launches headless Chromium via
   `playwright.async_api`, injects `_UI_PERF_HARNESS_JS` via `addInitScript`,
   navigates to the UI URL
3. Per iteration: arms keydown → types query → waits for search response →
   waits for result rows → marks DOM visible → `finishAfterNextPaint` →
   records timing. Then arms click → clicks result → waits for preview
   response → waits for preview visible → same flow.
4. All timing happens inside Chromium via `performance.mark/measure` +
   double `requestAnimationFrame`. The Python driver only reads back the
   result dict — no Python-side timing affects measurement accuracy.

### Relationship to legacy perf suite

The legacy `scripts/perf/` suite (~5,500 lines) does the same measurement
but wrapped in EvidenceBundle v1 infrastructure (artifact manifests,
determinism budgets, screenshot capture, dev-runner lifecycle management).
`jseval ui-perf` strips away the infrastructure and keeps only the timing
measurement — the part that actually matters for performance evaluation.

The injected JS harness (`__jsUiPerf`) is byte-for-byte identical to
`modules/ui-web/scripts/evidence/lib/perf-harness.mjs`. Measurement
fidelity is the same.

### What was lost with `scripts/perf/` deletion

The legacy suite provided capabilities beyond latency measurement that
jseval does not replicate:

- Screenshot-based visual verification (EvidenceBundle v1)
- Worker restart timing, cold-start scenarios
- JVM saturation gauges
- NDJSON histogram parsing for HTTP routes
- Determinism budget tracking

These were observability/verification concerns, not latency measurement.
They were already broken (missing dependencies from tempdoc 308 deletions)
and have been removed.

---

## Research: UI development tooling landscape (March 2026)

Investigation into current best-in-class tools for enabling agents and
developers to work on the JustSearch UI (React + TypeScript + Vite + Tauri).

### Browser automation — Playwright is the right choice

Playwright is the consensus best E2E testing framework in 2026. Cross-browser,
cross-language (JS/Python/Java/C#), fastest execution (up to 4x faster than
alternatives in benchmarks), best auto-waiting, built-in parallelization and
trace viewer. jseval already uses it via `playwright-python` for `ui-perf`.

Competitors assessed:
- **Cypress** — runs inside the browser (fast but limits multi-tab/domain).
  Best debugging UX but JS/TS only. Not suitable for Python-based tooling.
- **Puppeteer** — Chrome-only, no auto-waiting, not a testing framework.
  Playwright is its successor from the same team.
- **Selenium** — legacy, widest compatibility but slowest. No advantage.

### Tauri desktop app testing via CDP

Playwright can connect to Tauri's Edge WebView2 via Chrome DevTools Protocol
(CDP) by setting `--remote-debugging-port=9222` on the Tauri app. This means
`jseval ui-perf` could measure the actual desktop app, not just the Vite dev
server. Implementation: `browser.connect_over_cdp("http://localhost:9222")`.

Source: github.com/Haprog/playwright-cdp

### AI browser agents — Browser Use

Browser Use (github.com/browser-use/browser-use, 78k+ stars) is the leading
open-source framework for AI browser agents. It restructures DOM for LLMs,
strips irrelevant elements, labels interactive components. 89.1% success rate
on WebVoyager. Python-native.

Relevance to JustSearch: could enable agents to autonomously explore and
validate UI without hardcoded `data-testid` selectors. Different from jseval's
deterministic measurement approach — this is for exploratory testing, not
benchmarking. Interesting future direction but not a current priority.

### Component development — Storybook remains standard

Storybook 8 is the ecosystem leader for React. Its Vitest integration makes
stories automatically testable. Ladle is a lighter alternative (1.2s startup
vs Storybook's 8s) for React-only projects.

JustSearch doesn't currently use Storybook. Adding it would enable:
- Component-level visual regression testing
- Isolated development of UI components
- AI-powered visual testing via Applitools Eyes integration

### Fast iteration — current setup is already best-in-class

Vite + React Fast Refresh gives near-instant HMR. Combined with tempdoc 305's
hot-reload work, the edit→see cycle is already sub-second. No tool change
needed.

### Recommendations

| Area | Current | Recommendation |
|------|---------|---------------|
| Browser measurement | Playwright (jseval ui-perf) | Keep. Already best-in-class. |
| Desktop app testing | Not implemented | Add CDP connection to Tauri WebView2 — small effort, high value. |
| AI exploratory testing | Not implemented | Evaluate Browser Use for non-deterministic UI validation. Lower priority. |
| Component development | None (direct Vite dev) | Consider Storybook 8 if component isolation becomes a need. |
| Dev iteration speed | Vite + React Fast Refresh | Already optimal. No change needed. |
| Backend-free UI dev | Demo mode only | MSW (Mock Service Worker) — see below. |

---

## Research: UI development isolated from backend work

### The problem

The dev stack is shared — one backend at a time. When an agent is indexing a
corpus or running benchmarks, the backend (specifically the Worker process)
is under heavy load. UI developers or UI-focused agents working in parallel
experience:
- Degraded API response times (Worker is busy with Lucene I/O)
- Changing search results (new documents appearing mid-development)
- Backend restarts (indexing experiments use `--clean hard`)

### Architecture context: Head vs Worker

JustSearch runs as two JVM processes communicating via gRPC:

**Head (`modules/ui`)** — the HTTP-facing process:
- Javalin REST API (all `/api/*` endpoints)
- Serves the web UI
- Routes requests to the Worker via gRPC
- Manages AI inference (llama-server bridge)
- Lightweight — no Lucene, no index data

**Worker (`modules/indexer-worker`)** — the data engine:
- Owns the Lucene index (all disk I/O)
- File watching, content extraction, job queue
- Executes search queries against Lucene
- SPLADE, chunk vectorization, cross-encoder reranking
- Heavy — this is where indexing load lives

When indexing runs, the Worker is under load. The Head is mostly idle — it
just proxies HTTP→gRPC. UI API responses slow down because the Worker is busy
processing search gRPC calls while also indexing, not because the Head is
overloaded.

This means a mock backend only needs to replace the Head's HTTP responses.
Neither Head nor Worker needs to be running for UI development.

### What already exists

| Mechanism | Status | Limitation |
|-----------|--------|-----------|
| Demo mode (`?demo=true`) | Implemented | Hardcoded fixtures in `App.tsx`. Single query response. No preview, no settings variation. |
| Playwright route mocking | Implemented | Test/evidence-capture only. Not wired into dev server. |
| `searchFn` injection | Available | `useSearch` hook accepts mock function. Not exposed to developers. |
| Port override (`?api_port=N`) | Implemented | Still needs a running backend on that port. |
| Centralized API layer | `src/api/http.ts` → `request()` | Single interception point — all `fetch()` calls route through here. |
| MSW | Not installed | — |
| Storybook | Not installed | — |

### Recommended solution: MSW (Mock Service Worker)

MSW intercepts `fetch()` at the network level inside the browser. The app
code is unchanged — `request()` in `http.ts` makes a real fetch, MSW
intercepts it and returns fixture data before it hits the network.

**Why MSW is the right answer:**
- Works with the existing `fetch()`-based API layer — zero production code changes
- Same mock handlers work in dev mode, Vitest unit tests, Playwright E2E tests,
  and Storybook (if added later)
- Fixtures can be captured from a real backend session and replayed deterministically
- App behaves identically to production (network panel shows requests)
- Industry standard: mswjs.io, 16k+ GitHub stars, actively maintained

**What it would take:**
- `npm install msw --save-dev`
- `npx msw init public/` — registers the service worker
- Create `src/mocks/handlers.ts` — handlers for `/api/status`,
  `/api/knowledge/search`, `/api/preview`, `/api/settings/v2`,
  `/api/inference/status`, `/api/ui/ready`
- Create `src/mocks/fixtures/` — captured JSON responses from a real session
- Conditional startup in `main.jsx`:
  ```js
  if (import.meta.env.DEV && import.meta.env.VITE_MOCK) {
    const { worker } = await import('./mocks/browser');
    await worker.start({ onUnhandledRequest: 'bypass' });
  }
  ```
- Dev command: `VITE_MOCK=true npm run dev`

Estimated: ~200 lines of setup + fixture JSON files. The existing Playwright
mock-routes (`mock-routes.mjs` / `mock-data.mjs`) can be ported directly to
MSW handlers — same fixture data, different interception layer.

**What it unlocks:**
- UI development without any backend running (no Head, no Worker)
- Deterministic UI tests independent of index state
- Agent parallelism — UI agent and backend agent never compete for the dev stack
- Instant UI startup (no waiting for backend readiness polling)
- Reproducible bug investigation (same fixture data every time)

### Alternatives considered

| Approach | Why not |
|----------|---------|
| Extend demo mode | Limited — no network-level interception, no timing realism, fixtures live in production code |
| `vite-plugin-mock-dev-server` | Intercepts at the Vite proxy level (server-side), not browser-side. Can't be reused in tests or Storybook. |
| Separate read-only backend | No read-only mode exists. Two backends = 4GB+ RAM. Doesn't solve data-changing-under-you. |
| Recorded HTTP replay (e.g., Polly.js) | More complex setup for the same result. MSW is simpler and more widely adopted. |

### MSW implementation status

**DONE.** Implemented in `modules/ui-web/src/mocks/`. Verified live — UI loads
with fixture data, search returns 3 results, no backend running.

| File | Description |
|------|-------------|
| `src/mocks/handlers.ts` | MSW handlers for 7 endpoints + search + preview |
| `src/mocks/browser.ts` | Service worker registration |
| `src/main.jsx` | Async bootstrap with conditional MSW start |
| `public/mockServiceWorker.js` | MSW service worker (auto-generated) |
| `package.json` | `msw` devDep + `dev:mock` script |

Usage: `cd modules/ui-web && npm run dev:mock`

### Legacy perf suite deletion

`scripts/perf/` (~5,500 lines, 29 files) was entirely broken — all three
core dependencies had been deleted in tempdoc 308's mass deletion:
- `EvalSession.psm1` (imported by `perf-suite-runtime-common.ps1`)
- `eval-backend-lifecycle.mjs` (referenced by `dag-runner-perf-suite.mjs`)
- `policy-engine.mjs` + `suite-loader.mjs` (imported by `diff-perf-suite.mjs`)

Deleted along with orphaned CI workflows (`perf-regression-win.yml`,
`perf-calibration-win.yml`).

### Documentation updates

- `modules/ui-web/README.md` — complete rewrite from Vite scaffold placeholder
- `CLAUDE.md` — added frontend verification workflow + UI dev references
- `docs/how-to/develop-ui.md` — new guide for MSW, Chrome, testing, architecture

---

## Completion summary

| Deliverable | Status |
|-------------|--------|
| `jseval ui-perf` (Playwright timing) | Done, 275 tests pass |
| MSW mock mode (`npm run dev:mock`) | Done, verified with Chrome extension |
| Legacy `scripts/perf/` deletion | Done, ~22,400 lines removed |
| UI tooling research (Playwright, Browser Use, Storybook) | Done |
| UI isolation research (Head/Worker, MSW recommendation) | Done |
| Agent documentation (README, CLAUDE.md, how-to guide) | Done |
| Chrome extension live verification | Done (screenshot proof in session) |

## Future work (not this tempdoc)

- **Golden set construction** (tempdoc 308 Step 1) — human data work, not code.
  Highest-leverage remaining item for eval quality.
- **CDP connection to Tauri WebView2** — `jseval ui-perf --cdp-url` to measure
  the actual desktop app. Small feature.
- **Richer MSW fixtures** — capture real backend responses for more varied mock data.
