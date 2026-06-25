---
title: Adversarial Ingestion Resilience
type: tempdocs
status: active
---

# 410 - Adversarial Ingestion Resilience

## Status

**SUBSTANTIVELY COMPLETE (V1 + Hardening + Phases 0/1/2 + Code-review
fixes + Tempdoc 418 Phases A/B/B-H/C-A + Slices A–E + G.1–G.5 + Post-
merge validation pass) as of 2026-04-26.** Tempdoc 417's MetricCatalog
substrate (ADR-0027) merged into main alongside this work; ingestion
metrics now route through `IngestionOutcomeMetricCatalog`. Remaining
items (§1 SourceRoot, §3–§5 typed values, §9 archives-as-roots, §12
semantic poisoning, Phase C sub-commit B post-soak deletion, Windows-
reparse adversarial cases) are gated on product/CI decisions or
release-cycle soak — none block closure of this tempdoc's V1+hardening
goals. Invariant 1 ("no raw path crosses into indexing") holds
**end-to-end**, not just past the Worker boundary. The structural
invariant scoreboard stands at **14/15 closed, 1 partial** (see "Final
verification" below). Created 2026-04-24 as a design tempdoc after a
review of recent tempdocs 390-409 and the unresolved observation:

> No adversarial/security testing - no Lucene query injection, ZIP bomb,
> path traversal, or Tika fuzzing tests. Only `NastyCorpusTest`
> (robustness).

The design body below is the long-term north star and remains intact.
Implementation progress to date:

- **V1 slice** — typed outcomes + privacy-safe ledger + validated artifact
  contract + sandbox seam, landed as the "Revised First Implementation
  Slice" (line 471+).
- **First hardening pass** — closed 7 structural gaps from review.
- **Phase 0** — fixed 5 V1 defects (silent rollback swallow, retry-policy
  semantics, XXE policy honesty, ArchUnit defense, throughput baseline).
- **Phase 1** — landed 4 high-leverage mechanics (search-time honesty
  schema fields, gRPC ingestion-ledger read APIs + diagnostics endpoints,
  CI-safe adversarial corpus, env-var docs).
- **Phase 2** — cloud-placeholder DEFERRED routing (2.1); sandbox-config
  scope confirmed as env-only (2.2); full Worker-owned traversal (2.3)
  delegated to tempdoc 418 by user direction.
- **Code-review fixes** — 8 of 10 actionable items closed (defer-policy
  guard, ledger-entry sanitization, outcome-write counter,
  validateContentBoundsOnly rename, sandbox IOException causes,
  cloud-placeholder dedup, separated ledger retention, sandbox stderr
  logging on success path). Two cosmetic items deferred.
- **Tempdoc 418 Phase A** (commit `0d546e86e`) — Worker-side
  scaffolding: `ScanRoot` server-streaming RPC + `WatchRoot` /
  `UnwatchRoot` + `WorkerScanOps` + `RootWatcherRegistry` (placeholder).
  Additive only — no Head-side change.
- **Tempdoc 418 Phase B** (commits `9d46d2807` → `e5508b0dd`) —
  Head-side migration in 4 sub-slices. All four Head-side directory
  walkers (`KnowledgeSearchController`, agent `IngestTool`,
  `RootLifecycleOps.walkAndSubmit`, file watcher) now dispatch through
  the Worker. Methvin watcher dependency moved to `worker-services`;
  `WorkerMethvinWatcher` delivers events directly to `JobQueue` in the
  same process. Both watchers run during the soak window; SQLite
  `INSERT OR REPLACE` coalesces duplicates.
- **Tempdoc 418 Phase B Hardening (B-H.1–B-H.4)**
  (`c0b1e3ed0` → `e0d318622`) — closed 7 high + 4 medium defects from
  the post-Phase-B critical-analysis pass: shared `IngestionSkipPolicy`
  + `CloudPlaceholderRecorder` extraction, walk-time skip rules,
  hashed `current_directory`, cloud-placeholder ledger writes,
  Worker-owned backpressure + cancellation via
  `ServerCallStreamObserver.isCancelled()`, Worker-owned DELETE
  handling via `IndexingCoordinator.deleteByIdAndChunks`,
  `recordOutcomeSafely` broadened to `RuntimeException`, sandbox
  `LimitedBytes#length()`, controller `Math.toIntExact`.
- **Slice A.1+A.3** (`de0df4272`) — surfaced `parser_warnings_count`
  search-time honesty field with dual-copy catalog + repro fingerprint
  regen + new `regenSsotManifest` Gradle task.
- **Slice A.2** (`0b7753a4b`) — wired the latent
  `EXTRACTION_REASON_CODE` write for SUCCESS_PARTIAL paths (field
  was declared + registered but never populated).
- **Slice B** (`405e2959f`) — promoted `IngestionSkipPolicy` global
  rule sets out of hardcoded constants into three new operator-only
  `JUSTSEARCH_INGESTION_SKIP_*` env keys via an installable singleton.
- **Slice C.1+C.2** (`be8884d91`) — added symlink-loop (POSIX) +
  mid-extraction mutation adversarial cases to
  `AdversarialCorpusIngestionTest`.
- **Slice C.3** (`41f626906`) — re-enabled `NastyCorpusTest` in CI
  after restoring the missing `nasty-archive.zip` fixture (the
  actual root cause of the original "resource loading issues on
  Windows CI runners" disable).
- **Slice D** (`e9b67a4ae`) — Tempdoc 418 Phase C sub-commit A:
  deleted the back-compat shims (2-arg `WorkerMethvinWatcher`
  constructor, `IngestTool.defaultLocalScanCallback`). The
  load-bearing Head-side wiring deletion (sub-commit B) remains
  deferred until one production release cycle of soak.
- **Slice E** (`c84fd42ba`) — formalised the ingestion-ledger
  privacy contract in `docs/explanation/03-knowledge-server.md` and
  pinned the `IngestionEventView` field set via a new
  `ingestionEventViewExportContractIsPinned` test so future
  additions force explicit consent.
- **Post-merge validation pass** (commits `8bc40fdd1`, `27c5c47df`,
  2026-04-26) — full validation harness against the live `runHeadlessEval`
  backend (Tier A1/A2/A3/A4, B5/B6/B7, C9/C13). Surfaced one real defect
  (eval-mode env propagation) + three observation findings; one of those
  (the structurally-unreachable Tika cap post-check) was confirmed
  empirically and shipped as **Slice G.5** (commit `27c5c47df`).
  Throughput regression in run #1 (14.7 docs/s) was refuted by run #2
  (24.5 docs/s, *above* the 16.5 baseline). HTTP→gRPC cancel gap remains
  open as a known limitation (low real-world impact).

See **Implementation Progress** below for the concrete state mapping.

This document intentionally focuses on the correct long-term structure,
not the cheapest patch. Feasibility, migration cost, and short-term
implementation details are secondary. The design question is:

**What should JustSearch look like if hostile local files are treated as a
normal production input, not an exceptional test fixture?**

Production-Reality Verification is being handled elsewhere. This tempdoc
is adjacent but distinct: it is not about whether the packaged app runs.
It is about whether the app can safely ingest arbitrary user-controlled
local corpora.

## Implementation Progress

This section reflects the merged-to-`main` state as of 2026-04-26 (last
relevant commit `27c5c47df`, Slice G.5). The original worktree
`codex/410-ingestion-outcomes` is fully merged via `b295644c9` plus the
post-merge follow-ups (`39bc500c0`, `6da11b4c2`, `7ee0841e5`,
`8bc40fdd1`, `27c5c47df`, `143ac599e`). It is paired with the design
body, not a replacement: each "done" item lists the structural-invariant
or design section it satisfies, and each "not yet" item names the design
section whose work is still ahead.

### Done (V1 slice + hardening pass)

The hostile-input matrix at line 379+ now produces the desired V1
outcomes for every row that this slice was intended to cover. Each row
carries a typed `IngestionOutcomeClass`, a stable `IngestionReasonCodes`
constant, an `IngestionRetryPolicy`, and a privacy-safe ledger entry
recorded inside one SQLite transaction with the queue mutation.

| Hostile-input case                              | V1 outcome (now produced)                                  |
| ----------------------------------------------- | ---------------------------------------------------------- |
| Hidden/temp/system file                         | `SKIPPED_POLICY` / `SKIPPED_TEMP_OR_SYSTEM`                |
| Missing file at processing                      | `STALE_SOURCE` / `DELETED_OR_MISSING`                      |
| Unreadable file                                 | `IO_FAILED` / `UNREADABLE` (retryable)                     |
| Unmodified file                                 | `SKIPPED_POLICY` / `UNCHANGED`                             |
| Non-regular source (directory, special file)   | `SKIPPED_POLICY` / `NON_REGULAR_SOURCE`                    |
| Oversized input                                 | `BUDGET_EXCEEDED` / `INPUT_TOO_LARGE` (terminal)           |
| Oversized Office input                          | `BUDGET_EXCEEDED` / `OFFICE_INPUT_TOO_LARGE` (terminal)    |
| Generic Tika failure                            | `PARSER_FAILED` (terminal); silent placeholder removed     |
| Extraction timeout                              | `PARSER_TIMEOUT` (retryable)                               |
| IO error during extraction                      | `IO_FAILED` / `IO_ERROR` (retryable)                       |
| Runtime error during extraction                 | `PARSER_FAILED` (retryable)                                |
| Write/build/index error                         | `WRITE_FAILED` (retryable)                                 |
| Lucene runtime draining during write            | `WRITE_UNAVAILABLE_DRAINING` (defer, no attempt)           |
| File changed (size) after snapshot              | `STALE_SOURCE` / `SIZE_CHANGED_AFTER_SNAPSHOT` (defer)     |
| File changed (mtime) after snapshot             | `STALE_SOURCE` / `MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT`    |
| File kind changed after snapshot               | `STALE_SOURCE` / `SOURCE_KIND_CHANGED_AFTER_SNAPSHOT`      |
| File deleted after snapshot                     | `STALE_SOURCE` / `DELETED_AFTER_SNAPSHOT` (mark done)      |
| Sandbox crash / malformed / oversized / polluted| `SANDBOX_FAILED` (retryable)                               |
| XML entity payload                              | Existing Tika defaults hold (XXE leak test green)          |

Structural-invariant satisfaction:

- **Invariant 4** ("No parser outcome is represented only as empty
  content") — the `"File: <name>"` placeholder is gone; every non-success
  has a typed reason code. Verified by `IndexingLoopTest`'s
  `genericExtractionFailureDoesNotReturnPlaceholderDocument`.
- **Invariant 9** ("No single poison file blocks the corpus") — verified
  by `badFileDoesNotBlockGoodFileInSameBatch`.
- **Invariant 11** ("No hidden extraction truncation") —
  `ValidatedExtractionArtifact.truncated` is preserved through the
  ingestion boundary (not yet surfaced as a search-time field; that's the
  next slice).
- **Invariant 14** ("No diagnostic export leaks private file details by
  default") — `ingestion_ledger` schema (V6) stores `path_hash` only;
  `JobQueue.IngestionEventView` is structurally proven not to expose any
  raw-path component (`ingestionEventViewExposesOnlyPathHashStructurally`).
- **Invariant 6** (partial — "No unbounded expansion") —
  `TikaExtractionPolicy` enforces `maxExtractedChars`,
  `maxInputBytes`, `maxOfficeInputBytes`, `maxMetadataEntries`,
  `maxMetadataKeyChars`, `maxMetadataValueChars`,
  `maxEmbeddedResources`, `maxEmbeddedDepth`. Archive depth and
  compression-ratio are policy fields but not yet wired into a recursive
  parse path — see "not yet" below.
- **Invariant 10** ("No crash without classification") — sandbox crash,
  timeout, malformed response, oversized response, and stdout pollution
  each produce a typed `SANDBOX_FAILED` outcome, proven by
  `ProcessExtractionSandboxTest`.

Design-section coverage:

- §1 Ingestion Boundary — partial: `WorkerIngestionAuthority` +
  `SourceAdmission` + `FileEnvelope` + `FileFreshnessSnapshot`
  centralize Worker-side admission classification. Head-side walkers are
  still authoritative enumerators (planned future work).
- §3 FileEnvelope + FreshnessSnapshot — minimal viable form: path,
  hash, file-key, size, mtime, regular-file flag, observed-at. No
  SourceRoot, MimeProbe, ArchiveProbe, LinkProbe, CloudProbe,
  RiskClass, or PostOpenValidation yet.
- §4 Budget Algebra — `TikaExtractionPolicy` carries the V1 budget
  fields and rejects unsafe construction at compile time
  (`xmlExternalEntitiesDisabled=false` throws). Per-file budget
  derivation from RootPolicy/runtime mode is not yet implemented.
- §5 Extraction Sandbox — interface (`ExtractionSandbox`),
  in-process implementation (`InProcessExtractionSandbox`),
  out-of-process implementation (`ProcessExtractionSandbox` with
  versioned JSON request/response, bounded stdout/stderr, protocol-only
  enforcement, typed timeout/crash/malformed/oversize/pollution
  outcomes), child entry point (`ExtractionSandboxChild`), and operator
  selection seam (`ExtractionSandboxFactory` +
  `JUSTSEARCH_EXTRACTION_SANDBOX_MODE` /
  `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND`) all landed.
- §6 Parser Output Is Still Untrusted — `ExtractionArtifact.validate(
  policy, sourcePathHash)` enforces policy-id match, status check,
  content/metadata/embedded-resource/warning caps; only
  `ValidatedExtractionArtifact` reaches `IndexingDocumentOps`. The
  `buildDocument(... ExtractionResult ...)` overloads are now
  `private static buildDocumentInternal`, with no production back door.
- §7 Typed Extraction Outcomes — `IngestionOutcome` +
  `IngestionOutcomeClass` (13 classes including the dedicated
  `SANDBOX_FAILED`) + `IngestionRetryPolicy` + `IngestionReasonCodes`
  live in `worker-core` and flow through every transition.
- §8 Quarantine Ledger — `ingestion_ledger` table (V6 schema) carries
  `path_hash`, `collection`, outcome class/reason/retry policy, sanitized
  diagnostic, observed-at, source size/mtime/kind, artifact status,
  policy id, parser id. Atomic with the queue update; rollback proven
  by 5 dedicated tests (single, batch, retryable, terminal, defer).
  `recordOutcome` updates only the latest-outcome columns and never
  emits a contextless ledger row.
- §10 Document Builder Consumes Only Trusted Artifacts — entry point
  is the `ValidatedExtractionArtifact` overload only;
  `IndexingDocumentOps.SourceFileMetadata` carries provenance
  (`sourcePathHash`, `artifactStatus`, `policyId`, `parserId`,
  `truncated`, `embeddedResourceCount`, `maxEmbeddedDepth`,
  `artifactValidatedAtMs`).

### Not yet (design body still ahead)

Each item names the design section that owns it. Items closed by
Phases 0–2, tempdoc 418 (Phase A/B/B-Hardening + Phase C sub-commit A),
and Slices A–E have been removed; only genuinely-pending work remains.
Most outstanding items are gated on user/product decisions that the
implementation cannot unilaterally make.

- **§2 SourceRoot capability model.** Roots are still strings; symlink
  policy, root trust level, scan mode, and root-id provenance not yet
  modelled. Gated on product decisions (symlink default, cloud
  hydration default, root identity stability across rename).
- **§3 FileEnvelope full shape.** Current envelope is freshness-only.
  No `SourceRoot` reference, `MimeProbe`, `ArchiveProbe`, `LinkProbe`,
  `CloudProbe`, `RiskClass`, or `PostOpenValidation`. Gated on §1.
- **§4 Budget Algebra full shape.** No per-root or per-runtime-mode
  budget derivation; `IngestionBudget` (named, derived) does not exist
  as a typed value. Tika-native `SecureContentHandler`,
  `RecursiveParserWrapper`, and explicit XML-hardening audit point at
  parser construction not yet wired. Gated on §1.
- **§5 Sandbox in production.** Factory + protocol exist and are
  tested (Phase A). Production wiring (build-time classpath layout,
  packaged-app classpath construction) is still future work; operators
  selecting `process` mode today must supply their own command.
  Sandbox lifecycle (long-lived pool, drain/restart per 406
  service-identity pattern) not yet designed. Cross-cuts the packaging
  work in tempdoc 374.
- **§9 Archives as Virtual Source Roots.** Archive depth and
  embedded-resource caps exist on `TikaExtractionPolicy`; archive
  members are not yet first-class documents and there is no
  archive-specific budget profile. Three-way product decision
  (first-class members vs. metadata-only children vs. skipped) still
  open.
- **§11 Search-Time Honesty.** Phase 1.1 + Slices A.1/A.2 closed the
  parser-side fields (`extraction_status`, `content_truncated`,
  `extraction_reason_code`, `extraction_policy_id`,
  `extraction_parser_id`, `embedded_resource_count`,
  `parser_warnings_count`). Still missing: `source_trust_level` and
  `source_root_id` — both gated on §1 / §2.
- **§12 Semantic Poisoning / AI/RAG Safety.** No surfacing through
  agent context yet. Research-shaped (eval bench needed before
  plumbing).
- **§13 Config Authority — per-root form.** Slice B made the global
  skip patterns / extensions / directory blacklist operator-
  configurable via `JUSTSEARCH_INGESTION_SKIP_{PATTERNS,EXTENSIONS,
  DIRECTORY_NAMES}`. Per-root skip policy (different patterns per
  SourceRoot) remains gated on §1 SourceRoot.
- **§14 Adversarial Corpus Generator — Windows-specific rows.**
  Slice C.1/C.2 added symlink-loop (POSIX) + mid-extraction mutation
  cases. Slice C.3 re-enabled `NastyCorpusTest` in CI after restoring
  the missing fixture. Still missing: Windows reparse points and
  cloud-placeholder simulation (require platform-specific test
  infrastructure that doesn't currently exist in the test harness).
- **Quarantine UI / Health surface (partial).** Phase 1.2 added gRPC
  `RecentIngestionEvents` + `IngestionOutcomeSummary` RPCs and Head
  endpoints `GET /api/diagnostics/ingestion/{recent,summary}` —
  operator-curl-able. UI wiring deferred per Phase 2.4 user decision
  (defer until product question converges).
- **Tempdoc 418 Phase C sub-commit B (deferred).** Slice D landed
  Phase C sub-commit A (back-compat shim deletion: 2-arg
  `WorkerMethvinWatcher` ctor + `IngestTool.defaultLocalScanCallback`).
  Sub-commit B (production wiring deletion: `MethvinWatcherStrategy`,
  `WatcherBootstrap`, `WatcherEventOps`, `WatcherEventCallbacks`,
  the `watcherBootstrap.startWatcher/stopWatcher` calls in
  `RootLifecycleOps`, the `RemoteKnowledgeClient.setWatcherBootstrap`
  public method, the `KnowledgeServerBootstrap.watcherBootstrap`
  field, the `app-indexing` Methvin dep) lands after Phase B has
  soaked in production for at least one release cycle. The full
  deletion list is documented in
  `docs/tempdocs/418-worker-owned-filesystem-traversal.md` § "Phase C sub-commit
  B (deferred — post-soak)".

### V1 verification

Final verification at 2026-04-25:

- `./gradlew.bat build -x test` → BUILD SUCCESSFUL (238 tasks).
- `./gradlew.bat :modules:configuration:test :modules:worker-core:test
  :modules:worker-services:test :modules:indexer-worker:test` →
  BUILD SUCCESSFUL.
- `./gradlew.bat spotlessCheck` → BUILD SUCCESSFUL (clean).

### Phase 0 — V1 defect fixes (commit `c18a07dfd`)

Fixes for issues surfaced in critical analysis after V1:

- **Outcome-write rollback is no longer silent.** `OutcomeWriteException`
  introduced in `worker-core`. Every outcome-aware queue method
  (`markDone(... outcome ...)`, `markFailed`, `defer`,
  `markDoneTransitions`, `recordOutcome`, `recordIngestionEvent`)
  re-throws on `SQLException` after rolling back. `IndexingLoop` wraps
  every call in a `recordOutcomeSafely` helper that logs + leaves the
  job in `PROCESSING` so `recoverStuckJobs` requeues it on next
  Worker restart. `drainPendingMarkDone` retains failed paths via
  iterator-with-remove instead of clearing the whole list.
- **`IngestionRetryPolicy` now drives queue behavior.** Collapsed
  `markFailedTerminal` / `markFailedRetryable` into a single
  `markFailed(path, outcome[, entry])` that derives terminal vs.
  retryable from `outcome.retryPolicy() == NONE`. Single source of truth.
- **`xmlExternalEntitiesDisabled` renamed to
  `requireXmlEntitySafeTikaDefaults`** with Javadoc that explicitly
  marks it as a tripwire on policy construction, not parser-config
  enforcement. Honest about what V1 actually delivers.
- **Structural defense for the artifact boundary.** New
  `IndexingDocumentOpsArchitectureTest` uses reflection to assert
  exactly one public `buildDocument` overload exists and accepts a
  `ValidatedExtractionArtifact`. A future contributor can't
  re-introduce the bypass without breaking the test.
- **Pipeline throughput baseline** logged as a user-coordinated action
  in `docs/observations.md` because the measurement requires the shared
  dev stack and a controlled before/after that the worktree can't
  execute alone.

### Phase 1 — High-leverage mechanics (commit `c18a07dfd`)

- **§11 search-time honesty fields.** Added 6 schema fields to
  `SchemaFields`, both SSOT catalog copies, and
  `INDEXABLE_FIELDS`/`INTERNAL_FIELDS`:
  `extraction_status`, `content_truncated`, `extraction_reason_code`,
  `extraction_policy_id`, `extraction_parser_id`,
  `embedded_resource_count`. Written from `SourceFileMetadata` in
  `IndexingDocumentOps.buildDocumentInternal`. Existing indices read
  null until reindexed (tolerated; indexing metadata, not search
  payload).
- **§12 backend exposure of the ingestion ledger.** Two new gRPC RPCs
  (`RecentIngestionEvents` server-streaming-style + `IngestionOutcomeSummary`),
  matching `DelegatingIngestService` forwards, `RemoteKnowledgeClient`
  client methods, `IndexingService` interface defaults, and Head
  endpoints `GET /api/diagnostics/ingestion/recent` +
  `GET /api/diagnostics/ingestion/summary`. Operator-curl-able. UI
  surfacing deferred per Phase 2.4 user decision.
- **§14 adversarial corpus.** `AdversarialCorpusIngestionTest` runs
  7 hostile cases against the real `PolicyDrivenTikaExtractor` (zero-byte,
  invalid UTF-8, long single line, .pyc skip, directory non-regular,
  malformed ZIP, Office tilde temp). Proves the typed-outcome chain
  survives real Tika behavior, not just synthetic exceptions thrown by
  mocks. Platform-specific rows (symlink loops, Windows reparse, cloud
  placeholders, mid-extraction mutation) deferred.
- **Sandbox env vars documented** in
  `docs/reference/configuration/environment-variables.md`:
  `JUSTSEARCH_EXTRACTION_SANDBOX_MODE` and
  `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND`.

### Phase 2 — Product-decision-driven slice (commit `c18a07dfd`)

User decisions captured at 2026-04-25:

- **2.1 Cloud placeholders → DEFERRED routing.**
  `IngestionReasonCodes.CLOUD_PLACEHOLDER` added.
  `SyncDirectoryOps.recordCloudPlaceholderObservation` writes a
  `DEFERRED_POLICY` ledger event when the Windows
  `FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS` bit is set. The file is NOT
  enqueued (extraction would trigger network hydration); the ledger
  row is the user-visible signal.
- **2.2 Sandbox config stays env-only.** No code change — the
  direct-`EnvRegistry` pattern (precedent: `INDEXER_WORKER_VERSION`,
  `VDU_QUALITY_THRESHOLD`) matches the user's preferred operational
  posture. Logged in `docs/observations.md`.
- **2.3 Full Worker-owned filesystem traversal.** User chose the bigger
  option (vs. pragmatic skip-policy unification) — design and
  implementation delegated to tempdoc 418.
  **Phase A landed** (commit `0d546e86e`): `ScanRoot` server-streaming
  + `WatchRoot` / `UnwatchRoot` RPCs + `WorkerScanOps` walking +
  `RootWatcherRegistry` placeholder, all additive.
  **Phase B landed** (commits `9d46d2807` → `e5508b0dd`, four
  sub-slices): the four Head-side directory walkers
  (`KnowledgeSearchController`, agent `IngestTool`,
  `RootLifecycleOps.walkAndSubmit`, file watcher) all dispatch through
  Worker RPCs. Methvin watcher dependency moved to `worker-services`;
  `WorkerMethvinWatcher` delivers events into `JobQueue` directly with
  no IPC hop. Both watchers run during the soak window; the queue's
  `INSERT OR REPLACE` coalesces duplicate enqueues without Head-side
  dedup. **Phase C cleanup** (delete shadowed Head-side watcher
  infrastructure) is deferred until Phase B has soaked in production
  for at least one release cycle.

### Code-review fixes (commit `f76a625e2`)

Fixes for items surfaced in post-Phase-2 code review:

- **#5** `markFailed` rejects `DEFER_WITHOUT_ATTEMPT` outcomes with
  `IllegalArgumentException` pointing at `defer(...)`. Eliminates the
  silent foot-gun where a misrouted defer outcome would mark the job
  FAILED + increment attempts.
- **#10** `IngestionLedgerEntry` compact constructor caps every string
  field at 256 chars. Bounds on-disk row size against adversarial
  parser-id / policy-id values.
- **#1** `Telemetry.Counter` `ingestion.outcome_write_failures_total`
  in `IndexingLoop.recordOutcomeSafely` so persistent rollback failures
  surface beyond log lines.
- **#4** `ExtractionArtifact.validate(int)` renamed to
  `validateContentBoundsOnly(int)` with explicit Javadoc that
  production indexing must use `validate(policy, hash)`. Callers
  updated.
- **#2** `ProcessExtractionSandbox.LimitedBytes` carries the
  `IOException` cause so sandbox-stdout read failures throw
  `SandboxExtractionException` with the underlying cause attached.
- **#3** Sandbox child stderr on the success path is logged at
  debug. Operators see JNI / native-loader noise without it crossing
  the protocol boundary.
- **#7** `JobQueue.hasRecentLedgerEvent(pathHash, reasonCode, sinceMs)`
  + `SqliteJobQueue` impl. `SyncDirectoryOps` dedups
  cloud-placeholder events within a 24h window so repeated walks
  don't grow the ledger linearly with `N × scans`.
- **#6** `cleanupOldJobs` no longer touches `ingestion_ledger`. New
  `cleanupOldLedgerEvents(int retentionDays)` with 180d default in
  `KnowledgeServer` (vs. 30d for queue rows). Audit trail outlives
  queue retention so "why is this file missing from search?" remains
  answerable past the 30d horizon.
- **#8 / #9** cosmetic items deferred (consistent with surrounding
  code style; not regressions).

### Tempdoc 418 — Worker-owned filesystem traversal

Closes invariant 1 ("no raw path crosses into indexing") end-to-end.
Detail in `docs/tempdocs/418-worker-owned-filesystem-traversal.md`. Summary:

- **Phase A** (commit `0d546e86e`) — Worker-side scaffolding. Three new
  RPCs (`ScanRoot` server-streaming, `WatchRoot`, `UnwatchRoot`) in
  `indexing.proto` + 7 messages. `WorkerScanOps` walks roots, applies
  the SyncDirectoryOps skip + caller exclude globs, enqueues admitted
  files, streams `ScanRootProgress` every 100 files.
  `RootWatcherRegistry` placeholder. `DefaultWorkerAppServices` wires
  the registry. 8 new test cases.
- **Phase B** (commits `9d46d2807` → `e5508b0dd`, four sub-slices) —
  Head-side migration:
  - **B1** `KnowledgeSearchController.handleIngest` rewritten;
    `RemoteKnowledgeClient.scanRoot` server-streaming method.
  - **B2** Agent `IngestTool` and `RootLifecycleOps.walkAndSubmit`
    migrated; backpressure preserved between progress events; terminal
    progress drives state persistence; `ExcludeMatcher.patterns()`
    forwards globs.
  - **B3** Methvin watcher dep moved to `worker-services`. New
    `WorkerMethvinWatcher` delivers events directly to `JobQueue`. Both
    Head and Worker watchers run during the soak window; SQLite
    `INSERT OR REPLACE` coalesces duplicate enqueues without Head-side
    dedup.
  - **B4** Tempdoc updates + integration coverage check.
- **Phase C sub-commit A** (`e9b67a4ae`, Slice D) — back-compat shim
  deletion (2-arg `WorkerMethvinWatcher` ctor +
  `IngestTool.defaultLocalScanCallback`).
- **Phase C sub-commit B** — deferred (delete the load-bearing
  Head-side watcher infrastructure after one release cycle of soak).

### Slice A–E — remaining-implementation series (DONE, commits `de0df4272` → `c84fd42ba`)

Six independently reviewable commits closing every technically-
unblocked item from the design body. Plan file at
`C:\Users\<user>\.claude\plans\snazzy-soaring-sonnet.md`.

- **Slice A.1+A.3** (`de0df4272`): surfaced `parser_warnings_count`
  schema field. `SchemaFields.PARSER_WARNINGS_COUNT` declared and
  registered in `INDEXABLE_FIELDS`; `IndexingDocumentOps.SourceFileMetadata`
  carries the count from the validated artifact's warnings list;
  `IndexingDocumentOps.buildDocument` writes the field when count > 0
  (mirrors `EMBEDDED_RESOURCE_COUNT` conditional). Both catalog
  copies updated; `SSOT/manifests/repro/repro.v1.json` regenerated
  via the new `regenSsotManifest` Gradle task. Two new
  `IndexingLoopTest` cases.
- **Slice A.2** (`0b7753a4b`): wired the latent
  `EXTRACTION_REASON_CODE` write. Pre-Slice-A.2 the schema field was
  declared (`SchemaFields.java:190`) and registered (line 287) but
  never populated. New `IngestionReasonCodes.SUCCESS_PARTIAL` constant;
  `SourceFileMetadata.reasonCode` field;
  `IndexingDocumentOps.deriveReasonCode` helper maps SUCCESS_PARTIAL
  → reason code, SUCCESS_FULL → null (omit, implicit success). Two
  new `IndexingLoopTest` cases.
- **Slice B** (`405e2959f`): operator-configurable global skip
  patterns/extensions/directory blacklist via three new env keys
  (`JUSTSEARCH_INGESTION_SKIP_{PATTERNS,EXTENSIONS,DIRECTORY_NAMES}`).
  `IngestionSkipPolicy` becomes instance-based with an installable
  singleton (matches `Telemetry.installNoOp` precedent);
  `DefaultWorkerAppServices` reads + installs at Worker boot before
  any ingestion path can fire. Five new `IngestionSkipPolicyTest`
  cases. Updates the stale §13 "not yet" bullet that claimed skip
  patterns still lived in `WorkerIngestionAuthority` (B-H.1 had
  already moved them).
- **Slice C.1+C.2** (`be8884d91`): symlink-loop case (POSIX-only via
  `@EnabledOnOs({LINUX, MAC})`) + mid-extraction mutation case
  (cross-platform, custom `ContentExtractorProvider` truncates the
  file inside `extract()`). Mutation case asserts
  STALE_SOURCE/SIZE_CHANGED_AFTER_SNAPSHOT.
- **Slice C.3** (`41f626906`): re-enabled `NastyCorpusTest` after
  investigation surfaced the actual root cause of the original CI
  disable — the `nasty-archive.zip` fixture was missing from
  `src/test/resources/corpus/nasty/`. Fixture restored (force-added
  past the `*.zip` gitignore rule); `@DisabledIfEnvironmentVariable`
  removed. All 7 cases pass in <1s locally.
- **Slice D** (`e9b67a4ae`): tempdoc 418 Phase C sub-commit A —
  deleted the back-compat shims. 2-arg `WorkerMethvinWatcher`
  constructor gone (no production caller can accidentally drop
  DELETE events). `IngestTool.defaultLocalScanCallback` and the 1-
  and 2-arg `IngestTool` constructors gone; `IngestToolTest` got a
  private `toolWithLocalScan` helper that builds the 3-arg form with
  an inlined local-walk scan callback. Tempdoc 418 Phase C section
  split into "sub-commit A (DONE)" + "sub-commit B (deferred —
  post-soak)" with the expanded sub-commit-B deletion list.
- **Slice E** (`c84fd42ba`): formalised the ingestion-ledger privacy
  contract. New "Ingestion Ledger Privacy Contract" section in
  `docs/explanation/03-knowledge-server.md` (between Retention &
  Bloat and Durable Cutover Buffer) enumerates the 14 in-scope
  `IngestionEventView` fields and states the rule for adding a new
  field. New `ingestionEventViewExportContractIsPinned` test pins
  the exact 14-field set; future additions trigger the test failure
  + a pointer to the doc contract. Two layers of defense: positional
  ctor in `SqliteJobQueue` breaks compilation first, the pin test
  catches name-space-only additions that compile cleanly.

### Slice G — critical-analysis fix pass (DONE, commits `975fe4bc6` → `69a3c3228`)

Post-Slice-A–E critical-analysis pass surfaced 2 high + 6 medium
defects/gaps; Slice G closes 8 of the 10 in four sub-commits. Plan
file at `C:\Users\<user>\.claude\plans\snazzy-soaring-sonnet.md`.

- **Slice G.1** (`975fe4bc6`): closes H1 (the LEDGER ↔ DOCUMENT
  inconsistency for SUCCESS_PARTIAL paths). `IndexingLoop.drainPendingMarkDone`
  partitioned the pending transitions by `entry.artifactStatus()` and
  emits two `markDoneTransitions` calls (one per outcome class) so
  the ledger row's `outcomeClass` matches the document's
  `extraction_reason_code` field. New `outcomeForArtifactStatus`
  helper + `drainGroup` extraction. Two new tests: mixed-batch unit
  test + real-Tika truncation integration test (the regression test
  that would have caught H1 originally).
- **Slice G.2** (`4bd4d8af9`): closes H2 (pin verification was
  incomplete) + M8 (NOOP_DELETE_SINK masked spurious deletes). The
  pin test was re-verified end-to-end by adding a stub field, updating
  `SqliteJobQueue`, observing the failure message
  `added=[testFakeField], removed=[]`, and reverting. Symmetric-
  difference assertion now surfaces the diff inline.
  `WorkerMethvinWatcherTest`'s NOOP sink became a `RecordingDeleteSink`
  with `sink.observed.isEmpty()` assertions in create/modify cases.
- **Slice G.3** (`dd8621271`): closes M1 + M3 + M5. New
  `DefaultWorkerAppServicesSkipPolicyTest` (9 cases) covers the
  env→policy chain. `IndexingDocumentOps.deriveReasonCode` switch is
  exhaustive on `ExtractionStatus` (no `default` arm; new enum values
  fail compilation). `nasty-archive.zip` binary deleted; `@BeforeAll`
  generates it programmatically in the build resources dir.
- **Slice G.4** (`69a3c3228`): closes M2 (docs-only after EnvRegistry
  investigation) + M7 + L1 + O1. Redaction-contract doc enriched
  (path-normalisation spec, derivable-vs-not concrete examples,
  `sha256Hex` location, operator query pattern). Empty-env semantics
  documented (M2 is docs-only because `EnvRegistry.get()` collapses
  null+blank globally). `regenSsotManifest` added to `/ssot-catalog`
  skill. `AdversarialCorpusIngestionTest.malformedZipFailsTyped`
  degenerate-assertion finding logged in `observations.md`.

Slice G left 2 deferred items: M6 (symlink-loop pin requires
Linux/Mac access) and L1–L5 cosmetic.

- **Slice G.5** (`27c5c47df`, 2026-04-26): closes the post-validation
  Tika-cap defect. The validation pass empirically confirmed that
  `PolicyDrivenTikaExtractor.extractArtifact`'s post-extraction
  `result.content().length() > policy.maxExtractedChars()` check is
  structurally unable to fire when input chunk boundaries align with
  the cap (8 KiB SAX chunks divide 10 MiB exactly), making Slices A.2
  + G.1 inert in production. Probe test on a 12 MiB plain-text file
  under the default 10 MiB cap reproduced the issue:
  `contentLen=10485760 truncated=false status=SUCCESS_FULL`. Fix
  exposes the SAX handler's existing `limitReached` flag through
  `StructuredContentHandler.isLimitReached()` →
  `StructuredContentExtractor.extractWithStatus(Path) →
  StructuredExtractionResult(result, truncated)` →
  `PolicyDrivenTikaExtractor`. Regression test
  `defaultPolicyOversizedPlainTextProducesPartialArtifact` exercises
  the production path with the default policy and asserts
  `status=SUCCESS_PARTIAL` to keep this regression caught.

### Coordination with 406 substrate (2026-04-25)

The 406 worktree shipped its observability substrate to main
(commits `f0122b37d` → `c5e6284f7`) while Slice A–E + G were in
flight. The 406 agent reached out with three threads worth recording
here so the architectural framing isn't lost between branches.

**1. Architectural-shape match — 410 ledger as Form B reference.**
`IngestionOutcomeClass` + `IngestionReasonCodes` + `IngestionRetryPolicy`
+ `ingestion_ledger` (V6 schema) + `IngestionEventView` is the same
architectural shape the 406 work codifies as `WorkerLuceneTelemetryEvents`
+ `WorkerLuceneTelemetryAdapter` + `index.runtime.*` + `ALLOWED_TAG_KEYS`,
applied to a different bridge: typed-event emission + bounded surface
+ canonical namespace + stable wire identifiers. The 406 agent's
proposed ADR-0027 (per `docs/tempdocs/417-tier4-tier5-followups-from-406.md`
§5.3) will codify the shared discipline. Per the agent's framing
exchange, the recommended ADR structure is:

- Decision: the 4-element shared discipline (typed events, bounded
  surface, canonical namespace, stable wire identifiers).
- **Form A — in-memory OTel adapter** (406's reference): bridge
  interface + adapter + `<scope>.runtime.*` namespace + tag allowlist.
  Use when consumption is "scrape NDJSON for aggregates."
- **Form B — durable per-event ledger** (410's reference, this work):
  typed value records + atomic `inTransaction()` emission + schema-
  versioned table + privacy-redacted projection record. Use when
  consumption is "operator queries 'why was this specific path X?'".
- Pick-the-form decision tree + the third-shape anti-pattern
  (`ingestion.outcome_write_failures_total` → migrate to Form A).

When ADR-0027 lands, every reference in this tempdoc to "the
ingestion ledger pattern" should cite the ADR's Form B section
rather than re-deriving from this tempdoc. Until the ADR lands, this
tempdoc is the canonical statement of Form B.

**2. Three coordination follow-ups, all gated post-merge.**
- **Migrate `ingestion.outcome_write_failures_total` to Form A.** The
  counter at `IndexingLoop`'s `outcomeWriteFailureCounter` is the
  third-shape callsite — direct `Telemetry.counter(...)`, no adapter.
  Migration shape depends on ADR-0027; deferring until the ADR lands
  avoids picking a shape unilaterally. Tracked in
  `docs/observations.md` Inbox.
- **Add `component` to `NdjsonMetricExporter.ALLOWED_TAG_KEYS`.** The
  `extraction.timeout_total{component=content_extractor}` callsite at
  `TimeboxedContentExtractor.java:126-127` (Phase 1.x V1, not Slice
  G) emits a tag the exporter silently strips. Two-line fix; deferred
  to post-merge to avoid contention with `main`'s `reason` allowlist
  addition (line 50 in 406's version).
- **Trace-id correlation via exemplars.** Tempdoc 417 §5.1 enables
  OTel exemplars on metrics. With exemplars on, wrapping an
  `IndexingLoop.recordOutcomeSafely` call in a span + carrying
  `ledger_id` as a span attribute would let exemplars on
  `swap_duration_ms` correlate to the ingestion event that triggered
  the swap. Future-opportunity; not a defect.

**3. Number collision on tempdoc 412 — RESOLVED post-merge by renumbering to 418.**
Both `docs/tempdocs/418-worker-owned-filesystem-traversal.md` (this worktree) and
`docs/tempdocs/412-observability-pattern-adoption.md` (the 406
follow-up program, commit `2203664de`) claimed 412 independently.
Resolution: this worktree's tempdoc renumbered to
`docs/tempdocs/418-worker-owned-filesystem-traversal.md`. The 412–417
sequence in the 406 program is more deeply embedded in cross-references
(tempdoc 417 §5.3 explicitly cites "412/413/414/415"), so the unique
side renumbered. Pre-merge commit messages (Slice A–E + G + Phase
A/B/B-H + Phase C sub-commit A) still say "tempdoc 412" — those
historical references mean Worker-Owned Filesystem Traversal (now 418),
not the 406 program's InferenceLifecycleManager work. Disambiguate by
commit date / hash. In-text references in this tempdoc updated to
"tempdoc 418" below; commit-message references kept verbatim.

### Final verification

- `./gradlew.bat spotlessApply` clean.
- `./gradlew.bat build -x test` → BUILD SUCCESSFUL.
- 13-module test suite green: `configuration`, `worker-core`,
  `worker-services`, `indexer-worker`, `adapters-lucene`, `app-api`,
  `app-services`, `app-agent`, `ui`, `ssot-tools`, `indexing`,
  `ipc-common`, `app-indexing`.

### Structural invariant coverage update

After Phase B, **invariant 1 ("no raw path crosses into indexing") is
fully closed end-to-end** — up from "Worker boundary clean only" prior
to tempdoc 418. The watcher event path is the new authoritative source;
Head walkers no longer expand directories. The full structural
invariant scoreboard is now:

| Invariant | Status |
|---|---|
| 1 — No raw path crosses into indexing | **Done end-to-end** (tempdoc 418 Phase A+B) |
| 2 — No stale path check becomes authority | Done (FileFreshnessSnapshot pre/post checks) |
| 3 — No parser can mutate index state | Done |
| 4 — No parser outcome is empty content | Done |
| 5 — No failed file disappears | Done |
| 6 — No unbounded expansion | Partial (input/extracted/metadata/embedded caps; archive depth field exists, recursive parse not yet enforcing) |
| 7 — No source-root escape by default | Not yet (no SourceRoot policy) |
| 8 — No cloud hydration by accident | Done (DEFERRED_POLICY routing + 24h dedup) |
| 9 — No single poison file blocks the corpus | Done |
| 10 — No crash without classification | Done |
| 11 — No hidden extraction truncation | Done (`content_truncated` + `parser_warnings_count` schema fields) |
| 12 — No adversarial suite outside CI forever | Done (9 cases in CI: 7 V1 + symlink-loop POSIX + mid-extraction mutation; `NastyCorpusTest` re-enabled by Slice C.3; Windows reparse + cloud-placeholder still OS-test-infra-gated but nothing in CI is silently skipped) |
| 13 — No parser-specific special case leaks into search | Done (extraction provenance fields) |
| 14 — No diagnostic export leaks private file details | Done (structural test + Slice E redaction-policy doc + ArchUnit-style field-set pin) |
| 15 — No ingestion policy side channel | Done (sandbox mode + skip patterns/extensions/directory blacklist all routed through canonical `EnvRegistry` — Slice B; per-root form gated on §1) |

**14 of 15 fully closed**, 1 partial (invariant 6, archive recursive-
parse enforcement still ahead — gated on §9). Up from 12/2/1 before
tempdoc 418 Phase B and 13/2/0 after Phase B.

### Critical path through the implementation

For agents picking up the next slice, the production read path is:

```
Worker IndexingLoop.extractJob(path, collection)
  -> WorkerIngestionAuthority.admit(path)
       -> SourceAdmission { ADMIT | SKIP_DONE | STALE_DONE | RETRYABLE_FAILURE }
       -> FileEnvelope (from FileFreshnessSnapshot)
  -> contentExtractor.extractArtifact(path)
       -> ExtractionSandboxFactory selects in-process | process via env
       -> sandbox returns ExtractionArtifact
       -> ExtractionArtifact.validate(policy, pathHash) -> ValidatedExtractionArtifact
  -> handleStaleAfterSnapshot(envelope) checks freshness post-extraction
  -> writeExtractedJob(ExtractedJob{filePath, collection, artifact, startTime, envelope})
       -> IndexingDocumentOps.buildDocument(... ValidatedExtractionArtifact ...)
       -> indexingCoordinator.indexSingle(doc)
       -> pendingMarkDone collects IngestionLedgerTransition
  -> drainPendingMarkDone() after commit
       -> jobQueue.markDoneTransitions(transitions, SUCCESS_FULL outcome)
       -> single SQLite transaction: UPDATE jobs + INSERT ingestion_ledger
```

Every typed branch in `extractJob` and `writeExtractedJob` produces
exactly one outcome and at most one ledger event, and every ledger
event is paired with a queue mutation in one `inTransaction()` block —
the rollback tests prove the atomicity directly.

## Context

JustSearch's first production act is to read files the user points it at.
Those files are untrusted input. They may be benign-but-weird, corrupt,
oversized, cloud-backed, recursively compressed, parser-pathological, or
deliberately malicious.

The first version of this tempdoc correctly identified ingestion as a
missing explicit authority, but it leaned too far toward inventing a new
boundary from scratch. Some of the required design is already solved by
official platform primitives, Apache Tika primitives, OWASP guidance, and
JustSearch's own AI pack import path.

The corrected thesis is:

**The correct design is a single JustSearch ingestion authority that
composes platform, Tika, OS, and internal precedents into one typed
boundary.**

That boundary should make clear what is already handled, what is partially
handled, and what remains unsolved.

## Official Prior Art

This design should reuse established controls before inventing custom
ones.

- [Apache Tika Security Model](https://tika.apache.org/security-model.html)
  explicitly treats parsing untrusted data as dangerous. Tika documents
  denial of service, XXE/SSRF, command injection, deserialization, crashes,
  detection errors, and parser differentials as real risks. It also states
  that users are responsible for handling crashes and other consequences
  of untrusted parsing. This supports the Extraction Sandbox as a
  correctness boundary, not an optional hardening flourish.
- [Apache Tika Configuring Tika](https://tika.apache.org/3.2.3/configuring.html)
  already provides parser and detector configuration, including inclusion,
  exclusion, ordering, and `TikaConfig`-driven construction. A correct
  JustSearch design should have an explicit Tika policy/config layer
  before reaching for bespoke parser selection logic.
- [Tika `SecureContentHandler`](https://tika.apache.org/3.2.3/api/org/apache/tika/sax/SecureContentHandler.html)
  provides zip-bomb-style protections based on output/input ratio, XML
  nesting depth, and package-entry depth.
- [Tika `WriteOutContentHandler`](https://tika.apache.org/3.2.1/api/org/apache/tika/sax/WriteOutContentHandler.html)
  provides bounded character output and write-limit signaling.
- [Tika `RecursiveParserWrapper`](https://tika.apache.org/3.2.3/api/org/apache/tika/parser/RecursiveParserWrapper.html)
  provides embedded-resource metadata patterns and explicit write-limit
  behavior for recursive parsing, while also warning that it holds data in
  memory and is not appropriate for arbitrarily large content.
- [Java `Files.walkFileTree`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html)
  already defines important traversal behavior: by default it does not
  follow symbolic links; with `FOLLOW_LINKS`, cycle detection uses file
  keys when available. Java also warns that `exists()` / `isReadable()`
  results are immediately stale and should be treated carefully in
  security-sensitive contexts.
- [Java `BasicFileAttributes.fileKey()`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/attribute/BasicFileAttributes.html)
  gives a platform-provided file identity hint, but it is nullable and
  only reliable while the filesystem and file remain static. It is useful
  for freshness and duplicate detection, not a permanent global identity.
- [Microsoft file attribute constants](https://learn.microsoft.com/en-us/windows/win32/fileio/file-attribute-constants)
  formally define `FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS`: a file or
  directory is not fully present locally, and reading/enumerating it may
  fetch remote content. JustSearch's cloud-placeholder behavior should be
  grounded in that OS-level signal.
- [OWASP File Upload Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)
  names parser exploitation, ZIP/XML bombs, size limits, filename safety,
  type validation, sandboxing, and secure parser/library configuration as
  standard defense-in-depth controls.
- [OWASP XXE Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html)
  emphasizes disabling DTDs, external entities, external DTD loading,
  XInclude, and unbounded entity expansion for untrusted XML parsing.
  Tika hides many parser details, but the ingestion design should still
  have an explicit XML-hardening audit point for parser configuration and
  future non-Tika XML paths.

The design implication: JustSearch should not build "security parsing"
from nothing. It should own the ingestion authority while composing
official controls into a coherent protocol.

## Internal Precedents to Reuse

JustSearch already contains useful pieces. They are real guardrails, but
they are scattered and do not yet form one ingestion boundary.

- `ContentExtractor` caps input file size, extracted text length, and
  applies a stricter Office-document size limit. `StructuredContentExtractor`
  applies similar input limits and captures structure through Tika's SAX
  path. Both currently use high-level Tika APIs and return ordinary
  extraction results rather than typed admission/extraction outcomes.
- `TimeboxedContentExtractor` wraps extraction in a timeout and records a
  timeout counter. This is useful, but cancellation is best-effort inside
  the same JVM and cannot be treated as a hard sandbox boundary.
- `SyncDirectoryOps` walks deterministically, skips high-churn directories,
  skips unreadable files, and skips cloud placeholders via the Windows
  recall-on-data-access bit. Its default `walkFileTree` usage also inherits
  Java's non-following symlink behavior.
- `GrpcIngestService.sanitizePath()` rejects blank, relative, nonexistent,
  unreadable, and obvious `..` traversal paths before enqueueing direct
  ingest requests. These checks are useful hints, but `exists()` and
  `isReadable()` are stale by the time extraction opens the file.
- `TextQueryOps` and `ChunkSearchOps` escape simple user text before
  passing it to Lucene `QueryParser` on the main simple-query paths. Query
  injection is not the central gap in this tempdoc, but it is part of the
  same "untrusted input must cross a typed boundary" pattern.
- AI pack import is the strongest local precedent: manifest validation,
  declared file set, size budgets, SHA-256 verification, path-normalized
  staging, safe regular-file checks, and symlink rejection. General file
  ingestion should not copy pack-import details directly, but it should
  copy the principle: untrusted bytes cross a declared boundary before
  they affect runtime state.
- `NastyCorpusTest` exists and exercises malformed inputs directly through
  `ContentExtractor`, but it is narrow, extractor-level, and disabled in
  CI. It does not verify the full ingest path, quarantine state, Worker
  health, or mixed-batch progress.

## Still Unsolved

The missing principle remains:

**Untrusted local files are not documents yet. They become documents only
after passing an explicit ingestion boundary.**

Today, JustSearch has a strong architectural boundary around Lucene
ownership and an increasingly strong boundary around model/session
construction. It does not yet have the same explicit boundary around
untrusted file bytes.

The current pipeline blends five concerns:

1. **Discovery** - find files under a root.
2. **Admission** - decide whether a path/file is eligible.
3. **Extraction** - run parser code over bytes.
4. **Document construction** - convert extracted content into schema fields.
5. **Index mutation** - write into Lucene and job state.

When those concerns share one operational lane, hostile input can express
itself as many different symptoms:

- parser hang,
- Worker crash,
- heap pressure,
- disk amplification,
- slow extraction,
- skipped file with no user-visible explanation,
- stale/replaced/deleted file races between discovery and extraction,
- corrupted/garbled content indexed as if trusted,
- duplicated traversal through symlinks/junctions,
- cloud-placeholder download side effects,
- bad file blocks good files behind it,
- test passes because it only called `extractSafe()` directly.

The unsolved architecture is not "more checks." It is one authority that
classifies these cases and produces durable, typed outcomes.

## Implementation Confidence Hardening Pass

This section records pre-implementation hardening work. Its purpose is to
raise confidence before code changes by mapping real flows, pressure-testing
the taxonomy, and identifying the smallest implementation slice that avoids
creating a second ingestion system.

### Current File-Entry Flow Map

Current raw-path entrypoints are broader than the first design pass assumed:

```text
UI POST /api/knowledge/ingest
  -> KnowledgeSearchController.handleIngest()
  -> expands directories in Head with Files.walk()
  -> KnowledgeHttpApiAdapter.ingest()
  -> RemoteKnowledgeClient.submitBatch()
  -> Worker GrpcIngestService.submitBatch()
  -> JobQueue.enqueue()
  -> IndexingLoop.extractJob()

Agent ingest_files tool
  -> IngestTool.execute()
  -> resolves relative paths against indexed roots
  -> expands directories in app-agent with Files.walk()
  -> KnowledgeHttpApiAdapter.ingest()
  -> same submitBatch/job queue/indexing loop path

Persisted watched root startup / add root / force reindex with excludes
  -> RootLifecycleOps.walkAndSubmit()
  -> expands directories in Head with Files.walk()
  -> submitBatchFn.accept(batch, force)
  -> Worker submitBatch/job queue/indexing loop path

File watcher CREATE/MODIFY
  -> MethvinWatcherStrategy
  -> WatcherEventOps.handleCreateOrModify()
  -> callbacks.submitBatch(List.of(path))
  -> Worker submitBatch/job queue/indexing loop path

Worker-side syncDirectory
  -> GrpcIngestService.syncDirectory()
  -> SyncDirectoryOps.walkAndEnqueueMissingFiles()
  -> JobQueue.enqueue()
  -> IndexingLoop.extractJob()

Migration / generation rebuild enqueue paths
  -> KnowledgeServerMigrationOps
  -> JobQueue.enqueue()
  -> IndexingLoop.extractJob()
```

The main implementation conclusion is encouraging but constraining:

**`IndexingLoop.extractJob()` is the first common Worker-side choke point
after durable queueing, but not the only admission point.**

That means the first implementation slice should centralize outcome
classification in the Worker extraction path before attempting to replace
every Head-side walk. Head-side walkers can continue to be opportunistic
enumerators at first, but they should stop being treated as authoritative
admission logic.

### Current Behavior Pressure Points

The flow map increases confidence in the single-authority design, but it
also exposes several implementation traps:

- Direct ingest expansion exists in multiple Head-side places. A big-bang
  "all paths must already be admitted before enqueue" rewrite would touch
  too much surface at once.
- `JobQueue.IndexJob` currently stores only `Path` plus optional
  collection. It has no source-root id, freshness snapshot, admission
  reason, policy id, or budget id. The durable queue can carry raw paths
  for the first slice, but the Worker must become the source of typed truth.
- `IndexingLoop.extractContent()` treats `ExtractionException` as a
  successful minimal placeholder document (`"File: <name>"`) while timeout
  becomes a failed job. This is the clearest proof that `ExtractionOutcome`
  should be introduced before sandboxing.
- `IndexingDocumentOps.buildDocument()` reads size and modified time again
  after extraction. That metadata read is currently best-effort and can
  observe a different file state than the pre-check/extraction path.
- `NastyCorpusTest` exercises direct extraction, not the common queue to
  `IndexingLoop` to `IndexingDocumentOps` path.

The design confidence goes up if the first implementation slice uses the
existing common Worker choke point. It goes down sharply for any first
slice that starts with sandboxing, schema expansion, or Head-side walk
replacement.

### Existing Failed-Job State vs. Quarantine Ledger

`jobs.db` already has a useful seed for ingestion durability:

- `jobs.path` is the normalized durable identity used by the queue.
- `jobs.state` distinguishes `PENDING`, `PROCESSING`, `DONE`, and `FAILED`.
- `jobs.attempts`, `jobs.retry_after`, `jobs.error_message`, and
  `jobs.last_updated` support retry/backoff and failed-job surfaces.
- `JobQueue.failureSummary()`, `listFailedJobs()`, and `clearFailedJobs()`
  already flow into Worker status and Head indexing APIs.

This raises implementation confidence because the first durable slice does
not need to invent a product surface from nothing.

But the existing failed-job table is not a Quarantine Ledger:

- it stores free-form `error_message`, not stable `reason_code`;
- it only represents eventual permanent `FAILED`, not `DEFERRED`,
  `STALE_SOURCE`, `PARTIAL`, or `METADATA_ONLY`;
- it has no source-root id, policy id, budget id, parser id, extraction
  method, or freshness snapshot;
- it has no privacy/redaction level for diagnostics export;
- `INSERT OR REPLACE` on enqueue resets attempts/error state, which is good
  for retrying work but bad if the product needs historical ingestion
  diagnosis.

Implementation conclusion:

**The first slice should extend or flank `jobs.db` with typed outcome
fields, but it should not pretend the queue row is the whole long-term
ledger.**

A credible v1 is either:

```text
jobs: path, state, attempts, retry_after, error_message, collection
      + last_outcome_class, last_reason_code, last_budget_id
```

or a separate companion table:

```text
ingestion_outcomes: path, observed_at, outcome_class, reason_code,
                    attempt_count, collection, diagnostic_hash,
                    privacy_level
```

The companion table is cleaner for long-term quarantine history. Extending
`jobs` is cheaper for the first confidence slice. The tempdoc should keep
both options open until the implementation inventory decides whether
history or status integration matters more for v1.

### Minimum Viable Outcome Taxonomy

The theoretical taxonomy later in this document is intentionally broad. A
first implementation should use a smaller taxonomy that maps cleanly onto
current behavior and can be tested at the `IndexingLoop` boundary:

```text
SUCCESS_FULL
SUCCESS_PARTIAL
SKIPPED_POLICY
DEFERRED_POLICY
STALE_SOURCE
UNSUPPORTED
BUDGET_EXCEEDED
PARSER_FAILED
PARSER_TIMEOUT
IO_FAILED
WRITE_FAILED
WRITE_UNAVAILABLE_DRAINING
SANDBOX_FAILED
```

Initial interpretation:

- `SUCCESS_FULL`: extraction succeeded and document was written normally.
- `SUCCESS_PARTIAL`: extraction produced indexable content but hit a
  declared limit or warning threshold.
- `SKIPPED_POLICY`: hidden/temp/system/binary extension/root policy skip.
- `DEFERRED_POLICY`: cloud placeholder or policy-deferred work that might
  succeed later without counting as a failure.
- `STALE_SOURCE`: missing/deleted/replaced/mutated file detected between
  queueing, admission, extraction, or write metadata.
- `UNSUPPORTED`: recognized file class that should not be parsed/indexed.
- `BUDGET_EXCEEDED`: input size, extracted text, embedded resource,
  archive depth, parser warning, or output size budget exceeded.
- `PARSER_FAILED`: Tika/parser failure that is not a timeout and not a
  declared budget outcome.
- `PARSER_TIMEOUT`: current `ExtractionTimeoutException` path.
- `IO_FAILED`: read/open/metadata failure not otherwise classified.
- `WRITE_FAILED`: extraction succeeded but document/chunk/index write
  failed.
- `WRITE_UNAVAILABLE_DRAINING`: Lucene runtime is draining or being
  swapped; retry on the upgraded holder rather than quarantining the file.
- `SANDBOX_FAILED`: reserved for the later process-isolation slice.

This taxonomy is deliberately less elegant than the long-term model. Its
value is that every current branch in `IndexingLoop.extractJob()` and
`writeExtractedJob()` can emit exactly one outcome without changing the
whole pipeline.

### Hostile-Input Behavior Matrix for V1

The first implementation should pressure-test itself against this matrix:

| Case | Current behavior | Desired v1 outcome |
| --- | --- | --- |
| Hidden/temp/system file | `shouldSkip()` marks job done | `SKIPPED_POLICY` |
| Missing file at processing time | best-effort delete, mark done | `STALE_SOURCE` |
| Unreadable file | `markFailed("File not readable")` | `IO_FAILED` or `SKIPPED_POLICY`, decided by policy |
| Unmodified file | mark done silently | `SKIPPED_POLICY` with reason `UNCHANGED` if surfaced, otherwise no ledger row |
| Oversized input | extractor throws, then placeholder document is indexed | `BUDGET_EXCEEDED`, no fake full-content success |
| Oversized Office input | extractor throws, then placeholder document is indexed | `BUDGET_EXCEEDED`, no fake full-content success |
| Generic Tika failure | placeholder `"File: <name>"` document is indexed | `PARSER_FAILED` or `SUCCESS_PARTIAL` only if metadata-only indexing is explicit |
| Extraction timeout | failed job with free-form timeout string | `PARSER_TIMEOUT` |
| IO error during extraction | failed job with free-form IO string | `IO_FAILED` |
| Runtime error during extraction | failed job with free-form message | `PARSER_FAILED` or `SANDBOX_FAILED` depending boundary |
| Write/build/index error | `markFailed(e.getMessage())` | `WRITE_FAILED` |
| Lucene runtime draining during write | depends on caller/backoff path | `WRITE_UNAVAILABLE_DRAINING`, retryable, not quarantine |
| File changes after extraction before `buildDocument()` metadata read | best-effort metadata may observe new state | `STALE_SOURCE` once freshness snapshot exists |
| Cloud placeholder in Worker sync | skipped before enqueue | `DEFERRED_POLICY` once root policy/ledger exists |
| Cloud placeholder in Head-side walks | not consistently classified | `DEFERRED_POLICY` after Head walkers stop owning admission |
| Symlink/junction escape | depends on entry path and walker behavior | `SKIPPED_POLICY` or explicit root-policy admission |
| XML entity attempt | parser-dependent | `PARSER_FAILED`, `BUDGET_EXCEEDED`, or `UNSUPPORTED` based on Tika/XML policy |

This matrix is the main confidence gate. If a proposed first code slice
cannot classify these rows without special cases leaking into indexing, it
is not the right first slice.

### Test Affordances and Gaps

Current tests make some first-slice verification easy and some harder:

- `JobQueueTest` already verifies retry attempts, failed-job summaries,
  failed-job listing, cleanup, and delete-by-path behavior. This is a good
  place to test any queue/ledger fields that live in or near `jobs.db`.
- `IndexingLoopTest` mostly tests pure `IndexingDocumentOps` helpers and
  explicitly says full loop behavior needs better fixtures. It does not
  currently provide an easy harness for "queued path -> extract -> outcome
  -> write/mark failed."
- `ContentExtractorTest`, `TimeboxedContentExtractorTest`, and
  `NastyCorpusTest` cover extractor-local behavior. They are useful
  characterization tests, but they cannot prove the ingestion boundary.
- `NastyCorpusTest` is disabled in CI, so it should not be the only
  adversarial regression point.

Implementation conclusion:

**Before changing behavior, create a small loop-level test harness or
extract an `IndexingLoop` classification helper that can be tested without
running the whole background thread.**

The first useful tests should assert:

- generic `ExtractionException` no longer silently becomes a fake full
  document;
- timeout emits `PARSER_TIMEOUT`;
- too-large input emits `BUDGET_EXCEEDED`;
- missing-at-processing-time emits `STALE_SOURCE`;
- write failure emits `WRITE_FAILED`;
- failed/skipped/deferred outcomes update queue or ledger state with a
  stable reason code.

This test work is a confidence prerequisite. Without it, the first
implementation slice risks looking correct by static inspection while
preserving the current silent-placeholder behavior.

### Policy and Config Drift Findings

The design's config-authority warning is not theoretical. Current policy
is split across multiple places:

- Head HTTP ingest uses `ExcludeGlobs`.
- Head watcher/root lifecycle paths use `ExcludeMatcher`.
- Worker `SyncDirectoryOps` has its own `WALK_SKIP_DIRS` and cloud
  placeholder skip.
- Worker `IndexingLoop` has hard-coded `SKIP_PATTERNS` and
  `SKIP_EXTENSIONS`.
- General extraction has hard-coded file-size, Office-size, timeout, and
  extracted-text budgets.
- Tika construction is high-level: `ContentExtractor` uses `new Tika()`
  plus `setMaxStringLength()`, and `StructuredContentExtractor` uses
  `new AutoDetectParser()` with PDF marked-content reflection. There is no
  shared `TikaConfig`, parser allowlist/exclusion policy, recursive parser
  policy, or Tika-native secure-handler configuration in the current
  extraction path.

Implementation conclusion:

**The first slice can add typed outcomes without solving config drift, but
it must not add another independent skip/budget policy channel.**

For v1, reason codes should reflect the current checks. Later phases should
move those checks behind resolved config/root policy. That sequencing keeps
confidence high because it avoids coupling the typed-outcome foundation to
the larger resolved-config migration.

### Revised First Implementation Slice

The confidence pass changes the recommended first implementation slice.
The first slice should be:

```text
Introduce typed ingestion outcomes at the Worker extraction/write boundary,
persist stable outcome/reason truth near the existing queue state, and add
loop-level tests proving current silent-placeholder behavior is gone.
```

Concretely, v1 should:

1. Add a small `IngestionOutcome` / `ExtractionOutcome` representation with
   `outcome_class`, `reason_code`, `retry_policy`, and diagnostic summary.
2. Refactor `IndexingLoop.extractJob()` so each branch produces an outcome
   instead of implicitly choosing mark-done, mark-failed, or fake extracted
   content.
3. Stop treating generic `ExtractionException` as a full-success placeholder
   document. If metadata-only indexing is desired, make it an explicit
   `SUCCESS_PARTIAL` or `UNSUPPORTED` policy outcome.
4. Persist the last outcome/reason either by extending `jobs` or by adding a
   companion `ingestion_outcomes` table. Choose based on implementation
   inventory, but do not leave reason codes only in logs.
5. Keep existing Head-side walkers and direct enqueue paths intact for v1;
   they remain enumerators, not authoritative admission.
6. Add tests at two layers:
   - queue/ledger tests for stable outcome fields;
   - loop-level classification tests with injected extractor behavior.

V1 should explicitly not:

- add a process sandbox;
- redesign archives;
- add public API/schema fields for search-time honesty;
- move all skip policy into resolved config;
- replace Head-side directory walks.

Those later phases become safer after the system has typed ingestion truth.

### Logical Implementation Phases

The full design should not be implemented as one large refactor. The
correct split is by authority maturity, not by file type or by entrypoint.
Each phase should leave the system more honest than before, even if later
phases never land.

Phase 0: **Characterization Harness**

Goal: make current behavior observable without changing product semantics.

- Add loop-level tests or a small testable classifier seam around
  `IndexingLoop.extractJob()` and `writeExtractedJob()`.
- Characterize the current outcomes for missing, unreadable, skipped,
  oversized, timeout, generic parser failure, and write failure cases.
- Prove the existing silent-placeholder behavior with a failing/expected
  test before changing it.
- Do not add public APIs, schema fields, sandboxing, or new root policy.

This phase exists because the most dangerous implementation mistake would
be changing extraction behavior while tests still only exercise
`ContentExtractor` directly.

Phase 1: **Typed Worker Outcomes**

Goal: make the Worker extraction/write boundary speak in stable outcomes.

- Introduce the minimal outcome vocabulary from this tempdoc in
  Worker-owned code.
- Route every current `extractJob()` branch through one outcome:
  `SUCCESS_FULL`, `SKIPPED_POLICY`, `STALE_SOURCE`, `BUDGET_EXCEEDED`,
  `PARSER_FAILED`, `PARSER_TIMEOUT`, `IO_FAILED`, `WRITE_FAILED`, or
  `WRITE_UNAVAILABLE_DRAINING`.
- Stop converting generic parser failure into a fake `"File: <name>"`
  full-success document.
- Keep Head-side directory walkers as enumerators. Do not attempt to make
  them authoritative in this phase.

This is the first behavior-changing phase and the core correctness win.

Phase 2: **Durable Last Outcome**

Goal: ensure outcomes survive logs and process restarts.

- Persist last outcome class and reason code near the existing queue state,
  preferably as a narrow extension of `jobs.db` for v1.
- Keep existing retry/backoff semantics intact for retryable failures.
- Treat skipped, stale, deferred, and parser-failed files as typed states in
  diagnostics, even when the queue row remains `DONE`, `PENDING`, or
  `FAILED`.
- Preserve the long-term option for a separate historical Quarantine Ledger;
  do not force v1 queue columns to become the final ledger design.

This phase makes the system explainable without yet adding a full product
surface.

Phase 3: **Freshness Snapshot Lite**

Goal: stop pretending path checks and post-extraction metadata reads are
authoritative.

- Capture a small pre-open snapshot at the Worker boundary: canonical path,
  size, modified time, basic attributes, and optional file key.
- Validate after open/extraction that the file still matches the snapshot
  enough to index safely.
- Classify changed, deleted, replaced, or metadata-raced files as
  `STALE_SOURCE`.
- Keep source-root capability and complete `FileEnvelope` design for a later
  phase unless a focused refactor can reuse current root data cleanly.

This phase turns TOCTOU from an implicit risk into a typed outcome.

Phase 4: **Policy and Tika Authority Consolidation**

Goal: move scattered skip/budget/parser decisions behind named policy.

- Replace hard-coded Worker skip and budget decisions gradually with a
  shared ingestion policy object sourced from resolved config when possible.
- Introduce explicit Tika policy/config construction before custom parser
  invention: parser/detector allowlists or exclusions, output limits, secure
  handlers, recursive parse policy, and XML-hardening audit points.
- Keep current defaults behaviorally conservative unless a test proves the
  old behavior was unsafe or dishonest.
- Do not add a process sandbox yet; this phase strengthens in-process
  parser control and policy naming.

This phase reduces config drift and prepares the ground for a real
Ingestion Authority.

Phase 5: **Product-Surface Honesty**

Goal: let users, APIs, and future AI/RAG contexts see extraction truth.

- Promote selected outcome/provenance fields into indexed document fields
  only after the normal SSOT/catalog and API workflows are followed.
- Surface skipped/failed/deferred status through existing failed-job or
  status endpoints before designing new UI.
- Preserve privacy by redacting or hashing sensitive diagnostic details by
  default.
- Distinguish partial/metadata-only indexing from full content indexing.

This phase should wait until outcome semantics are stable enough that public
fields will not churn immediately.

Phase 6: **Sandbox Failure Domain**

Goal: isolate parser execution from the Worker core.

- Introduce a sandbox only after typed outcomes, durability, and policy
  naming exist, so sandbox failures have somewhere precise to land.
- Follow the 406 lifecycle pattern for any long-lived sandbox pool:
  service identity as holder, single-shot sessions, drain/close, and
  retry/defer outcomes during drain.
- Validate sandbox responses as untrusted artifacts before document
  construction.
- Keep sandbox workers away from Lucene and direct JobQueue mutation.

This phase is high value, but it is not the first move because sandboxing
without typed outcomes only creates a new place to hide ambiguous failures.

Phase 7: **Archives, Virtual Roots, and Adversarial CI**

Goal: extend the boundary to container semantics and make hostile-input
regression routine.

- Treat archives as virtual source roots with member path policy, depth,
  expansion, duplicate-name, and provenance controls.
- Add full ingest-path adversarial CI scenarios for corrupt, oversized,
  recursive, XML-entity, symlink/junction, cloud-placeholder, timeout, and
  mixed-batch cases.
- Keep expensive fuzzing as scheduled/manual work, but make deterministic
  hostile fixtures part of normal CI.
- Use AI pack staging tests as the reference pattern for declared files,
  path normalization, size budgets, and symlink rejection.

This phase closes the gap between "the extractor survived bad files" and
"the product survived hostile corpora."

Parallelization guidance:

- Phase 0 and the queue-schema inventory for Phase 2 can happen in parallel.
- Phase 1 should not race with Phase 2 unless the outcome type is already
  agreed; otherwise persistence will encode unstable names.
- Phase 3 should follow Phase 1 so freshness races can report typed truth.
- Phase 4 can begin design work early, but should not replace skip/budget
  behavior before Phase 1 tests lock current semantics.
- Phases 5-7 depend on stable outcomes and should not be pulled forward just
  because they are more visible.

### Confidence After Hardening

Updated confidence:

- **Design direction:** high.
- **First implementation slice:** medium-high.
- **Big-bang implementation:** low.
- **Sandbox implementation:** medium-low until packaging/lifecycle spikes
  are done.
- **Archive-as-virtual-root implementation:** medium-low until product
  defaults are decided.
- **Ledger/status implementation:** medium-high if v1 stays close to
  `jobs.db`; medium if it starts with a separate historical ledger.

The confidence improvement comes from discovering that:

- there is a common Worker choke point after queueing;
- `IndexingLoop` can inject a timeboxed extractor for tests;
- `jobs.db` already has retry/failure/status bones;
- the most dangerous current behavior is concrete and testable:
  parser failure becomes a fake placeholder document.

The remaining confidence risks are:

- raw paths enter from many Head-side enumerators, so "no raw path crosses
  into indexing" is a later invariant, not v1;
- policy is duplicated today, so new outcomes must not freeze the duplicate
  policy shape forever;
- file freshness and source-root identity still need platform-specific
  experiments;
- public schema/API fields require the normal SSOT/API workflows and should
  not be bundled into the first slice.

### 406 Worktree Read-Through

The 406 lifecycle worktree changes the constraints around future ingestion
implementation, not the recommended first slice.

The relevant 406 result is now concrete rather than theoretical:

- `RuntimeContext` has been deleted and folded into `RuntimeSession`, making
  Lucene runtime state a single per-session composition site.
- `RunningRuntime.drainAndClose(Duration)` sets a draining flag, waits for
  queue depth, performs a final commit, and closes the old session.
- `IndexingCoordinator` and `WritePathOps` reject writes during drain so
  callers can retry on the upgraded holder reference.
- `DeferredRuntimeCompileFailTest` proves write-side access is only exposed
  on `RunningRuntime`, not deferred or read-only phases.
- `LifecycleStressTest` exercises repeated holder swaps with concurrent
  readers and writers.
- `docs/future-features/service-identity-lifecycle-pattern.md` generalizes
  the pattern: service identity is the consumer/holder; the open resource is
  a single-shot phase value; restart is a holder swap, not a method on the
  value.

Impact on this tempdoc:

- The first implementation slice should still be typed outcomes at the
  Worker extraction/write boundary. 406 does not make sandboxing the next
  safest move.
- `WRITE_FAILED` should not be a single bucket forever. Runtime-drain write
  rejection is a retryable lifecycle condition, not evidence that the file
  is hostile or quarantined.
- If the future Extraction Sandbox becomes long-lived, pooled, or
  restartable, it should follow the 406 service-identity pattern:
  single-shot sandbox sessions behind a holder, explicit drain/close, no
  mutable "manager context" bag, and tests for holder swaps under load.
- Ingestion/write integration should avoid capturing stale write-side
  collaborators across Lucene runtime swaps. Where a component can outlive a
  `RunningRuntime`, it should resolve the current runtime/coordinator through
  the owning holder or supplier at call time.
- Future sandbox lifecycle tests should copy the spirit of
  `DrainAndCloseTest` and `LifecycleStressTest`: no new work after drain,
  in-flight work either lands or receives a typed retry/defer outcome, and
  session/resource leaks are tested under repeated cycles.

This increases confidence in the lifecycle design for later phases. It also
sharpens one warning: any ingestion authority that accumulates its own
queues, workers, parser pools, or process handles must not recreate the
pre-406 mutable-lifecycle ambiguity in a new subsystem.

## Correct Design

### 1. Ingestion Boundary as a First-Class Subsystem

Introduce a conceptual subsystem: **Ingestion Authority**.

The Ingestion Authority is the only boundary allowed to transform a raw
filesystem path into an indexable document candidate. It is not just a
helper class. It is a protocol boundary that composes OS metadata, Java
filesystem semantics, Tika configuration, JustSearch root policy, and
runtime budgets.

Input:

```text
SourceRoot + discovered path + filesystem metadata + user intent
```

Output:

```text
AdmittedFile | RejectedFile | DeferredFile
```

`AdmittedFile` is a typed value carrying all normalized, canonical,
freshness, budget-relevant, and provenance data needed downstream. The
downstream indexing pipeline never re-interprets raw paths. It consumes
admitted values.

This mirrors the pattern already emerging elsewhere in the codebase:

- ORT session construction moved toward a single composition root.
- Write mutations moved through `IndexingCoordinator`.
- Observability moved toward schemas and manifests.
- AI pack import already has a manifest-and-staging boundary.

File ingestion needs the same "one authority" treatment.

### 2. SourceRoot Capability Model

A library root should be represented as a capability, not a string.

```java
record SourceRoot(
    RootId id,
    Path canonicalRoot,
    RootPolicy policy,
    RootTrustLevel trustLevel,
    RootScanMode scanMode) {}
```

Every discovered file is evaluated relative to a `SourceRoot`. A path is
not admissible merely because it exists. It is admissible because a
specific root capability authorizes it.

Required invariants:

- A candidate file's canonical path must remain inside the canonical root,
  unless the root policy explicitly allows external symlink targets.
- Symlink/junction traversal is a root policy. Java's default non-following
  tree walk behavior should be treated as a useful default, not the whole
  policy.
- Cloud placeholders are classified before extraction, not discovered
  accidentally when a read triggers network hydration.
- Path identity is stable across casing, separators, drive-letter forms,
  extended-length prefixes, and UNC-like forms on Windows.
- Source-root provenance is stored with indexed documents so later search,
  diagnostics, and cleanup can explain where a document came from.

This prevents a broad class of bugs where "path string" quietly becomes
"authority to read."

### 3. FileEnvelope: Metadata and Freshness Before Bytes

Before any parser sees bytes, the system should construct a `FileEnvelope`.

```java
record FileEnvelope(
    SourceRoot root,
    Path canonicalPath,
    FreshnessSnapshot freshness,
    FileIdentity identity,
    MimeProbe mimeProbe,
    SizeClass sizeClass,
    ArchiveProbe archiveProbe,
    LinkProbe linkProbe,
    CloudProbe cloudProbe,
    RiskClass riskClass) {}

record FreshnessSnapshot(
    BasicFileAttributes preOpenAttributes,
    Optional<Object> fileKey,
    long size,
    Instant lastModifiedTime,
    LinkProbe linkProbe,
    CloudProbe cloudProbe,
    PostOpenValidation postOpenValidation) {}
```

The `FileEnvelope` is a pre-extraction description of the file. It lets the
system decide *how* to parse, *whether* to parse, and *what budget* applies
without trusting parser output.

Important distinction:

- `Files.exists()` and `Files.isReadable()` are hints, not authority. Java
  documents that their results are immediately outdated.
- `fileKey()` is a useful identity hint, not a permanent identity. It may
  be null, and reuse semantics are filesystem-dependent.
- `MimeProbe` is an untrusted hint.
- `RiskClass` is a policy decision.
- `DocumentKind` is assigned only after successful extraction.

The freshness snapshot should detect and classify files that are deleted,
replaced, resized, modified, moved through a symlink/junction, or changed
between discovery, admission, open, extraction, and indexing.

This avoids treating Tika's interpretation, or a stale path check, as both
detection and authority.

### 4. Budget Algebra and Tika-Native Controls

Extraction needs typed budgets, not scattered constants.

```java
record IngestionBudget(
    String budgetId,
    long maxInputBytes,
    long maxExpandedBytes,
    int maxExtractedChars,
    Duration maxWallTime,
    long maxHeapBytes,
    int maxEmbeddedResources,
    int maxArchiveDepth,
    int maxParserWarnings,
    boolean allowNetworkHydration) {}
```

Budgets should be derived from `FileEnvelope`, `RootPolicy`, Tika policy,
and global runtime mode.

The core design rule:

**Every parser run has a budget, every budget is named, and every budget
exhaustion produces a typed outcome.**

Tika-native controls should be applied first:

- parser and detector allowlists/exclusions through `TikaConfig`;
- write/output limits through Tika handlers;
- compression-ratio, XML nesting, and package-entry-depth checks where
  applicable;
- embedded-resource accounting and recursive parse policy;
- XML hardening audit points for DTDs, external entities, external DTD
  loading, XInclude, and entity expansion.

Process-level sandbox limits then cover what Tika cannot guarantee:

- parser crash containment,
- hard wall-clock termination,
- heap/process memory isolation,
- stdout/stderr/log containment,
- suspicious-input process recycling,
- runaway native or dependency behavior.

Examples:

- small text file: generous extracted-char budget, tiny heap risk.
- Office document: lower input cap, stricter heap budget.
- archive: depth, embedded-resource, and expansion-ratio budget.
- PDF: page-count, extracted-char, parser-warning, and timeout budget.
- cloud placeholder: deferred unless explicit hydration is allowed.
- unknown binary: metadata-only or rejected, never "try everything and see."

Budgets are not just defensive coding. They are user-facing product logic:
the app should be able to explain why a file was skipped or partially
indexed.

### 5. Extraction Sandbox as a Separate Failure Domain

The correct architecture treats parser execution as a failure domain
separate from the Worker core.

Conceptually:

```text
Worker indexing loop
  -> Ingestion Authority
  -> Extraction Sandbox
  -> ExtractionArtifact
  -> Document Builder
  -> IndexingCoordinator
```

The Extraction Sandbox may be an OS process, a constrained JVM, a pool of
disposable workers, or some other isolation mechanism. The exact mechanism
is implementation detail. The design property is what matters:

**Parser failure must not be able to corrupt or indefinitely occupy the
Worker's core indexing state.**

Required sandbox contract:

- bounded wall-clock time,
- bounded output size,
- bounded number of embedded resources,
- explicit result envelope,
- crash classified as `EXTRACTOR_CRASH`,
- timeout classified as `EXTRACTION_TIMEOUT`,
- budget exhaustion classified by budget,
- stdout/stderr/logs captured and rate-limited,
- sandbox process recyclable after suspicious inputs,
- no direct Lucene access,
- no direct JobQueue mutation,
- no hidden state that makes the next file depend on the previous file's
  parser leftovers.

This is the same principle as the three-process architecture, applied one
level lower: isolate the dangerous work from the orchestrator.

The 406 lifecycle worktree adds a structural requirement if the sandbox is
long-lived or restartable: sandbox identity and sandbox sessions should be
separate concepts. A running sandbox session should be a single-shot phase
value with explicit drain/close semantics; service owners should swap the
holder to a fresh session rather than restarting the old value in place.
New extraction work during drain should receive a typed retry/defer outcome,
not disappear into generic parser failure or quarantine.

### 6. Parser Output Is Still Untrusted

The sandbox response is not automatically trusted just because parsing
completed. It is another boundary crossing.

Sandbox output needs:

- schema validation,
- maximum serialized result size,
- maximum extracted text size,
- metadata key/value limits,
- encoding validation,
- provenance fields,
- parser name and version,
- warning/error count limits,
- digest or content-addressed identity where useful.

Only a validated `ExtractionArtifact` should reach the Document Builder.
This prevents a parser from moving the risk from "hostile input bytes" to
"hostile output metadata or unbounded extracted text."

### 7. Typed Extraction Outcomes

Extraction should not return "string or empty string." It should return a
typed outcome.

```java
sealed interface ExtractionOutcome permits
    ExtractedDocument,
    PartiallyExtractedDocument,
    RejectedBeforeParse,
    ParserFailed,
    BudgetExceeded,
    DeferredByPolicy,
    UnsupportedFile,
    StaleFile,
    SandboxCrashed {}
```

Each outcome carries:

- `file_id`,
- `source_root_id`,
- `path`,
- `risk_class`,
- `budget_id`,
- `reason_code`,
- `user_message_key`,
- `retry_policy`,
- `diagnostic_summary`,
- `parser_name/parser_version` when applicable.

`""` is not a failure value. Empty content is valid only when paired with
an outcome explaining why.

This prevents the silent class where "bad extraction" becomes "empty
document" and the rest of the pipeline proceeds as if the file genuinely
had no content.

### 8. Quarantine Ledger

Rejected, failed, deferred, stale, and partially extracted files should be
durable state, not log lines.

The design object is a **Quarantine Ledger**:

```text
file_id
path_or_redacted_path
source_root_id
first_seen_at
last_seen_at
last_attempt_at
outcome_class
reason_code
budget_id
attempt_count
retry_after
user_visible
diagnostic_hash
privacy_level
```

The ledger is not just for security. It solves product problems:

- "Why is this file missing from search?"
- "Which files were skipped because they were too large?"
- "Which folder has many parser failures?"
- "Did this update make extraction worse?"
- "Can I retry failed files after changing settings?"

The ledger must also have a privacy model. Filenames, directory names,
metadata, parser warnings, and excerpts can reveal sensitive local data.
Exported diagnostics should redact or hash paths/content unless the user
explicitly requests full local details.

The index should not be the only memory of ingestion. Failed inputs must
have their own durable history.

### 9. Archives as Virtual Source Roots

Archives should not be treated as just another flat file type. They are
containers that can create their own path, expansion, identity, and
provenance problems.

Correct archive handling needs policy for:

- whether archive parsing is enabled for a root,
- maximum archive depth,
- maximum expanded bytes,
- maximum member count,
- member path normalization,
- duplicate member names,
- path traversal inside archives,
- nested archive behavior,
- member identity,
- archive member provenance in search results,
- whether archive members become first-class documents, metadata-only
  children, or skipped children.

The right mental model is: an archive is a virtual source root with a
stricter budget, not merely a MIME type.

### 10. Document Builder Consumes Only Trusted Artifacts

The Document Builder should not invoke Tika, inspect raw files, or infer
admission policy. It consumes `ExtractionArtifact`.

```java
record ExtractionArtifact(
    FileEnvelope envelope,
    ExtractionOutcome outcome,
    Optional<StructuredDocument> document,
    ArtifactDigest digest,
    ExtractionProvenance provenance) {}
```

Indexing then becomes a pure-ish transformation:

```text
ExtractionArtifact -> IndexDocument | QuarantineRecord
```

This makes the boundary testable. If a future parser is added, it produces
the same artifact shape. If a future VLM extractor is added, it does not
special-case the indexing loop. If archive handling changes, the output
contract remains stable.

The builder/write boundary should also respect Lucene runtime lifecycle. If
the runtime can be swapped underneath a long-lived ingestion component, that
component should not cache stale `IndexingCoordinator` or write-side ops
references. It should resolve the current running runtime through the owning
holder/supplier and classify drain-time rejection as retryable
write-unavailable state.

### 11. Search-Time Honesty

Search results should be honest about extraction provenance.

Documents should carry fields such as:

- `extraction_status`: full | partial | metadata_only | failed | skipped
- `extraction_method`: tika | sandbox_tika | vdu | archive_metadata | none
- `extraction_reason_code`
- `content_truncated`: boolean
- `embedded_resource_count`
- `parser_warnings_count`
- `source_trust_level`
- `source_root_id`

The UI and API should be able to distinguish:

- "No results because nothing matched."
- "No results because the file was never indexable."
- "Result matched metadata only."
- "Result content may be incomplete because extraction was truncated."
- "Result came from lower-trust or generated/parser-derived content."

This prevents bad files from becoming invisible product mysteries.

### 12. Semantic Poisoning and AI/RAG Safety

Extracted content and metadata can later influence summaries, agent
behavior, query expansion, RAG context, or user trust. Ingestion resilience
therefore must preserve provenance and trust labels, not only parser health.

The design should assume:

- document content may include prompt-injection text,
- metadata fields may be adversarial or misleading,
- archive paths may be crafted to look authoritative,
- extracted text may come from OCR/VDU with a different error and trust
  profile than Tika text,
- search and agent surfaces need enough provenance to avoid presenting
  untrusted extracted content as system instruction or verified fact.

This does not mean ingestion should censor user files. It means extraction
status, source root, parser method, trust level, and artifact provenance
must survive far enough downstream for AI features to make informed
decisions.

### 13. Config Authority

Root policies and ingestion budgets should not become another split
authority.

The correct design should ultimately route policy through the same
resolved-config authority as the rest of the runtime:

- global defaults,
- per-root overrides,
- runtime mode,
- package/profile constraints,
- user intent,
- observability labels.

The Ingestion Authority may own admission decisions, but it should not
invent an independent configuration channel that can drift from Head,
Worker, or packaged-app state.

### 14. Adversarial Corpus Generator

The regression suite should generate hostile shapes programmatically where
possible. It should not depend on a hand-curated binary corpus that CI
cannot load.

Test corpus classes:

- zero-byte files,
- invalid UTF-8,
- very long single line,
- very long single token,
- high compression-ratio ZIP,
- nested archive,
- malformed ZIP central directory,
- malformed DOCX-like ZIP,
- XML entity expansion attempt,
- huge metadata fields,
- many tiny files,
- unreadable file where platform supports permissions,
- symlink escaping root,
- symlink loop,
- junction/reparse-point behavior on Windows,
- cloud-placeholder simulation where possible,
- duplicate path identity under Windows case normalization,
- long path / extended-length path,
- file mutating during extraction.

The suite should test layers, not only units:

- envelope classification,
- sandbox outcome,
- full ingest of a mixed folder,
- Worker health after poison input,
- good-file progress despite bad-file failures,
- quarantine ledger rows,
- user-visible status/API surface,
- search honesty fields.

The correct test question is not "does Tika parse this?" It is:

**Does JustSearch preserve system health, classify the failure, and keep
processing the rest of the corpus?**

### 15. Security and Reliability Share One Boundary

This work should not split "security tests" and "robustness tests" into
separate worlds. For a local-first desktop search app, the same input can
be:

- a malicious payload,
- a corrupted download,
- a weird enterprise document,
- a cloud placeholder,
- a generated build artifact,
- an archive from a user backup.

The system response should be the same: classify, budget, isolate, record,
continue.

The boundary is therefore named around **ingestion resilience**, not only
security.

## Structural Invariants

The correct architecture should enforce these invariants:

1. **No raw path crosses into indexing.** Downstream indexing consumes
   `AdmittedFile` / `FileEnvelope`, not arbitrary strings.
2. **No stale path check becomes authority.** Existence/readability checks
   are followed by open-time and post-open validation.
3. **No parser can mutate index state.** Parser code returns artifacts;
   only the indexing coordinator mutates Lucene.
4. **No parser outcome is represented only as empty content.** Every
   non-success has a typed reason code.
5. **No failed file disappears.** Failed, deferred, rejected, stale, and
   partial files enter the quarantine ledger.
6. **No unbounded expansion.** Archive depth, embedded resources, extracted
   chars, parser wall time, and output bytes are budgeted.
7. **No source-root escape by default.** Symlink/junction traversal is a
   policy decision and is recorded.
8. **No cloud hydration by accident.** Placeholder files are deferred
   unless a root policy allows read-triggered hydration.
9. **No single poison file blocks the corpus.** Bad files are isolated and
   the batch continues.
10. **No crash without classification.** Sandbox crash becomes a typed
    outcome and health signal.
11. **No hidden extraction truncation.** Partial content is marked as
    partial in schema and API output.
12. **No adversarial suite outside CI forever.** A small generated corpus
    must run automatically; expensive/fuzzy variants can be manual/stress.
13. **No parser-specific special case leaks into search.** Search consumes
    extraction provenance fields, not parser internals.
14. **No diagnostic export leaks private file details by default.**
    Quarantine and parser diagnostics have a redaction policy.
15. **No ingestion policy side channel.** Root policies and budgets flow
    from resolved configuration, not scattered constants.

## Desired End State

The desired end state is not "NastyCorpusTest has more cases." The desired
end state is this:

```text
User selects folder
  -> SourceRoot capability created from resolved config
  -> Discovery emits candidate paths
  -> Ingestion Authority builds FileEnvelope + FreshnessSnapshot
  -> Admission policy returns admitted/rejected/deferred/stale
  -> Tika-native controls and sandbox policy are selected
  -> Extraction Sandbox produces bounded sandbox response
  -> Sandbox response validates into ExtractionArtifact
  -> Quarantine Ledger records non-full-success outcomes
  -> Document Builder converts trusted artifact to schema fields
  -> IndexingCoordinator mutates Lucene
  -> Status/API/UI explain coverage and skipped files
  -> Adversarial CI verifies all invariants
```

Every stage has a typed input and output. Every failure becomes data.
Every untrusted boundary has a budget. Every parser is replaceable behind
the artifact contract.

## Acceptance Scenarios for Future Implementation

These are design acceptance scenarios, not an implementation checklist:

- Hostile files never crash the Worker core indexing loop.
- Oversized, corrupt, recursive, deeply nested, cloud-placeholder,
  symlink/junction, XML entity, stale/replaced-file, and parser-timeout
  cases produce typed outcomes.
- A bad file cannot block good files in the same batch.
- Partial extraction is indexed only with explicit status/provenance
  fields.
- Full ingest-path adversarial tests run in CI.
- Expensive parser fuzzing, large hostile corpora, and platform-specific
  stress cases can remain scheduled/manual suites.
- AI pack staging tests become a reference pattern for future
  ingestion-firewall tests.
- Diagnostic export redacts private local paths/content unless the user
  explicitly opts into full detail.
- AI/RAG surfaces can distinguish trusted system text from user-file
  content and parser-derived metadata.

## Design Consequences

### Consequence 1: Tika becomes a configured plugin behind a contract

Tika is not "the extractor"; it is one extractor implementation behind an
`ExtractionSandbox` contract and a Tika policy layer. The contract should
be strong enough that future extractors (VLM PDF extraction, email-specific
parser, archive metadata parser, code parser) fit without changing
indexing.

### Consequence 2: Quarantine is a user feature

The user should eventually see a File Health / Ingestion Health surface:

- files indexed,
- files partially indexed,
- files skipped by policy,
- files failed by parser,
- files deferred because cloud-only,
- files skipped because stale/replaced during indexing,
- top failure reasons,
- retry action.

Without this, production users will experience "search missed my file" as
quality failure even when the system behaved defensively.

### Consequence 3: Extraction provenance becomes search quality data

Search evaluation should be able to stratify by extraction status:

- full text,
- partial text,
- metadata-only,
- failed extraction,
- VDU-recovered,
- archive member,
- long document truncated,
- lower-trust/generated/parser-derived content.

Otherwise quality regressions caused by extraction changes get misread as
ranking regressions.

### Consequence 4: Root policies become product semantics

"Index this folder" needs explicit semantics:

- include hidden files?
- include generated folders?
- follow symlinks?
- hydrate cloud files?
- parse archives?
- parse embedded documents?
- index binary metadata?
- allow lower-trust/generated content into AI context?

These choices belong in `RootPolicy`, not as scattered checks in the walk
and extractor.

### Consequence 5: Observability gets a new authority

Ingestion resilience should emit counters and spans, but those are
secondary. The primary authority is the quarantine ledger and extraction
artifact manifest. Telemetry observes the boundary; it does not define it.

## Relationship to Existing Work

- **Tempdoc 400/404/405 observability:** complements those docs. This
  tempdoc creates a typed domain whose outcomes observability can consume.
- **Tempdoc 402 write coordinator:** downstream mutation should still go
  through the coordinator. This tempdoc is upstream of write serialization.
- **Tempdoc 406 lifecycle refactor:** now provides a concrete lifecycle
  pattern for this design to reuse. The Lucene side has phase-typed runtime
  values, a single `RuntimeSession` state owner, `drainAndClose`, write
  rejection during drain, compile-fail protection for read-only phases, and
  stress tests for holder swaps. This tempdoc still does not depend on 406
  for the first typed-outcome slice, but any future sandbox/process-pool
  implementation should copy 406's service-identity-with-single-shot-session
  discipline.
- **Tempdoc 374/409 release readiness:** public release increases the
  importance of hostile-input resilience. Public users will index arbitrary
  Downloads folders and old archives.
- **AI pack import:** provides a useful local precedent for manifest,
  size-budget, path-normalization, and staging discipline. General ingestion
  should not copy AI-pack details directly; it should copy the principle:
  untrusted bytes cross a declared boundary.

## Non-Goals

- This tempdoc does not pick the sandbox implementation mechanism.
- This tempdoc does not require fuzzing every parser before any release.
- This tempdoc does not claim Tika is unsafe or wrong.
- This tempdoc does not propose weakening indexing throughput work.
- This tempdoc does not replace Production-Reality Verification.
- This tempdoc does not specify UI design.
- This tempdoc does not mandate that every hostile-input control be built
  before the next production milestone.

## Open Design Questions

These are genuine design questions, not implementation blockers:

1. Should the Quarantine Ledger live in the existing SQLite job database,
   a new SQLite database, or a content-addressed artifact store?
2. Should `FileEnvelope` identity be path-based, file-key-based, content-
   hash-based, or a hybrid?
3. Should archive members become first-class documents, metadata-only
   children, or be skipped unless explicitly enabled?
4. How should VDU / vision extraction participate in the same artifact
   contract without pretending it has the same trust profile as Tika?
5. What is the correct default policy for symlinks and junctions on Windows?
6. What user-facing promise should JustSearch make for cloud-only files?
7. Which extraction outcomes should affect readiness/health vs. ordinary
   file-health warnings?
8. How should eval datasets encode extraction provenance so search-quality
   baselines remain comparable across parser changes?
9. Which Tika parsers/detectors should be enabled by default, excluded by
   default, or controlled by root policy?
10. Which fields are safe to include in diagnostics exports without
    explicit user consent?

## Confidence Evaluation

Confidence that the root diagnosis is correct: **high**.

Evidence:

- The current test gap is already documented in observations.
- Tika's official security model explicitly says untrusted parsing requires
  the application to handle crashes and other consequences.
- `NastyCorpusTest` exists but is disabled in CI and only tests direct
  extraction.
- AI pack import has a much clearer untrusted-byte boundary than general
  library ingestion.
- Recent bug patterns across tempdocs 397/400/402/406 repeatedly came from
  split authority and implicit contracts. Ingestion currently has both.

Confidence that the exact architecture above is complete: **medium**.

The major pillars are likely right: source-root capability, envelope,
freshness snapshot, Tika-native controls, budget, sandbox, typed outcome,
quarantine ledger, trusted artifact, adversarial corpus. Details around
archive semantics, cloud hydration, file identity, diagnostic privacy, and
AI/RAG trust labels need deeper platform-specific design.

Confidence that this is the most valuable missing workstream:
**medium-high**.

Production packaging, release legal fixes, observability, lifecycle, and
pipeline performance all have active/recent tempdocs. Hostile-input
ingestion is a fundamental production risk with only partial coverage and
no current owning design doc.

## First Follow-Up When This Becomes Implementation Work

Implementation work should start with a design inventory, not code:

1. Enumerate every current path from user-selected root/path to parser.
2. Enumerate every parser/extractor path and its current budgets.
3. Enumerate every current Tika parser/detector configuration point.
4. Enumerate every current user/API surface for skipped or failed files.
5. Build a matrix mapping hostile input classes to current behavior.
6. Decide the minimum viable `ExtractionOutcome` taxonomy.
7. Decide the minimum viable privacy model for quarantine diagnostics.
8. Only then pick the first code slice.

The likely first code slice is not a sandbox. It is probably the typed
outcome + quarantine ledger skeleton, because that gives every later
guardrail a place to report truth.

