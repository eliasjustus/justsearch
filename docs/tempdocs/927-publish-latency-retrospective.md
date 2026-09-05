---
title: "PR #632 publication latency retrospective"
type: tempdocs
status: "INVESTIGATION COMPLETE — remediation not yet implemented"
created: 2026-09-04
updated: 2026-09-04
charter: "explain why an authorized publication took about 53 minutes and reduce an uncontended publish to a 12–20 minute target"
related:
  - 921-separate-pr-review-record-from-public-squash-record
  - 829-publish-workflow-develocity-analysis
---

# PR #632 publication latency retrospective

## 0. Verdict

The final authorized publication phase for PR #632 took about **53 minutes**, from
disabling auto-merge at 09:08:38Z to the replacement post-merge `main` run becoming
green at 10:00:45Z. That is too long for an already implemented change. It was not
one 53-minute command: it was two PR validation runs, one merge-group run, queue
contention behind PR #634, and a duplicate post-merge run.

The largest fixable cause was missed during the advisory-transport implementation.
The change disabled install-time npm audit only in two `public-claims` commands, but
the same unbounded npm advisory transport remained active in other `npm ci` call
sites. Hosted logs repeatedly show a silent five-minute interval followed by
`added ... packages in 5m`. The equivalent covered root install with
`--audit=false` completed in two seconds. These repeated five-minute stalls set the
critical path of the PR, merge-group, and preceding queue-entry runs.

This contradicts tempdoc 921 §25's statement that CI installs for already-covered
lockfiles use `--audit=false`. The implementation, its tests, and its reviews proved
the new advisory producer but did not prove that every install of those lockfiles had
stopped invoking npm's legacy audit transport.

The earlier work around the failed publish was longer still, but it was remediation
rather than publication: commit `32ff46a4` was created at 07:28:08Z, its hosted gate
failed at 07:36:50Z, and the durable identity-gate replacement was committed at
09:03:58Z. That approximately 96-minute interval investigated and replaced a broken
security-evidence design. It should not be described as normal publish latency.

## 1. Final publication wall clock

All times are UTC on 2026-09-04. Run timestamps come from GitHub's run/job API;
local transition timestamps come from the branch reflog and PR timeline.

| Interval | Duration | What happened | Classification |
|---|---:|---|---|
| 09:08:38–09:09:08 | 0:30 | Disabled auto-merge, refreshed title/body, pushed the final documentation checkpoint. | Necessary preparation, but ideally completed before invoking publish. |
| 09:09:08–09:17:42 | 8:34 | First PR-head CI run (`33856911065`). | Partly avoidable: `main` advanced at 09:13:40Z, but the stale-head run was allowed to finish. |
| 09:17:42–09:19:23 | 1:41 | Resolved the new `main` conflict, ran the required local full build/test commands, pushed, and rebound the managed review record. | Necessary and efficient; local build/test were cache-hot. |
| 09:19:23–09:31:40 | 12:17 | Second PR-head CI run (`33857760813`). | Necessary validation, excessively slow because install-time audit remained active. |
| 09:31:45–09:49:48 | 18:03 | Merge queue from enqueue to merge. PR #632's own merge-group run finished at 09:42:42; it then waited about 7:06 for preceding PR #634. | Merge-group validation is necessary; queue contention and slow CI inflated it. |
| 09:49:50–10:00:45 | 10:55 | The landed-SHA `main` run was superseded by PR #636 after 1:16; the publisher waited for the replacement `main` run (`33860368773`) to finish. | Avoidable synchronous latency; the exact landed SHA had already passed merge-group CI. |

The intervals total about 52 minutes from first mutation to final post-merge green;
closeout/read-back work brought the visible turn to roughly 53 minutes.

Evidence:

- [first final-head PR run](https://github.com/justsearch-app/justsearch/actions/runs/33856911065)
- [caught-up PR run](https://github.com/justsearch-app/justsearch/actions/runs/33857760813)
- [PR #632 merge-group run](https://github.com/justsearch-app/justsearch/actions/runs/33858796005)
- [preceding PR #634 merge-group run](https://github.com/justsearch-app/justsearch/actions/runs/33858750284)
- [superseded landed-SHA run](https://github.com/justsearch-app/justsearch/actions/runs/33860266074)
- [replacement current-main run](https://github.com/justsearch-app/justsearch/actions/runs/33860368773)

## 2. What consumed the time

### 2.1 Repeated implicit npm audit calls were the primary repository defect

Run `33857760813` provides the clearest control:

- `public-claims` root `npm ci --audit=false`: **2 seconds**;
- build-job root `npm ci`: **301 seconds**, ending `added 321 packages in 5m`;
- license-job `npm ci --prefix modules/ui-web`: **305 seconds**, ending
  `added 297 packages in 5m`;
- Gradle `assemble` invokes `NpmInstallTask`, whose command is still exactly
  `npm ci`: the log pauses before `added 302 packages in 5m`;
- wire-contract `npm ci --prefix scripts/wire-contract`: **about 301 of the
  step's 306 seconds**, ending `added 2 packages in 5m`.

The package count is not the explanation: even the two-package wire lockfile stalls
for five minutes. The two-second `--audit=false` control on the same runner family
isolates npm's install-time advisory request as the cause. `actions/setup-node` cache
is present, but npm audit occurs after package installation and is not made reliable
by the tarball cache.

The missing call sites on the landed PR include:

- `.github/workflows/ci.yml` license job (`modules/ui-web`);
- `.github/workflows/ci.yml` build job (root);
- `.github/workflows/ci.yml` wire-contract install;
- `build-logic/src/main/kotlin/conventions/NpmTasks.kt`, used by Gradle assembly;
- the runtime-client generator install added by the preceding PR;
- other workflows and the shell lockfile require an explicit coverage decision
  before their install-time audit can be disabled.

Because build-job installs are sequential, two or three five-minute stalls accumulate
instead of overlapping. Other jobs stall in parallel but still become the run's
critical path. This explains why recent successful CI medians are approximately
12.28 minutes for PR runs, 11.63 minutes for merge groups, and 9.65 minutes for
`main` pushes (last 40 completed CI runs inspected on 2026-09-04).

The repository's own wall-clock attribution tool confirms which job bounded each
relevant run. Its “fixed tax” class includes setup/install work, so the hosted log
inspection above is needed to separate the npm audit plateau from unavoidable runner
setup:

| Run | Critical path | Wall clock | Tool-classified fixed tax | Tool-classified work |
|---|---|---:|---:|---:|
| caught-up PR `33857760813` | Build | 11:28 | 5:34 | 5:50 |
| PR #632 merge group `33858796005` | Build | 10:21 | 5:59 | 4:19 |
| preceding PR #634 merge group `33858750284` | Public claims | 17:59 | 5:35 | 12:20 |
| replacement current-main `33860368773` | Build | 8:18 | 5:44 | 2:30 |

### 2.2 CI runs the full matrix three times

The workflow fires on `pull_request`, `merge_group`, and pushes to `main`. A normal
publication therefore runs essentially the same matrix on the PR head, the synthetic
merge-group head, and the landed head. Merge-group validation is necessary because
it tests the queue composition. The full post-merge run is duplicative when the
merge-group head SHA is exactly the landed commit SHA, as it was for PR #632:
`39a714d87fef7b9a76c90a3911cb84e3d4ce3651`.

The ruleset has no bypass actors and uses a squash merge queue with `ALLGREEN`, up to
five entries built/merged, and a 45-minute check-response timeout. If those premises
remain machine-checked, a full synchronous post-push replay does not add evidence for
an ordinary queued merge. It remains useful as an asynchronous drift/direct-push
sentinel only if a path can land without merge-group validation.

### 2.3 Queue contention added seven minutes after our own evidence was green

PR #632 entered at queue position 2. Its own merge-group run took 10:54 and was green
at 09:42:42Z, but PR #634 ahead of it did not finish until 09:49:40Z. PR #634's
18:25 run was itself dominated by sequential five-minute npm installs. Queue ordering
is correct; the avoidable part is making each entry occupy the queue for network
timeouts unrelated to its code.

### 2.4 A moving base caused one redundant PR run

PR #635 landed at 09:13:40Z while the first final-head run was active. The publisher
did not inspect the PR base again until that run completed at 09:17:42Z. GitHub then
reported the PR dirty. Monitoring the base/head mergeability while awaiting CI would
have recovered about four minutes in this instance and avoided completing evidence
that could not be used for enqueue.

### 2.5 The publisher waited synchronously for redundant post-merge evidence

After merge, the exact landed SHA had already passed the merge-group matrix. The
publisher nevertheless waited until a full `main` push run passed. That first run was
cancelled by the next queued merge, so it then waited for the replacement current-main
run. This added about 11 minutes to the answer without changing whether PR #632 was
published safely.

The publish workflow should still inspect that post-merge CI started and should alert
on a meaningful failure. It should not hold a successful publication response open
when the exact landed SHA already has a successful merge-group run.

## 3. Agent/tooling inefficiency

The polling strategy did not materially extend GitHub's wall clock, but it wasted
turns and tokens:

- dozens of 30–50 second status polls repeated unchanged state;
- `gh run watch` was initially invoked without `--compact`, producing more than
  10,000 lines before interruption;
- one broad `Select-String` over the tempdoc produced more than 27,000 lines and was
  truncated;
- repeated `pr checks` and full job JSON reads were used where one compact,
  event-driven waiter would suffice.

Use `gh run watch --compact --interval 30 --exit-status` as the immediate fallback.
The repository should add a compact `run-wait` sibling to `checks-wait` that reports
only state transitions, active critical-path jobs, base-branch movement, queue
position, and the final verdict. Polling should not be narrated when nothing changed.

The context compaction before final publication was not a material delay. Its state
summary correctly preserved the worktree, PR, verification, and next actions. The
dedicated worktree also prevented unrelated dirty work in the shared `main` checkout
from contaminating publication. Both behaviors should be preserved.

## 4. Verification and review gaps

1. Tests asserted the new producer and two explicit `--audit=false` commands, not the
   invariant that **every** installation of a covered lockfile disables npm audit.
2. No registry connects each repository lockfile to its security-evidence authority.
   As a result, root/UI were covered while wire-contract, shell, and the later runtime
   client had ambiguous authority.
3. The live producer proof established that GitHub advisory GETs completed in about
   two seconds. It did not measure all hosted `npm ci` call sites before publication.
4. The tempdoc and review record overclaimed retirement of duplicate install-time
   advisory POSTs. Hosted logs refute that claim.
5. Raising public-claims timeout headroom to 30 minutes prevented an aggregate timeout
   from hiding individual verdicts, but it also made this latency easier to tolerate.
   The timeout is containment, not remediation.

This is a substantive review miss, not cosmetic cleanup. A workflow-wide negative
test should have failed the change before merge.

## 5. Recommended changes

### P0 — retire install-time npm audit completely and prove it

1. Create a lockfile/security-authority register for every non-fixture lockfile:
   root, UI, shell, wire-contract, runtime-client tooling, and any release-only npm
   lockfiles.
2. Expand the GitHub advisory identity producer/baseline to every lockfile that CI or
   release workflows install. Explicitly classify fixture-only and non-shipping tool
   locks rather than silently omitting them.
3. Once coverage exists, set `NPM_CONFIG_AUDIT=false` centrally for CI and pass
   `--audit=false` from `NpmInstallTask`. The environment-level default covers nested
   Gradle invocations that command-by-command edits miss.
4. Add a regression scanner over workflows, scripts, and build logic that rejects an
   install path whose lockfile is unregistered or whose implicit npm audit remains
   enabled.
5. Add a hosted timing assertion/telemetry warning when a small `npm ci` exceeds 60
   seconds. Five-minute plateaus should be actionable, not normalized.

Expected effect: the caught-up PR run should fall from about 12 minutes toward the
4–6 minute non-install critical path; the merge-group run should be bounded mainly by
integration tests (about 5–8 minutes under observed load), not advisory timeouts.

### P1 — remove synchronous duplicate post-merge validation

1. Keep required PR and merge-group checks.
2. If branch rules continue to guarantee no bypass and the landed SHA equals a green
   merge-group SHA, treat merge as the synchronous publication terminal condition.
3. Either make `push: main` CI lightweight, reuse the successful same-SHA evidence,
   or keep the full run asynchronous with failure notification.
4. Add a premise probe for merge-queue enforcement/no bypass if publication relies on
   this optimization.

Expected effect: remove about 9–11 minutes from the user-visible tail without reducing
pre-merge evidence.

### P1 — make publication waits event-driven and base-aware

1. While awaiting PR checks, watch the live base OID and mergeability. Abort stale
   evidence immediately when the base moves into a conflicting state.
2. After enqueue, report queue position separately from validation time.
3. Add a compact waiter instead of repeated manual polling.

Expected effect in this incident: about four minutes saved before the conflict
resolution, plus substantially lower token/tool noise.

### P2 — establish a publish latency budget

- Target a ready, uncontended PR at **12–20 minutes from authorization to merge**.
- Report three separate numbers: local preparation, required validation, and external
  queue contention.
- Do not describe transport remediation or implementation after a red run as publish
  time.
- Start the publish skill only after the implementation, tempdoc, external-setting
  mutations, and review record content are final; avoid a last-minute branch commit.

## 6. Reproduction and evidence commands

The investigation used read-only repository/GitHub commands:

```powershell
git reflog --date=iso --format="%gd|%cd|%h|%gs"
node scripts/dev/run-gh.mjs run view <run-id> --json createdAt,updatedAt,event,status,conclusion,headSha,jobs
node scripts/dev/run-gh.mjs run view <run-id> --job <job-id> --log
node scripts/dev/run-gh.mjs run list --workflow CI --limit 40 --json databaseId,event,conclusion,status,createdAt,updatedAt
node scripts/dev/run-gh.mjs api repos/justsearch-app/justsearch/rulesets/20851694
rg -n "npm ci|audit=false" .github scripts modules build-logic
```

The shared `main` checkout contained unrelated concurrent dirty work throughout this
investigation and was not edited. This tempdoc was created in the dedicated
`codex/927-publish-latency-retro` worktree from `origin/main` at `b9811a6d`.
No private/archive-only evidence is required to reproduce the conclusions.

## 7. Prompt/process assessment

The user's `publish`, `investigate`, `resume`, and `proceed with all of this work`
prompts were not confusing and did not cause the delay. The failure was process and
implementation scope: publication exposed a hosted transport problem; the first fix
covered the producer but not every install consumer; the final publisher then followed
an overly synchronous post-merge interpretation. A useful future prompt can simply say
`publish`; the publish skill and CI should supply an efficient, safe default without
requiring the user to specify latency policy.

## 8. What worked and should remain

- Fail-closed advisory identity evidence caught the hosted failure instead of
  publishing zero/unknown security evidence.
- The managed review record and squash preview stayed correctly bound through two
  head changes.
- Merge-group CI validated the exact landed SHA, and stable patch-id comparison proved
  that the reviewed patch landed unchanged.
- Full local build/test after the semantic merge rule was cheap on a warm cache and
  protected against silent integration conflicts.
- Concurrency cancellation correctly stopped the obsolete first post-merge `main`
  run after the next commit landed.
- The dedicated worktree and compaction state prevented unrelated work from being
  overwritten or the task from restarting after context compaction.
