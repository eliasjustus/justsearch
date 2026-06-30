---
title: "Public CI unit-test evidence ownership and test-tier policy"
type: tempdoc
status: "implemented - long-term design settled; coordination boundary recorded"
created: 2026-06-28
updated: 2026-06-28
related:
  - 651-public-ci-feedback-loop-efficiency
  - 653-public-main-history-hygiene
  - 650-go-public-capability-descriptor-truthfulness
  - 647-engine-performance-attribution-and-budget-allocation
---

> NOTE: Noncanonical working note. Verify against `.github/workflows/`, current GitHub
> Actions status, Gradle test reports, and the code before treating any detail as current truth.

# 652 - Public CI unit-test evidence ownership and test-tier policy

## Purpose

Tempdoc `public-ci-feedback-loop-efficiency` made public CI factual, parallel, free-to-use, and
green. The remaining bottleneck is now clearer: the public `Unit tests` check is still one broad
Windows lane, and it determines total PR CI wall-clock time.

The purpose of this tempdoc is to settle the right long-term shape of the public test signal. The
goal is not simply "make CI faster." The goal is to reduce public CI wall-clock time and improve
failure ownership while preserving trustworthy required test coverage and staying inside the free
public CI boundary.

## Why this matters now

The latest green PR evidence from the public CI fact-lane work showed roughly this shape:

- fast provenance and claim checks finish in seconds;
- license/notice and no-model build finish in about five minutes;
- `Unit tests` is the slowest required lane, around fourteen minutes on hosted Windows.

That means further meaningful CI speed improvement probably depends on understanding the unit-test
surface, not on more generic workflow reshuffling. A red `Unit tests` check is also less informative
than the new fact lanes: it can mean app-service behavior, worker extraction behavior, parser
fixture behavior, platform assumptions, or ordinary unit regression.

## Boundary

Do not start by splitting tests mechanically. First establish what facts the current unit lane is
supposed to prove, which tests dominate time, and which tests require Windows.

Do not weaken coverage to win wall-clock time. Moving a test out of the broad unit lane is acceptable
only if its new home still gives an honest public or documented local signal.

Do not solve this with paid runner classes, paid cache expansion, or broad build-output caches.
The design should remain usable for a public repository on standard hosted runners and ordinary
dependency caches.

Branch protection should not be changed by this investigation. A later implementation can recommend
which observed stable checks should become required.

## Central question

What should the public unit-test signal mean after the repo already has separate public facts for
claims, license/notices, build, DCO, CLA, and secret scanning?

A good answer should say:

- which test facts should remain required on every PR;
- which facts are Windows-specific;
- which facts are platform-neutral and could run elsewhere;
- which slow fixture or parser checks are actually integration evidence rather than ordinary unit
  evidence;
- which check names would be meaningful to require later.

## Investigation starting points

Measure before designing:

- collect current hosted timings for `Unit tests` and relevant Gradle test tasks;
- inspect local Gradle test reports or use targeted Gradle runs to identify slow modules and slow
  classes;
- compare Windows-only assumptions against tests that could plausibly run on Ubuntu;
- examine existing test tags, skip conditions, CI environment checks, and module boundaries;
- review the `StructuredExtractionIntegrationTest` and `VduEligibilityPdfFixturesTest` precedent for
  Tika/PDF fixture behavior on hosted Windows.

The first output should be a map of the current unit-test surface, not a workflow rewrite.

## Possible directions to keep open

**Module or subsystem lanes.** Split the broad unit lane into owned lanes such as app-services,
worker-services, and remaining JVM modules. This could reduce wall-clock time if the lanes are
balanced, but duplicated Gradle setup and compilation can erase the gain.

**Worker extraction / parser fixture lane.** Slow Tika, PDF, OCR, and document-parser fixtures may
deserve an explicit lane or documented local-only policy. This would make parser failures legible
instead of hiding them inside generic `Unit tests`.

**Platform split.** Platform-neutral JVM tests might run faster on Ubuntu while Windows-specific
tests remain on Windows. This only works if the boundary is explicit and does not accidentally drop
Windows path, native, Tauri, or packaging coverage.

**Gradle parallelism tuning.** Increasing forks or task parallelism may reduce time, but it can also
increase hosted-runner memory pressure and flake rate. Treat this as an experiment with evidence,
not a default assumption.

**Keep the current lane.** If measurement shows most time is unavoidable setup/compile duplication
or a few tests that must remain together, the right answer may be to keep one required unit lane and
only improve diagnostics or local profiling.

## Principle candidate

CI lanes should correspond to owned facts, not arbitrary chunks of runtime.

This principle already appears in `public-ci-feedback-loop-efficiency`: the public workflow is better
because it separates claims, licenses, build, tests, provenance, and secrets. The same principle may
apply inside the current `Unit tests` lane, but recognizing the principle is not the same as building
more structure. New lanes are warranted only where the current broad lane hides a real ownership or
feedback problem.

## Theorization notes - 2026-06-28

This section is deliberately pre-design. It records frames and options to consider before deciding
whether the remaining work should become a workflow split, a test-taxonomy change, a fixture policy,
or no CI change at all.

### Different ways to frame the problem

The narrow framing is **unit-test latency**: the required `Unit tests` check is the slowest public CI
fact, so reduce its wall-clock time. That is true, but it can bias the design toward sharding before
the test evidence is understood.

A better framing may be **test-lane semantics**: after public CI already has separate facts for
claims, licenses, build, provenance, and secrets, the broad unit lane should say what remaining
truth it owns. If the check means "all ordinary hosted JVM regression tests pass on Windows," then
local-only parser fixtures, process-spawning integration tests, model-gated tests, and platform
specific native checks need explicit homes rather than accidental inclusion or exclusion.

Another framing is **critical-path ownership**. The current PR wall clock is determined by the
slowest required lane, but the important failure mode is not just waiting fourteen minutes. It is
waiting fourteen minutes to learn a failure belongs to a parser fixture, app-services setter test,
or platform assumption. Under this framing, a split is worthwhile only where it makes failures more
owned, not merely where it makes a stopwatch look better.

A third framing is **hosted evidence versus local evidence**. Some tests prove real product facts but
are poor hosted-PR facts because their runtime depends on cold Tika/PDFBox initialization, OCR
availability, OS image load, or process-spawn timing. The decision is not binary "test or do not
test"; it is which environment and cadence produces honest evidence for that fact.

### Possible directions to keep open

**Keep one required hosted unit lane, improve attribution.** The least structural answer is to keep
`./gradlew.bat test` as the single required hosted unit fact and add better timing summaries,
slow-test reports, or Gradle/JUnit report surfacing. This avoids branch-protection churn and
duplicated setup, but it does not shorten the critical path.

**Split by Gradle module families.** A first real split could separate app/backend orchestration,
worker/indexing/extraction, and remaining support modules. This makes ownership more legible and
could reduce wall clock if the lanes are balanced. The risk is duplicated checkout/JDK/Node/Gradle
setup and repeated compilation across jobs; if setup dominates, this adds complexity without speed.

**Split by evidence kind rather than module.** Instead of `app-services` versus `worker-services`,
lanes could be named after facts: `hosted-jvm-regressions`, `windows-native-regressions`,
`worker-extraction-fixtures`, `frontend-unit`, or `contract-governance`. This fits the fact-lane
principle better, but may be harder to express cleanly with Gradle's existing module boundaries.

**Create an explicit worker-extraction/parser fixture signal.** Tika, PDF, Office, OCR, and VDU
fixtures are not ordinary cheap unit tests. They may deserve a named lane, perhaps manual,
scheduled, or required only once it is deterministic enough on standard hosted runners. This would
make the `StructuredExtractionIntegrationTest` and `VduEligibilityPdfFixturesTest` precedent
intentional rather than a pair of local-only exceptions.

**Introduce a test evidence taxonomy before changing CI.** The repo already uses tags like
`stress`, `experiment`, `ai`, and `systemTest`, plus coarse `CI` environment disables. A useful
intermediate move might be a documented taxonomy: hosted-required, hosted-advisory, local-fixture,
manual-parser, model-gated, stress, and system. The workflow can then follow the taxonomy instead
of tests self-classifying through scattered environment checks.

**Move some platform-neutral JVM work to Ubuntu.** This could reduce hosted Windows pressure and
queue time, but only if the selected tests truly do not exercise Windows path behavior, native
libraries, Tauri/NSIS assumptions, file-locking behavior, or PowerShell/batch command shape. A
mixed-platform test strategy should explain what Windows still proves.

**Use a two-level requiredness model.** Every PR might require fast deterministic facts and core JVM
tests, while parser fixtures or heavyweight integration evidence runs on schedule, manual dispatch,
or before release. This can be honest if branch protection and docs say exactly which facts are not
PR-required. It is dangerous if "advisory" silently becomes "ignored."

**Measure and optimize Gradle execution before sharding.** The slow lane may be dominated by
configuration, compilation, test JVM startup, or a few classes rather than test method runtime. If
so, a better direction may be Gradle build-cache hygiene, test task parallelism, fork settings, or
test report surfacing rather than job splitting.

**Add timing regression visibility.** A small artifact or summary that records lane duration,
slowest Gradle test tasks, and slowest classes would make future latency regressions visible. This
does not solve latency by itself, but it makes the next design pass less anecdotal.

### Hidden assumptions to challenge

- That the latest long pole is caused by test work, not compile/setup duplication.
- That a Windows-hosted pass is the only acceptable public evidence for all JVM modules.
- That a local-only parser fixture is an acceptable substitute for hosted parser evidence without a
  documented cadence.
- That `CI=true` is precise enough as a skip boundary. It says where the test is running, not what
  evidence tier the test belongs to.
- That a faster PR check is automatically a better contributor experience. A faster but
  under-scoped required set can create false confidence.
- That parser/PDF/OCR instability is a test-flakiness problem. It may be a real signal that the
  fixture substrate is too environment-sensitive for a generic hosted unit lane.
- That branch protection will be easy to configure later. Required check names become a public API
  of the repository once enforced.

### Risks for later design

- Over-sharding can produce many green checks while losing one simple "hosted unit regression"
  guarantee.
- Under-sharding keeps the current opaque long pole and hides ownership behind `Unit tests`.
- Platform splitting can miss Windows-only regressions unless every moved group has a stated reason
  not to need Windows.
- Parser fixture demotion can weaken confidence in document extraction unless replaced by a visible
  local/manual/scheduled signal.
- Path-aware skipping is especially risky in this repo because projection and governance checks often
  depend on non-obvious sources.
- Cache and parallelism tuning can improve average speed while worsening cold-run or fork-PR
  behavior if the workflow starts assuming cache warmth.

### Broader principle candidate

The broader recurring shape may be **evidence tiering with named ownership**:

> A public check should name the fact it proves, the environment required to prove it, the owner who
> can fix it, and whether the fact is required, advisory, local-only, or scheduled.

This extends the `public-ci-feedback-loop-efficiency` fact-lane principle from workflows into tests.
It would reject both extremes: one broad `Unit tests` bucket that hides parser and platform
semantics, and a fragmented CI where lanes are merely runtime shards with no stable meaning.

### Evidence still needed for implementation planning

- What exact fact should `Unit tests` continue to prove if no further split happens?
- Which current tests are disabled under `CI`, and are their replacement signals documented?
- Which Gradle tasks or test classes dominate hosted runtime after the PDF fixture exclusion?
- Is the critical-path cost mostly execution time, setup/compile duplication, or Windows runner
  variability?
- Which checks should eventually be branch-protection required, and which should remain advisory or
  local?
- Is a dedicated parser/extraction lane valuable now, or only after the fixture substrate becomes
  deterministic enough for hosted runners?

## Existing design substrate - 2026-06-28

The repo already has the right outer seam: public CI is organized as named fact lanes, not as one
catch-all workflow. `.github/workflows/ci.yml` currently exposes separate public facts for claims,
license/notices, no-model build, unit tests, secrets, DCO, and CLA. `docs/reference/contributing/agent-guide.md`
also treats those check names as public signal names that should stay stable once branch protection
starts depending on them.

There is already a workflow-level signal registry in `scripts/ci/workflow-signal-policy.v1.json`
and `scripts/ci/workflow-signal-health.mjs`. That registry owns workflow facts: expected triggers,
owners, staleness, failure defaults, blocking status, and advisory status. It is a useful model, but
it should not be stretched into a per-test taxonomy unless the present problem actually needs that
much structure.

The testing docs already describe tiered evidence: ordinary unit tests, integration tests,
guardrails/contracts, system tests, UI checks, opt-in stress/soak tests, and opt-in AI/model tests.
The code also has an existing policy-enforced precedent for one special tier:
`scripts/ci/verify-stress-suite-policy.mjs` requires `@Tag("stress")` tests to be covered by
`scripts/ci/stress-suite-policy.v1.json`.

The weak spot is not total absence of structure. The weak spot is that some important tests use
mechanism-shaped boundaries such as `CI=true` disables instead of an owned evidence tier. The PDF
and parser fixture precedent in worker-services is defensible as a local-hostility workaround, but
the replacement signal and cadence are not yet declared as clearly as the workflow fact lanes are.

## Design direction - 2026-06-28

The long-term design should keep the existing public CI fact-lane model and extend its discipline
inward to tests only as far as the current problem requires.

The required `Unit tests` check should continue to mean one stable hosted fact: ordinary JVM
regression tests pass on the public, standard-runner environment selected for the repo. It should
not silently mean "every test-shaped thing the repo owns," because parser fixtures, OCR/PDF/Office
evidence, model-gated checks, system tests, stress tests, and platform-native checks do not all have
the same environment requirements or requiredness.

The first design move is therefore classification, not sharding. Slow or skipped tests should be
assigned to evidence tiers with names that explain their purpose and required environment. The
minimum useful tiers for this problem are:

- hosted-required JVM regression evidence;
- Windows-specific hosted regression evidence;
- local fixture evidence for parser/PDF/OCR/Office behavior that is not yet reliable on hosted PR
  runners;
- scheduled or manually dispatched parser/extraction evidence, if local-only coverage is too weak
  for a public repo;
- already-opt-in stress, system, soak, and AI/model evidence.

The current `Unit tests` lane should stay a single required branch-protection candidate until
measurement proves that a split preserves the same fact or creates a clearer required fact. A split
is justified when it follows an evidence boundary, such as "Windows native regressions" or "worker
extraction fixtures," not when it merely divides runtime into arbitrary module chunks. Module-family
splits can still be the implementation mechanism, but the public check name should describe the fact
being proven.

The existing workflow signal registry should remain workflow-scoped for now. If the next pass finds
many `CI=true` skips, unowned `@Tag("experiment")` / `@Tag("evidence")` groups, or parser fixtures
whose replacement evidence is unclear, a small sibling test-evidence policy becomes warranted. Its
shape should follow the existing registry and stress-policy precedent: name the evidence tier,
owner, environment, command or cadence, required/advisory/local status, and what public fact it
replaces or supplements. That is a candidate structure, not a requirement for this tempdoc's current
scope.

Timing attribution is part of the design, not an optimization afterthought. Before introducing more
lanes, the repo should surface slow Gradle tasks/classes or hosted timing summaries so future
splits are evidence-led. This matches the performance-attribution principle in tempdocs `647` and
`648`: optimize only after the dominant cost and ownership boundary are visible.

The design should avoid branch-protection churn. Check names that may become required should be
stable, fact-shaped, and conservative. Parser or extraction evidence should not become required
until it is deterministic enough on standard hosted runners or has a clearly documented manual or
scheduled substitute.

## Reach judgment - 2026-06-28

This design is an instance of a broader principle: **named evidence lanes**.

A check, test tier, or evidence command should name the fact it proves, the environment required to
prove it, who owns failures, and whether the fact is required, advisory, scheduled, or local-only.
The principle already exists at the workflow level in the public CI fact lanes, and in a narrower
form in the stress-suite policy. The current tempdoc does not need to build a general evidence
registry yet; it only needs to recognize that `Unit tests` is too broad to carry every kind of test
evidence without declared ownership.

Candidate future scope:

- public CI workflow checks and branch-protection names;
- local-only or hosted-skipped parser/PDF/OCR/Office fixtures;
- `@Tag("experiment")`, `@Tag("evidence")`, stress, system, soak, and AI/model test groups;
- performance and benchmark evidence where tempdocs `647` and `648` already argue for attribution
  before optimization;
- truthfulness/projection checks like tempdoc `650`, where the public surface should report the
  fact actually proven rather than a convenient internal mechanism.

Known current violations or gaps:

- scattered `CI=true` disables describe where a test runs, not the evidence tier the test belongs
  to;
- local-only parser fixtures do not yet declare their replacement hosted, manual, or scheduled
  signal;
- unregistered non-stress tags do not have the same ownership discipline as `@Tag("stress")`;
- the broad `Unit tests` check lacks enough timing attribution to make future sharding decisions
  non-anecdotal.

The broader principle should be recorded now, but generalized machinery should wait until the next
implementation/design pass proves that documentation and targeted policy are insufficient.

## Internet research pass - 2026-06-28

This pass checked whether current global platform/tooling changes should alter the design. It used
primary sources only:

- GitHub hosted runner reference:
  https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- GitHub Actions billing reference:
  https://docs.github.com/en/billing/concepts/product-billing/github-actions
- GitHub runner-images repository:
  https://github.com/actions/runner-images
- GitHub Actions Windows image migration changelog:
  https://github.blog/changelog/2026-05-14-github-actions-upcoming-image-migrations/
- GitHub Actions parallel steps changelog:
  https://github.blog/changelog/2026-06-25-actions-steps-can-now-be-run-in-parallel/
- GitHub Actions workflow syntax:
  https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
- Gradle Java testing and test reporting docs:
  https://docs.gradle.org/current/userguide/java_testing.html
- Gradle test report aggregation plugin:
  https://docs.gradle.org/current/userguide/test_report_aggregation_plugin.html
- Gradle build performance docs:
  https://docs.gradle.org/current/userguide/performance.html
- JUnit environment-variable condition docs:
  https://docs.junit.org/5.10.5/user-guide/

Findings:

- Standard GitHub-hosted runners remain the right public-repo boundary. GitHub documents standard
  hosted runner usage as free for public repositories, while larger runners are a different managed
  runner class. This supports keeping paid/larger runners outside this tempdoc's design.
- Runner-image drift is active and relevant. GitHub's May 2026 changelog says `windows-latest` and
  `windows-2025` migrate to the Visual Studio 2026 image by default in June 2026. Since the repo's
  public CI uses `windows-latest`, the design should treat "Windows hosted evidence" as a moving
  image fact unless a later implementation pins a label such as `windows-2022` or records the image
  version in timing/evidence output.
- Native parallel steps are newly available in GitHub Actions. They may help overlap independent
  setup/reporting/telemetry work inside one job while preserving separate logs, but they do not
  replace semantic test lanes. They are an implementation option only after measurement shows
  within-job sequencing, rather than test execution or compile work, is part of the critical path.
- Gradle already has first-class support for test result XML, HTML reports, cross-subproject test
  report aggregation, and `maxParallelForks`. This strengthens the design preference for timing and
  report surfacing before workflow sharding.
- JUnit's environment-variable conditions are official and repeatable, so current `CI=true` disables
  are not technically suspicious. The design concern remains semantic: an environment condition says
  where a test is skipped, not what evidence tier replaces it.

Design impact:

- Keep the design. The internet pass did not justify replacing the repo's existing fact-lane model.
- Add runner-image identity to the later implementation evidence list; hosted Windows timings should
  record whether they ran on `windows-latest`, `windows-2025`, `windows-2025-vs2026`, or another
  pinned image.
- Treat native parallel steps as a possible tactical implementation tool, not a design principle.
  They should not become a workaround for unnamed evidence lanes.
- Prefer Gradle/JUnit-native reporting and classification before inventing a custom test-evidence
  framework.

## Confidence-building pass - 2026-06-28

This pass deliberately did not implement the tempdoc's feature work. It reduced surprises before a
future implementation by checking the current repo substrate, hosted CI evidence, and local test
attribution.

### Evidence gathered

- `node scripts/ci/workflow-signal-health.mjs --repo eliasjustus/justsearch --md`
- `gh run list --repo eliasjustus/justsearch --workflow CI --limit 8 --json ...`
- `gh pr checks 9 --repo eliasjustus/justsearch`
- `gh api repos/eliasjustus/justsearch/actions/cache/usage`
- `gh api repos/eliasjustus/justsearch/branches/main/protection`
- `gh api repos/eliasjustus/justsearch/rulesets`
- `gh run view 28307064634 --repo eliasjustus/justsearch --json jobs`
- `gh run view 28307064634 --repo eliasjustus/justsearch --job ... --log`
- `node scripts/ci/verify-stress-suite-policy.mjs --json`
- `$env:CI='true'; ./gradlew.bat test --rerun-tasks --console=plain --profile`
- local JUnit XML and Gradle profile inspection under `build/reports/profile/`

### Hosted CI findings

The current public CI signal is green and current. `workflow-signal-health` reports `CI` as
`success (pull_request, 0d)` and `CLA Assistant` as `success (pull_request_target, 0d)`.

Latest PR check times from run `28307064634`:

| Check | Result | Time |
| --- | --- | ---: |
| `DCO` | pass | 9s |
| `Secret scan` | pass | 9s |
| `Public claims` | pass | 16s |
| `License and notices` | pass | 4m42s |
| `Build (no model blobs)` | pass | 5m33s |
| `Unit tests` | pass | 14m7s |
| `cla-assistant` | pass | 8s |

The `Unit tests` job's own command step ran from `01:02:18Z` to `01:15:43Z`, about 13m25s.
Checkout/setup/post steps are therefore not the main observed long pole in the latest green run.
This reduces confidence in any implementation that starts with only setup overlap or native
parallel steps.

Runner identity is no longer abstract: the latest Windows jobs ran on image
`windows-2025-vs2026`, version `20260622.153.1`, with runner version `2.335.1`. The Ubuntu
public-claims lane ran on `ubuntu-24.04`, version `20260622.220.1`. Runner-image identity should be
captured in any future timing evidence because `windows-latest` is a moving target.

Actions cache usage remains small: `233,218,787` bytes across seven active caches. Cache capacity is
not currently the limiting factor. Branch protection still has no required status checks, and repo
rulesets are empty. That means there is no required-check migration to preserve yet, but check names
chosen by the next implementation will likely become public API once protection is enabled.

### Local attribution findings

The CI-mode local run passed:

`$env:CI='true'; ./gradlew.bat test --rerun-tasks --console=plain --profile`

Gradle profile summary:

| Measure | Time |
| --- | ---: |
| Total wall-clock build time | 3m54.69s |
| Startup | 0.541s |
| Configuring projects | 1.938s |
| Summed task execution | 11m39.42s |

The summed task time being much larger than wall-clock confirms that local Gradle parallelism is
already doing useful work. It also means mechanical job splitting can duplicate compile/setup work
and should not be assumed to reduce hosted wall-clock without measurement.

Largest local project totals in the Gradle profile:

| Project | Profile total |
| --- | ---: |
| `:modules:ui` | 1m21.56s |
| `:modules:app-services` | 57.597s |
| `:modules:adapters-lucene` | 53.958s |
| `:modules:worker-services` | 45.372s |
| `:modules:api-contract-projection-java` | 31.251s |
| `:modules:indexer-worker` | 26.429s |

JUnit XML test-class timing grouped by module:

| Module | Test time sum | Tests | Skipped |
| --- | ---: | ---: | ---: |
| `modules\worker-services` | 32.089s | 747 | 9 |
| `modules\app-services` | 26.955s | 1628 | 3 |
| `modules\indexer-worker` | 24.343s | 273 | 12 |
| `modules\adapters-lucene` | 20.474s | 497 | 0 |
| `modules\ui` | 14.047s | 442 | 0 |

Slowest local test-class reports were still single-digit seconds:

- `BatchUpdateIntegrationTest`: 8.502s
- `Filter normalization fallback`: 8.009s
- `GrpcIngestServiceTest`: 5.943s
- `OnlineModeOpsTest`: 5.411s
- `LifecycleContractTest`: 5.004s
- `WholeProgramDeadCodeTest`: 4.471s

This is useful for ownership but insufficient for hosted optimization. The local suite does not show
a single class or module that explains a 14-minute hosted command step by itself.

### Test-classification substrate

The strict annotation inventory currently includes:

| Tag | Count | Current ownership shape |
| --- | ---: | --- |
| `ai` | 8 | system-tests source sets and opt-in flags |
| `systemTest` | 3 | system-tests source set and opt-in flag |
| `stress` | 2 | covered by `stress-suite-policy.v1.json` |
| `experiment` | 3 | present, not policy-owned like stress |
| `evidence` | 1 | present, not policy-owned like stress |
| `vdu` | 1 | system-tests integration tag |

`node scripts/ci/verify-stress-suite-policy.mjs --json` passes and discovers the two stress-tagged
modules: `modules/adapters-lucene` and `modules/ort-common`. This remains the strongest existing
precedent for a small owned test-evidence policy.

There are eight current `@DisabledIfEnvironmentVariable(named = "CI", matches = "true")` annotation
sites:

- two worker-services `src/test` parser/PDF fixture sites;
- one UI `src/integrationTest` status-contract site;
- five app-services `src/integrationTest` worker/server integration sites.

That confirms the design concern. The repo uses official JUnit environment conditions, but the
annotation itself says only "not in CI." It does not name the evidence tier, replacement signal, or
cadence.

Existing Gradle tuning also matters. `worker-services` and `indexer-worker` already set
`maxParallelForks = 2`, while app-services and UI integration tests use `maxParallelForks = 1` where
global state or process-level isolation requires it. The next implementation should not assume that
unreviewed fork increases are safe.

### Confidence changes

Increased confidence:

- The design should start with attribution/report surfacing, not immediate sharding.
- `Unit tests` is genuinely the current hosted long pole; the latest command step itself dominates
  the job.
- Runner-image identity is observable from logs and can be captured without new infrastructure.
- The stress-suite policy is a usable precedent if local-only/evidence tags need ownership later.
- The current local test suite is green under `CI=true`, so there is no obvious local correctness
  blocker before implementation planning.

Reduced confidence:

- Local timings are not representative enough to choose a split. They are useful for ownership, not
  sufficient for hosted critical-path claims.
- A module-family split may be semantically useful but is not yet proven to reduce wall-clock time;
  compile/setup duplication could eat the gain.
- The repo lacks hosted per-module or slow-class artifacts from the public CI run. Without those,
  the next implementation would still be partly anecdotal.
- `CI=true` skips are more widespread than only the parser fixture story. Integration-test skips
  also need classification if this becomes a general evidence-tier pass.
- The confidence run itself dirtied tracked generated JSON files through line-ending/index effects
  without content diff. That is an operational surprise for future automated profiling runs; they
  should either avoid mutating generated artifacts or include a clean-tree check before/after.

### Implementation-readiness judgment

The remaining work should not begin with a workflow split. A safer implementation sequence is:

1. add or surface hosted timing evidence from existing Gradle/JUnit outputs;
2. record runner image identity with each timing sample;
3. classify current `CI=true` skips into named evidence tiers in docs or a small policy;
4. only then decide whether a named required/advisory lane is warranted.

Confidence in implementing the remaining work without major design surprise: **7/10**.

The concept is solid and now grounded in current hosted and local evidence. The missing three points
are hosted per-module attribution, a replacement-evidence decision for local-only skips, and the
risk that a lane split improves ownership but not wall-clock time.

## Done shape for this tempdoc

The first implementation slice is complete. It deliberately kept the `Unit tests` check name and did
not shard the workflow, change branch protection, change runner class, or change cache strategy.

Implemented changes:

- added `scripts/ci/report-unit-test-attribution.mjs`, which reads existing Gradle/JUnit XML and
  reports module totals, skipped/failure/error counts, slow suites, and runner identity as JSON and
  Markdown;
- added reporter fixture tests in `scripts/ci/test-report-unit-test-attribution.mjs`;
- added `scripts/ci/test-evidence-policy.v1.json` plus
  `scripts/ci/verify-test-evidence-policy.mjs` to own current `CI=true` Java test skips and
  non-stress JUnit tags;
- added policy verifier tests in `scripts/ci/test-verify-test-evidence-policy.mjs`;
- extended only the existing `Unit tests` job in `.github/workflows/ci.yml` with a Gradle step id,
  an `if: always()` attribution-report step, and an `if: always()` artifact upload for the report
  and JUnit XML;
- added the test-evidence policy guard to the existing public-claims fact lane;
- documented the evidence-tier model in `docs/explanation/09-testing-strategy.md` and the
  attribution/policy workflow in `docs/reference/contributing/agent-guide.md`.

Validation passed locally:

- `node --check scripts/ci/report-unit-test-attribution.mjs`
- `node scripts/ci/test-report-unit-test-attribution.mjs`
- `node --check scripts/ci/verify-test-evidence-policy.mjs`
- `node scripts/ci/test-verify-test-evidence-policy.mjs`
- `node scripts/ci/verify-test-evidence-policy.mjs --json`
- `node scripts/ci/verify-test-evidence-policy.mjs`
- `node scripts/ci/verify-stress-suite-policy.mjs --json`
- `node scripts/ci/check-workflow-triggers.mjs`
- `node scripts/ci/test-check-workflow-triggers.mjs`
- `node scripts/ci/check-tempdoc-numbers.mjs`
- `node scripts/ci/report-unit-test-attribution.mjs --runner-label local --top 10 --out-json tmp/unit-test-attribution.json --out-md tmp/unit-test-attribution.md`
- `$env:CI='true'; ./gradlew.bat test --console=plain`

Canonical-doc maintenance also passed:

- `node scripts/docs/llmstxt-generate.mjs`
- `node scripts/docs/skills-sync.mjs`
- `node scripts/docs/llmstxt-generate.mjs --check`
- `node scripts/docs/skills-sync.mjs --check`
- `node scripts/docs/verify-canonical-doc-links.mjs`
- `node scripts/architecture/module-deps.mjs --check-canonical`
- `npx markdownlint "docs/explanation/**/*.md" "docs/reference/**/*.md" "docs/how-to/**/*.md" "docs/decisions/**/*.md"`

Known unrelated validation issue:

- `node scripts/docs/verify-runtime-config-matrix.mjs` currently throws
  `TypeError: Cannot read properties of undefined (reading 'filter')` in the verifier script. The
  changed testing docs do not touch the runtime config matrix, and the generator/check outputs did
  not produce tracked doc diffs. This should be handled separately from the CI test-evidence slice.

Remote validation still remains after commit/push:

- inspect the next `Unit tests` job summary for the attribution Markdown;
- confirm the `unit-test-attribution` artifact contains JSON, Markdown, and Gradle JUnit XML;
- confirm `Unit tests` still passes or fails according to Gradle rather than the reporter;
- confirm check names remain unchanged.

Hosted validation from run `28317561618` passed for commit `2375236`:

- `Public claims`: 19s, including the new test-evidence policy guard;
- `Build (no model blobs)`: 7m15s;
- `License and notices`: 5m1s;
- `Unit tests`: 14m2s, with the Gradle test step from `09:15:01Z` to `09:28:02Z`;
- `DCO`, `Secret scan`, and `cla-assistant`: green.

The uploaded attribution artifact exists and contains JSON, Markdown, and Gradle JUnit XML. The
hosted report recorded runner label `windows-latest`, OS `Windows`, image `win25-vs2026`, and image
version `20260622.153.1`.

Hosted attribution findings:

- JUnit XML summed suite time was about 7m0s, while the Gradle test command took about 12m51s.
  Therefore the remaining wall-clock problem is not explained by JUnit class runtime alone.
- The slowest hosted module groups were `app-services` (62.296s), `worker-services` (59.677s),
  `indexer-worker` (50.253s), `adapters-lucene` (44.987s), `app-launcher` (38.163s), and
  `app-inference` (32.77s).
- The hosted log showed `:modules:ui:installWebDependencies`, `:modules:ui:buildWeb`, and
  `:modules:ui:copyWebResources` running inside the `Unit tests` lane even though the separate
  `Build (no model blobs)` lane already owns the web-bundle fact.
- That made the next safe reduction clear: keep the check name `Unit tests`, keep one required hosted
  JVM regression lane, but run it with the existing `-PskipWebBuild=true` Gradle property and remove
  Node setup from that job.

Second implementation slice:

- changed the `Unit tests` workflow command to `./gradlew.bat test -PskipWebBuild=true`;
- removed `actions/setup-node` from the `Unit tests` job because this lane no longer owns web asset
  building;
- kept `Build (no model blobs)` unchanged with `./gradlew.bat assemble -PskipWebBuild=false`, so
  public CI still proves the web bundle on hosted Windows.

Local validation for the second slice:

- `$env:CI='true'; ./gradlew.bat test -PskipWebBuild=true --console=plain`

Hosted validation of the second slice from run `28317985433` was green but did not reduce the
critical path:

- `Unit tests`: 14m16s total, with the Gradle step from `09:32:34Z` to `09:46:17Z`;
- JUnit XML summed suite time was about 7m11s;
- the report showed no failure or artifact regression, but the wall-clock result was effectively the
  same as the previous 14m2s baseline.

That result is important: removing the web-bundle fact from `Unit tests` clarified ownership, but it
did not solve the wall-clock goal. The remaining implementation now has enough evidence to split the
unit-test signal by owned JVM surface rather than by arbitrary runtime chunks.

Third implementation slice:

- replace the single `Unit tests` job with a matrix of three owned shards:
  `Unit tests (app-ui)`, `Unit tests (search-worker)`, and `Unit tests (platform-contracts)`;
- keep all shards on standard `windows-latest` hosted runners;
- keep `-PskipWebBuild=true` in each unit-test shard;
- keep the Gradle test result as the source of pass/fail;
- publish a separate attribution artifact for each shard:
  `unit-test-attribution-app-ui`, `unit-test-attribution-search-worker`, and
  `unit-test-attribution-platform-contracts`;
- keep `Build (no model blobs)` unchanged as the hosted Windows web-bundle and no-model assembly
  fact.

The split is evidence-shaped:

- `app-ui` owns application service, API, agent, launcher, observability, utility, and UI JVM tests;
- `search-worker` owns indexing, Lucene, worker, IPC, core, and configuration JVM tests;
- `platform-contracts` owns inference, native/platform support, API contract projection, stress
  substrate modules, system-test default tests, and governance-style test modules.

Hosted validation of the third slice from run `28318433836` was green after rerunning one unrelated
infrastructure failure:

- the first `License and notices` attempt failed before Gradle started because the Gradle wrapper
  download hit `java.net.SocketException: An established connection was aborted by the software in
  your host machine`;
- the same job passed on `gh run rerun --failed`, including `checkLicense`, NOTICE projection, and
  NOTICE sync;
- `Build (no model blobs)` passed in 6m55s, with the assemble step taking 6m17s;
- `Public claims`, `DCO`, `Secret scan`, and `cla-assistant` passed.

The three sharded unit checks all passed and uploaded separate attribution artifacts:

| Check | Total job time | Gradle test step | JUnit suites | JUnit tests | Skipped | JUnit suite time | Slowest module |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `Unit tests (app-ui)` | 10m22s | 8m52s | 536 | 3133 | 4 | 158.901s | `modules/app-services` 46.479s |
| `Unit tests (search-worker)` | 8m31s | 8m02s | 402 | 2120 | 22 | 180.547s | `modules/worker-services` 59.103s |
| `Unit tests (platform-contracts)` | 7m59s | 7m30s | 160 | 878 | 1 | 97.618s | `modules/app-inference` 40.789s |

This satisfies the tempdoc's main implementation goal: the old single `Unit tests` job had a hosted
Gradle step around 13-14 minutes, while the public unit evidence now reaches its first failing shard
in about 7.5-9 minutes on standard hosted Windows runners. The split is still evidence-owned rather
than arbitrary: each check name identifies the module surface it proves, each artifact records runner
identity and JUnit attribution, and the separate build lane remains responsible for web bundle
assembly.

Remaining follow-up:

- audit which tests are Windows-specific versus platform-neutral;
- consider moving platform-neutral shards to Ubuntu only after those assumptions are explicit;
- watch whether `Build (no model blobs)` becomes the next long pole now that unit tests are no longer
  the only slow public fact lane;
- treat the three current split checks as the required unit-test branch-protection facts:
  `Unit tests (app-ui)`, `Unit tests (search-worker)`, and `Unit tests (platform-contracts)`.

The branch-protection decision deliberately does not change the test-lane design. The next
optimization pass should still audit Windows-specific assumptions before moving shards to Ubuntu,
and should add a worker-extraction/parser lane only after attribution evidence shows that hosted
parser evidence needs a deterministic, named public signal.

Contributor provenance was simplified after this slice: `cla-assistant` is the required
contributor-policy check, and DCO is no longer part of the protected public CI set. This does not
change the test-lane design or the three required unit-test shard names.

## Post-implementation research pass - 2026-06-28

This pass asks what the implemented design makes possible now that public unit evidence is sharded,
owned, and attributed. It is deliberately exploratory. The goal is not to start the next
implementation, but to identify valuable directions and avoid confusing polish, speed work, and new
product features.

### How to research this from here

Use three evidence sources in this order:

1. Current repo facts: PR check timings, `ci.yml`, attribution artifacts, branch-protection policy,
   `test-evidence-policy.v1.json`, and current docs.
2. Primary platform docs: GitHub Actions workflow summaries, annotations, cache behavior, required
   checks, matrix jobs, artifact handling, and Gradle's CI/build-performance docs.
3. Experiments only after a hypothesis is clear: run one hosted/manual CI experiment per idea and
   compare against the current green baseline, rather than mixing several CI changes in one push.

Primary-source notes used for this round:

- GitHub job summaries are meant to show important run information without forcing readers into raw
  logs: [Workflow commands - job summaries](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#adding-a-job-summary).
- GitHub warnings/notices can create annotations tied to files and lines:
  [Workflow commands - warning messages](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#setting-a-warning-message).
- Required checks interact badly with workflow-level path filtering: a workflow skipped by path,
  branch, or commit-message filtering can leave required checks pending, while job-level conditionals
  report success when skipped:
  [Troubleshooting required status checks](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/collaborating-on-repositories-with-code-quality-features/troubleshooting-required-status-checks#handling-skipped-but-required-checks).
- GitHub matrix jobs are the right mechanism for owned job variants:
  [Running variations of jobs](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/run-job-variations).
- Gradle's performance guidance starts with inspection, then one change, then inspection again:
  [Improve Gradle build performance](https://docs.gradle.org/current/userguide/performance.html#inspect_your_build).
- Gradle test-performance guidance points to slow-test attribution, test parallelism, forks, and
  report cost, with the caveat that parallel tests must be isolated:
  [Gradle test execution performance](https://docs.gradle.org/current/userguide/performance.html#optimize_test_execution).
- `gradle/actions/setup-gradle` can validate the wrapper, cache Gradle user home, add job summaries,
  and recommends read-only cache use on non-primary matrix legs:
  [Gradle on GitHub Actions](https://docs.gradle.org/current/userguide/github-actions.html).
- Gradle can aggregate test results across multiple projects into one HTML report:
  [Test Report Aggregation Plugin](https://docs.gradle.org/current/userguide/test_report_aggregation_plugin.html).

### Current baseline after implementation

The current PR surface already proves that the design is useful:

- required public CI facts now include `Public claims`, `License and notices`, `Build (no model
  blobs)`, `Unit tests (app-ui)`, `Unit tests (search-worker)`, `Unit tests (platform-contracts)`,
  `Secret scan`, and `cla-assistant`;
- the latest observed check times were roughly: Build 7m22s, License 4m55s, `app-ui` 9m17s,
  `platform-contracts` 10m46s, and `search-worker` 8m27s;
- the slowest remaining public path is no longer one opaque 14-minute unit bucket, but the overall
  critical path is still close to ten or eleven minutes;
- the test attribution artifacts are machine-readable but not yet turned into a durable trend,
  policy, or contributor-facing explanation.

That means the most valuable next work is probably not another immediate split. The next value is
making the evidence easier to consume, easier to compare over time, and safer to use for later speed
experiments.

### Polishing the implemented code

**Make the report more actionable, not larger.** The current attribution report lists modules and
slow suites. A useful polish would add a short "What changed?" or "Where to look first" block:
slowest module, largest skipped count, runner image, and whether this shard exceeded a soft budget.
This should stay diagnostic; it must not become the pass/fail source.

**Emit GitHub notices only for high-signal cases.** The reporter could emit a small number of
`::notice` or `::warning` annotations for slow suites or unexpected skip spikes. The risk is PR
noise. A conservative design would emit annotations only above a configured budget and only for
owned files that a contributor can act on.

**Add a budget file later, not now.** A future `unit-test-budgets.v1.json` could define soft budgets
per shard or module. It should start advisory and trend-oriented. Making budgets blocking too early
would turn normal hosted-runner variance into CI noise.

**Preserve the small-script pattern.** The current reporter and verifier match the repo's
`scripts/ci/*.mjs` style. Polishing should extend them in-place rather than introduce a CI framework.

### Simplifying the CI shape

**Do not use workflow-level path filters for required checks.** A docs-only PR currently still runs
the public checks. That costs time, but it avoids required checks becoming permanently pending. If
path-aware skipping is ever added, it should happen inside the workflow with job-level conditions or
a stable required summary job, not by skipping the whole required workflow.

**Consider one required aggregate status only if branch protection becomes noisy.** Matrix checks are
clear, but three required unit names increase branch-protection surface area. A future aggregate job
could depend on all unit shards and become the single required test check. The downside is slower
first-failure visibility and another indirection. For now, the explicit shard checks are clearer.

**Avoid adding a fourth unit shard until the slowest lane is stable.** `platform-contracts` is
currently the slowest observed shard, but not by enough to justify a new required check without more
samples. More shards mean more runner startup, cache restore/save, and branch-protection names.

### Extending evidence UX

**Create a CI evidence digest.** A script such as `scripts/ci/report-ci-evidence-digest.mjs` could
combine `gh run view`, workflow job timings, unit attribution JSON, and workflow-signal policy into
one Markdown digest. It would answer: which required fact is slowest, which shard owns failures, what
runner image ran, and what artifacts exist. This is likely the highest-value documentation/UX layer.

**Create a trend view across recent runs.** A non-blocking reporter could read the latest N CI runs
and show median/max duration per required check. That would separate one-off hosted variance from
real regressions. It could start as a local command and later become a manual `workflow_dispatch`
diagnostic lane.

**Make artifact browsing easier.** The current artifacts are complete but raw. A top-level Markdown
or HTML index could link each shard's JSON, Markdown, and JUnit XML. If artifact size or upload time
becomes visible, set short retention for diagnostic artifacts rather than reducing the evidence
itself.

**Map changed files to expected checks.** A local helper could inspect changed paths and print which
public facts are likely to matter: `app-ui`, `search-worker`, `platform-contracts`, Build, docs, or
license. This would help contributors predict CI without changing branch protection.

### Speed directions worth testing later

**Build lane attribution is now the next obvious blind spot.** `Build (no model blobs)` is close to
the shard timings and owns web-bundle evidence. Before optimizing it, add the same kind of timing
ownership: Gradle task summary, web-build timing, runner image, and whether `assemble` is proving
more than the public fact requires.

**Try `gradle/actions/setup-gradle` in one branch experiment.** The official Gradle action can cache
Gradle user home, validate the wrapper, and add Gradle summaries. It may simplify custom cache
behavior compared with relying only on `actions/setup-java` caching. Because it writes caches mainly
from default-branch pushes and recommends read-only cache use for non-primary matrix legs, it could
reduce matrix cache churn. This needs a hosted A/B run before adoption.

**Do not assume configuration cache helps PR shards.** Gradle's configuration cache can skip
configuration work on repeated matching invocations, but CI runners are cold and matrix task sets
differ. It is worth investigating only after measuring configuration time in the hosted shards and
checking compatibility. It should not be enabled globally as a blind speed fix.

**Audit Ubuntu eligibility as an advisory matrix first.** `search-worker` may contain the most
platform-neutral work, but it still touches file watching, paths, Lucene, and worker behavior. A
safe experiment is an advisory Ubuntu duplicate, not replacing the required Windows shard. Only move
a required fact after a Windows-specific assumption audit.

**Parser/extraction should become a lane only when it has a product question.** The current
local-parser-fixture tier is honest. A hosted parser lane would be valuable if the repo wants public
proof of PDF/OCR/Office behavior before merge or before release. Without that product requirement,
it is just another slow lane.

### Possible new product or developer features

**A "CI facts" page in docs.** Turn the evidence-lane model into a compact contributor-facing page:
what each required check proves, what it does not prove, how to reproduce it locally, and where to
find artifacts. This is a documentation UX improvement, not a runtime feature.

**A PR failure triage command.** A script could take a run id and print the shortest diagnosis:
failed check, owning surface, relevant local command, artifact path, and likely owner. This would
turn the fact-lane design into daily agent/developer leverage.

**Evidence policy as a review checklist.** The current policy enforces presence. Later it could also
render a human-readable table: local-only evidence, replacement command, cadence, and owner. That
would make skipped evidence visible during reviews without adding CI time.

**A lightweight public health badge group.** The README could eventually show the public fact lanes
as stable public signals, but this should wait until check names and branch protection settle. Badges
are user-facing trust signals and should not change during experimentation.

### Ideas to defer deliberately

- A full dashboard app for CI evidence. The repo does not need a UI surface until the command-line
  digest proves the information is valuable.
- Paid/larger runners or self-hosted caches. They violate the public-free design boundary.
- Required path-based skipping. It is easy to make branch protection confusing or pending.
- Blocking performance budgets. Start advisory; hosted-runner variance is real.
- A generalized evidence framework. The current policy and reporter are enough unless more evidence
  tiers start appearing.

### Best next research rounds

1. **Evidence UX round:** design a `report-ci-evidence-digest` command and decide what a contributor
   should see after a failed or slow PR run.
2. **Build attribution round:** give `Build (no model blobs)` the same fact ownership and timing
   visibility that unit shards now have.
3. **Gradle setup/cache experiment:** compare current `setup-java` cache behavior with
   `gradle/actions/setup-gradle`, including matrix cache-write policy.
4. **Platform audit round:** classify each shard's Windows assumptions before any Ubuntu-required
   migration.
5. **Policy lifecycle round:** decide whether evidence policy entries need review cadence,
   advisory/blocking status, or rendered documentation.

## Coordination boundary with 651 - 2026-06-28

The post-implementation research above intentionally explored what the sharded unit-test evidence
makes possible. After comparing it with the adjacent active tempdoc `651-public-ci-feedback-loop-efficiency`,
the long-term ownership boundary should be explicit so the two notes do not compete.

This split is accepted by both sides: 652 remains the unit-test and test-evidence owner, while 651
may take over cross-CI presentation work that composes 652's outputs into a broader public evidence
view.

Tempdoc `652-public-ci-unit-test-latency` should keep owning:

- the three unit-test shard design and any future reshaping of those shard boundaries;
- the test-evidence policy for `CI=true` skips and declared test tags;
- parser/PDF/OCR/Office fixture tiering, including whether hosted parser evidence ever needs its
  own named lane;
- future unit-test budgets, skip budgets, or slow-suite warnings when they are scoped to unit-test
  shards;
- Windows-versus-Ubuntu assumptions for unit-test evidence, including advisory platform experiments.

Tempdoc `651-public-ci-feedback-loop-efficiency` should own the broader CI presentation layer:

- a top-level CI evidence digest that composes all public facts;
- run timing and trend reporting across checks;
- build-lane attribution for `Build (no model blobs)`;
- the contributor-facing answer to "what failed, why, and how do I reproduce it locally?";
- composing 652's unit-test artifacts into one public CI view.

This means the evidence-UX, trend, artifact-index, changed-file mapping, and build-attribution ideas
in the research section above should be treated as inputs or handoff notes for `651`, not as
remaining 652 implementation work. The remaining 652 work is narrower: keep the unit-test signal
honest, owned, platform-explicit, and budgetable without turning it into the repo-wide CI dashboard.

## Nearby work interference scan - 2026-06-28

This tempdoc's filename number is `652`, so the relevant numeric window is `632` through `672`. The
recency cutoff for this scan was the last five hours from 2026-06-28T22:14+02:00. In the current
checkout, the recent in-range tempdocs were `634-go-public-cutover-transition`,
`651-public-ci-feedback-loop-efficiency`, `652-public-ci-unit-test-latency`, and
`653-public-main-history-hygiene`. The active in-range worktree was
`.claude/worktrees/public-main-history-policy`, associated with tempdoc `653`.

Findings:

- `634-go-public-cutover-transition` is upstream historical context. It records the public cutover,
  the public hosted CI fact-lane premise, and the old private repo as archive-only. It should not
  own new unit-test work. Any old references to DCO, self-hosted/GPU lanes, or cutover-time CI shape
  must be treated as context and checked against the live workflow and ADR-0044 before use.
- `651-public-ci-feedback-loop-efficiency` is the active neighboring owner for the outer public CI
  evidence map. Its current design explicitly says it should compose the evidence produced by 652
  rather than redesign unit-test shards. This is the main coordination boundary: 652 produces and
  governs unit-test evidence; 651 presents and trends public CI evidence across all checks.
- `653-public-main-history-hygiene` is an adjacent public-repo policy surface, not a test lane. The
  active worktree has already implemented a forward-only public-history policy with ADR-0045,
  `repo-history-policy.v1.json`, a repo-history verifier, PR-template cleanup, branch-safety
  guidance, and live GitHub merge-setting changes. It can touch branch protection and PR guidance,
  but it should not reinterpret unit-test evidence or CI check names.

Potential long-term interferences:

- If 651 builds a digest or trend reporter, it must consume 652's unit-test attribution artifacts
  and policy output as inputs. It should not move unit-test budgets, parser/PDF tiering, or shard
  ownership into the outer digest layer.
- If 652 later changes unit-test check names, branch-protection facts, or requiredness, it must
  coordinate with 651's public CI map and with 653's public-history verifier/guidance if those tools
  report or assume the protected check set.
- If 653's repo-history verifier grows into a broader remote-settings policy, it should remain about
  publication settings and branch-protection posture. It should not become the owner of CI evidence
  semantics.

No current nearby tempdoc blocks the remaining 652 work. The meaningful risk is boundary blur, not
contradiction: 652 should stay the unit-test evidence owner, while 651 owns CI evidence
presentation/trends and 653 owns public-main publication policy.

## Long-term design settlement after implementation - 2026-06-28

This settlement supersedes the earlier pre-implementation design direction where the repo still had
one broad `Unit tests` lane. The broad lane has now been split, the hosted checks are green, and the
neighboring tempdocs have taken clearer ownership of the outer CI map and public-history policy.
The remaining 652 design should therefore be narrower and more durable than "make CI faster."

### Adjacent-tempdoc synthesis

- `651-public-ci-feedback-loop-efficiency` owns the outer public CI evidence map: digest,
  cross-check timing, build-lane attribution, trend reporting, and contributor-facing failure
  presentation. It should consume 652's unit-test artifacts, not redefine them.
- `653-public-main-history-hygiene` owns public `main` publication policy. It may verify branch
  protection and repository settings, but it should treat protected CI check names as declared
  public facts rather than designing test evidence.
- `650-go-public-capability-descriptor-truthfulness` is the closest structural sibling: when a
  public fact has a guard with the wrong scope, extend the existing guard instead of creating a
  parallel authority.
- `647-engine-performance-attribution-and-budget-allocation` and
  `648-engine-latency-optimization-cross-encoder-cost` provide the sequencing rule for any future
  speed work: first attribution, then budgets, then optimization. Do not choose optimization levers
  because one run looks slow.

### Existing design to extend

The current codebase already has a usable design substrate:

- `.github/workflows/ci.yml` declares three protected unit-test facts:
  `Unit tests (app-ui)`, `Unit tests (search-worker)`, and
  `Unit tests (platform-contracts)`.
- `scripts/ci/workflow-signal-policy.v1.json` declares those check names as required public CI
  facts beside public claims, license/notices, no-model build, secret scan, and CLA.
- `scripts/ci/test-evidence-policy.v1.json` is the sibling policy for non-default test evidence:
  `CI=true` skipped sites, declared non-stress tags, owners, replacement evidence, and cadence.
- `scripts/ci/verify-test-evidence-policy.mjs` scans Java sources for `CI=true` skips and JUnit
  tags, catches stale policy entries, and delegates `stress` to the stress-suite policy.
- `scripts/ci/report-unit-test-attribution.mjs` turns existing Gradle/JUnit XML into module,
  slow-suite, skip/failure/error, and runner-image evidence without becoming the pass/fail source.
- `docs/explanation/09-testing-strategy.md` and
  `docs/reference/contributing/agent-guide.md` already document the hosted unit shards and the
  evidence-tier model.

That is enough structure for 652. The correct design is to improve and govern this substrate, not to
replace it with a generalized test-evidence framework.

### Correct long-term design

652 should treat hosted unit testing as a **protected unit-evidence contract**.

The contract has two parts:

1. **Protected hosted unit facts.** The three unit-test shards are the public required facts for
   ordinary JVM regression evidence. Their names are branch-protection vocabulary. A future change
   to shard names, shard membership, runner platform, or requiredness is not just a YAML edit; it is
   a change to the public evidence contract and must update workflow policy, docs, attribution
   expectations, and branch protection together.
2. **Declared substitutions for non-default evidence.** Any test-shaped evidence that is excluded
   from the protected hosted unit facts must have an explicit reason and replacement when it uses a
   visible mechanism such as `CI=true` skipping or a JUnit tag. The current `test-evidence-policy`
   is the right home: tier, owner, reason, replacement evidence, and cadence.

The three current shards should remain the baseline until attribution shows a stable evidence
boundary that the current split cannot express. More shards are justified only when they name a
distinct fact, such as a deterministic hosted parser lane or a platform-specific test lane. A shard
should not be added merely because one module was slow in one run.

Parser/PDF/OCR/Office fixtures should remain `local-parser-fixture` evidence unless a product or
release decision requires hosted parser proof. If hosted parser evidence becomes necessary, it
should start as a named parser/extraction evidence lane with deterministic setup and clear
requiredness. It should not be smuggled back into a generic unit shard, because that recreates the
old opaque failure mode.

Windows-versus-Ubuntu movement is a proof-environment decision, not a speed trick. A unit shard can
move off Windows only after its Windows assumptions are audited: paths, file locking, native
libraries, Tauri/installer coupling, PowerShell/batch behavior, Lucene filesystem behavior, and
worker-process expectations. The first platform move should be advisory or duplicated until it is
clear that the protected fact can honestly be proven on the new runner.

Future unit-test budgets belong to 652 only when they are about unit evidence: shard duration,
module suite time, slow-suite thresholds, skip counts, or failure/error ownership. They should start
advisory and trend-based, fed by unit attribution. Blocking budgets are premature until hosted
variance is understood over multiple runs.

Source-set omission should not be over-modeled. The repo has many `integrationTest`, system, AI,
stress, and local fixture surfaces that are not part of protected hosted unit evidence. 652 should
not require every non-unit source set to be registered individually. Registration is required when a
test uses an explicit CI-skip or tag that could otherwise hide evidence, or when a local/scheduled
test is presented as replacement evidence for a hosted fact.

### What not to design here

- Do not build the top-level CI digest, run timing ledger, build-lane attribution, or
  contributor-facing "what failed and how do I reproduce it" page in 652. Those belong to 651.
- Do not fold `test-evidence-policy.v1.json` into `workflow-signal-policy.v1.json`; workflow facts
  and test substitutions are related but have different reasons to change.
- Do not add a general repository evidence registry. The current sibling policies are clearer:
  workflow facts, test evidence, stress evidence, and public-history policy each own one class of
  fact.
- Do not make path-aware skipping part of required unit evidence. Skipped required checks are too
  easy to misread, and this repo has projection-heavy inputs.
- Do not make performance budgets blocking before attribution has enough hosted samples to separate
  real regression from runner variance.

### Design reach

The broader principle is **declared evidence substitution**:

> When required public evidence does not directly run a test or proof, the substitute must be named:
> what fact it proves, who owns it, where it runs, how often it is expected, and what hosted evidence
> it replaces or supplements.

This is a specific instance of the broader public-evidence projection principle already present in
the repo. It appears in several places:

- public CI fact lanes project workflow internals into stable branch-protection facts;
- unit-test attribution projects raw JUnit XML into readable module and suite evidence;
- `test-evidence-policy.v1.json` projects scattered `CI=true` skips and tags into owned evidence
  tiers;
- `stress-suite-policy.v1.json` already delegates a special evidence class instead of mixing stress
  tests into ordinary unit semantics;
- 653's public-history policy projects branch work into curated public `main` history;
- 650's capability work projects public prose from declared facts rather than hand-authored forks.

Candidate future scope:

- hosted/advisory parser evidence if PDF/OCR/Office fixture proof becomes merge- or release-relevant;
- advisory unit-test budgets derived from attribution artifacts;
- platform assumption records for Windows-to-Ubuntu moves;
- rendered human-readable summaries of test-evidence policy entries, likely consumed by 651's CI
  digest rather than owned there;
- other opt-in evidence classes only if they start replacing or supplementing protected public facts.

Known gaps relative to this principle:

- The current policy captures explicit `CI=true` skips and declared tags, but it does not make the
  general "integration tests are not part of protected hosted unit evidence" boundary machine
  visible. That is acceptable for now because docs state the boundary and no current design needs a
  per-source-set registry.
- Unit-test attribution has enough detail for ownership, but no advisory budget file exists yet.
  That should wait for trend evidence.
- Parser/PDF/OCR evidence is honest as local fixture evidence, but not yet public hosted evidence.
  That is a product confidence tradeoff to revisit only if parser behavior becomes a merge-required
  public fact.

The principle should be recorded but not generalized into new infrastructure now. 652 needs a
well-owned unit-test evidence contract, not an all-repository evidence system.

## Confidence-building pass - 2026-06-28

Purpose: reduce surprises before any remaining 652 implementation work. This pass did not
implement feature work, change CI, alter shard boundaries, or introduce budgets.

### Static contract audit

The current workflow contract is internally consistent after expanding the unit-test matrix:

- `.github/workflows/ci.yml` defines three unit-test lanes: `app-ui`, `search-worker`, and
  `platform-contracts`.
- The expanded required check names are `Unit tests (app-ui)`, `Unit tests (search-worker)`, and
  `Unit tests (platform-contracts)`.
- `scripts/ci/workflow-signal-policy.v1.json` declares the same three required unit checks.
- The attribution artifact names are `unit-test-attribution-app-ui`,
  `unit-test-attribution-search-worker`, and `unit-test-attribution-platform-contracts`.
- Runner labels are explicit in the reporter call as `windows-latest/app-ui`,
  `windows-latest/search-worker`, and `windows-latest/platform-contracts`.
- `node scripts/ci/check-branch-protection.mjs --repo eliasjustus/justsearch --branch main`
  passed and reported that `main` requires the eight declared checks.

This confirms that 652's protected unit-test vocabulary currently matches workflow policy and
branch-protection policy. Any later shard rename or requiredness change must update all three
places together.

### Policy and reporter validation

Local validation passed:

- `node scripts/ci/verify-test-evidence-policy.mjs --json`
- `node scripts/ci/test-verify-test-evidence-policy.mjs`
- `node scripts/ci/verify-stress-suite-policy.mjs --json`
- `node --check scripts/ci/report-unit-test-attribution.mjs`
- `node scripts/ci/test-report-unit-test-attribution.mjs`
- `node scripts/ci/check-workflow-triggers.mjs`
- `node scripts/ci/check-tempdoc-numbers.mjs`

The test-evidence policy currently covers all discovered `CI=true` skipped Java test sites and all
non-stress JUnit tags. The discovered explicit CI skips are owned in `app-services`, `ui`, and
`worker-services`. The discovered tags are `ai`, `evidence`, `experiment`, `stress`, `systemTest`,
and `vdu`; `stress` remains delegated to `stress-suite-policy.v1.json`.

The attribution reporter is adequate for future advisory budgets because it reports module totals,
slow suites, skipped counts, failure/error counts, and runner identity. One local caveat matters:
running the reporter from the repository root can include copied hosted artifacts under local
temporary directories such as `tmp/gha-unit-*` if they exist. A clean hosted checkout is not affected,
but local budget research should either use clean artifacts or constrain `--results-root` to the
intended results tree.

A constrained local attribution run against `modules` reported 6131 tests, 27 skipped tests, no
failures, no errors, 1098 suites, and about 3m06s of summed suite time. The slowest local modules in
that evidence were `worker-services`, `app-services`, `indexer-worker`, `adapters-lucene`, `ui`,
`app-inference`, and `app-launcher`. This is enough for ownership diagnosis, but not yet enough for
blocking hosted budgets.

### Hosted evidence check

Read-only hosted checks showed that the three attribution artifacts are being uploaded on successful
CI runs. Recent green CI runs completed around ten minutes overall. The unit shards are all in the
same broad range, with no stable single long pole:

- one green `main` run had `Unit tests (app-ui)` around 9m57s, `Unit tests (platform-contracts)`
  around 9m42s, `Unit tests (search-worker)` around 8m56s, and `Build (no model blobs)` around
  9m17s;
- nearby green runs alternated between `app-ui` and `platform-contracts` as the slowest unit shard;
- `Build (no model blobs)` remains close enough to the unit shards that broad feedback-loop speed
  work belongs with 651, not 652.

A newer failed Dependabot dependency run failed broadly and early across multiple checks. It is not
evidence that the 652 shard design is wrong.

### Platform-risk review

All three current unit shards should be classified as `mixed/needs targeted experiment` for any
future Windows-to-Ubuntu move:

- `app-ui` includes Windows-sensitive app utility and launcher tests, including power status, job
  objects, app instance locking, runtime manifest/logback behavior, and headless-eval surfaces.
- `search-worker` includes worker services, Tika/PDF fixture behavior, index locking, worker-core
  native/model discovery, Lucene filesystem behavior, and platform path contracts.
- `platform-contracts` has the strongest native/runtime risk because it includes GPU bridge,
  app-inference, ORT/common fixture behavior, benchmarks, system-test substrate, and test-support
  surfaces.

This means an Ubuntu migration should not be treated as a speed-only change. The first platform
move, if attempted, should be advisory or duplicated until it proves the same public fact honestly.

### Assumptions confirmed or changed

Confirmed:

- The three protected unit-test checks match workflow policy and branch protection.
- `test-evidence-policy.v1.json` still covers the explicit skipped/tagged evidence surface.
- Local parser/PDF/OCR evidence remains a defensible local fixture tier; no current evidence forces
  a hosted parser lane.
- Unit attribution has the right data shape for future advisory budgets.
- 652 should stay scoped to unit-test evidence ownership, test-evidence policy, parser/test-tier
  boundaries, unit budgets, and platform assumptions.

Adjusted:

- Budget work should prefer hosted attribution artifacts or a constrained result root. Local
  repository-root scans can be polluted by copied artifacts.
- Direct protected Ubuntu migration is lower-confidence than a casual read suggests. All shards have
  enough Windows/native/file-system risk to require an advisory experiment first.
- There is no evidence yet for another immediate shard split. The remaining latency problem is now
  shared across unit shards and the build lane, so cross-check timing and contributor presentation
  remain 651's concern.

### Remaining uncertainty and confidence

Remaining uncertainties:

- Hosted shard variance needs several clean samples before any advisory budget becomes meaningful.
- A parser/extraction hosted lane may become necessary later if parser behavior becomes a
  merge-required public fact, but that is not required by the current evidence.
- Ubuntu eligibility needs targeted experiments per shard or per module group before any required
  check moves off Windows.
- Any future check-name or branch-protection change must coordinate with 651's CI digest direction
  and 653's public-history policy.

Confidence for implementing the remaining 652 work: **8/10**.

The contract is coherent, verified locally, and backed by recent hosted evidence. Confidence is not
higher because budget thresholds and platform moves still depend on hosted trend samples and
targeted platform experiments.

## Implementation closeout - 2026-06-29

Implemented the remaining 652 unit-test evidence contract slice without changing shard boundaries,
branch protection, runner class, or parser/test requiredness.

What landed:

- added `scripts/ci/unit-test-shard-policy.v1.json` as the checked-in contract for the three public
  unit shards: check name, artifact name, runner label, Gradle task list, local reproduction command,
  owner, platform classification, platform-risk notes, and advisory budget settings;
- added `scripts/ci/verify-unit-test-shard-policy.mjs` plus fixture tests so the workflow matrix,
  workflow-signal required checks, and shard policy cannot drift independently;
- extended `scripts/ci/report-unit-test-attribution.mjs` with explicit `lane` identity and tightened
  default JUnit XML discovery so repo-root scans do not count copied hosted artifacts under local
  temporary directories;
- added `scripts/ci/report-unit-test-budget.mjs` plus fixture tests to turn one shard's attribution
  JSON into warn-only budget evidence;
- wired the policy verifier into the existing `Public claims` lane;
- wired each unit shard to emit attribution and advisory budget JSON/Markdown artifacts, while
  keeping Gradle as the unit-test pass/fail authority;
- updated canonical testing and agent guidance to document the unit-shard policy, platform
  classification, and warn-only budget reports.

The advisory budget defaults are intentionally conservative and non-blocking:

- `maxSummedSuiteSeconds`: 300;
- `slowSuiteWarnSeconds`: 60;
- `maxSkipped`: 50.

These values are not a performance promise. They are an initial visibility floor that should be
revisited only after several clean hosted attribution samples exist.

Validation performed during implementation:

- `node --check scripts/ci/report-unit-test-attribution.mjs`
- `node scripts/ci/test-report-unit-test-attribution.mjs`
- `node --check scripts/ci/verify-unit-test-shard-policy.mjs`
- `node scripts/ci/test-verify-unit-test-shard-policy.mjs`
- `node scripts/ci/verify-unit-test-shard-policy.mjs --json`
- `node --check scripts/ci/report-unit-test-budget.mjs`
- `node scripts/ci/test-report-unit-test-budget.mjs`
- `node scripts/ci/report-unit-test-attribution.mjs --lane local --json --allow-empty`
- `node scripts/ci/verify-test-evidence-policy.mjs --json`
- `node scripts/ci/test-verify-test-evidence-policy.mjs`
- `node scripts/ci/verify-stress-suite-policy.mjs --json`
- `node scripts/ci/check-workflow-triggers.mjs`
- `node scripts/ci/test-check-workflow-triggers.mjs`
- `node scripts/ci/check-tempdoc-numbers.mjs`
- `node scripts/ci/check-branch-protection.mjs --repo eliasjustus/justsearch --branch main`;
- `CI=true ./gradlew.bat test -PskipWebBuild=true --console=plain`;

Docs-maintenance validation:

- `node scripts/docs/llmstxt-generate.mjs --check`
- `node scripts/docs/skills-sync.mjs --check`
- `node scripts/docs/verify-canonical-doc-links.mjs`
- `node scripts/architecture/module-deps.mjs --check-canonical`
- `node scripts/docs/prompt-surface-inventory.mjs`

Known unrelated validation issue:

- `node scripts/docs/verify-runtime-config-matrix.mjs` currently fails with a script-level
  `TypeError` because it expects `buildMatrixModel()` fields named `yamlKeys` and
  `envSyspropPairs`, while the current matrix model exposes counts and `rows`. The generator itself
  still works with `node scripts/docs/generate-runtime-config-matrix.mjs --check`. This is not caused
  by the 652 changes and was left untouched.

Hosted validation after push:

- opened PR #11 from `codex/unit-test-evidence-contract` to `main`;
- hosted CI run `28360852429` passed all required checks with unchanged check names;
- `Public claims` ran and passed the new `Unit-test shard policy guard`;
- each unit shard ran `Report unit-test attribution` and `Report unit-test advisory budget`;
- downloaded and inspected the three unit-test artifacts:
  - `unit-test-attribution-app-ui`: 3133 tests, 536 suites, 0 budget warnings;
  - `unit-test-attribution-search-worker`: 2120 tests, 402 suites, 0 budget warnings;
  - `unit-test-attribution-platform-contracts`: 878 tests, 160 suites, 0 budget warnings.

Remaining future-only 652 work:

- do not add blocking budgets until hosted variance is understood across multiple clean runs;
- do not move any protected unit shard off Windows without an advisory duplicate/platform
  experiment;
- do not add a hosted parser/PDF/OCR lane unless parser behavior becomes a merge-required public
  fact;
- keep 651 as the owner of top-level CI digest, build-lane attribution, and cross-check trend
  presentation.
