# Observations shard — session c9d08afd-f96a-42fc-b2cd-b28111bf2da3

> Per-session inbox shard (tempdoc 618 Seam C). Append-only; do not share with
> other sessions. Folded into docs/observations.md `## Inbox` by
> `node scripts/agent-analytics/fold-observations.mjs`.

- [ ] BrowseOperationHandler never reads the list_files arg despite justsearch_browse's tool description promising an explicit override — only auto-detect (no subfolders -> list files) is implemented — `modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/BrowseOperationHandler.java` (2026-07-02)
