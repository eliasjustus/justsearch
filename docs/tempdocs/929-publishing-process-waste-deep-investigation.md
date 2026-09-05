---
title: "Publishing-process waste: transcript-wide deep investigation"
type: tempdocs
status: "PUBLISHED — safe publication-feedback improvements landed in PR #641"
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
| PR #623 | 52.6m | full local build/test; PR CI; queue; post-merge current-main wait; cache defect correction | Directional only; the defect correction was real work, while the current-main tail is a policy cost that cannot yet be removed safely |
| PR #630 | 72.7m | ~30m clean publication branch/reconciliation/local verification; ~22m three CI-discovered governance fixes; ~7m evidence-only second run; ~8m merge group; ~11m post-merge flake | 15–20m stated by the session retrospective, plus a strong case for moving the exact hosted-equivalent gate set earlier |
| PR #632 | ~53m final publication, after a separate ~96m remediation interval | 8m34s stale PR run; 12m17s caught-up PR run; 18m03s queue; 10m55s replacement current-main run | ~4m stale-base detection; repeated five-minute npm advisory stalls; the 9–11m post-merge tail is detachable only after §8's repeatability prerequisite |
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

### F3 — Current-main confirmation mixes publication proof with repeatability monitoring

The merge-group SHA is the integrated commit that lands for an ordinary queued squash
merge. Waiting for the landed SHA on `main` primarily asks a different question: "does
the same evidence repeat in the post-merge environment?" The derisk pass found that
this is not yet vacuous: two of 21 exact-SHA pairs were green in merge-group CI and red
on `main`. Waiting for the *newest* `main` run after the landed-SHA run is superseded
asks an even broader question: "is the repository green after subsequent merges?" In
In PR #632, that replacement also contained #636. The process can acknowledge that #632
landed without delay, but it should retain final-main closeout until repeatability
defects are eliminated and it has an owned asynchronous failure path.

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

- **eliminate** work proven to add no distinct evidence after repeatability is
  established;
- **move left** failures covered by the deterministic local subset before the first
  push;
- **parallelize** independent facts whose current sequencing is incidental;
- **detach** useful monitoring only after it has an owned asynchronous response path.

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
queue's admission evidence. Treating the green integrated merge-group SHA plus
confirmed landing as the publication terminal condition would therefore be a
repository-policy decision, not a GitHub requirement. The derisk evidence in §8 shows
that making that decision now would be premature: current-main replay still catches
same-SHA nondeterminism. Landing can be acknowledged immediately, but green closeout
should remain until that failure class is removed or an owned asynchronous response
contract replaces it.

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
| 1 | First expand the explicit advisory authority to every active lockfile, then disable install-time audit at every covered install | Very high: removes a recurring five-minute plateau from multiple jobs and every PR/merge-group/main cycle | Medium until coverage is complete; low afterward | Cover root, UI, runtime-client, wire-contract, and shell lockfiles; add a regression scan for uncovered installs; show hosted install p95 below 60s while the explicit gate still fails on a planted advisory fixture |
| 2 | Make the existing bounded watcher the mandatory observation path and add merge-queue/current-main transition support | High token/attention savings; low direct wall-clock savings | Low; the existing PR waiter and supervisor already have tested state/timeout contracts | Replay #633's event stream and show the same terminal result and actionable failures with at least 90% fewer wakeups/messages |
| 3 | Build a generated candidate-ready manifest for the deterministic local subset of hosted checks, from a current-base clean publication branch | High: prevents part of the 15–22m remote discovery/replay pattern in #630 and #633 | Medium; "exact hosted parity" is not achievable locally for CLA, runner-OS, and hosted-environment facts | Generate rather than hand-copy the mapping; inject the known #630/#633 locally reproducible failures; identify every required check with no local analogue instead of implying full parity |
| 4 | Extend the existing CI wall-time budgets with attribution, expiry, and a pre-timeout alert | Medium: prevents unchanged retries and repeated ceiling raises | Medium; stale budgets can create noise and hard gates can block legitimate growth | Refresh p50/p95 from recent runs, reconcile the current 30m hard timeout with the stale 120s advisory budget, and prove the alert fires before the hard ceiling without becoming a merge gate |
| 5 | Separate immediate "landed" acknowledgement from final green-current-main closeout; do not yet remove the latter | Modest user-experience gain now; larger only after CI becomes repeatable | High if final monitoring is dropped today: exact-SHA post-merge failures exist | Classify and eliminate green-merge-group/red-main counterexamples, establish an owned asynchronous failure channel, and only then reconsider whether final green can be detached |
| 6 | Prototype selective expensive lanes behind an always-triggered, governed risk classifier | Potentially very high for tiny changes, but least proven | High: a false low-risk classification can skip needed evidence | Do not reuse the dormant module-filter files as authority; define closed risk classes with owners, preserve all required check names, fall back to the full matrix, and shadow selections against full results |

### 6.1 Recommended sequence

Start with intervention 1 because the causal evidence is strongest, but make expanded
lockfile coverage an atomic prerequisite rather than assuming the current authority is
complete. Intervention 2 can proceed independently because it changes observation
mechanics, not merge criteria. Build 3 as a generated deterministic-subset contract,
not a claim that one local command can reproduce hosted Windows, CLA, or runner facts.
Intervention 4 should extend the existing wall-time reporting seam. Keep final-main
monitoring while the exact-SHA counterexamples in §8 remain unexplained. Do not
implement 6 until cheaper fixed-tax removals have been measured and its classifier has
an owner; the dormant filter files are not a trustworthy starting authority.

### 6.2 Measurement contract

For the next ten ordinary publications, record timestamps for candidate-ready,
first push, first hosted red/green, enqueue, own merge-group green, landing, and any
post-landing health event. Report landing acknowledgement separately from final-main
closeout so the cost is visible without prematurely detaching it. The initial success
criteria are:

- no implicit audit call and no unexplained approximately-300-second install step;
- zero hosted failures that the candidate-ready command reproduces unchanged;
- immediate landed acknowledgement within two minutes of confirmed landing, while
  final closeout continues to wait for current-main evidence until §8's repeatability
  prerequisite is satisfied;
- unchanged status produces no model wakeup;
- timeout increases carry a measured step-level cause;
- no increase in post-merge regressions, bypasses, or false-green required checks.

These criteria deliberately avoid promising a single universal publish duration. A
publication that includes authorized exact-SHA product proof, such as #634, should be
reported separately from an ordinary merge so process improvements are not judged
against different work.

## 7. Closure and deferred work

This investigation, theorization, external research, and derisk pass is complete. It
changes no publication behavior. The recommendations remain proposals until separate
design and implementation work supplies the validation evidence in §6 and §8.

Unverified assumptions and deferred checks are explicit:

- the candidate-ready gate's prevented-run savings are extrapolated from historical
  failures; they have not been measured prospectively;
- detaching current-main monitoring is unsafe today: two sampled exact SHAs went from
  green merge-group evidence to a red current-main run;
- job-level selective CI is platform-feasible but has no repository risk classifier,
  shadow comparison, or false-negative evidence yet;
- the next-ten-publications measurement contract has not begun;
- Markdown lint was unavailable in this isolated worktree because dependencies were
  not installed; the repository's lint script excludes `docs/tempdocs/**` in any case.

## 8. Derisk pass — 2026-09-04

### 8.1 Confidence-building plan executed

The derisk pass investigated rather than implemented. It:

1. enumerated every npm lockfile and compared it with the explicit advisory report's
   configured targets;
2. compared the publication skill, CI evidence digest, required hosted contexts, and
   actual workflow commands to test the local-parity assumption;
3. read the live repository ruleset and branch protection, paired recent merge-group
   and `main` runs by exact SHA, and inspected every green-to-red counterexample;
4. traced the proposed selective-CI substrate to determine whether it is live and
   tested;
5. inspected and tested the existing waiting and wall-time-budget primitives.

### 8.2 Findings that changed the plan

#### D1 — audit retirement has a real coverage prerequisite

`REQUIRED_ADVISORY_TARGETS` currently names only `package-lock.json` and
`modules/ui-web/package-lock.json`. Using the report producer's own package-coordinate
parser, the other active lockfiles contain dependencies outside that authority:

| Lockfile | Package coordinates | Not covered by root + UI |
|---|---:|---:|
| `modules/shell/package-lock.json` | 12 | 12 |
| `packages/runtime-client/package-lock.json` | 135 | 78 |
| `scripts/wire-contract/package-lock.json` | 8 | 8 |

Runtime-client and wire-contract are installed in required CI; shell is installed in
the release workflow. Turning off their install-time audits before expanding the
explicit authority would create a coverage regression. The safe first slice is one
atomic change: expand the target register/baseline/tests, then suppress duplicate
install-time transport, then add a bare-install coverage guard.

#### D2 — green merge-group evidence does not yet make the final-main run dispensable

The live `main-merge-queue` ruleset is active, has no bypass actors, uses squash, and
has a 45-minute check-response timeout. Branch protection exactly matches the ten
checks declared by `workflow-signal-policy.v1.json`. In 21 recent cases where both run
types were available, the merge-group and landed `main` run used the exact same SHA.
That is stronger equivalence than the initial report assumed.

However, two of those 21 SHAs passed merge-group CI and then failed current-main CI:

- SHA `0b26fa46`: merge-group run
  [33742666437](https://github.com/justsearch-app/justsearch/actions/runs/33742666437)
  was green; `main` run
  [33743262129](https://github.com/justsearch-app/justsearch/actions/runs/33743262129)
  failed because the registry snapshot was absent and three gates would have inspected
  nothing.
- SHA `b6d0861e`: merge-group run
  [33668360411](https://github.com/justsearch-app/justsearch/actions/runs/33668360411)
  was green; `main` run
  [33668996606](https://github.com/justsearch-app/justsearch/actions/runs/33668996606)
  failed the dead-code gate.

Three additional paired `main` runs were cancelled by concurrency. The failures look
like repeatability/input-production defects rather than new tree content, but that is
precisely why same-SHA replay currently adds information. The plan must retain final
monitoring until these structural nondeterminisms are fixed and a prospective window
shows the failure class gone. It can still acknowledge landing immediately without
claiming full green closeout.

#### D3 — local candidate readiness must be a subset contract, not "exact parity"

The advisory CI evidence digest has local-reproduction entries for seven of ten
required contexts. It has none for Windows-native tests, shell Rust tests, or CLA, and
its Public-claims recipe is a hand-maintained subset of a much larger live job. The
publication skill says "full repository verification" but has no single generated
command that proves parity with the hosted workflow.

Therefore the implementable claim is narrower: generate a manifest of deterministic
checks that can run locally, prove the known locally reproducible misses are included,
and list hosted-only facts explicitly. This can move failures left without creating a
false promise that local green replaces CI.

#### D4 — most of the waiter already exists

`run-gh.mjs checks-wait` already owns the registration race, required-only filtering,
bitwise GitHub CLI verdicts, and a bounded timeout. `run-watcher.mjs` already provides
durable supervised process state. Their 24 and 17 tests passed respectively. The gap
is adoption plus a queue/run transition layer, not a new event system. This raises
implementation confidence and lowers the appropriate scope.

#### D5 — timeout governance should extend the existing telemetry seam

The repository already emits per-run attribution, has a warn-only budget policy, and
has a trend analyzer. Its Public-claims advisory ceiling is still 120 seconds while
the workflow's hard timeout is now 30 minutes and a recent successful merge-group run
took 191 seconds. The trend workflow is manual-only. The right implementation seam is
to refresh and govern this existing policy, not create another latency registry.

#### D6 — the apparent selective-CI substrate is dormant residue

`scripts/ci/module-filter.yml` says it is used by `dorny/paths-filter`, and
`resolve-affected-modules.mjs` expects that output. Neither is referenced by any live
workflow or test. Git history shows `dorny/paths-filter` was removed from CI one commit
after the initial public release, while the configuration file continued to receive
edits. Selective CI must not silently reactivate this stale representation. It needs a
new authority decision, tests, fixed required-check-name behavior, and a full-matrix
shadow phase—or the residue should be retired in the eventual implementation sweep.

### 8.3 Verification evidence

- Live policy: `node scripts/ci/check-branch-protection.mjs --repo
  justsearch-app/justsearch --branch main --json` — pass, ten expected and ten actual
  required contexts, `strict=false`.
- Live ruleset: `GET /repos/justsearch-app/justsearch/rulesets/20851694` — active,
  squash queue, no bypass actor, 45-minute response timeout.
- Waiter tests: `node scripts/dev/run-gh.test.mjs` — 24 passed;
  `node scripts/dev/run-watcher.test.mjs` — 17 passed.
- Budget reporter: `node scripts/ci/test-report-ci-walltime-budget.mjs` — pass.
- Advisory coverage counts were produced with `packageSpecsFromLockfileText`, the
  exact parser used by `report-github-advisories.mjs`.

### 8.4 Confidence and implementation routing

Overall confidence for implementing the **revised staged program** is **7/10**. It is
not one safe atomic change and should not be assigned as one broad implementation:

| Slice | Confidence | Difficulty | Recommended Codex setting |
|---|---:|---|---|
| Complete advisory coverage, suppress duplicate install audits, add coverage guard | 8.5/10 | Medium | `gpt-5.6-sol`, high |
| Enforce existing waiter and add merge/run transition observation | 8/10 | Medium | `gpt-5.6-terra`, high |
| Extend wall-time budgets and timeout attribution | 7.5/10 | Medium | `gpt-5.6-terra`, high |
| Generated deterministic candidate-ready manifest | 6/10 | Medium–high | `gpt-5.6-sol`, high |
| Detach final-main closeout after eliminating repeatability failures | 4.5/10 today | High evidence risk | `gpt-5.6-sol`, xhigh, only after prerequisite evidence |
| Selective expensive CI lanes | 3.5/10 today | High | `gpt-5.6-sol`, xhigh, separate design/derisk cycle |

The practical recommendation is to implement the first three slices independently,
measure ten ordinary publications, and then re-derisk the final two. The easy-looking
terminal-condition change is currently riskier than the larger-looking audit cleanup.

## 9. Implementation plan — approved for autonomous execution 2026-09-04

The implementation is intentionally split into independently verifiable slices. It
does not remove merge-group evidence, final-main green closeout, or any required check.

### P1 — complete advisory authority before suppressing duplicate transport

- [x] Extend `REQUIRED_ADVISORY_TARGETS` to every active production lockfile: root,
  UI, shell, runtime-client, and wire-contract.
- [x] Regenerate the advisory report and baseline using the expanded authority; add
  producer and enforcer tests proving missing targets fail closed.
- [x] Add a static CI guard that rejects active `npm ci`/`npm install` commands without
  an explicit audit policy, while allowing intentional fixture text.
- [x] Only after those checks are green, set `--audit=false` on covered installs in
  required, release, docs, and onramp workflows.

### P2 — make one-process waiting the paved publication path

- [x] Extend `run-gh.mjs` with bounded, transition-only waits for merge completion and
  an exact-SHA workflow run, preserving its existing exit-code contract.
- [x] Unit-test registration races, state transitions, closed-unmerged PRs, timeout,
  failed runs, cancelled runs, and exact-SHA selection.
- [x] Update the publish skill source, canonical guide, and hook guidance to use the new
  modes; regenerate synced skill sections and inspect the prompt-surface inventory. The
  Codex skill is independently owned in the current canonical ownership map and was not
  overwritten by a nonexistent projection command; it links to the updated canonical guide.

### P3 — make local candidate readiness an explicit subset contract

- [x] Move local-reproduction commands out of the evidence renderer into a versioned
  manifest shared by reporting and execution.
- [x] Add a runner that executes the deterministic candidate subset, labels hosted-only
  required contexts honestly, and fails on manifest/policy drift.
- [x] Include the locally reproducible governance/projection failures observed in the
  sample without claiming CLA, hosted OS, or runner-environment equivalence.

### P4 — govern latency through the existing wall-time seam

- [x] Refresh advisory budgets from a current measured window and record measurement
  date, source window, review date, and hard-timeout headroom.
- [x] Extend the budget reporter/tests to flag expired evidence, missing required lanes,
  and advisory ceilings that no longer leave meaningful hard-timeout headroom.
- [x] Keep the signal advisory; do not turn runner variance into a merge blocker.

### P5 — retire the proven dormant selective-CI representation

- [x] Delete `module-filter.yml` and `resolve-affected-modules.mjs` after a final
  repository-wide reference check confirms they have no consumer or test.
- [x] Confirm no live comment, workflow, test, or canonical doc references the deleted
  files. Dated tempdocs retain their historical references. Any future selective-CI design
  must introduce a new governed authority and shadow-validation contract.

### P6 — validation and evidence-gated follow-up

- [x] Run focused unit/fixture checks for every changed surface, governance preflight,
  docs/skill regeneration checks, and the full Gradle suite required after the
  `origin/main` merge; run frontend checks if the merged or implementation diff touches
  `modules/ui-web`.
- [x] Record commands and outcomes here and commit the completed implementation
  (`5d46ca41`, `ci: reduce publication feedback waste`).
- [x] Defer final-main detachment and selective lane activation. Reconsider only after
  ten ordinary publications satisfy §6.2 and a fresh derisk pass raises confidence.

## 10. Implementation outcome — 2026-09-04

The safe slices P1–P5 are implemented. Final-main validation and every required hosted
check remain intact. Selective CI was not activated; its two unreferenced legacy files
were deleted after a repository-wide consumer check found only dated-history mentions.

The full Windows test run exposed an additional candidate-side waste source that Linux
CI could not show: `SdkOpenApiProjectionTest` compared a committed LF snapshot with
Jackson's platform-native CRLF rendering and deterministically failed by exactly one byte
per line. `SdkOpenApiProjection.write` now normalizes the generated artifact to UTF-8 LF.
The original regression test passed unchanged, then the complete test and build suites
passed. This is a concrete example of why the local contract must describe a subset and
why platform-specific evidence remains necessary.

The first PR run exposed one further fixture-projection omission: the governance
kernel's npm-audit self-test fixtures still modeled only the original root and UI
targets after the production authority expanded to five lockfiles. The positive and
negative fixtures now carry all five target rows, matching lockfile fixtures, and
baseline projections. The hosted failure was reproduced locally before the fix; the
kernel self-test and all 34 governance test files then passed.

Verification evidence:

- `node scripts/ci/report-github-advisories.mjs` produced all five target rows: root
  (62 advisories), UI (21), shell (0), runtime-client (0), wire-contract (0).
- `node scripts/governance/run.mjs --gate npm-audit --mode gate` passed with zero
  findings; the enforcer fixture now proves an omitted target fails closed.
- Workflow audit-policy checks and fixtures passed; every active workflow install now
  declares `--audit=false`.
- `run-gh.test.mjs` passed 32 checks, including deterministic timeout and registration
  transitions. Live read-only smokes returned PR 640's landed SHA and successful exact-SHA
  main run 33862965941.
- The local-reproduction manifest validator passed with all ten required contexts
  classified; the evidence-digest fixture passed against the shared manifest.
- The refreshed 20-successful-run window has no lane above its new advisory ceiling.
  The previously absent Windows lane measured median 123s / max 329s; Rust measured
  median 97s / max 295s. Budget, attribution, and trend fixtures passed.
- Agent analytics passed 65/65 files; governance passed 34/34 files. Canonical-link,
  `llms.txt`, skill-sync, module-dependency, runtime-config, and prompt-surface checks
  passed; the prompt inventory reported zero suspicious tokens.
- `node scripts/governance/run.mjs --self-test --mode gate` passed after synchronizing
  the npm-audit fixtures with the five-target authority; PR run 33869071096 was the
  discovery evidence for the previously missing fixture rows.
- `./gradlew.bat test` passed after the cross-platform snapshot fix.
- `./gradlew.bat build -x test` passed after the final code change (251 actionable tasks).

## 11. Publication outcome — 2026-09-04

The safe P1–P5 implementation was published through
[PR #641](https://github.com/justsearch-app/justsearch/pull/641). The first exact-head
PR run, `33869071096`, found that the governance kernel's npm-audit self-test fixtures
still modeled only two of the expanded five-target advisory authority. That candidate-
owned failure was reproduced locally and fixed by adding the missing shell,
runtime-client, and wire-contract report, lockfile, and baseline fixture projections.
The kernel self-test and all 34 governance test files then passed locally.

Corrected source commit `a97c3c9e` passed every required PR context in CI run
`33869620693`; the managed review record and squash-message preview both passed before
enqueue. Merge-group CI run `33870222179` and CLA run `33870222191` passed on integrated
SHA `a25ce47f6330ea5ad802a8bf54e9596847a80e65`. The merge queue landed that exact SHA as
`ci: reduce publication feedback waste (#641)`, and post-merge main CI run `33870587060`
passed on the same SHA.

The publication is therefore complete. The evidence-gated follow-ups remain unchanged:
do not detach final-main validation or activate selective CI until ten ordinary
publications satisfy §6.2 and a fresh derisk pass supports the change.
