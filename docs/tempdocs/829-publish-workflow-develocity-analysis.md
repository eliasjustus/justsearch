---
title: "829 — Publish-workflow velocity & Develocity analysis (measured, 2026-08-13 campaign)"
type: tempdocs
status: "ANALYSIS + DERISK COMPLETE (2026-08-14) — findings F1-F7 measured, recommendations R1-R8 ranked; derisk pass corrected two premises (F2 mechanism = daemon locks not tar path; R4 unimplementable on a user-owned repo) and root-caused F7 (missing opentelemetry module). Implementation wave started for R1/R2+R7/R3/R5/R8; R4/R6 held as owner decisions."
created: 2026-08-14
author: agent session 776e10cd-eef9-4873-a027-1fc2887a334d (Fable orchestration; 2 opus evidence workers)
category: infra / CI / agent-economics
related:
  - 821 (the campaign this analysis measures), 828 (shard collision — already chartered from an F6 precursor)
  - tempdoc 746 T1 (WAITING baseline), tempdoc 622 (OTel architecture)
---

# 829 — How the publish workflow affects development velocity (and what Develocity actually does here)

Owner ask (2026-08-14): "critically analyse the publish workflow for how it affects
develocity … in depth … also consider using the agent observability architecture."
Both readings of "develocity" are covered: developer velocity (§2-§4) and the Gradle
Develocity integration (§5), because they turn out to be the same story — the
conveyor's costs are latency and attention, and the Develocity layer that should be
measuring/deduping that work is write-only here.

## §1 Method

Two read-only evidence workers on 2026-08-14 over the 2026-08-13 campaign
(25 merges 15:24-22:52 UTC, 31 merges for the day; 193 Actions runs):

- **Observability worker**: `overhead-taxonomy.mjs` (T1 method, re-applied over
  2026-08-12→14: 11 sessions, 2.83B tokens), transcript-derived subagent cost
  attribution (93 subagent transcripts), `gh api actions/runs` timing extraction,
  distinct-conflict counting over the orchestrator JSONL.
- **Develocity/CI worker**: config verbatim (`settings.gradle.kts:85-107`,
  `JvmBaseConventionsPlugin.kt:119-143`, `ci.yml`), branch-protection API, per-job
  dedup of the runs API (117 phantom job records = 7.2h double-count removed),
  cache-store enumeration, CI-log cache/scan forensics (runs 31750644388,
  31742266298 et al.).

Confidence markers inline. The headline numbers survived cross-checking between the
two workers' independent extractions.

## §2 The measured shape of one conveyor day

| Metric | Value |
|---|---|
| PRs merged (campaign window) | 25 in 6.96h → 19.0 min/PR cadence |
| Shipped delta (campaign 23-PR core) | 222 files, +10,084/−2,474 |
| CI runner-time (whole day, deduped) | **59.89 h** (Windows 32.50, Ubuntu 27.39) |
| PR-lane CI runs / branches | 60 runs / 33 branches = **1.82 runs per branch** |
| Re-CI (2nd+ run per branch) | 22 runs, **3.46h = 52.6% of campaign PR-lane compute** |
| Flake reruns | 12 runs (24% of completed PR runs), **all eventually green** |
| Cancelled mid-flight (supersession) | 7 runs, 231 runner-min |
| Post-merge push CI on main | 30 runs, 4.06h |
| Merge conflicts | **11 — all 11 on one shared observation shard** |
| Orchestrator WAITING tokens | **19.29%** of the session (139 turns, 214.4M tokens) |
| Window WAITING (11 sessions) | 12.10% — reproduces 746-T1's 4-week 12.47% baseline |
| Orchestrator blocking wall-clock on CI waits | **3.1 min total** (43/49 waits backgrounded) |
| Subagent share of session tokens | ≈70.6% (medium confidence; two roll-up methods disagree) |
| Dollar cost | **$0** (public repo, hosted runners free) |

The last three rows are the analysis in miniature: the conveyor does NOT block the
orchestrator (3 minutes of real blocking all day) — it converts CI latency into
notification-ack turns against a ~611M-token cache, which is where the 19.29% goes.
The cost of the publish workflow is measured in **tokens and head-of-line latency,
not dollars or idle time**.

## §3 Critical findings (ranked by measured magnitude × fixability)

### F1 — The rerun roulette was self-inflicted; all 12 reruns were unnecessary. (HIGH, self-audit)

`Integration tests (system-tests tier)` is `continue-on-error: true` (`ci.yml:533`)
and **absent from `required_status_checks.contexts`** (branch-protection API,
verified). Every reran run's **attempt-1 conclusion was already `success`** and every
PR was already mergeable — GitHub's `UNSTABLE` mergeState (non-required check
failing) satisfies protection. The orchestrator's merge gate (`mergeStateStatus ==
CLEAN`) was **stricter than branch protection itself**, so each advisory-lane flake
triggered: rerun (~8 min lane) → wait cycle → re-check. Cost: 102 min re-execution +
**~2.4h of serial head-of-line stall** (rerun runs: 20.7 min vs 8.9 min end-to-end)
+ one run needing 3 attempts. The boot-flake investigation those failures triggered
was genuinely valuable (it found and fixed a production bug — 821 §O.4) — but the
*merges* never needed the reruns.

The double-retry structure is incoherent on its own terms: the inner Develocity
retry (`maxRetries=2` + `failOnPassedAfterRetry=true`,
`JvmBaseConventionsPlugin.kt:135-137`) is designed to *surface* flakes loudly; the
outer `gh run rerun --failed` re-rolls the dice until the flake doesn't occur —
defeating the inner layer's purpose while re-executing ~80 passing tests per roll.

### F2 — The Windows Gradle cache has never been saved; 54% of CI time runs cold. (HIGH)

Every Windows lane logs `gradle cache is not found` → 0 FROM-CACHE → a save
failure warning, so lanes stay green and it has never been seen. 32.5h of the
day's 59.9h runner-time ran fully cold; Linux lanes get modest reuse (15/119
tasks FROM-CACHE). Compounding: the repo's own walltime-attribution shows 40-58%
of unit-lane wall-clock is framework overhead — exactly the fraction a working
cache attacks — and **all budgets/baselines are calibrated against the
broken-cache timings**.

**Mechanism (CORRECTED by the 2026-08-14 derisk pass — the first-pass "unquoted
`Program Files` tar path bug" diagnosis was wrong; that error text is a cosmetic
truncation artifact in @actions/cache's error formatter):** the Gradle daemon
(`org.gradle.daemon=true`, 30-min idle timeout) is still alive at post-job
cache-save time and holds mandatory Windows file locks on six
`~/.gradle/caches/**/*.lock` files; GNU tar hits `EBUSY` on each and exits 2
(run 31750644388 job 94615370233 L1926-1934 — the same tar binary *succeeds* at
restore at L231-232 in the same job). Windows-only because POSIX locks are
advisory. Upstream: `actions/setup-java#633` — same diagnosis, closed 2024 as
workaround-only (stop the daemon); setup-java's own README now points at
`gradle/actions/setup-gradle` instead (`#588`/PR `#972`).

### F3 — The re-CI tax is structural and the observed 1.82× multiplier is the lucky case. (MEDIUM-HIGH)

`required_status_checks.strict: true` + serial queue means every merge invalidates
every open PR's up-to-date status; `cancel-in-progress: true` additionally kills
in-flight runs on catch-up push (7 cancels, 231 min; one PR burned 3 CI runs, 2
thrown away, for one merge). Observed multiplier stayed 1.82× only because
effective queue depth was ~2; the O(N²) shape is latent and materializes the day N
ready PRs queue at once. GitHub's native **merge queue** exists for exactly this
and is not enabled.

### F4 — WAITING is one-fifth of orchestrator tokens and it is the conveyor's token bill. (MEDIUM-HIGH)

19.29% of the orchestrator session (214.4M tokens, 139 turns) is
notification-ack turns; 100% task-notification-triggered; each ack replays a
~611M-token cache-read. The 11-session window reproduces tempdoc 746-T1's 4-week
baseline (12.10% vs 12.47%) — structural, not a bad day. Levers: fewer wait cycles
(F1/F3 directly cut the count), and cheaper acks (the cache is the multiplier — an
ack turn's cost scales with session bloat, which argues for merging in fewer,
bigger batches rather than 23 sequential waits).

### F5 — Develocity is write-only: ~600 anonymous public scans/day, zero consumers, flake signal discarded. (MEDIUM)

No `server`, no access key, no remote build cache (`settings.gradle.kts:85-107`;
zero `buildCache` hits repo-wide) — scans go to free public scans.gradle.com
(`publishing.onlyIf { true }` in CI, ~7 scans/run × 90 runs), synchronously
(`uploadInBackground=false`, ~0.7s each). The one datum the retry extension
produces that matters — "passed after retry" = flaky — has no cross-build store, so
flake classification was done by a human/agent reading a red X. The substitute
analytics (`report-unit-test-attribution.mjs`) has zero retry awareness, and the
runs API's phantom job records double-counted 7.2h until deduped. Also: the
`refs/pull/*/merge`-scoped setup-java cache entries (5.79 GB of an 11.1 GB store,
over GitHub's 10 GB cap) are write-only — no sibling ref can read them — so each PR
pays ~820 MB eviction pressure for reuse it cannot collect.

### F6 — Single-file shard contention caused 100% of merge conflicts. (chartered → 828)

11/11 conflicts, 23 union-resolution invocations, 1.39 merge attempts per PR.
Already chartered with acceptance criteria in tempdoc 828-A; workers additionally
began declining to log observations to keep PRs clean — the knowledge-capture
mechanism suppressing knowledge capture.

### F7 — The agent-observability OTel sink has been dead for ~2 weeks, silently. (MEDIUM, meta)

Nothing listens on :4318; last data 2026-07-29 14:01. The `otlp-sink-ensure` hook
is fail-open with no alarm, so tempdoc 622's span/cost attribution silently
disappeared — this analysis had to fall back to transcript archaeology (which is
why the subagent split is medium-confidence). An observability layer that can die
without a signal fails its own purpose.

## §4 What the workflow did well (measured, for balance)

19 min/PR merge cadence over 7 hours; $0 marginal cost; delegation carried ~70% of
token load off the orchestrator; every merge landed green with independent review
on production code; post-merge main CI green end-to-end; the conveyor's friction
(flakes) was converted into a confirmed production-bug fix rather than tolerated.
The repo's home-grown attribution tooling (walltime/test attribution, budgets) is
genuinely good — it just measures a broken-cache baseline and lacks retry awareness.

## §5 Recommendations, ranked by leverage (NOT implemented — owner call)

- **R1 (zero-cost, kills F1):** merge gate consults `required_status_checks.contexts`
  — treat `UNSTABLE` with only advisory lanes red as mergeable; never
  `gh run rerun --failed` a lane that cannot change mergeability. Encode in the
  publish skill + `run-gh.mjs checks-wait` (add a `--required-only` mode).
- **R2 (kills F2; derisked 2026-08-14, fix direction corrected):** replace
  `actions/setup-java`'s `cache: gradle` with `gradle/actions/setup-gradle@v6`
  (which stops daemons in its post-action specifically to avoid the Windows
  lock failure, and whose default cache policy — only main writes, branches
  read — also delivers R7 with zero inputs). Sweep all three workflow files
  (`ci.yml` ×5 sites, `build-installer.yml`, `onramp-smoke.yml`); `cache:` must
  be REMOVED, not supplemented (documented incompatibility). Pre-clean the 7
  dead `refs/pull/*/merge` cache entries (5.79 GB) before first run. Verify:
  `FROM-CACHE > 0` in a Windows lane across two consecutive runs; recalibrate
  walltime budgets after. Six residual live-only unknowns are listed in the
  derisk record (nifty-baking-sunset plan + U3/U4 worker report).
- **R3 (completes F1):** while the integration lane stays advisory, set
  `failOnPassedAfterRetry = false` for `integrationTest` only (keep it true for
  required lanes) — an advisory lane that reddens on self-recovered flakes generates
  pure rerun-bait. Revisit when the lane joins required contexts (825 §3 is the
  path there).
- **R4 (kills F3) — UNIMPLEMENTABLE AS WRITTEN (derisk 2026-08-14):** GitHub
  merge queue is available only to *organization*-owned repos; this repo is
  user-owned (`owner_type: User`, live API fact; docs reusable
  `gated-features/merge-queue.md`). R4 therefore reframes as an OWNER DECISION:
  transfer the repo to an organization (which moves ownership, CLA storage,
  secrets, and the URL) — after which the wiring is small and fully mapped
  (add `merge_group` to ci.yml + workflow-signal-policy; a merge_group no-op
  job named `cla-assistant`; set queue merge method = squash; one live PR to
  confirm the PR_TITLE/PR_BODY squash contract, which rests on a staff comment,
  not docs). Until then, F3's mitigations are R1 (fewer wait cycles) and
  batching PRs.
- **R5 (cheap Develocity value without a server):** teach
  `report-unit-test-attribution.mjs` to parse repeated `<testcase>` entries and emit
  a `flakyTests` list + dedupe rerun phantom records — the flake signal from data CI
  already uploads.
- **R6 (Develocity decision; facts gathered 2026-08-14):** a real Develocity
  instance is quote-only per-committer commercial licensing with no solo-dev tier
  (self-hosting does not avoid the license; the OSS-sponsorship program is a
  discretionary application, not a plan). Free scans.gradle.com scans are
  link-public, permanent once viewed, with no read API. The REAL option set is
  therefore: (a) stop publishing scans entirely, or (b)
  `publishing.onlyIf { buildFailed }` — accepting each failure scan as a permanent
  link-public artifact carrying environment detail. Owner decides; either is a
  one-line settings change. The remote-build-cache benefit is unreachable at this
  scale.
- **R7 (folds into R2, derisked):** `gradle/actions/setup-gradle`'s default is
  exactly the read-only-PR policy (only the default branch writes); GitHub's
  cache-scoping docs confirm `refs/pull/*/merge` saves are readable only by
  re-runs of the same PR, so the policy loses zero legitimate reuse. Prune the
  over-cap store at migration time.
- **R8 (meta):** liveness alarm for the OTel sink (fail-loud SessionStart notice
  when :4318 is down); 828-A fixes the shard contention.

## §6 Honest limits

One-day CI sample (WAITING has a 4-week baseline; CI numbers do not); no
counterfactual measurement of a merge-queue alternative; no OTel for the campaign
day (F7) so subagent attribution is transcript-derived, medium confidence; no
job-level extraction of the boot-flake's per-run cost (bounded at run level);
scans.gradle.com server-side content not inspectable — the F5 claim is bounded to
config + logs; billing is $0 so all costs are latency/attention/tokens, and the
token→velocity conversion rate is not measured (746-T1's framing is adopted, not
re-derived).
