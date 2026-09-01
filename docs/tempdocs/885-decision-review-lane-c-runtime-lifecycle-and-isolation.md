---
status: CONTRACT — ready for takeover (not started)
created: 2026-09-01
updated: 2026-09-01
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
