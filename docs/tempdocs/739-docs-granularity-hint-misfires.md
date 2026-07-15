---
title: "739 — Why docs-ride-along was ignored: a hint that misfired, and an instruction that contradicted it"
type: tempdoc
status: implemented; hook + rule fixes committed on branch `fix/docs-granularity-hint-misfires` (aad467ac, a7a491ff, 1cdd20fc). Unpushed, no PR yet. One owner decision open (§6).
created: 2026-07-15
updated: 2026-07-15
related:
  - 653 (ADR-0045 axis-2 — the rule and hook this corrects)
  - ADR-0045 (public main as a squash-projected narrative)
  - .claude/rules/branch-safety.md (`docs-ride-along`, tier-register row 36)
  - 727 (session-transcript friction mining — same method, different subject)
---

# 739 — Why `docs-ride-along` was ignored

## 1. Question

Public `main` carries ~22 single-file tempdoc/observation commits published as
standalone PRs. `docs-ride-along` forbids exactly that, and the
`docs-granularity-hint` hook delivers the rule at `git push`. So why the gap?

The framing "agents ignore the hook" and "the rule is unfollowable" produce an
identical commit list. This doc records which one it actually was.

## 2. Method

Grepped 120 session transcripts for the hint's text; 12 contained it. Three
subagents read the firing windows and reported verbatim quotes with transcript
IDs. Every load-bearing claim was then re-verified against primary sources
(the hook's own code, the skill text, `origin/main`) before being acted on —
see §5 for one that did not survive that check.

## 3. Findings

**F-1 — The hint fired on things that were not pushes.** `isGitPush` tested the
whole raw command string, so `push` in *argument* position matched. Observed
firing on `git commit -m "…git push…"` and
`node check-always-loaded-budget.mjs --reason "…git push is allowed…"`.
Evidence: reproduced against the pre-fix regex; now pinned as tests.

**F-2 — The hint diffed the wrong repository.** `branchChangedFiles(input.cwd)`
never parsed `-C`, so `git -C <worktree> push` diffed whatever tree the agent
stood in. This repo pushes from worktrees constantly, so this was the dominant
misfire: one transcript shows the hint asserting "this branch changes ONLY dated
working history" on a push of two Java files. The regex matched `-C` but never
extracted it.

**F-3 — The strongest driver was an instruction telling agents to do the
opposite.** `.claude/skills/publish/SKILL.md` — invoked at every merge — read:

> "…so fold it into a small docs-only PR (squash merge, no required reviewers)
> instead of committing it directly…"

That manufactures the artifact the hint flags. Agents resolved the conflict in
favour of the specific, imperative, user-authored instruction over the general
advisory hint, and said so explicitly ("I'm proceeding … because that's exactly
your instruction #8"; "the user's explicit instruction for this exact scenario …
is what I'm following"). This is correct precedence resolution, not
non-compliance.

**F-4 — The post-merge fold had no compliant path.** `branch-safety.md` step 4
tells agents to fold observation shards *after* merge, on `main`, where no code
PR remains to ride along with — and branch protection blocks direct pushes to
`main`, so it must become a PR. The fold *is* the periodic batch the rule
permits, but that was never stated, so agents agonized: one abandoned a correct
fold, raced a conflict, and left PR #169 open and unmerged.

**F-5 — The hint fires on compliant batches too.** It cannot distinguish a
single note from a legitimate batch, so agents correctly dismissed it ("#160 is
exactly the endorsed batched pattern, so no change needed"). A hint that fires
on correct behaviour trains its own discounting. See §6.

**F-6 — Precedent-chaining is the one genuine agent failure.** PRs #98 and #100
cited an earlier PR as licence rather than re-qualifying ("same precedent as
PR #93…", "same precedent as #93/#98") — verbatim in transcript
`f9baeb69`. See §5 for the correction to this finding's detail.

## 4. What shipped

| Change | File |
|---|---|
| `isGitPush` requires command position; strips heredoc bodies; excludes branch deletions | `scripts/agent-analytics/hooks/docs-granularity-hint.mjs` |
| New `gitPushCwd` resolves `-C <path>` / `cd <path> &&`; `main()` diffs the pushed tree | same |
| 12 new tests, 3 of which reproduce a real misfire against the pre-fix regex | `docs-granularity-hint.test.mjs` |
| Post-merge fold named as the sanctioned periodic batch (closes F-4) | `.claude/rules/branch-safety.md` |
| Precedent-chaining named as a predictable evasion (closes F-6) | same |

**Not in this repo:** the F-3 root cause lives in `.claude/skills/publish/SKILL.md`,
which is untracked and local-only by design (the tracked-skill set is domain
skills plus four orchestration skills accepted after a `git add -A` leak — see
`accepted-tracked-skills-no-removal`). It was corrected locally to route working
history into the periodic batch. **A fresh checkout does not inherit that fix**,
so any environment with its own `publish` skill must apply it independently or
F-3 recurs.

## 5. Verification evidence

| Claim | Evidence |
|---|---|
| F-1 reproduces against pre-fix code | 3 cases fire on the old regex, silent on the new — pinned in `docs-granularity-hint.test.mjs` |
| F-2 fix works end-to-end | Live probe, throwaway docs-only worktree: docs-only `-C` push MISSED pre-fix → fires post-fix; code push from docs-only cwd wrongly FIRED pre-fix → silent post-fix |
| Deletion check does not swallow refspecs / Windows paths | `git push origin HEAD:refs/heads/foo` and `git -C C:/Users/x/wt push` both still detected — pinned as tests |
| Hook suite green | `node scripts/agent-analytics/hooks/docs-granularity-hint.test.mjs` → 23/23 |
| No neighbouring hook broken | All 15 `scripts/agent-analytics/hooks/*.test.mjs` suites green |
| Gates green | `--gate hook-integrity` pass; `--gate prose-tier-register` pass |
| F-3 skill text | Read directly at `.claude/skills/publish/SKILL.md:7` (not via audit) |
| F-6 quotes are real | Grepped verbatim from transcript `f9baeb69` |

**Correction recorded (`audit-without-test`, self-inflicted).** F-6 was first
written into the rule as "each single-file tempdoc PR citing the last" — an audit
claim that went in unverified. Measured against `origin/main`: PR #93 = 6 files
including `docs/reference/search-quality-register.md` (canonical, which the rule
lets stand alone, and which the hint would never fire on); PR #98 = 1 file; PR
#100 = 2 files. The *behaviour* is real; the characterization was false. Rule
reworded in 1cdd20fc. The lesson is the one already in `CLAUDE.md`: an audit's
claim is a hypothesis until checked, and it went into a rule before it was.

## 6. Open decision (owner)

**Should the hint fire only on single-file archaeology branches?** F-5 says it
currently fires on compliant batches, which is why agents learned to dismiss it.
A single-file threshold would target precisely the ~22 commits at issue and stop
crying wolf on the ~32 legitimate batched ones. This changes what the rule
*means* (policy), not just whether the hook is correct (a bug), so it was not
done unilaterally.

Sequenced deliberately: **do not raise `docs-ride-along` from `hook-hint` to a
blocking tier until this lands and the signal is true.** Blocking on a hook with
two false-positive classes would have hard-blocked the `/publish` instruction
itself and driven agents to `JUSTSEARCH_DISABLE_HOOKS=1`.

## 7. Follow-ups / deferred

- **`always-loaded-budget` is RED on `origin/main`** — 4 files over ceiling
  (`CLAUDE.md` +1604 B, `branch-safety.md` +1892 B, `hooks-reference.md` +99 B,
  `tier-register.md` +1579 B). Pre-existing, not from this branch. This branch
  adds +452 B to an already-over file. Logged to the inbox. A permanently-red
  ratchet is a ratchet nobody reads.
- **`check-tempdoc-numbers` reports 3 pre-existing collisions** (#720, #734,
  #737) across other worktrees. Not this branch's; renumbering another agent's
  in-flight work would be destructive.
- **PR #169** (`docs/obs-shard-pilot-724`) appears still open/unmerged with a
  live worktree — the F-4 casualty. Worth closing out.
- **`./gradlew.bat build -x test` not run.** No Java/TS touched, and
  `branch-safety.md` warns only one Gradle build should run at a time across
  agents; ~11 worktrees were live. Required before marking the PR ready.

## 8. Unverified assumptions

- **Transcript quotes beyond F-6 were subagent-reported and not independently
  re-read.** F-3's skill text and F-6's quotes were verified at primary source;
  the remaining quoted reasons (e.g. the "instruction #8" citations in
  `4bd6a45f`, `672f00c0`) were not. They are corroborated by the skill text
  existing verbatim, but treat the specific quotes as audit-tier.
- **The ~22 count** is from a `git log` file-count scan of `origin/main`
  (commits touching only `docs/tempdocs/**` / `docs/observations*`, exactly one
  file). The 28% archaeology-only figure includes compliant batches.
- **The hook's behaviour under a `cd` chain into a non-repo directory** is
  fail-open by construction but was not probed.
- **Whether F-3 recurs** depends on each environment's local `publish` skill;
  unknowable from this repo.
