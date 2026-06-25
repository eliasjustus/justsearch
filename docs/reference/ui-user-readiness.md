---
title: UI user readiness verification (web UI)
type: reference
status: in-progress
updated: 2026-04-07
description: "Workflow checklist and known issues for web UI readiness."
---

# UI user readiness verification (web UI)

## Scope

Goal: validate “normal user” workflows in the **web UI** (Vite browser mode) and record issues.

- Workflow baseline doc: `docs/how-to/use-ui.md`
- Verification principle: prefer deterministic signals via `/api/status` + `/api/debug/state` over log-grepping.

## Test environment

- **Mode**: Browser (Vite)
- **Command**: `npm --prefix .\modules\ui-web run dev:all`
- **Frontend**: `http://localhost:5173`
- **Backend**: auto-picked (recorded in run logs; example seen: `http://127.0.0.1:33222`)
- **OS**: Windows 11 (user machine)

### Notes about dev data

- Dev runs use `modules/ui-web/.dev-data` by default.
- For repeatable tests, prefer creating and cleaning up a temporary watched root under the repo (e.g. `JustSearch/.tmp-ui-verify-*`).

## Readiness gates (definition of “user ready” for this pass)

- **Core UX works**:
  - Add folder (browser mode manual path entry) works reliably.
  - Indexed docs become searchable.
  - Selecting a result shows the Inspector Panel without errors.
  - Reindex all + per-root reindex works and doesn’t silently fail.
  - Removing a root works and provides clear feedback.
- **Status is trustworthy**:
  - `/api/status` reflects Worker/index health, failures, and migration states.
  - No “200 OK” for actions that don’t actually work.
- **No scary errors for typical flows**:
  - No repeated console errors in browser mode for normal navigation.
  - No obvious crash loops, dead UI, or stuck indexing under normal usage.

## Workflow checklist (web UI)

### W1. Launch + connect

- Steps:
  - Open `http://localhost:5173`
  - Confirm app connects to backend automatically (`VITE_JUSTSEARCH_API_PORT`).
- Expected:
  - UI renders, status deck shows healthy/connected state.
  - No repeated console errors for normal browser mode.
- Result:
  - ✅ UI loads and connects to the backend (observed via successful `/api/status` polling).
  - ✅ Noisy browser-mode console errors (`tauri api_port invoke failed`) are gone after UI-001 fix.

### W2. Add folder (browser mode manual input)

- Steps:
  - Go to **Library**
  - Click **Add Folder**
  - Paste a real path (e.g. `D:\code\JustSearch\.tmp-ui-verify`)
  - Confirm root appears and indexing begins.
- Expected:
  - Root appears immediately; file count eventually matches.
  - Clear error toast if path invalid/unreadable.
- Result:
  - ⚠️ **Partially verified**:
    - The Library view correctly renders the browser-mode warning + manual path form.
    - The backend `POST /api/indexing/roots` works (verified via direct call), including add+remove of a temporary root (`JustSearch/.tmp-ui-advanced-2`) which was then cleaned up.
    - Due to automation limitations (controlled input typing), I couldn’t deterministically drive the manual text entry to completion via the browser automation tool in this session.
  - Follow-up: manually verify in a real browser that typing a path + clicking **Add** registers the root and updates `fileCount`.

### W3. Search

- Steps:
  - Use `?q=<token>` to avoid automation event issues (see `docs/how-to/use-ui.md`).
  - Verify at least one hit.
- Expected:
  - Results show correct path + file name.
  - **Typo correction:** Misspelled queries (e.g., `lucne serch`) show a correction banner: "Showing results for **lucene search** instead of *lucne serch*".
  - **Search history:** Focusing the empty search input shows a "Recent Searches" dropdown with previous queries. Entries can be individually removed.
  - **Syntax help:** A `?` button next to the SIMPLE/LUCENE toggle shows mode-specific operator help.
- Result:
  - ✅ Using `?q=hello` returns results including a known test file when indexed.

### W4. Inspector Panel

- Steps:
  - Click a result row **or checkbox** to set active file (Inspector opens immediately)
  - (Keyboard) Arrow keys move a cursor highlight without opening Inspector; `Enter` commits selection and opens Inspector
  - Confirm Inspector Panel shows preview (or graceful "no preview" state)
- Expected:
  - No UI crash; no endless loading.
- Result:
  - ✅ Selecting a result triggers `GET /api/preview?docId=...` and returns `200`; Inspector Panel loads without errors.

### W5. Reindex all

- Steps:
  - Library → **Reindex All**
  - Observe status transitions and that doc count remains consistent.
- Expected:
  - `/api/status.indexState` enters INDEXING then returns IDLE.
  - No permanent failures for “normal” files.
- Result:
  - ✅ Reindex All completes without failures.
  - Note: `pendingJobs` may remain `0` for small/no-op reindexes, but `roots[].lastIndexed` updates (observed).

### W6. Remove folder

- Steps:
  - Library → remove root (trash icon) → confirm
- Expected:
  - Root disappears; no crashes; re-adding later works.
- Result:
  - ✅ “Remove from index” opens a confirm dialog and successfully removes the root (list refreshes afterward).

### W7. Health view

- Steps:
  - Navigate to **Health**
- Expected:
  - Shows Worker/index state and basic counters without errors.
- Result:
  - ✅ Health view loads and polls `/api/status` without errors (Auto-refresh toggle + refresh button render).

### W8. Settings view

- Steps:
  - Navigate to **Settings**
- Expected:
  - Settings load; changes don’t brick the app.
- Result:
  - ✅ Settings view loads and renders key controls (theme, high contrast, density, vim mode, enter action).
  - Expected: Appearance section shows System/Dark/Light theme buttons. Selecting "System" follows OS preference and listens for live OS theme changes.
  - ✅ **Reset to Defaults** triggers `POST /api/settings/v2` and returns `200` (no more `Invalid settings format`).
  - ✅ Backend accepts both payload shapes (verified via direct call):
    - legacy/desktop flat payload (used by `BrainView`)
    - nested web payload (`{ ui: {...}, llm: {...} }`, used by `useSettings`)
  - Note: UI-only preferences (high contrast/density/vim mode/default action) are intentionally persisted client-side; only `theme` is merged into backend settings today.
  - ⚠️ Toggle interactions not deterministically automatable in this run due to ref churn; recommend a quick manual click-through in a real browser.

### W9. AI flows (optional)

- Steps:
  - Try **Summarize** / **Ask** in Inspector (may be unavailable depending on inference mode/config).
- Expected:
  - If unavailable, UI explains why and does not error-loop.
- Result:
  - ✅ AI Brain view loads (model path fields, inference sliders, auto-load checkbox).
  - ✅ In this environment, `/api/inference/status` reports **online + available** (so AI actions should be usable).
  - ✅ Multi-file **Summarize** works end-to-end via the streaming endpoint:
    - `POST /api/summarize/batch/stream` emits `progress`, `chunk`, and `done` events and returns a final summary.
    - Observed `done` metadata includes `coverage` + `perFile[]` diagnostics (example: `coverage=degraded` with `coverageReason=worker_slice_unavailable_or_failed`, but still produced a summary).
  - ✅ Multi-file **Ask** works end-to-end after UI-004 fix:
    - `POST /api/ask/stream` emits streamed `chunk` events and a final `done`.
    - If RAG retrieval yields an empty context (no chunks / no BM25 matches), the backend falls back to `documents().fetchBatch(...)` so Q&A still includes selected files.

## Advanced workflow checklist

This section focuses on “power user” / higher-complexity flows (pagination, facets/filters, multi-select + AI).

### A1. Advanced search (pagination + filters + facets)

- **Dataset**: temporary test root `D:\code\JustSearch\.tmp-ui-advanced-1` with a known token `adv_token_98765`.
- **Pagination**:
  - ✅ “Load more” works repeatedly and disappears when the result set is exhausted.
- **Facets + date filters**:
  - ✅ Facet counts now match filtered hits (previously facets could exceed `totalHits` under date filters).
  - Example verified: “Last 30d” shows `en-GB (110)` matching `totalHits=110`.
- **Sort**:
  - ✅ Switching sort between newest/oldest updates ordering without breaking pagination.
- **Scope + type filters**:
  - ✅ Scope dropdown + type dropdown apply and can be cleared with “Clear filters”.

## Issues found

Use this format:

- **ID**: UI-###
- **Severity**: blocker | major | minor | nit
- **Area**: Library/Search/Preview/Health/Settings/Infra
- **Symptoms**:
- **Repro**:
- **Expected**:
- **Actual**:
- **Notes / suspected cause**:
- **Fix**: (link to code or summary)
- **Status**: open | fixed | deferred

### UI-001 — Browser mode logs noisy Tauri errors on startup

- **Severity**: minor
- **Area**: Startup / API discovery
- **Symptoms**: browser console shows repeated `tauri api_port invoke failed ...` even when not running in Tauri.
- **Repro**:
  - Run `npm --prefix .\JustSearch\modules\ui-web run dev:all`
  - Open `http://localhost:5173`
- **Expected**: no Tauri invocation attempts in plain browser mode.
- **Actual**: UI attempted `invoke("api_port")`, caught error, logged warning.
- **Fix**:
  - `modules/ui-web/src/api/client.ts`: `resolvePortFromTauri()` now returns early unless `window.__TAURI__` exists, and uses dynamic import for `invoke`.
- **Status**: fixed (re-verified)

### UI-002 — Double-click “open” tries to open `file://` in browser mode (blocked)

- **Severity**: major (web UI workflow)
- **Area**: Search results / open action
- **Symptoms**: double-clicking a result attempts to open `file://...` which is blocked by the browser (“Not allowed to load local resource”).
- **Repro**:
  - Browser mode → run a search → double-click a result row
- **Expected**: a meaningful action occurs (open/preview) or UI explains that open is desktop-only.
- **Actual**: browser blocks `file://` navigation; user sees no useful result.
- **Fix**:
  - `modules/ui-web/src/App.tsx` (historical, pre-Lit path): in browser mode, `handleOpen()` opens a best-effort backend preview URL:
    - `GET /api/preview?docId=...`
- **Status**: fixed (best-effort; UX improvement recommended below)

### UI-003 — “Open containing folder” quick action is present in browser mode but does nothing

- **Severity**: minor
- **Area**: Search results quick actions
- **Symptoms**: folder button appears but only works in Tauri; in browser it’s a no-op.
- **Repro**:
  - Browser mode → hover result row → click “Open containing folder”
- **Expected**: disabled/hidden in browser mode or provides alternate behavior.
- **Actual**: no effect.
- **Fix**:
  - `modules/ui-web/src/components/search/ResultRow.tsx` (historical, pre-Lit path): hide the “Open containing folder” button unless `window.__TAURI__` is present.
- **Status**: fixed (re-verified)

### UI-004 — Multi-file “Ask” returns `NO_CONTENT` even for searchable files

- **Severity**: major (AI workflow)
- **Area**: Inspector Panel
- **Symptoms**: Asking a question about selected files yields an immediate error instead of a streamed answer.
- **Repro**:
  - Search for a known token (e.g. `adv_token_98765`)
  - Select 2+ results via the checkboxes
  - In the Inspector Panel "Ask about N files…" box, type a question and submit
  - Or call the backend directly:
    - `POST /api/ask/stream` with `{ docIds: [...], question: "...", language: "en-US" }`
- **Expected**: streamed answer (`chunk` events), then `done`.
- **Actual**: `event: error` with:
  - `errorCode=NO_CONTENT`
  - `error="No content in selected files (N files checked)"`
- **Fix**:
  - `modules/ui/src/main/java/io/justsearch/ui/api/SummaryController.java`:
    - `handleAskStream`: if RAG returns an empty context (no chunks + BM25 finds no matches), **fall back to** `documents().fetchBatch(...)` and format the selected docs (instead of hard-failing with `NO_CONTENT`).
    - `handleSummarizeBatchStream`: same empty-context fallback, for consistency.
- **Status**: fixed (re-verified via `POST /api/ask/stream` returns streamed `chunk` + `done`)

### UI-005 — Settings save/reset fails with `Invalid settings format` (web UI payload mismatch)

- **Severity**: major (Settings usability)
- **Area**: Settings / API contract
- **Symptoms**: clicking “Reset to Defaults” or changing a setting triggers a failed `POST /api/settings/v2`.
- **Repro**:
  - Browser mode → Settings → click **Reset to Defaults**
- **Expected**: `POST /api/settings/v2` returns `200` and settings persist.
- **Actual**: backend returned `400` with `{ "error": "Invalid settings format" }`.
- **Fix**:
  - `modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java`:
    - accept nested web payloads (`{ ui, llm, indexPaths }`) and merge supported fields into the legacy `UiSettings`
    - keep legacy flat updates working (desktop/`BrainView`)
- **Status**: fixed (re-verified: `POST /api/settings/v2` returns `200` for reset + nested payload)

## Comprehensive verification pass (2026-03-29, tempdoc 364 Phase 8)

A full frontend-backend alignment verification was performed as part of tempdoc 364. Key findings:

- **All views verified** against a real backend at `http://127.0.0.1:33221` with 611 indexed files. TypeScript typechecks pass; 190/190 unit tests pass (16 test files).
- **Health GPU card** renders real NVML data (1.7 GB / 12 GB VRAM, 26% utilization, driver 595.79). Gated on `gpu.available != null`.
- **BrainView compatibility cards** verified — schema mismatch card, chunk vector card, and embedding card all render correctly from grouped sub-objects after flat-field removal.
- **Metadata facets functional** — Source, Author, and Category facet sections in SearchFiltersBar render data-driven pill toggles. Metadata filter chips appear for active selections. Rich density mode shows metadata badges on result rows.
- **Search quality features verified** — BrainView "Search Quality Features" section now shows correct reranker status. A bug was found and fixed during this verification pass: the UI previously showed "Search reranking: Inactive" when the cross-encoder was actually executing on every search (confirmed via `crossEncoderApplied: true`, 388ms). Root cause: `WorkerModelDiscovery` used `null` for explicit path and could only find models at auto-discovery locations, while the actual ORT session was activated via `JUSTSEARCH_RERANK_MODEL_PATH` env var. Fix: the extracted `SearchQualityFeaturesSection` component now reads `rerankerOrtCuda` and `rerankerModelPath` from `/api/status` (ground truth) and overrides stale ONNX discovery status.
- **Settings round-trip** verified — `vimMode` now persists to backend via `UiSettingsV2` DTO. Settings save confirmed working with `PersistenceMode.READ_WRITE` (the earlier false alarm was caused by testing against an eval-mode backend with `IN_MEMORY` persistence).
- **Search parity** confirmed — same query produces identical top-10 ranking across UI in Chrome, direct API curl, and jseval hybrid mode.

## Long-term improvements (backlog)

These are not required for the schema-migration work, but they would materially improve “normal user” UX and reduce confusion.

### LT-001 — Browser-mode “Open” should not show raw JSON

Current browser-mode behavior uses `/api/preview` for “open” to avoid `file://` blocks, but this opens a JSON response in a new tab.

- **Recommendation**:
  - Add a UI route/modal (e.g. `/#preview?docId=...`) that renders the preview nicely (title/path + extracted text, with paging controls).
  - In browser mode, route “Open” to the in-app preview (and keep “Open on disk” as desktop/Tauri-only).

### LT-002 — Offer a browser-safe “Download file” action

In browser mode, users can’t “open on disk” or “reveal in explorer”, but they often want the raw file.

- **Recommendation**:
  - Add `GET /api/files/download?docId=...` (or similar) that streams the original file bytes with safe headers.
  - Add a UI action “Download” (browser-only) and keep “Open”/“Reveal” as desktop-only.

### LT-003 — Make “Remove from index” semantics explicit (stop watching vs delete docs)

It’s unclear to users whether removing a root only stops watching, or also removes those documents from search results.

- **Recommendation**:
  - In the confirm dialog, explain the semantics and/or offer an option:
    - “Stop watching (keep indexed docs)” vs “Remove indexed docs (prune)”.
  - If prune is supported, wire it to a safe Worker operation (e.g., prune by path prefix) and report progress.

### LT-004 — Library should show per-root indexing status/progress

`roots` currently show `fileCount` and `lastIndexed`, but not whether the root is currently being indexed or has failures.

- **Recommendation**:
  - Extend the backend to return per-root status signals (indexing, error summary, last error) or provide a “root status” endpoint.
  - UI can then show clear per-root state (spinner/check/error) rather than a mostly-static icon.

### LT-005 — Improve browser-mode backend discovery when env var isn’t set

`resolveApiEndpoint()` falls back to a limited “common ports” probe. This is fine for `dev:all`, but fragile if the port changes and env injection breaks.

- **Recommendation**:
  - Expand auto-discovery to scan a small safe range (e.g. `33221..33250`) with a short timeout, or
  - Add a stable same-origin Vite proxy target in dev (`/api/*` → backend) with a single source of truth port file.
  - Add a visible UI affordance to override/persist API port in browser mode (Settings → “Dev API Port”).

### LT-006 — Reindex UX should provide deterministic user feedback

For small/no-op reindexes, `pendingJobs` may remain `0`, so users can’t tell if anything happened.

- **Recommendation**:
  - Show a toast (“Reindex requested”) and a short-lived “in progress” badge.
  - Report progress using `/api/status` counters and timestamps (already present), rather than inferring from queue depth alone.


