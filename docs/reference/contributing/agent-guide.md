---
title: Agent Guide
type: reference
status: stable
description: "Development workflows, static analysis, and testing reference for autonomous agents."
---

# Agent Guide

## 1. Configuration & Single Source of Truth (SSOT)

### 1.1. The Golden Rule: Inversion of Control

**Low-level modules are "dumb."** They do not scan the filesystem for configuration.
Modules must be **given** their configuration object (POJO) via their constructor.

### 1.2. Path Resolution Order (CRITICAL)

All path resolution follows this priority:
1. **System Property** (e.g., `-Djustsearch.repo.root=/path`) - **Highest Priority**
2. **Environment Variable** (e.g., `JUSTSEARCH_REPO_ROOT=/path`)
3. **Auto-discovery** (traverse up from CWD looking for `SSOT/` directory)

**Never use `System.getenv()` directly.** Use `EnvRegistry.MY_VAR.get()`.

## 2. Testing Guidelines

### 2.1. The Test Pyramid

| Test Type | Directory | Purpose |
| :--- | :--- | :--- |
| **Unit** | `src/test` | Isolated classes, mocked deps. <10ms. |
| **Integration** | `src/integrationTest` | Module wiring, real Logic. |
| **System** | `modules/system-tests` | Spawns real child processes. IPC verification. |

### 2.2. Testing IPC

Use `WorkerProcessManager` to spawn the worker and `MmfTestHarness` to discover the ephemeral port.

```java
// Standard pattern for system tests
WorkerProcessManager worker = new WorkerProcessManager(workerJar, tempDir);
worker.spawnWorker();
MmfTestHarness mmf = new MmfTestHarness(worker.getSignalFilePath());
mmf.open();
int port = mmf.awaitPort(30_000, 100); // Wait for startup
GrpcTestClient client = new GrpcTestClient(port);
```

### 2.3. Test Tiers in Practice

Use the right Gradle command for the verification level you need:

| Command | What runs | When to use |
|---------|-----------|-------------|
| `./gradlew test` | Unit tests only (`src/test/java`) | **Fast inner loop** — after every code change |
| `./gradlew check` | Unit + integration + PMD + Spotless | **Pre-commit** — before pushing |
| `./gradlew fullTestSuite` | Unit + integration + system (no soak; system tier requires `-PincludeSystemTests=true`) | Full verification (system-tests module) |
| `./gradlew nightlyTestSuite` | Full suite including soak (requires opt-in flags for system/soak tiers) | Nightly CI only |

**Opt-in flags** (system-tests module only):

- `-PincludeSystemTests=true` — include system/chaos tests
- `-PincludeAiTests=true` — include AI inference tests (requires GPU)
- `-PincludeAgentTests=true` — include agent deterministic battery tests
- `-PincludeSoakTests=true` — include long-running soak tests

Source of truth for test tier definitions: `modules/system-tests/build.gradle.kts`.

**AI eval runtime policy (canonical):**

1. Keep AI tests opt-in for normal dev and CI verification paths (`-PincludeAiTests=true` required).
2. Use full RAG quality runs (`*RagQualityEvalTest`) on a perf-capable environment (dedicated self-hosted runner or equivalent local setup), not every default `check` run.
3. Run `node scripts/verify-prerequisites.mjs` before AI quality runs that depend on native runtime/model availability.

## 3. Development Workflow

### 3.1. Git Stash Warning

**Never use `git stash` to inspect staged state.** Use `git diff --cached --stat`
instead. Stash with `--keep-index` followed by `pop` silently drops unstaged
modifications when the working tree has staged renames or a complex mix of staged
and unstaged changes. This has caused silent data loss in production agent sessions.

### 3.2. Long-Running Eval Hygiene

Use wrapper-first orchestration for long-running evaluation and benchmark sessions:

- Canonical wrapper: `python -m jseval` (see the `/jseval` skill)
- Do not treat raw backgrounded PowerShell eval commands as the normal orchestration path
- Use `--progress-file <path>` when a run may survive agent compaction or handoff

Path and dependency rules:

- Model/native path environment variables used for Java or ONNX launches should be absolute
- After changing Gradle dependencies or telemetry/exporter coordinates, regenerate lockfiles before handoff:
  `./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks`

### 3.3. Pre-commit Verification

Before pushing changes, run the local gate to ensure code quality passes:

```powershell
# Heavy verification
./gradlew check --no-configuration-cache

# Pre-merge checks (run individually when their subjects change — see
# CLAUDE.md "Quick Commands → Pre-merge script checks" for the canonical list)
node scripts/ci/check-workflow-triggers.mjs
node scripts/ci/check-root-readme.mjs
node scripts/governance/run.mjs --gate wire --mode gate
```

#### PMD Rule Configuration

PMD is configured in `config/pmd/ruleset.xml` across 5 categories:

| Category | Rules |
|----------|-------|
| codestyle (1) | `UnnecessaryFullyQualifiedName` |
| errorprone (10) | `BrokenNullCheck`, `DoNotTerminateVM`, `EmptyCatchBlock`, `EqualsNull`, `ImplicitSwitchFallThrough`, `MisplacedNullCheck`, `OverrideBothEqualsAndHashcode`, `ReturnFromFinallyBlock`, `UnconditionalIfStatement`, `UselessOperationOnImmutable` |
| multithreading (3) | `AvoidThreadGroup`, `DoubleCheckedLocking`, `NonThreadSafeSingleton` |
| security (2) | `HardCodedCryptoKey`, `InsecureCryptoIv` |
| bestpractices (4) | `UnusedLocalVariable`, `UnusedPrivateField`, `UnusedPrivateMethod`, `UnusedAssignment` |

**Exception handling policy:**

An audit of ~100 `catch (Exception ignored) {}` sites found that silent exception swallowing was the codebase default because it was 1 line while proper handling was 5-10 lines. This created invisible failures: config reload errors, health endpoint crashes, index cleanup leaks, and streaming truncation — all with no log, no metric, no signal. The `Faults` utility was built to make the right thing equally convenient, and the PMD rule was tightened to prevent recurrence.

Every `catch` block must either (a) log the exception, (b) rethrow/wrap, or (c) include a comment explaining why silence is correct. Use `Faults.logAndContinue()` or `Faults.logAndFallback()` from `modules/configuration` for fault-isolation patterns — these are 1-line replacements for `catch (Exception ignored) {}`. PMD enforces `EmptyCatchBlock`: naming the variable `ignored` is NOT permitted (the `allowExceptionNameRegex` only accepts `expected`). For genuinely expected exceptions (parse fallbacks), use `expected` as the variable name with a comment.

**Rules intentionally excluded:**
- `CompareObjectsWithEquals` - Intentional identity comparisons exist (Thread, Throwable cause checks)
- `AvoidBranchingStatementAsLastInLoop` - Flags valid break-after-find patterns
- `AvoidCatchingGenericException` - Intentional error handling patterns

#### Intentional Code Patterns

Some code patterns trigger static analysis warnings but are intentional:

**Identity comparisons (`==`):**

```java
Thread.currentThread() == ownerThread  // Thread identity check
cause != throwable                      // Throwable cause loop prevention
indexRuntime != activeRuntime           // Runtime instance check
block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS  // Enum-like singletons
```

**Intentionally silent expected exceptions:**
Only use an empty catch when the exception is genuinely expected and silence is correct. PMD only allows the exception variable name `expected`; `ignored` is not permitted. Prefer logging, rethrowing, wrapping, or `Faults.logAndContinue()` / `Faults.logAndFallback()` for fault-isolation paths.

```java
} catch (NumberFormatException expected) {
  // fallback to default value
}
```

#### Error Prone Configuration

[Error Prone](https://errorprone.info/) is Google's compile-time bug detector, applied as a `javac` plugin via `conventions.errorprone` (`build-logic/src/main/kotlin/conventions/ErrorProneConventionsPlugin.kt`). Generated sources are excluded via `-XepExcludedPaths:.*[\\/]build[\\/]generated[\\/]`.

**Global suppressions:** `config/errorprone/suppressions.txt` - one check name per line. Currently suppressed:

| Check | Reason | Warnings suppressed |
|-------|--------|---------------------|
| `PatternMatchingInstanceof` | Java 21 pattern matching style preference | - |
| `ReferenceEquality` | `Character.UnicodeBlock` constants are singletons; `==` is correct | 112 |
| `StringSplitter` | Simple `String.split()` usage where behavior is well-understood | 10 |

**How to suppress a new check:**
- **Globally:** Add the check name to `config/errorprone/suppressions.txt` with a comment explaining why
- **Per-site:** Add `@SuppressWarnings("CheckName")` to the method or class

#### Additional Static Analysis Tools

**Dependency Analysis (DAGP):**

```bash
./gradlew buildHealth  # Full dependency analysis
```

Detects unused dependencies, wrong configurations (api vs implementation), and used-but-undeclared transitive dependencies. Report at `build/reports/dependency-analysis/build-health-report.txt`.

**Lock-skew convergence gate:**

```bash
node scripts/ci/report-lock-skew.mjs --root . --fail-on-unexpected-skew --out-json tmp/lock-skew-report.json
```

Fails when duplicate locked coordinates exist outside the optional allowlist. With no allowlist, this is a zero-skew check.

**Lockfile freshness check:**

CI and the local gate both run `resolveAndLockAll --write-locks` then `git diff --exit-code` on all lockfiles. If committed lockfiles are stale, the build fails.

**Never hand-edit lockfiles.** Always regenerate with `resolveAndLockAll --write-locks`. Manual edits miss auxiliary configurations (like PMD aux classpaths) that only appear during full resolution.

Regenerate lockfiles after changing versions in `libs.versions.toml`, adding/removing dependencies, or changing plugin versions:

```bash
./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks
```

**Playwright selector-literal guard:**

```bash
node scripts/ci/check-playwright-hardcoded-testids.mjs --mode gate --root modules/ui-web/e2e
```

Fails when Playwright specs use hardcoded `getByTestId('...')` string literals instead of shared selector constants.

**Unreferenced Code Detection (ArchUnit):**

```bash
./gradlew :modules:app-launcher:test --tests "*UnreferencedCodeTest*"
```

Detects unreferenced private methods across the codebase using bytecode analysis. Known false positives (FFM callbacks, test infrastructure) are frozen in `archunit_store/`.

To freeze new false positives:
1. Edit `modules/app-launcher/src/test/resources/archunit.properties`
2. Set `allowStoreCreation=true` and `allowStoreUpdate=true`
3. Run the test
4. Restore both settings to `false`

**Module Staleness Detection:**

```bash
node scripts/architecture/module-deps.mjs --include-staleness
```

Flags modules with zero dependents that haven't been modified in 6+ months. Entry-point modules (app-launcher, shell, benchmarks) naturally have zero dependents and are excluded.

#### `@RecordBuilder` Convention

Records in `app-api` that have **>10 positional parameters** and are **constructed across multiple modules** should be annotated with `@RecordBuilder` (io.soabase.record-builder). This generates a fluent builder so new fields don't break callers. See [ADR-0022](../../decisions/0022-recordbuilder-annotation-processor.md) for the decision rationale and rejected alternatives.

**When to annotate:** When you add a field to a record and the positional constructor change causes compilation errors in multiple modules. Don't annotate preemptively — annotate when coupling pain first appears.

**Enforcement:** An ArchUnit rule in `ArchitectureRulesTest` flags positional construction of annotated records in production code. The rule maintains an explicit type list — add new records to `RECORD_BUILDER_TYPES` when annotating.

**Currently annotated (8 records):** `KnowledgeSearchResponse`, `KnowledgeSearchResponse.Hit`, `KnowledgeSearchResponse.PipelineExecution`, `MigrationGenerationView`, `EnrichmentProgressView`, `WorkerDebugView`, `WorkerOperationalView`, `KnowledgeStatusView`.

**Adding a field to an API record:**

1. Add the field to the record parameter list.
2. If collection type, add null-coalescing in compact constructor (e.g., `myList = myList == null ? List.of() : List.copyOf(myList)`).
3. Compile: `./gradlew.bat :modules:app-api:compileJava` -- the builder regenerates automatically.
4. Update production construction site (e.g., `KnowledgeHttpApiAdapter`) to pass the new field.
5. If part of API contract, update the controller (`KnowledgeSearchController`) -- the controller uses a manual HashMap build, NOT direct record serialization. Adding a field to the record does NOT change API output unless the controller is also updated.
6. Regenerate schemas and fixtures: `./gradlew.bat :modules:app-api:updateSchemas`
7. If the record is part of `/api/status` or `/api/knowledge/search`, also update the Zod schema in `modules/ui-web/src/api/schemas.ts` and the TypeScript type in `stores/systemTypes.ts` (for status) or `api/domains/search.ts` (for search). Zod schemas use `.loose()` -- new fields pass validation without being declared, so contract tests alone won't catch a missing Zod field.
8. Run frontend contract tests: `cd modules/ui-web && npm run test:unit:run`
9. Note: v1 contract files (`status-v1.json`, `knowledge-status-v1.json`, `debug-state-v1.json`) are intentionally manual -- only update on breaking changes (field removal/rename), not field additions.

### 3.4. UI smoke checks (Agent workflow)

#### 3.4.1 Start the dev stack (backend + UI)

```powershell
# From repo root
$env:JUSTSEARCH_API_PORT = "33221" # pick a deterministic base port (dev-all will try near this)
npm --prefix ./modules/ui-web run dev:all
```

#### 3.4.2 Deterministic readiness checks

Before interacting with the UI, verify the backend is ready:

- `GET http://127.0.0.1:<apiPort>/api/status` returns 200
- `GET http://127.0.0.1:<apiPort>/api/health` returns 200 and reports Worker `UP`

`dev:all` prints the chosen backend endpoint (it may pick a nearby port if the base port is busy).

#### 3.4.3 Open the UI in a browser (Agent browser tools)

When an agent has access to an embedded browser tool, open the UI with an explicit backend port:

- `http://localhost:5173/?api_port=<apiPort>`

Notes:

- Prefer `localhost` for the UI URL. Some agent browser environments cannot reach `127.0.0.1` even when the host OS can.
- If the UI loads but shows "disconnected", check:
  - the backend readiness endpoints above
  - browser console messages and network requests

#### 3.4.4 What to verify in the UI (minimal "it works")

- Confirm the status bar shows **Connected**.
- Navigate between tabs (Search / Library / AI Brain / Health) and ensure each page renders.
- On **Health**, confirm API and Index indicators are green.

Desktop-only features (native folder picker, file dialogs) are unavailable in a pure browser run. A warning banner is expected.

#### 3.4.5 Debugging tools (agent-friendly)

- Prefer inspecting API responses directly (`/api/status`, `/api/health`) over inferring state from UI text.
- If the UI is blank or unresponsive:
  - check the browser console for errors
  - check Vite output and backend logs

#### 3.4.6 Clean shutdown

- Stop the dev stack with `Ctrl+C` in the terminal running `dev:all`.
- On Windows, if processes linger, terminate the process tree (e.g., via `taskkill /T /F`) to avoid stale ports.

#### 3.4.7 Visual verification of UI component changes (jseval ui-shot)

When editing files under `modules/ui-web/src/`, use `jseval ui-shot` for targeted screenshot capture to verify visual correctness:

- **Single step**: `jseval ui-shot <step>` captures a screenshot of the named UI state. Read the output PNG path to see the result inline.
- **Step discovery**: `jseval ui-shot --list` shows all available steps (chain and isolated) with their dependency relationships.
- **Affected steps**: `jseval ui-shot --affected <file>` finds which steps exercise a given source file. Works with full Windows paths.
- **Measurement companion (615 §6.2)**: every capture also writes a `<step>.measure.json` (accessibility tree + axe violations + element geometry/overflow + console errors) and prints a one-line fact summary. Judge correctness from the **facts** (cheaper + more reliable than the PNG); read the PNG for gestalt. The harness drives the **live Lit `shell-v0`** — there is no mock-data demo mode, so data/AI steps need the dev stack running (AI also needs `ai_activate`).
- **Auto-hint**: The `ui-shot-hint` PostToolUse hook automatically suggests relevant steps after edits to `.ts` files under `modules/ui-web/src/`. The hint is lightweight (<50ms, no process spawning) and the agent decides whether to capture.
- **Full reference**: Load the `/ui-check` skill for the complete step registry, file-to-step index, and worktree-aware auto-serve details.

### 3.5. CI signal model

The public repository has two CI postures:

- The standard hosted `CI` workflow runs automatically on pull requests and
  pushes to `main`, and can also be dispatched manually. ADR-0044 owns this
  public hosted lane.
- Self-hosted, release, security, and benchmark/specialty workflows stay
  manually dispatched unless a later ADR changes their trigger policy. ADR-0026
  remains the historical basis for that manual-specialty posture.

The public hosted `CI` workflow is split into stable fact lanes: public claims,
license and notices, no-model build, unit tests, and secret scan. A red
check should name the fact that failed rather than one generic build bucket.

The public unit-test signal is sharded into `Unit tests (app-ui)`, `Unit tests
(search-worker)`, and `Unit tests (platform-contracts)`. Each shard publishes a
unit-test attribution report from Gradle/JUnit XML. Use those reports to
identify slow modules, slow suites, skipped counts, and hosted runner image
identity before proposing another workflow split. Do not split the lanes solely
by runtime without an evidence boundary.

The unit-test shards run with `-PskipWebBuild=true` because the web bundle is
owned by the separate `Build (no model blobs)` fact lane. Keep that boundary
intact: if web assets need verification, use or extend the build lane rather
than making unit-test lanes prove the same fact again.

The public-claims lane verifies `scripts/ci/test-evidence-policy.v1.json`.
Whenever a Java test is skipped under `CI=true`, or a non-stress JUnit tag is
introduced, update that policy with the owner, evidence tier, replacement
signal, and cadence. Stress tags remain governed by
`scripts/ci/stress-suite-policy.v1.json`.

Local verification is still required before pushing substantive work. Use the
automatic PR checks as remote confirmation, not as a replacement for running the
subject-specific local commands.

**Dispatch `ci.yml` manually** when you need a fresh hosted run outside the
normal pull-request or push trigger, for example after updating a branch whose
previous run was cancelled or when rechecking after a transient hosted-runner
failure.

**Dispatch manual specialty workflows** when you changed a subject of their
gate:

- `docs-lint.yml` after edits under `docs/**`, `scripts/docs/**`, or
  `scripts/architecture/module-deps.mjs` if you need remote docs verification.
- `codeql.yml` when you need semantic code scanning outside GitHub's managed
  security surfaces.
- `build-installer.yml --ref <vX.Y.Z>` for installer/release attach validation.
- `phase-3-observability-nightly.yml` after changes to query orchestration,
  fusion weights, reranking, or anything that could shift σ(nDCG@10).

The `agent-live-eval-nightly.yml`, `rr219-resilience-governance-nightly.yml`,
`rr219-resilience-soak-weekly.yml`, `track-g-report-win.yml`, and
`claim-a-report-win.yml` workflows were retired in slice 3a-1-8f §B.14
(2026-05-12). Their underlying DAG runners and bench infrastructure were
deleted by commit `a9c484f59` (2026-03-16); jseval covers the substance
(`scripts/jseval/` — `agent-eval`, `retrieval-eval`, `rag-eval`,
`bench-concurrency`, etc.).

**How to trigger and inspect:**

```text
gh workflow run ci.yml                              # re-run public hosted fact lanes
gh workflow run docs-lint.yml                       # manual docs verification
gh workflow run codeql.yml                          # semantic code scanning
gh workflow run build-installer.yml --ref <vX.Y.Z>  # installer/release attach
gh workflow run phase-3-observability-nightly.yml   # σ(nDCG@10) drift gate
gh run list --workflow=<name> --limit=1             # check latest status
gh run view <id>                                    # inspect a specific run
```

Main branch protection requires the stable public check names declared in
`scripts/ci/workflow-signal-policy.v1.json`. Run
`node scripts/ci/check-branch-protection.mjs --repo eliasjustus/justsearch --branch main`
with a maintainer token after changing CI job names or branch-protection settings; the default
pull-request `GITHUB_TOKEN` cannot read branch-protection settings.

### 3.5.1. CI Failure Troubleshooting

**Test failure:**

- Symptom: `dorny/test-reporter` Check Run shows failing test names with line numbers.
- Reproduce: `./gradlew.bat :modules:<m>:test --tests "*FailingTest"`
- Fix: Read the failing test, fix the code or test expectation, re-run.

**PMD violation:**

- Symptom: `./gradlew check` fails with "PMD rule violations found".
- Reproduce: Check `build/reports/pmd/main.xml` under the failing module for `file:line:rule` detail.
- Fix: Fix the flagged code pattern. See §3.3 for intentionally excluded rules and accepted patterns.

**Spotless formatting:**

- Symptom: `./gradlew check` fails with "Spotless violations found".
- Reproduce: `./gradlew.bat spotlessCheck`
- Fix: Run `./gradlew.bat spotlessApply`, then re-stage the changed files.

**Docs lint (markdownlint):**

- Symptom: `docs-lint` workflow fails with `file:line MD0xx` violations.
- Reproduce: `npx markdownlint docs/explanation/**/*.md docs/reference/**/*.md docs/how-to/**/*.md docs/decisions/**/*.md`
- Fix: Fix the Markdown issue. Common violations: MD040 (missing code fence language tag — use `text` for plain output), MD032 (blank line around lists).

**Docs lint (link check):**

- Symptom: `docs-lint` workflow fails with "broken link" or "tempdoc cross-reference" error.
- Reproduce: `node scripts/docs/verify-canonical-doc-links.mjs`
- Fix: Fix the broken link, or remove the `docs/tempdocs/` cross-reference from canonical docs (canonical docs must not link to tempdocs).

**Docs lint (runtime config matrix):**

- Symptom: `docs-lint` or `ci` fails with a runtime config matrix mismatch error.
- Reproduce: `node scripts/docs/verify-runtime-config-matrix.mjs`
- Fix: Regenerate and/or update `docs/reference/configuration/runtime-config-ownership-matrix.md` using `node scripts/docs/generate-runtime-config-matrix.mjs --write-doc docs/reference/configuration/runtime-config-ownership-matrix.md`.

**Docs lint (module dependency graph drift):**

- Symptom: `docs-lint` or `ci` fails because canonical module dependency docs are stale.
- Reproduce: `node scripts/architecture/module-deps.mjs --check-canonical`
- Fix: Run `node scripts/architecture/module-deps.mjs --update-canonical`, then commit the updated `docs/reference/architecture/module-deps.md`.

**llms.txt drift:**

- Symptom: `docs-lint` workflow fails with "llms.txt is out of date".
- Reproduce: `node scripts/docs/llmstxt-generate.mjs --check`
- Fix: Run `node scripts/docs/llmstxt-generate.mjs` to regenerate, then commit the updated `docs/llms.txt`.

### 3.6. Module-Scoped Verification

When you've changed a specific module, you don't need to test everything:

```powershell
# Build + test the changed module and everything that depends on it
./gradlew :modules:adapters-lucene:buildDependents

# Build + test only the changed module (faster, but won't catch breaks in dependents)
./gradlew :modules:adapters-lucene:build
```

Rule of thumb:

- Changed a **leaf module** (ui, indexer-worker, app-launcher): use `:modules:X:build` — nothing depends on these.
- Changed a **core module** (core, configuration, infra-core): use `:modules:X:buildDependents` — many modules depend on these.
- **Unsure?** Run `node scripts/architecture/module-deps.mjs --include-staleness` to see the dependency graph.
