---
name: publish
description: >-
  Publish the current tempdoc's work through a pull request: catch up with main,
  run full verification, scan public content, validate the squash record, use
  the merge queue, and confirm post-merge CI.
---

# Publish

Publish only the work in the active tempdoc or explicitly named change set.
Invoking this skill does not broaden the change or authorize a release,
deployment, destructive cleanup, or unrelated maintenance.

## Authorization boundary

Before pushing, opening or updating a pull request, or enqueuing a merge, verify
that the user's current request explicitly authorizes that action. A direct,
unambiguous request to run `$publish` for the current work supplies authorization
for the ordinary branch push, pull-request, and merge-queue workflow. If the
request is only to inspect, prepare, or dry-run publication, stop before the
first external mutation.

The root agent performs the final merge action. Bounded preparation may be
delegated only when active system and session rules permit it; never delegate
merging, evidence judgment, destructive git operations, or shared-state cleanup.

## Prepare the candidate

1. Run the world-state report and verify the current worktree, branch, base, and
   active tempdoc. Never publish from a dirty shared `main` checkout.
2. Fetch the remote and incorporate the latest `origin/main` into the candidate
   without discarding work. Resolve conflicts by preserving both current project
   behavior and the tempdoc's acceptance contract.
3. Review the complete branch diff and ensure it contains only intended files.
4. Run the full repository verification suite against the caught-up candidate,
   not merely a compilation check. Also run every subject-specific gate required
   by `AGENTS.md` and the contributing documentation.

## Pre-publication review

Before the first push, scan the diff for credentials, API keys, internal-only
URLs, machine-local paths, generated local state, and other material that must
not become public. A later CI secret scan is not a safe substitute.

Review outward-facing prose for quantitative claims. Every number must point to
a reproducible measurement or primary evidence; remove unsupported claims rather
than approximating them.

Create or update the pull request with a precise title, summary, and testing
section. Include a standalone `Session-Id: <session uuid>` line in the body so
ADR-0045 attribution survives the squash merge. Then run:

```text
node scripts/ci/preview-squash-message.mjs --pr <number>
```

Fix every reported title, body, WIP, attribution, or testing defect before
enqueueing the merge.

## Wait for CI

Avoid chained blind polling. Start the repository's bounded watcher and retain
the returned terminal/session identifier:

```text
node scripts/dev/run-gh.mjs checks-wait <number> --required-only
```

Wait on that session using the available terminal wait mechanism. The wrapper
handles the registration race where GitHub initially reports no checks and
implements the required-check exit contract. For a multi-hour supervised run,
use `node scripts/dev/run-watcher.mjs` so progress, stalls, and completion remain
observable.

A failing advisory check must be reported and investigated on its own merits,
but it is not a merge gate. Never rerun or mask an advisory failure merely to
make the overall check list green. If GitHub reports `UNSTABLE`, use
`gh pr checks <number> --required` to prove that every failure is genuinely
non-required before continuing.

## Merge queue and completion

When the pull request's required checks are green, run `gh pr merge <number>`
without a strategy flag. The protected `main` branch uses the squash merge queue;
the queue runs its own merge-group checks. If the entry leaves the queue while
the pull request remains open, inspect the failed merge-group run and fix the
cause before re-enqueueing. Do not blind-retry.

After the queue reports success:

1. Fetch `origin/main` and verify the published content by diff, not branch
   ancestry alone.
2. Confirm public CI on `main` is green. A new failure caused by this publication
   remains part of the task.
3. Update the tempdoc outcome and identify any genuine follow-up work. Do not
   create speculative follow-up tempdocs merely to empty a checklist.
4. Report what was published, the verification evidence, the merge result, and
   any remaining risk. Clean up only worktrees and branches owned by this task,
   and only when the repository's safety rules permit it.

The full GitHub and merge-queue quirk catalog remains in
`docs/reference/contributing/agent-guide.md` §3.7.
