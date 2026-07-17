# Observations shard — session cfa87fbc-7be4-4f7f-bab7-f6df7072e627

> Per-session inbox shard (tempdoc 618 Seam C). Append-only; do not share with
> other sessions. Folded into docs/observations.md `## Inbox` by
> `node scripts/agent-analytics/fold-observations.mjs`.

- [ ] record-merge mis-link REPRODUCED a third time (sessions 25f8ac5d, c226227a logged it 2026-07-14): remove-worktree.cjs auto-linked session cfa87fbc to e608f75b (a fold commit made minutes after) instead of its own PR squash cef7a91e — three independent instances now; the fix is to capture the PR's mergeCommit oid at teardown rather than reading main's HEAD — `scripts/dev/remove-worktree.cjs` (2026-07-17)
