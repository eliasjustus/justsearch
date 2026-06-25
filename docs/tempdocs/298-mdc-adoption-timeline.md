---
title: "Tempdoc 298 - MDC Key and Structured Logging Adoption"
---

# Tempdoc 298 - MDC Key and Structured Logging Adoption

**Status:** Complete
**Created:** 2026-03-14
**Updated:** 2026-03-14
**Goal:** Adopt the MDC keys and structured logging features that are already built but have zero production callers.

## Context

Tempdoc 289 (F16) identified infrastructure built ahead of adoption. The infrastructure layer is complete: `MdcContext` factory methods, logback MDC whitelists, SSOT log schema, `LoggingRedactionGoldenTest` validation. What's missing is production code that actually calls these APIs.

### What's active

| Feature | Status | Set by |
|---------|--------|--------|
| `trace_id` | **Active** | `MdcContext.request()` in Worker + AI Worker gRPC services (289 A20) |
| `request_id` | **Active** | Same (289 A20/A21) |

### What's built but uncalled

| Feature | Infrastructure | What's needed to adopt |
|---------|---------------|----------------------|
| `pipeline_name` / `pipeline_hash` / `budget_profile` | `MdcContext.pipeline()`, logback whitelist | Add calls where pipeline config is resolved |
| `stage_id` | `MdcContext.stage()`, logback whitelist | Add calls at pipeline stage boundaries |
| `span_id` | Logback whitelist, SSOT schema | OTel MDC bridge library OR manual SpanProcessor |
| `Markers.append()` | Tested by `LoggingRedactionGoldenTest` | Add to specific log call sites |

## Investigation Results

### Item 1 — `span_id` (MEDIUM confidence — up from LOW)

**Resolved questions:**

- [x] **OTel library compatibility**: `opentelemetry-logback-mdc-1.0:2.26.0-alpha` (instrumentation project, not core SDK). Works with `AsyncAppender` **if wrapping order is `OTEL → ASYNC → LogstashEncoder`** (OTEL must be outermost). The library patches `ILoggingEvent.mdcPropertyMap` via reflection on the originating thread before the event is queued — this survives async handoff.
- [x] **`trace_id` conflict**: **Critical blocker.** The library has a hard bail-out: if `trace_id` already exists in the event's MDC map, it skips ALL injection (not just `trace_id` — also `span_id` and `trace_flags`). Our manual `MdcContext.request()` sets `trace_id`, so the library would inject nothing.
- [x] **Alpha stability**: All versions are `-alpha`. Acceptable for local desktop app, but adds a dependency with potential breaking changes. Version `2.26.0-alpha` targets SDK `1.60.1` (matches our version exactly).
- [x] **Manual SpanProcessor**: Fatal issues with `AsyncAppender` (MDC.remove fires before event is processed), nested spans (inner end removes outer's span_id), and virtual threads. Not viable for our architecture.

**Resolution path**: Two options, both viable:
- **Option A (recommended)**: Use the OTel library with `<traceIdKey>otel_trace_id</traceIdKey>` to avoid the conflict. Our manual `trace_id` stays, and we get `span_id` + `trace_flags` injected under their default keys. The `otel_trace_id` key can be excluded from the logback whitelist since we already have `trace_id`.
- **Option B**: Remove manual `trace_id` from `MdcContext.request()`, let the OTel library handle `trace_id` + `span_id` + `trace_flags`. Risk: OTel library only injects when a span is active — if any code path logs outside a span, `trace_id` would be missing. Our current manual approach is more reliable for `trace_id` coverage.

**Implementation**: Add dependency, configure appender wrapping order in both `logback.xml` files, set `<traceIdKey>otel_trace_id</traceIdKey>`, add `span_id` to logback whitelist (already done in 289).

### Item 2 — Pipeline keys (LOW confidence — down from MEDIUM)

**Resolved questions:**

- [x] **Where is pipeline config resolved?** `PipelineConfig` proto (in `indexing.proto`) only has activation flags (`sparse_enabled`, `dense_enabled`, `splade_enabled`, etc.) — **no `pipeline_name`, `pipeline_hash`, or `budget_profile` fields**. The `PipelineDefinition` with name/hash/budget lives in the **Head process** (loaded by `CapabilitiesService` / `SearchPipelineLoader`), not the Worker.
- [x] **Thread-locality?** `SearchOrchestrator.execute()` mostly runs on the gRPC thread (MDC works). One exception: 3-way parallel retrieval (BM25 + dense + SPLADE) fans out to 3 virtual threads via `Executors.newVirtualThreadPerTaskExecutor()` — MDC won't propagate.
- [x] **Scoping model?** `MdcContext.pipeline()` nests correctly inside `MdcContext.request()` — `close()` restores the snapshot taken at open time.

**Architectural blocker**: The values needed for `MdcContext.pipeline()` are not available on the Worker side. Three options:

- **Option A — Head-side MDC**: Set `MdcContext.pipeline()` in `RemoteKnowledgeClient.search()` where `PipelineDefinition` is available. Only tags Head-process logs (not Worker logs). Low effort, partial value.
- **Option B — Derive synthetic name on Worker**: Use `PipelineConfig` flags to derive a mode string (e.g., `"hybrid+splade+cross_encoder"`). Available in `GrpcSearchService.search()`. No proto change, but values are synthetic, not the real pipeline name/hash.
- **Option C — Extend proto**: Add `pipeline_name`/`pipeline_hash`/`budget_profile` string fields to `PipelineConfig` or `SearchRequest`. Set in Head, read in Worker. Most complete, but requires proto change + regeneration + both-side updates.

**Recommendation**: Option A (Head-side) for immediate value, defer Option C to a future iteration. Option B is misleading — it would tag logs with a derived string that doesn't match any real `PipelineDefinition`.

### Item 3 — `stage_id` (MEDIUM confidence — up from MEDIUM-LOW)

**Resolved questions:**

- [x] **Pipeline stages**: SSOT `search.v1.json` defines 11 stages: `normalize`, `translate_intent`, `parse`, `retrieve_bm25`, `retrieve_ann`, `merge`, `corrections`, `rerank`, `filter`, `highlight`, `respond`.
- [x] **Stage IDs are strings** — defined in `StageDefinition` record (`id` + optional `budgetMs`). No enum.
- [x] **Stage boundaries in code**:

| Code phase | SSOT stage(s) | Thread | MDC works? |
|------------|--------------|--------|------------|
| Request parsing, QPP, filter expansion | `parse` | gRPC | Yes |
| Vector/SPLADE prep | `parse` | gRPC | Yes |
| Sparse-only retrieval | `retrieve_bm25` | gRPC | Yes |
| 3-way parallel retrieval | `retrieve_bm25` + `retrieve_ann` | 3 virtual threads | **No** |
| 2-way hybrid | `retrieve_bm25` + `retrieve_ann` | gRPC | Yes |
| Dense/SPLADE-only | `retrieve_ann` | gRPC | Yes |
| Zero-hit corrections | `corrections` | gRPC | Yes |
| Chunk merge/fusion | `merge` | gRPC | Yes |
| Facet assembly | `filter` | gRPC | Yes |
| Response building + highlight | `highlight` + `respond` | gRPC | Yes |

- [x] **RAG path**: All synchronous on gRPC thread — MDC works for all phases.
- [x] **Virtual thread gap**: The 3-way parallel retrieval path (lines ~432–467 in SearchOrchestrator) spawns virtual threads that won't inherit MDC. To tag retrieval logs in that path, `MdcContext.stage()` would need to be set inside each `supplyAsync` lambda.

**Implementation approach**: Add `MdcContext.stage()` calls at phase boundaries in `SearchOrchestrator.execute()` and `RagContextOps.executeRetrieval()`. For the 3-way parallel path, either (a) accept the gap, or (b) propagate MDC into lambdas manually (copy MDC map, restore in lambda).

### Item 4 — `Markers.append()` (HIGH confidence — up from MEDIUM)

**Resolved questions:**

- [x] **Candidate sites enumerated** — 8 high-value sites across 5 files:

| # | File | Log text | Marker key(s) | Path |
|---|------|----------|--------------|------|
| 1 | `KnowledgeHttpApiAdapter.java:653` | `"Rerank skipped after {}ms"` | `reason_code`, `latency_ms` | Hot |
| 2a | `CrossEncoderReranker.java:368` | `"Rerank skipped: tokenization took {}ms"` | `reason_code`, `latency_ms`, `budget_ms` | Hot |
| 2b | `CrossEncoderReranker.java:397` | `"Rerank skipped: prep took {}ms, insufficient budget"` | `reason_code`, `latency_ms` | Hot |
| 3 | `RagContextOps.java:288` | `"RAG mode is 'hybrid' but embedding unavailable"` | `reason_code` (from var), `requested_mode` | Hot |
| 4 | `RagContextOps.java:758` | `"Chunk reranking skipped after {}ms"` | `reason_code`, `latency_ms` | Hot |
| 5 | `SearchOrchestrator.java:145` | `"Search request has no pipeline config"` | `reason_code`, `search_mode` | Warm |
| 6 | `SearchOrchestrator.java:1515` | `"SPLADE query encoding failed: {}"` | `reason_code`, `error_type` | Hot |
| 7 | `EmbeddingCompatibilityController.java:113,131` | `"BLOCKED_LEGACY"` / `"BLOCKED_MISMATCH"` | `reason_code`, `doc_count` / fingerprints | Cold |

- [x] **LogstashEncoder serializes markers automatically** — confirmed by `LoggingRedactionGoldenTest`. Markers appear as top-level JSON fields.
- [x] **Multi-key markers**: Chain with `Markers.append("k1", v1).and(Markers.append("k2", v2))` or use `Markers.appendEntries(Map.of(...))`.

## Revised Implementation Priority

| Item | Confidence | Value | Recommendation |
|------|-----------|-------|----------------|
| 4 | **High** | Medium | **Implement first** — 5-6 sites, add markers. Most straightforward, no architectural questions. |
| 3 | **Medium** | Medium | **Implement second** — stage boundaries are mapped, most run on gRPC thread. Accept the virtual-thread gap or propagate manually. |
| 1 | **Medium** | High | **Implement third** — OTel library approach is clear (Option A with renamed key). Needs logback config change + dependency add. |
| 2 | **Low** | High | **Defer or partial** — architectural blocker (values not on Worker side). Head-side MDC (Option A) is low effort. Proto extension (Option C) deferred. |

## Items

- [x] 1. **`span_id` adoption** — Added `opentelemetry-logback-mdc-1.0:2.26.0-alpha` dependency to indexer-worker and app-launcher. Configured `OpenTelemetryAppender` (`OTEL_MDC`) as outermost wrapper in both `logback.xml` files (OTEL → ASYNC → FILE). Set `<traceIdKey>otel_trace_id</traceIdKey>` to avoid conflict with manual `trace_id`. Library injects `span_id` and `trace_flags` into log events via reflection on the originating thread.
- [x] 2. **Pipeline key adoption** — Extended `PipelineConfig` proto with `pipeline_name` (field 9), `pipeline_hash` (field 10), `budget_profile` (field 11). Head side: `KnowledgeHttpApiAdapter.toProtoPipelineConfig()` derives pipeline name from activation flags (e.g., `"sparse+dense+ce"`). `PipelineConfigs` presets (TEXT, VECTOR, HYBRID) and `GplJobCoordinator.GPL_REQUERY_PIPELINE` also carry `pipeline_name`. Worker side: `GrpcSearchService.search()` reads proto fields and opens `MdcContext.pipeline()` scope nested inside `MdcContext.request()`. Full `PipelineDefinition` identity (name/hash/budget from SSOT JSON) deferred — requires wiring `CapabilitiesService` into the search path.
- [x] 3. **`stage_id` adoption** — Added `MDC.put("stage_id", ...)` at phase boundaries in `SearchOrchestrator.execute()` (parse → retrieve → merge → respond) and `RagContextOps.searchChunksWithMeta()` (retrieve → rerank → respond). Uses direct MDC.put instead of `MdcContext.stage()` because phases are sequential within one method and the outer `MdcContext.request()` scope handles cleanup. Virtual-thread gap accepted for 3-way parallel retrieval — those legs don't inherit MDC.
- [x] 4. **`Markers.append()` adoption** — Added structured markers to 6 hot-path log sites across 3 files: reranker tokenize-budget skip (`CrossEncoderReranker:368`), reranker prep-budget skip (`:397`), RAG hybrid fallback with `retrievalModeReason` (`RagContextOps:288`), RAG chunk rerank skip (`:758`), deprecated mode fallback (`SearchOrchestrator:145`), SPLADE encoding failure (`:1515`). Each log line now emits `reason_code` + relevant numeric fields as top-level JSON markers.

## Runtime Verification (2026-03-14)

Runtime verification against the dev stack confirmed:

- **`request_id`**: confirmed in Worker JSON logs on gRPC paths (ingest, sync). Example: `"request_id":"8e1d4f05-..."` on `syncDirectory`, `pruneByPathPrefix`, `submitBatch` log lines.
- **`stage_id` and `pipeline_name`**: set correctly in code but not visible in default output — search-path log lines (`SearchOrchestrator`, `GrpcSearchService`) emit at DEBUG level, and the Worker logback config has `io.justsearch.indexerworker` at INFO. These keys appear when DEBUG is enabled via the runtime logging API or config change.
- **Structured markers**: same visibility constraint — marker log lines are DEBUG on hot paths. WARN-level markers (SPLADE failure, deprecated mode fallback) would appear when those conditions trigger.
- **JSON format**: Worker now writes structured JSON via `LogstashEncoder` with `OTEL_MDC → ASYNC_FILE → FILE` chain.

**Infrastructure bugs found and fixed during verification:**

1. **Worker JAR excluded `logback.xml`** (pre-existing) — `tasks.jar { exclude("logback.xml") }` in `indexer-worker/build.gradle.kts` meant the spawned Worker process had no logback config and fell back to `BasicConfigurator` (console-only). All `LogstashEncoder`, MDC whitelists, and `OTEL_MDC` config was dead. Removed the exclusion.
2. **`runHeadless` task missing logging deps** (pre-existing) — `ui` module's `runHeadless` classpath had no `logstash-logback-encoder` or `opentelemetry-logback-mdc`, causing `ClassNotFoundException` when loading the production logback.xml via `-Dlogback.configurationFile`. Added both as `runtimeOnly` to the `ui` module. Also added `-Dlogback.configurationFile` and `-Djustsearch.data.dir` to all `runHeadless` task variants.

## Known Limitations

- **stage_id uses simplified names** — `parse`, `retrieve`, `merge`, `respond` (SearchOrchestrator) and `retrieve`, `rerank`, `respond` (RagContextOps) instead of the 11 SSOT stage names. Zero-hit corrections run inside "retrieve" without a separate "corrections" stage.
- **3-way parallel retrieval has no stage_id** — virtual threads spawned by `Executors.newVirtualThreadPerTaskExecutor()` don't inherit MDC. Log lines from BM25/dense/SPLADE legs in the 3-way path have no `stage_id`.
- **`pipeline_hash` and `budget_profile` are not populated** — proto fields exist but only `pipeline_name` is set (derived from activation flags). Full identity from `PipelineDefinition` requires wiring `CapabilitiesService` into the search request path.
- **OTel library is alpha** (`2.26.0-alpha`) — uses reflection to patch `LoggingEvent.mdcPropertyMap`. If Logback changes that internal field, injection silently stops. `trace_flags` is injected but excluded from the logback MDC whitelist (not needed for current use).
- **Marker sites are Worker-side only** — the 2 reranker skip sites in `KnowledgeHttpApiAdapter` (Head-side, lines 568/653) were not converted because they are less valuable (the same skip reasons are logged by the Worker-side reranker with full MDC context).
- **MDC keys on search path require DEBUG level** — `stage_id`, `pipeline_name`, and markers are set on search-path log lines that emit at DEBUG. The Worker's default level for `io.justsearch.indexerworker` is INFO. Enable DEBUG via runtime logging API to see them.

## Reference

- `modules/ipc-common/src/main/java/io/justsearch/ipc/logging/MdcContext.java` — MDC scope manager
- `modules/app-observability/src/test/java/io/justsearch/app/observability/LoggingRedactionGoldenTest.java` — Markers.append validation test
- `docs/reference/contributing/logging-conventions.md` — MDC key documentation
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/SearchOrchestrator.java` — primary candidate for stage_id + pipeline key adoption
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/GrpcSearchService.java` — `openRequestMdc()` pattern for scoping reference
- `modules/ipc-common/src/main/proto/indexing.proto` — `PipelineConfig` proto (activation flags + pipeline identity fields 9-11)
- `modules/pipeline-schema/src/main/java/io/justsearch/pipeline/PipelineDefinition.java` — `name()`, `dagHash()`, `budgetProfile()`
- `modules/pipeline-schema/src/main/java/io/justsearch/pipeline/StageDefinition.java` — `id` + `budgetMs`
- `SSOT/pipelines/search.v1.json` — canonical pipeline stage definitions
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/RagContextOps.java` — RAG path stage boundaries
- `modules/reranker/src/main/java/io/justsearch/reranker/CrossEncoderReranker.java` — reranker skip markers
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java` — rerank + LambdaMART skip markers
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/embed/EmbeddingCompatibilityController.java` — embedding compat markers
