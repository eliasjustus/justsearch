---
title: "Dev-tooling hardening: dev-runner JDK resolution + schema-writer line-ending normalization"
type: tempdocs
status: open — IMPLEMENTED + verified (Issue 1 JDK resolver + Issue 3 LF normalization; branch worktree-td696-dev-jdk-eol, no PR yet). Discovered follow-up: an always-true `updateSchemas` gate in build.gradle.kts (pre-existing; logged, not fixed). See "Implementation (2026-07-07)" at end
created: 2026-07-07
updated: 2026-07-07
category: dev-tooling / build / windows
related:
  - 655-mcp-conformance-and-capability-policy
  - 684-dev-workflow-tooling-hardening-batch
  - 637-silent-staleness-masquerades-as-product-bug
  - docs/observations.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 696 — Dev-tooling hardening: dev-runner JDK resolution + schema-writer line-ending normalization

## Handoff status (read this first) — 2026-07-08

Both issues IMPLEMENTED, reviewed (incl. an independent refute-first pass that found + fixed one bug),
and verified. Branch `worktree-td696-dev-jdk-eol`; commits `740ecb1` (risk register), `f8ac602` (feat),
`86f2bf4` (Worker `java.home` fix), `5ba05ad` (lessons). **No PR opened yet** — awaiting owner go-ahead.

**Verified (each with an evidence pointer):**
- Resolver selects a >=24 JDK, rejecting an ambient JDK 8 — `npm run test:resolve-jdk` → "11 checks
  passed"; plus a probe: `JAVA_HOME=<jdk8> node -e "require('./scripts/dev/lib/resolve-jdk.cjs').resolveJdkHome()"`
  → returned a JDK-25 home.
- Head leg works end-to-end (live) — with ambient JDK-8 `JAVA_HOME`, the resolved JDK boots Gradle:
  spawn `gradlew -version` with `env:{...,JAVA_HOME:resolved}` → "Gradle 9.6.1 | JVM: 25.0.2 | exit=0", no
  "JVM 17 required".
- Worker leg works end-to-end (live, decisive) — started the dev stack under an ambient JDK-8-first PATH;
  `Get-CimInstance Win32_Process` showed the Worker java binary = a JDK-25 `bin\java.exe` (not bare
  `java`), and `/api/health` → `worker: LIFECYCLE_STATE_READY`. Unit guard: `WorkerSpawnerJavaBinaryTest`.
- Issue 3 no-churn — forced `./gradlew.bat :modules:{app-api,ui,app-observability,app-services,worker-services}:test
  --rerun-tasks` (95 tasks executed, no cache) → BUILD SUCCESSFUL and `git status` shows **zero** churn
  under `SSOT/**`, `**/schemas/**`, `__fixtures__/**`, `errors.en.json`.

**Unverified assumptions / deferred checks (NOT verified — do not treat as proven):**
- The `justsearch-dev-mcp/server.mjs` **hot-swap** change (`resolveJavaExe()` + `JAVA_HOME`) was NOT
  exercised end-to-end: the dev-MCP server runs stale code within a session (tempdoc 637), so a live
  hot-swap couldn't run the edited path. It is code-reviewed and reuses the same resolver that is
  unit-tested and live-proven for the assemble/head legs — low risk, but its own flow is unrun.
- A cold `dev_start` from a fresh session under a deliberately-broken `JAVA_HOME` was not run start-to-finish
  (the Worker-launch + READY was verified within this session's stack).

**Follow-ups that must not be forgotten (logged to the observations inbox; out of 696's scope):**
- **`updateSchemas`-always-true gate** in several `build.gradle.kts` — a task named `updateSchemas` is
  exposed as a project property, so `hasProperty(...)` is always true → schema tests regenerate instead of
  comparing, defeating the schema-drift guard. Strongest 696 follow-up.
- **Direct-`gradlew` JDK gap** — 696 fixes dev-runner/hot-swap/prepare-worktree, but an agent/human running
  `./gradlew.bat` directly still inherits the ambient `JAVA_HOME`; on a JDK-8-fronting machine that needs a
  manual `JAVA_HOME=<jdk25>` every call. Consider a documented note or a `.gradle` init-script.
- `LambdaMartBenchmarkTest` load-flake (5ms-p50 latency assertion); `prepare-worktree.cjs` gradlew-path bug
  (obs 1625, unrelated to JDK).

**Known unrelated dirty state (leave untouched):** the shared main checkout currently holds ANOTHER
session's in-progress work (tempdoc `691`, a session shard) plus pre-existing untracked `models/**/*.onnx`
(LFS) — none belong to this tempdoc.

## Purpose

Two Windows dev-environment defects recur across agent sessions, cost real time each time, and are
already partially logged in the observations conditions store without a landed fix. This tempdoc scopes
the two durable, tooling-layer fixes. A third symptom investigated alongside them turned out to be a
non-issue (documented below so no one re-chases it).

Origin: surfaced during the tempdoc-655 close-out (2026-07-07) — the JDK mismatch blocked every
`./gradlew.bat` call and the `justsearch_dev_start` MCP tool for a whole session; the line-ending churn
had to be manually reverted repeatedly to keep PR diffs clean. Root causes were then confirmed by a
dedicated read-only investigation (evidence cited inline below).

## Canonical Java version (decided, with evidence)

**The project's canonical runtime JDK is Temurin 25.** Evidence:
- `.github/workflows/ci.yml:27` (and `build-installer.yml:11`, `onramp-smoke.yml:21`) set
  `JAVA_VERSION: "25"`; CI provisions **both 21 and 25** (`ci.yml:104-106` `java-version: | 21 / ${{ env.JAVA_VERSION }}`).
- `gradle.properties` comment (line 24-25): *"keeps the global toolchain at 25 while allowing EP [Error
  Prone] to use a compatible compiler"* (a local JDK 23 for test compiles).
- `gradle.properties:1` sets `-XX:+UseCompactObjectHeaders`, a **JDK 24+** feature — so the JVM running
  the build must be ≥24; JDK 8 fails hard.
- Runtime launchers pin JDK 25 (`modules/ui/build.gradle.kts:894,959`); `codeql.yml:24` pins `'25'`.
- 21 and 23 are **sub-toolchains** (build-logic convention plugins compile at 21 —
  `build-logic/build.gradle.kts:66`; Error Prone uses 23), auto-provisioned via the foojay resolver
  (`settings.gradle.kts`) with `org.gradle.java.installations.auto-download=true`. They are not the
  version a human/dev-runner should launch Gradle with.

**Conclusion:** the dev-runner (and any doc that tells a contributor which JDK to install) should target
**JDK 25**. The bare minimum to bootstrap Gradle 9.6.1 is 17, and the JVM args require ≥24, but the
declared canon is 25 — pin 25 to match CI and avoid toolchain-download friction.

## Issue 1 (WILL FIX) — dev-runner has no JDK resolution; inherits an ambient JDK-8 JAVA_HOME

**Root cause (evidenced):**
- `gradlew.bat` (root) does no JVM-version check — it execs `%JAVA_HOME%/bin/java.exe` directly; the
  "Gradle requires JVM 17 or later" error is raised *inside* `gradle-wrapper.jar` only after it has
  already launched under whatever `JAVA_HOME` pointed at.
- Toolchain auto-provisioning (foojay + `auto-download`) governs the *compile/runtime* JVM only; it
  **cannot** choose the bootstrap JVM that runs Gradle itself — that is `JAVA_HOME`/`PATH`.
- `scripts/dev/dev-runner.cjs` has **zero** JDK-resolution logic and inherits ambient env at its spawn
  sites — `:1011-1014` (`gradlew assemble`, the exact site that produced "Gradle assemble failed with
  exit code 1" from `justsearch_dev_start`) and `:1074-1089` (the generated `ui.bat` launcher, which
  also prefers `JAVA_HOME` unconditionally).
- The concrete trigger: a scoop `temurin8-jdk` install/update rewrote the persisted user `JAVA_HOME` to
  JDK 8 (observations line 240).

**Already logged:** `docs/observations.md` lines 182 and 240 (2026-07-05), the latter with essentially
this fix pre-written: *"dev-runner resolves/pins an explicit JDK-25 JAVA_HOME into the spawn env +
preflight runs JAVA_EXE -version so this fails fast instead of a 15s port timeout."*

**Proposed fix (design level — not yet built):** in `dev-runner.cjs`, before any gradle/`ui.bat`/worker
spawn, resolve a JDK ≥24 (target 25): probe ambient `JAVA_HOME`/`PATH` java `-version`; if unsuitable,
search known-good candidates (a `JUSTSEARCH_DEV_JDK_HOME` env convention first, then common install
roots), and pass an explicit `env: { ...process.env, JAVA_HOME: resolved }` on every spawn. Add a fast
`java -version` preflight so a bad JDK fails in ~1s with a clear message rather than an opaque assemble
failure or a 15s port-wait timeout. **Do NOT** hardcode a machine-specific path into the committed
`gradle.properties` (`org.gradle.java.home`) — that file is shared with all contributors + CI; a personal
path belongs in a developer's user-level `~/.gradle/gradle.properties`, documented not code-enforced.

**Open design questions for the implementation pass:** the candidate-search heuristic must be robust
across machines/CI (or it becomes a new drift source); decide whether to (a) fail-hard when no ≥24 JDK is
found, or (b) fall back to ambient with a loud warning; decide the exact env-var convention name.

## Issue 2 (NO FIX — recorded so it is not re-chased) — dev-MCP "staleness" is cosmetic

The `justsearch-dev` MCP server's `mcpServerStale.sourceChangedSinceBoot: true` is an **intentional,
by-design** self-freshness notice (tempdoc 637 §H.1; `scripts/dev/justsearch-dev-mcp/server.mjs:13-74`,
attached to every tool result by `toToolResult()` at `:501-518`). The `justsearch.dev.start` handler
(`:627-780`) never references it in any control-flow branch — verified by reading it in full. The
`dev_start` failure this session was **Issue 1 (JDK-8)**, which merely co-occurred with the staleness
flag; the two were wrongly conflated in the 655 retrospective. There is no in-session reload path (no
`SIGHUP` handler; reconnect = session restart, per tempdoc 637). **No change recommended.** Practical
guidance (already correct): treat the flag as informational; when `dev_start` fails, diagnose the real
error; or drive the stack directly via `node scripts/dev/dev-runner.cjs start --skip-build`.

## Issue 3 (WILL FIX) — schema-writer JSON emits CRLF on Windows (phantom-dirty)

**Root cause (bytecode-verified — NOT a `.gitattributes` gap):**
- The repo's `.gitattributes` is correct (`* text=auto eol=lf`) and `git ls-files --eol` shows the index
  is uniformly LF-clean for the affected paths; so this is not stale-blob or attribute mis-config.
- The churn comes from **Jackson's `writerWithDefaultPrettyPrinter()`**, whose `DefaultIndenter` defaults
  to `System.lineSeparator()` = **CRLF on Windows** (confirmed by disassembling the pinned
  `tools.jackson.core:jackson-databind:3.1.0`). Six schema/fixture-writing test classes use it without
  normalizing, so on Windows they write CRLF to disk:
  `modules/app-api/src/test/java/.../{registry/SubstrateSchemaGenTest, schema/WireRecordSchemaGenTest,
  indexing/IndexingJobViewSchemaTest, knowledge/KnowledgeSearchResponseSchemaTest,
  status/StatusRecordSchemaTest}.java` and `modules/ui/src/test/java/io/justsearch/ui/api/SettingsV2ContractTest.java`.
- The root `SSOT/schemas/**` copy is then propagated byte-for-byte into the dual-copy locations by the
  `syncSsotSchemas` Sync task (`modules/ui/build.gradle.kts:235-241`) and `syncSsotCatalogs`
  (`modules/adapters-lucene/build.gradle.kts:83-89`) — so ~40 files show as modified after any full
  build, line-ending-only (zero content diff; invisible to `git diff --stat`).

**Already logged:** `docs/observations.md` lines 1114 and 1538 (2026-07-01) — but those proposed a
`.gitattributes` pin. Since the generator is **first-party code here**, the correct fix is at the source.

**Proposed fix (design level — not yet built):** make the six write sites emit LF — construct
`DefaultPrettyPrinter` with `new DefaultIndenter("  ", "\n")` (or strip `\r\n` before
`Files.writeString`), ideally via **one shared test-support helper** (`writeJsonPretty(path, json)`) so
the fix is not duplicated six times and future schema-writers inherit it. This makes the Gradle-side
writer match the repo's declared LF policy, so the Sync propagation never sees a mismatch. A
`.gitattributes` pin is a weaker, symptom-level mitigation (it wouldn't stop Gradle writing CRLF bytes to
disk, only force the committed content to LF) and is unnecessary given the generator is ours.
`git add --renormalize` is **not** currently needed (index is already LF-clean).

**Verification note for the implementation pass:** after the fix, run `:modules:app-api:updateSchemas`
(and the ui schema/fixture-writing tests) once on Windows and confirm the regenerated files are
byte-identical (LF) to what is committed, i.e. `git status` stays clean after a full build.

## Scope / boundary

- IN: Issue 1 (dev-runner JDK resolution + preflight) and Issue 3 (schema-writer LF normalization, via a
  shared helper). Both are dev-tooling-only; neither changes product behavior, wire contracts, or schema
  content (Issue 3 changes only line-ending bytes).
- OUT: Issue 2 (no change). The scoop/temurin8 machine-env trigger for Issue 1 is a human coordination
  matter outside repo tooling. No change to committed `gradle.properties` `org.gradle.java.home`.
- Relationship to 684 (dev-workflow-tooling-hardening-batch): this is the same family; kept as its own
  tempdoc because both fixes are concrete, evidenced, and independently shippable rather than a batch of
  loosely-related items. If 684's owner prefers to absorb these, they can be folded — noted, not assumed.

## Verification plan (for the implementation pass, not run yet)

- Issue 1: on a machine with an ambient JDK-8 `JAVA_HOME`, `node scripts/dev/dev-runner.cjs start
  --skip-build` (and a normal start) must resolve JDK 25 and succeed, or fail fast (~1s) with a clear
  message; add/extend a dev-runner unit test for the resolver where feasible.
- Issue 3: `git checkout -- SSOT modules/ui/src/main/resources/SSOT modules/app-api/src/main/resources/schemas modules/ui-web/src/api/__fixtures__`
  to clean, then run the schema/fixture-writing tests + a full `build`, and confirm `git status` shows no
  line-ending churn.
- Full suite: `JAVA_HOME=<jdk25> ./gradlew.bat build -PskipWebBuild=true` green; do not merge without an
  explicit go-ahead.

## Status

Investigation complete; canonical JDK decided (25); scope proposed. **Implementation not started.**

## Implementation-readiness / risk register (2026-07-07) — pre-implementation de-risking pass

A read-only confidence pass (no feature code; one non-mutating diagnostic test run, restored after). Each
row: finding, effect on confidence, and any scope change. Evidence is `file:line`-cited.

### Issue 1 — dev-runner JDK resolution

- **T1 (spawn footprint) — BROADER than two dev-runner sites (scope up).** JVM launches that inherit the
  ambient JDK: `dev-runner.cjs:1011` (gradle `assemble` `spawnSync`, **no `env`** → add JAVA_HOME);
  `dev-runner.cjs:1074+` (head launch — **already builds an explicit `env: { ...process.env, ... }`
  object**, the clean injection point); the **worker + inference are spawned by the head** and inherit its
  env transitively (comment at `:1023` "env vars are inherited"; residual — confirm `WorkerSpawner`
  doesn't `.environment().clear()`, low risk). Two sites OUTSIDE dev-runner share the same JDK-8
  vulnerability: `scripts/dev/justsearch-dev-mcp/server.mjs:2545,2587` (hot-swap via bare `java …
  --source 25` off PATH) and `scripts/dev/prepare-worktree.cjs` (spawns gradle installDist; also has a
  separate path bug, obs 1625). **→ the clean design is ONE shared JDK-resolver helper consumed by all
  three files, not two inline patches.**
- **T2 (resolution strategy) — no existing mechanism to reuse (build fresh; MEDIUM confidence).**
  `dev-runner.cjs` has zero JDK logic; the MCP `justsearch.dev.preflight` checks dist/models, not JDK.
  Proposed ordering: ambient `JAVA_HOME`/PATH java if version ≥24 → a new `JUSTSEARCH_DEV_JDK_HOME` env
  convention → known candidate roots (Gradle's own `~/.gradle/jdks/` auto-download cache is the
  least-machine-brittle; scoop `temurin25-jdk`/Program Files as fallback) → fail-fast with a clear
  remediation message. Chicken-and-egg ruled out `gradlew -q javaToolchains` for discovery (it needs a
  working ≥17 JVM to run). **This heuristic is the judgment-heavy part of the whole tempdoc** — a weak one
  becomes a new drift source; the env-var escape hatch bounds that risk.
- **T3 (direct-`gradlew` scope) — RESOLVED; dev-runner scope is correct (confidence up).** README.md:92 /
  CONTRIBUTING.md:53 document the contract: "any recent JDK to bootstrap Gradle — the toolchain
  auto-resolves the required JDK 25." The session failure was `JAVA_HOME`=JDK **8** (below the bootstrap
  floor) from a scoop temurin8 clobber (obs 240) — a machine env issue, out of repo-tooling scope. So the
  fix is "make the dev stack resilient to a broken ambient JDK," not "fix direct gradle." obs 240
  pre-specifies exactly this fix.
- **T4 (verification) — resolver unit is testable; E2E is gapped.** Version-parse + JDK-selection are pure
  (unit-test with mocked `java -version`). Full E2E (reproduce JDK-8 → `dev_start` succeeds) needs a JDK-8
  env + the shared stack (contended); a non-stack probe (`JAVA_HOME=<temurin8>` + run only the resolver's
  detect step) is feasible and should be the accepted verification at impl time.

### Issue 3 — schema-writer LF normalization

- **T5 (writer set) — 8 sites, not 6 (scope up; the tempdoc's "6" was incomplete).** Named 6 verified;
  **`ErrorCatalogJsonArtifactTest` (writes `SSOT/messages/errors.en.json`) is the missed 7th** and DOES
  churn (confirmed live below). Plus 5 first-capture-gated writers (4 `app-observability` schema tests +
  `UIOperationViewConformanceTest`) that don't churn today but share the pattern — fix for consistency.
  Main-source controllers/`McpToolSurface` use the same Jackson API but serialize HTTP responses
  **in-memory, never to disk** — not affected (so the 655 `McpToolSurface` change is unrelated).
- **T6 (approach) — use `.replace("\r\n","\n")`, NOT the indenter swap (HIGH confidence).** Committed files
  are 2-space indent, but `DefaultPrettyPrinter` has **separate object/array indenters** — a naive
  `new DefaultIndenter("  ","\n")` swap must patch both or array content still emits CRLF. String-replace
  on the produced string has no such trap and has an **in-codebase precedent** (`StatusRecordSchemaTest.java:581,590`
  already normalizes on the compare side).
- **T7 (test interaction) — the fix is purely anti-churn; won't break passing tests (HIGH confidence, LIVE-verified).**
  Most tests parse-and-compare (`MAPPER.readTree`) — line-ending-insensitive. The subagent flagged
  `ErrorCatalogJsonArtifactTest`'s raw-string compare as a *possible live RED test on Windows*; I ran it
  (`./gradlew.bat :modules:app-api:test --tests "*ErrorCatalogJsonArtifactTest*"`, JAVA_HOME=temurin25):
  it **PASSED and churned `errors.en.json`** (git status `M` after; restored). So it is a **churn source,
  not a failure** — Issue 3 is confirmed cosmetic, and normalizing writers to LF keeps the compare passing.
- **T8 (helper home) — small open decision.** No cross-module test-support bridge is currently consumed as
  a test dependency (`modules/test-support` is a `java-library` used only via a launched CLI, not
  `testFixtures`). Options: (a) wire `testImplementation(project(":modules:test-support"))` into the
  affected modules and host `writeJsonPretty(path,json)` there; or (b) a tiny per-site/per-module
  `.replace`. Given the 8 sites span app-api/ui/app-observability/app-services, (b) may be simpler than
  wiring a new cross-module dep into 4 modules — an implementation-pass call.

### Confidence rating & difficulty

**Issue 3: ~8.5/10, difficulty LOW.** Approach settled (string-replace, precedent exists), risk understood
(cosmetic, won't break tests — live-verified), sites enumerated (8). Only open item: helper location (small).

**Issue 1: ~6.5/10, difficulty MEDIUM.** Injection points are clear, but the footprint is broader (one
shared resolver across 3 files), the discovery heuristic has real cross-machine robustness choices (the
judgment-heavy part), and full E2E verification is gapped (JDK-8 env + contended stack).

**Overall: ~7/10.** The pass settled Issue 3's approach, mapped Issue 1's exact injection points and true
footprint, resolved the direct-gradle scope question, and killed the scary "ErrorCatalog might be RED"
false alarm. Residual uncertainty is concentrated in Issue 1's resolver heuristic + its E2E gap.

**Model/effort recommendation:** **Sonnet at high effort** for the whole tempdoc. Both are dev-tooling,
bounded, and verifiable (unit-testable resolver + a git-status-clean check for Issue 3), and Issue 3 is
squarely mechanical. Issue 1's cross-machine JDK-discovery heuristic is the one piece where judgment
matters — it's well-specified by obs 240 + T2 above, so a strong brief lets Sonnet handle it, but **escalate
just the resolver design to Opus if the heuristic proves shaky**. Not max effort: no architectural
ambiguity remains. Suggested sequencing — do Issue 3 first (fast, high-confidence win), then Issue 1.

## Implementation (2026-07-07) — both issues done, verified; NOT yet a PR

Backend/dev-tooling only (no UI — nothing to browser-check). Committed on branch
`worktree-td696-dev-jdk-eol` (no PR opened yet, per owner instruction).

**Issue 1 — shared JDK resolver.**
- New `scripts/dev/lib/resolve-jdk.cjs` — resolves a JDK whose `java` is >=24 (target Temurin 25):
  ambient `JAVA_HOME` if good → `JUSTSEARCH_DEV_JDK_HOME` → `~/.gradle/jdks/*` → scoop/Adoptium/OS roots →
  fail-fast with an actionable message. Pure `parseJavaMajor`/`selectFromCandidates` exported under
  `__test`.
- Wired (inject `JAVA_HOME: resolveJdkHome()` into the spawn env) at: `dev-runner.cjs` gradle `assemble`
  + head launch (Worker + inference inherit the head env — confirmed `WorkerSpawner` uses
  `pb.environment().put(...)`, no `.clear()`); `justsearch-dev-mcp/server.mjs` hot-swap (`resolveJavaExe()`
  for the `--source 25` java + `JAVA_HOME` env on the gradle compile, reusing the existing `_ownReq` CJS
  interop); `prepare-worktree.cjs` `installDist` (lazy — a `--no-dist` run needs no JDK).
- New unit test `scripts/dev/test-resolve-jdk.mjs` + `package.json` `"test:resolve-jdk"`.
- **Verified:** `npm run test:resolve-jdk` (11/11); `npm run test:dev-runner` still green (my dev-runner
  edits didn't break its `__test` export); node `--check` on all edited files; **non-stack probe** — with
  the ambient JDK-8 `JAVA_HOME`, `resolveJdkHome()` correctly returned the Temurin-25 home, not JDK 8.
  Not live-verified this session: the `server.mjs` hot-swap path (stale dev-MCP server) and full
  `dev_start`-under-JDK-8 E2E (shared stack contended) — both use the unit-tested resolver.

**Issue 3 — schema-writer LF normalization** (delegated to a sonnet subagent; verified by the main loop).
- 13 test classes that write committed JSON via `writerWithDefaultPrettyPrinter()` now force LF on both
  the JSON body AND the trailing terminator (the terminator was the real churn — `System.lineSeparator()`
  is CRLF on Windows). The subagent's repo-wide sweep found one beyond the 8-site estimate
  (`SearchPlannerApprovalCorpusTest`, worker-services). String-replace approach (not the indenter swap),
  per the risk register.
- **Verified:** combined full build `./gradlew.bat build -PskipWebBuild=true` (JAVA_HOME=25) **BUILD
  SUCCESSFUL**, and `git status` shows **ZERO** churn under `SSOT/**`, `**/resources/**/schemas/**`,
  `__fixtures__/**`, `errors.en.json` after the build — only the 13 intended test-file edits.

**Teardown (rode along):** the four observation entries this work closes (JDK-8 dev-runner: obs 182, 240;
CRLF churn: obs 1114, 1540) are annotated RESOLVED-by-696 in `docs/observations.md`.

**Discovered follow-up (out of 696's scope — logged to the observations inbox, NOT fixed):** the churn's
deeper root cause is a pre-existing `build.gradle.kts` bug — `if (project.hasProperty("updateSchemas"))`
is **always true** because a Gradle task named `updateSchemas` is exposed as a project property, so every
plain `./gradlew test` takes the "regenerate baseline" branch instead of comparing. The LF fix neutralizes
the churn symptom and is forward-compatible if that gate is later corrected, but the always-true gate
(which also defeats the schema-drift guard the comparison was meant to provide) is a separate defect worth
its own fix. This is the strongest candidate for a 696 follow-up.

## Post-implementation refute review + fix (2026-07-07)

An adversarial refute-first review of the shipped implementation found **one substantive bug in the
Issue-1 fix itself** (and one minor nit). Recorded because the miss is instructive.

**The miss — the Worker's dev launch never *read* `JAVA_HOME`.** The shipped fix set `JAVA_HOME` on the
Head's spawn env and the risk register claimed the Worker "inherits it transitively → low risk, confirm
`WorkerSpawner` doesn't `.clear()` env." That check was **necessary but insufficient**: env *presence* ≠
env *consulted*. `WorkerSpawner.buildCommand()` launched the Worker in dev mode with a **bare `java`**
(`isWindows() ? "java.exe" : "java"`), resolved via PATH — and on this machine `where java` fronts a
**JDK 8** (scoop temurin8) while the Worker bytecode is JDK 25 (`0x45`), so the Worker could crash with
`UnsupportedClassVersionError`. The earlier "worker READY" was PATH order happening to favor JDK 25 —
luck, exactly the ambient fragility Issue 1 exists to remove. `detectProductionMode()` returns false for a
dev installDist launch, so the Worker always took the bare-java branch.

**The fix.** `WorkerSpawner.buildCommand()` now selects the Worker's java binary from
`System.getProperty("java.home")` (the running Head JVM's own runtime — deterministically the resolved
JDK 25, since dev-runner launches the Head under it), unified before the prod/dev branch (which now differ
only in the AOT-cache path). Extracted a package-private `workerJavaBinary(boolean)` with a regression
test `WorkerSpawnerJavaBinaryTest` (asserts the binary is `java.home`-based, never bare `java`). This is
the missing Worker leg; the shipped Head/hot-swap/prepare-worktree env injections are unchanged.
Minor nit also fixed: `prepare-worktree.cjs` now reports a resolver throw via its clean
`[prepare-worktree] …` convention instead of a raw Node stack.

**Verification — the fix is proven live, not just against the plan:**
- Unit: `WorkerSpawnerJavaBinaryTest` green; full `:modules:app-services:test` green.
- **Decisive live check:** rebuilt the Head dist, started the dev stack under the ambient **JDK-8-first
  PATH**, and inspected the actual Worker process (`Get-CimInstance Win32_Process`): its java binary was
  `F:\scoop\apps\temurin25-jdk\current\bin\java.exe` (JDK 25, `java.home`), **not** bare `java` — and
  `/api/health` reported `worker: LIFECYCLE_STATE_READY`. So the Worker now launches under the Head's
  JDK 25 independent of PATH order. (The earlier external anchor for the Head leg: with ambient JDK-8
  `JAVA_HOME`, the resolver injected JDK 25 and `gradlew -version` booted under JVM 25.0.2, exit 0.)
- One flaky failure observed during a concurrent full build — `LambdaMartBenchmarkTest` (a 5ms-p50 latency
  assertion, load-sensitive) — passed cleanly in isolation (`--rerun-tasks`); unrelated to this change,
  logged to the observations inbox.
- The refuter confirmed the rest correct: injection order at all 4 sites, Issue-3 completeness across all
  modules, content-safety of the LF `.replace`.

## Session lessons & operational provenance (2026-07-08) — stands without any chat transcript

Noncanonical working history; verify against `main` before trusting. Recorded so a future agent (or an
external reader) can continue/audit without the private session.

**Public provenance.** Branch `worktree-td696-dev-jdk-eol` (no PR opened yet — not merged). Commits:
`740ecb1` (confidence-building risk register), `f8ac602` (feat: resolver + LF normalization), `86f2bf4`
(fix: Worker `java.home` from the refute review).

**Exact validation commands (reproducible).**
- Node: `npm run test:resolve-jdk` (11 checks); `npm run test:dev-runner`.
- Java: `JAVA_HOME=<jdk25> ./gradlew.bat build -PskipWebBuild=true`;
  `./gradlew.bat :modules:app-services:test --tests "*WorkerSpawnerJavaBinaryTest*"`.
- Issue-3 no-churn (decisive): after a full build, `git status --short` shows zero modified files under
  `SSOT/**`, `modules/**/resources/**/schemas/**`, `modules/ui-web/src/api/__fixtures__/**`,
  `SSOT/messages/errors.en.json`. Force it with `--rerun-tasks` on the five test tasks so no writer is
  skipped by cache.
- F1 live (decisive): rebuild the Head dist (`:modules:ui:installDist`), start
  `node scripts/dev/dev-runner.cjs start --skip-build` under an ambient JDK-8-first PATH, inspect the
  Worker java binary (`Get-CimInstance Win32_Process -Filter "Name='java.exe'"` filtered to
  `indexer-worker` → must be a `java.home`/JDK-25 path, not bare `java`), and confirm `/api/health`
  `worker: LIFECYCLE_STATE_READY`.

**Generalizable lessons.**
1. **Env presence ≠ env consulted.** A fix that relies on "the child inherits `JAVA_HOME`/`PATH`/env"
   must verify the child actually *reads* it, not just that it's present. The shipped Issue-1 fix put
   `JAVA_HOME` in the Worker's env but the Worker launched a bare `java` off PATH — the risk register's
   "confirm no `.environment().clear()`" check was necessary-but-insufficient and nearly shipped a broken
   fix. When a fix's correctness rests on inheritance, trace the *consumption* site.
2. **A pass under uncontrolled conditions isn't proof.** The Worker "was READY" in an earlier session — by
   PATH-order luck, not correctness. An incidental success under an uncontrolled variable (here: which JDK
   PATH fronts) must not be read as validation; control the variable or inspect the actual runtime state
   (the process command line settled it unambiguously).
3. **Refute-first review earns its keep.** An independent "every claim is wrong until proven" pass caught
   the substantive C3 bug that the implementer's own review missed. Keep it as standard for non-trivial
   changes.
4. **Direct-`gradlew` JDK gap (recommended follow-up).** This tempdoc fixes dev-runner / hot-swap /
   prepare-worktree, but an agent/human running `./gradlew.bat` *directly* still inherits the ambient
   `JAVA_HOME`. On a machine where `where java` fronts a JDK 8 (this dev's scoop setup), every direct
   gradle call needs `JAVA_HOME=<jdk25>` set manually — a repeated per-agent cost. Consider a documented
   contributor/agent note or a `.gradle` init-script that resolves a >=24 toolchain for the bootstrap JVM.

**Known unrelated dirty work / follow-ups (do not treat as this tempdoc's scope).**
- The **`updateSchemas`-always-true** gate in several `build.gradle.kts` (a task named `updateSchemas` is
  exposed as a project property, so `hasProperty(...)` is always true → tests regenerate rather than
  compare, defeating the schema-drift guard). Pre-existing; logged to the observations inbox; the strongest
  696 follow-up.
- `LambdaMartBenchmarkTest` (app-services integrationTest) is **load-flaky** — a 5ms-p50 latency assertion
  that fails under concurrent-build load and passes in isolation; logged.
- `prepare-worktree.cjs` has a separate gradlew-path bug (obs 1625) — unrelated to JDK, out of scope.
- The main checkout carries pre-existing untracked `models/**/*.onnx` (LFS) belonging to no session.

**Private/ephemeral caveats (not canonical).** Session-specific ports (e.g. `51457`) are throwaway;
machine paths like `F:\scoop\apps\temurin25-jdk\current` are this dev's install — the resolver finds an
equivalent JDK 25 on any machine via its candidate chain (`JAVA_HOME` → `JUSTSEARCH_DEV_JDK_HOME` →
`~/.gradle/jdks` → scoop/Adoptium/OS roots).
