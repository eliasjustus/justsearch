# Observations inbox shards (tempdoc 618 Seam C)

Per-session shards for the `docs/observations.md` `## Inbox`. **Do not append to
`observations.md` directly** — on a contended multi-agent `main`, a neighbour's
commit/reset silently wipes an un-committed append (618 §4/§9/§12, reproduced as
data loss). Each session writes only its own file here, so two writers never
touch the same bytes — clobber is impossible by construction (the
worktree-isolation invariant applied to the inbox; the same shape as governance
`.changesets/` and the agent-telemetry per-session files).

## How to log an observation

```
node scripts/agent-analytics/note-observation.mjs "<description> — `optional/file:line`"
```

This resolves your session id (the `current-session-id` pointer written by the
`export-session-env` hook, with env/worktree fallbacks) and appends a
canonically-formatted entry to `<session-id>.md` here, creating it if absent. The
shard is committed with your own work in your own worktree, so the note is
durable in git the instant you commit — independent of any reconcile step.

## Reconcile into the curated inbox

```
node scripts/agent-analytics/fold-observations.mjs            # dry run
node scripts/agent-analytics/fold-observations.mjs --apply    # fold + remove consumed shards
```

Append-only, deduplicating, idempotent: it writes `observations.md` first, then
deletes the consumed shards, so a crash mid-fold loses nothing (the shards are
committed). Run it manually, or at merge next to `record-merge.mjs`. Correctness
of the data does not depend on the fold running — it is consolidation, not
durability.
