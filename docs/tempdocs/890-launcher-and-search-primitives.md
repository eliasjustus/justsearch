---
title: "Launcher and search primitives: global hotkey + palette window (191 §A), query history, size/folder filters, date-range control, boolean-syntax toggle, and the help-vs-composer syntax defect"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L8
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 191-tauri-desktop-shell-gaps      # §A "Global Search Bar (Spotlight/Raycast pattern)" — the design
  - 508 (command palette, in-window)  # what exists; not an OS launcher
  - 851-search-v2-retirement          # deleted the query trail; why there is no history today
  - 362-faceted-metadata-filtering    # the facet substrate facetChips.ts uses
  - 570-search-window-competitive-uiux-research  # filter/keyboard model research (status open)
  - 570 / 853 for UX audit expectations
---

# 890 — Launcher and search primitives

## Briefing for the agent picking this up

Fresh start. Read this file, 887 Appendix A6 (evidence) and A5 §5.1/§5.8, then tempdoc 191
§A lines 142-158 (the launcher design) and `docs/explanation/27-frontend-presentation-kernel.md`
(the rules every new surface must obey). Frontend is **Lit `shell-v0`**, not React (ADR-0032).
Work in a worktree; `node scripts/dev/prepare-worktree.cjs` for FE dev. Load `/ui-check` before
visual work — every surface change is verified by `jseval ui-shot`, not eyeballed. New persisted
state must be registered in `StoreCatalog.java` + `governance/store-recoverability.v1.json`
(`check-store-recoverability`). Search API changes go through the route manifest / wire gate.
Ship as 3-4 PRs in the order below; item 1 is the largest and can trail.

## Thesis

The shell already has tray, autostart, close-to-tray and single-instance focus (`lib.rs:1405-1540`);
the two launcher-defining pieces — an OS global hotkey and a separate compact window — were
explicitly parked in 191 ("re-added when the global search bar is implemented", `191:56`). On the
search side, pins exist but query history was deleted with search-v2 (851), filters lack size and
folder, and Lucene/boolean syntax is reachable by MCP and the API but not by the human composer
(`TextQueryOps.java:93` escapes operators because `querySyntax` is never sent) — while the shipped
help file documents that syntax to users (`SSOT/docs/help/search-syntax.md:13-25`).

## Scope

1. **Help-vs-composer syntax defect (first, smallest).** Decision: expose the toggle rather than
   delete the help. Add an "advanced syntax" affordance in the composer that sends
   `querySyntax: 'lucene'` (the API already accepts it, `KnowledgeSearchController.java:290-294`),
   surface parse errors from `TextQueryOps` as a composer-level notice (no toast; presentation
   kernel), and make `search-syntax.md` state when the syntax applies. Bump
   `HELP_FILES_VERSION` in `KnowledgeServerBootstrap.java:890-949` so the edited help re-ingests.
2. **Query history.** A local, per-profile recent-queries store (last 200, deduped, with
   timestamps), written on search submit, read by the composer (recents dropdown) and the
   Launchpad. Registered store; respects the encryption AUTHORED/DERIVED classification (629) —
   decision: **DERIVED** (recreatable, not sealed). Clearing history is a settings action.
   Do not resurrect `queryTrail.ts`; 851 deleted it for cause.
3. **Filters.** Add size range and folder-scope to `SearchFilterSpec` (`searchFiltersState.ts:13-16`
   has only the date range) and a user-facing **date-range control** (the backend
   `modified_at` range filter exists, `QueryFilterBuilder.java:196,257`, with no FE control).
   Folder scope = prefix filter on the `path` field; size = `LongPoint` range on the size field
   (add to `fields.v1.json` if absent — load `/ssot-catalog`).
4. **Launcher window + global hotkey** per 191 §A: a second `app.windows` entry (compact,
   always-on-top, frameless), its own capability file, `tauri-plugin-global-shortcut` registered
   from Rust with a default chord (decision: `Ctrl+Alt+Space`, rebindable in Settings), Windows
   focus-restore via `GetForegroundWindow`/`SetForegroundWindow` on show, Escape hides, Enter
   opens the main window on the query. The palette renders the existing search results
   projection — no new result component. Note 191:369 Wayland caveat (irrelevant on Windows;
   record it).

## Acceptance criteria

- `cd modules/ui-web && npm run typecheck && npm run test:unit:run`; ui-web gates via
  `node scripts/ci/run-ui-web-gates.mjs`; `check-store-recoverability` green for item 2.
- ui-shot steps added for: composer advanced toggle, recents dropdown, date/size/folder chips,
  launcher window; `check-ui-step-coverage` green. Measured (axe + contrast) via `ui_measure.py`.
- Live: `"invoice" AND path:contracts` returns filtered results with the toggle on and returns
  literal-escaped behavior with it off; hotkey summons the palette from another foreground app.
- `./gradlew.bat build -x test` + `:modules:ui:test :modules:adapters-lucene:test` for the filter
  field.

## Constraints

- Ranking changes (recency prior strength) are **out** — founder lane E.
- Result grouping / duplicate collapse is lane 897's measurement first; do not build it here.
- Keep Rust changes minimal and reviewable; `cargo test --lib --locked` must stay green;
  `modules/shell` changes may trigger `/installer` — load it if you touch `tauri.conf.json`.
- Non-goals: Explorer context menu, file associations (owner decision), OS notifications.

## Status

(unstarted)
