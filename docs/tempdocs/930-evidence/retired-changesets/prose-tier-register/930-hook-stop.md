---
classification: tier-change
tempdoc: 930
---
Tempdoc 930 §18.1 row 4 retired the Bash guard and 21 advisory hint hooks, so eight rows
lose the tier that enforced them. No rule row was removed — every rule survives, only its
enforcement tier changed:

- Rule 19 (`never-checkout-in-main`), `hook` → `prose-only`
- Rule 23 (`never-destructive-git-in-main`), `hook` → `prose-only`
- Rule 25 (`never-force-push`), `hook` → `harness` (a NEW tier, added in this PR)
- Rule 32 (`tempdocs-are-dated-history`), `hook-hint` → `prose-only`
- Rule 36 (`docs-ride-along`), `hook-hint` → `prose-only`
- Rule 37 (`piped-exit-masked`), `hook-hint` → `prose-only`
- Rule 41 (`edit-reread-cross-root`), `hook-hint` → `prose-only`
- Rule 42 (`subset-isnt-the-suite`), `hook-hint` → `prose-only`

Evidence (930 §13.2, reconstructed from 30 days / 1,174 transcripts because the hook layer
emits no telemetry about its own effect): the guard's git rules fired 27 times with **0 true
positives and 11 of 11 recoverable blocks false** — worktree-legitimate `reset --hard` /
`checkout --detach` / `checkout --ours` after a `cd` inside a compound command, plus three
force-push blocks whose `[^"']*` regex spanned `&&` into a later `gh workflow run -f`. Zero
destructive commands in the main checkout were intercepted. The hint tier has exactly one
measured study (739 §2, `docs-ride-along`) and it was negative. Rules 19/23 therefore drop to
the tier that was already doing the work, and a native rule is not a substitute:
`permissions.deny` cannot be scoped to "main worktree only", so it would block the legitimate
worktree use that was 100% of observed traffic.

Rule 25 is the exception and the reason for the new `harness` tier. Force-push maps cleanly
onto two `permissions.deny` prefix rules in `.claude/settings.json`
(`Bash(git push --force*)`, `Bash(git push -f*)`), and 930 F1 probed the semantics live: deny
IS enforced under `defaultMode: bypassPermissions`, takes effect without a restart, is
evaluated per segment of a compound command, and matches prefix-only — so it keeps ~100%
adherence and drops the `-f sign=true` false-positive class. `harness` exists because the
refusal is the Claude Code harness's own, not a project artifact: no gate id, hook file, or
ArchUnit class resolves it, so `prose-only` (~70%) would understate it and `hook` would
dangle a marker the `hook-integrity` gate must resolve. Known gap, stated in the row: the
refspec form `git push origin +main:main` is not expressible as a prefix rule and is
uncovered.
