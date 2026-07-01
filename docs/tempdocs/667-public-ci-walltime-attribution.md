---
title: "Public CI wall-clock attribution and advisory latency budgets"
type: tempdoc
status: "active — design settled, not implemented"
created: 2026-07-01
updated: 2026-07-01
related:
  - 651-public-ci-feedback-loop-efficiency   # declared this scope (cross-check timing, build-lane attribution, trend reporting) but left it unbuilt
  - 652-public-ci-unit-test-latency          # built unit-lane attribution + advisory unit budgets; deferred the whole-run map to 651
  - 647-engine-performance-attribution-and-budget-allocation  # the structural twin: the "attribute → budget" band 640 left unbuilt, in the engine domain
  - 640-engine-performance-budget-latency-throughput-footprint # the "measure → guard" band; sibling seam (canonical-record + governed-projection)
---

> NOTE: Noncanonical working note. Verify against `.github/workflows/`, current GitHub
> Actions status, and the code before treating any detail as current truth.

# 667 - Public CI wall-clock attribution and advisory latency budgets

> **Reframe (2026-07-01).** This tempdoc opened as "license-lane split + build-lane attribution."
> The measurement pass below killed the license-split premise (it is never the critical path) and
> reframed the work. The authoritative design is the **Design settlement** section near the end; the
> earlier Purpose / Thread A / Thread B / Theorization sections are retained as the evidence trail
> that led there, not as the current plan. Title updated to match the settled scope.

## Purpose

`652-public-ci-unit-test-latency` split the `Unit tests` lane from one ~13-14min job into
three module-owned shards (~7.5-9min critical path) using a shard-and-converge pattern:
independent facts run in parallel, a small downstream step reconciles them. That tempdoc's own
"Best next research rounds" left two threads unexecuted:

1. **Build attribution round** (652 round #2, verbatim): *"give `Build (no model blobs)` the
   same fact ownership and timing visibility that unit shards now have."*
2. The `License and notices` lane (~5-6min hosted) was measured by 652 but never analyzed for a
   split — it was out of 652's central question (which was scoped to `Unit tests` only).

This tempdoc picks up both threads. Current hosted wall-clock (`gh run list`, 2026-07-01) has three
roughly-tied long poles: `License and notices` (~6min), `Build (no model blobs)` (~8min), and the
`Unit tests` shards (~8-9min each, already addressed by 652). Total PR wall-clock is ~9-11min,
gated by the slowest of these running in parallel.

## Boundary

Inherited from 652 (still correct here): do not split mechanically before measuring. Do not solve
this with paid/larger runners or self-hosted caches — free public CI on standard hosted runners is
the design boundary (ADR-0044, 651). Do not weaken any check's coverage to win wall-clock time.

## Thread A — License lane split

`ci.yml`'s `License and notices` job runs 5 steps serially:
`checkout → setup-java → setup-node → npm ci → checkLicense (Gradle) → dump-cargo-licenses →
dump npm licenses (license-checker) → check-notices-regen`.

Of these, `checkLicense` (JVM classpath), the Cargo license dump, and the npm license dump are
three independent data sources with no dependency on each other — they only need to converge at
the final `check-notices-regen` sync step. This is structurally the same shape 652 already split
successfully.

**Investigation before implementing:**
- Measure each step's standalone duration in the current hosted job (add per-step timing or read
  the existing Action log breakdown) — confirm the split is worth the parallel-job overhead
  (checkout + setup-java/setup-node run 3x instead of once).
- Check whether `checkLicense --no-configuration-cache --no-parallel` is still a necessary flag
  combo, or a stale carryover — 408 already found similar flags stale post-migration and this one
  has not been re-audited since the public-CI cutover.
- Decide shard boundaries: likely `license-jvm` (checkLicense), `license-cargo` (Cargo dump),
  `license-npm` (npm dump), converging into a cheap `notices-sync` job that downloads the three
  artifacts and runs `check-notices-regen.mjs`.

## Thread B — Build lane attribution

`Build (no model blobs)` is a single `./gradlew.bat assemble -PskipWebBuild=false` call with no
internal timing visibility. Before proposing a split (which may not be possible if the module
compile graph is genuinely serial), give it the same attribution 652 gave the unit shards:

- Gradle build-scan or `--profile` summary to see which module/task dominates wall-clock.
- Separate the web-bundle build (`-PskipWebBuild=false`) timing from the JVM `assemble` timing —
  confirm whether they're already parallel inside one job or serialized.
- Only after that data exists, decide whether splitting is possible (e.g., web bundle as its own
  parallel job/artifact) or whether the lane is compile-graph-bound and near its floor.

## What not to do here

- Do not touch `Unit tests` shard boundaries — 652 already settled that design.
- Do not introduce a remote/shared Gradle build cache across jobs as a first move; that is a
  larger design question (652 raised `gradle/actions/setup-gradle` as a separate branch
  experiment, not bundled into this split).
- Do not change required branch protection checks until the new job names are observed stable
  across several hosted runs (same caution 652 applied to its own shards).

## Status log

- 2026-07-01: Tempdoc opened, worktree `ci-latency-667` created. Next: measure License-lane
  per-step timing and Build-lane task attribution before making any workflow edit.

- 2026-07-01, measurement pass (3 hosted runs, `gh api .../actions/jobs/<id>`):

  | run | Unit tests (app-ui) | Unit tests (search-worker) | Unit tests (platform-contracts) | Build | License |
  |---|---|---|---|---|---|
  | 28535232080 | 9:03 | 8:50 | 8:31 | 7:45 | 6:24 |
  | 28534542592 | 11:13 | 8:36 | 9:58 | 7:35 | 4:26 |
  | 28533885410 | 11:14 | 10:32 | 8:44 | 7:14 | 5:02 |

  **Correction to this tempdoc's own Purpose section**: the "three roughly-tied long poles" framing
  is wrong. `Unit tests (app-ui)` is consistently and clearly the critical path (9:03-11:14),
  1.5-4min ahead of `Build` (7:14-7:45), which is itself well ahead of `License and notices`
  (4:26-6:24). Interrogating this before implementing (per CLAUDE.md `interrogate-results`) changes
  the priority order:

  - **Thread A (license-lane split) does not reduce PR wall-clock right now.** License finishes
    minutes before the actual pacer regardless of whether it's split. Per-step measurement (job
    `84594785218`) found `checkLicense` alone is 3:48 of the job's 6:23 (60%) — Gradle/JVM
    configuration + dependency resolution cost, not something a 3-way job split fixes, since the
    split's new critical path is still bounded by that same 3:48 step plus per-job overhead. Also
    confirmed via `build.gradle.kts:567-579`: the job's `--no-configuration-cache` flag is NOT a
    stale carryover (408's hypothesis) — it's load-bearing, working around a documented
    diffplug/spotless Windows config-cache serialization bug that breaks config cache for the whole
    build, not just Spotless. **Deprioritized** — may still be worth doing later for failure-
    ownership clarity (a red `checkLicense` vs. a red Cargo/npm dump currently look identical), but
    that is a different justification than the speed goal this tempdoc opened with.
  - **New lead, higher value: `:modules:app-services:test` and `:modules:ui:test` both live in the
    `app-ui` shard** (`ci.yml` matrix), the confirmed critical-path lane. `app-services`'s
    `build.gradle.kts:116` pins `maxParallelForks = 1` on its plain `test` task (which CI runs) with
    **no comment explaining why** — this is exactly the gap 408 left open ("investigation-warranted
    but not done") and it now sits on the actual bottleneck. (Ruled out `ui:build.gradle.kts:153`:
    that pin is on `integrationTest`, not `test`; CI's app-ui lane only runs `:modules:ui:test`; and
    the pin is documented — avoiding PROGRAMDATA/global-state flakiness — so it's load-bearing and
    out of scope here.) `worker-services`/`indexer-worker` (in the second-place `search-worker`
    shard) both run at `maxParallelForks = 2`.
  - Build-lane attribution (Thread B) stays in scope as the second-priority thread — consistently
    the #2 lane, 1.5-4min behind app-ui.

## Revised scope (post-measurement)

1. **New Thread C (highest priority): investigate `app-services` `maxParallelForks = 1`.** Find
   out why it was pinned (git blame / history, since there's no inline comment), then test locally
   whether raising it to 2 (matching worker-services/indexer-worker) is safe — repeated local runs
   watching for shared-state flakiness — before touching CI. This directly targets the confirmed
   critical-path shard.
2. **Thread B: Build-lane attribution**, as originally scoped — Gradle `--profile`/build-scan to
   find what dominates the ~7.5min `assemble -PskipWebBuild=false` call.
3. **Thread A: license-lane split**, demoted to optional/deferred — not a wall-clock win under
   current measurements; revisit only if reframed as a failure-ownership improvement, not a speed
   one.

- 2026-07-01, Thread C root-cause found: `build-logic/.../JvmBaseConventionsPlugin.kt:38` sets
  `testParallelism` to **1 in CI** (`CI` env var truthy), 3 locally (bumped from 2, tempdoc 390).
  `TestGateService` (`maxParallelUsages = testParallelism`) gates how many Test *tasks* run
  concurrently across modules — in CI that's already 1, so modules in the same shard job never
  overlap regardless of any module's own fork count. That means `app-services`'s
  `maxParallelForks = 1` is not there to avoid cross-module contention (there isn't any in CI) — it
  only throttles parallelism *within* its own 271-test suite (tempdoc 287), which nothing else in
  the app-ui shard depends on. No comment or historical commit explains the pin (repo history was
  squashed at the public-release cut, ADR-0045); tempdoc 287 (2026-03-13) documents its existence
  but not its cause. Next: test locally whether raising it to 2 (matching worker-services/
  indexer-worker) causes flakiness before touching CI.

## Theorization pass - 2026-07-01

Design is not settled. This section is deliberately exploratory: alternative framings, directions,
and risks to weigh before committing to an implementation, not a plan.

### Different ways to frame "make CI better"

1. **Lane wall-clock framing (the framing used so far).** Attack each job's internal critical path:
   split monolithic jobs into independent shards, raise fork counts, attribute compile/test time.
   This is what 651 and 652 already did, and what Threads A-C above continue.

2. **Total-work framing.** Instead of running the same work faster, run less work per PR. 652
   explicitly rejected "required path-based skipping" for *test* lanes, reasoning that any source
   change could affect correctness in ways a path filter can't safely predict. That reasoning does
   not obviously transfer to every lane. `License and notices` is structurally different: it is a
   pure function of a narrow, enumerable file set (`build.gradle.kts` dependency blocks, Gradle
   lockfiles, `package.json`/`package-lock.json`, `Cargo.toml`/`Cargo.lock`,
   `config/allowed-licenses.json`). A PR that touches none of those files cannot change that lane's
   answer. Whether this distinction is real enough to act on, and whether it should be advisory-only
   or eventually skip-eligible, is worth thinking through explicitly rather than inheriting 652's
   test-lane caution by default.

3. **Redistribute-across-time framing.** Not everything needs to be recomputed synchronously on
   every push. Some evidence (license state, notice sync) changes rarely; a fast advisory check
   plus a slower authoritative nightly/scheduled re-verification is a different shape than "every
   PR pays the full cost every time."

4. **Fixed-cost-vs-variable-cost framing.** Some of the ~9-11min may be an unavoidable tax of
   hosted Windows runners (cold JVM start per job, no persistent Gradle daemon across jobs, no
   shared build cache between jobs, per-job checkout/setup) rather than anything this repo's build
   graph controls. Attribution work (Thread B, and the app-services lead) assumes the measured time
   is mostly "our work"; it would be worth first establishing the floor — how long a trivial no-op
   job takes on the same runner image — so future measurements can separate irreducible tax from
   addressable cost.

5. **Failure-ownership framing (652's own framing, distinct from speed).** A red check should tell
   a contributor what's broken without opening logs. Under this framing, splitting `License and
   notices` into `license-jvm` / `license-cargo` / `license-npm` still has value even though it
   doesn't move total wall-clock — a red `checkLicense` and a red npm license dump currently look
   identical from outside the job. This is a legitimate reason to keep Thread A on a list, just not
   under this tempdoc's original wall-clock goal.

6. **Perceived-latency framing.** Contributors may care more about *time to first useful signal*
   than *time to last check finishing*. 651 already moved in this direction (seconds-scale fact
   lanes finish first). It's an open question whether the slow lanes could similarly front-load
   their highest-signal, cheapest-to-compute subset, so a contributor gets an early "probably fine"
   or "definitely broken" without waiting for the full lane — without necessarily shrinking that
   lane's own total wall-clock.

### Possible solution directions worth keeping open

- **Shard-and-converge applied to `Build (no model blobs)`**, if attribution shows the JVM
  `assemble` and the web bundle build (`-PskipWebBuild=false`) are currently serialized inside one
  job rather than already overlapping — the same pattern 652 proved on Unit tests, applied one lane
  over.
- **A broader fork-count audit**, not limited to `app-services`. The two `maxParallelForks = 1`
  pins found by tempdoc 287 (`app-services:test`, `ui:integrationTest`) may not be the only
  under-parallelized spot; a full repo grep plus a check against which shard each module lives in
  would show whether other modules are similarly throttled on the critical-path shard specifically,
  versus throttled but harmless (like `ui:integrationTest`, not on the app-ui shard's `test`-only
  task list).
- **The CI-wide `testParallelism = 1` value itself.** This is a bigger and riskier lever than any
  single module's fork count: it caps how many Test *tasks* run concurrently across an entire shard
  job, and its current value of 1 is commented as "conservative" without a cited measurement (unlike
  the local value of 3, which cites a specific before/after comparison, tempdoc 390). Whether 1 was
  chosen from a real memory/OOM constraint on the hosted runner or just as an untested safe default
  is unknown from the code alone. If it could safely move to 2, every module in a shard would
  benefit, not just one — but this also carries the most risk (see below) and would need its own
  hosted-runner-specific validation, not local repetition.
- **A shared/warm build cache across jobs within one CI run.** Right now `Build`, all three `Unit
  tests` shards, and `License and notices` each independently compile whatever modules they touch,
  redundantly, in parallel jobs that share nothing but a dependency cache. A build-output cache
  populated by one job and reused by the others (not necessarily a paid remote-cache service — could
  be a GitHub Actions cache-backed local build cache) could cut redundant compile work across the
  whole run. This is close to what 652 already flagged and deferred (`gradle/actions/setup-gradle`
  as "needs a hosted A/B run before adoption") — worth keeping linked rather than treating as a new
  idea.
- **A per-check "what changed makes this check's answer change" map.** Several already-deferred
  ideas from 652 (a "CI facts" contributor page, evidence-policy-as-checklist) and the total-work
  framing above are really asking for the same underlying artifact: an explicit, documented mapping
  from required check to the narrow set of inputs it's a function of. Building that once would make
  safe advisory path-relevance decisions (for lanes like License) legible and reviewable, instead of
  ad hoc per-lane judgment calls.
- **Tracking the upstream config-cache blocker.** `build.gradle.kts:567-579` documents a
  diffplug/spotless Windows bug that disables Gradle configuration cache for the *entire* build, not
  just Spotless. If that's ever fixed upstream, enabling config cache could speed up the
  configuration phase of every CI job, not just one lane — a bigger lever than any single-lane fix
  discussed here. Worth a standing note to re-test when Spotless/Gradle versions bump.

### Tradeoffs and risks to weigh, not resolve here

- **Local flakiness testing does not validate hosted-runner memory pressure.** Any fork-count
  increase (app-services specifically, or the CI-wide `testParallelism` value) needs validation on
  the actual hosted runner, not repeated local runs on a dev machine with far more cores and RAM.
  A change that's stable locally could still OOM or contend for CPU on a resource-constrained hosted
  runner in ways that only show up under real hosted conditions.
- **Per-job fixed overhead has a floor.** Every additional parallel job pays its own
  checkout/setup-java/setup-node cost and runner provisioning/queue latency. Splitting a lane into
  more jobs is not free; past some point, added shards cost more in fixed overhead than they save in
  parallel work. There's no data yet on where that point is for this repo's jobs.
- **Concurrent-job budget is a fleet-level, not per-PR, cost.** Adding more parallel jobs per PR
  increases the number of runner-minutes and concurrent slots consumed per push. For a single PR
  this doesn't change wall-clock, but if many PRs are open at once it could cause queueing against
  the free tier's concurrency limits — a tradeoff distinct from any single PR's latency.
- **More shards means a larger set of required-check names to reason about.** 652 already noted
  this tension for the Unit tests shards; splitting License the same way would add to it. There's a
  legibility cost to a growing checks list that a pure speed or ownership argument doesn't capture
  on its own.
- **Absence of a comment is not evidence of absence of a reason.** The `app-services`
  `maxParallelForks = 1` pin has no inline explanation, and public repo history was squashed at the
  release cut (ADR-0045), destroying the commit-level trail. It's equally plausible this was a
  deliberate fix for an intermittent failure that simply wasn't documented as it was made. Green
  local runs are necessary but not sufficient evidence that raising it is safe.

### Hidden assumptions worth naming

- That the measured hosted runner has the specs actually assumed (core/RAM counts implicit in the
  reasoning above should be confirmed against GitHub's current published spec for public-repo
  Windows runners, not assumed from general knowledge that may be stale).
- That "free, standard hosted runners" is a permanent constraint rather than one that was simply
  never re-evaluated against how much a minimal paid tier would help. This tempdoc inherits that
  boundary from 651/652 without re-litigating it — worth flagging as inherited, not re-derived.
- That total lane wall-clock is the metric contributors actually care about, versus time-to-first-
  signal or predictability/variance of the number (a lane that's reliably 8min may be preferable to
  one that's usually 6min but occasionally 12min).
- That every `maxParallelForks` pin in this codebase reflects the same underlying reason. The two
  found so far (`app-services:test`, `ui:integrationTest`) turned out to have different relevance
  (one live on the critical path, one documented and irrelevant to it) — a broader audit should not
  assume the next one found will pattern-match either case.

### Possible broader principle

The 651-to-652 pattern and the License/Build leads above share a shape: **a CI job's current
boundary usually reflects how the workflow file happened to be written, not an analysis of which
facts inside it are actually independent.** `License and notices` bundles three unrelated data
sources for the same reason the original `Unit tests` job bundled unrelated modules — nobody had
split it yet, not because splitting it would be wrong. A recurring move worth naming: for any
monolithic CI job, ask first whether its steps are independent (shard-and-converge is safe) or
genuinely sequential (attribute the dominant sub-cost before proposing a change) — and treat "this
job groups several things" as itself a signal worth checking, not a fixed shape.

A more speculative, second-order candidate: the various deferred ideas across 652 and this tempdoc
(a CI facts page, an evidence-policy checklist, path-relevance for narrowly-scoped lanes) may all be
partial views of one missing artifact — an explicit map from each required check to the inputs it's
a deterministic function of. That's a much bigger undertaking than anything scoped here, and is
flagged only as a pattern worth watching for, not a direction to pursue yet.

## Design settlement - 2026-07-01

This is the authoritative design for 667. It supersedes the Thread A / Thread B framing above. It is
a design direction, not implementation-level YAML or schema.

### What the problem actually is, restated

The opening framing ("split the license lane, attribute the build lane") was a list of candidate
levers. Two passes — a wall-clock measurement pass and an inventory of existing CI infrastructure —
reframed it into a sharper problem with two layers:

- **Surface symptom.** The critical-path lane (`Unit tests (app-ui)`, ~9-11min) is throttled by
  test-parallelism config (`app-services:test maxParallelForks = 1`; the CI-wide `testParallelism = 1`
  in the shared convention plugin) whose rationale is not recorded anywhere. During measurement it was
  impossible to tell a deliberate throttle (fixing a real flake or OOM) from an undocumented default.
  That uncertainty is the actual blocker — not the raw number.

- **Recurring problem underneath.** Every CI-latency investigation in this repo's history (408, 651,
  652, and this tempdoc) begins by re-deriving where CI time goes **by hand** — a manual `gh api`
  pull of job/step durations that lands in a tempdoc status log and then rots. There is no standing
  answer to "which lane is the critical path," "how much of a lane is fixed runner tax vs addressable
  work," or "is total wall-clock drifting up or holding." The user's own question — "further
  improvement may not be realistically possible" — is exactly a trend-against-baseline question that
  the repo currently has no instrument to answer. The floor-vs-regression distinction cannot be made
  from a single manual sample.

### Existing substrate to extend (not replace)

The inventory found that most of the needed shape already exists — but scoped to the *test-suite*
layer, reading JUnit XML, not the *GitHub Actions wall-clock* layer:

- `scripts/ci/report-unit-test-attribution.mjs` — parses JUnit `TEST-*.xml` into per-module and
  per-suite timing; emits JSON + Markdown + `GITHUB_STEP_SUMMARY`; output kind
  `justsearch-unit-test-attribution.v1`; uploaded as a per-lane CI artifact. Pure attribution, no
  gating. This is the shape to generalize.
- `scripts/ci/report-unit-test-budget.mjs` — **already an advisory, warn-only budget** over that
  attribution (`maxSummedSuiteSeconds`, `maxSkipped`, `slowSuiteWarnSeconds`); explicitly leaves the
  Gradle test step as the pass/fail authority. This is the advisory-budget precedent to mirror.
- `scripts/ci/unit-test-shard-policy.v1.json` + `verify-unit-test-shard-policy.mjs` — declares each
  shard's check name / artifact / warn-only budget, and fails if the policy drifts from `ci.yml`.
- `scripts/ci/workflow-signal-health.mjs` — **already fetches** the GitHub Actions run JSON including
  `jobs` (with `startedAt` / `completedAt`), but uses it only for freshness/health and **discards the
  durations**. Those discarded durations are precisely the raw input the wall-clock layer needs — the
  manual `gh api` pull done in this tempdoc's Status log is re-fetching data the repo already knows how
  to retrieve.
- `docs/decisions/0044-public-hosted-ci-fact-lanes.md` — the canonical owner of "CI is organized as
  fact lanes"; its Context already frames the latency problem. `docs/explanation/09-testing-strategy.md`
  owns the unit-shard evidence model.

Nothing here needs replacing. The gap is a missing *layer* (Actions wall-clock) parallel to an
existing one (JUnit test-suite time), and a missing *coverage* (all lanes, not just unit lanes).

### The correct long-term design

667 owns the **CI wall-clock attribution band** — the direct analogue of what 647 is to 640 on the
engine side, and the concrete build-out of the "cross-check timing / build-lane attribution / trend
reporting" scope that 651 declared for itself but shipped only as a stub. The band has three parts,
each an *extension* of the substrate above, ordered by how strictly the present problem requires them.

1. **Wall-clock attribution as a standing instrument (core; the problem requires this).**
   A per-run projection that reads the GitHub Actions job/step timings (the data
   `workflow-signal-health.mjs` already fetches and drops) and decomposes each run into per-lane and
   per-step wall-clock, in the same JSON + Markdown + step-summary shape
   `report-unit-test-attribution.mjs` already uses for the test-suite layer. This makes "which lane is
   the critical path" and "how much of a lane is fixed runner tax (checkout / setup / cold JVM) vs
   addressable work" a durable, every-run fact instead of a manual re-derivation. It is the CI analogue
   of 647's "turn the one-off attribution into a standing instrument," and it is pure attribution — no
   pass/fail authority, exactly like the unit attribution it mirrors.

2. **Advisory wall-clock budgets (committed direction; extension of an existing script).**
   A warn-only per-lane wall-clock budget, mirroring `report-unit-test-budget.mjs`, whose floors
   **project from a measured baseline** rather than being hand-typed — reusing the canonical-record +
   governed-projection seam that 647 mandates and the perf ratchet already embodies (its floors project
   from `release.v1.json`). This is what answers the user's floor-vs-drift question over time. It stays
   **advisory / warn-only** — 652 already ruled that CI budgets "should start advisory and trend-based,
   fed by attribution… Blocking budgets are premature until hosted variance is understood over multiple
   runs," and hosted-runner variance is real (the same lane measured 9:03 and 11:14 across two runs).
   No governance gate, no branch-protection check.

3. **Parallelism config carries its rationale (minimal discipline; directly fixes the blocker).**
   The tuning constants that gate a lane's wall-clock — `testParallelism`, per-module
   `maxParallelForks` — should record the measurement that justifies their current value, the way the
   *local* `testParallelism = 3` already cites its before/after measurement (tempdoc 390) but the CI
   `= 1` branch and `app-services:test maxParallelForks = 1` do not. This is not "change the values"
   (that is implementation, and it needs validation on the real hosted runner, not a dev machine with
   far more cores/RAM). It is "a value that gates a measurable cost must be legible to the next
   investigator as deliberate-or-default." It directly removes the blocker this tempdoc hit.

### Scope discipline — what this design deliberately does NOT build

- **No blocking CI-latency gate.** Advisory/warn-only only, per 652's explicit rule and the real
  hosted variance. The lighter `scripts/ci/*-ratchet` + policy-JSON precedent is the conformant home,
  not the heavier governance discipline-gate kernel (baseline + enforcer + changesets), which is for
  hard gates.
- **No value changes in this tempdoc.** Raising a fork count or the CI `testParallelism`, moving a
  lane to Ubuntu, or adopting a cross-job build cache are optimization moves (the "648 band"). They
  must be *led* by the attribution this tempdoc builds, and validated on the hosted runner — not chosen
  now from guesswork. Recorded as follow-on, not designed here.
- **No general repository-wide evidence framework.** 652 warned specifically against "a general
  repository evidence registry." This band is scoped to CI wall-clock, sibling to the existing
  unit-test-suite attribution, and nothing wider.
- **The license-lane split is dropped** as a speed measure (measurement shows it is never the critical
  path). If it is ever done, it is for failure-ownership legibility, and it belongs to whichever
  tempdoc owns lane naming — not here.

### Reach judgment

**This design is an instance of an existing seam, not a new one.** It conforms to, and is composed
from, shapes already in the system:

- 651's **declared evidence lanes** and 652's **declared evidence substitution** — the wall-clock
  layer projects run internals into named, owned, per-lane facts, exactly as the fact lanes and the
  unit attribution already do.
- 647's **"attribute → budget" band** and its mandate to reuse the **canonical-record +
  governed-projection seam** — budgets project from a measured baseline, never a second hand-typed
  authority.
- The perf/relevance/leak ratchets' **advisory, warn-only, projected-floor** tier — the conformant
  precedent for the budget half.

Because it conforms rather than forks, the right move is to *extend* `report-unit-test-attribution`
/ `report-unit-test-budget` / `workflow-signal-health` and the `*-policy.v1.json` model, not to
stand up a parallel CI-perf mechanism.

**Two principles this problem surfaces, recorded but not built out now** (per the deliberate split
between recognizing a general principle and building general structure):

- **Principle A — the "attribute → budget" band is a recurring shape wherever a cost is guarded.**
  A guard that fires on *aggregate* regression is not a budget. The full shape is: measure → guard
  (aggregate ratchet) → **standing per-component attribution** (where did the cost go?) → **declared
  per-component allowances** (what should each component cost?) → optimize. The engine grew the first
  two rungs (640) and left the attribution/budget rungs unbuilt (647); CI grew the first two rungs
  (651/652) and left the same rungs unbuilt (this tempdoc). The pattern is domain-independent.
  *Candidate scope:* any aggregate the repo guards without decomposing it — engine latency (647,
  already recognized), CI wall-clock (667, this), indexing throughput (278 is the stale precedent).
  *Do not* build a shared cross-domain "budget framework" — the two instances share a shape, not yet
  a reason to change together (AHA: unify only what shares a reason to change).

- **Principle B — a tuning constant that gates a measurable cost must cite the measurement that
  justifies its value.** This is the repo's existing "declared, not assumed" / "verify, don't guess"
  discipline applied to operational tuning constants. The local `testParallelism = 3` satisfies it
  (cites tempdoc 390); its CI `= 1` sibling and `app-services:test maxParallelForks = 1` violate it —
  a value on the critical path with no recorded reason. *Candidate scope:* performance-tuning
  constants generally (fork counts, test heap sizes, cache-size limits, step timeouts, budget bands).
  *Known current violations:* the two named above; `ui:integrationTest maxParallelForks = 1` is the
  compliant counter-example (its reason — PROGRAMDATA/global-state flakiness — is documented inline).
  *Do not* build a linter for this now — the present problem needs the rationale recorded for a
  handful of knobs, not a new enforcement gate. Recording the invariant is the deliverable; a gate
  would be premature abstraction.

### Follow-on (recorded so it is not silently dropped)

- The optimization moves this attribution would *lead* (fork-count / `testParallelism` changes, a
  cross-job Gradle build cache, `Build (no model blobs)` internal parallelism) — the "648 band" for
  CI. Blocked on: standing attribution existing first, and hosted-runner validation.
- Re-testing whether the whole-build config-cache blocker (a documented upstream diffplug/spotless
  Windows bug) has been fixed on a later Spotless/Gradle bump — a bigger lever than any single lane,
  since it would cut the configuration phase of every job.
- Whether the wall-clock attribution belongs alongside the unit-test attribution in
  `docs/explanation/09-testing-strategy.md` or warrants a short canonical CI-structure note under
  `docs/reference/` (no such doc exists today; the model currently lives in ADR-0044 + the policy
  JSONs).

## Internet research pass - 2026-07-01

**Why warranted, and why narrow.** Only Part 1 (wall-clock attribution) has a *sourcing* decision
that is externally determined and moving fast enough that internal knowledge could be stale in a
design-altering way — the other two parts reuse established internal patterns. 651 already ran an
internet pass on the runner-cost / cache-size / cache-provider-licensing boundary, so this pass does
not re-cover that; it is scoped to three attribution-sourcing questions: (1) the current GitHub
Actions timing surface, (2) whether the official Gradle action now emits a machine-readable per-task
timing artifact, (3) the maturity of OpenTelemetry's CI/CD semantic conventions given the repo
already runs an OTLP sink (tempdoc 622).

**No external code, text, or assets were copied in this pass** — it informs design decisions only, so
there is no license/notice impact. The one external-service caveat (below) was already recorded by
651.

### Finding 1 — Actions step timestamps are the right source; reruns are a real gotcha

`GET /repos/{owner}/{repo}/actions/jobs/{job_id}` exposing `steps[].started_at` / `completed_at`
(ISO-8601) is the current, public-read, authoritative per-step wall-clock source — the same data the
manual measurement in the Status log used. The workflow-run *usage/timing* endpoint returns billable
minutes only (and billing applies to private repos), so it is **not** a wall-clock source. **Design
impact: confirms Part 1's data source.** New correctness note to carry into implementation: when a
job is re-run (`run_attempt > 1`), GitHub reuses the *original* attempt's step timestamps, so
attribution must key on `run_attempt` (or read the per-attempt jobs endpoint) or it will silently
report stale durations for reruns.

### Finding 2 — The official Gradle action gives a human summary + external Build Scan, not a machine-readable local timing artifact

`gradle/actions/setup-gradle` produces a **Job Summary** by default (tasks executed, build outcome,
cache-entry details, and a Build Scan link) — but this is a human-readable Actions-UI surface, not a
machine-readable per-task timing export. The complete machine-readable per-task timeline lives only
in the **Build Scan**, which is an external Develocity-hosted service (its "enhanced" tier is
proprietary — the licensing point 651 already flagged). **Design impact: confirms, does not change,
the design.** Our own attribution (JUnit XML for the test layer, Actions step JSON for the wall-clock
layer) is not reinventing an existing machine-readable local feature — none exists in the free /
no-external-service envelope this design must stay inside. The action's Job Summary is a candidate
*complementary human surface* later, never the machine source.

### Finding 3 — OTel CI/CD conventions exist but are Development-status; conform to the internal sibling now, watch OTel for the later backend

OpenTelemetry has CI/CD semantic conventions defining `cicd.pipeline.run.duration` (histogram,
seconds) with `cicd.pipeline.name` / `cicd.pipeline.run.state` / `cicd.pipeline.result` attributes,
and GitHub-Actions-to-OTel exporters exist (community actions; the OTel Contrib `githubreceiver`).
But the convention's status banner is verbatim **"Status: Development"** — OTel's least-stable tier,
where attribute names can still change. **Design impact — refines the reach principle rather than
the design:**

- The **immediate advisory artifact should conform to the stable *internal* sibling** — the
  `justsearch-unit-test-attribution.v1` JSON + Markdown + step-summary shape that
  `report-unit-test-attribution.mjs` already establishes — **not** to an unstable external
  convention. Adopting a Development-status external vocabulary for a per-PR advisory report would be
  premature abstraction: the primary consumer is a warn-only report, not a telemetry backend, and the
  repo's established pattern is the versioned `.v1.json` kind.
- OTel's `cicd.pipeline.*` vocabulary is recorded as the **candidate alignment target for a *later*
  trend/telemetry backend** — which would naturally emit through the repo's existing OTLP sink
  (tempdoc 622) rather than invent field names — to be adopted only once the convention stabilises.
  This is a "watch and align later," not a now-decision.
- Independently, the existence of a dedicated OTel CI/CD observability effort is **external
  corroboration of Principle A** (the "attribute → budget" band as a general shape): "CI/CD
  observability = standing pipeline/task duration attribution" is a recognised cross-industry shape,
  not a repo-local coincidence. It strengthens the reach claim without changing the scoped design.

Sources: [Actions workflow-jobs REST API](https://docs.github.com/en/rest/actions/workflow-jobs),
[Viewing job execution time](https://docs.github.com/en/actions/monitoring-and-troubleshooting-workflows/viewing-job-execution-time),
[cli/cli #9769 (step started/completed export)](https://github.com/cli/cli/issues/9769),
[gradle/actions setup-gradle docs](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md),
[A Better Way to Use Gradle with GitHub Actions](https://blog.gradle.org/gh-actions),
[OTel CI/CD metrics semconv](https://opentelemetry.io/docs/specs/semconv/cicd/cicd-metrics/),
[OTel Contrib githubreceiver / GH-Actions OTel exporters](https://github.com/marketplace/actions/opentelemetry-for-github-workflows-jobs-and-steps).

## Confidence-building pass - 2026-07-01

A pre-implementation investigation to reduce surprises. Read-only / measurement-only (repo reads,
`gh api` reads over 15 recent `main` runs, GitHub-docs facts). It **corrects two claims in the
Design settlement** and confirms the rest. No feature code, no value changes.

### Finding A — the execution model splits cleanly in two, each with a repo precedent

`.github/workflows/ci.yml` runs its seven lanes as independent parallel jobs with **no `needs`
fan-in, no `workflow_run`**, and `permissions: contents: read` only (`ci.yml:15-16`). The existing
unit attribution runs *inside* each unit-test matrix job (`ci.yml:228-247`), so it only ever sees its
own lane. Whole-run wall-clock attribution needs cross-lane data, which forces a choice — and the two
halves of the design want different answers, each already precedented in the repo:

- **Part 1 (per-run attribution)** → a small aggregation job gated `needs: [all lanes]` that calls
  `gh api /repos/{o}/{r}/actions/runs/${{ github.run_id }}/jobs` (sibling durations are complete by
  then). This needs `actions: read`, which `ci.yml` does **not** currently grant — its
  `contents: read` block sets every other scope, including `actions`, to `none`. Precedent for the
  permission exists in-repo (`codeql.yml:7`, `phase-3-observability-nightly.yml:44`). `needs`-fan-in
  is a new shape for `ci.yml` but trivial, and the job is tiny so it adds ~no critical-path time.
- **Part 2 (trend / budget)** → conform to `phase-3-observability-nightly.yml`: a scheduled/dispatch
  **post-hoc analyzer** that already has `actions: read`, uploads artifacts with retention, and opens
  a `gh` issue on drift. Running over `main`'s recent runs, it **sidesteps the per-PR fan-in and the
  fork-token question entirely** — the right home for the drift/floor signal (which is inherently
  multi-run).

**Confidence: raised.** Both halves now have named precedents; the only new primitive is one
`needs`-gated job plus an `actions: read` grant.

### Finding B — CORRECTION: the budget baseline is an advisory policy threshold, not a governed projection

The Design settlement said Part 2 should "reuse 647's canonical-record + governed-projection seam."
Investigation shows that is **wrong for CI wall-clock**, and the correction matters:

- The perf ratchet *does* project from a release (`perf-ratchet-baselines.v1.json` is a "POINTER, not
  a table" — floors projected from `release.v1.json`) **because engine performance is a property of a
  release** (cohort-identical config/commit/hardware).
- CI wall-clock is **not** a release property — it is a property of the CI environment over time. And
  the actual existing CI-budget precedent, `unit-test-shard-policy.v1.json`, uses **hand-set absolute
  advisory thresholds** (`maxSummedSuiteSeconds: 300`, `slowSuiteWarnSeconds: 60`, `maxSkipped: 50`),
  warn-only, consumed by the pure function `buildBudgetReport()` in `report-unit-test-budget.mjs`.
  Nothing is projected from a release.

**Correction:** Part 2's baseline should mirror the **`unit-test-shard-policy` advisory-threshold
seam** (a versioned policy JSON of warn-only ceilings), **seeded from a measured median** (see Finding
C), *not* the perf ratchet's release-projection. The 647 analogy holds at the **band** level
("measure → guard" vs "attribute → budget") but **not** at the baseline-sourcing level. Principle A
(the reach claim) is unaffected; only the specific "governed-projection" sentence in the settlement is
superseded by this note.

### Finding C — variance is moderate and characterizable; app-ui is robustly the critical path

Per-lane job wall-clock over **15 recent successful `main` runs** (`gh api .../jobs`, seconds):

| lane | n | min | median | max | mean | CV% | max/median |
|---|---|---|---|---|---|---|---|
| **Unit tests (app-ui)** | 15 | 534 | **600** | 708 | **601** | 8.6 | 1.18 |
| Unit tests (search-worker) | 15 | 477 | 528 | 634 | 530 | 6.4 | 1.20 |
| Unit tests (platform-contracts) | 15 | 396 | 511 | 617 | 525 | 9.8 | 1.21 |
| Build (no model blobs) | 15 | 409 | 475 | 582 | 482 | 8.7 | 1.23 |
| License and notices | 15 | 266 | 322 | 356 | 323 | 8.2 | 1.11 |

- **app-ui is the slowest lane in 12/15 runs** and has the highest mean by a clear margin — the
  design's critical-path premise is confirmed at n=15, not n=3.
- Run-to-run variance is **lower than the initial 3-sample scare** (CV ~6–10%, max/median ~1.18–1.23),
  so a per-run advisory ceiling around **1.25× median** would rarely false-alarm — coincidentally the
  perf ratchet's own `ce_p50_ms` band (1.25). A per-run warn is viable; a trend-over-N is cleaner
  still.
- **Key additive insight:** job wall-clock (~600s for app-ui) is ~2× the existing summed-suite budget
  (300s). That ~300s gap is JVM cold start + Gradle configuration + fork serialization — exactly what
  the wall-clock layer surfaces and the suite-time attribution **cannot** see. This confirms the new
  layer is additive, not redundant.

### Finding D — CORRECTION: a sibling script, not an extension of `report-unit-test-attribution.mjs`

The settlement offered "generalize the shape OR add a sibling." Investigation resolves it to
**sibling**: `report-unit-test-attribution.mjs`'s `buildReport({ root })` **walks the filesystem for
JUnit XML internally** — its input source is a directory of `TEST-*.xml`, structurally wedded to the
test layer. Wall-clock attribution's input is Actions job JSON, an entirely different source. The
clean design is a **sibling** (e.g. a `report-ci-walltime.*` pair) mirroring
`report-unit-test-budget.mjs`'s structure — a **pure builder over already-parsed JSON** plus a thin
`main()` that isolates the `gh` fetch — sharing only the output conventions (`KIND` string, dual
JSON+MD, `GITHUB_STEP_SUMMARY` append). Because the builder is pure, it is **unit-testable with a
captured Actions-jobs-JSON fixture — no network mocking**, matching the existing test harness.

### Finding E — runner facts sharpen Part 3 and confirm Part 1's reach

- **windows-latest standard runners for public repos are 4 vCPU / 16 GiB, free and unlimited.** So the
  memory-constraint hypothesis for the parallelism throttles is **not supported**: 2 test JVMs
  (2×384 MB) + a 1 GB daemon ≈ 1.8 GB, trivial against 16 GiB. The realistic ceiling on useful test
  parallelism is the **4 vCPUs**, not memory. Part 3's recorded rationale can therefore state, on
  evidence: `app-services maxParallelForks = 1` and CI `testParallelism = 1` are almost certainly
  **conservative defaults, not memory constraints** — raising app-services to 2 is plausibly safe on a
  4-vCPU box (still leaves headroom), while going high would hit the vCPU wall. (Still an
  implementation-band change requiring hosted validation — recorded, not done here.)
- **Fork-PR tokens are read-only but `actions` is a grantable read scope**, so a `needs`-gated Part-1
  job with `actions: read` would also work for external-contributor PRs. Caveat confirmed: because
  `ci.yml` currently declares `contents: read`, all other scopes are `none` — adding `actions: read`
  is a required, low-risk edit (verify it doesn't trip `check-workflow-triggers.mjs`, which checks
  triggers, not permissions — low risk).

### Net effect on the plan

Two design corrections (B: advisory-policy-threshold not governed-projection; D: sibling not
extension), one execution-model resolution (A: `needs`-job + `actions: read` for Part 1; nightly-style
analyzer for Part 2), a sharper Part 3 rationale (E: CPU-bound not memory-bound), and a confirmed,
quantified critical path with a defensible budget margin (C: ~1.25× median). No blocker surfaced; the
design stands with the two wording corrections folded in.

Sources: [GitHub-hosted runners reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners),
[Double the power for open source](https://github.blog/news-insights/product-news/github-hosted-runners-double-the-power-for-open-source/),
[Controlling permissions for GITHUB_TOKEN](https://docs.github.com/en/actions/writing-workflows/choosing-what-your-workflow-does/controlling-permissions-for-github_token).
