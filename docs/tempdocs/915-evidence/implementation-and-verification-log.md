<!-- Sidecar of docs/tempdocs/915-lane-d-index-fingerprint-identity-and-reindex-bundle.md — moved here per the tempdoc size-cap split (930 §19.3 F4, 2026-09-05). Headings below are unchanged from the main file; this directory is exempt from check-tempdoc-size.mjs. -->

## §D Implementation log

| # | Change | Location |
|---|---|---|
| D.1 | New `IndexFingerprint`: tri-state `ModelFingerprint`, `FieldShape` / `Chunking` / `Hnsw` / `Analysis` / `Inputs`, `compute` → `Optional<String>`, `canonicalJson`, `indeterminateInputs()` / `indeterminateModelInputs()`, process-wide model + vector-dimension providers | `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/commit/IndexFingerprint.java` (new) |
| D.2 | `SsotCommitMetadataSource`: deleted the `intent_v1.schema_ver` read, `schema_ver`, `index_schema_fp`, `analyzer_fp`, and the dead `setVectorDimensionOverride`; added `indexFingerprint()`, `projectFields()`, `parseCatalog()`, `vectorFormat()`, `DEFAULT_VECTOR_SIMILARITY` | `SsotCommitMetadataSource.java:40,62-118,120-208` |
| D.3 | `ChunkSplitter.ALGORITHM_VERSION = "v1"` (additive; lane E bumps it if the splitting algorithm changes) | `ChunkSplitter.java:91-99` |
| D.4 | `ParityDiagnostics`: `PARITY_KEYS` → `{index_fingerprint, boosts_fp}`, `REBUILD_REQUIRING_KEYS` → `{index_fingerprint}`, hints rewritten; blank-side handling made asymmetric (§C.5a): blank *expected* skips, blank *stored* on a rebuild-requiring key is a mismatch carrying `LEGACY_INDEX_HINT` | `ParityDiagnostics.java` — `PARITY_KEYS`, `REBUILD_REQUIRING_KEYS`, `LEGACY_INDEX_HINT`, `diff()` |
| D.5 | `IndexMetadataParityGuard`: message and comments describe `index_fingerprint`; the `allow_mismatch` branch documents that nothing sets it any more | `IndexMetadataParityGuard.java:56-73` |
| D.6 | `HeadlessApp`: both `allow_mismatch=true` set-sites deleted | `HeadlessApp.java:351` (was `:352`), `:781` (was `:783`) |
| D.7 | `ResolvedConfigBuilder`: prod default → `BLUE_GREEN_MIGRATE` | `ResolvedConfigBuilder.java:1009-1026` |
| D.8 | `KnowledgeServer`: installs the model-fingerprint + effective-vector-dimension providers before the first commit; repeat-rebuild brake in the `SCHEMA_MISMATCH` catch; metadata supplier simplified (the dead instance override removed) | `KnowledgeServer.java:507-520`, `:638-670`, `:1566-1580` |
| D.9 | `SpladeFingerprint`: `CachedResult` widened with the resolved model path; new `modelPath()` so "not configured" and "digest unreadable" are distinguishable | `SpladeFingerprint.java:46-60,77,90,95,99` |
| D.10 | `IndexGenerationManager`: `State` gains the three brake fields (all **8** `new State(` construction sites — `startMigration`, `setMigrationPaused`, `updateMigrationState`, `promoteBuildingGenerationToActive`, `rollbackToPreviousGeneration`, `newIdleState`, `normalizeAndUpgradeStateIfNeeded`, `recordAutoRebuildAttempt`); `MAX_AUTO_REBUILD_ATTEMPTS`, `recordAutoRebuildAttempt`, `autoRebuildAttemptsFor`; `promoteBuildingGenerationToActive` clears the brake | `IndexGenerationManager.java` — `State` record, `recordAutoRebuildAttempt` / `autoRebuildAttemptsFor`, and the widened constructors |
| D.11 | `CommitOps`: `commitWithBuildState` takes a required `CommitReason` (912 item 1); the low-level `commit()` is package-private so the cross-module bypass is a compile error (912 item 2) | `CommitOps.java:83`, `:143-158` |
| D.12 | `CommitReason`: `MIGRATION_CUTOVER`, `SWITCH_BUFFER_REPLAY` | `CommitReason.java:39-40` |
| D.13 | `KnowledgeServerMigrationOps`: cutover commit attributed `MIGRATION_CUTOVER`; switch-buffer replay routed through the funnel as `SWITCH_BUFFER_REPLAY`; green verification compares `index_fingerprint` and refuses on an uncomputable expected value | `KnowledgeServerMigrationOps.java:229`, `:325-345`, `:793` |
| D.14 | `IndexStatusOps`: both fingerprint helpers read `IndexFingerprint.COMMIT_META_KEY`; the `UNAVAILABLE` branch documents that an absent answer is not a clean bill | `IndexStatusOps.java:1101`, `:1124`, `:1138-1139` |
| D.15 | `RequiredFieldsCommitMetadataValidator`: `schema_ver`/`analyzer_fp` off the required list; `index_fingerprint` validated as well-formed **when present** (optional by the tri-state rule) | `RequiredFieldsCommitMetadataValidator.java:10-45` |
| D.16 | `commit-metadata.schema.json`: `schema_ver` / `index_schema_fp` / `analyzer_fp` removed; `index_fingerprint` added with a description | `SSOT/schemas/indexing/commit-metadata.schema.json` |
| D.17 | `CommitMetadataSpanAttrs.KEYS` and `NdjsonSpanExporter.ALLOWED_ATTRS`: 8 `commit.*` keys → 7 | `CommitMetadataSpanAttrs.java:19-26`, `NdjsonSpanExporter.java:70-76` |
| D.18 | `CommitFunnelArchTest`: the routed 912 §D.2 open item is closed by construction. To be exact, since the first cut of this row overstated it — **the `ALLOWED` allowlist is unchanged** (still `CommitOps`, `RuntimeSession`, `ComponentsFactory`). What closed the cross-module bypass is that `CommitOps.commit()` is package-private now, so `KnowledgeServerMigrationOps` cannot call it at all; only the class Javadoc changed | `CommitFunnelArchTest.java` (Javadoc only) |
| D.19 | jseval: `index_identity.py`, `manifest.py`, `preflight.py`, `release.py`, `projections/_spike_schema.py` + 5 test files | `scripts/jseval/**` |
| D.20 | Docs: `11-index-schema-migration.md` (fingerprint section, enforcement status 2026-09, policy defaults + brake), `04-storage-engine.md`, `06-configuration-ssot.md`, `08-observability.md`, `09-testing-strategy.md`, `18-adapters-lucene-deep-dive.md`, `index-schema-mismatch-reindex-noop.md` (superseded banner + inline strikes), `environment-variables.md`, ADR-0014 (dated append, history not rewritten) | `docs/**` |
| D.21 | RISK-011 instrumented to `tempdoc:915#C Design (Phase 1), tightened`; notes explain why it stays open rather than closed | `docs/reference/architectural-risks.md:264-282` |
| D.22 | New tests: `IndexFingerprintTest`, `CatalogPhysicalProjectionTest`, `SchemaMismatchPolicyBranchTest`, `IndexRebuildBrakeTest`; extended `CommitReasonAccountingTest`, `InvariantSuiteIT`; updated 14 fixture files | see §G |
| D.31 | **O7.** `IndexMetadataParityGuard`: `inspectCommittedParity(Path, Supplier<Map<String,Object>>)` and `schemaMismatch()` extracted; `checkOnOpen()` now calls both, so the pre-open and open-time paths share one implementation and one message. (The `Supplier` is G30's fix: an eager `Map` parameter built the expected metadata before the index-exists check.) | `IndexMetadataParityGuard.java:72`, `:119` |
| D.32 | **O7.** `KnowledgeServer.start()`: pre-open detection before the open-mode choice, and dispatch | `KnowledgeServer.java:608` (`preOpenMismatch`), `:611` (`inspectCommittedParity`), `:613` (`requiresRebuild`), `:653` (`policyHandledInCatch`), `:656` (raise), `:658` (`useDeferredWriter … && !preOpenMismatch`), `:751` (the existing handler) |
| D.33 | **O7.** `installEffectiveVectorDimension` hoisted out of `buildIndexRuntime` to the early install site, so the boot-time comparison sees the same dimension as every later one under BGE-M3 | `KnowledgeServer.java` — `effectiveVectorDimensionSupplier()`, installed beside `installModelFingerprintProviders` |
| D.34 | **O7.** `logBackgroundInitFailure(Exception)` + `isSchemaMismatch(Throwable)`: a schema mismatch escaping the deferred writer upgrade is reported as stopped ingestion, not as a non-fatal background failure | `KnowledgeServer.java:1008`, `:1026`, called at `:1546` |
| D.35 | **O7 tests.** `WorkerBootFixture` (shared boot scaffolding: production catalog, seed with matching / foreign / absent fingerprint, config publication, layout) and `PreOpenSchemaMismatchBootTest` (a-e plus the classifier) | both new, `modules/indexer-worker/src/test/.../server/` |
| D.24 | **Delta-review round (B3).** `KnowledgeServer.start()` no longer returns on brake exhaustion: it sets `rebuildBrakeExhausted`, opens Blue read-only, and falls through the rest of the sequence. Five previously skipped sites and what each does now — `createGrpcServer` + `grpcServer.start()`: run, so the port is real; `signalBus.writePort(boundPort)`: runs, so the Head discovers the Worker; `infraCtx`/`appServices` construction: runs, which is what builds `GrpcIngestService` and with it `IndexStatusOps`, the only producer of the new reason code; `appServices.startIndexingLoop()`: deliberately NOT started (guarded flag) with an ERROR naming the recovery path, because its only job is to write into a read-only runtime; `startSentinelThread()`: runs. `drainSwitchBufferBestEffort()` is also skipped — a read-only runtime is not a `RunningRuntime`, so it could only have logged a WARN and dropped the ops | `KnowledgeServer.java` — the `rebuildBrakeExhausted` field, the brake branch, the drain guard, the loop guard |
| D.25 | **The second defect, found by the test written for the first.** `IndexStatusOps.buildCore` dereferenced `indexingLoop` unconditionally. With no loop started that NPEs, and `GrpcIngestService.indexStatus` catches `RuntimeException` and returns a stub response with `core.state=ERROR` and NO compatibility sub-message — so `BLOCKED_REBUILD_BRAKE` was still unreachable, now silently. Null-guarded. This was ALSO a pre-existing latent hole: `DefaultWorkerAppServices.startIndexingLoop` already guards for a null loop (deferred-writer mode), so a status RPC arriving before the writer upgrade could blank the whole payload | `IndexStatusOps.java` — `buildCore`'s `setLastCommitTimestamp` |
| D.26 | **S10.** `BrakeExhaustedWorkerServesReadOnlyTest` — boots a real `KnowledgeServer` over a seeded generation layout with an exhausted brake, then asserts over gRPC: `isRunning()` + `getPort() > 0`; `schemaCompatState=BLOCKED_REBUILD_BRAKE` and `reindexRequiredReason=rebuild_brake_exhausted` and `reindexRequired`; a `Search` RPC answered from Blue; and `startMigration` + `promoteBuildingGenerationToActive` clearing the brake | `BrakeExhaustedWorkerServesReadOnlyTest.java` (new) |
| D.27 | **S12.** `SchemaMismatchPolicyBranchTest` gains a `withoutFingerprint()` source and `seedLegacyIndex`, and runs the key-absent fixture through all three policy values | `SchemaMismatchPolicyBranchTest.java` |
| D.28 | **S13.** `ChunkSplitter` (in `modules:indexing`, already an `api` dep of adapters-lucene) owns `CHUNK_THRESHOLD_CHARS` and `CONTENT_PREVIEW_MAX_CHARS`. `SsotCommitMetadataSource` reads them; its mirror and `ChunkWriterFingerprintMirrorTest` are deleted (with them the phantom `ChunkDocumentWriterFingerprintInputsTest` name); `ChunkDocumentWriter` re-exports them the way it already re-exported `CHUNK_TARGET_TOKENS`; two further private `4096` copies in `IndexingDocumentOps` and `GrpcIngestService` now point at the one owner | `ChunkSplitter.java`, `SsotCommitMetadataSource.java`, `ChunkDocumentWriter.java`, `IndexingDocumentOps.java`, `GrpcIngestService.java` |
| D.29 | **Nits.** `majorMinor()` truncates the analysis versions to `major.minor`; `EngineVectorIndexBench` reports the effective HNSW accessors instead of its own 16/200; the wire test parses `tags.reason` into a map instead of substring-matching a line; `resetUncomputableWarnedForTest()` makes the once-per-boot latch testable and `InvariantSuiteIT` asserts one WARN across three opens; `LEGACY_INDEX_HINT` no longer claims the index predates the key (it cannot know that); `ParityDiagnostics.diff` takes `docCount` and both consumers call `isIndexWithoutRecordedFingerprint` | see §F round 3 |
| D.30 | **New tests for the nits.** `SchemaCompatFreshInstallTest` (a fresh empty index is COMPATIBLE, an index holding documents of unrecorded shape is BLOCKED_LEGACY); `ParityGuardTest.anEmptyIndexWithoutAFingerprintIsNotAMigrationCandidate`; `FingerprintInputSourcesTest.aPatchLevelLibraryBumpDoesNotMoveTheFingerprint` and `theChunkFingerprintInputsComeFromTheSplitterNotFromACopy`; `InvariantSuiteIT.aFreshEmptyIndexWithNoFingerprintIsNotMigrated` and `theUncomputableFingerprintWarningIsEmittedOncePerBoot` | see §F round 3 |
| D.36 | **B4.** `inspectCommittedParity` no longer raises `CORRUPT_INDEX`. One `catch (IOException)` — which covers `CorruptIndexException`, `IndexFormatTooOld/TooNew` and `IndexNotFoundException` — WARNs with the cause class and returns an empty diff list, so the open path keeps its corruption recovery, its format upgrade and its second-line guard. `isCorruption` and three imports swept with it | `IndexMetadataParityGuard.java:89-111` |
| D.37 | **B5.** `IndexGenerationManager.abandonBuildingGeneration(reason)`: clears `building_generation`, returns `migration_state` to `IDLE`, marks the directory for deletion (after the pointer write, so a crash leaves an orphan the GC reaps rather than a pointer into a deleting directory), and carries the auto-rebuild budget over unchanged | `IndexGenerationManager.java:273` |
| D.38 | **B5.** The schema-mismatch handler abandons a mismatched Green before it retries, so `startMigration` allocates a fresh one instead of no-opping and handing back the generation that just threw. `start()` returns with Blue read-only whether the budget is fresh or spent | `KnowledgeServer.java:763-773` (the `inProgress` abandon, `:772`), `:777` (`blue`) |
| D.39 | **B5 ride-along.** Blue is held in a local (`blueReadOnly`, `KnowledgeServer.java:627`) and reused by both catch branches, instead of a second read-only runtime being opened over the first and the first leaked | `KnowledgeServer.java:627`, `:777`, `:799`, `:815` |
| D.40 | **S15.** `normalizeSchemaMismatchPolicy`'s `default` branch falls back to the mode default with a WARN instead of returning an unrecognised value verbatim — the shape `normalizeIntegrityCheck` already used | `ResolvedConfigBuilder.java:1009-1039` |
| D.41 | **Nit.** `ParityDiagnostics.holdsNothingToMigrate(docCount)`: the empty-index exclusion now applies to the changed branch as well as the blank one, and `IndexStatusOps` reports through the same predicate | `ParityDiagnostics.java`, `IndexStatusOps.java` (`safeSchemaCompatState`) |
| D.42 | **Round-4 tests.** `ResumedMigrationMismatchBootTest` (B5 at a FRESH budget — the boot the brake test cannot reach); `PreOpenSchemaMismatchBootTest` +4 (`PRE-OPEN` WARN asserted under FAIL_CLOSED (S14); `REBUILD_BACKUP_FIRST` backs Blue up before emptying it, asserted on the backup's doc count (S15); an unrecognised policy boots; a corrupt index still self-heals (B4)); `ParityGuardTest` +3 (unreadable commit, empty-index-with-stale-fingerprint, expected-metadata build count on an existing index); `SchemaCompatFreshInstallTest` +1; `ResolvedConfigBuilderTest` +1. `BrakeExhaustedWorkerServesReadOnlyTest` rewritten onto `WorkerBootFixture` (S16) and drives the `startMigration` RPC rather than the generation manager (S17) | see §F round 4 |
| D.43 | **B4 consequence.** `RecoveryIntegrationTest`'s "Gap D" assertion inverted: the parity guard is still invoked on the open path and must no longer raise `CORRUPT_INDEX`. The test's outcome assertions (backup, fresh index, writes accepted) are untouched and are what proves the open path still recovers. Also `SchemaMismatchStatusContractTest` seeds one document — its fixture committed an EMPTY index with a bogus fingerprint, which the empty-index rule now (correctly) reports COMPATIBLE | `RecoveryIntegrationTest.java`, `SchemaMismatchStatusContractTest.java` |
| D.44 | **D1/D3.** `GrpcIngestService`: `storedVectorFormat`, `openTimeCommitUserData` and `latestCommitUserDataBestEffort` are read from `searchLifecycle`, not `ingestLifecycle`. `queryVectorFormatActual` and `configuredVectorFormat` stay on ingest — one is a query-path fact, the other is config | `GrpcIngestService.java` (the `IndexStatusOps` construction) |
| D.45 | **D2.** `docCount` falls back to the SERVING reader before `jobQueue.completedCount()`. The building-generation branch is untouched, so a migration still reports Green's progress | `IndexStatusOps.java` (`buildStatusResponse`) |
| D.46 | **FAIL_CLOSED visibility.** `WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH`; written from `KnowledgeServer`'s outer `catch` via `isSchemaMismatch(e)`; read by `KnowledgeServerBootstrap.workerDownCode` into `LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH` (`STICKY`, same class as its corruption sibling) with a remedy detail; worded in `readinessNotice.ts` and added to the impairing set | `WorkerFatalReasonMarker.java`, `KnowledgeServer.java`, `KnowledgeServerBootstrap.java`, `LifecycleReasonCode.java`, `readinessNotice.ts` |
| D.47 | **Cutover restart + D4.** `preserveEvidenceBeforeRestart(context, promoted)` writes the clean-shutdown marker for the promoted generation and flushes the metrics snapshot, immediately after the promotion and before `initiateShutdownAction`. New `flushTelemetryAction` component on `CutoverContext`, wired to `KnowledgeServer.flushTelemetryBestEffort` | `KnowledgeServerMigrationOps.java`, `KnowledgeServer.java` |
| D.48 | **Nit.** The unreadable-commit WARN is latched on `path\|exceptionClass`, so one unreadable index produces one line across the pre-open check and the open-time guard — and a second, different unreadable generation still says so | `IndexMetadataParityGuard.java` |
| D.49 | **Round-5 tests.** `MidMigrationCompatSurfaceTest` (a real `GrpcIngestService` over two different runtimes — a wiring test, because handing `IndexStatusOps` the values under test is the mistake itself); `CutoverRestartEvidenceTest`; `BrakeExhaustedWorkerServesReadOnlyTest` +count/fingerprint assertions and a second case that clears `auto_rebuild_*` from `state.json` by hand and asserts the next boot migrates as a MISMATCH rather than as a legacy index; `PreOpenSchemaMismatchBootTest` asserts the fatal-reason marker under FAIL_CLOSED and exactly ONE unreadable-commit WARN | see §F round 6 |
| D.50 | **O14.** `CleanShutdownMarker.consumeWasClean` split into `wasClean` (non-destructive) + `consume`; `ComponentsFactory`'s dirty-open escalation only reads, and the consume happens at `new IndexWriter`. New `CleanShutdownMarkerLifecycleTest`: five consecutive read-only boots leave the marker in place, a writable open clears it, a clean close re-writes it, and a writer that never closes leaves it absent | `CleanShutdownMarker.java`, `ComponentsFactory.java` |
| D.51 | **R1, the latch.** `KnowledgeServerBootstrap`: `latchedIndexFatalVerdict` remembers whichever fatal index verdict `workerDownCode` reads out of the one-shot marker, and re-offers it once the marker is gone; `isIndexFatal()` widens the `supervisionVerdictHeld()` carve-out from `WORKER_INDEX_CORRUPT` to both codes; `indexFatalCode()` / `indexFatalDetail()` expose it. The latch is cleared by a capability listener on READY — registered in the constructor rather than at the two sites that write READY today, so a future READY path inherits it | `KnowledgeServerBootstrap.java` — the field, the ctor listener, `workerDownCode`, `latchIndexFatal`, `isIndexFatal`, `transitionWorkerDown` |
| D.52 | **R1, the ladder decision (made, not inherited).** Corruption did **not** short-circuit the respawn ladder before this — `BootRecoveryDecision.decide()` had vetoes for `clientBound` / `gaveUp` / `restartExhaustedHeld` / `supervisionActive` and nothing else — so the coordinator's "the same way corruption does, **if** corruption does" resolved to a decision rather than a copy. Decision: **yes, for both axes.** New `Veto.INDEX_FATAL` + `Input.indexFatalHeld`, ranked below supervision's terminal verdict and above the attempt budget. It reads the bootstrap LATCH, not `pendingReason()`: the marker read can land inside a suppressed arc, and gating on the wire state would make the veto depend on whether anyone had spoken | `BootRecoveryDecision.java` |
| D.53 | **R1, the narration + the hatch.** `KnowledgeServerHealthMonitor`: `currentRecoveryInput(operatorRequested)`; `gaveUpVeto` records WHICH veto latched the give-up; `narrateGiveUp`'s `INDEX_FATAL` arm stamps the latched cause when the capability does not already hold it (the case where the whole boot arc was suppressed and nothing else will ever say it). An operator request withholds the veto **and** re-opens that one give-up — the budget and the supervision vetoes are untouched, because the documented remedy for both fatal index causes is a settings or filesystem change the next spawn reads | `KnowledgeServerHealthMonitor.java` |
| D.54 | **R1, the user-facing string.** `HeadlessApp.startErrorFor(bootstrap, e)` prefers the latched remedy over `summarizeStartError(e)`, so `knowledgeServerStartError` — rendered verbatim in the 503 body — names the refusal instead of "Worker process crashed (exit code 1) before writing port to signal file". The `:540-542` narration comment now enumerates both fatal index codes and the latch | `HeadlessApp.java` |
| D.55 | **R1 tests.** New `SchemaMismatchFatalArcTest` (7 cases over a real bootstrap + a real monitor: the suppressed-attempt arc, the ladder short-circuit, STICKY through recovering/exhausted, the supervision-guard carve-out, the give-up narrating a swallowed cause, the READY clear, and a no-marker control) and `HeadlessAppStartErrorTest` (3). `BootRecoveryDecisionTest` +4 ranking cases; `StatusLifecycleWorkerReasonTest` +1 wire-projection case driving the full live sequence | see §F round 8 |
| D.56 | **R2 — a wrong-gate inside R1's own fix.** The `INDEX_FATAL` give-up skipped its capability write when the reason slot already held the cause. That compared the REASON and ignored the HEALTH, so an operator-requested attempt that re-refused left the capability parked at the `RECOVERING` the arm had set before the spawn — `readinessNotice.ts` renders that as "recovering" for a condition that never recovers on its own, and live R2 watched it sit there for 120 s. The write is unconditional now; it cannot double-narrate, because `WorkerCapability.transition` fires listeners only when the health OR the effective reason changes and the sticky reason is retained | `KnowledgeServerHealthMonitor.java` — `narrateGiveUp`'s `INDEX_FATAL` arm |
| D.57 | **R2 — the second half.** A failed attempt only `return`ed, so nothing re-asked the decision until the next tick. `settleAfterFailedAttempt()` re-runs the same pure function on the same executor thread and narrates through the same funnel. Scoped to `Veto.INDEX_FATAL`: it is the only veto whose cause is known-terminal the instant the attempt fails, and pulling the budget-exhaustion give-up forward would change a timing `KnowledgeServerBootRecoveryTest.arcGivesUpOnceAfterTheBudget` pins — which is exactly how that scoping error was caught (the unscoped first cut turned that test red) | `KnowledgeServerHealthMonitor.java` |
| D.23 | **Review round.** `ParityDiagnostics`: `LEGACY_INDEX_HINT` + the asymmetric blank-side rule (§C.5a). `IndexMetadataParityGuard`: `warnIfFingerprintUncomputable`, once per process. `ResolvedConfig.Index`: `DEFAULT_VECTOR_HNSW_M` / `DEFAULT_VECTOR_HNSW_EF_CONSTRUCTION` + `effectiveVectorHnsw*()`; `ComponentsFactory` reads them instead of its own 16/200. `IndexFingerprint`: `Analysis`, `threshold_chars`, `preview.max_chars`, `ner_model_sha256`, three-arg provider install. New `NerFingerprint` (worker-core). `SpladeFingerprint`: a missing model file is `NOT_CONFIGURED`. `ChunkDocumentWriter.CONTENT_PREVIEW_MAX_CHARS` made public; both constants mirrored into `SsotCommitMetadataSource` with a drift test. `KnowledgeServer`: `recordAutoRebuildAttemptOrSkip` (no budget for an unattributable boot), `expectedIndexFingerprintOrNull` (guarded), and exhaustion opens Blue read-only. `LifecycleReasonCode.INDEX_REBUILD_BRAKE_EXHAUSTED`; `StatusLifecycleHandler.compatBlockedReason` maps `BLOCKED_REBUILD_BRAKE`; `IndexStatusOps` produces it. `readinessNotice.ts`: corrected comment + new `index.rebuild_brake_exhausted` row, added to `REINDEX_CAUSE_CODES`. `WorkerSpawner` comment; `SchemaMismatchStatusContractTest` states its escape use; `environment-variables.md` documents the per-mode default | see §F round 2 |

---

## §E Post-implementation critical pass

**E1. Wrong-gate check — every gate the change depends on, set-site grepped.**

- `allow_mismatch`: grepped for every set-site of `justsearch.index.parity.allow_mismatch`.
  **Correction (review round):** the first cut of this bullet said there was one remaining writer.
  There were two — `OpenTimeCommitUserDataTest:38-47` and `SchemaMismatchStatusContractTest:76`, the
  latter in a file this PR modified. Both are tests that set and restore it deliberately, and both
  now say in a comment that they are exercising the operator escape on purpose. No production code
  sets it. `WorkerSpawner.java` *forwards* `INDEX_PARITY_ALLOW_MISMATCH` when
  present — it does not set it, so with nothing setting it the Worker inherits nothing. Verified by
  grep, not by symbol existence.
- `schemaMismatchPolicy`: the blue/green branch compares `"blue_green_migrate".equalsIgnoreCase(...)`
  against a value `normalizeSchemaMismatchPolicy` returns as `"BLUE_GREEN_MIGRATE"`. Case-insensitive
  comparison, so the flip does reach the branch — and `SchemaMismatchPolicyBranchTest` fails if it
  ever stops doing so (F1 in §F breaks the gate and the test reds).
- The brake gates on `attempt > MAX_AUTO_REBUILD_ATTEMPTS` after an increment, so attempts 1-3 run
  and the 4th refuses. Pinned by `repeatRebuildsForTheSameTargetExhaustTheBudget`.

**E2. Audit conclusions independently re-read.** Two subagent claims were load-bearing and both were
re-read at source before use: (i) "`KnowledgeServer` triggers blue/green only on the embedding sha"
— re-read `KnowledgeServer.java:634-667` and found the second, pre-existing SCHEMA_MISMATCH trigger,
which **changed the design** (§B.1 claim 2c); (ii) "`check-live-witness` is not a consumer" — grepped
the file directly, confirmed zero matches.

**E3. Test precision — passes for the right reason.**
- `IndexRebuildBrakeTest.aTruncatedStateFileIsRecoveredFromTheBackupSnapshot` was **rewritten after
  it failed**: my first version asserted a corrupt `state.json` reads as absent. It does not —
  `loadStateBestEffort:757-774` falls back to `state.json.prev`. Asserting the weaker (and wrong)
  property would have documented a worse product than the one that exists. The test now pins the
  real behaviour, and a separate test covers the genuinely-unrecoverable case.
- `ParityGuardTest.parityGuardMarksReadOnlyOnMismatch` was **deleted, not converted**: it flipped
  `similarity_fp`, which is no longer a parity key at all, so the property it tested no longer
  exists. `parityGuardCatchesBoostsMismatch` already covers the surviving read-only branch. Deleting
  it is retirement, not weakening — the branch it exercised is still covered.
- `SchemaMismatchPolicyBranchTest` asserts document counts, not just exception types: "FAIL_CLOSED
  throws" would pass even if it had destroyed the index first.
- `ResolvedConfigBuilderTest` asserts the exact value `BLUE_GREEN_MIGRATE`, not merely "not
  FAIL_CLOSED", so an accidental flip to the destructive `REBUILD_BACKUP_FIRST` fails there.

**E4. Tri-state lookups.** Three places now distinguish unknown from healthy and from mismatched:
`IndexFingerprint.compute` (empty on INDETERMINATE), `ParityDiagnostics.diff`, `verifyGreenMetadata`
(refuses on an uncomputable expected value). §F F3, F4 falsify the first two.

**Corrected in the review round.** The first cut of this bullet claimed the `diff` skip was the
tri-state handling and treated symmetry as the improvement. It was the defect: skipping a blank
*stored* value made the guard inert on every index built before this key existed — the whole
installed base. The skip is now asymmetric (§C.5a), and "unknown" on the expected side is no longer
silent either: `IndexMetadataParityGuard.warnIfFingerprintUncomputable` logs once per process naming
the unresolved input, because a check that is not running must not look like a check that passed.
§F G1-G3 falsify the new rule.

**E5. Asymmetric lifecycle.** `installModelFingerprintProviders` has a matching
`resetModelFingerprintProviders` used by `IndexFingerprintTest`'s `@AfterEach`, so a test that
installs a throwing provider cannot leak it into the rest of the fork.

**E6. Actionable findings from this pass: 2** — the corrupt-state assertion (E3) and the
second blue/green trigger (E2/§B.1 2c). Both changed the implementation.

**E9. The O7 round's own finding, and who found it.** Extracting the guard's inspection into a
reusable static changed *when* the expected metadata is built: the original method checked the index
existed before calling the supplier, and the extracted version called it first. Nothing in the O7
work noticed — not the six new boot tests, not the critical read of the diff. What noticed was
`CommitMetadataIntegrationTest.metadataSourceSupplierInvokedPerBuild`, a three-year-old assertion
that the supplier is invoked exactly once per commit, in the full suite (§F G30). That is the
argument for running the whole suite rather than the affected modules: the tests that catch a
refactor's side effects are, by definition, not the tests you were thinking about.

**E8. What the SECOND pass missed, and why (delta-review round).** The review-round critical pass
walked the brake change and asked whether the reason code was wired on both sides. It was. What it
never asked was whether anything could reach the code that emits it — and the answer was no, twice
over: `start()` returned before `appServices` existed (§D.24), and even after that was fixed the
status builder NPE'd before reaching the compatibility sub-message (§D.25). Both were invisible to
every unit test, to `check-readiness-reason-codes`, and to a careful reading of the diff, because
each one is a fact about a *composition* rather than about any file in it.

The transferable form: **a reason code is not shipped when the constant, the mapping and the gate
exist — it is shipped when something can emit it end to end.** The only instrument that could tell
was a test that boots the real server and reads the wire, and it found the second defect within
seconds of the first run. `audit-without-test`, at composition scale.

**E7. What this pass missed, and why (review round).** The independent review found the inert-guard
defect (B1) that E4 had walked straight past. The pass asked "does this conflate unknown with
healthy?" and answered yes-handled — but only for the *expected* side, because that is the side the
tri-state design was about. The stored side was never interrogated, so a blanket skip written for
one narrow case (a key that legitimately had no stored value) silently generalised to the case that
mattered. The transferable lesson: when a guard is made symmetric, check each side against its own
adverse scenario — symmetry is an aesthetic property, not a correctness one, and the legacy index is
exactly the `green-masked-destructive` shape (a green my dev machine's freshly-built index happened
to satisfy). E7 also revises E6: **actionable findings, counting the review: 3.**

---

## §F Falsification record

Every new or modified guarantee was broken once, run, watched fail with the expected assertion, and
restored. Driver: `tmp/falsify.sh` + `tmp/falsify-patch.py` (deleted before commit).

| ID | Break | Test that caught it | Failing assertion |
|---|---|---|---|
| F1 | `REBUILD_REQUIRING_KEYS` → `Set.of()` | `ParityGuardTest`, `SchemaMismatchPolicyBranchTest`, `InvariantSuiteIT` | four tests red, not three — `parityGuardTriggersRebuildOnFingerprintMismatch` FAILED; `aLegacyIndexWithNoFingerprintIsMigratedRatherThanIgnored` FAILED; `blueGreenMigratePropagatesTheMismatchAndKeepsEveryDocument` FAILED; `failClosedRefusesAndKeepsEveryDocument` FAILED (cited by NAME, not line: later rounds inserted fixtures above both and the line numbers this row originally carried drifted) |
| F2 | drop `boosts_fp` from `PARITY_KEYS` | `ParityGuardTest` | `parityGuardCatchesBoostsMismatch` FAILED (by name, same reason as F1) |
| F3 | `diff()` blank-skip → only skip when both sides null | `InvariantSuiteIT` | `anIndeterminateExpectedFingerprintIsNotAMismatch` FAILED (`InvariantSuiteIT.java:99`) |
| F4 | `compute()` no longer returns empty on INDETERMINATE | `IndexFingerprintTest` | `anIndeterminateModelYieldsNoFingerprintAtAll` FAILED (`IndexFingerprintTest.java:261`) |
| F5 | `hnsw.ef_construction` hard-coded to 0 | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`IndexFingerprintTest.java:172`) |
| F6 | `commitWithBuildState` ignores its reason, passes `UNKNOWN` | `CommitReasonAccountingTest` | `theCutoverCommitIsAttributedToMigrationCutoverNotUnknown` FAILED (`CommitReasonAccountingTest.java:114`) |
| F7 | funnel skips `pendingDocs.set(0L)` | `CommitReasonAccountingTest` | `theSwitchBufferReplayCommitIsCountedAndNotifiesTheListener` FAILED (`:143`) |
| F8 | brake's `sameTarget` always false | `IndexRebuildBrakeTest` | `repeatRebuildsForTheSameTargetExhaustTheBudget` FAILED (`IndexRebuildBrakeTest.java:75`); `aSuccessfulCutoverClearsTheBrake` FAILED (`:111`) |
| F9 | cutover carries the brake forward instead of clearing it | `IndexRebuildBrakeTest` | `aSuccessfulCutoverClearsTheBrake` FAILED (`:118`) |
| F10 | prod default back to `FAIL_CLOSED` | `ResolvedConfigBuilderTest` | `null/blank defaults to BLUE_GREEN_MIGRATE in prod mode` FAILED (`ResolvedConfigBuilderTest.java:1495`) |
| F11 | `startMigration` repoints `active_generation` to Green | `IndexRebuildBrakeTest` | `migrationBuildsGreenBesideBlueAndOnlyPromotionSwitches` FAILED (`IndexRebuildBrakeTest.java:43`) |
| F12 | `rmwPolicy` put back into the physical projection | `CatalogPhysicalProjectionTest` | `theProjectionDropsRmwPolicyEntirely` FAILED (`:89`); `anRmwPolicyAnnotationDoesNotCostTheUserAReindex` FAILED (`:75`) |

### Round 7 — O14, the clean-shutdown marker's owner

### Round 8 — R1, the refusal that never reached the user

Driver `tmp/915-r1-falsify.mjs` (deleted before commit), same contract. It caught itself once: the
first run reported six DRIVER ERRORs, because `execFileSync` cannot launch a `.bat` without
`shell: true`. That is postmortem #29's zero-XML invariant doing exactly its job — the six cases
would otherwise have read as "every break survived".

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G53 | drop the latch fallback in `workerDownCode` — the marker is one-shot again | `SchemaMismatchFatalArcTest` | three cases red. `the attempt that consumed the marker was suppressed; without the latch the ONE narrating call reports worker.spawn.failed … ==> expected: <worker.index_schema_mismatch>`; and `STICKY means the ladder's narration changes the HEALTH and leaves the cause alone ==> expected: <worker.index_schema_mismatch> but was: <worker.spawn_recovery_exhausted>` — the terminal string the validator saw, verbatim |
| G54 | narrow the supervision-guard carve-out back to `WORKER_INDEX_CORRUPT` | `SchemaMismatchFatalArcTest` | `the guard's carve-out covered worker.index_corrupt only, so the refusal was logged as 'not overwriting supervision's verdict' and thrown away` |
| G55 | remove the `INDEX_FATAL` veto — the ladder spends its budget again | `SchemaMismatchFatalArcTest`, `BootRecoveryDecisionTest` | `expected: <GIVE_UP> but was: <STAND_DOWN>`; `the worker wrote its refusal to disk, so every attempt re-reads the same bytes … expected: <GIVE_UP>`; and the arc case `expected: <worker.index_schema_mismatch> but was: <worker.recovering>` — i.e. an attempt was spent |
| G56 | stop the give-up narrating a cause the boot arc swallowed | `SchemaMismatchFatalArcTest` | `expected: <worker.index_schema_mismatch> but was: <worker.not_connected>` — the shape where the whole boot arc was suppressed and nothing else will ever say it |
| G57 | never clear the latch on READY | `SchemaMismatchFatalArcTest` | `READY is where the latch is dropped; this assertion fails if only the capability's ReasonRetention clears and the bootstrap keeps its copy ==> expected: <null>`. The anti-staleness direction is pinned too: without it an OOM death an hour later is reported as the old schema mismatch |
| G58 | `knowledgeServerStartError` falls back to the spawn symptom | `HeadlessAppStartErrorTest` | `the user must be told which setting produced the refusal; got: Worker process crashed (exit code 1) before writing port to signal file` — the live string, character for character |
| G59a | **R2.** restore the reason-only guard on the give-up stamp | `SchemaMismatchFatalArcTest` | `expected: <DEGRADED> but was: <RECOVERING>` — the live symptom exactly: the reason slot was right the whole time and the HEALTH was the lie |
| G59b | **R2.** stop settling after a failed attempt — wait for the next tick | `SchemaMismatchFatalArcTest` | same assertion. Both halves are load-bearing: the guard made the write a no-op, and without the settle nothing calls the funnel until a tick lands |

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G50 | make the read-only open consume the marker again | `CleanShutdownMarkerLifecycleTest` | `boot 1: a read-only open writes nothing, so it cannot make the index unclean — and must not report that it did ==> expected: <true> but was: <false>` |
| G51 | stop consuming at the writer open — a crash would look clean | `CleanShutdownMarkerLifecycleTest` | `the writer consumed it ==> expected: <false> but was: <true>`; `a writer exists now, so this session CAN die mid-commit … ==> expected: <false> but was: <true>`. Both directions pinned: the fix must not turn a real crash into a clean boot |
| G52 | re-fuse the read and the invalidation inside `wasClean()` | `CleanShutdownMarkerLifecycleTest` | same first assertion — the separation is the fix, not the call site |

### Round 6 — the live-validation defects

Driver `tmp/falsify5.mjs` (deleted before commit), same contract as round 5.

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G41 | D1: read the stored commit metadata off the INGEST runtime again | `MidMigrationCompatSurfaceTest`, `BrakeExhaustedWorkerServesReadOnlyTest` | `the stored shape reported is the one the user's searches reach ==> expected: <bbbb…> but was: <a4a0b1e6…>` (Green's own fingerprint, which is the live defect verbatim); `the stored fingerprint is Blue's, not the empty string … ==> expected: <64> but was: <0>`; `the braked worker reports the shape Blue actually carries ==> expected: <ffff…> but was: <>` |
| G42 | D2: fall back to `jobQueue.completedCount()` again when nothing is being written | `BrakeExhaustedWorkerServesReadOnlyTest` | `Blue holds documents, so the braked worker must not report zero ==> expected: <true> but was: <false>` |
| G43 | D2 the other way: report the SERVING count even while a Green is being built | `MidMigrationCompatSurfaceTest` | `documents indexed still counts GREEN while one is being built … ==> expected: <1> but was: <2>`. Both directions are pinned, so the fix cannot drift into hiding a rebuild's progress |
| G44 | stop writing the fatal-reason marker for a refused schema mismatch | `PreOpenSchemaMismatchBootTest` | `the dying worker must say WHY it refused, in the channel the Head reads ==> expected: <index_schema_mismatch> but was: <null>` |
| G45 | stop writing the clean-shutdown marker before the cutover restart | `CutoverRestartEvidenceTest` | `the promoted generation has just been committed and verified — the next boot must not pay a FULL integrity scan for it ==> expected: <true> but was: <false>` (both cases) |
| G46 | stop flushing telemetry before the cutover restart | `CutoverRestartEvidenceTest` | `and the cutover's own counters are written before they are lost ==> expected: <true> but was: <false>` |
| G47 | log the unreadable-commit WARN unlatched again | `PreOpenSchemaMismatchBootTest` | `… and say it ONCE: the pre-open check and the open-time guard ask the same question of the same bytes …` |
| G48 | anti-fixture: make the brake never latch | `BrakeExhaustedWorkerServesReadOnlyTest` | `precondition: the boot actually took the exhausted-brake path ==> expected: <true> but was: <false>` and `precondition: the brake is spent` — i.e. every count and fingerprint assertion in that file is guarded by a precondition that the state under test was actually reached |
| G49 | drop the Head's marker→reason-code mapping | `KnowledgeServerWorkerDownCodeTest` | **First run: NO FAILURE OBSERVED — nothing observed the Head half.** The Worker's write and the vocabulary were pinned; the mapping that turns the marker into `worker.index_schema_mismatch` was not, which is the `substrate-without-consumer` shape. A case was added, and the same break now reds it: `expected: <worker.index_schema_mismatch> but was: <worker.spawn.failed>` — the exact string the live validator saw |

### Round 5 — B4, B5 and the round-4 review items

Driver `tmp/falsify4.mjs` (deleted before commit), rebuilt on postmortem #29's remedy: byte
copy-aside backups, a per-case anchor assertion, per-case result wipes, and a final byte-compare
proving the restore. It grew a second guard this round, from its own failure — see below.

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G31 | B4: restore the `CORRUPT_INDEX` throw in `inspectCommittedParity` | `PreOpenSchemaMismatchBootTest` | `aCorruptIndexStillSelfHealsAtBoot` FAILED — `java.io.IOException: Failed to start KnowledgeServer`, which is the blocker verbatim: a corrupt index that recovers today takes the Worker down instead |
| G32 | B5: stop abandoning a mismatched Green before retrying | `ResumedMigrationMismatchBootTest` | `aResumedMigrationWithAMismatchedGreenStartsOverInsteadOfKillingTheWorker` FAILED — `java.io.IOException: Failed to start KnowledgeServer`. At a FRESH budget, i.e. boot #1 of 3 |
| G33 | B5 ride-along: open a second read-only runtime on Blue instead of reusing the first | `ResumedMigrationMismatchBootTest` | `Blue is opened once, and reused: the handler must not open a second runtime over it ==> expected: <1> but was: <2>` |
| G34 | S14: stop emitting the `PRE-OPEN` marker, routing untouched | `PreOpenSchemaMismatchBootTest` | `the pre-open routing must be what refused; got: [Starting KnowledgeServer…, …]` — the assertion the reviewer asked for, and it distinguishes pre-open routing from the second line, which the old `isSchemaMismatch`+`IDLE` pair did not |
| G35 | S15: return an unrecognised policy value verbatim again | `ResolvedConfigBuilderTest`, `PreOpenSchemaMismatchBootTest` | `expected: <REBUILD_BACKUP_FIRST> but was: <blue-green-migrat>`; and `anUnrecognisedPolicyFallsBackInsteadOfKillingTheWorker` FAILED with `Failed to start KnowledgeServer` — the boot failure the pass-through now causes, not merely inertness |
| G36a | empty-index nit, guard half: drop the exclusion from the CHANGED branch | `ParityGuardTest` | `an index with no documents has no content whose shape could be wrong ==> expected: <true> but was: <false>` |
| G36b | empty-index nit, status half: report `BLOCKED_MISMATCH` regardless | `SchemaCompatFreshInstallTest` | `a stale shape recorded against no documents describes nothing … ==> expected: <COMPATIBLE> but was: <BLOCKED_MISMATCH>` |
| G37 | S15: stop forcing a writable open for `REBUILD_BACKUP_FIRST` | `PreOpenSchemaMismatchBootTest` | `the mismatched index must be backed up, never deleted: this policy empties the active generation and the backup is the user's only copy ==> expected: not <null>` |
| G38 | S17: make the `startMigration` RPC reject | `BrakeExhaustedWorkerServesReadOnlyTest` | `the operator rebuild is reachable from here: BREAK G38 ==> expected: <true> but was: <false>` — which is the point of S17: arm (d) now fails when the WIRE fails, where the fixture call could not have noticed |
| G39 | S16 interrogation: seed through `FieldCatalogDef.forTesting(768)`, the catalog the inline copy used | — | **NO FAILURE OBSERVED, and that is the honest answer.** The stamped fingerprint comes from `SsotCommitMetadataSource` either way, so the forked catalog changed nothing observable here. S16 is drift prevention, not a live defect — recorded as such rather than claimed as a fix |

| G40 | (not injected — a real consequence of B4, caught by the full suite) `RecoveryIntegrationTest.corruptIndexAutoRecoveryProducesBackupAndFreshIndex` asserted the parity guard was what re-classified the read failure as `CORRUPT_INDEX` ("Gap D wiring proof") | `RecoveryIntegrationTest` | `parity guard should have surfaced an IndexRuntimeIOException(CORRUPT_INDEX) … Observed exceptions: [] ==> expected: <true> but was: <false>`. Every OUTCOME assertion in the same test still passed — backup taken, fresh index served, new writes accepted — so what B4 removed is a redundant classification, not the recovery. The mechanism assertion is inverted rather than deleted: the guard must **not** raise, because the identical throw from the pre-open site is the blocker |

**Two harness defects, both caught by the driver's own guards rather than by luck.**

1. Every case reported `NO FAILURE OBSERVED` on the first run. The cause was not the code: `execSync`
   runs through `cmd.exe`, where `./gradlew.bat` does not resolve, so nothing ran — and the driver
   had just wiped the results directory, so "no failures found" and "no tests found" were the same
   observation. This is postmortem #29's *other* half, and the fix is a rule the driver now enforces:
   **a case with zero result XMLs is a DRIVER ERROR, never a survived break.** With that assertion in
   place the second run reported nine driver errors instead of nine false negatives, which is what
   sent us to look at the invocation (`.\gradlew.bat` inside a JS template literal is a third bug —
   `\g` is not an escape, so the string became `.gradlew.bat`; an absolute path settled it).
2. `SchemaCompatFreshInstallTest.anEmptyIndexWithAStaleFingerprintIsCompatible` survived G36b when it
   was first written. It passed for the wrong reason: `buildStatus()` passed `null` for the
   open-time commit user-data supplier, so `safeSchemaFingerprintStored()` returned `""` and the case
   routed through the BLANK branch, never reaching the comparison it exists to pin. The test now
   supplies the stale snapshot explicitly, and G36b reds.

### Round 4 — the O7 fix

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G24 | pre-open detection removed (`preOpenMismatch = false`) — i.e. back to deciding inside the open | `PreOpenSchemaMismatchBootTest` | `an index whose shape changed must start migrating at boot …` FAILED; `aLegacyIndexWithSegmentsMigratesAtBoot` FAILED; `aChangedShapeOnAnIndexWithSegmentsIsRefusedUnderFailClosed` FAILED with `Expected java.io.IOException to be thrown, but nothing was thrown` |
| G25 | FAIL_CLOSED dropped from `policyHandledInCatch` | — | **NO FAILURE OBSERVED, and the diagnosis is the result.** With the pre-open raise gone the open is still forced writable, so the open-time guard raises and the same handler refuses: the property survives on the second line. Recorded rather than discarded — it is the defence-in-depth §C.12 claims, demonstrated |
| G25b | both lines broken (`policyHandledInCatch` narrowed AND `useDeferredWriter` no longer forced off) | `PreOpenSchemaMismatchBootTest` | `aChangedShapeOnAnIndexWithSegmentsIsRefusedUnderFailClosed` FAILED — `Expected java.io.IOException to be thrown, but nothing was thrown` |
| G26 | detection over-triggers (`preOpenMismatch = true`) | `PreOpenSchemaMismatchBootTest` | `a matching index must not be migrated — a detector that fires on everything is not a detector ==> expected: <IDLE> but was: <MIGRATING>`; `aFreshEmptyIndexDoesNotMigrateAtBoot` FAILED |
| G27 | the deferred-upgrade mismatch filed as non-fatal again | `PreOpenSchemaMismatchBootTest` | `a stopped ingestion pipeline is not a degraded capability; got: [Background model initialization failed (non-fatal), …]` |
| G28 | `isSchemaMismatch` stops at the top-level exception | `PreOpenSchemaMismatchBootTest` | `schemaMismatchIsRecognisedThroughTheCauseChain` FAILED (`expected: <true> but was: <false>`); two more, including `FAIL_CLOSED must refuse for the schema-mismatch reason, not some incidental failure` |
| G29 | the empty-index exclusion dropped from the shared predicate | `PreOpenSchemaMismatchBootTest` | `a first launch must not spend a rebuild on an index with nothing in it (the shared ParityDiagnostics predicate …)` FAILED — the point being that breaking it in `ParityDiagnostics` breaks the NEW site too, which is what "one predicate" is supposed to mean |

| G30 | (not injected — this one was a real defect, caught by an existing test) the extracted `inspectCommittedParity` took a built `Map` instead of a `Supplier`, so `checkOnOpen` built the expected metadata BEFORE checking whether the index exists — an extra catalog hash and model-digest read on every open of a fresh index | `CommitMetadataIntegrationTest` | `metadataSourceSupplierInvokedPerBuild` FAILED — `expected: <2> but was: <3>`, i.e. the supplier ran once per commit plus once for a parity check with nothing to compare. Fixed by making the parameter a lazy `Supplier` |

**G25 is the useful one.** A break that produces no failure is a claim about the harness or about
the code, and this time it was about the code: the property held because a second, independent
mechanism enforced it. That is worth knowing and worth writing down — but it is not a passing
falsification, so G25b breaks both lines and gets the real answer. The alternative, quietly recording
G25 as "caught", is how a redundant guard becomes an unexamined assumption.

### Round 3 — one break per delta-review decision

| ID | Break | Test that caught it | Observed failure |
|---|---|---|---|
| G15 | the early `return;` restored inside `start()` | `BrakeExhaustedWorkerServesReadOnlyTest` | `the Worker must not treat an exhausted brake as a fatal start ==> expected: <true> but was: <false>` |
| G16 | `indexingLoop` null-guard removed from `buildCore` | `BrakeExhaustedWorkerServesReadOnlyTest` | `the compat state is the wire carrier for the new reason code ==> expected: <BLOCKED_REBUILD_BRAKE> but was: <>` |
| G17 | the `docCount` term dropped, so an empty index migrates | `ParityGuardTest`, `SchemaCompatFreshInstallTest`, `InvariantSuiteIT` | `an empty index has no content that could have been written under the wrong shape ==> expected: <false> but was: <true>`; `aFreshEmptyIndexIsCompatibleRatherThanBlockedLegacy` FAILED; `aFreshEmptyIndexWithNoFingerprintIsNotMigrated` FAILED with `IndexRuntimeIOException: Index was built with a different effective index shape…` |
| G18 | the status path stops calling the shared predicate | `SchemaCompatFreshInstallTest` | `aFreshEmptyIndexIsCompatibleRatherThanBlockedLegacy` FAILED — "reporting BLOCKED_LEGACY here would demand a rebuild on first launch" |
| G19 | analysis versions hashed at patch level | `FingerprintInputSourcesTest` | `aPatchLevelLibraryBumpDoesNotMoveTheFingerprint` FAILED — `expected: <10.3> but was: <10.3.1>` |
| G20 | the chunk-constant mirror re-introduced | `FingerprintInputSourcesTest` | `the mirror is gone: read the splitter's constant, do not re-copy it ==> expected: not equal but was: <CHUNK_THRESHOLD_CHARS>` |
| G21 | the blank-stored skip restored, so legacy indexes never migrate | `SchemaMismatchPolicyBranchTest` | all three legacy-fixture branches FAILED: `Expected IndexRuntimeIOException to be thrown, but nothing was thrown` (BLUE_GREEN_MIGRATE, FAIL_CLOSED) and `expected: <0> but was: <1>` (REBUILD_BACKUP_FIRST) |
| G22 | the once-per-boot WARN latch removed | `InvariantSuiteIT` | `three opens, one warning ==> expected: <1> but was: <3>` |
| G23 | the schema-mismatch `try` put back inside the `else` branch only (i.e. not covering a resumed migration) | `BrakeExhaustedWorkerServesReadOnlyTest` | `java.io.IOException: Failed to start KnowledgeServer` … `Caused by: IndexRuntimeIOException: Index was built with a different effective index shape than this runtime produces (index_fingerprint mismatch)` at `KnowledgeServer.start(:577)` — observed directly, before the hoist |

**G15 failed as NO FAILURE OBSERVED twice, for two different reasons, and both were mine.**

The harness half of that is recorded as `falsify-restore-from-backup` in `agent-postmortems.md` #29 — deliberately there and not in `agent-lessons.md`, whose always-loaded ratchet had zero headroom (`check-always-loaded-budget` failed the first attempt by exactly the bytes the new bullet added).

The first time, the harness's `replace` silently matched nothing, so the break was never applied and a
green run got recorded as a weak test. The second time the break *was* applied and the test still
passed — because the test never reached the branch at all. Its config used
`justsearch.index.schema_mismatch.policy`; the real key is un-prefixed
(`ResolvedConfigBuilder:1545`), so the policy silently fell back to the dev default
`REBUILD_BACKUP_FIRST`, the mismatch was "recovered" destructively instead of propagating, and every
remaining assertion passed on evidence that had nothing to do with the brake: the port is bound on
any boot, search answers on any boot, and `BLOCKED_REBUILD_BRAKE` is read out of `state.json`, which
the test wrote itself.

The fix is `rebuildBrakeExhaustedForTest()` and a precondition assertion. That is the general shape:
**when every observable consequence of a branch is also true without it, the test needs a witness
that the branch ran** — otherwise it is measuring the fixture. This is the third time in this tempdoc
that a falsification run caught a test passing for the wrong reason (F12, G12, G15), and the only one
where two independent causes stacked.

### Round 2 — one break per review decision

| ID | Break | Test that caught it | Failing assertion |
|---|---|---|---|
| G1 | blank-stored skip restored for all keys (the original defect) | `InvariantSuiteIT` | `aLegacyIndexWithNoFingerprintIsMigratedRatherThanIgnored` FAILED (`:103`); `aLegacyIndexDiffNamesItselfAsLegacyRatherThanAsAShapeChange` FAILED (`:118`) |
| G2 | legacy diff carries the generic hint instead of naming itself | `InvariantSuiteIT` | `aLegacyIndexDiffNamesItselfAsLegacyRatherThanAsAShapeChange` FAILED (`InvariantSuiteIT.java:119`) |
| G3 | benign keys migrated too (a legacy `boosts_fp` would rebuild) | `InvariantSuiteIT` | `aBlankBenignKeyOnALegacyIndexIsNotAMismatch` FAILED (`:139`) |
| G4 | the HNSW fallback diverges from the codec's | `FingerprintInputSourcesTest` | `anExplicitlyWrittenHnswDefaultIsIndistinguishableFromLeavingItUnset` FAILED (`:36`) |
| G5 | `chunking.threshold_chars` dropped from the hash | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`:86`) |
| G6 | `preview.max_chars` dropped from the hash | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`:86`) |
| G7 | the mirrored `CHUNK_THRESHOLD_CHARS` drifts from `ChunkDocumentWriter` | `ChunkWriterFingerprintMirrorTest` | `theFingerprintMirrorsTheChunkWriterConstants` FAILED (`:22`) |
| G8 | `ner_model_sha256` no longer contributes | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`:86`) |
| G9 | `analysis.lucene_version` / `icu_version` pinned to a constant | `IndexFingerprintTest` | `everyPhysicalInputMovesTheFingerprint` FAILED (`:86`) |
| G10 | brake exhaustion forgotten across a restart | `IndexRebuildBrakeTest` | `exhaustionIsRememberedAcrossARestart` FAILED (`:135`); `repeatRebuildsForTheSameTargetExhaustTheBudget` FAILED (`:82`); `aSuccessfulCutoverClearsTheBrake` FAILED |
| G11 | a missing SPLADE model file becomes INDETERMINATE again | `SpladeFingerprintTriStateTest` | `aDirectoryWithNoModelFileIsNotConfiguredRatherThanIndeterminate` FAILED (`:51`) |
| G12 | green verification promotes on an uncomputable expected fingerprint | `GreenCutoverEmbeddingFpVerifyTest` | `expected fingerprint uncomputable -> green REJECTED rather than promoted blind` FAILED (`:84`) |
| G13 | both migration reasons collapse to `unknown` on the wire | `IndexRuntimeWireFormatRegressionTest` | `theMigrationCommitReasonsReachTheWireAsTheirOwnSeries` FAILED (`:291`) |
| G14 | the indeterminate sentinel removed, so unattributable boots share one budget | `KnowledgeServerBrakeSentinelTest` | `anUncomputableTargetNeverConsumesBudget` FAILED (`:30`) |

**G12 caught a test-precision defect in my own new test.** On its first run G12 reported *NO FAILURE
OBSERVED*: with the refusal branch removed the code fell through to the mismatch branch and returned
`false` anyway, so the test passed — for a reason that was not true. The test now captures the logger
and asserts the refusal *reason*, not merely the verdict. A test that cannot be made to fail is not
evidence; a test that fails for the wrong reason is worse, because it looks like evidence.

**F12 is the reason this section exists.** On the first falsification run F12 reported
*NO FAILURE OBSERVED*: the headline property of the whole phase — that an annotation-only catalog
edit no longer costs a reindex — was pinned by nothing. `CatalogPhysicalProjectionTest` was written
in response, and `projectFields` / `parseCatalog` were extracted from `indexFingerprint()` to make
it directly testable. A test that cannot be made to fail is not evidence; a property with no test
is not a guarantee.

---

## §G Verification results

Gradle home: `C:\Users\Elias\AppData\Local\Temp\jsgh-R1` (isolated from the other lanes).
Re-run in full after O14; every line below is from that run. The integration-test counts in the previous version of this table were wrong — they were read from a results directory holding an earlier run's XML, which is the same stale-artefact mistake the round-3 falsification harness made (§F). Counts here are from a `cleanIntegrationTest` run.

| Command | Result |
|---|---|
| `spotlessApply -PskipWebBuild=true` | exit 0 |
| `build -x test -PskipWebBuild=true` | BUILD SUCCESSFUL |
| `cleanTest test -PskipWebBuild=true --no-build-cache --continue` | BUILD SUCCESSFUL in 4m 9s — **1460 suites, 8912 tests, 0 failures, 0 errors, 26 skipped**. The run before it was RED, usefully: `RecoveryIntegrationTest.cleanCloseWritesMarkerAndOpenConsumesIt` encoded the superseded contract (ANY open consumes), which is the defect O14 names. Renamed and re-pointed at the writer, with the read-only half asserted the other way (counted from `TEST-*.xml`, not from the console). The run before this one was RED, and usefully so: `RecoveryIntegrationTest` caught a real consequence of B4 that no targeted run would have (§F G40) — the same value the O7 round got from G30 |
| `cleanIntegrationTest :modules:indexing:integrationTest --no-build-cache` | BUILD SUCCESSFUL — `InvariantSuiteIT` **9 tests**, 0 failures. Forced: an unforced attempt reports `UP-TO-DATE`, which is a replay, not a run |
| `:modules:indexer-worker:test --tests "*PreOpenSchemaMismatchBootTest*"` | BUILD SUCCESSFUL — **10** boot-level cases now (a-e, the classifier, and the four added this round: the FAIL_CLOSED `PRE-OPEN` WARN, `REBUILD_BACKUP_FIRST` backup-before-empty, an unrecognised policy, a corrupt index self-healing). Boot-level because every one of them passes at unit level against the defect it pins |
| `:modules:indexer-worker:test --tests "*ResumedMigrationMismatchBootTest*"` | BUILD SUCCESSFUL — B5 at a FRESH budget, the boot the brake test structurally cannot reach |
| `:modules:ui:integrationTest` | BUILD SUCCESSFUL — **9 tests across 4 suites**, 1 skipped, 0 failures (includes `SchemaMismatchStatusContractTest`). The earlier "16 across 5" was a stale-directory miscount, as the reviewer said |
| `:modules:worker-core:test` (named explicitly — the brake tests live there) | BUILD SUCCESSFUL — included in the full-suite totals above; run standalone as well |
| `:modules:indexer-worker:test --tests "*BrakeExhaustedWorkerServesReadOnlyTest*"` | BUILD SUCCESSFUL — the emit-chain test boots a real `KnowledgeServer`, so this is the first tier in this tempdoc above "compiles and unit-tests" |
| Full kernel: `governance/run.mjs --produce-inputs --mode gate` | 35 gates evaluated, 34 pass, 1 fail (`test-efficacy` skipped) — `ts-any`, **inherited**. (Two gates went red during this round and were FIXED, not baselined: `prose-tier-register` wanted a register row for the new `falsify-restore-from-backup` rule anchor — row 47 added; `config-surface` correctly reported `vectorHnswM` / `vectorHnswEfConstruction` as accessors no production code calls, because the bench nit moved its last two callers to the effective accessors — the effective accessors now call them.) All `ts-any` findings are `silent-growth` in files this branch does not touch (`citationResolve.test.ts`, `MarkdownBlock.ts`, `indexingProgress.ts`, `sv3-sessions.test.ts`, `searchResultViewModel.ts`); pinned as `ts-any-gate-counts-english-prose` (the gate scores the English word "any" in comments). `readinessNotice.ts`, the one ui-web file this branch edits, is not among them. |
| `check-readiness-reason-codes` | OK — **56 emittable codes, 50 worded rows** (was 55/49: `worker.index_schema_mismatch` is wired on both sides, and the producer direction confirms an emit site exists) |
| `check-live-witness` · `check-store-recoverability` · `check-search-degradation-reason-codes` · `check-language-agnostic-analysis` · `check-tempdoc-numbers` · `check-premerge-table` | all OK |
| `docs/verify-canonical-doc-links.mjs` · `llmstxt-generate --check` · `skills-sync --check` · `verify-runtime-config-matrix` | OK (156 files) · OK (115 docs) · OK (5 skills) · OK (yaml=111, pairs=250, rows=306) |
| `docs-validate.mjs` | exit 1, **inherited** — repo-wide `heading-case` advisories, pinned as `docs-validate-heading-case-repo-wide`; no finding names a heading this branch touched |
| `run-ui-web-gates.mjs` (the `ui-web-gates` recipe) | **40/40 passed**, re-run this round: `readinessNotice.ts` gained the `worker.index_schema_mismatch` row, so the trigger fired and the recipe was run rather than carried forward |
| `cd modules/ui-web && npm run typecheck` | exit 0 |
| `cd modules/ui-web && npm run test:unit:run` | 468 files, **6267 tests passed** |
| `:modules:worker-services:test --tests "*MidMigrationCompatSurfaceTest*"` | BUILD SUCCESSFUL — the D1 wiring test, driving a real `GrpcIngestService` over two different runtimes |
| `:modules:indexer-worker:test --tests "*CutoverRestartEvidenceTest*"` | BUILD SUCCESSFUL — 2 cases, including the independence of the two facts when the flush throws |
| Diff hygiene | NUL bytes in the diff: 0. Every added non-ASCII line is an intended em-dash / `§` / `→`; no `Ã` / `â€` / `Â` mojibake. No whole-file CRLF rewrite — `--numstat` shows large adds only for genuinely new files. |

**Not run, and why.** No live-stack verification: the brief forbids starting the dev stack, the eval backend, or any JVM on the shared ports, and the orchestrator owns those resources. The blue/green loop is therefore verified at the state-machine and policy-branch level only — recorded as open item O3, not as a passed tier.
