---
title: "Config-surface classification pass — 70 inert ResolvedConfig components"
status: "active — classification COMPLETE (70/70 with file:line evidence); deletion of the safe subset in progress. Owner scope: classification pass only, no gate; GJF out of scope."
created: 2026-07-15
author: agent session 1b3050fb (Opus 4.8) — orchestration/judgment; classification delegated to sonnet
category: config / dead-code / docs-truth
related:
  - the `dead-code-identification-sweep` tempdoc (638) — the rubric precedent (`audit-without-test`: "a 'this is dead' claim is a hypothesis until zero callers are proven") and the source of the deliberate scope-out of member-level dead-code detection
  - the `observations-backlog-triage` tempdoc (721) — the disposition-ledger structure this doc borrows
  - ADR-0043 / the `language-agnostic-analysis` tempdoc (581) — killed the consumer of `Index.defaultLanguage` and `Policy.languagePolicy`
---

# 728 — Config-surface classification pass

## Charter

`ResolvedConfig` holds all resolved application config as a tree of Java records.
**70 of its 342 components are inert** — the accessor is never called. This pass
classifies every one with primary-source evidence and dispositions the safe subset.

## Why this exists now

`ResolvedConfigBuilder` resolves keys **eagerly and independently of consumers**.
`resolveInt("justsearch.rag.top_k", 5)` (`ResolvedConfigBuilder.java:1523`) succeeds
whether or not anything reads `ragTopK()`. There is no structural coupling between
resolving a key and consuming it, so when a consumer is refactored away the whole
apparatus survives — env var, doc row, resolution, default — everything except the
effect. Nothing fails. That is the generator; this pass drains the pond, it does not
turn off the tap (no gate — owner scope decision).

## Method

Detection: accessor-call census over 2,440 Java files. Soundness premise, verified
before trusting it — `ResolvedConfig` is **not** wholesale-serialized (the snapshot
mapper writes a `LinkedHashMap<String,String>`, `ResolvedConfig.java:109-149`;
`EffectiveConfigController` reads `resolutions()`, not accessors), there are no
`::accessor` method references, and no reflection over its record components
(`getRecordComponents()` appears only in `app-api` wire-contract tests). So
"no accessor call" really does mean "no reader".

Classification: 6 parallel sonnet subagents by config group, each required to return
`file:line` evidence per verdict; verdicts judged here, not accepted (`audit-without-test`).

### Detection defects this pass found in its own method

Recorded because they bound the confidence of the headline numbers:

- **Accessor-name collision → over-attribution.** The census matched bare `.name()`.
  `Summary.messageKey` was scored "test-only" on a hit that is really
  `SummaryRejection.messageKey()` (`SummaryRejectionTest.java:14`) — a different class
  in a different module. `Ui.settingsMode` likewise: the hit is
  `SettingsV2.settingsMode()` (`SettingsV2ContractTest.java:85`). Both are **zero-reader**.
  The 55/15 zero-vs-test-only split therefore had noise; the per-item verdicts below
  are the reliable figures.
- **A test named for the wrong knob.** `ragTopKClamped()`
  (`ResolvedConfigBuilderTest.java:969`), `@DisplayName("RAG top_k is clamped to [1, 50]")`,
  actually puts `rag.retrieve.top_k` and asserts `retrieveTopK()` (lines 972, 976).
  **`ragTopK` has no test at all.** An earlier session claim that this test "certifies
  the inert `ragTopK` path" was wrong — it certifies a *different* inert knob.

## The taxonomy — and why "UNWIRED" had to split

The pass started with OBSOLETE / UNWIRED / KEEP. Reality needed five, because the
**disposition differs by mechanism**:

| Verdict | Meaning | Does the env var work? | Disposition |
|---|---|---|---|
| **OBSOLETE** | the subsystem it configured existed and is gone/superseded | no | delete component + doc row |
| **SPECULATIVE** | added for a subsystem **never built** — no shadow, no predecessor | no | delete component + doc row |
| **UNWIRED-shadowed** | live consumer hardcodes a constant duplicating the knob | **no — false promise to users** | **log as bug; do NOT delete** (deleting erases the intent record) |
| **UNWIRED-bypassed** | a live consumer re-reads the **same key** directly via `System.getProperty`/`EnvRegistry`, skipping `ResolvedConfig` | **yes** | delete the redundant component; **doc row stays true** |
| **UNWIRED-duplicate** | a **sibling key** does the job and is read; this key is dead | no (this key) | log as bug; sibling's doc row stays |

`UNWIRED-bypassed` is the finding that most changes the earlier framing: those knobs
**are honest** — only the `ResolvedConfig` plumbing is dead. The prior session's
"≥18 documented-but-inert = false promise to users" headline is therefore an
**overcount**; the false-promise set is the *shadowed* + *duplicate* rows only.

## Dispositions

### OBSOLETE — subsystem gone/superseded (32)

Whole clusters died together with their subsystem:

- **Translator tree (4)** — `ResolvedConfig.translator()` itself has zero call sites, so
  the whole sub-record is unreachable; every child dies with it.
  `translator`, `Translator.pipelineIntent`, `Translator.pipelineEmbed`, `Translator.pipelineClassify`
  — permanent Builder defaults at `LocalIntentTranslatorConfig.java:612-614`; the consuming
  `BackendRegistry` has zero non-test callers. `docs/reference/issues/backend-tech-debt.md:77`
  confirms `app-inference` superseded this path.
- **Paging tree (5)** — `Search.paging()` never called. `PagingCursorManager`, the sole
  consumer, was deleted by the 638 sweep (`638-…md:46,107`, commit `1eb454044`). Live paging
  hardcodes mode `"legacy"` at `Cursor.java:26`; PIT mode survives only in test fixtures.
  `Search.paging`, `Paging.cursorLegacyEnabled`, `Paging.pitTtlMs`, `Paging.tiebreakField`, `Paging.strategy`
- **Watcher tree (6)** — the pluggable watcher-strategy subsystem retired with
  `modules/app-indexing` (no longer in `settings.gradle.kts`); `WorkerMethvinWatcher.java:26-33`
  states it "replaces the Head-side `MethvinWatcherStrategy`", and `DirectoryWatcher.builder()`
  (`:136-141`) takes no strategy/debounce/poll parameter.
  `Watcher.strategy`, `Watcher.debounceMs`, `Watcher.pollingIntervalMs`, `Watcher.queueMaxEntries`, `CollectionCfg.watcherStrategy`
- **ai-backend fossil (7)** — the live sampling contract `SamplingParams.java:30-36` has no
  penalty/guard/remote fields; `LlamaServerOps.java:219-275` passes no such flags. Matching
  defaults exist only inside `LocalIntentTranslatorConfig` / `BackendRegistry`, reachable
  only from tests.
  `Llm.repPenalty`, `Llm.repWindow`, `Llm.enableJsonGuard`, `Llm.allowRemote`, `Llm.remoteEndpoint`, `Llm.remoteAuthToken`, `Llm.templateTranslate`
- **Summary tree (5)** — `SummaryRejection`/`SummaryRejectedException` are never constructed
  or thrown in production; no summarization executor/queue exists in the shape-based engine.
  `Summary.maxCharacters`, `Summary.queueFullMessageKey`, `Summary.executionThreads`, `Summary.executionQueueCapacity`, `Summary.messageKey`
- **Ai (3)** — `Ai.llmBackend`: `InferenceConfig.java:409` hardcodes the cuda12 variant
  ("Prefer cuda12 explicitly"); the `cpu/metal/vulkan` enum is unreachable.
  `Ai.aiClassifyEnabled`: consumer `AiClientConfig` deleted (trail: tempdoc 300:71 → 325:82);
  the live gate is a *different* field, `Search.queryClassificationEnabled`.
  `Ai.llmMode`: no production branch; doc itself hedges "implementation-defined".
- **Language levers (2)** — killed by ADR-0043. `Index.defaultLanguage` (per-language field
  fallback, tempdoc 314:257-258); `Policy.languagePolicy` (consumer `DefaultAppFacade` +
  `LanguageFilterPolicy` deleted; successor `HeadAssembly` has zero "language" hits).

### SPECULATIVE — never built (5)

No shadowing constant, no superseded predecessor, no consumer ever existed:

- `Corrections.indexFallbackEnabled` — siblings (`enabled`, `zeroHitRetryEnabled`, `maxEditDistance`,
  `dfThreshold`) are live-read at `SearchExecutor.java:180-214`; no "index fallback" concept exists there.
- `Ui.requireTranslator` — sibling `automationEnabled` is live (`RuntimeContextConfigBridge.java:62`);
  no automation/translator gating logic exists anywhere.
- `Index.commitPolicy` — `CommitOps.java:291-339` is a single unconditional commit path; no policy branch.
- `Rag.includeSurroundingContext` — no neighbour-chunk-expansion logic anywhere in the RAG pipeline.
- `Ocr.triggerMinImagePixels` — `OcrRoutingConfig.from()` (`:69-83`) maps 6 sibling `Ocr` fields into
  the live routing config and **omits this one**; the real gate is page-count + max-pixel ceiling +
  text-quality score (`PolicyDrivenTikaExtractor.java:149-190`).

### UNWIRED-bypassed — knob works, component redundant (5)

Safe to delete the component; **the documented env var keeps working**:

- `Telemetry.metricsMaxMb` — `NdjsonMetricExporter.java:116-117` reads
  `justsearch.telemetry.metrics.max_mb` directly. **Default mismatch: 10 there vs 50 at
  `ResolvedConfigBuilder.java:1304`.**
- `Telemetry.metricsRetentionDays` — same, `:121-122`. **Mismatch: 7 vs 30 (`:1305`).**
- `Telemetry.exemplarsEnabled` — `LocalTelemetry.java:144-149` computes it from
  `System.getProperty` and passes *that* to the exporter (`:153`).
- `Index.tracingLevel` — `KnowledgeServer.java:352-358` / `DefaultWorkerAppServices.java:115-119`
  call `EnvRegistry.INDEX_TRACING_LEVEL.getString("none")` directly; the stated reason is
  "ConfigStore is not ready until step 3".
- `Ui.settingsMode` — `UiSettingsStore.java:123` `PersistenceMode.resolveMode()` reads
  `EnvRegistry.UI_SETTINGS_MODE.get()` directly.

> The default mismatches are a **latent trap**: anyone "fixing" the dead Telemetry components
> by wiring them would silently change rotation from 10MB→50MB and retention 7→30 days.

### UNWIRED-shadowed / -duplicate — knob does nothing (28) → logged as bugs, NOT deleted

These are **false promises**: a live consumer hardcodes what the knob should control.
Per owner scope, they are logged, not fixed, and **not deleted** — deleting would erase
the record of intent without delivering the feature.

Highest-value instances:

- `Rag.ragTopK` — `JUSTSEARCH_RAG_TOP_K` documented (`environment-variables.md:141`) as
  "Number of chunks to retrieve for RAG context (default 5)"; real value is
  `DEFAULT_TOP_K = 5` (`RAGContext.java:55`) via `extractTopK` (`:343-350`). Setting it does nothing.
- `Llm.llmGpuLayers` — **duplicate key**. `justsearch.llm.gpu_layers` (`:1203`) is dead;
  `justsearch.gpu.layers` → `Ai.gpuLayers` (`:963`) is read in ~20 places
  (`InferenceConfig.java:103`, `HeadlessApp.java:947`, `RuntimeActivationService.java:191`, …).
  **Both are documented** (`environment-variables.md:103` and `:81`) — one works, one is a lie.
- `Llm.summaryChunkTokens` / `summaryChunkOverlap` / `templateSummary` / `templateReduce` /
  `templateRoot` — all shadowed by `HierarchicalShapeRunner.java` (`SECTION_TARGET_TOKENS = 1800` :63,
  contiguous no-overlap split :377-395, `SECTION_SUMMARY_SYSTEM_PROMPT` :70-78,
  `SYNTHESIS_SYSTEM_PROMPT` :80-85) and `PromptTemplateLoader.java:40` (hardcoded SSOT/prompts root).
- `Worker.maxBatchSize` / `maxQueueDepth` — shadowed by `GrpcIngestService.java:99,102`
  (`MAX_BATCH_SIZE = 10_000`, `MAX_QUEUE_DEPTH = 100_000`), enforced at `:425-442`.
- `Worker.maxFileSize` / `maxContentLength` — shadowed by `StructuredContentExtractor.java:43`
  (and a duplicate copy at `ContentExtractor.java:38`) and `TikaExtractionPolicy.java:33`.
- `Rag.citationMatchThreshold` — resolves to `""` and is never parsed; live threshold is
  `DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD = 0.5` (`:31`), used by
  `StreamingCitationMatcher.java:51` and `AgentCitationResolver.java:32-33`.
- `Rag.retrieveTopK` — second historical attempt at the same knob as `ragTopK`; both bypassed.
- `Llm` VRAM/session cluster (`vramFraction`, `vramProjected`, `vramLimitBytes`, `vramAutoScale`,
  `maxSlots`, `maxParallel`, `maxSessions`, `sessionWarmupMs`, `queueCapacity`, `backendSelector`,
  `backendSupports`) — shadowed by `HardwareProfile.MINIMUM_VRAM_FOR_GGUF = 7_500_000_000L` (:24),
  `VramRequirements.COMFORTABLE_VRAM_BYTES = 11_500_000_000L` (:32), the single `ReentrantLock`
  in `OnlineModeOps.java:73`, and `LlamaServerOps.java:230-232` (`-np` hardcoded to `1`, VDU-only).
  All 11 documented at `environment-variables.md:105-113,129,135`.
- `Index.commitDebounceMs` — shadowed by `CommitOps.java:34` (`COMMIT_TIMER_INTERVAL_MS = 10_000L`).
- `Watcher.overflowRescanOnOverflow` — shadowed by hardcoded `force=true` at
  `WorkerMethvinWatcher.java:211-213`.
- `Health.refreshIntervalMs` / `Health.stalenessAlertSeconds` (under the dead `Translator` tree) —
  the live equivalents are `InfraHealth.pollIntervalMs` / `InfraHealth.translatorHandshakeStaleMs`,
  read at `BootstrapHelpers.java:67,69` via `InfraPhase.java:53`.

### UNSURE (1)

- `Llm.simulatedLatencyMs` — no shadowing constant, and no evidence a latency-simulation
  subsystem ever existed. Reads as SPECULATIVE but the squashed public history
  (single root commit `29579e51`) blocks a `git log -S` trail. **Left in place**, not deleted.

## Reach — and what NOT to generalize

The tempting generalization is "extend the `dead-code-jvm` gate to member level so this
cannot regrow". **Do not.** `WholeProgramDeadCodeTest.java:121-125` records that
whole-program dead-*method* detection was already measured at **~6.4k findings,
noise-dominated by reflectively-serialized accessors / builders / fluent APIs**, needs
GraalVM-metadata-level roots, and was deliberately scoped out by the 638 sweep. The
analysis is class-only by construction (`getDirectDependenciesToSelf()`, `:134`) — the
javadoc's "inspects members of all visibilities" is aspirational, not what the code does.

What *is* tractable, if a guard is ever wanted, is a **narrow** check over the
`ResolvedConfig` record only: bounded (342 components, one record tree), and provably
free of the reflective/serialization noise that killed the general case (premise verified
above). Out of scope here by owner decision — recorded so the option is not lost and the
6.4k trap is not re-entered.

## Owner decisions (parked)

- **GJF** — Java formatting has not run since the JDK-25 move (`enableGjf` unset ⇒ the
  whitespace-only fallback at `JvmBaseConventionsPlugin.kt:180` is the permanent path;
  proven: registered tasks are `spotlessJavaSources*`, no `spotlessJava`). Out of scope
  this pass; logged. Decision needed: accept + correct CLAUDE.md's "Build fails on
  PMD/Spotless violations" claim, or land the isolated 1.34.1 reformat scoped in tempdoc 236.
- **The 28 shadowed/duplicate knobs** — each is a documented feature that does nothing.
  Wiring them changes runtime behaviour; deleting them removes a promised feature.
  Both are product calls, hence logged rather than actioned.
