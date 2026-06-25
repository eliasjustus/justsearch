# UI module (headless backend + Local API)

The `modules/ui` project hosts the **headless JustSearch runtime** and the **Local API server** used by:

- the shipped desktop app (Tauri + web UI), and
- local development (`npm --prefix ./modules/ui-web run dev:all` starts `:modules:ui:runHeadless`).

This module is intentionally **headless**: it initializes App Services, starts the Local API server (`/api/*`), and runs background workers. It does **not** include a JavaFX desktop UI layer in this repo.

## Primary entrypoints

- `io.justsearch.ui.HeadlessApp` (used by `:modules:ui:runHeadless`)
- `io.justsearch.ui.api.LocalApiServer` (serves `/api/status`, `/api/health`, `/api/diagnostics/export`, `/api/debug/*`, etc.)

## Evidence capture

The legacy JavaFX UI automation lane has been **removed**. Canonical evidence capture is now defined by EvidenceBundle v1 (Playwright + backend snapshots).

See:

- `docs/tempdocs/13/00-evidencebundle-v1-spec.md`
