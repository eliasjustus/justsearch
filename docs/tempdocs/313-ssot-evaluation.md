---
title: "SSOT Evaluation: Keep, Remove, or Restructure"
status: done
created: 2026-03-16
---

# 313 — SSOT Evaluation: Keep, Remove, or Restructure

## Goal

Critically evaluate every component of the `SSOT/` directory and determine what to keep, remove, or restructure. Produce a concrete action plan with prioritized steps.

## Completed Work

### Phase 1: Remove Dead Pipeline Infrastructure — DONE

Removed all pipeline definition infrastructure (ADR 0014):
- [x] Pipeline definitions, budget profiles, resolved artifacts, resolve-pipeline.mjs
- [x] `modules/pipeline-schema` (PipelineDefinition, PipelineLoader, PipelineValidator, etc.)
- [x] DagHashingService, IndexingPipelineLoader, SearchPipelineLoader
- [x] BudgetProfile, BudgetProfiles, StageBudget
- [x] `dag_hash` and `pipeline_budget_profile` from commit metadata, validator, parity diagnostics
- [x] `simulate` command from Launcher
- [x] Pipeline references from WorkerConfig, IndexingLoop, CapabilitiesService, SmokeDriver
- [x] All Gradle tasks (ssotResolvePipeline, simulateSearch, simulateIndexing)
- [x] Updated commit-metadata.schema.json
- [x] All tests adapted; build + unit tests pass

### Phase 2: Migrate Node.js Toolchain to Java — DONE

Replaced all SSOT Node.js scripts with Java equivalents in `modules/ssot-tools`:
- [x] `SynonymsCompiler.java` replaces `synonyms-compile.mjs` — line normalization + deterministic placeholder FST output
- [x] `GbnfGenerator.java` replaces `generate-gbnf.mjs` — hardcoded GBNF grammar + provenance manifest
- [x] `SsotValidator.java` replaces `validate.mjs` — schema validation (networknt 3.0.1, Draft 2020-12), analyzer fingerprint verification, reason-code uniqueness, repro manifest generation
- [x] Recomputed analyzer fingerprints using Jackson canonicalization (was JCS/RFC 8785 via Node `canonicalize` library)
- [x] Updated `field-catalog.schema.json` — added `splade` type and `multiValued` property to match current catalog
- [x] All SSOT Gradle tasks now use `JavaExec` with `ssotToolsCp` configuration
- [x] `ssotDriftCheck` replaced NpmTask with plain `Exec` (git diff)
- [x] `ssotGoldensValidate` removed (golden validation folded into `ssotValidateExec`)
- [x] Deleted `SSOT/tools/` directory entirely (5 .mjs scripts, package.json, .nvmrc)
- [x] Verified: `./gradlew ssotGenerate ssotValidateExec ssotChecks` all pass

**Not removed:** `NodeScriptTask` and node-gradle plugin remain for 2 docs linting tasks (`docsLintEllipses`, `docsApiDriftCheck`) which are outside SSOT scope.

### Phase 2b: Fix Phase 2 Implementation Issues — DONE

- [x] **Golden intent validation restored** — added `validateGoldenIntents()` to `SsotValidator` (4 records validated against `search-intent.schema.json`)
- [x] **Unit tests added** — `SynonymsCompilerTest` (normalization), `GbnfGeneratorTest` (output files), `SsotValidatorFingerprintTest` (pinned-hash guard against Jackson drift)
- [x] **Canonicalization documented** — comment in `SsotValidator.java` + updated ADR 0013 fingerprinting section to document Jackson sorted-key JSON (not JCS/RFC 8785)
- [x] **`repro.v1.json` regenerated** — hashes updated to match Jackson canonicalization
- [x] **Field catalog schema verified** — `multiValued` and `splade` confirmed as legitimate runtime fields (`FieldMapper`)

### Phase 3: Merge ADR Spaces — DONE

- [x] Migrated SSOT/ADRs/0002 → `docs/decisions/0011-distributed-readiness-spi.md`
- [x] Migrated SSOT/ADRs/0003 → `docs/decisions/0012-ui-stack-and-doc-tooling.md`
- [x] Migrated SSOT/ADRs/0005 → `docs/decisions/0013-synonyms-fst-placeholder.md`
- [x] Migrated SSOT/ADRs/0007 → `docs/decisions/0014-pipeline-definition-removal.md` (updated to cover full removal)
- [x] Deleted SSOT/ADRs/0004 (superseded bootstrap) and 0006 (superseded by 0007) outright
- [x] Deleted `SSOT/ADRs/` directory
- [x] Updated `.claude/rules/deprecated-modules.md`

### Phase 4: Schema and Manifest Cleanup — DONE

Audited all 25+ schema files and 4 manifests. Removed 17 orphaned schemas, pruned stale manifest sections.

**Removed (no consumers):**
- [x] `schemas/pipelines/` — all 5 files (dsl, stage, indexing, search, budget-profile) — orphaned after pipeline removal
- [x] `schemas/plugins/` — both files (manifest, stage-plugin) — never wired
- [x] `schemas/automation/` — both files (runner-evidence-manifest, runner-summary) — no consumers
- [x] `schemas/common/` — both files (deadline, error) — no `$ref` from any active schema
- [x] `schemas/telemetry/` — 7 of 8 files (kept only `log.schema.json` used by `LoggingRedactionGoldenTest`)
- [x] `schemas/genai-semconv.mapping.json` — no consumers
- [x] `schemas/config/policy.v1.schema.json` — `EnterprisePolicyService` uses Jackson, not schema validation

**Pruned:** `SSOT/manifest.v1.json` — removed stale `pipelines`, `budget_profiles`, `plugin_manifests` sections. Kept `catalogs`, `config`, `secrets`, `prompts`, `versions_catalog`.

**Kept (15 files):** All have runtime or active build-time consumers.

**Noted for future:** `capabilities-view.schema.json` is documentation-only (Javadoc reference, no validation). Consider wiring into a test.

### Phase 5: Catalog and Miscellaneous — DONE

Audited all remaining SSOT catalogs and miscellaneous directories.

**Removed (dead):**
- [x] `catalogs/design-tokens.v1.json` — W3C design tokens catalog, never consumed. Frontend uses hand-authored `tokens.css`. ADR 0003 proposed a token pipeline that was never built.
- [x] `samples/` directory (`docs.json`, `queries.json`) — planned for a simulate CLI that was deleted with the pipeline engine. No consumer.
- [x] `docs/help/` directory (5 Markdown files) — frontend hardcodes its own help content inline. These files were the source material but are not loaded at build or runtime.

**Verified as live (kept):**
- [x] `catalogs/secrets.v1.json` — actively loaded at runtime by `SecretsVault` via `manifest.v1.json` for bundle allowlisting.
- [x] `versions/catalog.json` — actively read at runtime by `SsotCommitMetadataSource`, `AiClientConfig`, and AI worker `WorkerConfig` for intent schema/grammar version coordination. No pipeline-related data remains.

### Phase 7: Remove Unenforced Artifacts — DONE

- [x] `catalogs/reason-codes.v1.json` + `schemas/catalogs/reason-codes.schema.json` — deleted. Was documentation-only; Java code hardcodes reason codes as string constants. The catalog validated itself but never enforced anything on the code.
- [x] `manifests/backend-capabilities.v1.json` + `schemas/manifests/backend-capabilities.schema.json` + `BackendManifestGenerator.java` — deleted. Write-only artifact with no runtime reader.
- [x] `manifests/plugin.justsearch.v1.json` + `schemas/config/plugin-manifest.schema.json` — deleted. Forward declaration for a plugin system that was deleted with the pipeline engine.
- [x] Removed `validateReasonCodes()` and `validatePluginManifest()` from `SsotValidator`
- [x] Pruned `manifest.v1.json` `reason_codes` entry
- [x] Removed `backendCapabilityManifest` Gradle task from `ai-bridge/build.gradle.kts`

### Phase 6: Remove Node.js from Build — DONE

Migrated 3 docs linting scripts from Node.js to Java and removed all Node.js build infrastructure.

**Ported to `modules/ssot-tools`:**
- [x] `DocsApiDriftCheck.java` — replaces `docs-api-drift-check.mjs` (scan canonical docs for banned endpoints)
- [x] `DocsNoncanonicalEndpointsWarn.java` — replaces `noncanonical-removed-endpoints-warn.mjs` (warn-only scan of tempdocs)
- [x] `DocsTempdocStatusCheck.java` — replaces `tempdocs-status-warn.mjs` (validate YAML frontmatter status values)

**Removed:**
- [x] `NodeScriptTask` and `NpmTask` classes from `build-logic/src/main/kotlin/conventions/SsotTasks.kt`
- [x] `com.github.node-gradle.node` plugin from `build.gradle.kts` and `libs.versions.toml`
- [x] `node { ... }` configuration block
- [x] Broken `docsLintEllipses` task (script didn't exist)
- [x] 3 Node.js scripts from `scripts/docs/`

**Fixed:** Added `jackson-databind` to `build-logic` dependencies — the node-gradle plugin was transitively providing it to the `modules/ui/build.gradle.kts` script classpath.

**Kept:** `NpmBuildTask`/`NpmInstallTask` in `build-logic/src/main/kotlin/conventions/NpmTasks.kt` — these are for the frontend (ui-web) npm build, not SSOT or docs linting. `StylelintTask.kt` also stays (frontend CSS linting).

### Deferred item resolved: capabilities-view schema wired into test

- [x] Updated `capabilities-view.schema.json` to match current `CapabilitiesView` record (removed pipeline/budget/tasks fields)
- [x] Added `capabilitiesPayloadMatchesSchema()` test in `CapabilitiesServiceTest` — validates serialized JSON against the SSOT schema using networknt Draft 2020-12

All tempdoc items complete. This tempdoc is ready for `done` status after merge.
