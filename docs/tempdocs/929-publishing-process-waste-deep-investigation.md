---
title: "Publishing-process waste: transcript-wide deep investigation"
type: tempdocs
status: "INVESTIGATION COMPLETE — recommendations are evidence-backed but not implemented"
created: 2026-09-04
updated: 2026-09-04
charter: "identify the dominant avoidable time, attention, and token costs in recent JustSearch publication work; distinguish current defects from historical or already-remediated friction; and evaluate alternative process designs"
related:
  - 927-publish-latency-retrospective
  - 829-publish-workflow-develocity-analysis
  - 727-session-transcript-friction-mining
---

# Publishing-process waste: transcript-wide deep investigation

## 0. Question and acceptance contract

This investigation asks which parts of recent publishing work consume time without
adding proportionate safety or evidence. It treats wall-clock latency, agent attention,
tool/API traffic, context/token replay, CI compute, and review confidence as separate
cost axes rather than collapsing them into one score.

The investigation is complete only when it:

- [x] defines a reproducible recent-transcript sample and its coverage limits;
- [x] separates implementation/remediation discovered during publication from the
  publication phase itself;
- [x] reconstructs representative publication timelines from primary evidence;
- [x] quantifies recurring waste where the evidence supports a number and labels
  directional findings where it does not;
- [x] distinguishes current waste from mechanisms already remediated after earlier
  campaigns;
- [x] challenges the leading explanations with plausible alternatives and
  counterexamples;
- [x] records broad theorization before settling recommendations;
- [x] decides whether an external internet research pass is warranted and records
  the result;
- [x] ranks interventions by expected leverage, safety risk, and evidence needed to
  validate them.

## 1. Initial scope

Primary evidence will come from recent local Claude and Codex transcripts for
JustSearch publishing sessions, repository-native agent analytics, git/reflog history,
and already-captured GitHub Actions/run evidence. Tempdocs 927 and 829 are starting
evidence, not conclusions to repeat uncritically.

The default analysis window is 2026-08-25 through 2026-09-04, with the measured
2026-08-13 publication campaign used as a historical comparison. Raw transcript
content is private working evidence and will not be copied into this public-history
tempdoc; only reproducible aggregate facts and short, non-sensitive descriptions will
be recorded.

## 2. Evidence log

### 2.1 Sample construction

A lexical scan of main-session transcripts in the stated window found 31 segments
that mentioned publishing. That is not the analytic sample: it included negative
instructions ("do not publish"), questions about an earlier publish, status questions,
subagent notifications, skill bodies, and mixed turns that requested implementation
before publication. Manual adjudication retained six completed Codex publication
cases with public PR/run evidence, plus the earlier Claude sessions and the measured
2026-08-13 campaign as comparison evidence.

The six current-process cases are PRs #623, #630, #632, #633, #634, and #636. They
are deliberately not collapsed into a median because their scopes differ:

- #623 published a Codex migration and repaired a cache-related CI defect;
- #630 published governance work and discovered three missed local-gate failures;
- #632 published the permanent review-record work after a separate security-gate
  remediation, then exposed repeated implicit npm audit calls;
- #633 published one skill, but publication expanded into CI-timeout maintenance and
  a corrective PR;
- #634 combined publication with an explicitly requested exact-SHA onramp proof,
  including a 29m34s devcontainer job;
- #636 published the hermetic MCP bootstrap fix while the same slow CI lane remained
  on the critical path.

This selection prevents two opposite errors: treating every prompt containing the
word "publish" as a publication, and treating implementation discovered after the
first red hosted run as if it were normal merge latency.

Reproduction used the repository's transcript ledger/readers plus a scratch segment
classifier over the same local stores:

```powershell
node scripts/agent-analytics/overhead-taxonomy.mjs --since 2026-08-25 --until 2026-09-05
node scripts/agent-analytics/context-residency.mjs --since 2026-08-25 --until 2026-09-05 --harness all --json
node scripts/dev/run-gh.mjs run view <run-id> --json createdAt,updatedAt,event,status,conclusion,headSha,jobs
```

### 2.2 Reconstructed cases

| Case | User-visible duration | Primary time consumers | Avoidable portion supported by the case evidence |
|---|---:|---|---:|
| PR #623 | 52.6m | full local build/test; PR CI; queue; post-merge current-main wait; cache defect correction | Directional only; the defect correction was real work, while the synchronous current-main tail was policy |
| PR #630 | 72.7m | ~30m clean publication branch/reconciliation/local verification; ~22m three CI-discovered governance fixes; ~7m evidence-only second run; ~8m merge group; ~11m post-merge flake | 15–20m stated by the session retrospective, plus a strong case for moving the exact hosted-equivalent gate set earlier |
| PR #632 | ~53m final publication, after a separate ~96m remediation interval | 8m34s stale PR run; 12m17s caught-up PR run; 18m03s queue; 10m55s replacement current-main run | ~4m stale-base detection; 9–11m synchronous post-merge tail; repeated five-minute npm advisory stalls across the required runs |
| PR #633/#635 | 140m | 128m (91%) serialized GitHub CI: 15m timeout, unchanged retry, 20m retry, merge group, failed main, corrective PR, second merge group | ~55m from the unchanged retry plus undersized first timeout correction; monitoring added 211 polls and ~130 progress messages |
| PR #634 | 162.3m | 22.6m PR wait; 17.7m merge-group watch; 11.2m current-main watch; a superseded/failed 17.6m watch; exact-SHA native and 29m34s devcontainer proof | Cannot be compared to an ordinary publish: proof acquisition was authorized product work, not publication ceremony |
| PR #636 | 140.6m | three required-check waits totaling ~43m; a 10m local npm advisory/gate run; further merge/current-main validation | Directional: the slow advisory/install path and repeated full evidence cycles dominate, but the hermetic fix itself was substantive |

The durations above are transcript-turn duration or the more precise PR/run timeline
where available. They are wall clocks, not sums of command duration: waits overlap
agent reasoning, runner setup, and queue activity. The table therefore avoids adding
sub-intervals into a fabricated total.

### 2.3 Cross-session process evidence

The repository's maintained overhead taxonomy found 29 Claude sessions in the
2026-08-25..2026-09-05 window, representing about 8.02B measured tokens:

- WAITING: 566 notification-ack turns, 747.6M tokens, **9.319%**;
- hook friction: 208 events, 37.2M tokens, **0.464%**;
- re-orientation: 12 turns, 0.008%;
- two publish-heavy sessions had WAITING shares of 26.10% and 19.13% respectively.

This does not prove 9.319% of *publishing* tokens were waiting: the report covers all
Claude sessions in the window and has no Codex notification classifier. It does prove
that notification acknowledgement is a material surrounding tax and supports the
same mechanism found in the measured August campaign (12.10% window WAITING and
19.29% in its publication orchestrator).

The cross-harness context report found Codex main calls at p50 129,719 context tokens
and p90 208,609 over this window. Repeated status turns therefore have a real context
replay cost even when their shell call takes less than a second. The PR #633
retrospective's 211 terminal polls and ~130 progress messages are the clearest
publication-specific instance. This is primarily an attention/token problem, not the
cause of its 140-minute wall clock.

### 2.4 Historical controls and remediated mechanisms

The `publish-workflow-develocity-analysis` tempdoc's 2026-08-13 campaign remains a
useful control: 25 merges in 6.96h, 193
Actions runs, 1.82 PR runs per branch, 12 unnecessary advisory-flake reruns, 231
cancelled runner-minutes, and 11 conflicts on one shared shard. Its largest findings
must not be reported as current without qualification:

- native merge queue adoption removed the earlier serial update-branch/re-CI shape;
- cache migration and flake-handling changes addressed the known cold-cache and
  advisory-rerun policies;
- sharded evidence work addressed the single observation-file conflict source.

The recent sample nevertheless shows the same higher-order pattern in new forms:
remote evidence discovers work late, any correction invalidates the current SHA, and
the entire required evidence conveyor repeats.

### 2.5 Evidence locators and limits

The public change timelines are [PR #623](https://github.com/justsearch-app/justsearch/pull/623),
[PR #630](https://github.com/justsearch-app/justsearch/pull/630),
[PR #632](https://github.com/justsearch-app/justsearch/pull/632),
[PR #633](https://github.com/justsearch-app/justsearch/pull/633),
[PR #634](https://github.com/justsearch-app/justsearch/pull/634), and
[PR #636](https://github.com/justsearch-app/justsearch/pull/636). The detailed #632
run-by-run reconstruction, including run IDs and SHA transitions, is preserved in the
[`publish-latency-retrospective`](927-publish-latency-retrospective.md). The aggregate
WAITING and context figures are reproducible with the commands in §2.1 against the
dated local transcript stores.

The transcript selection is purposive, not a random sample, and the six cases are not
identically scoped. Codex transcripts do not yet have the maintained WAITING classifier
used for Claude sessions. Accordingly, the report makes mechanism claims supported by
case reconstruction; it does not claim a population-average publication duration or a
cross-harness token-savings forecast.

## 3. Findings

### F1 — Late discovery and evidence invalidation are the dominant fixable mechanism

The common cost is not "tests are slow" in isolation. A defect or governance miss is
first discovered after pushing; fixing it changes the SHA; the PR matrix repeats;
the merge group then repeats the matrix on the integrated SHA; the landed branch may
repeat it again. A five-minute defect can therefore impose 20–60 minutes of evidence
replay. PR #630 (three locally reproducible misses), #633 (timeout policy), and #632
(partial retirement of npm audit consumers) all fit this mechanism.

The highest-leverage question is therefore: **which failure could have been known
before the first required hosted run, and which evidence must genuinely be replayed
after the correction?** A generic "make CI faster" program misses both halves.

### F2 — CI fixed tax is too size-insensitive

The process makes a two-file skill change pay nearly the same PR, merge-group, and
current-main matrix as a cross-module runtime change. Some invariants are intentionally
global, so path filtering cannot simply replace the matrix. But the observed fixed tax
means small publication units have extremely poor evidence-per-minute economics and
encourage batching unrelated work merely to amortize ceremony.

### F3 — Current-main confirmation is conflated with proof of the published change

The merge-group SHA is the integrated commit that lands for an ordinary queued squash
merge. Waiting synchronously for the newest `main` run instead answers a different
question: "is the repository's latest state green after subsequent merges?" In #632,
the landed-SHA run was superseded and the publisher waited for a replacement run that
also contained #636. That is useful repository monitoring, but it no longer proves
only #632 and should not hold #632's publication response open.

### F4 — Timeouts are being treated as capacity instead of failed budgets

PR #633 is the strongest example. A 15-minute deterministic timeout was retried
unchanged, then raised to 20 minutes despite a 17–19 minute observed runtime, then
failed on `main` and caused a corrective PR. Raising to 30 minutes contained the
failure but did not explain the variance. A timeout increase can be necessary, but
without attribution it converts a latency regression into accepted capacity.

### F5 — Queue contention is an amplifier, not an independent root cause

PR #632 waited about seven minutes after its own merge-group evidence was green
because #634 was ahead. The queue behaved correctly. The avoidable component was the
slow preceding entry, itself dominated by repeated five-minute installs and a broad
proof workload. Optimizing queue polling or queue order alone would not recover that
time reliably.

### F6 — Polling is a serious experience/token waste but a secondary wall-clock cause

Manual status loops, repeated full JSON reads, and narrated unchanged state create
large transcripts and compactions. They do not make GitHub finish later. The remedy is
still worthwhile—event-driven state changes, bounded output, and silent unchanged
waits—but it must not be sold as the main latency fix.

### F7 — "Publish" is an overloaded phase boundary

Across the six cases, the word sometimes meant:

- integrate, verify, enqueue, and confirm an already-final change;
- acquire new exact-SHA product proof (#634);
- repair a discovered CI defect (#623/#632);
- perform unrelated infrastructure maintenance (#633);
- clean worktrees and registered processes.

Without a phase boundary, every discovered issue remains inside the user's perceived
publish timer. This obscures both process performance and authorization: remediation
can be correct engineering while still being an unauthorized expansion of publication.

### 3.1 Counter-hypotheses considered

1. **"The merge queue is the problem."** Refuted as a primary explanation: it
   supplies necessary integrated-SHA evidence; slow entries and synchronous tails
   explain more recoverable time.
2. **"Full local verification is redundant with CI."** Only partly supported.
   PR #630 shows that exact local equivalents could have prevented remote replay;
   removing local verification would have made the case slower, not faster.
3. **"The agent is simply overcautious."** Partly true for post-main waiting and
   unrelated maintenance, but the hosted failures were real and sometimes protected
   public correctness. The problem is evidence scope and terminal-condition policy,
   not caution itself.
4. **"Tiny changes should skip most CI."** Plausible but unproven. Global generated
   projections, governance registers, and publication machinery make path-only risk
   classification easy to get wrong. A path-sensitive lane needs contract ownership
   and a conservative fallback, not filename intuition.
5. **"Bigger timeouts solve the issue."** Refuted by #633 and #632. They reduce red
   runs while allowing unexplained work to occupy the critical path.

## 4. Theorization

The following are possibilities and frames, not a settled design.

### 4.1 Publication as a monotonic evidence transaction

One useful model is a transaction over a specific candidate SHA. Evidence should
accumulate monotonically for that SHA until either it lands or the SHA changes. A
correction invalidates only evidence whose premise changed. Today the process mostly
replays whole matrices because the evidence units are workflow runs, not explicit
claims with input ownership. A claim-addressed evidence ledger could eventually allow
safe reuse; the risk is building a complex cache whose invalidation is less trustworthy
than rerunning tests.

### 4.2 Publication as two state machines, not one long skill

Another frame separates:

1. **Candidate preparation:** reconcile, run exact local equivalents, finalize public
   records, and prove the candidate is ready to enter hosted validation.
2. **Publication:** obtain PR and merge-group evidence, enqueue, verify landing, and
   return once the integrated SHA is published.

New implementation after the first hosted red would transition back to preparation or
to an explicit remediation task. This would make duration and authorization honest.
The tradeoff is more visible phase transitions and potentially more user-facing status.

### 4.3 Four different levers should not be collapsed

Each waste has one of four remedies:

- **eliminate** work that adds no distinct evidence (same-SHA synchronous replay);
- **move left** failures that can be found before the first push (exact local parity);
- **parallelize** independent facts whose current sequencing is incidental;
- **detach** useful monitoring that need not block the publication response.

"Make CI faster" is too coarse because these levers have different safety arguments.

### 4.4 A fast path may need risk declarations, not path inference

A small-change fast path is attractive, but file paths alone understate cross-cutting
governance and generated-output effects. Alternatives worth considering include:

- an explicit closed set of low-risk change classes, each with an owning gate;
- a proof that the diff is documentation-only or skill-only plus all projection checks;
- always running global cheap gates while selecting only expensive build/test lanes;
- no fast path at all, but aggressively removing install/setup duplication.

The last option is less glamorous and may be safer if the global matrix can reach a
single-digit-minute critical path.

### 4.5 Timeouts should be circuit breakers with latency evidence

A timeout could be modeled as "this work exceeded its allowed budget and requires
attribution," not merely "the job did not finish." Increasing it would then require a
measured critical-path explanation and an expiry/review condition. This risks adding
governance ceremony around legitimate slow growth; a warning budget plus a larger hard
ceiling may be a better shape than blocking every variance event.

### 4.6 Waiting should become a subscription

The ideal waiter emits only transitions: registered, running critical path, base moved,
queue position changed, failed, merged. It can compactly hold one durable subscription
rather than prompting the model on every unchanged poll. This improves attention and
token economics even if GitHub wall time is unchanged.

### 4.7 The broader principle may be evidence proportionality

The recurring shape is not unique to publishing: confidence should be proportional to
the claim being made, and synchronous latency should be proportional to the evidence
needed for the next irreversible action. Evidence useful only for ambient repository
health can run asynchronously after that action. The retirement condition for this
principle would be evidence that the distinction causes missed regressions or creates
more classification/reuse failures than the time it saves.

## 5. Research decision and external evidence

External research was warranted. The design choices depend on current GitHub Actions,
merge-queue, and npm CLI behavior rather than only repository-local facts. The pass
used official vendor documentation; it did not import external code, so licensing
review is not applicable.

### 5.1 npm's defaults explain the five-minute install plateau

npm documents that `audit` defaults to `true`, that audit-capable commands submit an
audit report, and that the default HTTP fetch timeout is `300000` milliseconds. That
is an unusually exact match for the observed 301–305 second `npm ci` steps. Combined
with the controlled `npm ci --audit=false` run completing in about two seconds, this
raises the implicit-audit explanation from correlation to strong causal evidence.
([`npm ci`](https://docs.npmjs.com/cli/commands/npm-ci/),
[`npm audit`](https://docs.npmjs.com/cli/v11/commands/npm-audit/),
[`fetch-timeout` and `audit` configuration](https://docs.npmjs.com/cli/using-npm/config/))

The current workflow has retired the implicit audit in two installs, but not all of
them: `ci.yml` still contains bare installs for the wire-contract helper, frontend
license lane, root build dependencies, and runtime-client generator. The fixed tax is
therefore a current partial-retirement defect, not only a historical anecdote.

### 5.2 Merge-group evidence is necessary; current-main waiting is a separate policy

GitHub documents that a required check used with a merge queue must run on the
`merge_group` event, whose check SHA is the merge-group SHA. The queue tests the pull
request combined with the latest target branch and earlier queued changes. That
supports keeping merge-group checks: they are the platform's pre-merge integration
evidence, not redundant PR replay.
([required-check troubleshooting](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks),
[`merge_group` event](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows),
[merge-queue behavior](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/incorporating-changes-from-a-pull-request/merging-a-pull-request-with-a-merge-queue))

The same documentation does not make a later `push` run on `main` part of the merge
queue's admission evidence. Given this repository's enforced queue and no-bypass
rules, treating the green integrated merge-group SHA plus confirmed landing as the
publication terminal condition is a repository-policy inference, not a GitHub claim.
Ambient current-main health remains valuable, but it can be monitored asynchronously.

### 5.3 Safe selective CI is possible, but whole-workflow path filtering is the wrong tool

GitHub warns that a required workflow skipped by path, branch, or commit-message
filtering can remain `Pending` and block merging. In contrast, a job skipped by a
job-level `if` condition reports `Success` and does not block a required check. This
refines the fast-path hypothesis: an always-triggered required workflow could keep a
stable set of check names and condition expensive jobs on a governed risk classifier.
It does **not** justify adding path filters to the required workflow or trusting an
ad-hoc filename list.
([skipping workflow runs](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs?apiVersion=2022-11-28),
[job conditions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-jobs-with-conditions?apiVersion=2022-11-28),
[required-check states](https://docs.github.com/en/pull-requests/reference/status-checks))

### 5.4 Concurrency explains why "wait for latest main" couples unrelated publications

GitHub's concurrency model permits a newer pending run in the same group to replace
an older pending run, and `cancel-in-progress: true` also cancels a running member.
This repository keys `push` runs by `github.ref`, so all `main` pushes share a group.
The #632 supersession was therefore expected behavior, not a one-off API anomaly.
Waiting for the newest current-main run can silently expand one publication's terminal
condition to include later merges.
([GitHub Actions concurrency](https://docs.github.com/en/actions/concepts/workflows-and-actions/concurrency))

## 6. Recommendations and validation plan

The rankings optimize the next irreversible action—merging—without weakening the
evidence required for it. Estimated leverage is based on the sample above; it is not a
forecast with statistical confidence bounds.

| Rank | Intervention | Expected leverage | Safety risk | Evidence required before adoption |
|---:|---|---|---|---|
| 1 | Disable install-time npm audit at every CI install and retain one explicit lockfile-authoritative audit gate | Very high: removes a recurring five-minute plateau from multiple jobs and every PR/merge-group/main cycle | Medium if any lockfile loses authoritative coverage | Enumerate every lockfile and audit owner; add a regression scan for bare `npm ci`; show hosted install p95 below 60s while the explicit audit still fails on a planted vulnerable fixture |
| 2 | Add a candidate-ready gate that runs the exact hosted-equivalent governance checks before the first push, from a current-base clean publication branch | High: prevents the 15–22m remote discovery/replay pattern in #630 and #633 | Low–medium; local/hosted drift could create false confidence | One command must enumerate the same governed checks as CI; inject known failures from #630/#633 and prove they fail locally before push |
| 3 | End synchronous publication after green merge-group evidence and confirmed landing; detach current-main health monitoring | High for user-visible latency: removes roughly 9–11m in #632 and avoids coupling to later merges | Medium; a push-only packaging or deployment fact could be missed | Inventory every `push: main`-only fact; retain synchronous waiting only for facts required to make the publication claim; alert asynchronously on later main failures |
| 4 | Replace conversational polling with one bounded, transition-only waiter | High token/attention savings; low direct wall-clock savings | Low; missed state transitions or approval prompts | Replay #633's event stream and show the same terminal result and actionable failures with at least 90% fewer wakeups/messages |
| 5 | Govern timeouts as latency budgets with attribution and expiry | Medium: prevents unchanged retries and repeated ceiling raises | Medium; an overly rigid budget can block legitimate growth | Record job/step p50, p95, and timeout headroom; require a named cause and review date for increases; verify the alert fires before the hard ceiling |
| 6 | Prototype selective expensive lanes behind an always-triggered, governed risk classifier | Potentially very high for tiny changes, but least proven | High: a false low-risk classification can skip needed evidence | Closed risk classes with owners, conservative fallback to full matrix, mutation fixtures for cross-cutting projections, and a shadow period comparing selected versus full results |

### 6.1 Recommended sequence

Start with intervention 1 because the causal evidence is strongest and it changes no
test coverage. Then implement 2 and 3 together: moving reproducible failures left
only pays fully when unrelated post-merge health is also detached. Intervention 4 can
proceed independently because it changes observation mechanics, not merge criteria.
Treat 5 as a guard against regression. Do not implement 6 until the cheaper fixed-tax
removals have been measured; the classifier's safety burden may not be worth carrying
if the full matrix becomes fast enough.

### 6.2 Measurement contract

For the next ten ordinary publications, record timestamps for candidate-ready,
first push, first hosted red/green, enqueue, own merge-group green, landing, and any
post-landing health event. Report both the critical path and detached monitoring. The
initial success criteria are:

- no implicit audit call and no unexplained approximately-300-second install step;
- zero hosted failures that the candidate-ready command reproduces unchanged;
- publication response within two minutes of confirmed landing when no required
  post-landing fact exists;
- unchanged status produces no model wakeup;
- timeout increases carry a measured step-level cause;
- no increase in post-merge regressions, bypasses, or false-green required checks.

These criteria deliberately avoid promising a single universal publish duration. A
publication that includes authorized exact-SHA product proof, such as #634, should be
reported separately from an ordinary merge so process improvements are not judged
against different work.

## 7. Closure and deferred work

This investigation, theorization, and external research pass is complete. It changes
no publication behavior. The recommendations remain proposals until separate design
and implementation work supplies the validation evidence in §6.

Unverified assumptions and deferred checks are explicit:

- the candidate-ready gate's prevented-run savings are extrapolated from historical
  failures; they have not been measured prospectively;
- detaching current-main monitoring is safe only after an inventory proves there is no
  push-only fact required for the publication claim;
- job-level selective CI is platform-feasible but has no repository risk classifier,
  shadow comparison, or false-negative evidence yet;
- the next-ten-publications measurement contract has not begun;
- Markdown lint was unavailable in this isolated worktree because dependencies were
  not installed; the repository's lint script excludes `docs/tempdocs/**` in any case.
