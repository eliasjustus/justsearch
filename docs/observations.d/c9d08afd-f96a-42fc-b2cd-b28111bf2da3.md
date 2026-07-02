# Observations shard — session c9d08afd-f96a-42fc-b2cd-b28111bf2da3

> Per-session inbox shard (tempdoc 618 Seam C). Append-only; do not share with
> other sessions. Folded into docs/observations.md `## Inbox` by
> `node scripts/agent-analytics/fold-observations.mjs`.

- [ ] BrowseOperationHandler never reads the list_files arg despite justsearch_browse's tool description promising an explicit override — only auto-detect (no subfolders -> list files) is implemented — `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/BrowseOperationHandler.java` (2026-07-02)
- [ ] modules/ui-web's `npm run typecheck` is currently broken repo-wide (pre-existing, unrelated to my change): TS 6.0.3 rejects tsconfig.json's baseUrl as a hard error (TS5101), and with --ignoreDeprecations 6.0 there are ~60 further pre-existing errors (missing global/node types in tests, CSS side-effect imports, .ts-extension imports) across files I never touched (2026-07-02)
- [ ] Pre-existing failing/flaky vitest: HealthLitView.test.ts > 'connection badge shows a soft paused (warning, not error)...' fails deterministically (real 850ms setTimeout vs 750ms debounce, no fake timers) — reproduces identically with all my tempdoc-655 changes stashed away, so unrelated to this work — modules/ui-web/src/shell-v0/views/HealthLitView.test.ts:313 (2026-07-02)
