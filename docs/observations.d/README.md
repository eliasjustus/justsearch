# Observations shards (tempdoc 618 Seam C → tempdoc 680 conditions store → tempdoc 862 shard key)

Per-writer shards feeding the `docs/observations.md` `## Conditions` store.
**Do not append to `observations.md` directly** — on a contended multi-agent
`main`, a neighbour's commit/reset silently wipes an un-committed append
(618 §4/§9/§12, reproduced as data loss). Each writer writes only its own file
here, so two writers never touch the same bytes — clobber is impossible by
construction (the worktree-isolation invariant applied to the inbox; the same
shape as governance `.changesets/` and the agent-telemetry per-session files).

## The contract: keyed by the writing tree, not by the actor

```
<session-id>.md                 written from the home checkout
<session-id>.<worktree>.md      written from a linked worktree
```

618 keyed the shard by **session**, on the assumption that one session means one
writer. The delegate model broke that: a subagent inherits its parent's
`CLAUDE_CODE_SESSION_ID`, so an orchestrator and every worker it spawned resolved
one shard path across several worktrees — nine independent writers on one file in
the 859 wave, and a hand-resolved merge conflict on every catch-up pull (tempdoc
862 §A).

Contention is not a property of the actor; it is a property of the tree that
merges. Two writers in one worktree cannot conflict (one file, one branch,
append-only); two writers in different worktrees always can. So the **name** now
varies with the writing tree, while the session id stays in the name and in the
shard header as the **attribution** fact. Generally: *a per-X file that exists to
prevent write contention must be keyed by the concurrency domain, not by the
actor.*

Consequences worth knowing:

- **Shard count grows between folds** — one file per (session, tree) rather than
  per session. That is expected, not leakage; the fold drains all of them.
- **Nothing is renamed.** The home checkout still writes the bare
  `<session-id>.md`, byte-identical to before, so shards already in flight and
  already in history stay valid.
- **The name is parsed in exactly one place** —
  `recover-merge-links.mjs` (tempdoc 856) recovers session→merge links from shard
  filenames and strips the last dot-segment to get the parent session id. The
  writer component therefore must never contain a dot; `note-observation.mjs`'s
  `sanitizeWriter` enforces that. Everything else (including the fold) globs the
  directory and never parses the name.

## How to log an observation

```
node scripts/agent-analytics/note-observation.mjs "<description> — `optional/file:line`"
```

This resolves your session id (the `current-session-id` pointer written by the
`export-session-env` hook, with env/worktree fallbacks) and your writing tree,
then appends a canonically-formatted entry to your shard here, creating it if
absent. The shard is committed with your own work in your own worktree, so the
note is durable in git the instant you commit — independent of any reconcile step.

## Reconcile into the conditions store

```
node scripts/agent-analytics/fold-observations.mjs            # dry run
node scripts/agent-analytics/fold-observations.mjs --apply    # fold + remove consumed shards
```

The fold resolves IDENTITY (tempdoc 680): each shard entry either merges into an
existing condition (occurrence appended, `seen` incremented — write blind, never
check for duplicates; recurrence is the triage ranking) or opens a new condition
with a proposed kind for triage to confirm. It writes `observations.md` first,
then deletes the consumed shards, so a crash mid-fold loses nothing (the shards
are committed). Run it at merge teardown next to `record-merge.mjs`. Correctness
of the data does not depend on the fold running — it is consolidation, not
durability.

## After a fold: keep only the entries that were not folded

The fold **deletes** each shard it consumes. A branch cut before the fold still
carries those entries, so merging it can resurrect a shard the fold already
drained. When that happens, keep **only the entries not already folded** — check
`docs/observations.md` and drop the rest.

This needs a human because a blind re-add is not harmless. An entry whose
condition still exists and still matches is absorbed silently (the fold dedupes on
the exact occurrence line). But an entry whose condition was **retired, re-titled
or re-anchored** by triage since the fold no longer matches anything, so it
**re-opens as a new condition** — resurrecting work that was deliberately closed.
One arc came within 57 entries of exactly this.

It is also why `docs/observations.d/*.md merge=union` is deliberately **not** set
in `.gitattributes` (tempdoc 862 §D.2): a content merge driver cannot know an
entry was already drained, it never runs at all for the modify/delete conflict
this shape actually produces, and it does not run in GitHub's server-side merge
anyway. It would trade a legible conflict for a silent wrong merge in the store
the whole mechanism exists to protect. After the per-writer key above, no two
writers share a shard path, so there is nothing left for it to resolve.
