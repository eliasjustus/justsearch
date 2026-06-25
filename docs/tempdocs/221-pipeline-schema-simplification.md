---
title: "Tempdoc 221 — Pipeline Infrastructure Cleanup (Post Engine Deletion)"
---

# Tempdoc 221 — Pipeline Infrastructure Cleanup (Post Engine Deletion)

**Status**: done
**Date**: 2026-02-19
**Origin**: Follow-on from tempdoc 214-I6 (pipeline engine deletion). Scope expanded after
post-implementation critical review identified two further structural layers needing cleanup.

---

## Background

`modules/pipeline-engine` and `modules/pipeline-executor` were deleted in commit `12f12215`.
The deletion removed the execution logic but left behind engine-era infrastructure at three
distinct layers, each requiring a separate pass to clean up:

| Layer | What was left behind | Status |
|-------|---------------------|--------|
| Schema fields | Dead execution-engine fields on `StageDefinition`, `PipelineDefinition`, `EdgeDefinition` | ✅ Phase 1 done |
| Wrapper classes | `SearchPipeline` and `IndexingPipeline` — typed dispatch shells for a nonexistent dispatcher | ✅ Phase 2 done |
| Module boundary | `pipeline-schema` as a standalone Gradle module, questioned after shrinking to ~150 LOC | ✅ Phase 3 done (no action) |

The phases flow inside-out: fields must be clean before wrapper scope can be assessed,
and the module boundary can only be decided once the wrappers are resolved.

---

## Phase 1 — Schema simplification ✅

**Commit**: `ea3137e3`

`StageDefinition` carried 11 fields; only `id` and `budgetMs` were read by live code.
`PipelineDefinition` carried `defaultOnFailure` and `withoutStageBudgets()` (simulation
utility). `EdgeDefinition` carried `capacity`. `StageRetryPolicy` existed solely to serve
the retry fields on `StageDefinition`. `IndexingPipelineValidator` validated `StageType`
role-based edge rules (SOURCE/TRANSFORM/SINK) that only made sense with an executor.

### What was removed

| Artifact | Change |
|----------|--------|
| `StageDefinition` | Reduced from 11 fields to 2: `id`, `budgetMs` |
| `StageRetryPolicy` | Deleted |
| `PipelineDefinition.defaultOnFailure` | Removed |
| `PipelineDefinition.withoutStageBudgets()` | Removed |
| `EdgeDefinition.capacity` | Removed; now a 2-field record |
| `PipelineValidator` | Removed failure graph, BFS reachability, retry/idempotent checks |
| `IndexingPipelineValidator` | Removed StageType role validation; now delegates + kind check |
| `StageType` (indexing module) | Deleted |
| SSOT JSON artifacts | Removed `type`, `parallelism`, `budget_policy`, `optional`, `on_failure`, `capacity`, `default_on_failure`; new `pipeline_hash` values computed |

### What was kept

`PipelineValidator` retains: Kahn's cycle detection, edge reference validation, isolated
stage check, source/sink existence, budget-exceeds-pipeline check.

### Post-implementation review findings

A critical review after commit `ea3137e3` raised four concerns, all resolved:

- **Smoke baselines pinning `pipeline_hash`**: No source file in `scripts/` pins a hash
  value. Only stale `build/reports/simulate/` outputs contain old hashes; these are
  regenerated on each `Launcher simulate` run.

- **`edges()` on SearchPipeline / IndexingPipeline wrappers**: Callers confirmed —
  `SearchPipelineTest` and `IndexingPipelineLoaderTest` use them to assert loaded edge
  content. Wrapper delegation is legitimately used by tests. No change needed.

- **ArchUnit freeze store**: Verified correct. `searchFullDocsWithMeta in RagContextOps`
  (new violation, added correctly) and `telemetry in Stage` (retained correctly —
  `Stage` is `SummaryWorkflowDefinition.Stage` in ai-bridge, not the deleted engine
  `Stage`) both present. `archunit.properties` correctly restored to
  `allowStoreCreation=false, allowStoreUpdate=false`.

- **`RemoteKnowledgeClientMsToSecondsTest` failures**: Stale disk artifact. A parallel
  agent had already deleted the file from git in commit `3e079331`. `git rm` cleaned the
  filesystem copy; full unit suite now passes cleanly.

---

## Phase 2 — Wrapper class cleanup ✅

`SearchPipeline` and `IndexingPipeline` are typed wrappers that existed so the engine
could dispatch by pipeline kind. The engine is gone; the wrappers remain.

### 2A — Dissolve `SearchPipeline`

**File**: `modules/search/src/main/java/io/justsearch/search/pipeline/SearchPipeline.java`

Every method on `SearchPipeline` is a pure delegation to `PipelineDefinition`:

| Method | Delegation |
|--------|-----------|
| `name()` | `definition.name()` |
| `version()` | `definition.version()` |
| `kind()` | `definition.kind().id()` |
| `budgetMs()` | `definition.budgetMs()` |
| `concurrency()` | `definition.concurrency()` |
| `stages()` | `definition.stages()` |
| `stage(id)` | `definition.stage(id)` |
| `edges()` | `definition.edges()` |
| `budgetProfile()` | `definition.budgetProfile()` |
| `dagHash()` | `definition.dagHash()` |

**Production callers** (2):
- `CapabilitiesService` — calls `stages()`, `name()`, `dagHash()`, `budgetProfile()`,
  `budgetMs()`, `concurrency()`; all pure delegations
- `SmokeDriver` — stores pipeline as a field, passes to `budgetResolver.resolve(pipeline)`

**Implementation**: replace `SearchPipeline` with `PipelineDefinition` at all call sites.
Update `SearchPipelineLoader` return type accordingly. Update `SearchPipelineTest` and
`SmokeDriverTest` to construct `PipelineDefinition` directly.

**Risk**: Low. Purely mechanical; no behavior change.

### 2B — `IndexingPipeline`: decide on `writerSettings()`

**File**: `modules/indexing/src/main/java/io/justsearch/indexing/pipeline/IndexingPipeline.java`

Unlike `SearchPipeline`, `IndexingPipeline` carries domain-specific state beyond what
`PipelineDefinition` holds: `writerSettings()`, an `Optional<WriterSettings>` record
(SoftDeletes/Retention config) parsed from the `writer` block in the SSOT JSON.

```java
public record WriterSettings(SoftDeletes softDeletes) { }
public record SoftDeletes(String field, Retention retention) { }
public record Retention(boolean enabled, Integer days) { }
```

**Production callers of `writerSettings()`**: none. Only read in tests
(`IndexingPipelineLoaderTest`, `IndexingPipelineTest`).

**Production callers of the wrapper itself** (3):
- `IndexingLoop` — calls `name()`, `dagHash()`, `budgetProfile()` (all delegations)
- `WorkerConfig` — calls `budgetMs()`, `budgetProfile()` (all delegations)
- `IndexingPipelineValidator` — calls `definition()` to unwrap

**Decision required** before implementing: is `writerSettings()` forward-looking
infrastructure (will be wired to `IndexerWorker` configuration), or is it also dead code
from the engine era that should be deleted?

- If **dead**: delete `WriterSettings`, `SoftDeletes`, `Retention`; remove the `writer`
  block from the SSOT JSON source files and re-resolve; then dissolve `IndexingPipeline`
  the same way as `SearchPipeline`.
- If **live-intended**: keep `IndexingPipeline` as a named bundle of
  `(PipelineDefinition, WriterSettings)`, but rename it to make the bundling purpose
  explicit (e.g., `IndexingPipelineBundle` or leave as `IndexingPipeline` with a
  clarifying comment). Dissolving the wrapper would mean threading `WriterSettings`
  separately through callers, which is more churn for no gain.

**Decision made**: `writerSettings()` is dead code. The `writer` block was never present
in `indexing.v1.json` and `writerSettings()` has zero production callers. Lucene soft
deletes are wired independently via `RuntimeConfig` / `application.yaml`. Both
`WriterSettings` and `IndexingPipeline` were deleted.

**Implementation** (commit `fa43f988`):
- `SearchPipelineLoader`: return type `SearchPipeline` → `PipelineDefinition`
- `CapabilitiesService`, `SmokeDriver`, `SmokeDriverTest`: updated to `PipelineDefinition`
- `IndexingPipelineLoader`: fully rewritten as thin loader, drops Jackson/WriterSettings parsing
- `WorkerConfig`, `IndexingLoop`: updated to `PipelineDefinition`
- Deleted: `SearchPipeline.java`, `SearchPipelineTest.java`, `IndexingPipeline.java`,
  `IndexingPipelineValidator.java`, `IndexingPipelineTest.java`, `IndexingPipelineValidatorTest.java`
- `IndexingPipelineLoaderTest`: removed writer-settings tests; fixed `kind()` assertion
  from `assertEquals("indexing", ...)` to `assertEquals(PipelineKind.INDEXING, ...)`

**Post-implementation review** raised three concerns; one required a follow-up fix:
- `SmokeDriver` line 151 stale reference: caught by compiler during Phase 2B, fixed before
  any commit landed. No separate commit.
- `IndexingPipelineLoaderTest` full-rewrite risk: result verified correct, no action.
- `indexer-worker` direct dependency on `pipeline-schema` undeclared: `indexing` uses
  `api(pipeline-schema)` so it was stable, but explicit declaration added for clarity.
  Commit `3cb642ac` added `implementation(project(":modules:pipeline-schema"))` to
  `indexer-worker/build.gradle.kts`.

---

## Phase 3 — Module fate ✅

**Verdict**: keep `pipeline-schema` as a standalone Gradle module. No action required.

**Investigation summary**:

`pipeline-schema` post-simplification: 6 files, ~450 LOC, package `io.justsearch.pipeline`.
Dependents: `modules/indexing`, `modules/search`, `modules/app-launcher`,
`modules/app-observability`.

Options evaluated:

- **Merge into `modules/configuration`** — rejected. Configuration owns budget profiles,
  embedding model resolution, and system config loading — a different semantic space.
  Merging would cause thematic bloat and a misleading module name.

- **Dissolve into `modules/indexing`** — rejected. Would create a `search → indexing`
  module dependency just to access shared types (`PipelineDefinition`, `PipelineLoader`,
  `PipelineValidator`). An unusual and confusing coupling direction.

- **Keep as standalone** — correct. A small, focused shared-schema module is a legitimate
  Gradle module purpose. Post-simplification its name and scope are accurate. The LOC
  count is not a problem in itself.

---

---

## Phase 1B — Search-module engine-era remnants ✅

**Commit**: `e3d36557`

Post-closure review found three engine-era artifacts in the `search` module and one in
`adapters-lucene` that Phase 1 missed — structurally identical to what Phase 1 cleaned up
in the `indexing` module.

| Artifact | Action |
|----------|--------|
| `SearchStageType` (search module) | Deleted — direct analog of the deleted `StageType` in `indexing`; `type` field removed from SSOT in Phase 1, zero production callers |
| `SearchStageRole` (search module) | Deleted — SOURCE/TRANSFORM/SINK enum only used by `SearchStageType` |
| `SearchStageTypeTest` (search module) | Deleted — tests the deleted enum |
| `RuntimeConfig.pipelineBuiltinStageTypes()` + `DEFAULT_BUILTIN_STAGE_TYPES` | Deleted — Javadoc said "recognized by the pipeline executor" (deleted); zero callers; `HashSet` import removed |

---

---

## Phase 1C — Indexing `StageRole` and stale doc references ✅

**Commit**: `c225b37d`

A second post-closure scan found one more dead code artifact and several stale documentation
references, all engine-era remnants missed by Phase 1 and Phase 1B.

| Artifact | Action |
|----------|--------|
| `StageRole.java` (indexing module) | Deleted — SOURCE/TRANSFORM/SINK enum, zero callers, mirror of `SearchStageRole` deleted in Phase 1B |
| `runtime-config-ownership-matrix.md` | Removed 4 dead `pipeline.builtin_stage_types.*` rows (owning method deleted in Phase 1B) |
| `modules/indexing/README.md` | Removed `IndexingPipeline`, `IndexingPipelineValidator`, `StageRole`/`StageType` rows; updated deps from `pipeline-engine`/`pipeline-executor` → `pipeline-schema` |
| `modules/search/README.md` | Rewrote to reflect 2 live classes (`SearchPipelineLoader`, `SearchHitMetadata`); removed 5 deleted classes and `pipeline-engine` dep |
| `modules/app-services/README.md` | Replaced deleted `IndexingPipelineExecutor` in flow diagram with `gRPC → IndexerWorker/IndexingLoop` |

---

## Remaining work

None. All phases complete. Tempdoc closed.
