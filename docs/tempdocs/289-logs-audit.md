---
title: "Tempdoc 289 - Logs Audit"
---

# Tempdoc 289 - Logs Audit

**Status:** Open
**Created:** 2026-03-13
**Updated:** 2026-03-14
**Goal:** Audit the current state of logging across all JustSearch processes, identify root causes and anti-patterns, and fix them with both remediation and prevention.

## Context

JustSearch runs three processes (Head, Worker/Body, Brain) with independent logging configurations. This audit examines the logging stack from framework setup through operational concerns, identifies root causes behind surface-level findings, and produces a prioritized action list.

---

## 1. Logging Stack Summary

| Layer | Technology | Version |
|-------|-----------|---------|
| API | SLF4J | 2.0.17 |
| Implementation | Logback Classic | 1.5.32 |
| Structured output | Logstash Logback Encoder | 7.4 |
| JUL bridge | `jul-to-slf4j` | 2.0.17 (Head only) |
| Log4j bridge | `log4j-to-slf4j` | 2.24.3 (Worker only) |
| Test silencing | `slf4j-nop` | via `test-support` |
| Version forcing | Root `build.gradle.kts` | All SLF4J → 2.0.17, all Log4j → 2.24.3 |

Logger declaration is consistent everywhere: `private static final Logger log = LoggerFactory.getLogger(ClassName.class)`. No Lombok `@Slf4j`, no direct JUL/Log4j usage.

---

## 2. Configuration Files

| Config | Process | Path |
|--------|---------|------|
| Head logback.xml | Head (app-launcher) | `modules/app-launcher/src/main/resources/logback.xml` |
| Worker logback.xml | Worker (indexer-worker) | `modules/indexer-worker/src/main/resources/logback.xml` |
| Benchmarks logback.xml | Benchmark harness | `modules/benchmarks/src/main/resources/logback.xml` |
| Brain | N/A | No config — runs in-process with Head, inherits Head's config |

---

## 3. Output Destinations and Formats

> **Note:** This table was updated 2026-03-14 to reflect the post-A1 state. The original pre-A1 Worker config (plain text, uncompressed, queue 512, no neverBlock) is documented in F2.

| Aspect | Head (app-launcher) | Worker (indexer-worker) |
|--------|--------------------|-----------------------|
| **Format** | **Logstash JSON** (structured) | **Logstash JSON** (structured) — updated by A1 |
| **Console** | Automation logger only (`io.justsearch.ui.automation`) | **Disabled** (pipe-buffer deadlock prevention) |
| **File** | `<dataDir>/logs/app.log` | `<dataDir>/logs/worker.log` |
| **Rolling** | `SizeAndTimeBasedRollingPolicy` — 10 MB/file, daily, gzip | `SizeAndTimeBasedRollingPolicy` — 10 MB/file, daily, gzip — updated by A1 |
| **Retention** | 7 days, 256 MB total cap | 7 days, 256 MB total cap — updated by A1 |
| **Async** | Queue 2048, `neverBlock=true`, `discardingThreshold=0` | Queue 2048, `neverBlock=true`, `discardingThreshold=0` — updated by A1 |
| **Hot reload** | No | Yes (30s scan) |
| **MDC fields** | `trace_id`, `span_id`, `pipeline_name`, `pipeline_hash`, `budget_profile`, `request_id`, `stage_id` | Same 7 keys — `request_id` + `stage_id` added by A11 |

---

## 4. Root Cause Analysis

The findings stem from 6 root causes:

### RC1: No logging conventions or automated enforcement

There is no documented logging style guide, and no PMD/ArchUnit rules enforce patterns. The `LoggingRedactionGoldenTest` validates the *output schema* of log lines, but nothing validates *input patterns* — how developers write log statements. The `e.getMessage()` pattern was likely correct somewhere (validation errors, expected failures) and then copy-pasted across ~210 production sites without considering whether each site should preserve the stack trace.

**Produces:** F1, F7, F8, F9, F12, F13

### RC2: Worker logging never received a production hardening pass

The Head process got the full observability treatment — LogstashEncoder JSON, MDC field whitelisting, gzip compression, clean INFO-only root level, `neverBlock=true`. The Worker process still has its *development-time* config: plain text for readability, DEBUG on the indexing loop, no compression, smaller async queue, and no `neverBlock` (meaning a slow file write can stall indexing threads). This isn't a format preference — it's an incomplete production transition.

**Produces:** F2, F3, F10

### RC3: Diagnostic file writers have no shared retention infrastructure

Logback manages its own rotation and cleanup. The telemetry module (`NdjsonMetricExporter`) built its own retention (10 MB rotation, 7-day pruning, disk pressure guard), but that solution wasn't generalized. All other diagnostic file writers — `CrashReporter`, `SlowRequestDumper`, `DiagnosticsController`, `NdjsonSpanExporter`, `AgentRunStore`, `FileOperationLog` — have no cleanup at all.

**Produces:** F4

### RC4: Logging infrastructure duplicated across modules

`MdcScope` in `ai-worker` duplicates `MdcContext` from `app-observability`. `RequestIdClientInterceptor` is byte-for-byte identical in `app-ai` and `app-indexing`. `TraceClientInterceptor` is also duplicated between the same two modules. The `SensitiveQuery`/`redact()` pattern is independently implemented in `KnowledgeSearchController` and `SearchOrchestrator` (and a third test-only copy in `LoggingRedactionGoldenTest`).

**Produces:** F5, F11

### RC5: Test logging was never configured

No `logback-test.xml` exists anywhere. Tests with `logback-classic` on the classpath get Logback's default (console at DEBUG). The `test-support` module uses `slf4j-nop` (total silence), but modules that need Logback at test runtime have no middle ground.

**Produces:** F6

### RC6: MDC/trace propagation incomplete across process boundary

The Head→AI Worker path has full trace + request-ID propagation (client interceptors + server interceptors + MdcContext). The Head→Worker path has interceptors on both sides for OTel span propagation, but the Worker's gRPC service methods never call `MdcContext.request()` to write `trace_id`/`request_id` into the logging MDC. Additionally, `RequestIdClientInterceptor` is not wired on the Head→Worker client channel, so no request ID is sent. This means Worker **OTel traces** are correctly correlated, but Worker **logs** have no trace correlation with Head requests.

**Produces:** F14, F15, F16

### Root Cause → Finding Map

| Root Cause | Findings | Fix leverage | Status |
|-----------|----------|--------------|--------|
| **RC1**: No logging conventions/enforcement | F1, F7, F8, F9, F12, F13 | High — fix pass + prevention rule | Fix pass partially done (A2, A3, A8). **Prevention never implemented** — no PMD/ArchUnit rules added. Anti-patterns can recur. |
| **RC2**: Worker config never production-hardened | F2, F3, F10 | High — single config file | **Resolved** by A1. |
| **RC3**: No shared diagnostic file retention | F4, F19 | Medium — small utility | **Partially addressed.** A5 added inline retention to 3 writers. Root cause (no shared utility) remains — 3 writers still unbounded. |
| **RC4**: Logging infra duplication | F5, F11, F13 | Medium — consolidation to `ipc-common` | **Partially addressed.** A4 moved MdcContext + interceptors. `SensitiveQuery`/`redact()` still duplicated (step 5 skipped). |
| **RC5**: Test logging unconfigured | F6 | Low — single file | **Resolved** by A7. |
| **RC6**: MDC/trace propagation incomplete | F14, F15, F16 | Medium — MDC population + config | New. Worker OTel traces propagate correctly, but Worker logs lack `trace_id`/`request_id` because `MdcContext.request()` is never called in Worker service methods. |

---

## 5. Findings (with investigation results)

### F1. `e.getMessage()` instead of passing exception to SLF4J — HIGH

**210 production sites across ~70 files.** Investigation categorized a 35-site sample:

- **~35–40% accidental** (~75–85 sites) — stack trace lost on consequential paths
- **~60–65% intentional** (~125–135 sites) — best-effort/validation/debug-level where message-only is appropriate

**Top modules:** indexer-worker (95), adapters-lucene (27), ui (25), app-services (22), telemetry (13).

**Highest-severity cluster:** Lucene commit failures in `IndexingLoop.java` (lines 325, 404, 511, 569) — `RuntimeException` caught with `log.error("Failed to commit: {}", e.getMessage())`. These are data-integrity-critical paths where the stack trace distinguishes OOM from CorruptIndexException from lock failure. ONNX/ORT exceptions in `EmbeddingService` are similar — `BackendException` wraps native errors whose cause chains are truncated by `getMessage()`.

**Intentional pattern markers** (safe to leave as-is): log message contains "best-effort", "non-fatal", "will fall back", "ignored"; log level is `debug`; exception type is `IllegalArgumentException`/`InvalidPathException`.

**Fix approach:** Fix the ~75–85 accidental sites. Leave intentional sites but add a comment `// message-only: <reason>` for clarity.

### F2. Worker uses plain-text log format, Head uses JSON — MEDIUM

**Gap analysis (Worker → Head parity):**

| Item | Head | Worker | Change needed |
|------|------|--------|---------------|
| Encoder | `LogstashEncoder` (JSON) | Plain-text pattern | Add `LogstashEncoder` + gradle dep |
| MDC keys in output | 5 keys whitelisted | None | Add same 5 MDC keys |
| `customFields` | `"service":"app-launcher"` | None | Add `"service":"indexer-worker"` |
| Rolling policy | `SizeAndTimeBasedRollingPolicy` | `TimeBasedRollingPolicy` | Upgrade |
| Per-file size cap | 10 MB | None | Add `maxFileSize` |
| Total size cap | 256 MB | 100 MB | Increase to 256 MB |
| Compression | `.log.gz` | `.log` | Add `.gz` suffix |
| `neverBlock` | `true` | absent (defaults `false`) | Add — prevents indexing thread stalls |
| Async queue | 2048 | 512 | Increase to 2048 |

**Worker-only features to preserve:** `scan="true" scanPeriod="30 seconds"` (hot reload — useful for runtime debug), noise-suppression loggers (grpc/netty/lucene/sqlite at WARN), fine-grained app logger hierarchy. Consider adding `scan="true"` to Head too.

### F3. Worker indexing loop runs at DEBUG by default — MEDIUM

`io.justsearch.indexerworker.loop` and `io.justsearch.indexerworker.coordination` at `DEBUG`. `IndexingLoop.java` alone has ~65 log statements. Change to `INFO` in production config; users can re-enable DEBUG via hot-reload or the `/api/debug/logging` endpoint.

### F4. No cleanup for diagnostic files — MEDIUM

**Full inventory of diagnostic file writers:**

| Writer | Path pattern | Rotation | Retention | Bounded? |
|--------|-------------|----------|-----------|----------|
| `NdjsonMetricExporter` | `telemetry/metrics.ndjson` | 10 MB size-based | 7-day age-based | **Yes** |
| `RrdMetricStore` | `telemetry/metrics.rrd` | N/A (RRD) | N/A (fixed size) | **Yes** |
| `GplTrainingTripleStore` | `gpl-training-triples.ndjson` | Cleared per job | Per-run reset | **Effectively** |
| `NdjsonSpanExporter` | `telemetry/traces.ndjson` | **None** | **None** | **No** |
| `CrashReporter` | `crashes/crash-*.json` | **None** | **None** | **No** |
| `SlowRequestDumper` | `slowapi/slow-*.json` | **None** | **None** | **No** |
| `DiagnosticsController` | `diagnostics/*.zip` | **None** | **None** | **No** |
| `AgentRunStore` | `agent-runs/<sessionId>/` | **None** | **None** | **No** |
| `FileOperationLog` | `file-operations/<batchId>.json` | **None** | **None** | **No** |

**Highest risk:** `NdjsonSpanExporter` (traces.ndjson) — active on every process startup, append-only, no cap. Its sibling `NdjsonMetricExporter` has full retention; traces does not.

**`SlowRequestDumper`** output is not even collected by `DiagnosticsController` — completely invisible to tooling.

**No shared retention utility exists.** `NdjsonMetricExporter`'s retention implementation (size rotation via `Files.move`, age pruning via `Files.list` + `lastModifiedTime`, disk pressure guard) is self-contained and should be extracted.

### F5. Duplicate logging infrastructure — MEDIUM (upgraded from LOW)

Investigation found more duplication than initially scoped:

| Duplicate | Module A | Module B | Identical? |
|-----------|----------|----------|------------|
| `MdcScope` / `MdcContext` | `ai-worker` | `app-observability` | MdcContext is superset (has `pipeline()`, `stage()`) |
| `RequestIdClientInterceptor` | `app-ai` | `app-indexing` | Byte-for-byte identical logic |
| `TraceClientInterceptor` | `app-ai` | `app-indexing` | Byte-for-byte identical logic |
| `SensitiveQuery`/`redact()` | `KnowledgeSearchController` | `SearchOrchestrator` | Same pattern, independent implementations |

**`MdcContext` cannot be added to `ai-worker` via `app-observability`** — too many transitive deps (ai-bridge, pipeline-schema, search, infra-core). **Consolidation target: `ipc-common`**, which `ai-worker` already depends on and has minimal transitive deps.

**Consolidation plan:**
1. Move `MdcContext` to `ipc-common` (fix `isEmpty()` → `isBlank()` bug during move)
2. Move `RequestIdClientInterceptor` to `ipc-common` (already has gRPC infra)
3. Move `TraceClientInterceptor` to `ipc-common`
4. Delete duplicates from `ai-worker`, `app-ai`, `app-indexing`
5. Extract shared `SensitiveQuery`/`redact()` utility (for F13)
6. Update 3 import sites: `AiServiceImpl`, `GrpcAiTranslatorService`, `RemoteKnowledgeClient`

### F6. No `logback-test.xml` in any module — LOW

Tests with `logback-classic` on the classpath get Logback's default config (console at DEBUG), producing noisy test output. A shared `logback-test.xml` in `test-support` with root at WARN and `io.justsearch` at INFO would provide a sensible middle ground.

### F7. String concatenation in 3 log statements — LOW

`AppFacadeBootstrap.java:709`, `OperationalMetrics.java:295`, `IntentAccuracyTest.java:152`. Mechanical fix.

### F8. User query text logged without redaction — HIGH (upgraded from LOW)

Investigation revealed the problem is worse than initially scoped. The `SensitiveQuery`/`redact()` pattern is applied in only 2 of ~10 locations that log query text:

**WARN level (unconditional in production — highest priority):**
- `ChunkSearchOps.java:114,168,337` — raw `queryText` on Lucene parse failure
- `LuceneIndexRuntime.java:1261` — raw `queryText` on parse failure
- `TextQueryOps.java:391` — raw `queryText` on parse failure
- `SearchOrchestrator.java:300` — raw `queryString` on parse failure

These fire whenever a user query contains characters that confuse Lucene's parser (unbalanced parens, colons, question marks). The full query text is written to `worker.log` at WARN level.

**DEBUG level (only when debug enabled):**
- `RemoteDocumentService.java:229` — RAG `question`
- `GrpcSearchService.java:460` — RAG `question`
- `RagContextOps.java:312,318,325` — RAG `question` (3 branches)
- `HybridSearchOps.java:262` — `queryText` (trivial query check)
- `AppFacadeBootstrap.java:653` — noop translator query text + JSON

### F9. File paths in logs expose Windows usernames — MEDIUM (upgraded from LOW)

File paths are pervasively logged at WARN/ERROR across the Worker and indexer modules. On Windows, these contain `C:\Users\<username>\...`.

**Highest-frequency sites:**
- `IndexingLoop.java` — 8 WARN/ERROR sites logging full `filePath` on processing failures
- `SqliteJobQueue.java` — 3 WARN/ERROR sites on job failure/completion
- `SyncDirectoryOps.java`, `SyncOps.java`, `RootLifecycleOps.java` — root path on sync errors
- `WatcherEventOps.java` — file path on watcher errors
- `KnowledgeServer.java:724-741` — startup banner logs full data directory path at INFO (every startup)
- `GrpcIngestService.java:443,456` — attacker-controlled path strings logged at WARN

Good pattern already used in some spots: `filePath.getFileName()` (filename only, no parent). This could be standardized.

**Credentials are clean:** Session token handling logs only null-check and length, never the value. No API keys, passwords, or user-identifying fields appear in logs.

### F10. Worker rolled logs are uncompressed — LOW

Part of F2/RC2 fix. Add `.gz` to Worker's `fileNamePattern`.

### F11. `Markers.append` pattern tested but not used in production — INFO

The `LoggingRedactionGoldenTest` validates structured marker usage against the SSOT schema. No production code uses `Markers.append()`. The infrastructure is ready but unadopted.

### F12. Swallowed exceptions — MEDIUM (triaged)

**430 catch blocks** log at warn/error without rethrowing. After removing ~100 false positives (HTTP 500 handlers that surface errors via `ctx.status(500)` or `responseObserver.onError`), true swallows are ~200–250.

**Estimated breakdown:**
- **~55–60% intentional** — cleanup, best-effort, explicit fallback (telemetry module is the gold standard: every swallow has `// best-effort` comment + `healthState.record*Failure()` metric)
- **~35–40% suspicious** — real silent failures
- **~1–2% clearly wrong**

**Critical suspicious patterns identified:**

| Pattern | Location | Impact |
|---------|----------|--------|
| **Commit failure skips `drainPendingMarkDone()`** | `IndexingLoop.java:325,404,511,569` | Jobs stay in pending state permanently — job accounting diverges from index state |
| **Permanent degradation from transient init failure** | `EmbeddingService.java:252` (sets `available=false`), `AiWorkerServer.java:64` (installs stub) | Vector search or summarization permanently disabled for the session, no retry |
| **Double-fallback to empty RAG context** | `RemoteDocumentService.java:273→343` | Both primary and fallback paths swallow — LLM answers with empty context (hallucination) |
| **gRPC fire-and-forget with void return** | `MigrationOps.java` (6 methods) | Caller cannot distinguish "RPC never sent" from "Worker rejected" |
| **gRPC contract violation** | `GrpcIngestService.java:413` | Replies with `onNext(errorResponse)` instead of `onError` — client sees success status |
| **No disk-full circuit breaker** | `GplJobCoordinator.java:440-471` | Produces O(N) error log lines if disk is full |
| **Silent root de-indexing** | `RootLifecycleOps.java:153` | Walk failure marks root `markNeverIndexed` — user's content silently stops being indexed |

### F13. Query text redaction inconsistently applied — HIGH (new finding)

The `SensitiveQuery`/`redact()` pattern exists in `KnowledgeSearchController` and `SearchOrchestrator` but is not applied in `adapters-lucene` (ChunkSearchOps, LuceneIndexRuntime, TextQueryOps, HybridSearchOps) or the RAG pipeline (RagContextOps, RemoteDocumentService, GrpcSearchService). The pattern is also duplicated rather than shared.

### F14. MDC key whitelist gaps — MEDIUM (new finding, 2026-03-14)

Both `logback.xml` files whitelist `trace_id`, `span_id`, `pipeline_name`, `pipeline_hash`, `budget_profile` via `<includeMdcKeyName>`. However:

- **`request_id`**: set by `MdcContext.request()` in production (`AiServiceImpl` — all three AI Worker gRPC handlers), defined in SSOT `log.schema.json`, but **NOT whitelisted** in either logback.xml. Silently dropped from JSON output.
- **`stage_id`**: factory method exists in `MdcContext.stage()`, not whitelisted. No production callers yet, but config should match the API to avoid silent drops when adoption begins.

The `ApiErrorHandler.buildTypedResponse()` also reads `MDC.get("request_id")` to inject into HTTP error responses — confirming this key is intended for production use.

### F15. Worker trace context extracted but never written to MDC — MEDIUM (new finding, 2026-03-14; corrected after code review)

**Correction:** The initial investigation (from subagent exploration) reported that the Worker had no `TracingServerInterceptor`. This was wrong. Code review on 2026-03-14 confirmed the Worker **does** have both interceptors:

- `TracingServerInterceptor` at `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/grpc/TracingServerInterceptor.java`
- `RequestMetadataInterceptor` at `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/grpc/RequestMetadataInterceptor.java`
- Both wired in `KnowledgeServer.java:500-503` via `List.of(new TracingServerInterceptor(), new RequestMetadataInterceptor())`
- `SearchOrchestrator` already uses `TracingServerInterceptor.currentOtelContext()` to parent OTel spans under the Head's trace

The actual gap is narrower: **OTel span propagation works, but MDC population does not.** The Worker's gRPC service methods never call `MdcContext.request(traceId, requestId)`, so `trace_id` and `request_id` are never written to the logging MDC. Compare with `AiServiceImpl` (AI Worker), which calls `MdcContext.request(...)` in every handler.

The remaining asymmetry:

| Aspect | Head→AI Worker | Head→Worker |
|--------|---------------|-------------|
| `TraceClientInterceptor` (client-side) | Yes | Yes |
| `TracingServerInterceptor` (server-side) | Yes | **Yes** (corrected) |
| `RequestIdClientInterceptor` (client-side) | Yes (`GrpcAiTranslatorService`) | **No** |
| `RequestMetadataInterceptor` (server-side) | Yes | **Yes** (corrected) |
| `MdcContext.request()` in service methods | Yes (`AiServiceImpl`) | **No** — the gap |
| OTel span parenting | Yes | **Yes** (`SearchOrchestrator` uses it) |

**Result:** Worker OTel traces are correctly parented under Head spans, but Worker **logs** have empty `trace_id` and `request_id` because MDC is never populated. The `RequestIdClientInterceptor` is also missing on the Head→Worker client channel, so even if the Worker's `RequestMetadataInterceptor` extracts `x-request-id`, no request ID is sent.

### F16. Dead MDC infrastructure — LOW (new finding, 2026-03-14)

Infrastructure built ahead of adoption:

| Item | Config/Code | Production callers |
|------|------------|-------------------|
| `span_id` MDC key | Whitelisted in both logback.xml, defined in SSOT schema | **None** — only `MdcPropagationTest` |
| `pipeline_name` / `pipeline_hash` / `budget_profile` | `MdcContext.pipeline()` factory method, whitelisted in configs | **None** — only `MdcContextTest` |
| `stage_id` | `MdcContext.stage()` factory method | **None** — only `MdcContextTest` |
| `Markers.append()` | Tested in `LoggingRedactionGoldenTest` against SSOT schema | **None** |

This is not a bug — it's infrastructure waiting for adoption. Documenting it prevents future developers from assuming these features are active.

### F17. Head logback.xml configuration asymmetries — LOW (new finding, 2026-03-14)

After A1 aligned the Worker logback.xml to production standards, one asymmetry remains: the Head's `logback.xml` lacks `scan="true" scanPeriod="30 seconds"`. The Worker has it. Adding hot-reload to Head would allow runtime debug-level changes via file edit (complementing the existing `LogLevelController` HTTP API, which is Head-only and requires API access).

### F18. Hardcoded logback-core version strings — LOW (new finding, 2026-03-14)

Four modules declare test dependencies on `ch.qos.logback:logback-core:1.5.32` as literal strings instead of using the version catalog alias:

- `modules/app-observability/build.gradle.kts`
- `modules/telemetry/build.gradle.kts`
- `modules/indexing/build.gradle.kts`
- `modules/app-services/build.gradle.kts`

The version catalog (`gradle/libs.versions.toml`) defines `logback = "1.5.32"` and `logback-classic` as an alias, but there is no `logback-core` alias. These literal strings will drift if the catalog version is bumped.

### F19. Remaining unbounded diagnostic writers — MEDIUM (new finding, 2026-03-14)

A5 added retention to `NdjsonSpanExporter`, `CrashReporter`, and `SlowRequestDumper`. Updated inventory:

| Writer | Path pattern | Rotation | Retention | Disk pressure | Bounded? |
|--------|-------------|----------|-----------|---------------|----------|
| `NdjsonMetricExporter` | `telemetry/metrics.ndjson` | 10 MB size-based | 7-day age-based | Yes (WARNING <1 GB, CRITICAL <200 MB) | **Yes — full** |
| `NdjsonSpanExporter` | `telemetry/traces.ndjson` | 10 MB size-based | 7-day age-based | **No** | **Partial** |
| `RrdMetricStore` | `telemetry/metrics.rrd` | N/A (RRD fixed-size) | N/A | N/A | **Yes — by design** |
| `GplTrainingTripleStore` | `gpl-training-triples.ndjson` | Cleared per job | Per-run reset | No | **Effectively** |
| `CrashReporter` | `crashes/crash-*.json` | N/A (write-once) | 30-day startup sweep | No | **Yes** |
| `SlowRequestDumper` | `slowapi/slow-*.json` | N/A (write-once) | 30-day startup sweep | No | **Yes** |
| `DiagnosticsController` | `diagnostics/*.zip` | **None** | **None** | No | **No** |
| `AgentRunStore` | `agent-runs/<sessionId>/` | **None** | **None** | No | **No** |
| `FileOperationLog` | `file-operations/<batchId>.json` | **None** | **None** | No | **No** |

A5 implemented retention as inline code in each writer (age-based file scan via `Files.list` + `lastModifiedTime`). The pattern is nearly identical across `NdjsonSpanExporter`, `CrashReporter`, and `SlowRequestDumper` but was not extracted into a shared utility — RC3's root cause ("no shared retention infrastructure") remains.

### F20. logstash-logback-encoder 2 major versions behind — LOW (new finding, 2026-03-14)

JustSearch uses `logstash-logback-encoder` 7.4. Two major versions have shipped since:

| Version | Date | Key changes | Jackson | Java min |
|---------|------|-------------|---------|----------|
| **7.4** (current) | 2023 | Baseline | 2.x | 11 |
| **8.0** | Jul 2025 | Composite `MdcEntryWriter` with regex filtering (#974), `ShortenedThrowableConverter` extensibility, logback-access 2.x support | 2.x | 11 |
| **9.0** | Oct 2025 | Jackson 3 migration (#1095), pretty-print throwables as array (#1043), suppress stacktrace messages (#1104), `droppedWarnFrequency=0` (#1086) | **3.x only** | **17** |

**8.0** is a safe upgrade — same Jackson 2.x, same Logback 1.5 compatibility. The `MdcEntryWriter` regex feature could simplify our MDC key management.

**9.0** requires a Jackson 3 migration (`com.fasterxml.jackson` → `tools.jackson`, exceptions become unchecked `RuntimeException`). JustSearch uses Jackson 2.18.6 across 10+ modules with custom `resolutionStrategy` version forcing. This is a project-wide dependency migration, not a logging change, but should be planned eventually.

---

## 6. Investigation Status

- [x] **Deep-dive: F1 scope** — 210 sites categorized. ~35-40% accidental, ~60-65% intentional. Top cluster: IndexingLoop commit failures.
- [x] **Deep-dive: F12 triage** — 430 catch blocks, ~200-250 true swallows after removing HTTP handler false positives. 7 critical suspicious patterns identified.
- [x] **Deep-dive: RC2 impact** — Full gap analysis produced. 9 config items to align. Worker also has features to preserve (hot-reload, noise suppression).
- [x] **Deep-dive: RC3 inventory** — 9 diagnostic file writers found. Only 3 are bounded (metrics, RRD, GPL triples). 6 grow without limit.
- [x] **Deep-dive: F5 consolidation** — MdcContext is superset of MdcScope. Also found RequestIdClientInterceptor and TraceClientInterceptor duplicated. Consolidation target: `ipc-common`.
- [x] **Deep-dive: F8/F9 privacy** — WARN-level query exposure in 6 Lucene-layer sites is highest priority. File paths logged at WARN/ERROR in ~20+ sites. Credentials are clean.
- [x] **Re-audit (2026-03-14)**: Verified completed items against codebase. A2 partially unfixed (`IndexingLoop.java:569` still has `log.error("Failed final commit: {}", e.getMessage())`). A4 step 5 skipped (`SensitiveQuery`/`redact()` still duplicated). 107 `log.error`/`log.warn` sites with `e.getMessage()` remain across 50 files.
- [x] **MDC/trace audit (2026-03-14)**: Full MDC key inventory. Only `trace_id` and `request_id` populated in production (AI Worker only). Worker trace propagation broken — no server-side interceptor. `request_id` not whitelisted in logback.xml.
- [x] **Diagnostic retention re-audit (2026-03-14)**: 3 writers still unbounded (`DiagnosticsController`, `AgentRunStore`, `FileOperationLog`). No shared utility extracted — retention implementations duplicated inline.
- [x] **Config audit (2026-03-14)**: logback.xml `request_id`/`stage_id` whitelist gap. Head lacks `scan="true"`. 4 modules hardcode `logback-core:1.5.32` literal instead of version catalog alias.
- [x] **Dependency research (2026-03-14)**: logstash-logback-encoder 7.4 is 2 major versions behind. 8.0 is a safe upgrade (Jackson 2.x). 9.0 requires Jackson 3 migration. OpenRewrite `CompleteExceptionLogging` recipe exists for automated `e.getMessage()` fixes. OTel `opentelemetry-logback-mdc-1.0` library exists for A22.

### Remaining investigation gaps

- [x] **A2b code context (2026-03-14)**: Read all 3 sites. `IndexingLoop.java:569`: catch `RuntimeException` after `indexRuntime.commit()` — purely mechanical fix, add `, e`. No recovery logic beyond the log. `GrpcIngestService.java:414,419`: catch `IllegalStateException`/`InvalidPathException` — replies `onError` to client then logs. Fix is add `, e` for diagnostics. Note: the `withDescription` also uses `e.getMessage()` which is correct for gRPC status (don't pass stack trace to client). `Launcher.java:67`: catch `IllegalArgumentException` for CLI args — `e.getMessage()` is arguably intentional here (validation error, message IS the diagnostic). Could leave as-is or add `, e` — low impact either way.
- [x] **A2 git verification (2026-03-14)**: Commit `2176cd02` is on `main` and in this worktree. It fixed 4 IndexingLoop sites as claimed, but **picked a different 4th site** than what F1 identified. F1 called out lines 325, 404, 511, 569 as the highest-severity cluster. The commit fixed the first three (lines 322, 402, 508 — line numbers shifted) but substituted line 1212 (GPU transition reload, `log.error`) for line 569 (final commit on shutdown, `log.error`). The "Failed final commit" catch block at line 569 was missed — it remains `log.error("Failed final commit: {}", e.getMessage())`. This is the shutdown-path commit failure, which is less frequently hit than the periodic/idle commits but still loses the stack trace on a critical path.
- [x] **A14 dependency graph (2026-03-14)**: `adapters-lucene` does NOT depend on `ipc-common` or `app-util`. All three modules are independent — they converge only at `configuration`/`core`/`infra-core`. The `SensitiveQuery` utility cannot go in `ipc-common` or `app-util` without adding a new dependency to `adapters-lucene`. Options: (a) put it in a module both already depend on (`infra-core`, `configuration`), (b) accept that `adapters-lucene` doesn't need it (it uses the WARN→DEBUG level-split from A3 instead of redaction), or (c) duplicate is acceptable for 2 sites.
- [x] **A19 retention source (2026-03-14)**: Read all three implementations. Core algorithm is identical: age-based, `lastModifiedTime`, `Files.list` + prefix filter + delete. Differences: `NdjsonMetricExporter` runs per-export with `TelemetryHealthState` integration and disk pressure guard; `CrashReporter` and `SlowRequestDumper` run once at startup, best-effort. `CrashReporter` is deliberately SLF4J-free (uses `System.err` for failures) — a shared utility must accommodate this constraint. A shared utility API: `pruneBefore(Path dir, String prefix, Instant cutoff)` static method, returns count, throws nothing. Each caller can wrap with its own error handling.
- [x] **A20 Worker gRPC server wiring (2026-03-14)**: Worker already has `TracingServerInterceptor` AND `RequestMetadataInterceptor`, wired in `KnowledgeServer.java:500-503`. `SearchOrchestrator` already uses `TracingServerInterceptor.currentOtelContext()` to parent OTel spans. **F15 corrected** — the gap is only the missing `MdcContext.request()` calls in Worker service methods, not missing interceptors. A20 rewritten accordingly.
- [x] **A15 prevention feasibility (2026-03-14)**: PMD 7.22.0, no custom rules in `config/pmd/ruleset.xml`. PMD's built-in `SystemPrintln` rule exists — easy win for A16. ArchUnit 1.4.1, 13 test files using `@AnalyzeClasses`/`@ArchTest` pattern with `.doNotHaveFullyQualifiedName()` for exclusions. `arch4u-pmd` not present. For A15 (`e.getMessage()` detection): PMD XPath rule is possible but complex (needs to match "log call with getMessage() inside catch block where exception is not also passed"). ArchUnit can't detect this (operates at class/method-call level, not AST control flow). **Recommended: use OpenRewrite `CompleteExceptionLogging` recipe as a CI lint step** — it's purpose-built for this exact anti-pattern and can run as a dry-run check in `./gradlew rewriteDryRun`.
- [x] **A22 OTel compatibility (2026-03-14)**: JustSearch uses OTel SDK 1.60.1. `opentelemetry-logback-mdc-1.0` latest is 2.23.0-alpha (instrumentation library, versioned separately from SDK — 2.x instrumentation targets 1.x SDK, compatible). It wraps appenders and auto-injects `trace_id`, `span_id`, `trace_flags` into MDC. Compatible with LogstashEncoder. **However**, still alpha. More importantly, A20 (adding `MdcContext.request()` calls) solves the MDC population for `trace_id` and `request_id` without this library. The OTel MDC library is only needed for auto-populating `span_id` — which is lower priority (F16/A22). Recommend: implement A20 first, defer A22 until `span_id` adoption is decided (A23).
- [x] **A25 MdcEntryWriter evaluation (2026-03-14)**: The 8.0 `RegexFilteringMdcEntryWriter` is a composite wrapper with `<includeMdcKeyPattern>` and `<excludeMdcKeyPattern>` regex properties. It's a DIFFERENT config mechanism from `<includeMdcKeyName>` on LogstashEncoder — configured inside a `<provider>` block, more complex XML. For our use case (7-8 specific named keys), per-key `<includeMdcKeyName>` is simpler and more readable. The regex feature is better suited for dynamic/unpredictable MDC keys. **Recommendation: keep `<includeMdcKeyName>` for A11, don't change to regex.**
- [x] **Section 3 staleness (2026-03-14)**: Section 3 table describes pre-A1 Worker state. Updated inline below.

---

## 7. Confidence Assessment

Each action item evaluated for: how well-understood is the change, what can go wrong, and what assumptions remain unverified.

### High confidence — can implement now

| Item | Confidence | Rationale |
|------|-----------|-----------|
| A1 (Worker logback.xml) | **High** | Config-only change with clear spec from gap analysis. Logback hot-reload means misconfigs are recoverable. Only risk: verify `logstash-logback-encoder` dep doesn't conflict in Worker's Gradle. |
| A7 (logback-test.xml) | **High** | Single new file in `test-support`. Well-understood. |
| A8 (3 string concat fixes) | **High** | Trivial mechanical changes. |

### Medium confidence — feasible with caveats

| Item | Confidence | Risk / Caveat |
|------|-----------|---------------|
| A2 (e.getMessage() fixes) | **Medium** | The investigation sampled 35 of 210 sites and extrapolated a 35-40% accidental ratio. I don't have a verified complete list — I'll need to read each site individually and make a judgment call. **Mitigation:** conservative scope — only fix sites where the exception is clearly non-trivial (RuntimeException/IOException on commit/write/load paths). Leave ambiguous sites alone rather than risk adding noisy stack traces to intentionally-suppressed logs. |
| A3 (query redaction) | **Medium-Low** | Design question unresolved: the Lucene parse-failure WARN sites log `queryText` because that's the diagnostic someone needs to understand *why* the query failed. Redacting it removes the useful information. The correct fix may be to **split the log level** (WARN for the error without query text, DEBUG for the full query) rather than redact. Also: where does the shared utility live? `adapters-lucene` doesn't depend on `app-util` or `ipc-common` — need to verify dependency graph before deciding. |
| A4 (consolidate duplicates) | **Medium** | The consolidation plan is solid but unverified assumptions remain: (1) `ipc-common` package naming conventions, (2) whether `app-indexing`'s `RequestIdClientInterceptor` is truly unused (could be wired via reflection/config), (3) all consumers of `app-observability`'s `MdcContext` API surface need import updates. Should read the actual source files before moving code. |
| A9 (DEBUG redaction) | **High** | Same pattern as A3 but simpler — these are already DEBUG level so redaction is straightforward. Less diagnostic-value tradeoff than the WARN sites. |
| A10 (file path standardization) | **Medium-Low** | Case-by-case judgment needed. In many sites the full path IS diagnostically necessary — "file not readable: report.pdf" is less useful than the full path when debugging a permissions issue on a specific directory. Can't batch this; each site needs individual evaluation. |

### Low confidence — needs more investigation or separate tempdoc

| Item | Confidence | Why |
|------|-----------|-----|
| A5 (diagnostic file retention) | **Low** | Haven't read `NdjsonMetricExporter` or `NdjsonSpanExporter` source directly — working from investigation summaries. The retention patterns differ fundamentally: metrics rotates a single growing file; crashes/slowapi are write-once files that need count/age pruning. Extracting a shared utility means designing a new API (when to prune? caller-driven? scheduled?). Risk of data loss if pruning logic is wrong. |
| A6 (7 critical swallowed exceptions) | **Low** | **These are correctness bugs, not logging bugs.** The logging audit surfaced them, but fixing them requires deep understanding of each component's behavioral contract: What should happen when an IndexingLoop commit fails — retry? crash the loop? mark jobs as failed? What's the correct EmbeddingService recovery model — retry with backoff? How many times? Changing `GrpcIngestService` from `onNext(errorResponse)` to `onError(status)` could break client-side handlers. Changing `RootLifecycleOps` to surface failures requires UI work. Each of these is a mini-investigation in itself. |

### New items (2026-03-14 re-audit)

| Item | Confidence | Rationale |
|------|-----------|-----------|
| A11–A13 (config fixes) | **High** | Config-only changes, well-understood, no behavioral risk. |
| A2b (remaining log.error fixes) | **High** | 4 specific sites identified, mechanical fix. |
| A2c (log.warn triage) | **Medium** | 107 sites need individual triage — same judgment-call pattern as original A2. |
| A14 (SensitiveQuery consolidation) | **Medium** | Dependency graph check needed for utility location (`ipc-common` vs `app-util`). |
| A15 (OpenRewrite CI lint for e.getMessage()) | **High** | Investigation confirmed PMD XPath is too complex and ArchUnit can't detect this pattern. OpenRewrite `CompleteExceptionLogging` recipe is purpose-built for this. Run as `rewriteDryRun` in CI. |
| A16 (ArchUnit SLF4J-only) | **Medium** | Straightforward rule with known-intentional exclusions (`HeadlessApp` IPC, `Launcher` CLI, `CrashReporter` last-resort). |
| A17 (remaining retention) | **Medium** | Same pattern as existing sweeps, but `AgentRunStore` dirs need recursive delete. |
| A18 (span exporter disk pressure) | **High** | Copy disk pressure guard from `NdjsonMetricExporter` — pattern already exists. |
| A19 (shared retention utility) | **Medium-High** | Core algorithm identical across 3 writers. API shape clear: `pruneBefore(Path dir, String prefix, Instant cutoff)`. Main wrinkle: `CrashReporter` is SLF4J-free — utility must not use logging. |
| A20 (Worker MDC population) | **High** | Worker already has both interceptors. Only missing `MdcContext.request()` calls in 3 service entry points — same pattern as `AiServiceImpl`. Mechanical. |
| A21 (RequestIdClientInterceptor on Head→Worker) | **High** | One-line change in `RemoteKnowledgeClient` — add `new RequestIdClientInterceptor()` to the `ClientInterceptors.intercept()` call alongside `TraceClientInterceptor`. |
| A22 (span_id population) | **Low** | Multiple approaches (OTel instrumentation agent vs manual SpanProcessor). Architectural decision needed. |
| A25 (logstash-logback-encoder 7.4→8.0) | **High** | Same Jackson 2.x, same Logback 1.5. Version bump + lockfile update + verify JSON output unchanged. |
| A26 (logstash-logback-encoder 8.0→9.0) | **Low** | Requires Jackson 3 migration across 10+ modules. Project-wide impact, not a logging-scoped change. |

### Recommendation (updated 2026-03-14, finalized)

**Batch 1 — Config, wiring, mechanical fixes (Tier 1):**
- A11, A12, A13, A25 — config fixes and safe dependency upgrade
- A20, A21 — Worker MDC population + request-ID wiring (mechanical, enables log correlation)
- A2b — fix 3 remaining `log.error` sites

**Batch 2 — Full e.getMessage() triage (Tier 2):**
- A2c — triage all 107 remaining `log.error`/`log.warn` sites

**Batch 3 — Prevention + retention (Tiers 3–4):**
- A15 — OpenRewrite `CompleteExceptionLogging` as developer tool first, CI later
- A16 — PMD `SystemPrintln` + ArchUnit JUL ban
- A17, A18 — retention for 3 unbounded writers + span exporter disk pressure

**Batch 4 — Shared utility (Tier 5):**
- A19 — extract `DiagnosticFileRetention` utility

**Implement last (requires project-wide migration):**
- A26 — Jackson 3 / logstash-logback-encoder 9.0

**Dropped:**
- A14 — trivial 2-site duplication, not worth a dependency

**Defer to separate tempdoc:**
- A6 — correctness bugs, not logging changes
- A10 — case-by-case path evaluation
- A22, A23, A24 — architectural decisions / documentation

---

## 8. Action Items

### Completed

- [x] **A1 (RC2)**: Harden Worker logback.xml — LogstashEncoder JSON, SizeAndTimeBasedRollingPolicy, gzip, neverBlock, queue 2048, loop/coordination loggers to INFO. Preserved hot-reload, noise suppression.
- [x] **A7 (F6/RC5)**: Added shared `logback-test.xml` to `test-support` module (root WARN, `io.justsearch` INFO).
- [x] **A8 (F7)**: Fixed 3 string-concatenation log statements.
- [x] **A3 (F13/F8)**: Split query text from WARN to DEBUG at 6 Lucene parse-failure sites (ChunkSearchOps ×3, LuceneIndexRuntime, TextQueryOps, SearchOrchestrator).
- [x] **A5 (F4/RC3)**: Added inline rotation/retention for traces.ndjson (10 MB, 7-day), crash reports (30-day startup sweep), slow-request dumps (30-day startup sweep). Note: no shared utility was extracted — see A19.

### Partially completed

- [~] **A2 (F1)**: Fixed 15 accidental `e.getMessage()` sites at `log.error` level. However, `IndexingLoop.java:569` (`log.error("Failed final commit: {}", e.getMessage())`) — the highest-severity site identified in F1 — remains unfixed. 107 `log.error`/`log.warn` sites with `e.getMessage()` remain across 50 files. See A2b and A2c for remaining work.
- [~] **A4 (F5/RC4)**: Consolidated MdcContext, RequestIdClientInterceptor, TraceClientInterceptor into ipc-common. Fixed isEmpty→isBlank. Deleted 6 duplicate source files. However, consolidation plan step 5 (extract shared `SensitiveQuery`/`redact()` utility) was skipped — pattern remains independently implemented in `KnowledgeSearchController` and `SearchOrchestrator`. See A14.

### Dropped

- ~~**A9 (F8 DEBUG)**~~: Dropped — DEBUG is opt-in, redaction adds no value at that level.
- ~~**A14 (F5/F13)**~~: Dropped — `SensitiveQuery`/`redact()` duplication is 2 sites with a 5-line record+method. `adapters-lucene` doesn't use it (uses A3's level-split). `indexer-worker` doesn't depend on `app-util`, and adding a dependency solely for this is over-engineering. Tolerable duplication.

### Tier 1 — Config, wiring, and mechanical fixes — COMPLETED (2026-03-14)

- [x] **A11 (F14)**: Added `request_id` and `stage_id` to `<includeMdcKeyName>` in both logback.xml files.
- [x] **A12 (F17)**: Added `scan="true" scanPeriod="30 seconds"` to Head's `logback.xml`.
- [x] **A13 (F18)**: Added `logback-core` alias to `gradle/libs.versions.toml`, replaced 4 hardcoded `"ch.qos.logback:logback-core:1.5.32"` literals with `libs.logback.core`.
- [x] **A25 (F20)**: Upgraded `logstash-logback-encoder` 7.4 → 8.0. Updated lockfiles and verification metadata.
- [x] **A20 (F15/RC6)**: Added `MdcContext.request(traceId, requestId)` try-with-resources to 6 Worker gRPC service methods: `GrpcSearchService.search`, `suggest`, `retrieveContext`, `matchCitations`; `GrpcIngestService.submitBatch`, `syncDirectory`. Skipped `GrpcHealthService.check` (trace-level diagnostic, not worth overhead). Extracted `openRequestMdc()` private helper in both services to deduplicate the 3-line pattern. Guards invalid span context (passes `null` traceId instead of all-zeros). Catch blocks structured inside MdcContext scope so error logs retain trace correlation. Added `MdcPopulationTest` (3 tests) verifying full interceptor→MDC chain, scope cleanup, and invalid-span handling.
- [x] **A21 (F15/RC6)**: Wired `RequestIdClientInterceptor` on `RemoteKnowledgeClient` Head→Worker channel alongside existing `TraceClientInterceptor`.
- [x] **A2b (F1)**: Fixed 3 `log.error` sites: `IndexingLoop.java:569` (final commit), `GrpcIngestService.java` ×2 (queue error, invalid path). `Launcher.java:67` left as-is (intentional `IllegalArgumentException` for CLI args).

### Tier 2 — Full e.getMessage() triage — COMPLETED (2026-03-14)

- [x] **A2c (F1)**: Triaged all 104 remaining `log.error`/`log.warn` sites with `e.getMessage()` across 49 files. Result: 55 fixed (passed exception as last SLF4J argument for full stack trace), 48 left as intentional (best-effort/fallback/validation patterns where message-only is appropriate), 1 already correct. 49 intentional sites remain — all verified against the triage criteria (message contains "best-effort"/"non-fatal"/"will fall back"/"fallback"/"ignored", or exception type is validation-specific, or cleanup/close path).

### Tiers 3–5 — Prevention, retention, shared utility — COMPLETED (2026-03-14)

- [x] **A15 (RC1)**: Added OpenRewrite `CompleteExceptionLogging` recipe as developer tool. Plugin `org.openrewrite.rewrite` v7.28.1 with `rewrite-logging-frameworks:3.26.0`. `./gradlew rewriteDryRun` previews, `./gradlew rewriteRun` applies. Note: the recipe targets simple `log.error(e.getMessage())` patterns but does NOT catch `log.error("prefix: {}", e.getMessage())` — so A2c triage must still be done manually. The tool prevents the simpler anti-pattern form in future code.
- [x] **A16 (RC1)**: Added PMD `SystemPrintln` rule to `config/pmd/ruleset.xml`. Added `@SuppressWarnings("PMD.SystemPrintln")` to 14 intentional sites across 7 files (Launcher, CrashReporter, HeadlessApp, 3 benchmark CLIs, WorkflowValidator). Added ArchUnit JUL ban in `LayeringEnforcementTest` (Rule 8: no `java.util.logging.Logger` dependency).
- [x] **A19 (RC3)**: Extracted `DiagnosticFileRetention` utility in `modules/telemetry`. Two static methods: `pruneBefore(Path, String, Instant)` for files, `pruneDirectoriesBefore(Path, Instant)` for session dirs. No SLF4J dependency (CrashReporter-safe). Migrated 3 callers: `CrashReporter`, `SlowRequestDumper`, `NdjsonSpanExporter`. `NdjsonMetricExporter` not migrated (has health-state integration that goes beyond the shared API).
- [x] **A18 (F19)**: Added `hasSufficientDiskSpace()` disk pressure guard to `NdjsonSpanExporter`, matching `NdjsonMetricExporter` pattern (CRITICAL <200 MB suppresses writes, WARNING <1 GB logs warning). Checks at top of `export()` before rotation.
- [x] **A17 (F19/RC3)**: Added 30-day retention sweeps to 3 unbounded writers: `DiagnosticsController` (export-time, `justsearch-diagnostics-` prefix), `AgentRunStore` (constructor, `pruneDirectoriesBefore`), `FileOperationLog` (constructor, empty prefix for all JSON files).

### Tier 6 — Dependency upgrade (implement last)

- [ ] **A26 (F20)**: Upgrade `logstash-logback-encoder` from 8.0 to 9.0. Requires Jackson 3 migration (`com.fasterxml.jackson` → `tools.jackson`) across all modules, minimum Java 17 (already met). This is a project-wide dependency change — do not attempt until Jackson 3 migration is planned. OpenRewrite has an automated recipe: `org.openrewrite.java.jackson.upgradejackson_2_3`. Gains: pretty-print throwables as array, suppress stacktrace messages, LMAX Disruptor 4.

### Documentation — COMPLETED (2026-03-14)

- [x] **A24 (RC1)**: Written `docs/reference/contributing/logging-conventions.md`. Covers: exception logging (default vs message-only vs WARN/DEBUG split), MDC keys and gRPC service usage, query text redaction patterns, file path handling, enforcement rules (PMD, ArchUnit, OpenRewrite).

### Spun off to separate tempdocs

- **A6 (F12 critical)** → **Tempdoc 296** — Swallowed Exception Correctness Fixes (7 items)
- **A10 (F9)** → **Tempdoc 297** — Diagnostics Export Path Redaction
- **A23 (F16)** → **Tempdoc 298** — MDC Key and Structured Logging Adoption Timeline
- **A26 (F20)** → **Tempdoc 299** — Jackson 3 Migration (project-wide, includes logstash-logback-encoder 9.0)
