---
title: "243: Consolidate System.getProperty calls to EnvRegistry"
---

# 243: Consolidate System.getProperty calls to EnvRegistry

**Status:** Complete
**Parent:** tempdoc 238 (F5)
**Goal:** Convert direct `System.getProperty("justsearch.*")` calls across production files to
their `EnvRegistry` equivalents, then add a build-time rule preventing recurrence.

## What was done

### Part A — 8 new EnvRegistry entries added

`modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java`:
- `ONNXRUNTIME_VARIANT_ID` (`justsearch.onnxruntime.variantId` / `JUSTSEARCH_ONNXRUNTIME_VARIANT_ID`)
- `SEARCH_PIPELINE` (`justsearch.search.pipeline` / `JUSTSEARCH_SEARCH_PIPELINE`)
- `TRANSLATOR_REPO_ROOT` (`justsearch.translator.repoRoot` / `JUSTSEARCH_TRANSLATOR_REPO_ROOT`)
- `UI_AUTOMATION_ENABLED` (`justsearch.ui.automation.enabled` / `JUSTSEARCH_UI_AUTOMATION`)
- `UI_AUTOMATION_REQUIRE_TRANSLATOR` (`justsearch.ui.automation.requireTranslator` / `JUSTSEARCH_UI_AUTOMATION_REQUIRE_TRANSLATOR`)
- `UI_AUTOMATION_FORCE_DIAGNOSTICS` (`justsearch.ui.automation.forceDiagnostics` / `JUSTSEARCH_UI_AUTOMATION_FORCE_DIAGNOSTICS`)
- `UI_SETTINGS_MODE` (`justsearch.ui.settings.mode` / `JUSTSEARCH_UI_SETTINGS_MODE`)
- `SERVER_EXE_SOURCE` (`justsearch.server.exe.source` / `JUSTSEARCH_SERVER_EXE_SOURCE`)

### Part B — 16 production files updated (34 call sites)

All direct `System.getProperty("justsearch.*")` calls replaced with `EnvRegistry` equivalents.
Also removed the now-unused `chooseFlag` and `chooseFirstNonBlank` private helpers.

Files changed: `LlamaServerOps`, `InferenceLifecycleManager`, `WorkerSpawner`,
`AppFacadeBootstrap`, `SmokeDriver`, `LauncherEnvironment`, `LauncherBootstrap`, `Launcher`,
`TranslatorAssets`, `HeadlessApp`, `UiSettingsStore`, `RuntimeActivationService`,
`BudgetProfiles`, `PlatformPaths` (annotation only), `OnnxModelDiscovery`.

**Special cases documented in code:**
- `LauncherEnvironment:66` — save/restore safety: uses `System.getProperty(EnvRegistry.CONFIG_PATH.sysProp())` (sysprop-only check to avoid env-var leakage on `close()`)
- `HeadlessApp:harmonizeDataDirProperties` — logback propagation safety: uses `System.getProperty(EnvRegistry.DATA_DIR.sysProp())` (sysprop-only check; `.get()` would skip `setProperty` when only the env var is set)
- 4 legacy `"justsearch.data_dir"` (underscore) calls annotated `// SYS-PROP-LEGACY-COMPAT` in `LauncherBootstrap`, `Launcher`, `HeadlessApp`, `PlatformPaths`

**Telemetry module exempt:** `LayeringEnforcementTest` bans `io.justsearch.telemetry..` from
depending on `io.justsearch.configuration..`. The 4 calls in `CrashReporter`, `LocalTelemetry`,
`NdjsonMetricExporter` are intentionally left as-is.

### Part C — Gradle enforcement task added

`build.gradle.kts`: `checkNoDirectJustsearchSysProp` task scans `modules/**/src/main/**/*.java`,
excludes `modules/telemetry/**` and lines tagged `SYS-PROP-LEGACY-COMPAT`. Wired into
`:check` so it runs with `./gradlew build`, `./gradlew check`, and `./gradlew quality`.

## Verified

- `./gradlew build -x integrationTest` — BUILD SUCCESSFUL
- `./gradlew checkNoDirectJustsearchSysProp` — BUILD SUCCESSFUL (zero violations)
- All affected module unit tests pass
