---
title: "246: Eval Infrastructure Centralization"
type: tempdoc
status: done
created: 2026-02-28
---

> NOTE: Noncanonical doc (analysis + strategy). May drift.

# 246: Eval Infrastructure Centralization

## Purpose

Document the systemic infrastructure issues discovered during the tempdoc 245
component isolation experiments, and propose centralization across all eval and
benchmarking lanes to prevent recurrence.

This tempdoc covers three scopes:

1. **Specific bugs** encountered and their root causes in the config resolution
   chain and API contracts (§1–§2).
2. **Cross-lane analysis** of duplicated infrastructure patterns, deep code
   forensics, and prior consolidation work (§3–§8).
3. **Ideal state architecture** — a research-backed three-pillar design
   (ResolvedConfig, typed status records, EvalSession) with risk assessment
   and migration strategy (§10, Appendices C–D).

---

## 1. Issues Encountered During Isolation Experiments

### 1.1 Settings.json silently overrides explicit env vars

**Symptom:** Server used `modules/ui-web/.dev-data/index/default` instead of
the BEIR data directory, despite `JUSTSEARCH_DATA_DIR` being set correctly.
All queries returned wrong documents (JustSearch's own docs, not SciFact).
BM25-only scored nDCG@10 = 0; dense-only crashed on missing fields.

**Root cause:** `UiSettingsStore.load()` always reads from disk, even in
`IN_MEMORY` mode (which only suppresses writes). The user's
`%APPDATA%/justsearch/ui/settings.json` contains a hardcoded `indexBasePath`.
`HeadlessApp` applies this value via `setSysPropIfBlankWithSource` before
`PlatformPaths.resolveDataDir()` runs, so the settings file wins over the
env var.

**Resolution chain for `indexBasePath` (highest to lowest precedence):**

1. `-Djustsearch.index.base_path` sysprop (JVM command line)
2. `JUSTSEARCH_INDEX_BASE_PATH` env var
3. `settings.json` `indexBasePath` field (via `HeadlessApp` →
   `setSysPropIfBlankWithSource` — only writes if sysprop not already set)
4. YAML `app.data_dir` (literal value in config profile)
5. `-Djustsearch.data.dir` / `JUSTSEARCH_DATA_DIR` (via `PlatformPaths`)
6. Platform default (`%LOCALAPPDATA%\JustSearch` on Windows)

The problem: layer 3 (settings.json) beats layer 5 (env var). Explicit env
vars should always beat implicit settings file values.

**Workaround applied:** Pass `-Djustsearch.index.base_path=...` and
`-Djustsearch.data.dir=...` via `UI_OPTS` to pre-set sysprops (layer 1),
preventing settings.json from winning.

**Affected scope:** Any eval lane that starts a server with a custom data
directory via env vars is vulnerable. Currently only `run-ranking-experiments.ps1`
has the workaround. `run-claim-b-suite`, `run-eval-autonomous-until`, and
`overnight-rag-ai-queue` would hit the same bug if they needed a custom data dir.

### 1.2 No "source won" logging in config resolution

**Symptom:** When the server used the wrong data directory, there was no log
line indicating which config source provided the value. Debugging required
reading `HeadlessApp.java`, `UiSettingsStore.java`, `PlatformPaths.java`, and
`RuntimeConfig.java` to trace the resolution chain.

**Root cause:** `EnvRegistry.get()` returns `Optional<String>` with no trace
of whether the value came from a system property or env var.
`PlatformPaths.resolveDataDir()` has no logging of which source won — only
warnings for the two deprecated legacy sysprops. The companion property
`justsearch.index.base_path.source` (set to `"ui_settings"` by HeadlessApp)
exists but is only populated for the settings.json path, not for env var or
sysprop wins.

**Impact:** Any config resolution bug requires source code reading to diagnose.
This is a 2-hour debugging session that should be a one-line log read.

### 1.3 Status API field name divergence

**Symptom:** `beir-eval-win.ps1` called `/api/knowledge/status` but accessed
`readiness.composites.retrieval` (which only exists on `/api/status`).
PowerShell's `Set-StrictMode -Version Latest` turned the missing property into
a terminating error.

**Root cause:** `/api/status` and `/api/knowledge/status` expose the same
underlying Worker data under different field names:

| Worker getter | `/api/status` field | `/api/knowledge/status` field |
|---------------|--------------------|-----------------------------|
| `getQueueDepth()` | `pendingJobs` | `queueDepth` |
| `getDocCount()` | `indexedDocuments` | `docCount` |
| `getActiveDocCount()` | `activeIndexedDocuments` | `activeDocCount` |
| `getBuildingDocCount()` | `buildingIndexedDocuments` | `buildingDocCount` |
| `getIsHealthy()` | `indexHealthy` | `healthy` |

The `/api/status` names come from `WorkerStatusMapper.toUiStatusMap()` (an
ad-hoc `LinkedHashMap` with no shared constants). The `/api/knowledge/status`
names come from the `KnowledgeStatus` record's field names. Neither references
the other. There are contract tests for `/api/status` response shape
(`LifecycleContractTest`) but none for `/api/knowledge/status`.

**Impact:** Any script that switches between endpoints (or is copy-pasted
from a script using the other endpoint) breaks silently until runtime.

### 1.4 PowerShell → bash → JVM env var propagation

**Symptom:** Three separate issues in the env var propagation chain:

1. `[System.Environment]::SetEnvironmentVariable()` vars don't reliably
   propagate to bash child processes via `Start-Process` on Windows.
2. `Start-Process -ArgumentList "-c", $cmd` concatenates with spaces, so
   bash receives `-c echo` instead of `-c "echo hello"`.
3. Even when env vars arrive in the JVM, the settings.json override (1.1)
   can nullify them.

**Root cause:** Crossing three process boundaries (PowerShell → cmd → bash →
JVM) on Windows, each with its own quoting and inheritance rules. No single
config propagation path is tested end-to-end.

**Workaround applied:** Inline env vars in the bash command string
(`VAR='val' exec script`) and pass critical config as `-D` system properties
via `UI_OPTS`.

---

## 2. Infrastructure Gaps

### 2.1 `UiSettingsStore.load()` should respect IN_MEMORY mode — Resolved

> **Fixed:** `load()` now returns `new UiSettings()` immediately when
> `mode == IN_MEMORY`, matching the `save()` guard. Option (b) was chosen.
> Three tests added to `UiSettingsStorePersistenceModeTest`: IN_MEMORY ignores
> file on disk, READ_WRITE reads from disk, IN_MEMORY handles missing file.

~~`IN_MEMORY` mode suppresses `save()` but `load()` still reads from disk.
This means startup always reads whatever `settings.json` exists on the
machine, including stale `indexBasePath` values.~~ Two options were considered:

- **(a)** `HeadlessApp` checks for explicit env var / sysprop overrides before
  applying settings.json values. If an explicit override exists, skip the
  settings value.
- **(b)** `load()` in `IN_MEMORY` mode returns empty defaults. If you chose
  in-memory, you don't want disk state.

Option (b) was implemented — semantically correct per ADR 0008's isolation
intent, and simpler than option (a).

### 2.2 Config resolution needs source tracing — Resolved (Pillar 3)

> `ConfigResolution` record + `/api/effective-config` endpoint provide full
> source tracing per key. `EnvRegistry` itself lacks `getWithSource()` but
> all consumers now read via `ConfigStore.get()` which carries provenance.

`PlatformPaths.resolveDataDir()` and `RuntimeConfig.indexBasePath()` should
log at INFO which source provided the value. `EnvRegistry` could offer a
`getWithSource()` returning `record ResolvedValue(String value, String source)`
so every consumer is automatically traceable.

### 2.3 Status field names need unification or contract testing — Resolved (Pillar 1)

> `StatusRecordSchemaTest` (13 tests) validates all view records against
> generated JSON Schema (victools). `KnowledgeStatusView` has canonical
> names + deprecated legacy accessors for backward compat.

Either:
- Unify field names across `/api/status` and `/api/knowledge/status` (breaking
  change for frontend consumers of `/api/knowledge/status`), or
- Add contract tests for `/api/knowledge/status` response shape (like
  `LifecycleContractTest` does for `/api/status`), and document the mapping.

### 2.4 Eval scripts should use `-D` properties, not env vars — Resolved (Pillar 2)

> `EvalSession.Start-EvalServer` uses `-UiOpts` for `-D` system properties,
> at ordinal 500 — guaranteed to win over settings.json at ordinal 300.

The JVM reads `-D` system properties reliably regardless of how the process
was started. `UI_OPTS` provides a clean single-hop propagation path
(PowerShell string → env var → JVM command line). Env vars should be the
fallback for interactive use, not the scripted path.

---

## 3. Cross-Lane Centralization Analysis

### 3.1 Current landscape

The eval infrastructure has ~50 scripts across `scripts/search/`,
`scripts/bench/`, `scripts/perf/`, and `scripts/ci/`. Major eval lanes:

| Lane | Key scripts | Server lifecycle |
|------|-------------|-----------------|
| BEIR search quality | `beir-eval-win.ps1`, `run-ranking-experiments.ps1` | Hand-rolled in `run-ranking-experiments` |
| Rank eval | `rank-eval-win.ps1` | Assumes server already running |
| Correction eval | `correction-eval-win.ps1` | Assumes server already running |
| Claim A (engine indexing) | `engine-index-bench-win.ps1`, `run-claim-a-suite-win.ps1` | No server (direct Lucene) |
| Claim B (pipeline indexing) | `pipeline-bench-win.ps1`, `run-claim-b-suite-win.ps1` | Inline `Start-DevRunner` |
| Claim D (LLM inference) | `llm-bench-win.ps1`, `run-claim-d-suite-win.ps1` | `benchmark-ci-common.ps1` |
| Track G (filtered kNN) | `filtered-knn-bench-win.ps1`, `run-track-g-suite-win.ps1` | `benchmark-ci-common.ps1` |
| RAG eval | `run-rag-context-format-comparison.ps1` | `benchmark-ci-common.ps1` |
| Agent battery | `agent-battery-core.mjs` | Own `waitReady()` in JS |
| Perf regression | `run-perf-suite-win.ps1` | `perf-suite-runtime-common.ps1` |
| Overnight autopilot | `overnight-benchmark-autopilot.mjs` | `dev-runner.cjs` lifecycle |

### 3.2 Seven independent server lifecycle implementations

Scripts that implement "start server → poll readiness → run eval → stop server":

1. `run-ranking-experiments.ps1` — `Start-ServerWithConfig` (Start-Process bash/Gradle)
2. `run-claim-b-suite-win.ps1` — inline `Start-DevRunner` (dev-runner.cjs)
3. `run-claim-d-suite-win.ps1` — `benchmark-ci-common.ps1` `Start-DevRunnerManaged`
4. `run-eval-autonomous-until.ps1` — inline `Start-Runner` (dev-runner.cjs)
5. `overnight-rag-ai-queue-win.ps1` — `benchmark-ci-common.ps1`
6. `agent-battery-core.mjs` — own `waitReady()` in JavaScript
7. `perf-suite-runtime-common.ps1` — `Wait-DevRunnerReady`

None share a common "start and wait" abstraction. A fix to server startup
(like the data directory workaround) must be applied to each independently.

### 3.3 Four readiness definitions

| Pattern | Used by |
|---------|---------|
| `status.status -eq "ok"` | Older PS1 scripts (claim-b, ranking-experiments) |
| `status.ok -eq $true` | Newer PS1 scripts |
| Either of the above (`-or`) | Hedging scripts (claim-d, llm-bench, benchmark-ci-common) |
| HTTP 200 alone | JS scripts (agent-battery, capture-nmt-snapshot) |

Some scripts additionally require `indexAvailable`, `worker.status == "UP"`,
or `readiness.ready_http`. These aren't just style differences — they answer
different questions ("is the HTTP server up?" vs "is the index serving?" vs
"is the full stack healthy?"). No shared function encodes which question each
eval lane should be asking.

### 3.4 Two shared libraries that don't compose

- `benchmark-ci-common.ps1` — `Wait-ApiReady`, `Start-DevRunnerManaged`.
  Used by Claim D, Track G, RAG eval, CI lane runners.
- `perf-suite-runtime-common.ps1` — `Wait-DevRunnerReady`. Used by perf suite.

Neither knows about the other. Scripts that predate both roll their own.
A fix to readiness checking in one library doesn't propagate to scripts
using the other.

### 3.5 Per-lane v1/v2 artifact converters duplicate structure

Nine separate `convert-*-v1-to-v2.mjs` scripts exist:

- `convert-claim-a-suite-v1-to-v2.mjs`
- `convert-claim-b-suite-v1-to-v2.mjs`
- `convert-track-g-suite-v1-to-v2.mjs`
- `convert-rank-eval-metrics-v1-to-v2.mjs`
- `convert-perf-suite-v1-to-v2.mjs`
- `convert-claim-d-suite-v1-to-v2.mjs`
- `convert-competitor-suite-v1-to-v2.mjs`
- `convert-rag-eval-v1-to-v2.mjs`
- `convert-beir-metrics-v1-to-v2.mjs`

Each implements the same structural transformation (wrap in `bench-suite`
envelope, add `measurements.summary`, add `decisions`) with lane-specific
field mapping. The shared pattern is unextracted.

### 3.6 Baseline management is convention-based

35 baseline files follow a naming convention
(`search-eval-beir-<dataset>-baseline.metrics.json`) but there's no schema
or registry that enumerates what baselines should exist. Per-lane promote
scripts (`promote-claim-a-baseline`, `promote-search-eval-beir-baseline`,
etc.) each implement their own promotion logic. A new lane requires writing
a new promote script.

### 3.7 Status field consumption is scattered

24 files read `/api/status`, `/api/knowledge/status`, or `/api/health`.
Each hardcodes field names from the API response. When `WorkerStatusMapper`
renames a field (as happened with `queueDepth` → `pendingJobs`), every
consumer must be found and updated manually.

---

## 4. Deep Code Analysis: Config Resolution

### 4.1 Six-layer resolution with no source tracing — Resolved (Pillar 3)

> ResolvedConfig ordinal chain (100-500) replaces six-layer ad-hoc resolution.
> `ConfigResolution` record carries source provenance per key.

The config resolution chain for the index path has six layers:

| Layer | Source | Checked by | Guard |
|-------|--------|-----------|-------|
| 1 | `-D` JVM arg (via `UI_OPTS`/`JAVA_OPTS`) | `EnvRegistry.get()` → `System.getProperty()` | None (first checked) |
| 2 | `settings.json` `indexBasePath` | `HeadlessApp` → `setSysPropIfBlankWithSource` | If-blank (skips if layer 1 set) |
| 3 | `JUSTSEARCH_INDEX_BASE_PATH` env var | `RuntimeConfig.indexBasePath()` → `ConfigPrecedence` | Sysprop checked first |
| 4 | YAML `app.data_dir` | `RuntimeDataDirResolver` | Wins over env if set literally |
| 5 | `JUSTSEARCH_DATA_DIR` env var | `PlatformPaths.resolveDataDir()` | Only if sysprops blank |
| 6 | Platform default (`%LOCALAPPDATA%`) | `PlatformPaths.getPlatformDefault()` | Last resort |

`EnvRegistry.get()` returns `Optional<String>` with no indication of which
source won. No INFO log is emitted at any step.

### 4.2 `IN_MEMORY` mode doesn't prevent settings.json contamination — Resolved

> **Fixed at two levels:** (1) Ordinal chain prevents settings.json (300) from
> beating env vars (400) structurally. (2) `load()` now returns `new UiSettings()`
> immediately in `IN_MEMORY` mode — settings.json is never read from disk.
> Both the contamination and the asymmetry (save guarded, load unguarded) are
> resolved.

~~`UiSettingsStore.PersistenceMode.IN_MEMORY` is intended for prod/CI
verification ("prevents production/CI verification from being contaminated
by user-profile settings" — comment in `resolveMode()`). But `load()` always
reads from disk regardless of mode. `IN_MEMORY` only suppresses `save()`.~~

~~If a developer has `indexBasePath` set in their local `settings.json`, it
contaminates every `IN_MEMORY` run — the stated prevention intent is not met.~~

### 4.3 Two parallel index-path resolvers that can diverge — Resolved (Pillar 3)

> Single resolver in `ResolvedConfigBuilder`. `PlatformPaths.resolveIndexPath()`
> and `RuntimeConfig.indexBasePath()` still exist but migrated consumers read
> from `ConfigStore.get().paths()` or `ConfigStore.get().index()`.

- `PlatformPaths.resolveIndexPath(collection)` — pure: `resolveDataDir()/index/collection`
- `RuntimeConfig.indexBasePath()` — checks `JUSTSEARCH_INDEX_BASE_PATH` sysprop first

If `HeadlessApp` sets `justsearch.index.base_path` from settings.json (Phase 1),
and something calls `PlatformPaths.resolveIndexPath("default")`, it gets a
different answer than `RuntimeConfig.indexBasePath()`. No assertion or warning
when they diverge.

### 4.4 `llama.lib.path` is the only unguarded settings bridge — Resolved

> Now uses `SystemPropertyUtils.setSysPropIfBlank()` — explicit `-Dllama.lib.path`
> wins over settings.json value, matching all other sysprop bridges.

`HeadlessApp` applies 7 settings.json values to sysprops. Six use
`setSysPropIfBlankWithSource` (if-blank guard). `llama.lib.path` uses a bare
`System.setProperty` (line 83) — it silently overwrites any `-Dllama.lib.path`
JVM arg. Likely a bug.

### 4.5 YAML beats env var for data dir (inconsistent) — Resolved (Pillar 3)

> YAML at ordinal 200, env at ordinal 400 — uniform precedence. Migrated
> consumers read from ConfigStore where env always beats YAML.

`RuntimeConfig.indexBasePath()` checks env/sysprop first (explicit wins).
But `RuntimeConfig.dataDir()` delegates to `RuntimeDataDirResolver`, which
returns a literal YAML `app.data_dir` value directly — bypassing
`EnvRegistry.DATA_DIR`. A user setting `JUSTSEARCH_DATA_DIR` cannot override
a stale YAML value. This is inconsistent: `indexBasePath` has
env-wins-over-YAML semantics, `dataDir` has YAML-wins-over-env semantics.

### 4.6 Three data-dir sysprop aliases must stay synchronized — Resolved (Pillar 3)

> Builder resolves canonical `justsearch.data.dir`. `harmonizeDataDirProperties()`
> in HeadlessApp still writes all three aliases post-resolution for backward
> compat, but ConfigStore consumers read the canonical resolved value.

`PlatformPaths.resolveDataDir()` checks: canonical `justsearch.data.dir`,
legacy `justsearch.data_dir`, legacy `app.data_dir`.
`harmonizeDataDirProperties()` in HeadlessApp sets all three after resolution.
Any component that reads a legacy name before `harmonizeDataDirProperties`
runs will see a blank value.

### 4.7 Duplicate sysprop writes — Mitigated

> ConfigStore reduces the need for sysprop communication. The HeadlessApp
> bridge (`setSysPropIfBlankWithSource`) still sets sysprops for backward
> compat with unmigrated paths. Full elimination requires removing the
> sysprop bridge after all consumers migrate (future work).

Lines 58-61 in HeadlessApp set `justsearch.index.parity.allow_mismatch`,
`justsearch.infra.health.port`, `justsearch.infra.health.host` unconditionally.
Lines 148-150 set the same properties again with the same values. These
unconditional writes make the properties non-overridable via `-D` args — they
are hardcoded for sidecar mode.

### 4.8 `setSysPropIfBlank` duplicated across 3 files — Resolved

> Extracted to `SystemPropertyUtils` in `modules/configuration`. All 3 callers
> now use the shared implementation. Unified to always-trim values.

`HeadlessApp.setSysPropIfBlankWithSource`, `AiInstallService.setSysPropIfBlank`,
and `AiPackImportService.setSysPropIfBlank` are three independent private
implementations of the same pattern. No shared utility.

### 4.9 `justsearch.server.exe` written from 4 locations — Mitigated (Pillar 3)

> Ordinal chain resolves priority. Runtime writers (`RuntimeActivationService`,
> `SettingsController`) now trigger ConfigStore rebuild after writes.
> The 4 write locations still exist but ConfigStore consumers always read
> the correctly prioritized value.

1. `HeadlessApp.setSysPropIfBlankWithSource` (from settings.json, guarded)
2. `HeadlessApp.maybeAutoSelectCuda12Variant` (unconditional)
3. `RuntimeActivationService.maybeApplyServerExeSysProp` (ownership-checked)
4. `RuntimeActivationService` rollback path (unconditional restore)

Correctness depends on call ordering in `main()`, not on an explicit priority
mechanism. Adding a fifth writer would require tracing the full call sequence.

### 4.10 Policy sysprops written from 3 locations with no coordination — Accepted (Phase 3)

> Policy readers (`LlamaServerOps`) use direct `System.getProperty()`,
> bypassing ConfigStore entirely. No staleness exists. The policy keys
> remain as ad-hoc sysprops — they don't need ConfigStore because their
> readers bypass it. See tempdoc 247 Phase 3 findings.

`justsearch.policy.gpuAccelerationEnabled` and
`justsearch.policy.disallowExternalInferenceServers` are written
unconditionally by HeadlessApp (best-effort), LocalApiServer constructor,
and RuntimeActivationService.activate(). All three overwrite each other.
The most recent call wins, which is the intent, but there is no single
authoritative writer.

### 4.11 Silent exception swallowing in `resolveAiHome()` — Resolved

> Both catch blocks now log at WARN with exception details before falling back.

Both `EnvRegistry.HOME.getPath()` and `resolveDataDir()` are wrapped in
`catch (Exception ignored)` with no logging. If either fails unexpectedly,
the caller falls back to CWD silently.

### 4.12 `getBoolean`/`getInt` silently fall back on parse failure — Mitigated (Pillar 3)

> `ResolvedConfigBuilder` logs parse failures at WARN with raw value.
> `EnvRegistry` direct callers still get silent fallback, but most consumers
> have migrated to ConfigStore where failures are logged.

`EnvRegistry.getInt("JUSTSEARCH_API_PORT")` with a malformed value like
`"abc"` silently returns the default. No warning is logged. `getBoolean`
only recognizes `true`/`1`/`yes` — values like `"on"` or `"TRUE"` silently
return the default.

---

## 5. Deep Code Analysis: Status Endpoints

### 5.1 75+ field names as inline string literals — Resolved (Pillar 1)

> View records (`WorkerOperationalView`, `WorkerDebugView`, etc.) replace
> all inline string literals with compile-time checked record fields.

Every field name in `StatusLifecycleHandler`, `WorkerStatusMapper`,
`KnowledgeSearchController`, and their response maps is an inline string
literal. No field-name constants exist anywhere. The most dangerous are
fields that appear in multiple files:

- `"indexState"` — in WorkerStatusMapper, StatusLifecycleHandler (3 fallback
  paths), KnowledgeSearchController, and LifecycleContractTest
- `"indexHealthy"` — in WorkerStatusMapper, StatusLifecycleHandler
  (populateFallbackIndexDefaults + computeComponent), test mocks
- `"embeddingReady"`, `"aiReady"` — in WorkerStatusMapper,
  StatusLifecycleHandler, and 3-4 test assertions each

A rename of any of these requires finding and updating every occurrence
manually, with no compiler assistance.

### 5.2 Same data, different field names across endpoints — Resolved (Pillar 1)

> `KnowledgeStatusView` has canonical names + `@JsonProperty` deprecated
> legacy accessors. Both old and new names coexist in responses during migration.

| Worker proto | `/api/status` field | `/api/knowledge/status` field |
|--------------|--------------------|-----------------------------|
| `getDocCount()` | `indexedDocuments` | `docCount` |
| `getIsHealthy()` | `indexHealthy` | `healthy` |
| `getQueueDepth()` | `pendingJobs` | `queueDepth` |
| `getActiveDocCount()` | `activeIndexedDocuments` | `activeDocCount` |
| `getBuildingDocCount()` | `buildingIndexedDocuments` | `buildingDocCount` |

The `KnowledgeStatus` record is annotated "Stability: stable (API contract)"
— the only explicit stability annotation in the codebase. But its field names
(`docCount`, `healthy`, `queueDepth`) diverge from the UI-facing names
(`indexedDocuments`, `indexHealthy`, `pendingJobs`). Neither set of names is
wrong — they just evolved independently and were never reconciled.

### 5.3 `KnowledgeSearchController` shares zero code with `StatusLifecycleHandler` — Resolved (Pillar 1)

> Both endpoints now use view records mapped from the same Worker proto.

`/api/knowledge/status` builds its response from `KnowledgeStatus` record
fields. `/api/status` builds its response from `WorkerStatusMapper.toUiStatusMap()`.
Both endpoints ultimately read from the same Worker proto, but the mapping
is implemented twice independently.

### 5.4 Three naming conventions within `WorkerStatusMapper` — Resolved (Pillar 1)

> `@JsonNaming(SnakeCaseStrategy.class)` on `WorkerDebugView`, `HealthNodeView`,
> `SignalBusView`. Default camelCase for `WorkerOperationalView`.

| Method | Convention | Example |
|--------|-----------|---------|
| `toUiStatusMap()` | camelCase | `indexedDocuments`, `pendingJobs` |
| `toDebugWorkerState()` | snake_case | `queue_depth`, `doc_count` |
| `buildHealthNode()` | snake_case | `ai_ready`, `worker_state` |

Additionally, `buildHealthNode()` contains a comment: "ai_ready is a legacy
misnomer: reports embedding readiness, not LLM readiness." The misnomer is
documented but uncorrected.

### 5.5 Contract test coverage gaps — Resolved (Pillar 1)

> `StatusRecordSchemaTest` (13 tests): schema drift detection, contract
> validation, backward compat fixtures. JSON Schema generated from records
> via victools, validated via networknt.

| Surface | Contract test? | Consequence |
|---------|---------------|-------------|
| `/api/health` response shape | Yes — `assertExactFields` (strict) | Rename caught at build time |
| `/api/status` schema v1 subset | Yes — subset check | Presence verified, renames partially caught |
| `/api/status` worker fields (55+) | **5 of 60 tested** | Rename of 55 fields not caught |
| `/api/status` readiness envelope | Yes — path traversal | Path structure renames caught |
| `/api/knowledge/status` | **No test** | Any change undetected |
| `/api/debug/state` | **No test** | Any change undetected |
| `buildHealthNode()` | **No test** | Any change undetected |

### 5.6 `reasonCode` casing inconsistency — Resolved (Pillar 1)

> Record fields enforce consistent casing per view. `@JsonNaming` per record
> eliminates ad-hoc casing decisions.

The readiness envelope (built by `readinessComponent()`) uses camelCase
`"reasonCode"`. The `LifecycleSnapshotV1.Component` record uses snake_case
`reason_code` (matching the v1 schema). These are different layers of the
same response — `readiness.components.ai.reasonCode` vs
`components.head.reason_code`.

---

## 6. Deep Code Analysis: Shared Libraries

### 6.1 Two PS1 libraries with diverged interfaces — Resolved (Pillar 2)

> `EvalSession.psm1` + `eval-common.psm1` extract shared lifecycle and
> utilities. Both libraries import the shared modules and delegate lifecycle.

| Dimension | `benchmark-ci-common.ps1` | `perf-suite-runtime-common.ps1` |
|-----------|--------------------------|--------------------------------|
| Transport | HTTP to `/api/status` | `dev-runner.cjs status --json` |
| Readiness check | `status.status == "ok"` or `status.ok == true` | `readiness.ready_http == true` |
| UI port | Not started (no `--ui-port`) | Started (both ports) |
| Poll interval | 2 seconds | 250ms |
| On process exit | Keeps polling | Throws immediately |
| Clean mode | Always `--clean hard` | Caller-configurable |
| Corpus identity | Full env var management | None |

Both duplicate `Resolve-RepoRoot`, `Read-JsonBestEffort`, and JSON write
helpers. Neither is a superset of the other — each has unique capabilities
the other lacks.

### 6.2 `Start-DevRunnerManaged` always forces `--clean hard` — Resolved (Pillar 2)

> `Start-EvalServer -Clean <none|soft|hard>` — caller chooses.

There is no way to start with warm data. This prevents reuse for isolation
experiments (which need a custom data directory) or for scenarios that test
indexing resume.

### 6.3 `Resolve-BeirBaselinePath` hardcodes two profile IDs — Resolved

> Refactored from hardcoded switch to data-driven lookup table. Error message
> now lists available profiles. New profiles require one hashtable entry.

The function (in `benchmark-ci-common.ps1`) only recognizes `embedding-nomic-q4`
and `stub-jaccard`. Any new profile requires editing this function.

### 6.4 No shared "ingest + wait for idle" pattern — Resolved (Pillar 2)

> `Invoke-EvalIngest` + `Wait-EvalIndexIdle` in EvalSession.psm1.
> `ingestEval()` in eval-session.mjs for JS callers.

Seven scripts implement server lifecycle, but corpus ingestion and
wait-for-idle is handled by `beir-eval-win.ps1` (PS1) and
`agent-battery-core.mjs` (JS) independently. The BEIR harness calls
`POST /api/indexing/roots` then polls; the agent battery calls its own
`ingestCorpus()`. Neither is extracted as a reusable building block.

### 6.5 Per-lane diff scripts duplicate threshold comparison — Resolved

> Extracted `isRegressed(ratio, { minRatio, maxRatio })` to `policy-engine.mjs`.
> All 5 diff scripts now use the shared function instead of inline comparisons.

Each of the 7 `diff-*-suite.mjs` scripts implements its own per-metric
comparison logic and passes the result to `policy-engine.mjs`. The shared
library provides `buildGateDecision()` (scan comparisons, compute pass/fail)
and `ratioOrNull()` (single ratio) but no helper for "compare metric X with
threshold T and produce a comparison object." Each diff script reimplements
this independently.

---

## 7. Prior Consolidation Work (overlap assessment)

| Tempdoc | Status | What it covers | Overlap with 246 |
|---------|--------|---------------|-----------------|
| **216** | Complete | Scorecard, v2 schema, producer contracts | None — 246 addresses infrastructure below the scorecard |
| **235** | Complete | Measurement confidence, operational burden | Identified some issues 246 details (A5 isolation, dual-write burden) |
| **237** | Implemented | Readiness envelope, consumer drift prevention | Partially covers 246 §4.2 (readiness contract); W7 centralized wait-loop for BEIR |

**What 237 already solved:** `Wait-ForIndexIdle` in `beir-eval-win.ps1` now
reads `readiness.composites.retrieval.state` instead of raw fields. Prevention
rule: new `ReadinessDimension` constants require handling (compile error).

**What 237 did NOT solve:** The 7 independent server lifecycle implementations,
config propagation bugs, status field name divergence between endpoints, and
the shared library fragmentation.

---

## 8. Centralization Directions

### 8.1 Config resolution: explicit-wins-over-implicit

Env vars and `-D` sysprops are explicit overrides — a user or script
deliberately set them. Settings.json is implicit state from a prior GUI
session. The resolution chain should guarantee that explicit sources beat
implicit ones. This means:

- `HeadlessApp` should check whether a sysprop/env override exists before
  applying settings.json values via `setSysPropIfBlankWithSource`
- Or `UiSettingsStore.load()` in `IN_MEMORY` mode should return empty defaults
- The fix applies to all 7 settings→sysprop bridges, plus `llama.lib.path`
  which needs the if-blank guard added

### 8.2 Config resolution: source tracing

`EnvRegistry.get()` should log at DEBUG which source provided each value
(sysprop vs env var vs absent). `PlatformPaths.resolveDataDir()` should log
at INFO the final resolved path and its source. `RuntimeConfig.indexBasePath()`
should do the same.

### 8.3 Status field names: constants or unification

Two approaches, not mutually exclusive:

**(a)** Extract field name constants into a shared class (e.g.,
`StatusFieldNames`) and use them in `WorkerStatusMapper`, `StatusLifecycleHandler`,
`KnowledgeSearchController`, and `LifecycleContractTest`. This prevents
accidental divergence within a single endpoint and makes renames compiler-checked.

**(b)** Unify the field names between `/api/status` and `/api/knowledge/status`
so the same Worker data always appears under the same JSON key. This is a
breaking change for consumers — the frontend reads `/api/knowledge/status` and
expects `docCount`, while `/api/status` returns `indexedDocuments`. Would
require a migration period or versioned endpoint.

### 8.4 Contract tests for untested surfaces

Add `LifecycleContractTest`-style response-shape tests for:
- `/api/knowledge/status` (zero coverage today)
- `/api/debug/state` (zero coverage today)
- The full field set of `toUiStatusMap()` (5 of 60 fields tested today)

### 8.5 Server lifecycle shared abstraction

A single module that provides:
- Start server (installDist, Gradle, or dev-runner — configurable)
- Wait for readiness (HTTP up, index serving, or full stack — configurable)
- Yield to eval callback
- Stop server

Config propagation happens once in this module: `-D` sysprops via `UI_OPTS`,
data directory override, API port. The 7 existing implementations become
thin callers that specify their config and eval callback.

### 8.6 Shared library deduplication

Extract `Resolve-RepoRoot`, `Read-JsonBestEffort`, and `Write-JsonFile` into
a single base utilities module. Both `benchmark-ci-common.ps1` and
`perf-suite-runtime-common.ps1` dot-source it instead of reimplementing.

---

## 9. Priority Assessment

> **Superseded by §10.** This section reflects the pragmatic tier assessment
> before the ideal-path architecture was designed. Retained for context. The
> operative plan is the three-pillar design in §10 with sequencing in §10.5.

### Tier 1: Concrete bugs (small, do now)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 1 | `llama.lib.path` missing if-blank guard (§4.4) | Trivial | Prevents silent `-D` override |
| 2 | Settings.json should not override explicit env vars/sysprops (§4.2, §8.1) | Small | Prevents "wrong data dir" class |
| 3 | Source-won INFO logging in `resolveDataDir` and `indexBasePath` (§8.2) | Small | Makes config bugs diagnosable |
| 4 | Contract test for `/api/knowledge/status` (§8.4) | Small | Catches field drift at build time |

### Tier 2: API hygiene (medium, needs coordination)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 5 | Extract status field name constants (§8.3a) | Medium | Prevents accidental renames |
| 6 | Unify `setSysPropIfBlank` into shared utility (§4.8) | Small | DRY, reduces private copies |
| 7 | Remove duplicate sysprop writes in HeadlessApp (§4.7) | Trivial | Dead code removal |
| 8 | Log warning on `EnvRegistry` parse failures (§4.12) | Small | Makes misconfig visible |

### Tier 3: Structural consolidation (larger, needs design)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 9 | Unify readiness polling across eval lanes (§8.5) | Medium | 7 implementations → 1 |
| 10 | Server lifecycle shared abstraction (§8.5) | Medium | Config propagation fixed once |
| 11 | Shared library deduplication (§8.6) | Medium | Reduces maintenance surface |
| 12 | Status field name unification across endpoints (§8.3b) | Large | Breaking change, needs migration |

### Tier 4: Ergonomic (low priority)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 13 | Lane-agnostic v2 converter | Medium | 9 scripts → 1 (moot if v1 sunsetted per 235-S1) |
| 14 | Baseline registry manifest | Low | Convention → enforcement |

---

## Appendix A: Prior Tempdocs

- **216** (complete) — Eval Harness Consolidation: scorecard, v2 schema, producer contracts
- **230** (complete) — Eval Suite Decision Closure: decision artifact standardization
- **235** (complete) — Eval Infrastructure Audit: 16 recommendations, measurement confidence
- **237** (implemented) — Pipeline Readiness Consumer Drift: readiness envelope, W7 wait-loop fix
- **245** (active) — Search Quality Strategy: isolation experiments that surfaced these issues

## Appendix B: Files Investigated

### Config resolution
- `PlatformPaths.java` — 5-step resolveDataDir, resolveIndexPath, resolveAiHome
- `RuntimeConfig.java` — indexBasePath (env→YAML→platform), dataDir (YAML-first)
- `EnvRegistry.java` — 107 entries, sysprop-then-env, no source tracing
- `HeadlessApp.java` — 7-phase startup, settings bridge, harmonizeDataDirProperties
- `UiSettingsStore.java` — IN_MEMORY load-from-disk behavior, PersistenceMode.resolveMode

### Status endpoints
- `StatusLifecycleHandler.java` — buildStatusMap (7 layers, 60+ fields)
- `WorkerStatusMapper.java` — toUiStatusMap (60+ camelCase), toDebugWorkerState (30+ snake_case)
- `KnowledgeSearchController.java` — handleStatus (20 fields, shares zero code with StatusLifecycleHandler)
- `KnowledgeStatus.java` — typed DTO, "Stability: stable (API contract)"
- `LifecycleContractTest.java` — 8 tests, exact-match for /api/health, subset for /api/status

### Shared libraries
- `benchmark-ci-common.ps1` — Wait-ApiReady, Start-DevRunnerManaged, 20+ helpers
- `perf-suite-runtime-common.ps1` — Wait-DevRunnerReady, Invoke-CaptureEvidence, 12+ helpers
- `policy-engine.mjs` — buildGateDecision, ratioOrNull (pure gate logic)
- `v2-dual-write.ps1` — mechanical v1→v2 conversion wrapper
- `benchmark-utils.ps1` — Get-MachineFingerprint, Get-Stats, Get-Median

---

## 10. Ideal State Design (Theoretical, Research-Informed)

> **Implementation status:** All three pillars have been implemented on
> branch `feat/246-eval-centralization`. Pillar 1 (§10.2): 14 view records,
> JSON Schema generation, contract tests — 28-commit implementation (§11).
> Pillar 2 (§10.3): `EvalSession.psm1` + `eval-session.mjs` +
> `eval-common.psm1` with 4 of 5 consumers integrated. Pillar 3 (§10.1):
> Consumer migration complete via tempdoc 247 (6 phases, ~148 call sites).
> The design below reflects the target architecture; see §11-§14 for
> implementation details and any deviations from the design.

This section describes the target architecture for resolving all issues in
§4–§6. It pursues the ideal path — clean abstractions over pragmatic
workarounds. Each proposal is grounded in external research (Appendix D) and
validated against established framework patterns.

### 10.1 ResolvedConfig — Immutable Configuration Object

**Problem summary:** Configuration resolution is scattered across 4 files
(`EnvRegistry`, `RuntimeConfig`, `PlatformPaths`, `HeadlessApp`), uses system
properties as a shared mutable communication bus (35+ files read `EnvRegistry`,
`HeadlessApp` writes 15+ sysprops), and has no source tracing. The 107
`EnvRegistry` entries resolve sysprop-then-env with no defaults stored in the
enum — defaults are scattered across 35+ call sites as method arguments.

**External validation:** All 5 surveyed frameworks (Spring Boot, HOCON,
SmallRye, Micronaut, .NET IConfiguration) use the same core pattern: layered
sources with explicit priority, source tracing, and immutable config objects
after construction. Using `System.setProperty()` as a runtime communication
channel is universally identified as an anti-pattern — global mutable state,
thread-unsafe, order-dependent initialization (§D.1).

**Ideal design:**

A `ResolvedConfig` is an immutable snapshot of all resolved configuration. The
initial snapshot is built at startup, before any component reads configuration.
It replaces all `System.getProperty` reads and `EnvRegistry.get()` calls with
typed accessor methods. When the user changes settings at runtime (via the
GUI), a new snapshot is built and atomically swapped in (see **ConfigStore**
below). Components always access config via `configStore.get()`, never via
`System.getProperty`.

The design draws from four established patterns:

- **Constructor binding** (Spring Boot 2.2+): All config values are final
  fields injected via constructor. No setters, no post-construction mutation.
- **Numeric ordinals** (SmallRye): Each source has an explicit ordinal number.
  Higher ordinal wins. Eliminates ambiguity about which source beats which.
- **Source tracing as return type** (SmallRye `ConfigValue`, HOCON
  `ConfigOrigin`): Every lookup returns not just the value but also the source
  name, ordinal, and file location. Not just logged — available programmatically.
- **Atomic snapshot replacement** (SEI CERT VNA01-J, Android LiveData):
  `AtomicReference<ResolvedConfig>` provides thread-safe read access;
  settings changes build a new immutable snapshot and swap it in. An optional
  event bus notifies components that cached the old snapshot.

```
ResolvedConfig (immutable record, replaced atomically via ConfigStore)
├── paths: ResolvedPaths
│   ├── dataDir: Path
│   ├── indexBasePath: Path
│   ├── home: Path
│   ├── modelsDir: Path
│   ├── ssotPath: Path
│   └── repoRoot: Path
├── ports: ResolvedPorts
│   ├── apiPort: int
│   ├── aiWorkerPort: int
│   └── serverPort: int
├── ai: ResolvedAiConfig
│   ├── serverExe: Path
│   ├── gpuLayers: int
│   ├── embedModel: Path
│   ├── llmModelPath: Path
│   ├── ... (28 LLM_* entries)
│   └── disabled: boolean
├── search: ResolvedSearchConfig
│   ├── profile: String
│   ├── pipeline: String
│   ├── lambdamartEnabled: boolean
│   └── collection: String
├── index: ResolvedIndexConfig
│   ├── vector: VectorCfg          (from RuntimeConfig, contributed at ordinal 200)
│   ├── hybrid: HybridSearchCfg
│   ├── merge: MergeTieredCfg
│   └── ... (25 index/storage methods from RuntimeConfig)
├── telemetry: ResolvedTelemetryConfig
├── policy: ResolvedPolicyConfig
├── ui: ResolvedUiConfig
│   ├── settingsMode: PersistenceMode
│   ├── automationEnabled: boolean
│   └── excludePatterns: List<String>
└── resolutions: Map<String, ConfigResolution>
    // Every resolved value, keyed by config key, queryable at runtime
```

**Resolution priority with numeric ordinals:**

Following SmallRye's pattern, each source has a fixed ordinal. Higher wins.
Same-ordinal tiebreaking in SmallRye is non-deterministic — all slots below
are distinct to avoid ambiguity:

| Ordinal | Source | Rationale |
|---------|--------|-----------|
| 500 | `-D` JVM argument | Operator override — always wins |
| 450 | Worker config snapshot | Head→Worker propagation (see **Worker config** below) |
| 400 | Environment variable | Scripting/CI override (12-factor) |
| 350 | CI profile overrides | CI-specific config file (forward-looking; no current lane needs it) |
| 300 | `settings.json` value | User preference (GUI-set) |
| 200 | YAML `application.yaml` | Application profile defaults |
| 150 | Auto-detected values | GPU capabilities, platform paths, locale inference |
| 100 | Programmatic default | Hardcoded fallback |
| 0 | (none) | Sentinel — no value found |

Auto-detected values sit at ordinal 150: above hardcoded defaults (100) so
that GPU detection of "35 available layers" beats a hardcoded `gpuLayers=0`,
but below YAML (200) so that a developer can pin a value in their config
profile. Any user-supplied source (settings.json at 300, env at 400, JVM arg
at 500) beats auto-detection.

Values derived from other config values (e.g., `workerLibDir` computed from
`dataDir`) are not separate ordinal slots — they are computed during
`ResolvedConfig.Builder.build()` from already-resolved values. For source
tracing, the derived value inherits the ordinal of its highest input.

The current issue (settings.json at ordinal 300 beating env var at ordinal 400
for `indexBasePath`) is structurally impossible with ordinals — the builder
iterates sources in ordinal order and the first match wins.

This also matches the Twelve-Factor adaptation for desktop (§D.1): user
settings files are legitimate for preferences (ordinal 300) but must be lower
priority than env vars (ordinal 400) and JVM args (ordinal 500).

**Source tracing as first-class return type:**

Modeled on SmallRye's `ConfigValue` and HOCON's `ConfigOrigin`, every resolved
value carries a `ConfigResolution` record:

```java
record ConfigResolution(
    String key,             // e.g., "justsearch.data.dir"
    String value,           // e.g., "/tmp/beir-eval"
    String sourceName,      // e.g., "env_var"
    int sourceOrdinal,      // e.g., 400
    String sourceDetail,    // e.g., "JUSTSEARCH_DATA_DIR"
    List<SourceCandidate> considered  // all sources checked, in ordinal order
)

record SourceCandidate(String sourceName, int ordinal, String rawValue)
// rawValue is null if source didn't have the key
```

This is queryable at runtime — not just logged once:

```java
// Diagnostic endpoint can expose resolution details
ConfigResolution r = config.resolution("justsearch.data.dir");
// r.sourceName() == "env_var", r.sourceOrdinal() == 400
// r.considered() == [{jvm_arg, 500, null}, {env_var, 400, "/tmp/beir"}, ...]
```

At startup, `ResolvedConfig` logs at INFO:
```
Config: dataDir=/tmp/beir-eval (env_var:JUSTSEARCH_DATA_DIR, ordinal=400)
Config: indexBasePath=/tmp/beir-eval/index/default (derived:dataDir)
Config: serverExe=C:\...\llama-server.exe (settings_json, ordinal=300)
```

**Impact on `HeadlessApp` startup:**

The current 7-phase startup collapses to 4 phases:
1. Build `ResolvedConfig` (phases 1-3: UI settings load, GPU auto-select,
   headless profile — all config resolution happens here, each contributing
   at its ordinal. Phase 1 must precede Phase 2: GPU auto-select needs
   settings values.)
2. Init telemetry (phase 4: reads resolved data dir, separate because it
   has side effects — disk I/O, flush scheduling)
3. Start Worker (phases 5-6: enterprise policy snapshot + Knowledge Server
   spawn. Phase 5 must precede Phase 6: policy gates Worker GPU config.)
4. Start API (phase 7: AppFacade + LocalApiServer init, port file writes,
   stdout signals)

See Appendix E.2 for the full phase map with ordering constraints.

`System.setProperty` calls are eliminated from application code. The JVM's
system property namespace is read-only (at ordinal 500) — no application code
writes to it. This directly addresses the anti-pattern confirmed by external
research: "System properties are appropriate for read-only `-D` flags set at
JVM startup. Using `System.setProperty()` in application code at runtime is
the problematic pattern" (§D.1).

**Impact on `EnvRegistry`:**

`EnvRegistry` becomes a source definition enum — it defines the sysprop/envvar
name pairs and their ordinals (500 for sysprop, 400 for env var) but no longer
has `get()` methods. Resolution is handled by `ResolvedConfig.Builder`, which
iterates `EnvRegistry` entries during construction. This follows HOCON's
`reference.conf` pattern: the enum defines what keys exist and their default
values, the builder resolves them from all sources.

**Impact on `RuntimeConfig` (scope: 88 methods across 9 factory classes):**

`RuntimeConfig` has its own parallel resolution system (`RuntimeEnvResolver` +
`RuntimeNodeReader`) separate from `EnvRegistry`. `ResolvedConfig` must
subsume BOTH systems. The full scope:

- `RuntimeNodeReader` (pure YAML navigator, 5 typed methods: `optText`,
  `optInt`, `optLong`, `optDouble`, `optBoolean`) — becomes the ordinal 200
  source reader inside `ResolvedConfig.Builder`
- `RuntimeEnvResolver.envOrProperty(envKey, propKey, fallback)` — delegates
  to `ConfigPrecedence` (sysprop > env > fallback). Replaced by the ordinal
  chain directly.
- `RuntimeEnvResolver.resolveWithConflictLog(yamlValue, ...)` — gives YAML
  highest precedence, logging a conflict if env/sysprop also set. This is a
  **precedence difference** from the ordinal table (where sysprop=500 > env=400
  > YAML=200). See **Precedence investigation** below.
- 9 factory classes (`RuntimePolicyConfigFactory`, `RuntimeHybridSearchConfigFactory`,
  `RuntimeRagConfigFactory`, `RuntimeIndexConfigFactory`,
  `RuntimeWorkerConfigFactory`, `RuntimeInfraConfigFactory`,
  `RuntimeSearchConfigFactory`, `RuntimeWatcherConfigFactory`,
  `RuntimeOcrConfigFactory`) contain 88 resolve methods with ~36 `envOrProperty`
  calls. 4 of 9 are YAML-only (Index, Search, Watcher, Ocr) — these need only
  ordinal 200 reads, no envOrProperty migration. Absorbed into
  `ResolvedConfig.Builder`. (See Appendix E.1 for full inventory.)
- 5 keys are registered in BOTH `EnvRegistry` and factory inline literals:
  `EGRESS_BLOCK_ALL`, `LLM_ENABLED`, `LLM_MODEL_PATH`, `LLM_MODE`,
  `SEARCH_PROFILE`. Same resolution algorithm, but maintenance duplication
  that `ResolvedConfig` eliminates.

Method count by resolution mechanism in `RuntimeConfig`:
- `RuntimeNodeReader` only: ~15 methods
- `RuntimeNodeReader` + `RuntimeEnvResolver` fallback: 4 methods
- `RuntimeEnvResolver` only: 1 method
- `EnvRegistry` only: 3 methods
- Delegates to factory classes: ~35+ methods

`RuntimeConfig`'s per-domain inner classes (`VectorCfg`, `SearchConfig`,
`Hybrid`, etc.) survive as typed sub-records within `ResolvedConfig.index`
and `ResolvedConfig.search`.

**Precedence investigation (YAML-wins vs ordinal table):**

`resolveWithConflictLog` gives YAML highest precedence for **1 key**:
`searchPipelineProfile` (sysprop: `justsearch.search.pipeline.profile`, env:
`JUSTSEARCH_SEARCH_PROFILE`). The 3 other keys previously assumed to use this
method (`indexDefaultLanguage`, `indexLanguageDetectionEnabled`,
`searchLanguagePolicy`) do not — they use standard `envOrProperty` resolution.
This dramatically reduces the YAML-wins migration risk.

The ordinal table gives env (400) and sysprop (500) higher precedence than
YAML (200). This is a conscious precedence difference for `searchPipelineProfile`
— the method logs conflicts, suggesting someone anticipated the case. Before
implementation, the migration must:
1. Verify whether any deployment or developer workflow depends on YAML
   beating env vars for this key
2. Run parity tests (see **Migration testing** below) to surface divergences
3. If no dependencies exist, migrate to ordinal semantics (env wins)
4. If dependencies exist, document them and either preserve the YAML-wins
   behavior for these keys (custom ordinal) or provide a migration path

**Backward compatibility: legacy sysprop readers**

Some third-party code or logback configuration may read sysprops directly.
`ResolvedConfig.Builder` writes a small set of legacy sysprops after
resolution (the three data-dir aliases, logback-relevant paths) as a
one-way publish step. These are documented as deprecated compatibility shims,
not a communication channel.

**ConfigStore — runtime settings changes:**

The GUI allows runtime settings changes (theme, index path, model path, GPU
layers) via `POST /api/settings/v2`. Currently, `SettingsController` applies
these via `System.setProperty()` side effects, with a `*.source` companion
property preventing operator overrides from being clobbered.

With `ResolvedConfig`, the `System.setProperty()` side effects are replaced
by `ConfigStore`:

```java
class ConfigStore {
    private final AtomicReference<ResolvedConfig> current;
    private final List<Consumer<ConfigChangedEvent>> listeners;

    ResolvedConfig get() { return current.get(); }

    void applySettingsChange(UiSettings newSettings) {
        ResolvedConfig prev = current.get();
        ResolvedConfig next = prev.rebuildWithSettings(newSettings);
        // Settings rebuilt at ordinal 300 — env/sysprop still win structurally
        current.set(next);
        fire(new ConfigChangedEvent(prev, next));
    }
}
```

`rebuildWithSettings()` reconstructs the snapshot with new settings.json
values at ordinal 300, preserving all higher-ordinal sources. The current
`maybeApply*SysProp` source-tracking check ("only overwrite if source was
`ui_settings`") maps to the ordinal system: settings at 300 structurally
cannot beat env at 400 or JVM args at 500.

Settings categories after migration:
- **Pure UI state** (theme, density, vimMode) — no server-side config change
- **Live server settings** (excludePatterns, embeddingModelPath) —
  `ConfigChangedEvent` handler picks up new value on next use
- **Restart-required** (indexBasePath, llamaLibPath) —
  `ConfigChangedEvent` handler surfaces a "restart required" flag to the
  frontend, rather than silently setting a sysprop that won't take effect

**Worker config propagation:**

The Worker is a separate JVM spawned by `WorkerSpawner`. Currently it
receives ~19 config values via env vars and `-D` sysprops, then re-resolves
using `RuntimeConfig.load()` — which may produce different results for any
value NOT explicitly forwarded.

The config snapshot pattern (from Gradle Worker API) eliminates re-resolution
drift:

```
ResolvedConfig (Head process)
  .toWorkerSnapshot()  → writes worker-relevant subset to temp properties file

WorkerSpawner
  passes -Djustsearch.config.snapshot=<path> to Worker JVM

WorkerConfig.load()
  if snapshot exists: load snapshot as ordinal 450 source (above env=400)
  else: fall back to current RuntimeConfig.load() (backward compatible)
```

Ordinal 450 is above env vars (400) but below JVM args (500), so an operator
can still override a snapshot value via `-D` on the Worker command line. The
snapshot contains the 18 values the Worker currently receives (4 env vars +
14 sysprops — see Appendix E.3 for the full list) and is cleaned up on
shutdown.

The snapshot is taken from the current `configStore.get()` at spawn time.
A settings change that triggers a `ConfigStore` swap after the Worker is
spawned does not affect the running Worker — the snapshot is a point-in-time
capture.

**Migration testing strategy:**

Migrating 107 `EnvRegistry` entries + ~36 factory `envOrProperty` calls
across 35+ files requires a safety net. The parallel change (expand-and-
contract) pattern with parity testing:

1. **Expand:** Add `ResolvedConfig` alongside `EnvRegistry` and
   `RuntimeConfig`. No callers change yet.
2. **Parity mode:** `ResolvedConfig.get(key)` internally also calls the old
   resolution path and asserts equality. Divergences are logged as warnings.
   The old value is returned (safe to ship).
3. **Migrate:** Switch callers one at a time. Each switch is a single-file
   change. Tests verify the caller works with both old and new.
4. **Contract:** Delete `EnvRegistry` entries and factory classes that have
   no remaining callers.

Completeness test ensures no key is missed:

```java
@Test void allRegisteredKeysResolvedByNewSystem() {
    ResolvedConfig config = ResolvedConfig.load(testYaml, testEnv, testSettings);
    for (EnvRegistry entry : EnvRegistry.values()) {
        ConfigResolution resolution = config.resolve(entry.key());
        assertThat(resolution).isNotNull();
        assertThat(resolution.value()).isNotNull();
    }
}
```

Precedence conflict test for the 1 YAML-wins key (`searchPipelineProfile`):

```java
@Test void envVarBeatsYamlForSearchProfile() {
    ResolvedConfig config = ResolvedConfig.load(
        yamlWith("search.pipeline.profile", "yaml-value"),
        envWith("JUSTSEARCH_SEARCH_PROFILE", "env-value"),
        emptySettings()
    );
    assertThat(config.searchPipelineProfile()).isEqualTo("env-value");
}
```

The 9 factory classes are migrated independently — add their fields to
`ResolvedConfig`, dual-read to verify parity, switch callers, delete the
factory. The 4 YAML-only factories (Index, Search, Watcher, Ocr) have simpler
migrations — their methods become ordinal 200 reads with no envOrProperty
handling.

**Module placement:** `modules/configuration` has 0 JustSearch module
dependencies and 18 modules depend on it. `ResolvedConfig` belongs here — no
circular dependency risk, no new Gradle dependency edges needed.

**What this solves:**

| Issue | How |
|-------|-----|
| §4.1 Six-layer resolution with no source tracing | Single builder with ordinals, `ConfigResolution` per key |
| §4.2 IN_MEMORY mode doesn't prevent contamination | settings.json at ordinal 300, always loses to env at 400 |
| §4.3 Two parallel index-path resolvers | One resolver in builder, one accessor on config |
| §4.4 `llama.lib.path` unguarded | All settings bridges use same ordinal chain |
| §4.5 YAML beats env var inconsistently | YAML at ordinal 200, env at 400 — uniform |
| §4.6 Three data-dir aliases | Builder writes all three from one resolved value (compat shim) |
| §4.7 Duplicate sysprop writes | No sysprop writes — components read config object |
| §4.8 `setSysPropIfBlank` duplicated | Pattern eliminated entirely |
| §4.9 `server.exe` written from 4 locations | One resolution in builder at ordinals 500/400/300 |
| §4.10 Policy sysprops from 3 locations | One resolution in builder |
| §4.11 Silent exception swallowing | Builder logs resolution errors at WARN |
| §4.12 Silent parse failure fallback | Builder logs parse failures at WARN with raw value |

---

### 10.2 Typed Status Records — Compile-Time Checked API Contracts

**Problem summary:** 113 JSON field names as inline string literals across
`WorkerStatusMapper` (3 methods), `StatusLifecycleHandler`, and
`KnowledgeSearchController`. Same Worker proto data appears under different
JSON keys depending on endpoint. Contract tests cover 8 of 60+ fields.

**External validation:** Java records provide compile-time safety, immutability
by default, and native Jackson support from 2.12.3+ — no `@JsonCreator`
needed. The `victools/jsonschema-generator` with its `jsonschema-module-jackson`
generates JSON Schema from records at build time, respecting `@JsonProperty`,
`@JsonNaming`, and `@JsonUnwrapped`. The `networknt/json-schema-validator`
validates actual HTTP responses against those schemas in integration tests.
This is the lightest-weight contract testing approach for monoliths — Pact and
Spring Cloud Contract are overkill for internal endpoints (§D.2).

**Ideal design:**

Replace all `Map<String, Object>` response builders with Java records.
Each record defines the API contract as a type — field names are compile-time
checked, serialization casing is declarative via `@JsonNaming` per view record,
and JSON Schema is generated at build time for cross-boundary contract
enforcement.

**Record hierarchy:**

```
WorkerOperationalView (record, camelCase — default Jackson behavior)
├── indexHealthy: boolean
├── indexedDocuments: long
├── activeIndexedDocuments: long
├── buildingIndexedDocuments: long
├── pendingJobs: long
├── indexState: String
├── indexSizeBytes: long
├── ... (all 55 scalar fields from toUiStatusMap)
├── migrationEnumerator: MigrationEnumeratorView (record)
├── ortCuda: OrtCudaView (record)
├── aiReady: boolean              // conditional: only present when health != null
└── embeddingReady: boolean       // conditional: only present when health != null

WorkerDebugView (record)
├── @JsonNaming(SnakeCaseStrategy.class)
├── status: String
├── queueDepth: long
├── docCount: long
├── ... (all 20 fields from toDebugWorkerState)
├── migrationEnumerator: MigrationEnumeratorDebugView (record, snake_case)
├── signalBus: SignalBusView (record, snake_case)
└── healthCheck: HealthNodeView (record, snake_case)

HealthNodeView (record)
├── @JsonNaming(SnakeCaseStrategy.class)
├── serving, version, pid, workerState, aiReady, embeddingReady

StatusResponse (record)
├── schemaVersion: int
├── observedAt: String
├── lifecycle: LifecycleView
├── components: ComponentsView
├── status: String               // "ok"
├── service: String
├── indexBasePath: String
├── uptimeMs: long
├── memoryUsedBytes: long, memoryTotalBytes: long, memoryMaxBytes: long
├── diskPressure: Boolean         // optional, head-level (not from Worker)
├── @JsonUnwrapped worker: WorkerOperationalView
├── indexAvailable: boolean
├── llm: LlmStatusView
├── onlineAi: OnlineAiView
├── readiness: ReadinessEnvelopeView
├── aiReady: boolean             // derived from readiness envelope
└── embeddingReady: boolean      // derived from readiness envelope

KnowledgeStatusView (record)
├── state, ready, queueDepth, docCount, activeDocCount, buildingDocCount, ...
├── healthy: boolean
└── extras: Map<String, Object>
```

**Investigation findings on `@JsonUnwrapped` and field mapping:**

No `@JsonUnwrapped` collision risk exists — worker fields use camelCase
names that are distinct from all head-level fields. The current code achieves
flattening via `response.putAll(workerMap)`, not via annotations; the record
migration replaces this with `@JsonUnwrapped` on the worker field.

All 3 `WorkerStatusMapper` methods do pure mechanical field mapping — no
formatting, date conversion, null coercion, or complex computation. The only
exception is 2 conditional fields (`aiReady`, `embeddingReady` — only added
if `health != null`). The record migration is therefore mechanical: direct
field assignment from proto, no logic to preserve.

Note that `KnowledgeSearchController` uses a **different code path** from
`WorkerStatusMapper` — it extracts specific fields from the `KnowledgeStatus`
record + `extras()` map directly, without going through the mapper. The record
migration must handle both code paths.

**Naming convention strategy (research-informed):**

`@JsonNaming` on the view record controls serialization casing. Each view
record has exactly one naming convention, selected by annotation:

- `WorkerOperationalView` — no annotation (default camelCase for `/api/status`)
- `WorkerDebugView` — `@JsonNaming(SnakeCaseStrategy.class)` for `/api/debug/state`
- `HealthNodeView` — `@JsonNaming(SnakeCaseStrategy.class)` for `/api/health`

This avoids per-endpoint `ObjectMapper` instances (which Jackson
supports but adds configuration complexity) and avoids a `WorkerFieldNames`
constants class (which is the current string-literal pattern with extra
indirection). The record IS the contract — annotations on the record define
the serialization behavior.

**Field name migration for `KnowledgeStatus`:**

The `KnowledgeStatus` record is annotated "Stability: stable (API contract)."
Changing its field names is a breaking change. Research (§D.2) confirms that
`@JsonAlias` is **inbound-only** — it cannot emit old names during a migration
period. The correct migration pattern (per Google AIP-180):

1. New `KnowledgeStatusView` record uses canonical names (`pendingJobs`,
   `indexedDocuments`)
2. Add deprecated accessor methods that emit old names simultaneously:
   ```java
   public record KnowledgeStatusView(long pendingJobs, long indexedDocuments, ...) {
       @JsonProperty("queueDepth") @Deprecated
       public long legacyQueueDepth() { return pendingJobs; }
       @JsonProperty("docCount") @Deprecated
       public long legacyDocCount() { return indexedDocuments; }
   }
   ```
3. During migration: both old (`queueDepth`) and new (`pendingJobs`) names
   appear in the response. Consumers can read either.
4. After migration: remove deprecated accessors. Only canonical names remain.

This is the "both names coexist until the old one can be safely retired"
pattern that Google AIP-180 formalizes.

**Build-time JSON Schema generation:**

Using `victools/jsonschema-generator` with the Jackson module as a Gradle
task (or Maven plugin equivalent):

```
:modules:app-api:generateSchema
  → reads: StatusResponse.class, KnowledgeStatusView.class, WorkerDebugView.class
  → writes: src/main/resources/schemas/status-response.schema.json
            src/main/resources/schemas/knowledge-status.schema.json
            src/main/resources/schemas/debug-state.schema.json
```

The generated schemas are checked into the repo and published as the API
contract. They include every field name, type, and nesting structure — derived
directly from the record definitions and their Jackson annotations.

**Runtime schema validation in tests:**

Using `networknt/json-schema-validator` in integration tests:

```java
@Test void statusResponseConformsToSchema() {
    String response = httpGet("/api/status");
    JsonSchema schema = schemaFactory.getSchema(
        getClass().getResourceAsStream("/schemas/status-response.schema.json"));
    Set<ValidationMessage> errors = schema.validate(objectMapper.readTree(response));
    assertThat(errors).isEmpty();
}
```

This closes the loop: schema is derived from records (compile-time), responses
are validated against schema (test-time). A field rename in the record
changes the schema, which fails the validation test. No manual field
enumeration required.

**Backward compatibility regression testing (research-informed):**

Store versioned JSON response examples in `src/test/resources/contract/`:
```
contract/status-v1.json       // current response shape
contract/knowledge-v1.json    // current /api/knowledge/status shape
```

Parametrized tests deserialize each versioned example into the current record
type. If a field is renamed or removed, deserialization fails. New versioned
files are added when the schema changes; old files are never deleted. This
pattern is from §D.2 — "never delete these files; add new ones when the
schema changes."

**Impact on `WorkerStatusMapper`:**

The three methods become factory methods returning typed records:

```java
WorkerOperationalView toOperationalView(StatusResponse proto, HealthCheckResponse health)
WorkerDebugView toDebugView(StatusResponse proto, HealthNodeView healthNode)
HealthNodeView toHealthNode(HealthCheckResponse health)
```

All 113 inline string literals disappear — field assignment is to record
constructor parameters. Compact constructors handle null coercion (the pattern
`KnowledgeStatus` already uses).

**Frontend type generation pipeline:**

The frontend has 3 duplicate `SystemStatus` interfaces with 71+ manually
maintained fields each (`api/domains/status.ts`, `stores/systemTypes.ts`,
`hooks/useStatus.ts`). Zod schema in `schemas.ts` uses `.passthrough()`
(fail-open validation). No hardcoded field name strings exist in component
code — all use TypeScript property access (`status.fieldName`), so the type
generation migration only needs to replace interface definitions, not
component code. A Java field rename breaks the frontend silently because
TypeScript compilation succeeds against the stale manual types. Two viable
generation pipelines:

| Approach | Pipeline | Effort | Trade-off |
|----------|----------|--------|-----------|
| OpenAPI intermediate | `javalin-openapi` → OpenAPI spec → `openapi-typescript` | High: requires `@OpenApi` annotations on all route handlers | Spec serves as documentation; enables future client generation |
| Direct generation | `typescript-generator` Gradle plugin → TS interfaces | Medium: configure class patterns, no route annotations | No intermediate spec; less human-readable contract |

Both pipelines produce TypeScript interfaces that replace the manual ones.
The generated types are committed to the repo (not generated on every build)
to avoid build pipeline fragility. Regeneration is an explicit step after
Java record changes.

During the field name migration period (§10.2 deprecated accessors), the
generated types include both old and new names. Frontend code migrates one
component at a time. When all references to the old name are removed, deleting
the deprecated Java accessor regenerates types without the old field →
TypeScript compile error catches any straggler.

The choice between OpenAPI and direct generation is deferred to implementation.

**/api/effective-config endpoint:**

`ResolvedConfig` with source tracing (§10.1 `ConfigResolution` record)
naturally enables a diagnostic endpoint:

```java
record EffectiveConfigEntry(
    String key,
    String value,
    String sourceName,    // "jvm_arg", "env", "settings_json", "yaml", "auto_detect", "default"
    int sourceOrdinal,
    String sourceDetail   // e.g., "-Djustsearch.data.dir=..." or "JUSTSEARCH_DATA_DIR"
) {}

GET /api/effective-config → List<EffectiveConfigEntry>
```

Non-production only (consistent with `/api/debug/state`). Replaces the
current config section of `/api/debug/state` (flat map, no source tracing)
with the "one-line log read" diagnostic that §1.2 identified as missing.

**What this solves:**

| Issue | How |
|-------|-----|
| §5.1 113 inline string literals | Record fields replace strings |
| §5.2 Same data, different names | Canonical names, deprecated accessors for migration |
| §5.3 Zero shared code between endpoints | Both endpoints use view records from same proto |
| §5.4 Three naming conventions | `@JsonNaming` per view record |
| §5.5 Contract test coverage gaps | Schema generation + validation covers all fields |
| §5.6 reasonCode casing inconsistency | Record fields enforce consistent casing per view |
| (new) No cross-boundary contract | JSON Schema published as artifact, validated by PS1 scripts |

---

### 10.3 EvalSession — Unified Server Lifecycle Abstraction

**Problem summary:** 7 independent server lifecycle implementations, 4
readiness definitions, 2 shared PS1 libraries that don't compose. Fixes to
config propagation (§1.1–§1.4) must be applied independently to each lane.

**External validation:** No IR evaluation framework (BEIR, MTEB, Pyserini,
ir-measures) manages server lifecycle — they all treat the system under test
as an in-process library or a pre-existing service. Experiment tracking
systems (MLflow, W&B) similarly don't manage SUT lifecycle. This is because
standard IR systems are single-process (Python + Lucene/FAISS library).
JustSearch's multi-process architecture (Head + Worker + optional Brain)
requires custom lifecycle management — no off-the-shelf alternative exists
(§D.3).

The closest model is **pytest's session-scoped fixture** pattern: start
process → poll readiness → yield control to tests → teardown in finally.
Pester's `BeforeAll`/`AfterAll` is the PowerShell equivalent but lacks
pytest's fixture dependency graph. The `Invoke-EvalSession` wrapper proposed
below follows this pattern directly.

MLflow's **nested runs** pattern validates session grouping: a parent run
contains child runs (one per configuration), each logging its own params
and metrics. This maps to the isolation sessions in
`run-ranking-experiments.ps1` — one server session (parent) contains multiple
evals (children) that share server config but vary query-time parameters.

**Current landscape (from codebase research):**

| Pattern | Readiness gate | Poll interval | Config propagation | Used by |
|---------|---------------|---------------|-------------------|---------|
| `Start-DevRunnerManaged` | `Wait-ApiReady` (HTTP poll, `status.ok`) | 2s | env vars + save/restore | CI lanes |
| `Start-DevRunner` (perf) | `Wait-DevRunnerReady` (JSON state, `ready_http`) | 250ms | env vars | Perf suite |
| `Start-ServerWithConfig` | HTTP poll, `status.status=="ok"` AND `indexAvailable` | 5s | inline bash env + UI_OPTS `-D` | BEIR isolation |
| Inline `Start-Runner` | varies | varies | env vars | eval-autonomous |
| `agent-battery-core.mjs` | own `waitReady()` (JS, HTTP 200) | varies | JS env | Agent battery |
| beir-eval-win.ps1 | `Invoke-RestMethodWithRetry` preflight (not loop) | exp backoff | assumes running | BEIR eval |

**Implementation hierarchy (investigation finding):**

The 7 implementations form a two-level hierarchy, not 7 independently evolved
flat implementations:

- **Leaf scripts** (direct process management, no supervisor):
  `run-ranking-experiments.ps1` (Gradle or installDist via bash),
  `beir-eval-win.ps1` (assumes running, polling-only)
- **Mid-level wrappers** (delegate to dev-runner.cjs, nearly identical to
  each other): `benchmark-ci-common.ps1`, `perf-suite-runtime-common.ps1`
- **Root process manager**: `dev-runner.cjs` (Gradle + npm supervisor,
  process tree owner, most complete cleanup — port owner detection, 10s
  deadline loop, tree kill)
- **Detached DAG wrapper**: `dev-runner-lifecycle.mjs` (detached spawn of
  dev-runner.cjs, adds supervisor crash detection via NO_ACTIVE_RUN streak
  counter)
- **Polling-only consumer** (not a lifecycle manager):
  `agent-battery-core.mjs` (readiness polling + AI activation state machine)

This means EvalSession replaces fewer truly independent implementations than
the flat count suggests. `benchmark-ci-common` and `perf-suite-runtime-common`
share identical lifecycle code; `agent-battery-core.mjs` and `beir-eval-win.ps1`
are not lifecycle managers at all.

**Ideal design:**

A single `EvalSession` module (PowerShell, since most lanes are PS1) that
provides the full lifecycle, modeled on the pytest fixture pattern (start →
yield → teardown):

```
EvalSession.psm1
├── Start-EvalServer
│   ├── -Backend <installDist|gradle|devRunner>
│   ├── -ApiPort <int>
│   ├── -DataDir <path>
│   ├── -EnvVars <hashtable>     // per-session overrides
│   ├── -UiOpts <string>         // -D system properties
│   ├── -Clean <none|soft|hard>
│   ├── -UiPort <int>            // 0 = no frontend
│   └── Returns: EvalServerHandle { Process, ApiPort, UiPort, BaseUrl, RunId, Backend }
│
├── Wait-EvalServerReady
│   ├── -Handle <EvalServerHandle>
│   ├── -Level <http|indexServing|fullStack>
│   │   http:         HTTP 200 on /api/status (server process is up)
│   │   indexServing:  http + indexAvailable==true + indexHealthy==true
│   │   fullStack:     indexServing + readiness.composites.retrieval.state in {READY, DEGRADED}
│   ├── -TimeoutSec <int>
│   ├── -PollIntervalMs <int>    // default: 1000 (compromise between 250ms and 2s)
│   └── Returns: status response object
│
├── Wait-EvalIndexIdle
│   ├── -Handle <EvalServerHandle>
│   ├── -TimeoutSec <int>
│   ├── -RequireChunkVectors <bool>
│   └── Returns: final status (7-condition gate from beir-eval-win.ps1)
│
├── Get-EvalServerStatus
│   ├── -Handle <EvalServerHandle>
│   └── Returns: parsed /api/status response (for diagnostic access mid-eval)
│
├── Invoke-EvalIngest
│   ├── -Handle <EvalServerHandle>
│   ├── -Mode <watchedRoot|ingestBatches>
│   ├── -Path <string>           // corpus path or root directory
│   ├── -Collection <string>
│   ├── -BatchSize <int>         // for ingestBatches mode
│   ├── -BackpressureHighWaterMark <int>   // queue depth threshold
│   └── Returns: ingest summary { filesIngested, durationMs }
│
├── Stop-EvalServer
│   ├── -Handle <EvalServerHandle>
│   ├── -Force <bool>
│   ├── -TimeoutSec <int>        // port-release wait
│   └── Guarantees: ports released, processes terminated, data dir intact
│
└── Invoke-EvalSession (convenience wrapper — the "fixture" pattern)
    ├── -Backend, -ApiPort, -DataDir, -EnvVars, -UiOpts, -Clean, -UiPort
    ├── -ReadyLevel <http|indexServing|fullStack>
    ├── -ReadyTimeoutSec <int>
    ├── -ScriptBlock <{param($Handle) ... }>   // "yield" — caller's eval logic
    └── Semantics: start → wait → invoke ScriptBlock → finally { stop }
```

**Backend implementations:**

| Backend | Spawns | Startup time | Use case |
|---------|--------|-------------|----------|
| `installDist` | `modules/ui/build/install/ui/bin/ui.bat` via bash | ~10-15s | Isolation experiments, rapid iteration |
| `gradle` | `gradlew.bat :modules:ui:runHeadless --no-daemon` | ~3-5min | Development, CI (guaranteed fresh) |
| `devRunner` | `node scripts/dev/dev-runner.cjs start` | ~3-5min (Gradle-backed) | Perf suite, UI evidence (supervised, frontend included) |

All three produce an `EvalServerHandle` with the same interface. `Stop-EvalServer`
dispatches by `$Handle.Backend`:

- `installDist`/`gradle`: direct process kill + java child hunt (timestamp-based)
- `devRunner`: `node dev-runner.cjs stop --run $RunId --force` + port verification

All backends should incorporate the **port owner detection** pattern from
`dev-runner.cjs` (the most complete cleanup implementation): after process
kill, poll `Get-NetTCPConnection -State Listen -LocalPort <port>` to find
the owning PID and kill it if port is not released, with a 10-second deadline
at 250ms intervals, returning a `portsClosed` boolean for verification. See
Appendix E.4 for a comparison of cleanup patterns across implementations.

**Supervisor crash detection** (from `dev-runner-lifecycle.mjs`): for the
`devRunner` backend, EvalSession should track a NO_ACTIVE_RUN streak — if
the underlying process dies without leaving `active.json`, the lifecycle
module detects and reports the failure rather than hanging on readiness
polling indefinitely.

**Config propagation (solved once):**

`Start-EvalServer` applies config through a single, documented path:

1. `-EnvVars` hashtable values are inlined into the bash command string
   (not set via `[Environment]::SetEnvironmentVariable`, which doesn't
   propagate reliably on Windows — §1.4)
2. Critical path overrides (`dataDir`, `indexBasePath`) are always passed as
   `-D` system properties via `-UiOpts`, at ordinal 500 in the `ResolvedConfig`
   model — guaranteed to win over settings.json at ordinal 300 (§1.1)
3. The function logs which config values were applied and their source
4. `Invoke-EvalSession` wraps the entire lifecycle in `try`/`finally` for
   guaranteed cleanup

**Readiness levels (unified):**

The 4 current readiness definitions (§3.3) collapse into 3 well-defined levels.
Each level is a strict superset of the previous:

```
http          ← HTTP 200 on /api/status
                (server process is alive and accepting requests)

indexServing  ← http + status.indexAvailable==true + status.indexHealthy==true
                (index is open and serving queries, but may still be ingesting)

fullStack     ← indexServing + readiness.composites.retrieval.state in {READY, DEGRADED}
                (all retrieval components operational, embeddings available)
```

The current hedging pattern (`status.status=="ok" -or status.ok==$true`) is
eliminated — each level checks specific, documented fields from the
`StatusResponse` record (§10.2). If the typed records rename a field, the
schema file changes, and a grep of the PS1 module for the old field name
catches the drift.

**Session grouping for isolation experiments:**

Following MLflow's nested runs pattern, `Invoke-EvalSession` supports multiple
eval callbacks per server session:

```powershell
Invoke-EvalSession -Backend installDist -DataDir $beirDir -EnvVars $sessionEnv `
    -ReadyLevel indexServing -ScriptBlock {
    param($Handle)
    # Multiple evals share this server — mode is query-time, no restart needed
    Run-BeirEval -Handle $Handle -OutDir "bm25-only"   -Modes @("lexical")
    Run-BeirEval -Handle $Handle -OutDir "dense-only"   -Modes @("vector")
    Run-BeirEval -Handle $Handle -OutDir "bm25-dense"   -Modes @("hybrid")
}
```

**Migration path from current state:**

| Current | Maps to |
|---------|---------|
| `Start-ServerWithConfig -UseDist` | `Invoke-EvalSession -Backend installDist -ReadyLevel indexServing` |
| `Start-DevRunnerManaged` | `Invoke-EvalSession -Backend devRunner -ReadyLevel http -Clean hard` |
| `Start-DevRunner` (perf) | `Invoke-EvalSession -Backend devRunner -ReadyLevel http -UiPort 5173` |
| Inline HTTP poll loops | `Wait-EvalServerReady -Level http` |
| `Wait-ForIndexIdle` | `Wait-EvalIndexIdle` |
| `beir-eval-win.ps1` preflight check | `Get-EvalServerStatus` + assert |

The 7 implementations are replaced by thin callers that specify their config
and eval callback. The two existing shared libraries (`benchmark-ci-common.ps1`,
`perf-suite-runtime-common.ps1`) retain their non-lifecycle functions (corpus
identity, evidence capture, artifact validation) but delegate lifecycle to
`EvalSession.psm1`.

**JavaScript counterpart (`withEvalSession`):**

`agent-battery-core.mjs` and other Node.js scripts also manage server
lifecycle via `dev-runner.cjs`. They need the same lifecycle abstraction:

```javascript
// scripts/eval/eval-session.mjs
async function withEvalSession(options, evalCallback) {
    const handle = await startEvalServer(options);
    try {
        await waitEvalServerReady(handle, options.readyLevel);
        return await evalCallback(handle);
    } finally {
        await stopEvalServer(handle);
    }
}
```

This mirrors `Invoke-EvalSession` exactly. Both implementations share
`dev-runner.cjs` as the underlying process manager for the `devRunner`
backend. The JS version adds `installDist` and `gradle` backends using
`child_process.spawn`.

Both must agree on: server handle shape (apiPort, baseUrl, runId, backend),
readiness level semantics (http, indexServing, fullStack), and status field
names (from §10.2 typed records → §10.4 schema bridge).

*Unresolved ownership question:* The external research suggests JS should
own lifecycle (closest to `dev-runner.cjs`) with PS1 delegating to a Node
wrapper. The current design has parallel PS1 + JS implementations that share
the same backends. This tension is deferred to implementation — both
approaches are viable. The key constraint is that the lifecycle contract
(start → wait → eval → stop) must be identical in both.

**Optional AI activation (investigation finding):** `agent-battery-core.mjs`
has a standalone AI activation state machine (states: `activation_requested`
→ `completed` / `failed` / `activation_timeout`, 60-second deadline). This is
not lifecycle management but an AI-specific post-readiness step. EvalSession's
JS counterpart should support optional AI activation as a composable step
after `waitEvalServerReady`, not as part of the core lifecycle contract.

**Shared utilities (`eval-common.psm1`):**

§6.1 identified 7 duplicated utility functions across `benchmark-ci-common.ps1`
and `perf-suite-runtime-common.ps1`. These are extracted into a shared
PowerShell module:

```
scripts/eval/common/eval-common.psm1
```

| Function | Current locations |
|----------|------------------|
| `Resolve-RepoRoot` | benchmark-ci-common, perf-suite-runtime-common |
| `Read-JsonBestEffort` | benchmark-ci-common, perf-suite-runtime-common |
| `Write-JsonAtomic` | benchmark-ci-common, perf-suite-runtime-common |
| `Write-JsonPretty` | benchmark-ci-common, perf-suite-runtime-common |
| `Get-FreePort` | benchmark-ci-common, perf-suite-runtime-common |
| `Test-PortInUse` | benchmark-ci-common, perf-suite-runtime-common |
| `Wait-PortFree` | benchmark-ci-common, perf-suite-runtime-common |

Import pattern: `Import-Module (Join-Path $PSScriptRoot "common/eval-common.psm1") -Force`
using `$PSScriptRoot` for reliable path resolution. The existing libraries
delete their copies and import the module instead. `EvalSession.psm1` also
imports `eval-common.psm1` for port and JSON utilities.

This is sequencing-independent — it can happen before, during, or after any
pillar implementation.

**What this solves:**

| Issue | How |
|-------|-----|
| §3.2 Seven lifecycle implementations | One module, three backends |
| §3.3 Four readiness definitions | Three levels, strict superset chain |
| §3.4 Two non-composing libraries | One library for lifecycle, existing libs keep non-lifecycle functions |
| §6.1 Diverged library interfaces | Eliminated for lifecycle; preserved for domain-specific helpers |
| §6.2 `Start-DevRunnerManaged` always `--clean hard` | `-Clean` parameter, caller chooses |
| §6.4 No shared ingest + wait pattern | `Invoke-EvalIngest` + `Wait-EvalIndexIdle` |
| §8.6 Duplicated utility functions | `eval-common.psm1` extracts 7 shared functions |
| §1.1 Settings.json override | Config propagation uses `-D` sysprops (ordinal 500) |
| §1.4 PS→bash→JVM env var chain | Solved once in `Start-EvalServer` (inline bash env) |
| (new) JS callers not covered | `withEvalSession()` mirrors PS1 contract |

---

### 10.4 Cross-Cutting: How the Three Pillars Interact

The three abstractions (`ResolvedConfig`, typed status records, `EvalSession`)
address different layers but interact at two boundaries:

**Boundary 1: Config → Server startup**

`ResolvedConfig` defines how the server resolves its own configuration.
`EvalSession` defines how eval scripts configure and start the server.
These must agree on the config propagation contract:

- `EvalSession.Start-EvalServer` passes config as `-D` sysprops (ordinal 500)
- `ResolvedConfig.Builder` reads ordinal 500 first
- Result: eval scripts can always override any config value, and the server
  always respects the override

Currently, this contract is violated (settings.json beats env var). With
`ResolvedConfig` and numeric ordinals, the contract is structural — ordinal
500 always wins by construction. No code path can bypass it.

**Boundary 2: Server responses → Eval script parsing (Schema bridge)**

Typed status records (§10.2) define the JSON response shape. `EvalSession`'s
readiness gates parse those responses. These must agree on field names.

The research (§D.2) identified the ideal mechanism: **build-time JSON Schema
as a published contract**. The pipeline:

```
Java records (source of truth)
  → victools generates JSON Schema at build time
  → schemas checked into repo at modules/app-api/src/main/resources/schemas/
  → Java integration tests validate HTTP responses against schema (networknt)
  → EvalSession.psm1 references field names documented in schema
  → A grep-based CI check verifies PS1 field references match schema properties
```

A field rename in a Java record:
1. Changes the generated schema (caught at `./gradlew.bat build`)
2. Fails the Java schema validation test (caught at `./gradlew.bat test`)
3. Is detectable in PS1 by grepping for the old field name against the new
   schema (catchable by a CI lint step)

This isn't full contract enforcement in PS1 (PowerShell has no schema
validation library comparable to networknt), but it closes the gap
significantly. The current situation — field renames break scripts silently
with no detection at any stage — is eliminated.

**Sequencing (research-informed, gap-aware):**

The three pillars can be implemented independently but have natural
dependencies revealed by the gap analysis:

1. **Typed status records** (lowest risk, highest immediate value) —
   No behavioral change, just replaces `Map<String,Object>` with records.
   Existing `KnowledgeStatus` record proves the pattern works. Schema
   generation adds contract coverage immediately.
   - Frontend type generation pipeline (OpenAPI or typescript-generator)
     can happen in parallel or after the records are stable.
   - `/api/effective-config` endpoint can be added once `ResolvedConfig`
     exists (Pillar 3), but the record shape is defined here.

2. **EvalSession + eval-common.psm1** (medium risk, highest script-side
   value) — Pure PS1/JS refactoring, independent of Java changes. Can happen
   in parallel with Pillar 1. The `Start-EvalServer` with `-UiOpts` for `-D`
   sysprops provides the config-override safety net even before
   `ResolvedConfig` exists.
   - `eval-common.psm1` (utility deduplication) has no dependencies — it
     can be done first as a low-risk warm-up.
   - JS counterpart (`withEvalSession`) is independent of PS1 work.

3. **ResolvedConfig + ConfigStore** (highest risk, highest architectural
   value) — Changes the startup sequence. Requires touching 35+ files that
   call `EnvRegistry.get()`, absorbing 9 factory classes (~36 envOrProperty
   calls), and restructuring the 7-phase `HeadlessApp` startup to 4 phases.
   Should be done last,
   when Pillars 1–2 have stabilized the external interfaces.
   - Depends on migration testing infrastructure (parity tests, completeness
     enum test) being in place first.
   - Worker snapshot mechanism depends on `ResolvedConfig` builder.
   - The ordinal system makes migration incremental: each call site can be
     migrated one at a time without breaking the others.
   - The YAML-wins precedence investigation (1 key: `searchPipelineProfile`) should happen early in
     Pillar 3 to inform the migration approach.

---

### 10.5 What This Does NOT Address

The following issues from §6 and §3 are not covered by the three pillars:

| Issue | Why excluded |
|-------|-------------|
| §3.5 Nine v1→v2 converters | Moot if v1 is sunsetted (per tempdoc 235-S1) |
| §3.6 Baseline management is convention-based | Low severity; a manifest would help but isn't architectural |
| §6.3 `Resolve-BeirBaselinePath` hardcodes two profiles | Fixed by convention or small refactor, not by an abstraction |
| §6.5 Per-lane diff scripts duplicate threshold comparison | Addressable by extracting a `compare-metric` helper into `policy-engine.mjs` |

These are ergonomic improvements that can be addressed independently as
small, targeted fixes rather than architectural changes.

---

### 10.6 Risk Register

| Risk | Severity | Mitigation |
|------|----------|------------|
| Ordinal resolution bug affects all 107+ config values | High | Parity testing (dual-read) during migration; completeness enum test catches missing keys |
| ConfigStore swap during Worker spawn creates inconsistency | Medium | Snapshot is taken at spawn time from current `AtomicReference`; swap after spawn doesn't affect running Worker |
| Frontend type generation pipeline link failure breaks build | Medium | Generated types committed to repo (not generated every build); regeneration is an explicit step |
| YAML-wins→env-wins precedence change for `searchPipelineProfile` | Low | Parity test the 1 affected key; log divergences in MIRROR mode before switching |
| 35+ file migration introduces subtle behavioral change | High | Parallel change pattern: old code returns authoritative value throughout migration |
| Worker snapshot file left behind on crash | Low | Snapshot is in `dataDir` (cleaned on next `--clean hard`); contains no secrets |
| `eval-common.psm1` import path breaks on non-standard layouts | Low | `$PSScriptRoot`-relative path; CI verifies import in test scripts |

---

### 10.7 Design Completeness Checklist

Cross-reference of all issues from §1–§6 against the three pillars:

| Area | Severity | Pillar | Resolution |
|------|----------|--------|------------|
| Worker config propagation | High | §10.1 | Config snapshot file at ordinal 450 |
| Frontend consumer migration | High | §10.2 | Type generation pipeline (OpenAPI or direct) |
| RuntimeConfig parallel resolution | High | §10.1 | ResolvedConfig subsumes both EnvRegistry and RuntimeConfig |
| /api/effective-config diagnostic | Medium | §10.2 | New endpoint exposing ConfigResolution entries |
| Settings write-back with immutable config | High | §10.1 | ConfigStore with AtomicReference + event bus |
| JS eval callers | Medium | §10.3 | `withEvalSession()` JS counterpart |
| Utility function deduplication | Low | §10.3 | `eval-common.psm1` module |
| Migration testing strategy | High | §10.1 | Parallel change with parity testing + completeness test |
| Ordinal table completeness | Medium | §10.1 | 9-slot ordinal table (0–500) with auto-detect at 150 |
| Module placement for ResolvedConfig | Low | §10.1 | `modules/configuration` — 0 JustSearch deps, 18 dependents |

---

## Appendix C: Research Data

### EnvRegistry scope

The `EnvRegistry` enum contains **107 entries** (not 63 as previously reported).
Entries group into: core paths/ports (12), telemetry (4), LLM/inference (28),
agent config (7), LLM templates/tuning (13), translator/summary pipelines (10),
AI/GPU/embedding (14), search/index (7), UI automation (5), misc (7).

No defaults are stored in the enum — defaults are passed as arguments at each
call site. 35+ files call `EnvRegistry.*.get()` across 17 modules.

### RuntimeConfig scope

`RuntimeConfig` is a facade over parsed `application.yaml` with ~80 public
methods grouped into: language (3), index/storage (25), hybrid search tuning
(12), RAG (8), policy (3), paths (3), search pipeline (1), worker resources (4),
collections/workers/infra (6), watcher (5), OCR (7). It uses its own
`RuntimeEnvResolver` and `RuntimeNodeReader` — not `EnvRegistry` — for most
resolutions, except 3 direct `EnvRegistry` calls.

### WorkerStatusMapper scope

`toUiStatusMap()` emits 55 scalar fields + 2 nested objects (9 + 6 fields each)
+ 2 conditional fields = ~72 total JSON fields.
`toDebugWorkerState()` emits 20 scalar fields + 3 nested objects = ~35 total.
`buildHealthNode()` emits 6 fields.
Grand total: ~113 JSON fields across 3 methods, all inline string literals.

### Eval infrastructure scope

28 exported functions across `benchmark-ci-common.ps1` (975 lines) and
`perf-suite-runtime-common.ps1` (644 lines). 7 functions overlap
(`Resolve-RepoRoot`, `Read-JsonBestEffort`, `Write-Json*`). 5 are unique to
ci-common (corpus identity, artifact validation). 8 are unique to perf-common
(evidence capture, scenario execution).

`dev-runner.cjs` (963 lines) provides: `start`, `status`, `stop`, `cleanup`
commands. Always uses Gradle for backend spawn. Readiness is HTTP 200 on
`/api/status` (no field parsing). Frontend spawn is optional (`--ui-port`).
Cleanup supports `none/soft/hard` data dir wipe.

### Worker config propagation

`WorkerSpawner.java` passes config via 3 mechanisms:
- 4 env vars: `JUSTSEARCH_DATA_DIR`, `JUSTSEARCH_LLM_ACCEL`,
  `JUSTSEARCH_EMBED_GPU_LAYERS`, `JUSTSEARCH_MODEL_PATH`
- 14 sysprops via `forwardPropIfSet()`: data dir (+ legacy alias), config
  path, repo root, SSOT path, plugins manifest, GPU policy, embed GPU
  layers, GPU layers, index base path, index parity, migration cutover,
  ONNX path, worker signal path (total: 4 env + 14 sysprop = 18 values)
- MMF signal bus for ongoing coordination (heartbeat, shutdown, port)

Worker entry: `IndexerWorker.main()` → `WorkerConfig.load()` →
`RuntimeConfig.load()` (YAML-first) + 2 direct `EnvRegistry` calls
(telemetry flush, worker version).

Worker lib dir: resolved by `KnowledgeServerConfig.resolveWorkerLibDir()`
in 3 steps: explicit override → production `lib/worker/` → development
Gradle `installDist` output.

### RuntimeConfig resolution

`RuntimeEnvResolver` precedence: YAML > sysprop > env > default (via
`resolveWithConflictLog`). This method is used for **1 key only**
(`searchPipelineProfile`). The ordinal table gives sysprop (500) > env (400)
> settings.json (300) > YAML (200).

5 keys registered in both `EnvRegistry` and `RuntimeEnvResolver` inline
literals: `EGRESS_BLOCK_ALL`, `LLM_ENABLED`, `LLM_MODEL_PATH`, `LLM_MODE`,
`SEARCH_PROFILE`. Same resolution algorithm, but maintenance duplication.

9 factory classes contain 88 resolve methods (~36 `envOrProperty` calls).
4 factories are YAML-only (Index, Search, Watcher, Ocr). See Appendix E.1.

### Frontend + settings flow

Frontend uses single polling loop (`useSystemStore.refreshNow()`) fetching
`/api/status` + `/api/inference/status` every 5s. 13 components reference
40+ status field names. All TypeScript interfaces are manually maintained
(no code generation). Zod schemas in `schemas.ts` are also manual, fail-open.

Settings write-back: `POST /api/settings/v2` → merge → validate → save to
disk → `maybeApply*SysProp()` for 5 keys. Source tracking via companion
`*.source` sysprop prevents operator overrides from being clobbered by GUI
changes.

Settings categorization: pure UI state (no server effect), live server
settings (sysprop applied, next use), restart-required (sysprop set but
effective on next start).

---

## Appendix D: External Research — Prior Art and Best Practices

### D.1 Configuration Resolution Frameworks

Five major frameworks were surveyed for their configuration architecture.
All share the same core principle: **layered sources with explicit priority,
source tracing, and immutable config objects after construction.**

| Framework | Priority Mechanism | Source Tracing | Immutability |
|-----------|-------------------|----------------|--------------|
| Spring Boot | Ordered `PropertySource` list (cmd args > sysprops > env > files > defaults) | `PropertySourceOrigin` → file + line number via `OriginLookup` | Constructor binding with `final` fields |
| Typesafe Config (HOCON) | `withFallback()` chain (sysprops > application.conf > reference.conf) | `ConfigValue.origin()` → `ConfigOrigin` with filename, line, resource | All objects immutable by design; `withFallback()` returns new instances |
| Quarkus SmallRye | Numeric ordinals (sysprops=400, env=300, .env=295, files=250) | `ConfigValue.getConfigSourceName()` + ordinal + location | Read-only after construction |
| Micronaut | Ordered source list (cmd > sysprops > env > files), resolved at compile time | **None built in** — no source tracing API | Compile-time generated binding |
| .NET IConfiguration | Last-registered-provider wins (files → env → cmd args) | `GetDebugView()` shows provider name per key | Read-only by interface contract |

**Key patterns relevant to JustSearch:**

1. **Constructor binding** (Spring Boot 2.2+, .NET): A single parameterized
   constructor receives all config values as final fields. No setters, no
   post-construction mutation. This is exactly the `ResolvedConfig` record
   pattern proposed in §10.1.

2. **Source tracing as a first-class return type** (SmallRye): `getConfigValue()`
   returns a `ConfigValue` wrapper containing the value, the source name, its
   ordinal, and its file location — not just `Optional<String>`. This is the
   `ConfigResolution` record proposed in §10.1.

3. **Library defaults via reference.conf** (HOCON): Libraries ship their own
   `reference.conf` with all default values; applications override in
   `application.conf`; operators override via `-D` flags. The priority chain
   is structural, not call-order dependent. This maps directly to the 5-layer
   priority chain in §10.1 (JVM arg > env > settings.json > YAML > default).

4. **Numeric ordinals** (SmallRye): Each source has an explicit ordinal number.
   Higher ordinal wins. Custom sources can declare their ordinal. This
   eliminates ambiguity about which source beats which — the priority is a
   number, not a comment in a code review.

**Anti-pattern confirmation:** The use of `System.setProperty()` as a runtime
communication channel between components is universally identified as an
anti-pattern:

- **Global mutable state**: Any thread can read/write any property at any time
  with no coordination. Thread-local isolation requires exclusive JVM-wide locks.
- **Order-dependent initialization**: Libraries that read sysprops during static
  init will pick up whatever value happens to be present, depending on class
  loading order — which is not guaranteed stable.
- **Testing consequence**: Parallel test execution is impossible for tests that
  mutate system properties. JUnit Pioneer's `@RestoreSystemProperties` requires
  exclusive locks that kill parallelism.
- **Correct use**: System properties are appropriate for read-only `-D` flags
  set at JVM startup. Using `System.setProperty()` in application code at
  runtime is the problematic pattern.

This directly validates the §10.1 design: `ResolvedConfig` eliminates all
`System.setProperty()` calls from application code. JVM `-D` args remain the
highest-priority source, read once during config construction.

**Twelve-Factor adaptation for desktop:** The original twelve-factor principle
("store config in environment variables") was written for cloud SaaS with
multiple deployment environments. For a desktop/local-first application like
JustSearch:

- User preferences (index path, GPU layers, model path) are application state,
  not deployment config — they belong in user-writable settings files, not env
  vars.
- Env vars are appropriate for CI, scripting, and integration testing.
- JVM `-D` args are for developer/operator overrides.
- The twelve-factor prohibition on config files applies to files inside the app
  distribution, not to user-space config files (`%APPDATA%`, `~/.config`).

This validates JustSearch's settings.json as a legitimate configuration source,
while confirming that it must be lower priority than env vars and JVM args.

Sources:
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Typesafe Config — GitHub](https://github.com/lightbend/config)
- [SmallRye Config Documentation](https://smallrye.io/smallrye-config/Main/)
- [.NET Configuration — Microsoft Learn](https://learn.microsoft.com/en-us/dotnet/core/extensions/configuration)
- [The Twelve-Factor App — Config](https://12factor.net/config)
- [System Properties Anti-Pattern — bmuskalla.dev](https://bmuskalla.dev/blog/2019-11-25-release-scoped-properies/)

---

### D.2 API Contract Patterns

**JSON Schema generation from Java records:**

The `victools/jsonschema-generator` library with its `jsonschema-module-jackson`
companion generates JSON Schema (Draft 2019-09 / 2020-12) directly from Java
record definitions. It handles Jackson annotations correctly:

- `@JsonProperty` → overrides schema property name
- `@JsonNaming(SnakeCaseStrategy.class)` → schema uses snake_case names
  (fixed in v4.12.0)
- `@JsonIgnore` → field excluded from schema
- `@JsonUnwrapped` → nested fields elevated to parent level

A Maven plugin generates schemas at build time:
```xml
<plugin>
  <groupId>com.github.victools</groupId>
  <artifactId>jsonschema-maven-plugin</artifactId>
  <configuration>
    <classNames>io.justsearch.app.api.StatusResponse</classNames>
    <schemaFilePath>src/main/resources/schemas</schemaFilePath>
    <modules><module><name>Jackson</name></module></modules>
  </configuration>
</plugin>
```

**Contract testing for monoliths:**

Pact and Spring Cloud Contract are designed for microservice boundaries where
consumer and provider deploy independently. For a monolith with multiple
internal endpoints (JustSearch's case), the lightest-weight approach is
**schema validation in integration tests** using `networknt/json-schema-validator`:

1. Generate schema from record at build time (victools)
2. Validate actual API response against schema in integration tests (networknt)
3. Closed loop: schema always matches the Java type, tests verify the HTTP
   response matches the schema

This replaces the manual `assertExactFields` approach in `LifecycleContractTest`
with auto-generated schemas that cover all fields by construction.

**Field name migration patterns:**

- `@JsonAlias` only works for **deserialization** (inbound). The Jackson
  maintainers explicitly rejected `writeAlso` in [issue #2383](https://github.com/FasterXML/jackson-databind/issues/2383).
- For dual-name output during migration: add a deprecated accessor method
  with `@JsonProperty("old_name")` that returns the same value. Jackson
  serializes both names. Remove the deprecated accessor when consumers migrate.
- Google AIP-180 formalizes this: renaming is "remove and add," and both names
  coexist until the old one is safely retired.

**Multiple naming conventions (camelCase vs snake_case):**

Jackson applies naming strategy at deserializer construction time — there is no
per-request switching on a single `ObjectMapper` ([issue #2006](https://github.com/FasterXML/jackson-databind/issues/2006)). Solutions:

- **Separate ObjectMapper instances** per naming convention (thread-safe)
- **`@JsonNaming` on the view record** — one record per convention
- **Explicit `@JsonProperty` on every field** — overrides any global strategy

For JustSearch, the recommended approach is view records with `@JsonNaming`:
`WorkerOperationalView` (camelCase for `/api/status`) and `WorkerDebugView`
(`@JsonNaming(SnakeCaseStrategy.class)` for `/api/debug/state`) both read from
the same proto, but serialization casing is declared on the record, not the
mapper.

**Java records as API contracts — confirmed benefits:**

- Compile-time safety: adding/removing a record field causes compilation errors
  at every construction site (vs `Map.put()` which silently succeeds)
- Immutability by default: no `Collections.unmodifiableMap()` needed
- Automatic `equals`/`hashCode`/`toString` for test assertions
- Jackson 2.12.3+ native record support — no `@JsonCreator` needed
- Compact constructor for null-safety validation (coerce nulls at construction)

The existing `KnowledgeStatus` record already demonstrates this pattern — it
has a compact constructor that coerces nulls. The §10.2 proposal extends this
to all status endpoints.

Sources:
- [victools/jsonschema-generator — GitHub](https://github.com/victools/jsonschema-generator)
- [networknt/json-schema-validator — GitHub](https://github.com/networknt/json-schema-validator)
- [Jackson @JsonAlias writeAlso rejected — #2383](https://github.com/FasterXML/jackson-databind/issues/2383)
- [Jackson multiple naming strategies — #2006](https://github.com/FasterXML/jackson-databind/issues/2006)
- [Google AIP-180 Backwards Compatibility](https://google.aip.dev/180)
- [Contract Testing vs Schema Testing — PactFlow](https://pactflow.io/blog/contract-testing-using-json-schemas-and-open-api-part-1/)

---

### D.3 Evaluation Harness Architecture

**No evaluation framework manages server lifecycle.** This is the single most
important finding. All four IR evaluation frameworks surveyed (BEIR, MTEB,
Pyserini, ir-measures) treat the retrieval system as either an in-process
library or a pre-existing external service:

| Framework | SUT Abstraction | Lifecycle Management |
|-----------|----------------|---------------------|
| BEIR | `BaseSearch` interface (encode_queries, encode_corpus) | None — in-process; Elasticsearch must pre-exist |
| MTEB | `EncoderProtocol` (structural typing, `encode()` method) | None — fully in-process |
| Pyserini | `LuceneSearcher` / `FaissSearcher` wrapping static index files | None — Lucene is a library |
| ir-measures | `Qrel` + `ScoredDoc` namedtuples | None — pure metric computation |
| MLflow | None — logging client only | Tracking server is separate; never manages SUT |
| W&B | None — logging client only | Agents don't manage SUT process |

**Why JustSearch is different:** These frameworks evaluate in-process models
(embedding models, rerankers) or static index files (pre-built Lucene/FAISS).
JustSearch is a multi-process architecture (Head + Worker + optional Brain)
where the system under test is a running server. No off-the-shelf evaluation
harness covers this use case — server lifecycle must be custom.

**The test framework pattern is the right model:** Only pytest (session-scoped
fixtures) and Pester (`BeforeAll`/`AfterAll`) have explicit lifecycle
primitives. Both use the same pattern:

1. Start process (`subprocess.Popen` / `Start-Process`)
2. Poll readiness (HTTP health check loop)
3. Yield control to tests/evaluations
4. Teardown in `finally` / `AfterAll` (kill process, release ports)

This is exactly the `Invoke-EvalSession` pattern proposed in §10.3:
start → wait → yield to eval callback → finally { stop }.

**Configuration variation patterns from ML frameworks:**

- **MLflow nested runs**: A parent run contains child runs, one per
  configuration. Each child logs its own params and metrics. The parent
  serves as organizational grouping. This maps to the session-based
  isolation in §10.3: one server session (parent) contains multiple evals
  (children) that share the same server config but vary query-time params.

- **W&B sweeps**: A controller assigns parameter combinations to agents.
  Each agent runs one evaluation and reports back. This is heavier than
  needed for JustSearch's 5-7 configuration isolation, but the
  controller/agent separation is a useful pattern for future automated
  parameter search.

- **BEIR embedding caching**: `encode_and_retrieve()` allows reusing encoded
  corpus vectors across runs with different query configurations. This
  validates the session grouping in §10.3 — configs with the same env vars
  (same embeddings) share a server session and only vary the query-time
  `mode` parameter.

**Key design insight for EvalSession:**

The `EvalSession` abstraction should follow the test fixture model (start →
yield → teardown), not the ML experiment model (log-only). JustSearch's
evaluation requires process lifecycle management because the system is a
server, not a library. The ML experiment frameworks (MLflow, W&B) are useful
for tracking results, but not for managing the thing being evaluated.

The §10.3 `Invoke-EvalSession` with its `-ScriptBlock` parameter is the
PowerShell equivalent of pytest's `yield` fixture — the caller provides the
evaluation logic, and the lifecycle wrapper handles start/wait/stop.

Sources:
- [BEIR — GitHub](https://github.com/beir-cellar/beir)
- [MTEB — GitHub](https://github.com/embeddings-benchmark/mteb)
- [Pyserini — GitHub](https://github.com/castorini/pyserini)
- [ir-measures documentation](https://ir-measur.es/en/latest/)
- [MLflow Tracking API](https://mlflow.org/docs/2.10.1/tracking/tracking-api.html)
- [W&B Sweeps](https://docs.wandb.ai/models/sweeps)
- [pytest fixtures](https://docs.pytest.org/en/stable/how-to/fixtures.html)
- [Pester BeforeAll](https://pester.dev/docs/commands/BeforeAll)

---

### D.4 Synthesis: How External Research Validates and Refines §10

| §10 Proposal | External Validation | Refinement |
|--------------|--------------------|----|
| §10.1 ResolvedConfig with 5-layer priority | All 5 frameworks use the same pattern: JVM/cmd args > env > files > defaults. Source tracing is standard (4 of 5 have it). | Add numeric ordinals (SmallRye pattern) for unambiguous priority. Return `ConfigResolution` record from every lookup (not just log it). |
| §10.1 Eliminate System.setProperty | Universally confirmed as anti-pattern: global mutable state, thread-unsafe, order-dependent initialization. | No refinement needed — the research strongly validates this decision. |
| §10.1 Settings.json as a config source | Twelve-Factor adapted for desktop: user settings files are legitimate, but must be lower priority than env vars and JVM args. | Confirmed: settings.json at layer 3 (below env vars at layer 2) is correct. |
| §10.2 Typed status records | Java records as API contracts: compile-time safety, immutability, Jackson 2.12.3+ native support. `KnowledgeStatus` already uses this pattern. | Add build-time JSON Schema generation (victools) + runtime schema validation (networknt) for full contract coverage. |
| §10.2 Field name unification | `@JsonAlias` is inbound-only; dual-name output requires deprecated accessor methods. Google AIP-180 formalizes the migration pattern. | Use `@JsonNaming` per view record for camelCase/snake_case split. Add deprecated accessors for renamed fields during migration. |
| §10.3 EvalSession lifecycle | No eval framework manages SUT lifecycle. pytest/Pester session fixtures are the right model: start → yield → teardown. | Confirmed: `Invoke-EvalSession` with `-ScriptBlock` is architecturally correct. No off-the-shelf alternative exists. |
| §10.3 Session grouping | BEIR embedding caching validates sharing a server session across configs that only vary query-time params. | Confirmed: session grouping is a known optimization in IR evaluation. |
| §10.4 Schema as cross-boundary contract | Schema validation (networknt) is the lightest-weight approach for monoliths. Pact/Spring Cloud Contract are overkill for internal endpoints. | Generate schema from records at build time, validate in tests. Publish schema files as the contract between Java and PS1 scripts. |

### D.5 Additional Research Sources (Gap Resolution)

**Migration testing:**
- Parallel Change pattern — Martin Fowler
- Parity testing with feature flags — Harness
- Spring Boot 3.0 ConfigurationProperties migration guide
- Reflection-driven completeness testing — Flyway/Room analogy

**Ordinal conflicts:**
- SmallRye Config ordinal tiebreaking: non-deterministic, use distinct ordinals
- MicroProfile Config spec: framework values at ordinals 0-100
- SmallRye ConfigSourceFactory for computed/derived values
- HOCON `${substitution}` for self-referential config

**Two-process propagation:**
- Config snapshot file pattern — Gradle Worker API
- Stdin piping — java-stdio-ipc
- Spring Cloud Config Server — applicable concept, over-engineered for
  same-machine

**Immutable config + runtime changes:**
- `AtomicReference<ResolvedConfig>` + event bus — SEI CERT VNA01-J, Guava
  EventBus, Apache Commons Configuration Events
- Spring Cloud `@RefreshScope` — proxy + cache invalidation (Spring-only)
- Micronaut `@Refreshable` — compile-time proxy (Micronaut-only)

**TypeScript generation:**
- `javalin-openapi` — compile-time OpenAPI spec from Javalin annotations
- `openapi-typescript` — type-only TS generation from OpenAPI
- `typescript-generator` — direct Java-to-TS (Gradle plugin)

**PowerShell modules:**
- `.psm1` with `Export-ModuleMember` — established pattern for shared utils
- `Import-Module (Join-Path $PSScriptRoot "...")` — reliable path resolution
- `-Force` flag for interactive sessions

**JS eval harness:**
- "One language owns lifecycle" — polyglot test infrastructure pattern
- Node.js as orchestration layer — closest to `dev-runner.cjs`

---

## Appendix E: Implementation Investigation Data

Investigation conducted to resolve uncertainties in the §10 ideal state
design. Findings are referenced inline in §10.1–§10.3.

### E.1 Factory Class Inventory

| Factory | Methods | envOrProperty | Pattern |
|---------|---------|---------------|---------|
| `RuntimePolicyConfigFactory` | 5 | mixed (3) | Mixed: YAML-only + env/sysprop |
| `RuntimeHybridSearchConfigFactory` | 13 | 13 | All envOrProperty, numeric bounds |
| `RuntimeRagConfigFactory` | 7 | 7 | All envOrProperty |
| `RuntimeIndexConfigFactory` | 18 | few | Mostly YAML-only; `resolveVector()` has try-catch |
| `RuntimeWorkerConfigFactory` | 8 | 11 | Builds DTO objects (host/port/deadline) |
| `RuntimeInfraConfigFactory` | 2 | 2 | Port validation (0-65535 range check) |
| `RuntimeSearchConfigFactory` | 17 | 0 | YAML-only; has `validate()` method |
| `RuntimeWatcherConfigFactory` | 5 | 0 | YAML-only |
| `RuntimeOcrConfigFactory` | 6 | 0 | YAML-only; array parsing |
| **Total** | **88** | **~36** | |

4 YAML-only factories (Index, Search, Watcher, Ocr) need only ordinal 200
reads in `ResolvedConfig.Builder`. This reduces the envOrProperty migration
scope from "all 88 methods" to ~36 methods that need full ordinal chain
handling.

No non-standard resolution patterns were found — all factories use either
`envOrProperty(envKey, propKey, fallback)` or direct `RuntimeNodeReader`
reads. The only exception is `RuntimeIndexConfigFactory.resolveVector()`
which wraps `envOrProperty` calls in try-catch for `NumberFormatException`
with debug logging.

### E.2 HeadlessApp Startup Phase Map

| Phase | What | Sysprop Writes | Key Ordering Constraint |
|-------|------|----------------|------------------------|
| 1 | UI settings load + sysprop mirror | 8 props (`index.base_path`, `llama.lib.path`, `llm.model_path`, `server.exe`, `model.path`, `exclude_patterns`, `gpu.layers`, `context.size`) + `.source` companions | None (first phase) |
| 2 | Auto-select CUDA12 variant | `server.exe`, `server.exe.source` (conditional) | Phase 1 (needs settings for GPU detection) |
| 3 | Headless profile setup | 4 hardcoded props (`health.grpc.disable`, `parity.allow_mismatch`, `health.port`, `health.host`) | None |
| 4 | Telemetry init | None (reads only) | Phase 1 (data dir must be resolved) |
| 5 | Enterprise policy snapshot | None (reads only) | Phases 1-3 (needs config for policy evaluation) |
| 6 | Knowledge Server (Worker) spawn | None (config forwarded via ProcessBuilder) | Phase 5 (policy gates Worker GPU config) |
| 7 | AppFacade + LocalApiServer init | None (writes port files + stdout signals) | Phase 6 (Worker is core dependency) |

**Collapse to 4 phases:** Phases 1-3 → `ResolvedConfig.Builder` (all config
resolution). Phase 4 → telemetry init (side effects). Phases 5-6 → start
Worker (policy + spawn). Phase 7 → start API.

### E.3 Worker Config Values (18 total)

**Environment variables (4):**

| Variable | Source |
|----------|--------|
| `JUSTSEARCH_DATA_DIR` | Always set from `config.dataDir()` |
| `JUSTSEARCH_LLM_ACCEL` | Forwarded if set in parent |
| `JUSTSEARCH_EMBED_GPU_LAYERS` | Forwarded if set in parent |
| `JUSTSEARCH_MODEL_PATH` | Forwarded if set in env; falls back to sysprop `justsearch.model.path` |

**System properties (14):**

| Property | Category |
|----------|----------|
| `justsearch.data.dir` | Data (canonical) |
| `justsearch.data_dir` | Data (legacy alias) |
| `justsearch.worker.signal_path` | IPC |
| `justsearch.config` | Config path |
| `justsearch.repo.root` | Repo root |
| `justsearch.ssot.path` | SSOT |
| `justsearch.plugins.manifest` | Plugins |
| `justsearch.policy.gpuAccelerationEnabled` | GPU policy |
| `justsearch.embed.gpu.layers` | GPU tuning |
| `justsearch.gpu.layers` | GPU tuning |
| `justsearch.index.base_path` | Index location |
| `justsearch.index.parity.allow_mismatch` | Dev/demo mode |
| `justsearch.index.migration.cutover.max_failed_jobs` | Migration |
| `onnxruntime.native.path` | ONNX runtime (explicit or derived from variant) |

Plus pass-through: `JUSTSEARCH_JVM_OPTS` env var (split by whitespace, added
to Worker command line).

### E.4 Windows Process Cleanup Patterns

| Implementation | Kill Method | Port Verification | Child Handling | Crash Detection |
|----------------|-------------|-------------------|----------------|-----------------|
| `dev-runner.cjs` | `taskkill /PID /T /F` (tree kill) | `Get-NetTCPConnection` → kill owner PID; 10s deadline at 250ms | Tree kill covers children | N/A (is the supervisor) |
| `run-ranking-experiments.ps1` | `Stop-Process -Force` | None | Timestamp-based Java child hunt (`StartTime -gt $Process.StartTime.AddSeconds(-10)`) | None |
| `dev-runner-lifecycle.mjs` | Delegates to dev-runner.cjs | Delegates; polls port status up to 10 times | Delegates | NO_ACTIVE_RUN streak counter |
| `agent-battery-core.mjs` | N/A (polling-only) | N/A | N/A | N/A |

EvalSession should adopt the `dev-runner.cjs` pattern (most complete) for all
backends. The timestamp-based child hunt in `run-ranking-experiments.ps1` is
fragile and should be replaced by tree kill + port verification.

### E.5 Confidence Assessment After Investigation

| Pillar | Before | After | Key Factor |
|--------|--------|-------|------------|
| §10.2 Typed Status Records | 85% | 90% | WorkerStatusMapper is purely mechanical; no @JsonUnwrapped collision risk; 3 duplicate frontend interfaces confirmed eliminable |
| §10.3 EvalSession | ~65% | ~65% | Hierarchy clarified (fewer truly independent implementations) but Windows process cleanup complexity remains; AI activation pattern identified |
| §10.1 ResolvedConfig | 35% | 50% | YAML-wins reduced from 4 keys to 1; 4 of 9 factories are YAML-only (simpler); module placement confirmed safe; phase collapse realistic (7→4 not 8→3) |

---

## 11. Implementation Review

Post-implementation review of the 28-commit implementation (20 implementation +
8 fix commits, 61 files changed) on branch `feat/246-eval-centralization`.

### 11.1 Review Process

A self-review identified **32 issues** across all three pillars. All actionable
issues have been resolved. The fix work spanned 15 implementation steps across
two sessions.

### 11.2 Issue Tracker (Final State)

| ID | Severity | Summary | Resolution |
|----|----------|---------|------------|
| C1 | Critical | `unescapeJsonString` replacement order corrupts Windows paths | **Fixed** — replaced custom JSON with Jackson `ObjectMapper` (also fixes H7, L6) |
| C2 | Critical | `Wait-EvalIndexIdle` accesses nonexistent field — always times out | **Fixed** — switched to `/api/status`, updated field names |
| C3 | Critical | `@JsonUnwrapped` duplicate `aiReady`/`embeddingReady` keys | **Fixed** — `@JsonIgnore` on `WorkerOperationalView` components |
| H1 | High | YAML `llm.*` keys contributed under wrong namespace | **Fixed** — changed to `justsearch.llm.*` prefix |
| H2 | High | Worker snapshot write+forward but no load | **Fixed** — added `loadWorkerSnapshotFromSysprop()` in Worker main |
| H3 | High | Missing validation/clamping from factory classes | **Fixed** — RAG, HybridSearch, Index, Ports clamping added |
| H4 | High | `beir-eval-win.ps1` not actually migrated | **Fixed** — delegates `Wait-EvalIndexIdle`, `Invoke-EvalIngest` |
| H5 | High | `Invoke-EvalIngest` infinite loop risk | **Fixed** — `-TimeoutSeconds` parameter with deadline |
| H6 | High | `Invoke-EvalIngest` silently swallows failures | **Fixed** — `-MaxRetries` with exponential backoff, throws on final failure |
| H7 | High | JSON snapshot parser fragile | **Fixed** by C1 (Jackson) |
| M1 | Medium | `benchmark-ci-common.ps1` lifecycle not migrated | **Fixed** — legacy lifecycle functions deleted (zero external callers); `EvalSession.psm1` is sole API |
| M2 | Medium | `perf-suite-runtime-common.ps1` lifecycle not migrated | **Fixed** — EvalSession imported, deprecation comments added |
| M3 | Medium | `run-ranking-experiments.ps1` ignores `$Handle` | **Fixed** — callbacks pass `$Handle.BaseUrl` |
| M4 | Medium | `eval-session.mjs` missing ingest function | **Fixed** — added `ingestEval()` with watchedRoot + ingestBatches modes |
| M5 | Medium | SIGTERM/SIGKILL meaningless on Windows | **Fixed** — `taskkill.exe /PID /T /F` on Windows |
| M6 | Medium | Search sub-record has 3 of ~20 fields | **Deferred** — keys accessible via resolutions map; typed accessors are convenience |
| M7 | Medium | Static singleton ConfigStore | **Deferred** — constructor injection requires Javalin init refactor |
| M8 | Medium | Incomplete `putSettings()` coverage | **Fixed** via M9 extraction |
| M9 | Medium | Duplicated settings contribution logic | **Fixed** — `HeadlessApp.contributeUiSettings()` shared method |
| M10 | Medium | Contract test coverage gaps | **Fixed** — expanded to 18 field assertions |
| M11 | Medium | Legacy accessor fields missing from fixtures | **Fixed** — `knowledgeStatusViewEmitsLegacyFieldNames` test |
| M12 | Medium | Frontend missing readiness dimensions | **Fixed** — `chunkEmbedding`, `lambdamartModel` added |
| L1 | Low | `Resolve-RepoRoot` rigid nesting assumption | **Fixed** — walks up tree looking for `.git` |
| L2 | Low | Single-quote escaping in env var inlining | **Fixed** — `ConvertTo-BashSafeValue` helper |
| L3 | Low | Empty DataDir passed to devRunner | **Fixed** — conditional `--data-dir` argument |
| L4 | Low | Timestamp-based child hunt fragility | **Fixed** — replaced with port verification |
| L5 | Low | Partial JSON race in devRunner status | **Fixed** — retry loop (3 attempts with delay) |
| L6 | Low | Control characters not escaped in snapshot JSON | **Fixed** by C1 (Jackson) |
| L7 | Low | Unused `defaultValue()` infrastructure | **No-op** — 3-arg constructor IS used |
| L8 | Low | Broad exception catch in `rebuildConfigStore` | **Fixed** — widened to `Exception` for consistency with HeadlessApp (reversed initial narrowing per P0-F parity analysis) |
| L9 | Low | Inconsistent null coercion for diskPressure | **No-op** — intentionally nullable, `@JsonInclude(NON_NULL)` |
| L10 | Low | Frontend missing `modelInfo` in LLM type | **No-op** — field already exists |

**Summary:** 27 fixed, 2 deferred, 3 no-ops. All critical and high issues resolved.

### 11.3 Migration State (Honest Assessment)

**Typed Status Records (Pillar 1): Complete.**
All 8 view records implemented. JSON Schema generation pipeline operational
(victools generates Draft 2020-12 schemas, networknt validates serialized
instances, drift detection catches field renames). Backward compatibility
fixtures (`status-v1.json`, `knowledge-status-v1.json`, `debug-state-v1.json`)
in place. Frontend types updated. This pillar is fully delivered.

**EvalSession (Pillar 2): Infrastructure complete, migration partial.**

| Consumer | Status | Gap |
|----------|--------|-----|
| `run-ranking-experiments.ps1` | Full delegation via `Invoke-EvalSession` | None |
| `beir-eval-win.ps1` | `Wait-EvalIndexIdle` + `Invoke-EvalIngest` delegated | Inline `ingest_batches` flow retained (specialized) |
| `benchmark-ci-common.ps1` | Legacy lifecycle functions deleted (zero callers) | None — uses EvalSession.psm1 imports only |
| `perf-suite-runtime-common.ps1` | EvalSession imported | `Wait-DevRunnerReady` retained (dev-runner JSON status + UI port readiness) |
| `run-claim-b-suite-win.ps1` | Full delegation via `Invoke-EvalSession` | None |

The JS counterpart (`eval-session.mjs`) has `withEvalSession()` wrapper and
all core functions. Missing: UI port readiness checking, CI env var management.

**ResolvedConfig (Pillar 3): Consumer migration complete (tempdoc 247).**
The builder, ordinal chain, ConfigStore, worker snapshot, and
`/api/effective-config` are all built. Tempdoc 247 completed all 6 phases
(0-5) of consumer migration:

| System | Files | Call Sites | Status |
|--------|-------|------------|--------|
| `EnvRegistry.*.get()` (old) | ~20 | ~22 | Permanent — bootstrap paths, ai-worker, ConfigStore bridge |
| `RuntimeConfig` factories (old) | ~10 | ~15 | Retained — complex composite types (indexSort, boosts, collections, workerAi) |
| `ResolvedConfig` / `ConfigStore` (new) | ~35 | ~148 | **Primary** — all migrated consumers read from here |

The parity migration strategy (§10.1: expand → parity → migrate → contract)
is at step 3 (migrate). Step 4 (contract — remove old factories) is blocked
by complex composite types not yet in ResolvedConfig. See tempdoc 247 for
full details.

### 11.4 Test Coverage

| Area | Tests | Notes |
|------|-------|-------|
| ResolvedConfigBuilder | 50+ | Core resolution, Windows path round-trip, YAML LLM visibility, 8 clamping tests, 7 schema mismatch policy normalization tests |
| ConfigParityTest | 20+ | YAML parity (6 groups), known divergences, default convergence, EnvRegistry parity, sub-record spot checks |
| ConfigStoreRebuilderTest | 3 | Sysprop pickup after rebuild, null safety, config completeness |
| StatusRecordSchemaTest | 13 | Serialization, duplicate key detection, legacy field names |
| LifecycleContractTest | 8 | Real HTTP server, JSON output validation |
| EvalSession (PS1/JS) | None | Verified via compilation and manual server lifecycle |

---

## 12. Lessons Learned

Honest assessment of systematic errors in the initial implementation and
review, informed by the pre-fix confidence assessment.

### 12.1 "Simple fix" overconfidence

**C1** was initially assessed as "simple fix (reorder replacements)."
Empirical testing proved the reorder approach fundamentally broken — chained
string replacement cannot correctly handle `\\` followed by `n` in the same
value. The correct fix required replacing the entire custom JSON serialization
with Jackson `ObjectMapper`. Lesson: **test before declaring a fix trivial.**

### 12.2 Understated scope in review findings

**H1** was described as affecting only `searchPipelineProfile`. Verification
of `RuntimePolicyConfigFactory` revealed all 5 policy keys have YAML-wins
behavior. The fix was still a key rename, but the behavioral impact (5 keys
changing from YAML-wins to env-wins precedence) was larger than stated.
Lesson: **verify the full scope of a pattern, not just the first instance.**

### 12.3 Design decisions masked as bug fixes

**C3** was described as "remove or rename" the duplicate `aiReady` field.
In reality, the `toMap()` bridge, frontend consumers, and the question of
which `aiReady` source is canonical (health probe vs readiness envelope)
made this a behavioral decision. The `@JsonIgnore` fix chose the readiness
envelope as canonical. Lesson: **serialization fixes can be behavioral
changes in disguise.**

### 12.4 Syntax-over-semantics migration checking

The implementation was initially marked "implemented" after verifying that
`Import-Module` statements existed in consumer scripts. But 3 of 4 consumers
had the import without calling any EvalSession functions — the migration was
syntactically present but semantically incomplete. Lesson: **verify function
call sites, not just imports.**

---

## 13. Remaining Migration Work

This tempdoc's infrastructure is complete — all three pillars are built,
tested, and operational. What remains is migrating consumers off the old
systems onto the new ones. This section scopes that work.

### 13.1 ResolvedConfig Consumer Migration → Tempdoc 247 — COMPLETE ✅

**Scope:** 113 `EnvRegistry.*.get()` call sites across 41 files in 12 modules,
plus ~88 `RuntimeConfig` factory methods across 50 files.

**Tracked in:** `docs/tempdocs/247-resolvedconfig-consumer-migration.md`

**All 6 phases completed:**

| Phase | Scope | Outcome |
|-------|-------|---------|
| P0: Prerequisites | 6 investigations, default fixes, YAML wiring, factory logic replication, parity tests | All 6 investigations completed; 2 default mismatches fixed; `contributeYaml()` wired; `normalizeSchemaMismatchPolicy()` added; `ConfigParityTest` with 5 nested groups |
| P1: Startup reads | ~148 call sites across ~35 files, 10 modules | All migrated to `ConfigStore.globalOrNull()` / `global()`. 4 new sub-records: `Llm` (34 fields), `Agent` (7), `Summary` (7), `Translator` (4) |
| P2: Dynamic reads | 5 static + 1 dynamic | 5 effectively-static reads migrated; 1 genuinely dynamic (`SERVER_EXE` in `OnlineAiServiceImpl`) remains on EnvRegistry |
| P3: Rebuild triggers | 3 runtime communication channels | `ConfigStoreRebuilder` utility extracted; triggers in `RuntimeActivationService`, `AiInstallService`, `AiPackImportService`. Policy channel confirmed NOT stale |
| P4: RuntimeConfig consumers | adapters-lucene (16 sites), app-ai (2 sites) | Migrated to `ResolvedConfig`. RuntimeConfig retained only for complex composite types (`indexSort`, `boosts`, `collections`, `workerAi`, `translatorHealth`) |
| P5: EnvRegistry trim | 1 feasible site | `EffectiveConfigController.keyAiDisabled()` migrated. EnvRegistry `.get()` methods are permanent infrastructure (bootstrap, ai-worker, ConfigStore bridge) |

**Post-migration hardening:** 4 hardening fixes (H1-H4), 1 CRITICAL bug fix
(schema mismatch policy case mismatch), 5 HIGH default value fixes (LLM
builder defaults), 3 new test classes (`ConfigParityTest`,
`ResolvedConfigBuilderTest.SchemaMismatchPolicy`, `ConfigStoreRebuilderTest`).

**What remains on old systems (permanent):**
- ~22 `EnvRegistry.*.get()` calls: bootstrap paths, ai-worker process, ConfigStore bridge
- ~15 `RuntimeConfig.load()` calls: complex composite types, structural YAML sources, ai-worker, benchmarks
- 9 factory classes: cannot be deleted until composite types are migrated (separate tempdoc)

### 13.2 EvalSession Consumer Migration — COMPLETE ✅

Three gaps were identified, all now resolved:

**Gap 1: `benchmark-ci-common.ps1` lifecycle functions.** — **Fixed (deleted)**
~~`Start-DevRunnerManaged` has CI-specific env var save/restore and corpus
identity management that EvalSession doesn't replicate.~~

Investigation found **zero external callers** of `Start-DevRunnerManaged`,
`Stop-DevRunnerBestEffort`, `Restart-DevRunnerManaged`, and `Wait-ApiReady`.
All four functions were dead code — only called internally within each other.
All CI scripts that dot-source `benchmark-ci-common.ps1` use other functions
(corpus identity, artifact resolution, step logging) but not the lifecycle
wrappers. Deleted all four functions (~130 lines). New code should use
`Start-EvalServer` / `Stop-EvalServer` / `Wait-EvalServerReady` from
`EvalSession.psm1` directly.

**Gap 2: `perf-suite-runtime-common.ps1` readiness.**
`Wait-DevRunnerReady` checks dev-runner JSON status (`readiness.ready_http`)
AND UI port listening (`ports.ui.listening`). EvalSession's
`Wait-EvalServerReady` uses `/api/status` HTTP polling, which doesn't verify
UI port. Options:
- (a) Add `-RequireUiPort` to `Wait-EvalServerReady`
- (b) Keep `Wait-DevRunnerReady` as-is (only 1 caller: `Invoke-PerfSuiteScenarioRun`)

Option (b) is pragmatic — the perf suite is the only consumer that needs
UI port readiness, and it works today. **Decision: keep as-is (option b).**

**Gap 3: `run-claim-b-suite-win.ps1`.** — **Fixed**
~~Has its own local `Start-DevRunner`/`Stop-DevRunner` that don't use any
shared library.~~ Migrated to `Invoke-EvalSession` with `-Backend devRunner
-Clean hard` per iteration, matching the `run-ranking-experiments.ps1` pattern.

### 13.3 Frontend Type Generation (Deferred — Needs Design Decision)

The tempdoc §10.2 designed two pipeline options:

| Approach | Pipeline | Status |
|----------|----------|--------|
| OpenAPI intermediate | `javalin-openapi` → spec → `openapi-typescript` | Not started |
| Direct generation | `typescript-generator` Gradle plugin → TS interfaces | Not started |

Frontend types are currently manual (`systemTypes.ts`, `status.ts`). The
JSON Schema pipeline (victools) is built and could serve as the source for
generated types, but no generation step exists yet.

This is a design decision (which pipeline?) followed by a medium implementation.
Not blocking — manual types work, and the schema drift tests catch Java-side
renames at build time. Frontend drift from Java is the remaining gap.

### 13.4 What 246 Accomplished vs. What Remains

| Pillar | Built (246) | Migration (247) | Remaining |
|--------|-------------|-----------------|-----------|
| Typed Status Records | 8 view records, schema generation, contract tests, backward compat | N/A | Frontend type generation pipeline |
| EvalSession | PS1 module + JS module, 4 of 5 consumers integrated, claim-b-suite migrated, CI lifecycle dead code deleted | N/A | None — all gaps resolved |
| ResolvedConfig | Builder, ordinal chain, ConfigStore, worker snapshot, /api/effective-config | **~148 call sites migrated**, 4 sub-records added, rebuild triggers, parity tests | Complex composite type migration (indexSort, boosts, collections), factory deletion |

The centralization **infrastructure** is complete. The ResolvedConfig
**consumer migration** (the dominant remaining work) is complete via
tempdoc 247. The remaining gaps are:
- **EvalSession**: All 3 gaps resolved (Gap 1 deleted, Gap 2 keep-as-is, Gap 3 migrated)
- **Frontend types**: pipeline design decision needed (§13.3, §13.6.3)
- **Code hygiene**: All 6 open items fixed (§13.5)
- **ResolvedConfig contract phase**: 9 factory classes retained for complex
  composite types — deletion requires a separate tempdoc
- **Search sub-record / ConfigStore DI**: deferred, no current need (§13.6.4-5)

### 13.5 Code Hygiene Items from §1-§8 (All Resolved)

Items from the original analysis (§1-§8) identified after the three pillar
implementations and tempdoc 247 migration — all now resolved:

| # | Section | Issue | Severity | Status |
|---|---------|-------|----------|--------|
| 1 | §2.1 | `UiSettingsStore.load()` reads disk in IN_MEMORY mode | Medium | **Fixed** — `load()` now returns `new UiSettings()` immediately when `mode == IN_MEMORY`, matching the `save()` guard |
| 2 | §4.4 | `llama.lib.path` missing if-blank guard | Low | **Fixed** — uses `SystemPropertyUtils.setSysPropIfBlank()` |
| 3 | §4.8 | `setSysPropIfBlank` duplicated across 3 files | Low | **Fixed** — extracted to `SystemPropertyUtils` in `modules/configuration` |
| 4 | §4.11 | Silent exception swallowing in `resolveAiHome()` | Low | **Fixed** — both catch blocks now log at WARN |
| 5 | §6.3 | `Resolve-BeirBaselinePath` hardcodes two profile IDs | Low | **Fixed** — refactored to data-driven lookup table |
| 6 | §6.5 | Per-lane diff scripts duplicate threshold comparison | Low | **Fixed** — extracted `isRegressed()` to `policy-engine.mjs` |

All 6 items from the original analysis are now resolved.

### 13.6 Investigation of Remaining Items (Disposition Analysis)

Deep investigation of all items not addressed above — assessment of each
item's feasibility, risk, and recommended disposition.

#### 13.6.1 §13.5 #1: `UiSettingsStore.load()` IN_MEMORY Mode (Medium)

**Investigation findings:**

`UiSettingsStore.load()` unconditionally reads from disk regardless of
`PersistenceMode`. The `IN_MEMORY` guard only exists in `save()`:

```java
// save() — guarded:
if (settings == null || mode == PersistenceMode.IN_MEMORY) return;

// load() — NOT guarded:
public UiSettings load() {
    if (!Files.exists(settingsFile)) return new UiSettings();
    return MAPPER.readValue(settingsFile.toFile(), UiSettings.class);
}
```

This asymmetry undermines the isolation intent documented in ADR 0008:
IN_MEMORY exists to prevent dev `settings.json` from contaminating prod/CI
runs. Reading from disk violates that contract.

**Scope:** 13 call sites across 7 files. `RuntimeActivationService` alone
calls `load()` 6 times in a single activation flow, each hitting disk.

**Fix:** Guard `load()` to return `new UiSettings()` immediately when
`mode == IN_MEMORY`. This is semantically correct: if we don't persist
writes, we shouldn't read stale state either.

```java
public UiSettings load() {
    if (mode == PersistenceMode.IN_MEMORY) return new UiSettings();
    if (!Files.exists(settingsFile)) return new UiSettings();
    // ... existing disk-read logic
}
```

**Risk:** Very low. IN_MEMORY is only active in prod mode
(`justsearch.prod=true`) or when explicitly set. In prod mode, settings
should already be default (no user-profile `settings.json` exists). Any
caller relying on disk-read in IN_MEMORY mode is a bug.

**Complication:** `HeadlessApp.main()` reads settings at line 113 *before*
contributing them to the ordinal chain. If `mode == IN_MEMORY`, this would
return defaults, meaning no UI settings contribute to sysprops. This is
actually the correct behavior for prod (env vars and `-D` flags should
drive everything), but needs verification that no prod deployment relies
on `settings.json` values when `justsearch.prod=true`.

**Status: Fixed.** Added `if (mode == PersistenceMode.IN_MEMORY) return new UiSettings();`
at the top of `load()`. Three tests added to `UiSettingsStorePersistenceModeTest`:
IN_MEMORY returns defaults (ignores file on disk), READ_WRITE reads from disk,
IN_MEMORY handles missing file gracefully.

#### 13.6.2 §13.2 Gap 1: CI Lifecycle Wrapper Delegation (Medium)

**Investigation findings:**

`benchmark-ci-common.ps1` already imports `EvalSession.psm1` (line 4) and
`Wait-ApiReady` already delegates to `Wait-EvalServerReady`. Three legacy
functions remain:

- `Start-DevRunnerManaged` (68 lines) — sets CI env vars (`CI=true`,
  `BACKEND_PORT_TIMEOUT_MS`, `BACKEND_READY_TIMEOUT_MS`), launches
  `dev-runner.cjs start` via `Start-Process`, calls `Wait-ApiReady`
- `Stop-DevRunnerBestEffort` (13 lines) — fire-and-forget `dev-runner.cjs stop`
- `Restart-DevRunnerManaged` (26 lines) — kill + stop + start

All three have doc comments saying "prefer EvalSession.psm1 for new code."

**Delegation approach (option b from §13.2):** Replace function bodies to
delegate to `Start-EvalServer` / `Stop-EvalServer`, preserving the CI env
var wrapper:

```powershell
function Start-DevRunnerManaged {
    # ... same params ...
    $savedCI = $env:CI; $env:CI = "true"
    $savedTimeout = $env:JUSTSEARCH_DEV_RUNNER_BACKEND_PORT_TIMEOUT_MS
    # ... save/set CI env vars ...
    try {
        $handle = Start-EvalServer -Backend devRunner -ApiPort $ApiPort ...
        Wait-EvalServerReady -Handle $handle -Level http -TimeoutSec $ReadyTimeoutSec
        return $handle
    } finally {
        $env:CI = $savedCI  # restore
        # ... restore other env vars ...
    }
}
```

**Callers:** 4 CI scripts (`run-claim-a-report-win.ps1`, 3 test scripts).
Return type changes from `System.Diagnostics.Process` to eval-session
`$handle` — callers need updating.

**Risk:** Medium. The CI env var dance and the caller return-type change
both need careful testing. CI scripts are hard to test locally.

**Status: Fixed (deleted).** Investigation found zero external callers — all
four functions (`Wait-ApiReady`, `Stop-DevRunnerBestEffort`,
`Start-DevRunnerManaged`, `Restart-DevRunnerManaged`) were dead code. Deleted
~130 lines. EvalSession.psm1 is the sole lifecycle API going forward.

#### 13.6.3 §13.3: Frontend Type Generation Pipeline (Deferred)

**Investigation findings:**

The frontend has **3 separate, manually-maintained** copies of `SystemStatus`
across `status.ts`, `systemTypes.ts`, and `hooks/useStatus.ts`. Types are
consumed via `request<T>()` which is a pure cast (`as T`) — no runtime
validation in production (Zod schemas are dev-only, fail-open).

**Existing infrastructure that could serve as source:**
- victools JSON Schema generator (already in `app-api/build.gradle.kts`)
- 3 committed `.schema.json` baselines: `status-response`, `knowledge-status`,
  `debug-state`
- `StatusRecordSchemaTest` regenerates schemas on `-PupdateSchemas=true`

**Three pipeline options:**

| # | Pipeline | Effort | Pros | Cons |
|---|----------|--------|------|------|
| 1 | `javalin-openapi` → spec → `openapi-typescript` | High | Full API spec, standard tooling | Javalin openapi plugin is fragile; adds dep |
| 2 | `typescript-generator` Gradle plugin → TS | Medium | Direct, no intermediate format | JVM-only, limited ecosystem |
| 3 | victools JSON Schema → `json-schema-to-typescript` | Low-Medium | Reuses existing schemas; npm-native | Only covers types with schemas; no API spec |

**Option 3 is most pragmatic:** the JSON schemas already exist, the npm tool
(`json-schema-to-typescript`) is mature, and it integrates into the existing
`ui-web` Vite build. A Gradle task would run victools to regenerate schemas,
then a `package.json` script would convert to `.d.ts`.

**Why this remains Deferred:** This is a toolchain/infrastructure decision,
not a code fix. It requires choosing a pipeline, wiring it into CI, and
migrating 6+ manual type files. The manual types work today, and the
schema drift tests catch Java-side renames. The gap is frontend → Java
drift (adding a field to Java but not TypeScript), which is real but
low-frequency.

**Confidence: 45%** (up from 30%). Option 3 is clearly the best approach,
but the implementation is a medium-sized project with CI integration,
manual type deletion, and Zod schema alignment. Not a 246 scope item.

#### 13.6.4 M6: Search Sub-Record Expansion (Deferred)

**Investigation findings:**

`ResolvedConfig.Search` has 3 fields: `profile`, `pipeline`, `collection`.
`RuntimeSearchConfigFactory` exposes ~19 search-related fields covering
paging, hybrid, rerank, facets, corrections, and chunk-aware settings.

**Zero production consumers:** Grep for `.search().` found only test code.
No production code reads `resolvedConfig.search().profile()` or any other
field. The 3 existing fields are populated by `ResolvedConfigBuilder` but
never consumed outside tests.

**Recommendation: Defer.** Adding fields with zero consumers violates
"don't design for hypothetical future requirements." The expansion should
be driven by a consumer migration that actually needs these fields (e.g.,
migrating `SearchOrchestrator` from `RuntimeConfig.SearchConfig` to
`ResolvedConfig.Search`). That is a separate tempdoc scope.

**Confidence: N/A** — not recommended for implementation. Correct action
is to defer until a consumer migration drives the need.

#### 13.6.5 M7: Static Singleton ConfigStore (Deferred)

**Investigation findings:**

`ConfigStore` is a manually-managed static singleton:
```java
private static volatile ConfigStore GLOBAL;
public static void setGlobal(ConfigStore store) { GLOBAL = store; }
public static ConfigStore global() { return GLOBAL; } // throws if null
public static ConfigStore globalOrNull() { return GLOBAL; }
```

**Scope:** ~30 production files across 10 modules call `global()` or
`globalOrNull()`. Several call sites are in `private static` utility
methods (`AgentLoopService.resolveFromConfig()`,
`IndexMetadataParityGuard.allowMismatch()`,
`LuceneIndexRuntime.resolveFromConfigStoreOrBuild()`), making injection
propagation non-trivial.

**No DI framework:** The codebase uses manual constructor injection.
Removing the static would require threading `ConfigStore` through
constructor chains across 10 modules. Some classes already receive it
via constructor (`SettingsController`, `EffectiveConfigController`), but
the majority use `ConfigStore.global()` directly.

**Risk:** High. 30-file mechanical refactor with no functional change.
Each call site must be verified for null-safety (many use `globalOrNull()`
defensively during early initialization). Cross-module constructor chain
propagation is error-prone without a DI container.

**Recommendation: Defer indefinitely.** The static singleton is an
intentional design choice, not technical debt. It works correctly, is
thread-safe (`volatile`), and has clear initialization semantics. The
refactor would add complexity (constructor parameter threading) without
functional benefit. If DI is ever adopted (Spring, Guice), this becomes
a natural migration target — but not before.

**Confidence: N/A** — not recommended for implementation. The static
singleton is the correct design given the codebase's manual-DI approach.

---

## 14. Post-Review Parity Fixes (P0-F)

After the implementation review (§11), a critical analysis of ResolvedConfig
vs RuntimeConfig parity identified 5 additional correctness gaps. All fixed
on this branch as part of tempdoc 247 Phase 0 prerequisites:

| Fix | File | Description |
|-----|------|-------------|
| `resolveString()` trim | `ResolvedConfigBuilder.java` | `resolveInt`/`resolveLong`/`resolveDouble` all trimmed; `resolveString` did not. Fixed: `r.value().trim()` at typed-resolution layer |
| `normalizeSchemaMismatchPolicy()` | `ResolvedConfigBuilder.java` | Factory normalizes aliases (`"rebuild"` → `REBUILD_BACKUP_FIRST`) via enum; builder stored raw strings. Added switch-based normalizer matching factory behavior |
| `contributeUiSettings()` exclude_patterns | `HeadlessApp.java` | `setSysPropIfBlankWithSource` sets the sysprop, but `contributeUiSettings()` never contributed it to the ordinal chain. Added JSON serialization of exclude patterns |
| Exception unification | `SettingsController.java` | `rebuildConfigStore()` catch type unified to `Exception` matching HeadlessApp (reverses L8 narrowing) |
| ConfigParityTest expansion | `ConfigParityTest.java` | 10 missing YAML value assertions added, alias normalization test, default convergence test, EnvRegistry limitation documented |

**All 174 configuration tests pass.** Details in `docs/tempdocs/247-resolvedconfig-consumer-migration.md` §P0-F.

