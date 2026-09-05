---
classification: new-rule-registered
tempdoc: 734
---
New rule 42 (`subset-isnt-the-suite`) registered at `hook-hint` tier in the
`.claude/rules/agent-lessons.md` Named substrate-discipline principles section.

`subset-isnt-the-suite` already existed as a named principle in `agent-lessons.md`,
resolving to `docs/reference/contributing/agent-postmortems.md` §13 (tempdoc 618 §10c), but
carried no `<!-- rule:... -->` anchor and no tier-register row — it was informally prose-only
(agent discretion, no mechanical delivery), not a formally registered rule. This is therefore
`new-rule-registered`, not `tier-change`: there is no prior row to change the tier of.

This branch's merge of `origin/main` reproduced the exact failure mode the principle names. Git
reported FOUR textual conflicts; a FIFTH went unmarked. Our side (tempdoc 726 F1) made
`tryFinalizeRebuild()` require two consecutive `pending==0` reads (a mid-flush-race guard);
main's side (tempdoc 730) made `finalizeShutdownCommit()` call `tryFinalizeRebuild()` exactly
once, at shutdown. Each is correct alone. Merged, a worker stopping right after a rebuild
completes has a zero streak, the single call declines, the fingerprint stamp never persists, and
the next boot silently re-flags the index as needing a rebuild — reopening the exact hole main's
fix existed to close. It auto-merged clean, no conflict marker, and was caught only because
someone ran the FULL suite instead of the affected modules.

Tier is `hook-hint`, not `hook` (blocking): a hook cannot know whether tests actually ran
afterward, and blocking a merge outright would be wrong — conflict resolution is often iterative
across several tool calls, and the actual verification step (running the full suite) happens in
a later, separate tool call this hook has no visibility into. So salience is mechanized at the
merge boundary; the verification itself stays the agent's judgment call — same shape as
`piped-exit-masked` (row 37) / `docs-ride-along` (row 36).

`merge-full-suite-hint.mjs` (PostToolUse Bash) fires on any `git merge` invocation except
`--abort` (which undoes rather than completes a merge) — regardless of the command's own exit
code, because the fifth-conflict class this hook warns about can hide inside an otherwise
CONFLICTED invocation (four marked conflicts and one silent auto-merge, in the same `git merge`
call) — non-blocking, fail-open, honors `JUSTSEARCH_DISABLE_HOOKS=1`.

Its manifest bite spec is `command-signal`, not `unit`: the enforcer spawns the hook against a
crafted `git merge origin/main` stdin and asserts the advisory text is actually emitted on
stdout, rather than asserting only that a test *file* exists. `hook-integrity`'s bite loop is
blocking-role-only (`enforcer.mjs`'s `if (entry.role !== 'blocking') continue`), so this manifest
bite is not gate-checked for an advisory hook — it is included anyway as stronger, executable
evidence than the `unit` kind (`existsSync(testPath)` alone proves presence, not firing), matching
the standard this same tempdoc's evidence names one layer up (22 real tests that nothing ever
ran). Verified directly: firing on a crafted `git merge` PostToolUse stdin, and staying silent
(exit 0, no stdout) on both a non-merge command and `git merge --abort`.

The `agent-lessons.md` prose bullet is kept in place, now carrying the anchor and a pointer to
the hook, as the always-loaded fallback for a checkout without wired hooks.
