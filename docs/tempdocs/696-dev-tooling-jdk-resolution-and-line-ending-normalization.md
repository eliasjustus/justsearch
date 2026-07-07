---
title: "Dev-tooling hardening: dev-runner JDK resolution + schema-writer line-ending normalization"
type: tempdocs
status: open — investigation complete, canonical JDK determined (25), scope proposed; NOT yet implemented
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
