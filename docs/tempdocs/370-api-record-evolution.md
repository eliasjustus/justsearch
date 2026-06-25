---
title: "370: API Record Evolution Workflow"
status: superseded
superseded_by: "ADR-0022 (RecordBuilder), docs/reference/contributing/agent-guide.md §3.3, .claude/rules/common-workflows.md"
created: 2026-03-30
updated: 2026-04-06
merges: [370, 372]
---

# 370: API Record Evolution Workflow

## Problem

Adding a field to an API record like `KnowledgeSearchResponse` is
disproportionately expensive. The pain has two dimensions:

### A. Constructor coupling (was 370)

`KnowledgeSearchResponse` is a Java record with 25 positional
constructor parameters. Adding one field breaks every callsite
across every module — tests construct it with long chains of
positional `null`s that are fragile and unreadable:

```java
new KnowledgeSearchResponse(
    0, 5, List.of(), null, null, null, null, null, null, null,
    null, null, null, null, null, null, null, null, null, null,
    null, null, null, null, null);
```

This is O(N) coupling: each new field requires updating every
caller. In the 366 tempdoc, adding `queryUnderstanding` required
fixing 8 callsites across 3 modules (`app-api`, `app-services`,
`app-agent`).

### B. Schema regeneration fragmentation (was 372)

When a record used in the API contract changes, the developer must
run two separate regeneration commands with different flags:

1. `./gradlew.bat :modules:app-api:test --tests '*regenerateSchemas' -PupdateSchemas=true`
   — regenerates JSON Schema baselines in `src/main/resources/schemas/`
2. `./gradlew.bat :modules:app-api:test -DupdateContractFixtures=true`
   — regenerates cross-language contract fixtures in `modules/ui-web/src/api/__fixtures__/`

Each uses a different flag mechanism (`-P` vs `-D`), different
test target names, and writes to different directories. There is
no single `./gradlew.bat updateSchemas` task. Missing either one
causes test failures that look like code bugs rather than
regeneration omissions.

Note: the v1 contract files (`status-v1.json`, `knowledge-status-v1.json`,
`debug-state-v1.json`) are **intentionally manual** backward-compatibility
baselines. They test that old JSON shapes can still deserialize into
current record types. They only need updating on breaking changes
(field removal/rename), not on field additions.

### Combined impact

In the 366 tempdoc, adding a single field to `KnowledgeSearchResponse`
caused: 8 callsite updates across 3 modules, multiple schema
regeneration failures, a persistent cross-language fixture failure,
and several test-fix-rerun cycles. What should be a 5-minute change
took over an hour of churn.

## Research findings

### Language-level status (as of April 2026)

The Java language has **not** solved record construction ergonomics:
- **JEP 468 (Derived Record Creation / withers)** — stuck in
  Candidate status since April 2024. Not in JDK 23, 24, 25, or 26.
- **No JEP exists** for record builders, default parameter values,
  or named parameters. No movement on the amber-dev mailing list.
- **JDK-8254009** — open bug requesting builder defaults for records
  (since Oct 2020). Unresolved.

### Ecosystem solutions

**Annotation processor builders (mainstream approach):**

| Library | Version | Key differentiator |
|---------|---------|--------------------|
| Randgalt/record-builder | v52 (active) | De facto standard. Builders, withers, staged builders, lambda modifications. |
| avaje/avaje-record-builder | Active (2025+) | `@DefaultValue` annotation for compile-time defaults. Null-safe collections. |
| cbarlin/advanced-record-utils | v0.6.8 | Deeply nested record support — builders, withers, mergers, diffs. |
| lilbaek/recordbuilder | v1.0.3 | Zero-config opinionated builder generation. |
| Lombok `@Builder` | v1.18.42 | Works on records since 1.18.20. `@Builder.Default` has known bugs on records. |

**Compiler plugins (alternative approach):**

| Tool | What it does |
|------|-------------|
| Manifold (`manifold-params`) | Adds optional parameters + named arguments directly to Java syntax. Works on records. Java 8-25. Full IntelliJ support. Higher-risk dependency (modifies javac). |
| Auties00/NamedParameters | Similar to Manifold but lighter. Java 17+ only. Less mature. |

**Schema regeneration tools:**
- **be-hase/jsonschema-generator-tools** — Gradle plugin wrapping
  victools. Provides `generateJsonSchema` build task.
- **victools/jsonschema-generator v5.0.0** — Java 17+, Jackson 3.x
  baseline. Matches this project's stack.
- **origin-energy/java-snapshot-testing v4.x** — Jest-like
  `update-snapshot` flag for Java. Auto-regeneration on demand.
- No single tool unifies schema baseline + cross-language fixture
  regeneration. Custom Gradle task is still required.

**Jackson 3.x (already in use):**
- Built-in parameter names support (no separate module needed).
- Record deserialization uses canonical constructor by default.
- `KnowledgeSearchResponse` is only serialized, never deserialized
  from JSON in production — builder approach is unconstrained.

### Community consensus

Annotation-processor builders (especially Randgalt/record-builder)
are the mainstream solution. For test code specifically, the
**Object Mother + Fluent Builder** pattern is widely recommended.
Manifold is technically superior but carries compiler-plugin risk.

## Experiment results

### Part A experiments

**Blast radius (adding one field):**
- Added `Boolean dummyExperimentField` as 26th param
- **8 compilation errors across 3 modules**: `app-services` (1 prod),
  `app-api` (1 test), `app-agent` (6 test)
- All errors: "actual and formal argument lists differ in length"
- Cascade: `app-services` main source failure blocks all downstream
  modules from compiling

**Compact constructor constraints:**
- Defensive copies: `results` → `List.copyOf`, `facets` → `Map.copyOf`,
  `entityFacetVariants` → deep copy via stream
- Null coalescing: null collections → `List.of()` / `Map.of()`
- No `Objects.requireNonNull` — all fields are nullable
- Builder defaults: `null` is safe for all non-collection fields.
  Collection fields need `List.of()` / `Map.of()` defaults (or null,
  since the compact constructor normalizes).

**record-builder v52 prototype:**
- Annotated `KnowledgeSearchResponse` with `@RecordBuilder`
- Generated 1201-line `KnowledgeSearchResponseBuilder.java`
- Compilation: clean. No Spotless/PMD/SpotBugs conflicts.
- Generated code lands in `build/generated/sources/annotationProcessor/`
  (excluded from linting automatically)
- Compact constructor normalization preserved (null → empty collections)
- Downstream modules (app-agent, app-services, ui) can use builder
  with zero additional configuration
- One-time setup cost: verification-metadata.xml + lockfile updates
  (7 new artifacts)
- No runtime dependency — `@RecordBuilder` is source-retention,
  generated code uses only JDK types

**JSON serialization path:**
- `KnowledgeSearchController.handleSearch()` builds a `HashMap`
  manually from record fields, NOT direct record serialization
- Only explicitly mapped non-null fields appear in JSON output
- Adding a field to the record does NOT change API output unless
  the controller is also updated
- `@JsonInclude(NON_NULL)` on the top-level record is irrelevant
  (only nested records use Jackson serialization directly)

**Callsite analysis:**
- 8 callsites total: 1 production, 7 test
- Test callsites: 6 set only 3 fields (totalHits, tookMs, results),
  nulling the other 22. 1 (schema fixture) sets 15 fields.
- Builder ROI is very high for test code — most callers only need
  2-3 fields.

### Part B experiments

**Unified Gradle task prototype:**
- Created `./gradlew.bat :modules:app-api:updateSchemas` task
- Normalizes both regeneration methods to single `updateSchemas` flag
  (`-P` converted to system property in build.gradle.kts)
- Single task runs both `regenerateSchemas()` and
  `regenerateContractFixtures()` plus all validation tests
- No ordering issues — JUnit 5 executes in declaration order
- Error messages updated to point to unified command
- Old invocation paths still work (backward compatible)

**V1 contract files:**
- `status-v1.json`, `knowledge-status-v1.json`, `debug-state-v1.json`
  are **intentionally manual** backward-compat baselines
- They test old JSON shapes deserialize into current records
- They only break on field removal/rename (breaking change), not addition
- No auto-regeneration needed — this is by design

**-P vs -D inconsistency:**
- Accidental. `-P` uses a build.gradle.kts shim to convert Gradle
  property → system property. `-D` bypasses the shim.
- Both end up as `System.getProperty()` calls in the test
- Unified task normalizes to single `-P` flag

## Decisions

### Part A: Use record-builder v52 (Randgalt)

**Chosen approach:** `@RecordBuilder` annotation processor.

**Rationale:**
- Prototype proved it works with JDK 25 + Jackson 3.1.0 + Gradle 9.4.0
- Zero runtime dependency, generated code uses only JDK types
- No Spotless/PMD conflicts
- Compact constructor normalization preserved
- Downstream modules get builder access automatically
- De facto standard in Java ecosystem (v52, actively maintained)
- Simplest integration: one annotation, one dependency

**Rejected alternatives:**
- **avaje-record-builder**: `@DefaultValue` is nice but unnecessary —
  compact constructor already normalizes nulls to empty collections.
  record-builder is more established.
- **Manifold**: Technically superior (native syntax) but modifies
  javac. Too invasive for this codebase's build setup.
- **Manual builder**: More code to maintain. Annotation processor
  generates and maintains it automatically.
- **Lombok `@Builder`**: Known bugs with `@Builder.Default` on records.

### Part B: Unified `updateSchemas` Gradle task

**Chosen approach:** Single task with normalized flag.

**Implementation:** Prototype already working in worktree. Task runs
both regeneration methods + validation tests via single flag.

## Scope

### Part A: Constructor coupling

- [x] Audit: which records have >10 positional parameters?
- [x] Audit: how many callsites does each cross-module record have?
- [x] Research: standard Java approaches
- [x] Research: does Jackson deserialization constrain the solution?
- [x] Experiment: read compact constructor for null/validation constraints
- [x] Experiment: simulate adding a field — count compilation errors
- [x] Experiment: prototype annotation-processor builder on one record
- [x] Experiment: verify generated code works with Spotless/PMD
- [x] Experiment: verify builder works with Jackson serialization
- [x] Decide: migration pattern for `KnowledgeSearchResponse`
- [x] Implement: add record-builder dependency to `app-api`
- [x] Implement: annotate `KnowledgeSearchResponse` with `@RecordBuilder`
- [x] Implement: migrate test callsites to use builder (7/7 test, 0/1 prod)
- [x] Implement: annotate P1/P2 records (MigrationGenerationView, EnrichmentProgressView, WorkerDebugView)
- [x] Implement: update verification-metadata.xml and lockfiles

### Part B: Schema regeneration

- [x] Audit: map all regeneration commands, their flags, and output paths
- [x] Experiment: run current regeneration commands, verify they work
- [x] Experiment: check -P vs -D flag reason in build.gradle.kts
- [x] Experiment: prototype unified Gradle task
- [x] Experiment: verify cross-language fixture path resolution
- [x] Experiment: check what "manual contract file update" entails
- [x] Implement: unified `updateSchemas` task in app-api/build.gradle.kts
- [x] Implement: normalize flag to single `updateSchemas` system property
- [x] Implement: update error messages to point to unified command
- [x] Document: intended developer workflow when adding a field

### Part C: Enforcement and durability

- [x] Implement: ArchUnit rule flagging positional construction of
      @RecordBuilder-annotated records (explicit type list, precise
      prefix-based builder exclusion)
- [x] Fix: clarify workflow doc scope (which records have builders)
- [x] Implement: CI fixture freshness check (`updateSchemas` + `git diff --exit-code`)
- [x] Implement: controller HashMap contract test (record components vs mapped keys)
- [x] Implement: annotate WorkerOperationalView + migrate callsites (6 callsites, 3 modules)
- [x] Implement: annotate KnowledgeStatusView (28 params, from() factory + test fixture)
- [x] Fix: restore dropped buildStamp field lost in merge conflict resolution
- [x] Fix: ArchUnit exclusion predicate (replace `contains("Builder")` with
      precise prefix match using actual generated class name pattern)
- [x] Fix: regenerate llms.txt after doc changes
- [x] Document: @RecordBuilder convention in agent-guide.md §3.3 (P3)
- [x] Fix: add `pipelineExecution` to Zod SearchResponseSchema
      (`queryUnderstanding` and `filterNormalization` not yet consumed
      by frontend mapper — outside Zod schema scope until mapper adds them)

### Records priority (from experiments)

| Record | Params | Callsites | Modules | Priority |
|--------|--------|-----------|---------|----------|
| `KnowledgeSearchResponse` | 25 | 8 | 3 (app-api, app-services, app-agent) | P0 |
| `MigrationGenerationView` | 19 | 5 | 3 (app-api, app-services, ui) | P1 |
| `EnrichmentProgressView` | 18 | 4 | 2 (app-api, app-services) | P1 |
| `WorkerDebugView` | 23 | 2 | 2 (app-api, app-services) | P2 |
| `KnowledgeStatus` | 22 | 2 | 1 (app-services only) | P3 — contained |
| `ResolvedConfig.*` | 24-34 | 1 each | 1 (configuration) | Skip — already centralized in builder |

## Follow-up opportunities

Investigated observations, prioritized by long-term value.

### P0: CI-enforced fixture freshness — do immediately

The `updateSchemas` task works but is a manual step. A CI step that
runs `updateSchemas` then `git diff --exit-code` on fixture files
would make it impossible to merge stale fixtures. This is ~5 lines
of YAML in `ci.yml`. Industry standard for schema-as-code. Highest
ROI follow-up — trivial effort, eliminates an entire class of drift.

### P1: Controller HashMap contract test — next tempdoc

`KnowledgeSearchController.handleSearch()` manually maps each record
field into a `HashMap` with custom filtering logic:
- `isBlank()` on strings (stricter than Jackson `NON_EMPTY`)
- `Boolean.TRUE.equals()` on `correctionApplied`/`expansionApplied`
  (omits `false`, not just `null`)
- `facetsTruncated` coupled to `facets` presence

Currently all 26 fields are mapped (no drift). But each new field
requires a manual `if/put` block — forgetting it fails silently.

**Investigated alternatives:**
- `@JsonInclude(NON_EMPTY)`: Cannot replicate the three custom
  behaviors above without custom serializers. Trades one burden for
  another. **Not recommended.**
- Reflection-based mapping: Adds complexity, loses explicit control.
  **Not recommended.**
- **Contract test** comparing record component names against mapped
  keys: Catches omissions at test time without changing serialization.
  **Recommended.** Moderate effort, high safety payoff.

The HashMap pattern also exists in 7+ other controllers
(`PreviewController`, `InferenceHandlers`, `RetrieveContextController`,
`SummaryController`, `RagStreamingHandler`, `FullCoverageSummarizer`,
`StatusLifecycleHandler`). The contract test pattern could be
generalized.

### P2: Annotate WorkerOperationalView and KnowledgeStatusView

**WorkerOperationalView**: 13 params, 6 callsites, 3 modules
(app-api, app-services, ui). Same coupling pattern as the records
we fixed. Will cause pain when a field is added.

**KnowledgeStatusView**: 28 params, 2 callsites, single module.
Largest unannotated record. Low coupling risk (single module) but
28 positional params is extreme.

Other unannotated records (`CoreIndexView` 6 params,
`CompatibilityStatusView` 9 params) are small enough that builders
add more noise than value. Not recommended.

### P3: Document convention instead of blanket policy

29 records have 10+ params but most are single-module or have few
callsites. A blanket `@RecordBuilder` policy adds annotation noise
to stable records. Better as a documented convention in agent-guide:
"Records in `app-api` with >10 params that are constructed
cross-module should have `@RecordBuilder`. Add when you first
encounter coupling pain, not preemptively."

### Resolved / not worth pursuing

**Flaky timestamp assertions**: Only one additional case found
(`TelemetryHealthStateTest` uses `before/after` window). Low-risk —
the window pattern is robust unless under extreme load. The
`ConfigManagerBootstrapTest` fix was the only real offender.
Best practice is `Clock` injection but that requires broad refactoring.
Fix only if it flakes.

**`ui:integrationTest` failure**: `SchemaMismatchStatusContractTest`
is already `@DisabledIfEnvironmentVariable(named = "CI")`. It fails
locally because it spawns a real Worker process. The `build -x test`
command still runs integration tests via `check` task dependency —
that's a build harness issue (separating `check` from
`integrationTest`), not a test quality issue. Low priority.

**Redundant `empty()` factory methods**: Keep them. They serve as
readable documentation of "what does an empty instance look like?"
and hide required non-null sub-object construction. Removing them
would make call sites longer. Not worth changing.

## Unconsidered risks (investigated)

### Builder usage enforcement (drift risk) — VALIDATED

**Problem:** The positional canonical constructor is still public.
Nothing prevents future code from using `new KnowledgeSearchResponse(...)`.

**Investigation result:** `@RecordBuilder` has `@Retention(SOURCE)` —
stripped from bytecode. ArchUnit reads bytecode, so
`isAnnotatedWith(RecordBuilder.class)` cannot work.

**Two workable approaches:**
- **Explicit type list** (Option A): Enumerate the 6 record names
  in a `Set<String>`, flag constructor calls targeting them, exclude
  generated builders via `endsWith("Builder")`. Proven pattern —
  `IndexWriterOwnershipTest` already does this for `IndexWriter`.
- **Custom runtime marker** (Option C): Create `@EnforceBuilderUsage`
  with `@Retention(RUNTIME)`, apply alongside `@RecordBuilder`.
  ArchUnit can detect it. Cleaner long-term, self-documenting.

Both exclude the builder's own `build()` call via class name filter.
**Recommendation:** Option A for simplicity — the list is short and
changes rarely.

### Fixture vs API shape divergence — NO BUG, but sloppy

**Investigation result:** No runtime crash risk. The frontend uses
`validateWithFallback` → `safeParse()` → fails open (logs warning
in dev, silent in prod).

The Zod `SearchResponseSchema` is **incomplete** — `queryUnderstanding`,
`filterNormalization`, and `pipelineExecution` are absent from it.
The schema uses `.loose()` (passthrough), so unknown keys are accepted
silently. The mapper drops `queryUnderstanding` and `filterNormalization`
entirely — the frontend never accesses these fields.

The fixture includes `"queryUnderstanding": null` and
`"filterNormalization": null`, but the mapper ignores them and the
Zod schema doesn't validate them. **Harmless but misleading.**

The Zod schema should eventually add these fields as `.nullish()` for
explicit frontend type awareness, even if unused today. Low priority.

### Controller HashMap as merge conflict hotspot — irreducible

With parallel agents, both adding fields will create adjacent `if/put`
blocks in the controller that conflict. Builders help on the record
construction side but not here. The P1 contract test catches omissions
but doesn't prevent merge conflicts. This tension is irreducible unless
the HashMap is eliminated — which is not recommended (it has 3 custom
filtering behaviors that `@JsonInclude` cannot replicate).

### Documentation gaps in workflow doc — should fix

The workflow says "test callsites need no update." This is only true
for `@RecordBuilder`-annotated records. The production `Hit` callsite
in `KnowledgeHttpApiAdapter` is still positional (deliberate).
`KnowledgeStatus` (21 params, 2 callsites) was never migrated.
The doc should clarify which records have builders.

### Request-side records — awareness only

`KnowledgeSearchRequest` (13 params) has the same structural problem.
Not addressed because the 366 pain was on the response side. Low
priority until the request record grows.

### Post-merge builder regeneration — VERIFIED, no issue

After merging, the annotation processor correctly regenerated
`KnowledgeSearchResponseBuilder` to include `filterNormalization`
(51 occurrences in the generated source). Automatic during
compilation — no manual step needed.
