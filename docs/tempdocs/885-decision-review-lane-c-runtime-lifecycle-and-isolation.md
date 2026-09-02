---
status: IN PROGRESS — chunk 1 (item 19 NRT fix + baseline) and chunk 2 (item 14 extraction pool) landed; items 3/6/21/19-measure open
created: 2026-09-01
updated: 2026-09-02
owner_session: unassigned (wave-1 orchestrator; on the critical path 0 → C → D → F)
follows:
  - 882-decision-review-lane0-hygiene.md (lane 0; moved MMF RESERVED1 start, raised the RPC deadline, left the breath-hold and per-RPC deadlines to C/F)
  - 410 (extraction timeout + `ExtractionSandbox` seam, 2026-04-25)
  - 326 (the eval escape hatch for breath-holding: throughput ~5 → ~1 doc/s under polling)
  - 627 / 630 (supervision extraction; PID gate + energy slot)
  - 430 / 445 / 662 (HealthEvent substrate; push jobs; SSE connection budget)
  - 269 §A9 (RISK-002 SQLite queue throughput: "Monitor", never instrumented)
---

# 885 — Decision review, lane C: runtime lifecycle and isolation

**Thesis.** Four lifecycle decisions from November–December 2025 still run the Worker as first
written, and each encodes a policy as a **proxy signal** instead of the quantity the policy is
about: recent-activity wall clock → pause (instead of in-flight load → throttle); client poll →
health truth (instead of state change → health truth); attempt count → failure (instead of
failure class → failure); wall clock → NRT reopen (instead of staleness → reopen). The
out-of-process extraction sandbox that would make a wedged parser survivable is built but
unreachable as shipped, and because the in-process extractor is a single-thread executor, one
wedged parser stops **all** extraction, not one file. This lane replaces each proxy with the real
quantity, with the long-term shape in mind: the sandbox becomes a persistent process pool (the
correct home for crash isolation and the precondition for lane F's single-JVM engine), pacing
becomes a duty cycle driven by Worker-observed foreground load, health sampling becomes
internal, and the failure ladder that already exists is completed rather than rebuilt.

> **Independent review fold (2026-09-01, session justsearch-public-9a).** Two of this
> contract's original premises were wrong and are corrected below, marked **[R#]**: the eval
> escape hatch never reached the Worker, so no measurement of its effect exists; and job-failure
> classification already ships. Both were verified by this author before folding.

Lane C is on the critical path (see `882-…lane0-hygiene.md` for the split and cross-lane rules).
Lane D (index identity + migration) and lane F (engine merge) branch after it merges. Design
everything here so that it survives the Head/Worker merge: no new MMF fields, no new Head→Worker
signals; the Worker observes its own foreground load.

## Scope (contract)

| # | Item | This lane does | Not this lane |
|---|---|---|---|
| 14 | extraction isolation | ship the `process` sandbox mode as a **persistent child process pool** (1 process by default, restart on crash/timeout, bounded queue) with a shipped launch command built from the running JVM + Worker classpath; make it the default for the parser families that can wedge (PDF, Office, archives) and measurable for all; keep `in_process` as an explicit opt-out | changing Tika policy, OCR routing, VDU (790), file-size limits |
| 3 | breath-holding | replace the Head-written wall-clock activity byte with a Worker-local **foreground-load gauge** (in-flight search-family RPCs) driving a **duty cycle** (bounded throttle, never a full pause) plus the existing GPU arbitration slot; remove the 16 `isUserActive` call sites and the dead eval hatch; state the MMF residue lane F inherits | the MMF layout (lane 0 fixed it; lane F deletes it); `main_gpu_active` (stays, see residue) |
| 6 | health sampling | the existing `KnowledgeServerHealthMonitor` schedule performs the one `IndexStatus` unary and feeds `ConditionStore` + `HealthEventChangeRegistry`; `/api/status` returns the latest snapshot without a Worker RPC on the request thread; the health SSE stream advances with no client polling; a `FetchDocuments` byte budget for the 50-document GPL batches | a streaming health RPC (wasted under lane F); the frontend's 10 s poll (`modules/ui-web`, out of scope; it becomes cheap) |
| 21 | job queue failures | **complete** the shipped `IngestionRetryPolicy` ladder: transients no longer count against `MAX_ATTEMPTS`, the backoff ladder extends from ~17 min to days with a visible terminal state that a rescan resets, the real exception text is preserved in `error_message`, single source for max-attempts, queue throughput metrics (RISK-002's missing instrument) | replacing SQLite; the schema ladder; rebuilding classification (it exists, **[R4]**) |
| 19 | NRT + commit cadence | fix the live defect first (**[R5]**: `ComponentsFactory` hardcodes 0.5/0.05 while `CommitOps` rebuilds the reopen thread from the configured `index.nrt.*` values after every backfill, so cadence silently changes mid-run); then **measure**: jseval throughput + search p95 + p95 of the first search after N new segments, current vs on-demand reopen + background cadence; ship only what the numbers justify | the Lucene writer config beyond cadence (lane D owns codec/schema) |

## File ownership (no other wave-1 lane edits these)

`modules/worker-services/.../extract/**` (TimeboxedContentExtractor, ProcessExtractionSandbox,
ExtractionSandboxChild, ExtractionSandboxFactory, DefaultWorkerAppServices sandbox wiring),
`modules/worker-services/.../loop/**` (IndexingLoop, LoopPacingPolicy, BackfillScheduler,
`*BackfillOps` yield sites, JobBatchExtractor), `modules/indexer-worker/.../coordination/MmfWorkerSignalBus.java`
(`isUserActive` only), `modules/indexer-worker/.../queue/**`, `KnowledgeServer.java` queue
construction + health sampler wiring, `modules/worker-services/.../services/GrpcSearchService.java`
(in-flight gauge instrumentation only), `modules/ui/.../api/StatusLifecycleHandler.java`,
`CoreApiAssembly.java` (taps), `modules/app-services/.../worker/KnowledgeServerBootstrap.java`
(`signalUserActivity` retirement), `modules/adapters-lucene/.../CommitOps.java`, `ComponentsFactory.java:324` +
`RuntimeSession.java` NRT stale-time defaults (item 19 only), the affected MetricCatalogs
(`ExtractionMetricCatalog`, `WorkerOpsMetricCatalog`), `modules/app-services/.../gpl/GplJobCoordinator.java`
and `.../worker/RemoteDocumentService.java` (the four `fetchDocuments` callers, granted for the
byte-budget item only **[R6b]**), `modules/app-services/.../worker/KnowledgeServerHealthMonitor.java`
(sampler host **[R8]**), the `systemTest` chaos suite (`ChaosSuiteTest` "Time Lord" rewrite),
`docs/explanation/02-process-coordination.md` §breath-holding, `03-knowledge-server.md`
§extraction + §job queue, `08-observability.md` health sampling.

Lane A owns the *structure* of `configuration/**` (ordinals, contributors, promotions) and the
Head config phase; the `EnvRegistry` enum and `ResolvedConfigBuilder` **append regions are shared**
(cross-lane rule from the 2026-09-01 review), so this lane adds its own keys
(`justsearch.extraction.sandbox.{mode,pool,heap,command}`, `justsearch.health.sample_ms`,
`justsearch.queue.retry.*`) at the end of the enum / builder method without asking. Lane B owns
existing `docs/decisions/**`; this lane creates only **ADR-0048 "Extraction isolation and
indexing pacing"** (number reserved) and lane B indexes it.

## Evidence (verified 2026-09-01 on `main` at 8e148b3b; lane 0 moved some lines)

### Item 14 — extraction

- `TimeboxedContentExtractor.java:41-45,144-162`: single-thread executor, `DEFAULT_TIMEOUT = 60 s`,
  `MIN_TIMEOUT = 5 s`, `future.cancel(true)` on timeout, `extraction.timeout_total` metric. Its own
  javadoc (`:28`): "cancelled (best-effort; native parsers may not respond)" — a wedged PDFBox/POI
  parse ignores the interrupt and holds the executor thread; the next file queues behind it.
- `DefaultWorkerAppServices.java:453-468`: `JUSTSEARCH_EXTRACTION_SANDBOX_MODE` default
  `in_process`; `process` mode requires a non-blank `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND` (`:477`)
  and no shipped argv exists anywhere.
- `ProcessExtractionSandbox.java:19-20,76-90`: spawns **one process per file**, writes a
  `SandboxExtractionRequest` JSON on stdin, reads a bounded response on stdout, bounded stderr.
- `ExtractionSandboxChild.java:16-40`: a real entry point exists (`main` reads all of stdin, runs
  `PolicyDrivenTikaExtractor`, writes one response). It is one-shot: per-file JVM start (AOT
  helps, still hundreds of ms + Tika class-loading) is why nobody shipped it.
- `ContentExtractor.MAX_FILE_SIZE` duplicated in `StructuredContentExtractor.java:43` (equal today).
- **[R7] Severity is larger than first stated:** `TimeboxedContentExtractor.java:122` is a
  `newSingleThreadExecutor`, so one wedged parser stops **all** extraction until Worker restart.
  No existing fixture wedges a parser (the nasty corpus fails fast); the chaos criterion needs a
  synthetic hanging child.
- **[R7] The shipped-command recipe already exists in tests:** `ProcessExtractionSandboxTest`
  has seven stub children (incl. polluted-stdout and sleeping cases) and builds the child command
  from `java.home` + `java.class.path` (`:119-122`). The Worker runs from a plain `-cp lib\*`
  classpath (`WorkerSpawner.java:586-588`), no jlink, so the same pair works in production.
  Polluted stdout is a proven failure mode: the persistent child must capture `System.out` at
  startup and redirect it to bounded stderr, keeping the original stream for frames.
- Metrics: `extraction.sandbox_restart_total` must live in `ExtractionMetricCatalog` (its static
  initializer throws on a foreign prefix); `IndexingPipelineWireFormatRegressionTest.java:57,80-84`
  pins the metric wire format.
- **[R6a] Module boundary:** `WindowsJobObject` lives in `modules/app-util`, which
  `worker-services` does not depend on; the Worker cannot create a nested job object without a
  `/module-arch` change. The Head's job (`WorkerSpawner.java:200,435-437`, kill-on-close, no
  breakaway) already kills the grandchild on Head exit but not on a Worker restart.

### Item 3 — breath-holding

- `MmfWorkerSignalBus.java:215-229`: `isUserActive()` = `activity_epoch_ms` written within 2000 ms.
- **[R1] The eval hatch never reached the Worker.** The reader (`:219`) is `Boolean.getBoolean`
  in the Worker JVM; the only setter is a Gradle `systemProperty` on the Head `JavaExec`
  (`modules/ui/build.gradle.kts:2183`); the key is absent from `WORKER_FORWARDED_PROPS`, is not an
  env var (the `JUSTSEARCH_*` forwarding at `WorkerSpawner.java:418-425` cannot carry it), is not
  an `EnvRegistry` key, and the ordinal-450 snapshot loads into `ConfigStore`, not sysprops. The
  "~5 → ~1 doc/s" figure exists only as a code comment (`MmfWorkerSignalBus.java:217-218`,
  `build.gradle.kts:2181-2182`); tempdoc 326 records no such measurement (326:399 says only
  "disabled breath-holding in runHeadlessEval"). Deleting the hatch is free; **no baseline for
  its effect exists**, so this lane measures fresh.
- **[R2] The status poll does not throttle indexing.** `StatusLifecycleHandler` never calls
  `signalUserActivity` (0 hits). The five writers are `KnowledgeSearchController.java:304` (search),
  `:849` (suggest), `:887` (folders), `:931` (folder-files) and `PreviewController` via
  `CoreApiAssembly.java:110`, through `KnowledgeServerBootstrap.signalUserActivity()` (`:698`) →
  `WorkerSpawner.signalUserActivity()` (`:334-338`). So breath-holding already means "a foreground
  request happened"; the defects are (a) the signal is a Head-written MMF byte (lane F blocker),
  (b) a 2 s wall-clock window instead of in-flight state, (c) a **pause** instead of a bounded
  throttle, which starves indexing under a continuous agent search loop.
- **[R3] Sixteen** main-source call sites, not twelve: `IndexingLoop:605`;
  `BackfillScheduler:239,430,612`; `JobBatchExtractor:128`; `SpladeBackfillOps:236,242`;
  `NerBackfillOps:69,75`; `EmbeddingBackfillOps:187`; `DisambiguationBackfillOps:68,74`;
  `CombinedEnrichmentBackfillOps:591`; `SyncDirectoryOps:227,299`; `GrpcIngestService:1082,1142`.
  Pause verb: `IndexingLoop.java:604-610` (`transitionToPaused`, sleep `BREATH_HOLD_MS = 500`,
  `LoopPacingPolicy.java:8`). `ChaosSuiteTest` "Time Lord" (`:283-346`) asserts the activity
  breath-hold via `harness.writeActivity` and lives in the `systemTest` source set, which
  `gradlew test` does not run; it must be rewritten and run explicitly.
- **MMF residue lane F inherits (state it, do not fix it here):** `main_gpu_active` (byte 24,
  `MmfWorkerSignalLayoutV1.java:44`) has five live readers (`KnowledgeServer.java:996,1632`,
  `IndexingDocumentOps.java:219`, `EmbeddingBackfillOps.java:191`, `BgeM3BackfillOps.java:335`) and
  stays; `IndexStatusOps.java:428` puts `signalBus.readActivity()` on the status wire and must be
  retired with the activity byte.
- Constants unchanged since 2025-11-27 through 627 and 630: `HEARTBEAT_STALE_MS = 5000`,
  `STARTUP_GRACE_MS = 15_000` (`MmfWorkerSignalBus.java:44-48`). Keep them; they are not this
  lane's defect.

### Item 6 — status and health

- `StatusLifecycleHandler.java:406-431`: every `/api/status` hit calls
  `client().getWorkerOperationalView()` = one blocking `IngestService.IndexStatus` unary
  (`RemoteKnowledgeClient.java:767-768`) on the request thread, STANDARD deadline.
- `CoreApiAssembly.java:238-250`: the taps that feed `ConditionStore` + `HealthEventChangeRegistry`
  hang off that handler, so the substrate advances at poll cadence only. Worker→Head streaming
  RPCs are just `SubscribeIndexingJobs`, `ScanRoot`, `InfraDiagnostics.StreamSnapshots`; health is
  not streamed.
- Frontend: one shared poller at 10 s (`statusPoll.ts:27`); 662 collapsed five SSE connections
  into `/api/shell-events/stream` because they starved this poll. A health SSE endpoint exists
  (`ResourceApiModule.java:401`).
- `StatusLifecycleHandler.java:388-393` also reads Head heap per hit; keep, it is cheap.
- **[R8] A scheduler already exists:** `KnowledgeServerHealthMonitor` (app-services,
  `scheduleWithFixedDelay` at `:145`, 10 s default, started at `HeadlessApp.java:509`, already
  handles OS-resume gaps). Ten mock sites across `LifecycleContractTest`,
  `StatusReadinessStalenessTest`, `ReadinessTriggerCompositionTest` stub
  `getWorkerOperationalView()` and need updating.
- **[R6b] `FetchDocuments` overflow is a count problem, not a size problem:** per-document content
  is capped at 200k chars (`GrpcSearchService.java:77,603`), but `GplJobCoordinator.BATCH_SIZE = 50`
  (`:58`) × 200k ≈ 30 MB against the 32 MiB server limit. All four callers are in `app-services`
  (`GplJobCoordinator.java:306,670`, `RemoteDocumentService.java:96,476`).

### Item 21 — job queue

- `SqliteJobQueue.java:46,49,60,62,154-181`: one connection, one `ReentrantLock`, WAL,
  `synchronous=NORMAL`, `busy_timeout=5 s`, `DEFAULT_MAX_ATTEMPTS = 3`; `KnowledgeServer.java:394-401`
  passes a bare `3` (the only construction site). One dequeue caller (`IndexingLoop.java:613`),
  ≥6 enqueue callers on other threads. No throughput metric; 269 §A9 set RISK-002 to "Monitor"
  with the trigger ">2× throughput regression / >30 min bulk imports" and no instrument.
- **[R4] Classification already ships** (the first draft said otherwise): `IngestionRetryPolicy
  {NONE, RETRY_WITH_BACKOFF, DEFER_WITHOUT_ATTEMPT}` and 14 `IngestionOutcomeClass` values in
  `modules/worker-core/.../ingest/`; exponential backoff with jitter capped ~17 min and a
  `retry_after` column (`SqliteJobQueue.java:378,640-652`); `markFailedWithOutcome` (`:779-855`)
  branches on `outcome.retryPolicy()`; catch-site wiring in `JobBatchExtractor.java:250-340`
  (`IOException` → `IO_FAILED` → RETRY, parser failure → NONE); cloud placeholders → DEFER
  (`CloudPlaceholderRecorder.java:61`); `IndexingJobView` carries `attempts` + `retryAfterMs`;
  schema `TARGET_VERSION 9`. **Genuine gaps:** transients still count against `MAX_ATTEMPTS`; the
  ladder caps at ~17 min; `error_message` stores fixed literals ("I/O failure") and the exception
  text reaches only the log.

### Item 19 — cadence

- `ComponentsFactory.java:324-326` `ControlledRealTimeReopenThread(w, mgr, 0.5, 0.05)` hardcoded;
  **[R5] `CommitOps.java:274-279` rebuilds the reopen thread from
  `session.nrtTargetMaxStaleMs / nrtHardMaxStaleMs` after every bulk-backfill suspend/resume**, so
  when `index.nrt.*` is configured (`ResolvedConfigBuilder.java:366,1443`) the cadence silently
  changes after the first backfill. That is a live defect independent of any benchmark.
  `RuntimeSession.java:103-105` "default 50L must match the hardcoded 0.05s" documents the
  coupling by comment only. `CommitOps.java:34` 10 s timer; `ResolvedConfigBuilder.java:1123-1124`
  `commit_interval_ms` 10 000 / `max_docs_before_commit` 1000; `IndexingLoop.java:673-689` the
  trigger. Unchanged since the root commit; 402 fixed writer coordination, never cadence.
  Reopen-on-demand moves the HNSW reopen cost onto the first query after new segments, so the
  benchmark table needs that column.

## Design decisions this lane must make (recommendation in bold)

1. **Sandbox process lifetime.** **Persistent child, length-prefixed request/response frames on
   stdin/stdout, one in flight at a time per child, pool size 1 by default (`justsearch.extraction.sandbox.pool`).**
   On timeout: kill the child, mark the file `FAILED/TIMEOUT` (already a status), respawn lazily.
   On crash: same, with the child's exit code and bounded stderr in the failure reason. The child
   command is built in-process from `java.home` + `java.class.path`, exactly as
   `ProcessExtractionSandboxTest:119-122` already does **[R7]**:
   `<java.home>/bin/java -Xmx<heap> -cp <java.class.path> io.justsearch.indexerworker.extract.ExtractionSandboxChild --serve`;
   `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND` remains an operator override. Reuse the existing JSON
   records; add a `--serve` loop to `ExtractionSandboxChild`; capture `System.out` at child startup
   and redirect it to bounded stderr. **Child heap is load-bearing (T2):** pure-Java parsers loop
   or OOM rather than segfault, so tie the default (`justsearch.extraction.sandbox.heap`) to
   `MAX_FILE_SIZE` (≥ 4× the largest accepted file, floor 512m) and classify a child OOM as a
   permanent parse failure. **Grandchild lifetime without a module change [R6a]:** the Worker
   kills the child in its shutdown hook and the child polls the Worker PID (630's PID-gate
   pattern); no `WindowsJobObject` dependency is added to `worker-services`.
2. **Default routing.** **`process` for PDF, Office, archives, and any OCR route; `in_process` for
   plain text, markdown, code, CSV/JSON.** One switch to force all-process for measurement. Record
   per-family p50/p95 extraction latency before/after; the round-trip cost must stay under 10 ms
   per file for the in-process families to justify keeping the split.
3. **Pacing signal and verb.** **A Worker-local `ForegroundLoad` gauge as a `worker-services`
   type** (so it survives lane F; the gRPC `ServerInterceptor` at `KnowledgeServerGrpcWiring.java:32-34`
   that feeds it is a thin adapter lane F throws away): a counter of in-flight foreground RPCs for
   the search-family methods (Search, Rerank, RetrieveContext, FetchDocuments, FetchDocumentSlice,
   Suggest) plus the existing GPU-active slot. **The verb is a duty cycle, not a pause (T1):**
   while the gauge is >0 the loop runs at a minimum duty (default 20%, key
   `justsearch.indexing.foreground_duty_pct`) between batches, and the cheaper lever of lowering
   ORT intra-op threads under load is tried first for the enrichment backfills; while GPU-active,
   GPU-bound backfills yield as today. Delete `isUserActive`, its **16** call sites,
   `signalUserActivity` and its five callers, `IndexStatusOps.java:428`'s activity field, and the
   dead eval hatch. **Land this as its own PR (T3)** so lane F rebases over one clean commit.
4. **Health sampler [R8].** **Host it on `KnowledgeServerHealthMonitor`'s existing schedule** (no
   new executor): its tick performs the one `IndexStatus` unary and feeds the existing taps;
   `StatusLifecycleHandler` reads the last snapshot + age and never calls the Worker. Default
   period stays 10 s until measured; `?fresh=true` forces one synchronous sample for debug
   tooling. Keep it minimal: under lane F it collapses to a direct call; do not build a
   streaming RPC. **`FetchDocuments` budget [R6b]:** route GPL batches to `FetchDocumentSlice` or
   add a request-level `max_total_bytes`; the proto change waits for the lane F decision (no
   gRPC boundary after a merge), the caller fix does not.
5. **Complete the failure ladder [R4], do not rebuild it.** (a) `RETRY_WITH_BACKOFF` outcomes stop
   incrementing `attempts` against `MAX_ATTEMPTS`; (b) the ladder extends 1 min → 1 h → 6 h →
   24 h, bounded at **7 days**, then a visible terminal state (`RETRY_EXHAUSTED`) that a rescan
   or file change resets; (c) `error_message` stores the exception's message (bounded) instead
   of a literal; (d) the attempts cap lives in one place (`SqliteJobQueue` constant exposed to
   `KnowledgeServer`); (e) metrics `queue.dequeue_rate_per_min` + `queue.enqueue_rate_per_min` in
   `WorkerOpsMetricCatalog`, which is RISK-002's instrument. Schema bump to `TARGET_VERSION 10`
   only if (b) needs a column.
6. **Cadence.** **Fix the coupling first [R5]:** `ComponentsFactory` reads the same
   `nrtTargetMaxStaleMs / nrtHardMaxStaleMs` the rebuild path uses, so configured values apply
   from the first open. Then **decide by measurement**: candidate = reopen on demand (searcher
   refreshed at query time if stale > 1 s) + background reopen every 2 s during bulk indexing;
   commit at 30 s / 5000 docs / idle. The table carries throughput, search p95, **p95 of the
   first search after N new segments**, commit count, reopen count. Ship only what the numbers
   justify.

**Lane F interaction (R9), so nobody builds throwaway work:** survives the merge = the sandbox
pool (more valuable in one JVM), the gauge (as a worker-services type), the queue changes, the NRT
fix. Mostly wasted under a merge = anything beyond the minimal sampler, and a `FetchDocuments`
proto change. Residue lane F must delete = `main_gpu_active` byte + its five readers, the
activity field on the status wire if not removed here.

## Acceptance criteria

- **Chaos (live, `systemTest` source set run explicitly):** a **synthetic hanging child** (no
  existing fixture wedges a parser, R7) → child killed at the timeout, file marked
  `FAILED/TIMEOUT` with the reason, the **next file extracts normally** (this is the assertion
  that the single-thread-executor defect is gone), Worker never restarts. A child crash
  (`kill -9`) → same outcome with the exit code in the reason; a child OOM → permanent parse
  failure. `extraction.sandbox_restart_total` increments and the wire-format regression test is
  updated. Worker shutdown leaves no orphan child (PID poll).
- **Throughput (live, jseval), fresh before/after since no prior baseline exists [R1]:** measure
  `jseval run --pipeline` on the standard corpus (a) alone, (b) with 10 queries/min, (c) under a
  **continuous** MCP-style search loop (T1). Acceptance: (b) within 10% of (a); (c) achieves at
  least the configured minimum duty of (a)'s rate, where today it starves; search p95 in (b) and
  (c) recorded before/after and not regressed by more than 20%.
- **Health (live):** subscribe to `/api/health/events/stream`, issue **no** `/api/status` calls,
  stop the Worker → the stream carries the transition within one monitor period. `/api/status`
  p50 latency drops below 5 ms (no RPC on the request thread) and reports `sampledAt` age. The
  ten mock sites (R8) updated; `LifecycleContractTest` green.
- **`FetchDocuments` (unit):** a 50-document GPL batch of 200k-char documents no longer exceeds
  the client/server limit (slice routing or byte budget), with a test at the caller.
- **Queue (unit + live):** a file failing with an `IO_FAILED` outcome three times stays `PENDING`
  with `retry_after` set and `attempts` unchanged; a parser failure fails on the first attempt;
  after the 7-day bound the job shows `RETRY_EXHAUSTED` and a rescan resets it; `error_message`
  carries the exception text; the throughput metrics appear in `WorkerOpsMetricCatalog` and its
  test.
- **NRT:** a unit test proves the initial reopen thread uses the configured `index.nrt.*` values
  (today it cannot, R5). Then the jseval comparison table (throughput, search p95, first-search
  p95 after N segments, commit count, reopen count) for current vs candidate defaults is in this
  tempdoc; the shipped defaults match the winner.
- `grep -rn "isUserActive\|signalUserActivity\|disable_breath_holding\|readActivity" modules/*/src/main`
  → no hits; `ChaosSuiteTest` "Time Lord" rewritten to the gauge and run.
- Gates: `--gate operation-surface` if any job-lifecycle surface changed; `check-readiness-reason-codes`
  if a reason code was added; MetricCatalog tests; `check-runtime-manifest-closure` if a new
  runtime file appears. Docs regenerated (`/docs-maintenance`): `02-process-coordination.md` no
  longer describes breath-holding as input-driven; `03-knowledge-server.md` describes the pool.
- `./gradlew.bat build -x test`; `:modules:worker-services:test`, `:modules:indexer-worker:test`,
  `:modules:ui:test`, `:modules:system-tests:test` (chaos); full `./gradlew.bat test` before closing.
- Independent review by a session other than the implementer; the reviewer reruns the chaos and
  throughput checks, not just reads the numbers (`static-green ≠ live-working`).

## Verification tier and dev-stack rules

Every live criterion above needs the shared dev stack; jseval campaigns are long holds.
**Default schedule (T5):** lane C holds daytime windows during items 14/3/6; lane A's live checks
run in C's gaps; C's cadence campaign runs detached overnight with self-terminating polls
(agent-lessons: 60-minute task kill). Lease explicitly (`leaseDurationSec`); never take over
another lane's lease. `/jseval` and `/dev-stack` must be loaded before live work; `/module-arch`
before any dependency change (none is planned, R6a).

## Takeover checklist

1. Branch after `882-decision-review-lane0-hygiene` (#592) merges; lane 0 touched
   `WorkerSpawner` (flags), `MmfWorkerSignalLayoutV1`, `KnowledgeServerConfig`.
2. First live act: the fresh throughput baseline (a)/(b)/(c) above, before any code change; there
   is no prior measurement to compare against (R1).
3. Implement in the order 19-fix → 14 → 3 → 6 → 21 → 19-measure: the NRT coupling fix is a
   one-day defect fix that de-risks every later measurement; 14 is the precondition for lane F; 3
   removes the dependency 6 would otherwise have on the poll; 19's cadence change is
   measurement-gated and last. Item 3 ships as its own PR (T3).
4. Before deleting `isUserActive`, grep the 16 call sites (list in the evidence) and replace each
   with the gauge/duty check; `wrong-gate` is the failure mode here — assert in a test that the
   loop throttles when the gauge is >0 and does not on a `/api/status` call.
5. Write the six design decisions into this tempdoc as §B with `path:line` before coding; run the
   post-impl critical-analysis pass; keep the diff inside the ownership list.
6. Independent reviewer at closure is a **named** other session (lane A's or lane B's), and it
   reruns the chaos and throughput checks itself.

## Open questions for the owner

- Pool size 1 is the conservative default; on machines with ≥8 cores a pool of 2 would overlap
  extraction with embedding. Decide after the per-family latency numbers exist, or set it now?
- Transient retries bounded at 7 days then `RETRY_EXHAUSTED` (review's recommendation, adopted)
  versus the first draft's unlimited-with-backoff: confirm the bound, and whether the exhausted
  state should surface in the UI as "could not be indexed" (UI work is out of this lane).
- Minimum indexing duty under continuous foreground load: 20% is a guess to be measured; is a
  user-facing "index faster / index quieter" preference wanted later, or is one default enough?

---

## §B — pre-implementation verification (chunk 1)

Re-read of every `path:line` the Evidence and Design-decision sections cite for items **19, 3 and
14**, against this branch's base `6c3ba431` (lane 0 = 882 merged; the contract's line numbers were
taken on `8e148b3b`). Verified 2026-09-02 by the chunk-1 implementer. Verdict per row:
**OK** = line still says what the contract says it says; **MOVED** = same fact, different line;
**WRONG** = the claim does not hold on this base.

### B.1 Item 19 — cadence

| Contract cite | On `6c3ba431` | Verdict |
|---|---|---|
| `ComponentsFactory.java:324-326` hardcoded `ControlledRealTimeReopenThread<>(w, mgr, 0.5, 0.05)` | `ComponentsFactory.java:324` exactly; `setName`/`setDaemon` at `:325-326` | **OK** |
| `CommitOps.java:274-279` rebuilds the thread from `session.nrtTargetMaxStaleMs / nrtHardMaxStaleMs` | the constructor call is `CommitOps.java:276-279`; `:274` is the `if (session.crtrt != null) return;` guard. `resumeNrtRefresh()` spans `:271-285` | **MOVED** (-2) |
| `ResolvedConfigBuilder.java:366` (`index.nrt.*` configured) | `:366` is `index.commit.meta.enabled`. The NRT keys are contributed at `:367` (`index.nrt.target_max_stale_ms`) and `:368` (`index.nrt.max_stale_ms`) | **MOVED** (+1/+2) |
| `ResolvedConfigBuilder.java:1443` (NRT resolution) | `:1443` is `index.vector.ef_search`. NRT resolves at `:1434` / `:1435` (`resolveNullableInt("index.nrt.target_max_stale_ms")`, `resolveNullableInt("index.nrt.max_stale_ms")`) | **MOVED** (-9/-8) |
| `RuntimeSession.java:103-105` "default 50L must match the hardcoded 0.05s" | exact: field `:103`, comment `:104`, field `:105`. The literal defaults live at `RuntimeSession.java:233-234` (production ctor) and `:288-289` (test ctor) | **OK** |
| `CommitOps.java:34` 10 s commit timer | `COMMIT_TIMER_INTERVAL_MS = 10_000L` at `:34` | **OK** |
| `ResolvedConfigBuilder.java:1123-1124` `commit_interval_ms` 10 000 / `max_docs_before_commit` 1000 | `:1114` / `:1115`; the keys are `justsearch.backfill.commit_interval_ms` / `justsearch.backfill.max_docs_before_commit` (declared `EnvRegistry.java:687,693`), **not** `index.*` | **MOVED** (-9) |
| `IndexingLoop.java:673-689` commit trigger | `:673-689` exactly (`LoopPacingPolicy.isTimeCommitTriggered` / `isBufferCommitTriggered`, `commitOps.commitAndTrack`) | **OK** |
| `ComponentsFactoryTest` "nrt defaults ~264-281" (chunk brief) | `buildReturnsConfiguredNrtValues` `:251-268`, `buildWithNullNrtConfigUsesDefaults` `:270-285` | **MOVED** |

**B.1a — new fact the contract does not state: the config values already reach `ComponentsFactory`,
they just do not reach the thread.** `ComponentsFactory.java:274-278` already resolves
`nrtTargetMs` / `nrtHardMs` (config value when `>= 0`, else the caller-supplied default) and passes
them into the `Components` record at `:313-314` (read-only path) and `:342-343` (read-write path);
`RuntimeSession.java:334-335` copies them onto the session, which is what `CommitOps` then reads.
So the defect is exactly one line — `:324` ignores the two locals computed 46 lines above it — not a
missing plumbing path. `RuntimeSession.openComponents()` (`:528-540`) passes the session's own
`nrtTargetMaxStaleMs`/`nrtHardMaxStaleMs` (500/50, set at `:233-234`) as those *defaults*.

**B.1b — near-WRONG: the two configured values are not order-safe, and the shared helper the chunk
brief asks for would crash on a configuration the repo's own test already uses.** Lucene's
`ControlledRealTimeReopenThread(writer, manager, targetMaxStaleSec, targetMinStaleSec)` throws
`IllegalArgumentException` when arg 3 `<` arg 4 (verified by `javap -c` on
`lucene-core-10.4.0.jar`: `dcmpg; ifge; new IllegalArgumentException; athrow`). The codebase feeds
`nrtTargetMaxStaleMs` as arg 3 and `nrtHardMaxStaleMs` as arg 4, which only works because the
defaults are 500/50 — i.e. the field named "hard **max** stale" is used as Lucene's **min** stale.
The config key behind it is `index.nrt.max_stale_ms`, which reads as the larger bound, and
`ComponentsFactoryTest.buildReturnsConfiguredNrtValues` (`:251-268`) configures exactly that
inversion (`target_max_stale_ms: 200`, `max_stale_ms: 5000`). Under that config today,
`CommitOps.resumeNrtRefresh()` would already throw at the first bulk-backfill resume — a latent
crash the contract does not mention. The chunk-1 helper therefore clamps the tighter bound to
`min(hard, target)` (a no-op at the 500/50 defaults) rather than propagating the inversion into a
new crash site at index open. Recorded so lane C's later cadence work fixes the *naming*, not only
the ordering.

### B.2 Item 3 — breath-holding

| Contract cite | On `6c3ba431` | Verdict |
|---|---|---|
| `MmfWorkerSignalBus.java:215-229` `isUserActive()` = activity within 2000 ms | exact (`:215` signature, `:227-228` the 2000 ms window) | **OK** |
| `MmfWorkerSignalBus.java:219` `Boolean.getBoolean` eval hatch; `:217-218` the "~5 to ~1 doc/s" comment | exact | **OK** |
| `MmfWorkerSignalBus.java:44-48` `HEARTBEAT_STALE_MS = 5000` / `STARTUP_GRACE_MS = 15_000` | `:45` and `:48` | **OK** |
| `modules/ui/build.gradle.kts:2183` sets `justsearch.eval.disable_breath_holding`; comment `:2181-2182` | `:2178`; comment `:2176-2177` | **MOVED** (-5) |
| the key is absent from `WORKER_FORWARDED_PROPS` | `WorkerSpawner.java:71`; a repo-wide grep for `disable_breath_holding` returns exactly two main-source hits (`MmfWorkerSignalBus.java:219`, `modules/ui/build.gradle.kts:2178`) — **[R1] holds**: no forwarding, no env var, no `EnvRegistry` key | **OK** |
| `StatusLifecycleHandler` never calls `signalUserActivity` (**[R2]**) | 0 hits; the five writers are `KnowledgeSearchController.java:304` (inside the `POST /api/knowledge/search` handler declared at `:271`), `:849` (suggest), `:887` (folders), `:931` (folder-files), `CoreApiAssembly.java:110` (Preview) | **OK** |
| `KnowledgeServerBootstrap.signalUserActivity()` `:698` then `WorkerSpawner.signalUserActivity()` `:334-338` | exact (`WorkerSpawner:334-338` is the method incl. the `running.get()` guard and `signalBus.writeActivity()`) | **OK** |
| **[R3]** sixteen `isUserActive` main-source call sites | all sixteen exact: `IndexingLoop:605`; `BackfillScheduler:239,430,612`; `JobBatchExtractor:128`; `SpladeBackfillOps:236,242`; `NerBackfillOps:69,75`; `EmbeddingBackfillOps:187`; `DisambiguationBackfillOps:68,74`; `CombinedEnrichmentBackfillOps:591`; `SyncDirectoryOps:227,299`; `GrpcIngestService:1082,1142` | **OK** |
| pause verb `IndexingLoop.java:604-610`, `BREATH_HOLD_MS` `LoopPacingPolicy.java:8` | exact | **OK** |
| `ChaosSuiteTest` "Time Lord" `:283-346`, `systemTest` source set | `class TimeLordTests` spans `:283-346` exactly (next `@Nested` at `:348`) | **OK** |
| ...that test "asserts the activity breath-hold via `harness.writeActivity`" | the Time Lord test (`workerPausesOnRecentActivity`, `:285-316`) drives it via `mmfHarness.simulateRecentActivity(100)` / `simulateStaleActivity(1000)` and asserts `"PAUSED"`. `harness.writeActivity(now)` at `:451` is a *different* test (`StalePortTests.mmfActivityTimestamps`, class `:407-487`) | **WRONG** (harmless conflation; the rewrite target range is right) |
| `main_gpu_active` byte 24, `MmfWorkerSignalLayoutV1.java:44` | `OFFSET_MAIN_GPU_ACTIVE = 24` at `:44` | **OK** |
| "**five** live readers: `KnowledgeServer.java:996,1632`, `IndexingDocumentOps.java:219`, `EmbeddingBackfillOps.java:191`, `BgeM3BackfillOps.java:335`" | `BgeM3BackfillOps.java:335` calls `shouldYieldGpuBackfill()`, **not** `isMainGpuActive()`. Direct main-source `isMainGpuActive()` readers number **six**: `KnowledgeServer:996`, `KnowledgeServer:1632`, `BackfillScheduler:192`, `EmbeddingProviderLifecycle:169`, `EmbeddingBackfillOps:191`, `IndexingDocumentOps:219`. Two more reach it *indirectly* via the `WorkerSignalBus:118` default `shouldYieldGpuBackfill()` (`BgeM3BackfillOps:335`, `BackfillScheduler:612`) | **WRONG** — the residue lane F inherits is larger than stated |
| `IndexStatusOps.java:428` puts `signalBus.readActivity()` on the status wire | exact | **OK** |

**B.2a — measurement constraint the contract does not state: a breath-hold pause is not observable
in the Worker log at shipped log levels.** The only two pause-adjacent log statements are
`IndexingLoop.java:607` (`log.trace("User active, pausing indexing (breath holding)")`) and
`IndexingLoop.java:1028` inside `setCurrentState` (`log.debug("IndexingLoop state: {} -> {}")`,
reached from `transitionToPaused()` at `:1036`). The Worker's
`modules/indexer-worker/src/main/resources/logback.xml` pins `io.justsearch.indexerworker.loop` to
`INFO` **explicitly** (line 97), so `JUSTSEARCH_LOG_LEVEL=DEBUG` (which only parameterises the
`io.justsearch` logger, line 95) does not lift it, and `POST /api/debug/logging`
(`LogLevelController.java:48-90`) mutates the **Head's** Logback context only — there is no
Head-to-Worker log-level RPC, and the Worker's `LoopState` is not on the jseval timeline
(`scripts/jseval/jseval/timeline.py:13-42`). Consequence for the chunk-1 baseline: the pause
*count* cannot be read from `worker.log`; the observable is the throughput delta between arms
(a)/(b)/(c). Item 3's own acceptance ("assert in a test that the loop throttles when the gauge is
>0") is unaffected — that is a unit assertion, not a log grep — but the lane should add an
INFO-level or metric-level pause counter when it rewrites the pacing verb, or the duty cycle ships
unobservable in the field.

### B.3 Item 14 — extraction

| Contract cite | On `6c3ba431` | Verdict |
|---|---|---|
| `TimeboxedContentExtractor.java:41-45` `DEFAULT_TIMEOUT = 60 s`, `MIN_TIMEOUT = 5 s` | `:41-42` and `:44-45` | **OK** |
| `TimeboxedContentExtractor.java:144-162` `future.cancel(true)` on timeout, `extraction.timeout_total` | `extractArtifact` opens at `:144`; `future.get(...)` `:151`, `future.cancel(true)` + `recordTimeout(file)` `:153-154` | **OK** |
| `TimeboxedContentExtractor.java:28` javadoc "cancelled (best-effort; native parsers may not respond)" | exact | **OK** |
| **[R7]** `TimeboxedContentExtractor.java:122` `newSingleThreadExecutor` | exact | **OK** |
| `DefaultWorkerAppServices.java:453-468` sandbox mode default `in_process`; `:477` requires a non-blank command | mode read at `:456` (`EnvRegistry.EXTRACTION_SANDBOX_MODE.getString("in_process")`), command read at `:470`, the throw at `:477` | **OK** (within the cited ranges) |
| `ProcessExtractionSandbox.java:19-20` bounded response/stderr constants | `DEFAULT_MAX_RESPONSE_BYTES` `:23`, `DEFAULT_MAX_STDERR_BYTES` `:24` | **MOVED** (+4) |
| `ProcessExtractionSandbox.java:76-90` one process per file, JSON request on stdin | `new ProcessBuilder(command).start()` at `:76` | **OK** |
| `ExtractionSandboxChild.java:16-40` one-shot `main` | `main` spans `:16-44` | **OK** |
| design decision 1: "capture `System.out` at child startup and redirect it to bounded stderr" | **already shipped**: `ExtractionSandboxChild.java:17-18` keeps `protocolOut = System.out` and reassigns `System.out` to a UTF-8 `PrintStream` over `System.err`. The `--serve` loop must *preserve* this, not add it | **WRONG** (lists shipped code as new work) |
| `ContentExtractor.MAX_FILE_SIZE` duplicated in `StructuredContentExtractor.java:43` (equal today) | both `100 * 1024 * 1024`: `ContentExtractor.java:38` and `StructuredContentExtractor.java:43`. Note `ContentExtractor` lives in `modules/worker-services/.../extract/`, not `worker-core` | **OK** (with the module correction) |
| **[R7]** `ProcessExtractionSandboxTest:119-122` builds the child command from `java.home` + `java.class.path` | that helper is at `:105-107`; `:114-124` are the `MalformedChild` / `OversizedChild` stubs | **MOVED** (-14) |
| `WorkerSpawner.java:586-588` plain `-cp lib\*` classpath, no jlink | `:584-587` (`cmd.add("-cp"); cmd.add(buildWorkerClasspath(...))`) | **MOVED** (-2) |
| `IndexingPipelineWireFormatRegressionTest.java:57,80-84` pins the metric wire format | `extraction.timeout_total` assertion `:56-58`, the `component=content_extractor` assertion `:80-84` | **OK** |
| **[R6a]** `WindowsJobObject` lives in `modules/app-util`; the Head's job kills the grandchild | `modules/app-util/.../WindowsJobObject.java`; `WorkerSpawner.java:200` `WindowsJobObject.createOrNull()`, `:435-437` `jobObject.assign(process.pid())` | **OK** |

### B.4 Claims that no longer hold (summary)

1. **`main_gpu_active` has six direct readers, not five**, and `BgeM3BackfillOps:335` is not one of
   them (it reads the `shouldYieldGpuBackfill()` default). Lane F's residue list must be corrected.
2. **The Chaos "Time Lord" test does not use `harness.writeActivity`** — it uses
   `mmfHarness.simulateRecentActivity` / `simulateStaleActivity`. The rewrite target range
   (`:283-346`) is right.
3. **`ExtractionSandboxChild` already captures `System.out`** (`:17-18`); design decision 1 lists it
   as work still to do.
4. **A breath-hold pause is not loggable at shipped Worker log levels** (B.2a) — takeover step 2
   asks the baseline to show the mechanism firing, and no log line can show it.
5. **The NRT pair is order-unsafe** (B.1b): a configured `index.nrt.max_stale_ms` greater than
   `index.nrt.target_max_stale_ms` already crashes `CommitOps.resumeNrtRefresh()`.
6. Line drift from `8e148b3b` to `6c3ba431` is otherwise small and mechanical (at most 14 lines),
   except the `ResolvedConfigBuilder` cites, two of which pointed at unrelated keys.

## Baseline (chunk 1, 2026-09-02)

Takeover checklist step 2: the fresh throughput baseline, since **[R1]** established that no prior
measurement of breath-holding's effect exists. Three arms, scifact, ingest-only
(`--max-queries 0`), full pipeline wait, `--start-backend --clean` on port 33221, run sequentially
from the lane-C worktree with port + orphan-process checks between arms.

### Exact commands

Each arm was launched detached (`Start-Process powershell -WindowStyle Hidden`) with
`INSPECT_DISPLAY=none`, `PYTHONUTF8=1`,
`PYTHONPATH=F:/justsearch-public/.claude/worktrees/lane-C/scripts/jseval`, cwd
`<worktree>/scripts/jseval`, writing `arm-<x>.done` with the exit code
(driver: `tmp/885-baseline/arm.ps1`):

```
python -m jseval run --dataset scifact --max-queries 0 --pipeline --start-backend --clean \
  --timeline <tmp>/timeline-a.tsv --output-dir <tmp>/baseline-a --json
python -m jseval run --dataset scifact --max-queries 0 --pipeline --start-backend --clean \
  --timeline <tmp>/timeline-b.tsv --output-dir <tmp>/baseline-b --json --search-load-qpm 10
python -m jseval run --dataset scifact --max-queries 0 --pipeline --start-backend --clean \
  --timeline <tmp>/timeline-c.tsv --output-dir <tmp>/baseline-c --json --search-load continuous
```

`<tmp>` = `F:\justsearch-public\.claude\worktrees\lane-C\tmp\885-baseline` (gitignored; not
committed). Corpus: `scifact`, 5183 BEIR docs materialised to
`scripts/jseval/tmp/eval-corpora/scifact` from the shared `ir_datasets` cache — no repo-root
`datasets/scifact` was needed or created.

### Results

Git SHA `6c3ba431` for all three arms, **plus the uncommitted chunk-1 NRT change in the working
tree**. That change is bit-identical at defaults (§C.3: no `index.nrt.*` is configured in eval, so
the helper is called with 500/50 and Lucene receives the same `(0.5, 0.05)` the removed literals
passed), so these numbers are a valid pre-item-3 baseline.

| | (a) alone | (b) `--search-load-qpm 10` | (c) `--search-load continuous` |
|---|---|---|---|
| `pipeline_timing.primary_indexing.docs_per_s` | **112.6** | **44.1** (39% of (a)) | **never reached** — block absent |
| `primary_indexing.duration_s` / `docs_indexed` | 45.9 s / 5168 | 117.3 s / 5168 | stalled at **699 / 5184** docs |
| `ingest.docs_per_sec` (whole ingest+enrichment window) | 18.2 | 11.6 (64%) | **0.5** (2.7%) |
| `ingest.worker_throughput_docs_per_sec` | absent | absent | absent |
| `stages.embedding_100_pct_at_s` | 231.1 | 378.2 | never |
| `stages.splade_100_pct_at_s` | 274.5 | 387.0 | never |
| `stages.chunk_100_pct_at_s` | 231.1 | 257.5 | never |
| `stages.ner_complete_at_s` (`ner_total_docs`) | 231.1 (5184) | 438.3 (5184) | never (699) |
| `readiness_passed` | true | true | **false** (`backend_unreachable` after the deliberate stop) |
| `search_load.queries_issued` / `errors` | — | 75 / 0 | 5339 / 11 |
| `search_load.latency_ms` p50 / p95 / max | — | 281.8 / 543.0 / 11602.6 | 245.3 / 275.8 / 15913.0 |
| `search_load.duration_s` | — | 448.7 | 1356.0 |
| breath-hold pauses in `worker.log` | **0 (unobservable)** | **0 (unobservable)** | **0 (unobservable)** |

Arm (c) was **deliberately terminated after 22 minutes**, not crashed and not fabricated. The
Head JVM was stopped at 01:32:08 local; jseval detected it (`Backend unreachable after 5
consecutive failures`), unwound normally, stopped the search load and emitted its summary. The
reason for stopping: the arm cannot complete by construction. `timeline-c.tsv` shows the indexed
count reaching 699 at t=14.2 s and then **not advancing for the remaining 1315 s** (603 timeline
rows, all 699); an independent `/api/status` probe every 10 s from 01:21:28 to 01:31:19 read
`worker.core.indexedDocuments = 699` on all 60 samples. Letting the run reach jseval's 7200 s
`index_timeout_sec` would have produced the same "never completed" record 90 minutes later.

### Reading the numbers

* **(b) fails the contract's own acceptance band today.** The criterion is "(b) within 10% of (a)";
  measured, primary indexing is **61% slower** (112.6 → 44.1 docs/s) and the full enrichment window
  36% slower. At 10 queries/minute each query holds `isUserActive()` true for the 2000 ms window at
  `MmfWorkerSignalBus.java:227-228`, i.e. ~2 s of every 6 s, and `IndexingLoop.java:604-610` pauses
  the whole loop for `BREATH_HOLD_MS` each time it observes it.
* **(c) starves completely, as the contract predicted.** Continuous load is ~3.9 queries/s
  (5339 queries in 1356 s), so the 2 s activity window never expires and the loop is paused on
  every iteration. The 699 documents that did land all landed in the first 14 s, while the first
  hybrid queries were still cold (>2 s apart). This is the "0% duty" number that design decision 3's
  20% minimum duty has to beat.
* **The attribution is to the pause gate, not to GPU contention.** Primary indexing is Lucene write
  + extraction (CPU), not GPU work; GPU contention would slow it, not zero it. Arm (c) shows exactly
  zero progress for 22 minutes while the Worker stayed `READY` and answered 5339 searches at a p50
  of 245 ms — a healthy Worker doing no indexing at all.
* **Search latency is better under (c) than under (b)** (p95 276 ms vs 543 ms) because in (c)
  indexing is fully stopped and search has the machine to itself, whereas in (b) search competes
  with an actively indexing loop. Design decision 3's duty cycle will move search p95 in the (b)
  direction; the 20% figure should be chosen against these two poles, and the "not regressed by
  more than 20%" acceptance should be read against **(b)'s 543 ms**, not (c)'s 276 ms.
* **`ingest.worker_throughput_docs_per_sec` does not exist in this run shape.** The contract names
  it; `jseval`'s `ingest` block emits `docs_per_sec` (whole-window) and the throughput figure the
  lane wants is `pipeline_timing.primary_indexing.docs_per_s`. Recorded so the later comparison
  table keys on a field that is actually emitted.
* **The pause count could not be taken from the Worker log** — see §B.2a. All three arms' logs are
  INFO-only (`worker-a.log` 1093 lines; 0 hits for `PAUSED` / `breath` / `IndexingLoop state` in any
  arm), because `IndexingLoop.java:607` is TRACE, `IndexingLoop.java:1028` is DEBUG, and
  `modules/indexer-worker/src/main/resources/logback.xml:97` pins
  `io.justsearch.indexerworker.loop` to INFO. The mechanism firing is instead evidenced by the
  throughput collapse and by the frozen indexed count. **Item 3 must ship an INFO-level or metric
  pause/duty counter**, otherwise the duty cycle is unverifiable in the field and the "after"
  half of this table cannot be attributed either.

Raw artifacts (gitignored, kept on disk for the reviewer to re-read):
`tmp/885-baseline/{summary-a,summary-b,summary-c}.json`, `timeline-{a,b,c}.tsv`,
`worker-{a,b,c}.log`, `arm-c.probe.txt`, `arm.ps1`, `extract.py`.

## §C — post-implementation critical analysis (chunk 1, NRT fix)

Scope of the diff under review: `NrtReopenThreads.java` (new),
`ComponentsFactory.java:324`, `CommitOps.java:267-286`, `RuntimeSession.java:104` (comment),
`ComponentsFactoryTest.java` (+3 tests, +1 helper), `docs/explanation/18-adapters-lucene-deep-dive.md`
§2.2.

### C.1 Wrong-gate: does the helper actually feed BOTH sites?

Checked by grep, not by trusting the edit. After the change, the repository contains exactly
**one** `new ControlledRealTimeReopenThread` expression in main source —
`NrtReopenThreads.java:52` — and exactly **two** callers of `NrtReopenThreads.create`:

```
$ grep -rn "ControlledRealTimeReopenThread(" --include=*.java modules/*/src/main
modules/adapters-lucene/.../runtime/NrtReopenThreads.java:52

$ grep -rn "NrtReopenThreads\.create" --include=*.java modules/*/src/main
modules/adapters-lucene/.../runtime/CommitOps.java:278
modules/adapters-lucene/.../runtime/ComponentsFactory.java:324
```
(the third and fourth hits of the second grep are the two javadoc/comment references at
`CommitOps.java:270` and `RuntimeSession.java:104`, not call sites)

The values each site passes were re-read at the source, not inferred:

* `ComponentsFactory.java:324` passes `nrtTargetMs` / `nrtHardMs`, the locals computed at
  `:274-278` from `idx.nrtTargetMaxStaleMs()` / `idx.nrtHardMaxStaleMs()` with the caller's
  defaults as fallback — the same two values it already put on the `Components` record at
  `:313-314` / `:342-343`.
* `CommitOps.java:278-282` passes `session.nrtTargetMaxStaleMs` / `session.nrtHardMaxStaleMs`,
  which `RuntimeSession.java:335-336` copies **from that same `Components` record**.

So both sites are provably fed by one config resolution, and the "silently changes cadence after
the first backfill" defect cannot recur without a new construction site — which the single-expression
grep above would catch.

**Residual gate risk considered and rejected:** the read-only branch (`ComponentsFactory.java:290+`)
builds no reopen thread at all (`crtrt == null`, asserted by the pre-existing
`buildReadOnlyReturnsNullWriterAndCrtrt` test, `ComponentsFactoryTest.java:174-192`), so there is no third path that could keep the old constants.
`RuntimeSession.java:373` is the only `crtrt.start()`; `CommitOps.resumeNrtRefresh` starts its
own. Both still start exactly once.

### C.2 Test precision: does it pass for the right reason?

The load-bearing assertion is on the **thread's own state**, not on the `Components` record — the
record already carried the configured values before this change (B.1a), so an assertion on
`c.nrtTargetMaxStaleMs()` would have passed against the unfixed code and proved nothing. The tests
read `ControlledRealTimeReopenThread.targetMaxStaleNS` / `targetMinStaleNS` by reflection and
compare against the exact nanosecond products of the configured milliseconds.

Falsification run (the `audit-without-test` discipline): `ComponentsFactory.java:324` was
temporarily restored to `new ControlledRealTimeReopenThread<>(w, mgr, 0.5, 0.05)` and the suite
re-run.

```
ComponentsFactoryTest > initialReopenThreadUsesConfiguredNrtValues() FAILED
ComponentsFactoryTest > initialReopenThreadClampsInvertedNrtBounds() FAILED
> Task :modules:adapters-lucene:test FAILED
```

`initialReopenThreadUsesDefaultsWhenNrtUnconfigured` passed in that run — correctly: it asserts the
*unchanged* 500/50 behaviour, so it must be green on both sides of the fix. That asymmetry (two red,
one green) is the evidence that the new assertions discriminate on the configured value and not on
the mechanism.

### C.3 Behaviour at defaults is bit-identical

`RuntimeSession` seeds 500/50 (`:234-235`, `:289-290`) and passes them as the factory's defaults
(`:529-541`), so with no `index.nrt.*` configured the helper is called with (500, 50), the clamp is
a no-op (`min(50, 500) == 50`), and Lucene receives `(0.5, 0.05)` — the exact literals removed.
`initialReopenThreadUsesDefaultsWhenNrtUnconfigured` pins this.

### C.4 The clamp is new behaviour and is deliberate

The helper's `Math.min` (`NrtReopenThreads.java:42`) is the one behavioural addition beyond the coupling fix, and it is what
makes a *shared* helper safe at all (B.1b): without it, wiring the configured values into the
initial open would turn `ComponentsFactoryTest.buildReturnsConfiguredNrtValues`'s own YAML
(`target_max_stale_ms: 200`, `max_stale_ms: 5000`) into an `IllegalArgumentException` at index open
— converting a latent crash on the *resume* path into an immediate crash on the *open* path. The
clamp WARNs, so an operator who configured an inverted pair learns about it. It is covered by
`initialReopenThreadClampsInvertedNrtBounds`.

**Honest limit:** the clamp fixes the *ordering*, not the *naming*. `nrtHardMaxStaleMs` /
`index.nrt.max_stale_ms` still denotes Lucene's `targetMinStaleSec`, which reads backwards. Renaming
it touches the config key and the `ResolvedConfig.Index` record, which is lane A's structure and
lane C's later cadence item — recorded in B.4 (5) rather than done here.

### C.5 Tri-state / stale-flag / asymmetric-lifecycle checks

* No tri-state lookup added (both values are primitive `long`s with explicit defaults; the
  "unknown" case is `cfgTarget == null`, already handled at `ComponentsFactory.java:274-278` and
  untouched).
* No new flag, so no stale-flag short-circuit.
* No `start()` without `stop()`: the helper only constructs; the two callers keep their existing
  start/close pairing (`RuntimeSession.java:373` / `:568-575`, `CommitOps.suspendNrtRefresh` at `:239-246`).
* WARN dedup: the clamp WARN fires at most once per index open and once per backfill resume, and
  only for a misconfigured pair, so no log flood.

### C.6 Findings

None actionable. One follow-up recorded for a later chunk (C.4's naming limit, already listed as
B.4 (5)).

---

## §SB — pre-implementation verification (chunk 2, item 14)

Every `path:line` the Evidence and Design-decision sections cite for **item 14**, re-read against
this branch's HEAD `fcf7c6e7` (= base `6c3ba431` + chunk 1). Verified 2026-09-02 by the chunk-2
implementer. Same verdict vocabulary as §B.

| Contract cite | On `fcf7c6e7` | Verdict |
|---|---|---|
| `TimeboxedContentExtractor.java:122` `newSingleThreadExecutor` **[R7]** | exact (`this.executor = Executors.newSingleThreadExecutor(r -> {`) | **OK** |
| `TimeboxedContentExtractor.java:144-162` cancel path | `extractArtifact` opens `:144`; `future.get` `:151`, `future.cancel(true)` `:154`, `recordTimeout` `:155`, `throw` `:156-157`; the `InterruptedException` branch `:158-161` | **OK** |
| `DefaultWorkerAppServices.java:453-479` sandbox wiring | method signature `:452-455`, mode read `:456`, command read `:470`, the fail-fast throw `:476-478` | **OK** |
| `ProcessExtractionSandbox.java:76-104` one process per file | `new ProcessBuilder(command).start()` `:76`, stdin write `:90`, `waitFor(timeout)` `:104` | **OK** |
| `ExtractionSandboxChild.main` one-shot `:16-40` | `main` `:16`, `System.out` capture `:17-18`, `readAllBytes` `:19`, response write `:42-43`, method ends `:44` | **MOVED** (the write is `:42-43`, outside the cited range) |
| `ProcessExtractionSandboxTest` child-command recipe `:119-122` | the `javaCommand` helper is `:104-108` (`java.home` `:106`, `java.class.path` `:107`); `:114-122` are the `MalformedChild` / `OversizedChild` stubs. §B.3 already corrected this to `:105-107`; the exact declaration-to-brace span is `:104-108` | **MOVED** (-15) |
| "...and its **seven** stub children" | there are **five**: `MalformedChild`, `OversizedChild`, `PollutedChild`, `SleepingChild`, `EchoOcrConfigChild` (`grep -c "public static final class"` = 5). Six children total if the real `ExtractionSandboxChild` is counted | **WRONG** (count) |
| `ExtractionMetricCatalog.java:22-45` namespace guard | `NAMESPACE` `:22`, `TIMEOUT_TOTAL` `:24`, static initializer `:33-45`; it throws `ExceptionInInitializerError` on a foreign prefix | **OK** |
| `IndexingPipelineWireFormatRegressionTest.java:57,80-84` | `extraction.timeout_total` type assertion `:56-58`; the `component=content_extractor` assertion `:80-84` | **OK** |
| `EnvRegistry.java:512-520` sandbox keys | `EXTRACTION_SANDBOX_MODE` `:512-513`, `EXTRACTION_SANDBOX_COMMAND` `:520-521` | **OK** |
| `WorkerSpawner.java:200,435-437` Job Object | `WindowsJobObject.createOrNull()` `:200`; `jobObject.assign(process.pid())` `:436` (inside the `if (jobObject != null)` at `:435-437`) | **OK** |
| §B.4 (3): `ExtractionSandboxChild:17-18` already captures `System.out` | confirmed verbatim; the serve loop **preserves** it rather than adding it | **OK** (§B.4's correction stands) |

### SB.1 — new facts the contract does not state

1. **`MAX_FILE_SIZE` is not the number the heap default should key on.**
   `ContentExtractor.MAX_FILE_SIZE` is `private static final` (`ContentExtractor.java:38`,
   `100 * 1024 * 1024`) and is not reachable from the sandbox. The *configurable* equivalent is
   `TikaExtractionPolicy.maxInputBytes` (`DEFAULT_MAX_INPUT_BYTES = 100L * 1024 * 1024`,
   `TikaExtractionPolicy.java:35`), which the operator moves via `worker.limits.max_file_size`.
   The heap default is therefore keyed on the *policy*, so raising the accepted file size raises
   the child heap with it: `max(512 MiB, 4 x policy.maxInputBytes())` gives `512m` at defaults
   (4 x 100 MB = 400 MB, below the floor). Design decision 1's "verify MAX_FILE_SIZE" resolves to:
   the two constants agree today, and the policy field is the one to read.
2. **The file-kind classification design decision 2 needs already exists and is public**:
   `IndexingDocumentOps.classifyFileKind(Path, String mimeBase)`
   (`modules/worker-services/.../loop/ops/IndexingDocumentOps.java:505`), returning
   `pdf | image | markdown | office | code | text | archive | binary | unknown`. It is already the
   tag source for `OperationalMetrics.recordDocumentFailed` (`IngestionOutcomeJournal.java:246`),
   so routing on it is a projection of the existing taxonomy, not a second one. **It needs a
   detected MIME**: with `mimeBase == null` it can only return `markdown | code | unknown` — a
   `.pdf` classifies as `unknown`. The router therefore calls
   `ContentExtractorProvider.detectMimeType` first, which is why `RoutingExtractionSandbox` takes a
   provider.
3. **No ArchUnit rule forbids `extract` depending on `loop.ops`.** `BoundaryRulesTest` and
   `LayeringEnforcementTest` treat `io.justsearch.indexerworker..` as one unit, and the repo has no
   package-cycle rule, so the router may call `IndexingDocumentOps` directly.
4. **`IndexerWorkerGuardrailsTest.indexerWorkerMustNotReadEnvOrSystemProperties` blocks
   `System.getProperty` anywhere under `io.justsearch.indexerworker..`** with a four-class allowlist
   — so the shipped child command cannot be built the way the *test* built it
   (`System.getProperty("java.home")` / `("java.class.path")`). This is the one guardrail the
   contract's "the shipped-command recipe already exists in tests" claim does not survive contact
   with; see §SC.5 (3) for how it is resolved.
5. **The config-surface ratchet has headroom.** `env_sysprop_pairs` was 239 before this chunk
   against a pinned 243 (`gates/config-surface/baseline.txt`), so three new keys land at 242 —
   under the pin, `config-surface: pass`, no changeset needed.

## §SC — post-implementation critical analysis (chunk 2, item 14)

Diff under review: `ExtractionSandboxChild` (serve loop + PID gate), `SandboxFrames` (new),
`PersistentExtractionSandbox` (new), `RoutingExtractionSandbox` (new), `ExtractionSandboxCommand`
(new), `SandboxExtractionException` (promoted to top level), `ExtractionSandboxRestartTags` (new),
`ExtractionSandbox` (+`policy()`, +`close()`), `ExtractionMetricCatalog` (+2 counters),
`ExtractionSandboxFactory` (+`AUTO`, +`PoolSettings`), `TimeboxedContentExtractor` (executor
replacement + sandbox close), `DefaultWorkerAppServices` (mode/pool/heap wiring),
`EnvRegistry` (+3 keys), `JobBatchExtractor` + `IndexingLoop` (sweep), plus the deletion of
`ProcessExtractionSandbox` and its test.

### SC.1 Wrong-gate: does the timeout path actually kill the child and release the thread?

Two independent gates had to fire, and each was checked at its set-site rather than assumed.

**(a) The pool's deadline kills the child.** `PersistentExtractionSandbox.extractOnSlot` reads the
response frame on a `readers` pool task and waits with `pending.get(timeout, MILLISECONDS)`. On
`TimeoutException` it calls `discardChild(...)` — which calls `destroyForcibly()` — **before**
`pending.cancel(true)`. The order is load-bearing: `cancel(true)` only interrupts, and a blocking
pipe read on Windows is not interruptible; it is the kill closing the child's stdout that gives the
reader task EOF and un-leaks it. Asserted end-to-end by
`PersistentExtractionSandboxTest.hangingChildIsKilledAtTheDeadlineAndTheNextRequestSucceeds`: the
stub sleeps 600 s, the call returns at the 2 s deadline, `restartCount()` is 1,
`extraction.sandbox_restart_total{reason=timeout}` is 1 on a real `TestMetricRegistry`, the next
request succeeds, and `spawnCount()` is 2 — so the child was genuinely replaced, not reused.

**(b) The in-process executor is replaced — and the first implementation of this gate was wrong.**
The initial version asked `future.isDone()`. That is **true the instant `cancel()` succeeds**, even
while the task thread runs on, so the replacement never fired. The test caught it
(`TimeboxedContentExtractorTest.wedgedExtractionDoesNotPoisonTheExecutor` failed on the *second*
extraction). The gate now reads a flag the task itself sets in a `finally` block, which is the only
signal that answers "did the thread come back?".

**Falsification run** (the `audit-without-test` discipline). `replaceIfWedged`'s guard was
temporarily changed to `if (true) { return; }` — the fix disabled, everything else identical:

```text
TimeboxedContentExtractor > after a wedged extraction times out, the next file extracts normally FAILED
19 tests completed, 1 failed
```

Exactly one failure, and the pre-existing `extractTimesOutDeterministically` stayed green — so the
new assertion discriminates on the executor-replacement behaviour, not on the timeout mechanism.
Guard restored; suite green.

**Residual, stated rather than papered over.** The wedged thread is not killable from the JVM.
`shutdownNow()` re-interrupts and stops new work reaching it, but a native parser ignoring the
interrupt keeps that daemon thread (and its parser's memory) until the process exits. Only the
child-process path reclaims the work. This is exactly why item 14's answer is *routing*, not "fix
the executor" — recorded in `TimeboxedContentExtractor`'s class javadoc so the next reader does not
mistake the executor fix for a full solution.

### SC.2 Wrong-gate: does the routing switch fire for the extensions claimed?

Checked against **real files through the real MIME detector**, not against extension strings,
because `classifyFileKind` keys on the detected MIME first (SB.1 (2)) — asserting on extensions
would have been the classic "passes for the wrong reason".
`ExtractionRoutingTest.pdfAndOfficeAndArchivesGoOutOfProcessAndTextStaysInProcess` drives a real
`.pdf`, real `.docx`/`.xlsx`/`.pptx` fixtures, a real ZIP built in the test, and five text-family
files, and asserts the exact ordered list each side saw plus "no text file crossed the process
boundary". The `.json` case is worth naming: it classifies as `code` (not `text`), because
`isCodeExtension` lists `.json`. It still routes in-process, which is what design decision 2 asks
for — but for a different reason than the label suggests.

**What the gates do NOT do was checked too.** `auto` is now the *default*
(`DefaultWorkerAppServices.buildContentExtractor`, `EXTRACTION_SANDBOX_MODE.getString("auto")`), so
an unconfigured install changes behaviour — that is the intent of item 14 ("make it the default for
the parser families that can wedge"), and it is stated in `environment-variables.md` and
`03-knowledge-server.md`. `parseSandboxMode` still throws on an unknown value rather than silently
falling back, so a typo is loud.

**Mode-switch test precision.** The mode assertion deliberately does not use a getter for "which
sandbox did the factory build" — a getter would let the test pass while the wiring was wrong. It
asserts an observable consequence instead: `IN_PROCESS` tolerates an empty child command, while
`PROCESS` and `AUTO` both throw `IllegalArgumentException` because they construct a child pool.

### SC.3 Test precision — right reason vs wrong reason

* **The stub-name collision the first run exposed.** The follow-up files were originally named
  `after-hang.txt` / `after-crash.txt`, and `ScriptedChild` branches on `name.contains("hang")` /
  `contains("crash")` — so the "next request succeeds" cases were re-triggering the *failure*. Both
  tests failed, correctly. Renamed to `next-after-timeout.txt` / `next-after-exit.txt`. Worth
  recording: the tests failed for a real reason, and the reason was in the test, not the code.
* **OOM is a real OOM, not a simulated stderr string.** `childHeapExhaustionIsAPermanentParseFailure`
  launches the stub with `-Xmx64m` and has it retain 8 MB blocks until the JVM dies, so the
  `OutOfMemoryError` signature the parent classifies on is the JVM's own. The assertion is on the
  exact class (`assertEquals(ContentExtractor.ExtractionException.class, failure.getClass())`), not
  `instanceof` — `SandboxExtractionException` *extends* `ContentExtractor.ExtractionException`, so
  an `instanceof` assertion would have passed on the retryable path too and proven nothing about
  permanence.
* **"One child serves three requests" asserts the PID**, not just three successes — three successes
  are also consistent with three spawns, which is the behaviour this chunk replaces.
* **The request-budget test asserts the PID *changed*** after the budget and that `spawnCount()`
  went to 2 — the leak guard firing, not merely not-crashing.
* **The benchmark asserts almost nothing, on purpose.** It first asserted `overhead >= 0`, which
  failed in the full suite the moment the process path came out *faster* than in-process under load.
  That was a wrong assertion, not a flaky machine: it encoded "was measured" as "is non-negative".
  It now asserts only that both paths were measured and prints the overhead; the 10 ms criterion is
  judged from the recorded table (SB.4 below).

### SC.4 Tri-state / stale-flag / asymmetric-lifecycle checks

* **Asymmetric lifecycle — the one this chunk could have got wrong.** `ExtractionSandbox` gained a
  `close()`, and the pool spawns processes, so "who kills the children" is a real question with
  three answers, all wired: (i) `IndexingLoop.close()` already closed the `TimeboxedContentExtractor`
  (`IndexingLoop.java:1083-1085`), which now closes the sandbox; (ii) the pool registers a JVM
  shutdown hook, removed in `close()` so a per-test sandbox does not accumulate hooks; (iii) the
  child polls its parent PID and halts, which is the only one that survives a `kill -9` of the
  Worker. Asserted by `childExitsWhenItsParentPidIsGone`, which holds the dead process's handle for
  the test's lifetime so Windows cannot recycle the PID underneath the assertion.
* **stderr draining is a liveness requirement, not diagnostics.** An undrained stderr pipe fills its
  OS buffer and wedges the child mid-parse — which would look exactly like the hang the sandbox
  exists to prevent. `StderrTail` drains continuously on a daemon thread into a bounded buffer.
* **The OOM classification races the drain thread.** `destroyForcibly()` + `waitFor` returns before
  the drain thread has necessarily seen EOF, so reading the tail immediately would classify a heap
  exhaustion as an ordinary crash. `discardAndClassify` joins the drain thread (bounded, 2 s) before
  reading. Found by inspection, then confirmed by the OOM test passing.
* **No tri-state added.** `slot.child` is `Child | null`, where null means "spawn lazily"; there is
  no third "unknown" state to conflate with healthy.
* **WARN dedup.** The recycle WARN fires once per discarded child, bounded by the request budget
  (default 500 requests per line) — not per file.

### SC.5 Deliberate deviations from the chunk brief

1. **No `--serve` flag.** The brief asks for `ExtractionSandboxChild --serve`. Because the one-shot
   mode is retired (below), a mode flag with exactly one mode is residue, so the child always
   serves. The parent still appends `--parent-pid=<pid>`.
2. **One-shot mode deleted, and `ProcessExtractionSandbox` with it.** The brief allowed "keep the
   one-shot mode working for the existing tests, **or migrate them deliberately**". Migrated: the
   pool is the only construction site for a child, so a one-child-per-file sandbox plus a one-shot
   child mode would be exactly the residue `retire-with-a-sweep` prohibits. The sweep:
   `ProcessExtractionSandbox.java` and `ProcessExtractionSandboxTest.java` deleted;
   `SandboxExtractionException` promoted to a top-level type (it is the boundary contract
   `JobBatchExtractor` classifies on, so it must not be nested inside whichever implementation
   throws it); `JobBatchExtractor.java` import + catch updated; a dead import removed from
   `IndexingLoop.java:23`; javadoc references relabelled in `ExtractionArtifact`,
   `ExtractorContributionRegistry` and `StdioMcpTransport`; the historical CI-incident comment in
   `JvmBaseConventionsPlugin.kt` labelled with the test's new name rather than rewritten, since it
   records a dated event. `grep -rn ProcessExtractionSandbox` outside `docs/tempdocs/` now returns
   only that labelled history comment.
3. **`ExtractionSandboxCommand` added to the `IndexerWorkerGuardrailsTest` allowlist** (SB.1 (4)).
   Before allowlisting, the surface was minimised so the exemption covers as little as possible: the
   classpath comes from `ManagementFactory.getRuntimeMXBean().getClassPath()` and the launcher from
   `ProcessHandle.current().info().command()` — the latter is strictly better than reconstructing
   `java.home` + `os.name`, since it is the *actual* running binary (the trap tempdoc 696 fixed for
   `WorkerSpawner`). One `System.getProperty("java.home")` remains, in the fallback for a platform
   where `ProcessHandle.Info#command()` is empty, and even that picks `java` vs `java.exe` by file
   existence rather than by parsing `os.name`. The exemption is the same class as the existing
   `TikaOcrRuntime` one — JVM/environment self-discovery, not user configuration — and is justified
   inline in the test.
4. **Leak guard is a request budget, not an RSS probe.** The brief offered either; a request count
   needs no platform-specific memory API and is deterministically testable. Key
   `justsearch.extraction.sandbox.max_requests`, default 500.
5. **No `-XX:+ExitOnOutOfMemoryError` on the child**, although it would give a deterministic exit
   code. The JVM writes that flag's "Terminating due to java.lang.OutOfMemoryError" message to the
   process's **real stdout**, which is the protocol channel — it would corrupt a frame.
   Classification is taken from the stderr tail instead, where the default uncaught-exception
   handler writes.

### SC.6 Findings

None actionable beyond what is recorded above — SC.1 (b) and SC.3's stub-name collision were both
found and fixed inside this chunk. Two follow-ups belong to later chunks and are listed in the
live-window section.

## SB.4 — per-family extraction latency (unit-level, chunk 2)

`ExtractionSandboxLatencyBenchmarkTest`: 3 warmup + 15 samples per family per path, one persistent
child, repo fixtures plus two rows from the tempdoc-410 adversarial corpus. **Both runs were taken
with another agent worktree building concurrently**, so absolute values are noisy and only the
in-process vs process *delta* on the same row is meaningful.

Run 1 (2026-09-02, single-test run):

| family | in_process p50 | in_process p95 | process p50 | process p95 |
|---|---|---|---|---|
| text | 7.07 ms | 13.47 ms | 7.29 ms | 11.93 ms |
| markdown | 3.15 ms | 4.61 ms | 4.34 ms | 5.24 ms |
| code | 14.00 ms | 23.04 ms | 9.72 ms | 18.32 ms |
| json | 4.70 ms | 5.81 ms | 4.45 ms | 7.51 ms |
| nasty_long_line | 6.95 ms | 8.55 ms | 8.14 ms | 9.91 ms |
| nasty_empty | 0.31 ms | 0.34 ms | 2.23 ms | 2.67 ms |
| pdf | 7.62 ms | 11.99 ms | 7.16 ms | 10.02 ms |
| office_docx | 12.14 ms | 16.22 ms | 14.18 ms | 16.35 ms |
| office_xlsx | 6.90 ms | 12.42 ms | 7.71 ms | 16.10 ms |
| office_pptx | 10.11 ms | 15.31 ms | 9.27 ms | 12.01 ms |

Run 2 (2026-09-02, full `:modules:worker-services:test` run, heavier load):

| family | in_process p50 | in_process p95 | process p50 | process p95 |
|---|---|---|---|---|
| text | 11.58 ms | 18.16 ms | 10.82 ms | 13.71 ms |
| markdown | 6.91 ms | 9.52 ms | 5.96 ms | 8.96 ms |
| code | 13.61 ms | 21.51 ms | 10.80 ms | 20.68 ms |
| json | 5.01 ms | 17.21 ms | 7.26 ms | 15.22 ms |
| nasty_long_line | 8.99 ms | 12.17 ms | 9.17 ms | 13.02 ms |
| nasty_empty | 0.33 ms | 0.43 ms | 2.25 ms | 2.83 ms |
| pdf | 9.74 ms | 13.19 ms | 8.41 ms | 10.98 ms |
| office_docx | 22.18 ms | 31.39 ms | 15.68 ms | 22.23 ms |
| office_xlsx | 11.07 ms | 19.48 ms | 15.89 ms | 32.59 ms |
| office_pptx | 13.91 ms | 26.64 ms | 12.75 ms | 15.55 ms |

**Reading the numbers (per `interrogate-results`).**

* **Design decision 2's criterion is met with room to spare.** The round-trip cost is the
  `nasty_empty` row — the only row where the parser does essentially no work, so the measurement is
  almost pure IPC: **+1.92 ms in run 1 and +1.92 ms in run 2**, against a 10 ms budget, and
  identical across two different machine loads, which is what a fixed per-call cost should look
  like. The text row's delta (+0.22 ms run 1, **-0.76 ms** run 2) sits inside the noise of the parse
  itself and must not be read as "IPC is free"; that is why `nasty_empty` is the row that answers
  the question.
* **The negative deltas are noise, not a speedup.** A child JVM cannot parse faster than the same
  Tika in the same process. Rows like `code` (14.00 to 9.72) and `office_docx` (22.18 to 15.68) are
  the *in-process* number being inflated by the test JVM's own GC and by the concurrent build; the
  child has a dedicated 512 MB serial-GC heap and its own core. That is a real but incidental effect
  of the measurement setup, not a property to claim.
* **What this does NOT measure.** Child spawn cost is excluded (3 warmup requests amortise it, and
  the pool spawns once per 500 files by design), and so is Tika class-loading in the child. Spawn
  cost is a live-window number, not a unit-test one.
* **The split is justified on cost, and would have been justified anyway on correctness.** ~2 ms per
  file across a text-heavy corpus is real (5 000 text files is about 10 s), and the text families
  have no wedge or OOM exposure to buy with it.

## Live/chaos-window acceptance items still open (item 14)

Nothing below can be checked without the shared dev stack or the `:modules:system-tests` chaos
source set, which this chunk was scoped out of. Listed so the orchestrator can schedule one window.

1. **Synthetic hanging child under the chaos harness** — the `ChaosSuiteTest` case the acceptance
   criteria ask for: a real wedged parser (no existing fixture wedges one; the nasty corpus fails
   fast), file marked `FAILED/TIMEOUT` with the reason, **the next file extracts normally**, and
   **the Worker never restarts**. The unit-level halves are green
   (`hangingChildIsKilledAtTheDeadlineAndTheNextRequestSucceeds`,
   `wedgedExtractionDoesNotPoisonTheExecutor`); the Worker-never-restarts half is live-only.
2. **Orphan check on Worker shutdown** — kill the Worker (graceful and `kill -9`) with a child
   mid-parse and assert no `ExtractionSandboxChild` process survives. The PID gate is unit-tested
   against a dead PID; the Worker-shutdown wiring end of it is not.
3. **Real-corpus routing + throughput** — `jseval run --pipeline` on the standard corpus with
   `mode=auto` vs `mode=in_process`, to price the split on a real mixed corpus (including the child
   spawn the unit benchmark excludes) and confirm no ingestion regression.
4. **Child OOM against a real hostile document**, rather than the unit test's deliberate allocator.
5. **`extraction.sandbox_restart_total` observed on the live metrics stream** — the wire format is
   pinned by `IndexingPipelineWireFormatRegressionTest`, but no live run has emitted it yet.
