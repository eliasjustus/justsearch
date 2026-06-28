---
title: "Public CI test-evidence lanes and hosted unit-test ownership"
type: tempdoc
status: "implemented slice - local validation complete"
created: 2026-06-28
updated: 2026-06-28
related:
  - 651-public-ci-feedback-loop-efficiency
  - 650-go-public-capability-descriptor-truthfulness
  - 647-engine-performance-attribution-and-budget-allocation
---

> NOTE: Noncanonical working note. Verify against `.github/workflows/`, current GitHub
> Actions status, Gradle test reports, and the code before treating any detail as current truth.

# 652 - Public CI test-evidence lanes and hosted unit-test ownership

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

Remaining follow-up:

- measured timing evidence for the current unit-test lane;
- runner image label and, where available, runner image version for hosted timing evidence;
- the main slow modules/classes or setup costs;
- which tests are Windows-specific versus platform-neutral;
- whether a split would preserve or improve required public facts;
- a recommended lane shape, including any checks that should stay local-only, advisory, or required.
