---
title: "365 — Agent UI Feedback Loop"
status: done
created: 2026-03-28
scope: scripts/jseval, modules/ui-web
depends-on: [364]
---

# 365 — Agent UI Feedback Loop

## Purpose

Enable AI agents to develop the UI with a visual feedback loop: edit code
-> see the result -> decide if it's correct -> iterate. Currently agents work
blind -- they modify React components and hope the render is correct.

---

## Completed work

### Evidence system rewrite (batch tool)

Deleted the old Node.js evidence capture system (10,028 lines) and replaced
it with `jseval ui-check` -- a Python Playwright batch screenshot tool:

- 47 steps declared in a flat registry with `setup`, `depends_on`, `isolated`
- 11 shared-browser chain steps (sequential) + 36 isolated steps (parallel)
- 46/47 pass in 62s (1 CDP failure: button-active selection timeout)
- Shared helpers: `_demo_url()`, `_type_and_search()`, `_ensure_file_selected()`
- File-size baseline comparison for drift detection between runs
- `jseval/ui_check.py` (684 lines), `jseval/ui_selectors.py` (65 lines)

### `jseval ui-shot <step-name>` -- single-step capture

Implemented in `jseval/ui_shot.py`. Reuses `_build_steps()` from `ui_check.py`.

**Isolated steps** (no dependencies): launches browser, navigates to demo URL,
runs step setup, screenshots. Measured: ~340ms.

**Shared-chain steps** (have `depends_on`): replays the dependency chain up
to the requested step. E.g., `ui-shot citation-highlight` replays:
search-results -> filters-chips -> inspector-open -> streaming -> summarize-done
-> citation. Measured: ~300-560ms for all chain depths.

```bash
$ jseval ui-shot filters-chips --ui-url http://localhost:5173
tmp/ui-shot/filters-chips.png
```

The agent then `Read`s the path to see the result inline in its context.

### `jseval ui-shot --list` -- step discovery

```bash
$ jseval ui-shot --list
Steps: 47 total (11 chain, 36 isolated)

Chain steps (sequential, shared browser):
  search-results
  command-mode  <- search-results
  filters-chips  <- search-results
  ...

Isolated steps (parallel, own browser):
  home
  search
  library
  ...
```

### Worktree-aware auto-serve

In a worktree, the main checkout's dev server doesn't watch the worktree's
source files. `ui-shot` detects this and auto-starts a Vite dev server:

1. Creates a `node_modules` junction in the worktree pointing to the main
   checkout's `node_modules/` (Windows junction, no admin needed)
2. Starts `vite --mode mock --port 5174` from the worktree's `modules/ui-web/`
3. Persists server info to `tmp/ui-shot-server.json` (PID + port + root)
4. Subsequent calls reuse the running server (port liveness check)

First call pays ~3s startup; subsequent calls are ~300-600ms. The server
is detached and survives the parent process.

### File-to-step index

Single source of truth: `jseval/ui_step_index.json`. Both `ui_shot.py` and
`ui-shot-hint.mjs` load from the same JSON file. Maps 26 frontend source
files to the steps that exercise them. Exposed as `--affected`:

```bash
$ jseval ui-shot --affected components/search/SearchFiltersBar.tsx
tmp/ui-shot/filters-chips.png
tmp/ui-shot/search-simple-mode.png
tmp/ui-shot/search-advanced-mode.png
```

Works with full Windows paths -- normalizes backslashes and matches by suffix.

### Hook integration

PostToolUse hook (`ui-shot-hint.mjs`) fires on Edit/Write for files matching
`modules/ui-web/src/**/*.{ts,tsx}`. Outputs `additionalContext` telling the
agent which steps are affected:

```
UI file edited: components/search/ResultRow.tsx
Affected steps: search-results, multi-select, row-hover, snippets-expanded, selection-preserved (+3 more)
To see the visual result: jseval ui-shot search-results
To capture all: jseval ui-shot --affected "D:\...\ResultRow.tsx"
```

The hook is lightweight (<50ms, no process spawning) -- it does a JSON lookup
and outputs the hint. The agent decides whether to capture.

Registered in `.claude/settings.local.json` and documented in
`.claude/rules/hooks-reference.md`.

### Server cleanup

SessionEnd hook (`ui-shot-cleanup.mjs`) kills the worktree Vite server and
removes the PID file when the agent session ends. Prevents port 5174 leaks.

### Agent documentation

CLAUDE.md verification workflow updated to reference `ui-shot` instead of
`/chrome` for visual verification. Both the quick-reference and the full
workflow section point agents to `jseval ui-shot`.

### Step index validation

`tests/test_ui_step_index.py` (4 tests) validates that every step name in
the shared index exists in the step registry, with no empty or duplicate
entries.

---

## Performance

| Step type | Measured latency |
|-----------|-----------------|
| Isolated (e.g., `home`) | ~340ms |
| Chain root (e.g., `search-results`) | ~560ms |
| Deep chain (e.g., `citation-highlight`, 5 deps) | ~300ms |
| First call in worktree (server startup) | ~3s + capture |
| Subsequent worktree calls | same as above |
| Batch `ui-check` (all 47) | ~62s |

All single-step captures well under the 2s target.

---

## Blocked items (waiting on 364)

Fixture updates for `modules/ui-web/src/mocks/fixtures.mjs` when 364
frontend types land:

- `meta_source`, `meta_author`, `meta_category` on search hits
- `provenance` (`HitProvenance` sub-records) on search hits
- `pipelineExecution`, `indexCapabilities` on search response
- MSW handlers for `retrieve-context` / `match-citations`

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Agent UI feedback loop design with fixture-update plan for the 364 frontend-types landing. Design captured; the 364 fixture work has presumably landed (per recent slice activity).

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

