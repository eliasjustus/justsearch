---
status: IN PROGRESS — chunks 1, 2, 2b, 2c, 2d, 3 and 4 landed (item 19 NRT fix + baseline; item 14 extraction pool + chaos tier green + argfile fallback + review fixes; item 3 foreground duty cycle; items 6 + 21 internal health sampler + bounded retry ladder, live arms pending); item 19-measure open
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
   records a dated event. Stated accurately: `grep -rn ProcessExtractionSandbox` outside
   `docs/tempdocs/` still returns **four** hits, all deliberate history in prose —
   `ExtractionSandboxChild:20`, `ExtractionSandboxCommand:19`, `SandboxExtractionException:9` and
   the `JvmBaseConventionsPlugin.kt` incident comment. No code references the retired type; the
   claim is "no live referencer", not "no occurrences".
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

> **SUPERSEDED by §SC-chaos (chunk 2b, 2026-09-02).** Items 1, 2, 4 and 5 below are closed —
> the chaos tier ran green (14/14) and its evidence is recorded there. Only the real-corpus
> throughput arm (item 3) is still open, plus the gap §SC-chaos records under "what the chaos
> tier did NOT cover". This section is kept as the dated list the chunk was planned against.

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

## §SC-chaos — chaos tier, 2026-09-02 (chunk 2b)

Closes the item-14 acceptance block "**Chaos (live, `systemTest` source set run explicitly)**".
Live window granted for the chaos tier only: no dev-runner stack, no jseval backend, no
`ai_activate`. Run from the lane-C worktree while another agent worktree built concurrently under a
separate Gradle home.

### Commands and results

```
./gradlew.bat :modules:indexer-worker:installDist -PskipWebBuild=true
./gradlew.bat :modules:system-tests:systemTest --tests '*Chaos*' \
    -PincludeSystemTests=true -PskipWebBuild=true
```

Final run — **14 tests, 0 failures, BUILD SUCCESSFUL in 4m 23s**. The two new cases, and the
twelve pre-existing `ChaosSuiteTest` cases that share the tier, all green; **no residue**:

| Test | Result | Time |
|---|---|---|
| `ExtractionSandboxChaosTest` › Hanging, crashing and OOM children are each contained; the next file indexes and the Worker never restarts | PASSED | 205.5 s |
| `ExtractionSandboxChaosTest` › Killing the Worker mid-parse leaves no orphaned extraction child | PASSED | 2.3 s |
| `ChaosSuiteTest` › Watchdog (2), Time Lord (1), Disconnector (2), Signal Noise (1), Stale Port (3), Protocol Stress (1), Harness Unit (2) | 12 PASSED | — |

The narrower class alone (`--tests '*ExtractionSandboxChaosTest*'`) runs in **3m 30s**; the whole
suite in 4m 23s.

### The harness, and why it is shaped this way

`ExtractionSandboxChaosTest` boots a **real Worker distribution** and substitutes only the child's
*parser*, through the production `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND` operator override pointing
at `ChaosExtractionSandboxChild` (`modules/system-tests/src/systemTest/.../chaos/`). So the parent
under test is production `PersistentExtractionSandbox` code end to end.

* **Why a stub parser at all.** [R7] is right that no existing fixture wedges a parser — the
  tempdoc-410 adversarial corpus fails fast — so a wedge has to be synthesised. The stub branches on
  the requested file's name (`chaos-hang` / `chaos-crash` / `chaos-oom`) and runs the **real**
  `PolicyDrivenTikaExtractor` for anything else, so "the next file extracts normally" is a real
  extraction reaching the real index, not a canned answer.
* **`JUSTSEARCH_EXTRACTION_SANDBOX_MODE=process`** is set because under the shipped `auto` default a
  `.txt` file is parsed in-process and would never reach the pool.
* **Two things are production code, not copies.** The orphan gate — `ExtractionSandboxChild
  .startParentWatchdog(String[])` was made public precisely so the stub runs *that* code; a copied
  watchdog would make the orphan assertion prove nothing about production. And the response records.
  The **frame codec is deliberately re-implemented** in the stub: an independent implementation of
  the wire format is stronger evidence that the format is real than reusing one codec on both ends.
* **`WorkerProcessManager.withEnv(name, value)`** is new. `withJvmArgs` splits on whitespace to build
  the JVM argument list, so a whitespace-separated argv like the sandbox command cannot be passed as
  a `-D` property at all.
* **Harness limit worth recording:** `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND` is whitespace-split by
  the Worker, so an operator whose JDK or classpath path contains a space cannot use it inline. The
  harness works around it with a JVM `@argfile` and asserts the JDK path is space-free rather than
  failing obscurely. A quoted-argv form for that key is a real (small) gap; it belongs to whoever
  next touches the key, not to this chunk.

### The defect the chaos tier found (and the unit tests could not)

Run 2 failed with `extraction.sandbox_restart_total{reason=timeout} never reached the metrics file;
observed reasons=[crash, crash, …]`. The Worker log said exactly what happened:

```
Extraction sandbox child spawned (pid=3888)
Extraction executor thread wedged past the timeout; replaced with generation 1
Extraction timeout for chaos-hang-1.txt (total timeouts: 1)
Extraction sandbox child recycled (reason=crash, pid=3888)
```

**Both layers enforced the same 60 s deadline, and the outer one starts its clock first, so it always
won.** `TimeboxedContentExtractor` timed out, `replaceIfWedged` called `shutdownNow()`, that
**interrupted** the pool's `pending.get(...)`, and the `InterruptedException` branch recycled the
child as `crash`. Consequences, both real:

1. The pool's own kill-at-the-deadline path — the mechanism design decision 1 exists to provide —
   **never ran in production shape**. The child was still killed (by the interrupt branch), so the
   containment property held; but it held by accident, through a path labelled as a crash.
2. `extraction.sandbox_restart_total{reason}` was **wrong**: a deadline kill was indistinguishable
   from a child that died on its own, which is exactly the discrimination the `reason` tag exists for.

Why the unit tests missed it: they drive `PersistentExtractionSandbox` **directly**, with no timebox
around it, so the race cannot occur there. A textbook `static-green ≠ live-working`.

**Fixes (production, not test tweaks):**

* `ExtractionSandboxFactory.PROCESS_TIMEBOX_GRACE = 15 s` — when a child pool is in play the outer
  timebox waits the sandbox deadline **plus** a grace that covers the kill itself (`destroyForcibly`
  + a 5 s `waitFor` + a 2 s stderr-drain join, plus slack). The sandbox owns the deadline; the
  timebox is now what it was always meant to be — a backstop for a sandbox that itself wedges.
* `PersistentExtractionSandbox.REASON_INTERRUPTED` — an interrupted wait is not a crash. It still
  kills the child (correct), but it is tagged for what it is.

**Regression test + falsification.** `PersistentExtractionSandboxTest
.factoryLetsTheSandboxDeadlineFireBeforeTheTimeboxBackstop` builds the extractor *through the
factory*, as production does, and asserts `reason=timeout` is 1 and `reason=interrupted` is 0.
Restoring `Duration backstop = effectiveTimeout;` reds exactly that one test
(`12 tests completed, 1 failed`). One subtlety the first attempt got wrong and the falsification
caught: with a 2 s timeout the test passed even with the fix removed, because
`TimeboxedContentExtractor.MIN_TIMEOUT` (5 s) clamps the timebox up and it beats a 2 s sandbox
deadline for the wrong reason. The test now uses 6 s, above `MIN_TIMEOUT`, and discriminates.

### Acceptance evidence (final run, `run-1788310605515`)

Worker log of the hang/crash/OOM sequence, in order:

```
child spawned (pid=26436) → recycled (reason=timeout, pid=26436)      [x3, the retry ladder]
child spawned (pid=29692) → recycled (reason=crash,   pid=29692)      [x3]
   "Extraction sandbox failed for" + "exited with code 3"
child spawned (pid=8436)  → recycled (reason=oom,     pid=8436)       [x1, no retry]
   "Content extraction failed for" + "exhausted its heap"
child spawned (pid=29564)                                             [serves after-oom.txt]
```

* **Child killed at the timeout, file `FAILED` with the timeout reason.** `failure.lastFailedPath`
  is the hanging file (compared case-insensitively — the Worker lower-cases paths on Windows) and
  `failure.lastFailedErrorMessage` contains "timed out". `reason=timeout` reached the live metrics
  file — which also closes the separate open item "`extraction.sandbox_restart_total` observed on a
  live metrics stream": it is read from a real Worker's
  `<dataDir>/telemetry/metrics-worker.ndjson`, not from a unit registry.
* **The next file extracts normally**, after each of the three failure classes: three healthy files
  submitted and indexed (`awaitIndexing`), asserted per scenario and again at the end.
* **The Worker never restarts.** Its PID is re-read from the gRPC health surface after every
  scenario and asserted unchanged (4 assertions), plus `isProcessAlive` at the end. Independently:
  `"Knowledge Server started"` appears **exactly once** in that run's `worker.log` — one boot for
  the whole sequence.
* **Crash carries the exit code**: `"exited with code 3"` in the failure reason, via the retryable
  `SandboxExtractionException` branch.
* **OOM is a permanent parse failure**: the assertion is on *which catch clause ran*, because that
  IS the retry policy — `"Content extraction failed for"` is `JobBatchExtractor`'s plain
  `ExtractionException` branch (`PARSER_FAILED` + `IngestionRetryPolicy.NONE`), and the test also
  asserts the OOM did **not** take the `"Extraction sandbox failed for"` (RETRY_WITH_BACKOFF) branch.
  Corroborated behaviourally: timeout and crash each produced three attempts, the OOM exactly one.
  The OOM is a real JVM heap exhaustion in a real child (`-Xmx128m`, 8 MB blocks), not a simulated
  stderr string.
* **Worker shutdown leaves no orphan child.** The child is wedged **mid-parse** — the state where
  the stdin-EOF exit cannot help, because the child is not reading stdin, so only the parent-PID
  gate can reap it. The Worker is then `forceKill`ed (not shut down gracefully), so the assertion
  cannot pass on the shutdown-hook path. The child's PID is captured from
  `ProcessHandle.of(workerPid).descendants()` while the Worker is alive, and asserted gone within
  30 s of the Worker's death.
* **Post-run sweep:** no `java.exe` matching `IndexerWorker` / `ExtractionSandboxChild` /
  `ChaosExtractionSandboxChild` / the chaos data dirs survives the run.

### Test-shape notes

* **Retry interference is real and had to be handled.** `PARSER_TIMEOUT` and `SANDBOX_FAILED` are
  `RETRY_WITH_BACKOFF`, and with `pool=1` a retrying hang re-wedges the only child for another whole
  deadline, so the next file queues behind the retry ladder. Each chaos file is deleted once its
  failure has been recorded (`retireChaosFile`), which keeps the test measuring containment rather
  than the ladder. The ladder itself is item 21's subject.
* **A test bug the first run caught, worth recording because it is the same species as the unit
  chunk's:** the follow-up files were named `after-hang.txt` / `after-crash.txt`, and the stub
  branches on `contains("hang")` / `contains("crash")` — so the "next file succeeds" cases were
  re-triggering the failure. Renamed to `after-timeout.txt` / `after-crash.txt` with the stub
  markers changed to `chaos-hang` / `chaos-crash`.
* **`lastFailedErrorMessage` does not carry the exit code** — `IngestionOutcomeJournal` stores a
  fixed literal ("Sandbox failed"), so the exit code is only in the Worker log. That is item 21's
  documented gap (c) ("`error_message` stores fixed literals … the exception text reaches only the
  log"), not a defect of this chunk; the chaos test therefore asserts the exit code against the log
  and the outcome against the wire.

### Item 14 acceptance: what is closed and what remains

**Closed by this run:** synthetic hanging child → child killed at the timeout, file `FAILED/TIMEOUT`
with the reason, next file extracts normally, Worker never restarts · child crash → same with the
exit code · child OOM → permanent parse failure · `extraction.sandbox_restart_total` increments and
the wire-format regression test is updated · Worker shutdown leaves no orphan child.

**Still open (needs the dev-stack window, not the chaos tier):** (a) **the shipped default child
command and `auto` routing have never been launched from a real Worker dist** — the chaos harness
overrides `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND` and forces `mode=process`, so
`ExtractionSandboxCommand.defaultCommand` and the per-family router are covered by unit tests and
by nothing live; the real-corpus arm closes both. (b) the real-corpus comparison —
`jseval run --pipeline` on the standard corpus with `mode=auto` vs `mode=in_process`, to price the
routing split on a real mixed corpus *including* the child spawn the unit benchmark excludes, and to
confirm no ingestion regression. Optional refinement: an OOM driven by a genuinely hostile document
rather than a deliberate allocator.

## §SC-argfile — chunk 2c: the child command must not assume a short classpath (2026-09-02)

### The report

Lane C3's worker and lane B's full-suite run both hit
`java.io.IOException: Cannot run program java.exe: CreateProcess error=206, The filename or
extension is too long` in `PersistentExtractionSandboxTest` and
`ExtractionSandboxLatencyBenchmarkTest` (and, on `main`, in the retired
`ProcessExtractionSandboxTest`) when Gradle ran under an isolated
`GRADLE_USER_HOME=C:\Users\Elias\AppData\Local\Temp\jsgh-C`.

**Production was never at risk and still is not**: `WorkerSpawner.buildCommand` launches the Worker
with `-cp lib\*` (`WorkerSpawner.java:584-587`), a wildcard the launcher expands itself, so the
Worker's own `java.class.path` is a handful of characters. **But `ExtractionSandboxCommand` must not
depend on that.** It copies whatever classpath the running JVM reports onto a child command line, so
any embedder with an expanded classpath — a Gradle test JVM, and an isolated Gradle home in
particular, where every cache entry carries a ~24-character-longer prefix — pushes it past Windows'
32,767-character `CreateProcess` limit. The dependency was invisible because the one environment
that mattered happened to be short.

### The fix

`ExtractionSandboxCommand` now chooses between two forms by length
(`MAX_INLINE_COMMAND_CHARS = 30_000`, leaving margin for the launcher path, the main class, the
`--parent-pid` the pool appends, and per-argument quoting):

* **inline**, unchanged, when the assembled command line fits;
* **argfile** otherwise — the JVM options (including `-cp <classpath>`) are written to a JDK
  `@argfile` and the command becomes `java @<file> <main>`, which is a few hundred characters
  regardless of classpath size.

One argfile is written per built command and reused by every child the pool spawns from it. It
cannot be deleted on child exit — the pool respawns children throughout the Worker's life and they
all read the same file — so it is a temp file registered for deletion at Worker exit. In practice
`DefaultWorkerAppServices` builds the command once, so that is one file per Worker.

`PersistentExtractionSandboxTest.javaCommand` was making the *same* mistake for its stub children,
so it routes through the same two production primitives (`commandLineLength` / `writeArgFile`)
rather than growing a parallel copy.

### Argfile encoding: what is actually true, measured rather than assumed

The first version of this work asserted in a comment that "the backslash is an escape character
inside a quoted token, so an unescaped `C:\dir` loses its separators". **The falsification run
refuted that as written**: with the escaping removed entirely, the real-child test — which launches
the production child from an argfile whose classpath begins with a directory containing a space —
still passed.

Probing further with a token designed to contain the sequences that differ produced the ground
truth. Feeding `C:\tab\back\form\already\\doubled\dir with spaces\a"quoted".jar` through the JDK 25
launcher unescaped and reading it back out of a child returned:

```
C:<TAB>ab<BS>ack<FF>orm...already\\doubled...dir with spaces...aquoted.jar
```

So: the backslash **is** an escape character inside a quoted token, but an **unrecognised** escape
is passed through unchanged. That is why an ordinary Windows path survives unescaped, and why this
would have been a latent, path-dependent corruption rather than an obvious break — the worst kind.
Quoting every token and doubling every backslash is therefore not defensive; it is the only
encoding that survives an arbitrary path. The javadoc on `argFileToken` now says this, with the
measurement, instead of the claim that was wrong.

### Tests

| Test | Pins |
|---|---|
| `ExtractionSandboxCommandTest.switchesToAnArgfileWhenTheCommandLineWouldExceedTheWindowsLimit` | above the threshold the form is `java @file main`, the file exists, and the classpath round-trips into it |
| `…inlineFormIsKeptBelowTheThreshold` | below it, nothing changes |
| `…publicEntryPointPicksTheFormThatFitsThisRunnersClasspath` | the public entry point picks the form that fits **this** runner's classpath, and the chosen form always fits the limit — the branch that made the same suite pass under one Gradle home and die under another |
| `…argFileTokenQuotesSpacesAndEscapesBackslashes` | the encoding, as a pure function |
| `PersistentExtractionSandboxTest.argfileCommandLaunchesTheRealChildWithASpacedClasspathEntry` | the argfile form launches the **production** child, with a spaced classpath entry |
| `PersistentExtractionSandboxTest.argFileEncodingRoundTripsThroughTheJdkParser` | a hostile token survives the JDK's **own** parser, out through a child JVM and back |

The last two are deliberately different in kind: the launch test proves the 206 fix, and (as the
falsification showed) it cannot discriminate on escaping; the probe is what does.

**Falsification.** Replacing `argFileToken` with bare quoting reds exactly the two encoding tests
(`argFileTokenQuotesSpacesAndEscapesBackslashes`, `argFileEncodingRoundTripsThroughTheJdkParser`)
and leaves the other 18 green.

### Verification, under both Gradle homes

| | default `GRADLE_USER_HOME` | `GRADLE_USER_HOME=…\Temp\jsgh-C` |
|---|---|---|
| `:modules:worker-services:test` | **1100 tests, 0 failures** | **1100 tests, 0 failures** |
| `:modules:indexer-worker:test` | 305 / 0 | — |
| `:modules:adapters-lucene:test` | 592 / 0 | — |
| `PersistentExtractionSandboxTest` | 14 / 0 | 14 / 0 |
| `ExtractionSandboxCommandTest` | 7 / 0 | 7 / 0 |
| `ExtractionSandboxLatencyBenchmarkTest` | 1 / 0 | 1 / 0 |
| `ExtractionRoutingTest` | 3 / 0 | 3 / 0 |

`error=206` appears nowhere in either run. The default-home figures are from a `--rerun-tasks`
pass, because the two homes share the build directory and an up-to-date task would otherwise have
reported the other home's results.

### Ride-along

`PruneByPathPrefixTest.java:332`'s comment described the abort checker as "user active", which
stopped being what it means when breath-holding was replaced by the contention duty cycle (#598).
PR #598 does not touch that file (checked against its file list), so the comment is corrected here.
The method name `abortsOnUserActivity` carries the same staleness but is left to #598's own sweep
rather than renamed across branches.

## §SC-review — independent review of #595, applied (chunk 2d, 2026-09-02)

An independent reviewer reproduced the chaos tier (14/14, with the 60.0 s sandbox deadline firing
before the 75 s backstop) and all three falsifications, then raised seven should-fixes. What each
one changed, and what it changed my mind about.

### 1. The stderr capture kept the HEAD, so a chatty parser could demote an OOM

The real defect of the set. `StderrTail.append` stopped accepting once full, so the **newest** bytes
were dropped — and the newest bytes are the ones that say how the child died. A parser that logged
more than 64 KB before dying pushed its own `OutOfMemoryError` trace out of the capture, the
substring test in `discardAndClassify` went false, and a permanent parse failure was reported as a
**retryable** crash: the file would then be retried forever against a heap it cannot fit in. The
OOM classification chunk 2 added was therefore only correct for quiet parsers.

Fixed as a real ring (keep the last `maxBytes`), plus a `reset()` before each request so the
reported tail belongs to the file that actually failed rather than to whatever the child logged
handling earlier ones. Note which half is load-bearing: with a head buffer, resetting per request
would **not** have helped, because the noise and the OOM are in the same request.

Regression test `chattyParserCannotDemoteAnOomToRetryable` — the stub writes ~212 KB of chatter and
then genuinely exhausts a 64 MB heap. **Falsified:** restoring the "stop when full" behaviour reds
exactly that test (`15 tests completed, 1 failed`) and leaves the quiet-OOM test green, so the two
discriminate on the ring and not on the OOM plumbing.

### 2. Merge `origin/main`, and retire the stale pin

Merged `origin/main` (#594, #596, #597). Three conflicts, resolved deliberately:

* `docs/explanation/23-search-pipeline-overview.md` — both sides rewrote the same stage-2 row.
  Kept main's VDU sentence (it removed the retired enable flag and renamed the threshold) and this
  branch's sandbox sentence; neither side's fact was dropped.
* `.claude/skills/search-quality/SKILL.md` — generated; regenerated from the resolved canonical doc
  with `skills-sync` rather than hand-merged.
* `scripts/agent-analytics/expected-state.v1.json` — both sides appended pins. Took main's set plus
  this branch's one addition. **Caught while resolving:** a naive union would have resurrected
  `ui-web-typecheck-ts5101`, which main deliberately retired — a merge that silently restores a pin
  someone else just removed is exactly the residue the register exists to prevent.

`process-extraction-sandbox-classpath-too-long` deleted in the same commit. That pin was written
against the defect chunk 2c fixed at the root, and it names `ProcessExtractionSandboxTest`, which
chunk 2 deleted — its own exit condition says "when the file is absent, delete this entry".
`run-all-tests.mjs` 50/50; `expected-state-probe --gate` reports 15 pins, 0 shape/review problems.

### 3. The Prune comment asserted a change that lands in a different PR

Reworded to point at the tempdoc item rather than to state the duty-cycle outcome as already true.
The reviewer is right that #598, not #595, is where that becomes a fact; a comment on this branch
claiming it would be wrong for however long #598 takes to land.

### 4. The shipped default command and `auto` routing have never run from a real Worker dist

Accepted and recorded rather than papered over. The chaos harness sets
`JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND` (to substitute the parser) and
`JUSTSEARCH_EXTRACTION_SANDBOX_MODE=process` (so a `.txt` reaches the pool at all) — so the two
things a default install actually uses, `ExtractionSandboxCommand.defaultCommand` and `auto`
routing, are covered by unit tests and by nothing live. This is now the second entry on the
still-open list, and the dev-stack real-corpus arm is what closes it.

### 5. A bad child command was invisible until the first file

Spawning is lazy, which is right for steady state, but it meant a broken command — a bad operator
override, a missing JDK, an unreadable classpath — surfaced only as a per-file `IOException`, on
every file, forever. `ExtractionSandboxFactory.probeChildCommand` now spawns one child at wiring
time and runs a trivial extraction through it (`PROBE_TIMEOUT` = 20 s for the extraction, so a broken
command cannot stall Worker boot; the honest worst case is ~25 s, since killing a child that
*hangs* adds the 5 s `waitFor` in `discardChild` — a command that cannot launch is rejected
immediately). On failure `DefaultWorkerAppServices` logs a WARN naming the
reason and the command, records
`extraction.sandbox_restart_total{reason=probe_failed}` as the lifecycle-visible signal, and falls
back to in-process extraction **for the session** — degraded, but every document still indexes.
Both branches are tested in `ExtractionRoutingTest` (the shipped command passes its own probe; a
command that cannot launch returns a named failure).

Honest limit: the fallback is per-session and silent to the UI. A user-visible condition would need
the worker-condition path, which is item 6's territory, not this chunk's.

### 6-7. Superseded pointer; EnvRegistry append region

The old "Live/chaos-window acceptance items still open" list now carries a SUPERSEDED banner
pointing at §SC-chaos. The three new `EXTRACTION_SANDBOX_*` constants moved from mid-enum to the
end of `EnvRegistry`, where the cross-lane append rule puts them — they were added before that
region existed on this base, and leaving them mid-enum is a merge conflict waiting for lane A.

### Nits

`SandboxFrames` javadoc corrected (the length is a **signed** 31-bit int — Java has no unsigned
int, and the reader rejects a negative one); `TimeboxedContentExtractor.extractSafe` now routes
through its own `detectMimeType`, which already null-guards the delegate, instead of dereferencing
it (an extractor built on a bare sandbox has no delegate); §SC.5's sweep claim restated accurately
(four prose mentions of the retired type remain, all deliberate history — the claim is "no live
referencer", not "no occurrences"); the chaos test deletes its `%TEMP%\justsearch-sandbox-chaos`
run directory on success and keeps it on failure, where the worker log and metrics NDJSON are the
evidence; the argfile probe child uses `redirectErrorStream` so the two-pipe read cannot deadlock.

---

## §TB — pre-implementation verification (chunk 3, item 3)

Every `path:line` the Evidence and Design-decision sections cite for **item 3**, re-read against
this chunk's base `768d69c7` (= `6c3ba431` + chunks 1/2/2b). Verified 2026-09-02 by the chunk-3
implementer. Same verdict vocabulary as §B. Line numbers below are the **pre-change** ones on
`768d69c7`; the change itself moves or deletes most of them.

### TB.1 The sixteen `isUserActive` call sites

All sixteen still present, and §B.2's re-verified list holds with only the drift chunks 1/2 caused:

| # | Site on `768d69c7` | Verdict vs §B.2 (`6c3ba431`) |
|---|---|---|
| 1 | `IndexingLoop.java:604` (`if (signalBus.isUserActive())` → `transitionToPaused()` + `Thread.sleep(BREATH_HOLD_MS)`) | **OK** (`:605` in the contract is the `transitionToPaused()` line) |
| 2-4 | `BackfillScheduler.java:239`, `:430`, `:612` | **OK** |
| 5 | `JobBatchExtractor.java:128` | **OK** |
| 6-7 | `SpladeBackfillOps.java:236`, `:242` (both inside `shouldInterrupt`) | **OK** |
| 8-9 | `NerBackfillOps.java:69`, `:75` | **OK** |
| 10 | `EmbeddingBackfillOps.java:187` | **OK** |
| 11-12 | `DisambiguationBackfillOps.java:68`, `:74` | **OK** |
| 13 | `CombinedEnrichmentBackfillOps.java:591` | **OK** |
| 14-15 | `SyncDirectoryOps.java:227` (prune abort-checker), `:299` (walk abort) | **OK** |
| 16 | `GrpcIngestService.java:1082` (prune abort-checker) and `:1142` (syncDirectory skip) | **OK** — two sites in one file, which is how the count reaches 16 |

Plus one non-call reference: `IndexingLoop.java:539`, a comment naming `isUserActive()` beside
`shouldYieldGpuBackfill()`; and `LoopPacingPolicy.shouldInterruptBackfill`'s `userActive` parameter
(`LoopPacingPolicy.java:66-73`), a 17th *symbol* but not a bus call.

**TB.1a — a fact the contract does not state, and it changes the shape of the diff.** Three of the
five ops `BackfillContext` records carried `WorkerSignalBus` **only** to reach `isUserActive()`:
`NerBackfillOps` (`:31`), `SpladeBackfillOps` (`:36`), `DisambiguationBackfillOps` (`:28`). Same for
`JobBatchExtractor` (`:68`) and `SyncDirectoryOps` (`:65`). Deleting the method therefore does not
merely replace a condition in those five — it removes their whole dependency on the signal bus, so
the pacing policy takes over the same parameter slot instead of being an added one.
`EmbeddingBackfillOps` and `BgeM3BackfillOps` keep the bus (GPU/energy), and
`CombinedEnrichmentBackfillOps`'s single bus use *was* the `isUserActive()` check.

### TB.2 The five Head-side `signalUserActivity` callers

| Contract cite | On `768d69c7` | Verdict |
|---|---|---|
| `KnowledgeSearchController.java:304` (search), `:849` (suggest), `:887` (folders), `:931` (folder-files) | exact, all four | **OK** |
| `CoreApiAssembly.java:110` (Preview, via a `Runnable` handed to `PreviewController`) | exact; `PreviewController` holds it as the `signalUserActivity` field (`:44`), invoked at `:107-108` | **OK** |
| `KnowledgeServerBootstrap.signalUserActivity()` `:698` → `WorkerSpawner.signalUserActivity()` `:334-338` | exact | **OK** |
| `StatusLifecycleHandler` never calls it (**[R2]**) | still 0 hits | **OK** |

**TB.2a — WRONG, and it changes the deletion plan: `signalUserActivity` is not only the MMF write
path.** `KnowledgeServerBootstrap.signalUserActivity()` (`:698-702`) does two things — it stamps the
Head-local `lastUserActivityEpochMs` (`:83`) *and* calls the spawner. That Head-local stamp is read
through `msSinceLastUserActivity` (`:711`) by `VduPacingPolicy` (documented at
`VduPacingPolicy.java:16-18`), `VduOfflineTriggerSampler.java:95` and `ServicePhase.java:196`.
Deleting the five Head callers as the chunk brief words it would silently make VDU's activity signal
read "idle forever" — a regression in another lane's feature, caused by a rule about this one. The
Head-side recorder is therefore **renamed** (`recordUserActivity()`), keeping all five callers, and
only the Head→Worker half (`spawner.signalUserActivity()` → `MainSignalBus.writeActivity()`) is
deleted. The acceptance grep is satisfied by the rename; VDU keeps its input.

### TB.3 The status wire and the MMF residue

| Contract cite | On `768d69c7` | Verdict |
|---|---|---|
| `IndexStatusOps.java:428` puts `signalBus.readActivity()` on the status wire | exact | **OK** |
| the proto field behind it | `indexing.proto:677` — `int64 signal_bus_activity_ts = 6;` in `CoreStatus` | **OK** (deleting it is a buf break — §TC.4) |
| `main_gpu_active` byte 24, `MmfWorkerSignalLayoutV1.java:44` | `OFFSET_MAIN_GPU_ACTIVE = 24` at `:44` | **OK** |
| §B.4's corrected count: **six** direct `isMainGpuActive()` readers | confirmed six, unchanged by this chunk: `KnowledgeServer:1000`, `KnowledgeServer:1642` (post-change numbering; `:996`/`:1632` before), `BackfillScheduler:197`, `EmbeddingProviderLifecycle:169`, `EmbeddingBackfillOps:194`, `IndexingDocumentOps:219`. Indirect via the `WorkerSignalBus:100` `shouldYieldGpuBackfill()` default: `BgeM3BackfillOps:335`, `BackfillScheduler:246`, `:437`, `:624` | **OK** |
| `MmfWorkerSignalBus.java:215-229` `isUserActive()`; `:219` the `Boolean.getBoolean` eval hatch | exact; `readActivity()` at `:146-149` | **OK** |
| `modules/ui/build.gradle.kts:2178` sets `justsearch.eval.disable_breath_holding` (comment `:2176-2177`) | exact | **OK** |
| `modules/indexer-worker/src/main/resources/logback.xml:97` pins `io.justsearch.indexerworker.loop` to INFO | exact — which is why the new pacing logger lives in `io.justsearch.indexerworker.loop.pacing` (INFO by inheritance) and logs at INFO | **OK** |

**TB.3a — two sites the contract's residue list does not mention.** `MmfTestHarness`
(`modules/system-tests/src/main/.../chaos/MmfTestHarness.java:91-122`) carries its own
`writeActivity` / `readActivity` / `simulateRecentActivity` / `simulateStaleActivity`, and
`ChaosSuiteTest.mmfActivityTimestamps` (`:496-517`), `MmfSignalBusCompatibilityTest` and
`torture/ReadWhileWriteTest.java:155` exercise the activity slot. `MmfTestHarness` is
`modules/system-tests/src/main`, so the acceptance grep sees it. They are swept here (§TC.5) rather
than left for lane F — the alternative was to meet a "no hits" criterion by not looking.

### TB.4 Which `GrpcSearchService` methods count as foreground

`SearchService` declares **ten** RPCs (`modules/ipc-common/src/main/proto/indexing.proto:299-317`),
all unary, all implemented in `GrpcSearchService`:

| RPC | Impl | Foreground? |
|---|---|---|
| `Search` | `:414` | yes |
| `Rerank` | `:463` | yes |
| `Suggest` | `:527` | yes |
| `FetchDocuments` | `:582` | yes |
| `FetchDocumentSlice` | `:652` | yes |
| `RetrieveContext` | `:771` | yes |
| `MatchCitations` | `:820` | yes |
| `ListFolders` | `:866` | yes |
| `ListFolderFiles` | `:915` | yes |
| `ListAllDocumentIds` | `:962` | **no** |

The contract names six; the nine above are those six plus `MatchCitations` (the chat citation
matcher), `ListFolders` and `ListFolderFiles` — and the last two are literally two of the five Head
sites that used to signal activity (`KnowledgeSearchController:887`, `:931`), so excluding them
would have *lost* coverage the breath-hold had. `ListAllDocumentIds` is excluded because its only
caller is the Head's background GPL pager (`GplJobCoordinator.java:292-293`, `BATCH_SIZE = 50` over
the whole corpus): counting it would let a background job throttle indexing, the same class of
defect as counting a status poll. No `IngestService` or `HealthService` method counts.

## §TC — post-implementation critical analysis (chunk 3, item 3)

### TC.1 Wrong-gate: does the interceptor's method filter fire where it must?

The filter is built from generated `MethodDescriptor`s (`ForegroundLoadInterceptor.java:38-48`), not
from hand-written strings, so a proto rename is a compile error rather than a silently-empty filter
(`catalog-verbatim`). The gate is asserted from both sides in `ForegroundLoadInterceptorTest`:

* `indexStatusIsNotForeground` drives a real `IngestServiceGrpc.getIndexStatusMethod()` through the
  interceptor and asserts the gauge stays 0 **and** `IndexingPacing.foregroundBusy()` is false — then
  drives a real `Search` through the same interceptor and asserts `foregroundBusy()` flips true. That
  is the takeover checklist's wrong-gate assertion with both halves in one test, so neither can pass
  for the wrong reason.
* `foregroundSetIsTheSearchServiceMinusThePager` derives the expected set from
  `SearchServiceGrpc.getServiceDescriptor()` at runtime, so a `SearchService` RPC added later fails
  the test until someone classifies it.

The registration site was re-read rather than assumed: `KnowledgeServer.java:712-720` builds the
interceptor list and feeds the new entry `appServices.foregroundLoad()` — the same instance
`DefaultWorkerAppServices` hands to `IndexingPacing` (`DefaultWorkerAppServices.java:105-115`), so
producer and consumer provably share one gauge.

### TC.2 Wrong-gate: does the pacing reach every former `isUserActive` site?

Checked by grep after the change, not by trusting the edit:

```
$ grep -rn "isUserActive\|signalUserActivity\|disable_breath_holding\|readActivity" modules/*/src/main
modules/configuration/.../resolved/ResolvedConfig.java:347        (prose: names the retired gate)
modules/worker-services/.../loop/pacing/ForegroundLoad.java:13     (prose: names the retired gate)
modules/worker-services/.../loop/pacing/IndexingPacing.java:12     (prose: names the retired gate)
```

All three survivors are doc comments naming what was replaced (two in the replacement types, one on
the config record whose javadoc explains what the per-document responsiveness check is now). Every executable
site is gone and each is now a `pace()` call in the same control-flow position (loop tail, per file,
per document, sub-batch boundary, walk throttle tick). The distribution is compile-enforced rather
than optional: `IndexingLoop`, `BackfillScheduler` and `GrpcIngestService` take the policy as a
`requireNonNull` constructor parameter, so a composition that forgets it fails at construction
instead of running unthrottled.

### TC.3 Test precision: right reason vs wrong reason

* `IndexingPacingTest` injects both clocks and the sleep, so every assertion is on arithmetic rather
  than on wall-clock timing: 100 ms of work under load yields exactly 400 ms at duty 20, and five
  such intervals give `observedDutyPct() == 20`. A test that merely asserted "it slept" would pass
  for a pause as well — item 3 is about the *ratio*, so the ratio is what is asserted.
* `contendedIntervalStillPerformsTheConfiguredShare` is the "never fully stops" criterion, written so
  a regression to a pause fails it: a pause yields unbounded time for zero work, driving
  `observedDutyPct()` to 0, not 20.
* The interceptor's three terminal paths (OK close, error close, cancel) are separate tests, and two
  of them fire a *second* terminal event to prove the latch prevents a double decrement — the failure
  mode that would drift the gauge negative and make it read "never busy".
* `ForegroundPacingConfigForwardingTest` asserts the Head→snapshot→Worker round-trip, not the
  existence of a key. "The key is declared" would have passed for `disable_breath_holding` too, which
  is exactly the [R1] defect.

### TC.4 The status wire: field kept, population stopped

`signal_bus_activity_ts` (`indexing.proto:677`) is **left declared and not `reserved`**;
`IndexStatusOps` simply stops setting it, and the removed call site is replaced by a comment naming
why. Two reasons: removing or reserving the field is a `buf` breaking change against
`contracts/registry.v1.json`, and lane F deletes the MMF activity byte and this field together, so
one wire change there beats two. No `.proto` file was modified by this chunk, so the `wire` gate has
nothing new to judge. Consumers now always read `0` — which is what the field already returned
whenever the Head had not signalled.

### TC.5 Sweep: what the retirement actually touched

Beyond the 16 sites: `WorkerSignalBus.isUserActive()` + `readActivity()` (interface),
`MmfWorkerSignalBus`'s implementations of both **and** the `Boolean.getBoolean` eval hatch,
`MainSignalBus.writeActivity()`, `WorkerSpawner.signalUserActivity()`, the Gradle setter in
`modules/ui/build.gradle.kts` with its comment, `LoopPacingPolicy.BREATH_HOLD_MS` + `breathHoldMs()`
+ the `userActive` parameter of `shouldInterruptBackfill`, `IndexStatusOps`'s activity population,
`MmfTestHarness`'s four activity accessors, `ChaosSuiteTest.mmfActivityTimestamps`, and the
`simulateRecentActivity` poke in `torture/ReadWhileWriteTest.java`. Two behaviours died with them and
were swept rather than left inert: `syncDirectory` no longer *skips* on activity (so
`IngestResponses.syncDirectorySkippedResponse(int,int)` and `SyncWalkPhaseResult.walkAborted` are
gone), and the prune abort-checker became a pacing tick that never aborts — a prune that stopped
half-way on user activity left orphans behind.

Docs swept in the same change: `02-process-coordination.md` §3 (rewritten as the duty cycle, with the
activity slot marked retired), `03-knowledge-server.md` step 1, `20-benchmarking-architecture.md`,
`23-search-pipeline-overview.md`, `docs/reference/performance/indexing-throughput.md` (the
`BREATH_HOLD_MS` row becomes the two config keys), ADR-0002 and ADR-0018's breath-hold mentions, and
`modules/indexer-worker/README.md`.

### TC.6 The cheaper ORT lever (design decision 3's "try first")

Design decision 3 asks that lowering ORT intra-op threads under load be tried first for the
enrichment backfills, "only if the ORT session API already exposes it". It does not, in a usable
form: `OrtSession.SessionOptions.setIntraOpNumThreads` in `ai.onnxruntime` is a
**session-construction** option, fixed for the session's lifetime, with no setter on a live session.
Honouring load through it would mean tearing down and rebuilding every encoder session on each
foreground burst — seconds of model re-init and exactly the VRAM churn the GPU arbitration slot
exists to avoid. The duty cycle reaches the same end (give the machine back) at the cost of a
`Thread.sleep`. Recorded as "not done, and why", not silently skipped.

### TC.7 Residue lane F inherits (precise)

* **MMF activity slot** — `MmfWorkerSignalLayoutV1.OFFSET_ACTIVITY_EPOCH_MS = 0`
  (`MmfWorkerSignalLayoutV1.java:33`). No writer, no reader, no harness accessor; its only remaining
  reference is the layout's own test (`MmfWorkerSignalLayoutV1Test.java:36-37`), which is what keeps
  it from being dead residue while the layout still exists.
* **Status wire** — `CoreStatus.signal_bus_activity_ts` (`indexing.proto:677`), declared but never
  populated (§TC.4). Delete it with the layout.
* **`main_gpu_active`** — byte 24 (`MmfWorkerSignalLayoutV1.java:44`), Head-written, with **six**
  direct readers (`KnowledgeServer:1000`, `KnowledgeServer:1642`, `BackfillScheduler:197`,
  `EmbeddingProviderLifecycle:169`, `EmbeddingBackfillOps:194`, `IndexingDocumentOps:219`) and four
  indirect ones through the `WorkerSignalBus:100` `shouldYieldGpuBackfill()` default
  (`BgeM3BackfillOps:335`, `BackfillScheduler:246`, `:437`, `:624`). Untouched here, per the contract.
* **The gRPC interceptor** — `ForegroundLoadInterceptor` is the deliberate throwaway half: under a
  single JVM the gauge is incremented directly at the search entry points and the interceptor goes.
  `ForegroundLoad` and `IndexingPacing` survive as `worker-services` types.

### TC.8 The routed finding this chunk raised, and where it landed

Before the merge with `main`, `:modules:worker-services:test` was **1108 tests, 13 failed** on this
branch — all 13 item 14's (`PersistentExtractionSandboxTest` ×12,
`ExtractionSandboxLatencyBenchmarkTest.perFamilyLatencyTable` ×1), every one failing inside
`ProcessBuilder.start` with `CreateProcess error=206, The filename or extension is too long`, the
Windows command-line limit hit before any child code ran. It was not this chunk's: the item-3 diff
touches no file under `extract/` beyond one constructor argument in
`AdversarialCorpusIngestionTest`.

Routed to the orchestrator with the diagnosis (the child argv copies the running JVM's fully
expanded `java.class.path`, which the lane's isolated `GRADLE_USER_HOME` lengthens past the limit;
production was never exposed because the Worker runs from `-cp lib\*`). **Chunk 2c fixed it at the
root rather than pinning it** — see §SC-argfile: `ExtractionSandboxCommand` now falls back to an
`@argfile` when the assembled command would exceed the OS limit, which also hardens the shipped
path against a genuinely deep install. After merging `origin/main` (#595, which carries 2c), those
13 failures are gone; the post-merge counts are in the PR body.

### TC.9 Live-window items still open for item 3

None of these can run in a unit tier; they need the shared dev stack and are scheduled by the
orchestrator, not by this chunk.

1. **The "after" half of the baseline table** — `jseval run --pipeline` on scifact, three arms:
   (a) alone, (b) `--search-load-qpm 10`, (c) `--search-load continuous`, with the same commands as
   the Baseline section. Acceptance: (b) within 10% of (a); (c) reaches **at least 20% of (a)'s
   `pipeline_timing.primary_indexing.docs_per_s`** where it previously reached 0 (frozen at 699 of
   5184); search p95 for (b) and (c) read against **(b)'s 543 ms**, not (c)'s 276 ms, per the
   baseline's own note.
2. **Pacing attribution for that run** — `worker.indexing.paced_intervals_total` and
   `worker.indexing.duty_pct` must be non-trivial in arms (b) and (c) and zero/100 in arm (a), and
   the Worker log must carry the INFO pacing line. This is what §B.2a said the breath-hold could
   never show; if the after-run cannot show it either, the instrument is wrong, not the result.
3. **Chaos "Time Lord"** — ~~not run~~ **RUN AND GREEN** (2026-09-02, review window):
   `ChaosSuiteTest.indexingRunsAtAReducedDutyUnderForegroundSearchLoad`, rewritten to drive the
   gauge with real `SearchService` traffic instead of the MMF byte, passes in 38.1 s as part of a
   **13/13** chaos suite. Three defects were found and fixed getting there, all in the test, none
   in the product: (a) `spawnWorkerAndAwaitPort` stops its heartbeat keeper as soon as the port
   appears, so any body outliving `STARTUP_GRACE_MS + HEARTBEAT_STALE_MS` watches the Worker honour
   the suicide pact — the test now runs its own keeper for the whole body; (b) a single corpus was
   fully drained by the poll-only phase, so "no progress under load" meant "no work queued" — the
   test now submits a second batch at the load phase and asserts `queueDepth > 0` before measuring;
   (c) a 1200-path `submitBatch` exceeds the client default 10 s deadline while the loop is
   indexing. The Worker log carries the shipped-level evidence:
   `Indexing pacing: foreground duty 20%, cooldown 500 ms` at startup (the config crossed the
   process boundary live) and `Indexing paced by foreground load: … foreground inFlight=2
   cooldownMs=500` at INFO from `i.j.indexerworker.loop.pacing.IndexingPacing` — the observable
   §B.2a said the breath-hold could never produce. Note when reading it: the line is rate-limited
   to one per 30 s and fires on the first yield, so the first line always reports `1 yields` and a
   window duty dominated by the preceding uncontended phase; magnitude comes from the second line
   onward and from the jseval arms, not from the first.
4. **Search p95 before/after** — read from the same jseval arms' `search_load.latency_ms`.

## §UB — pre-implementation verification (chunk 4, items 6 and 21)

Every `path:line` the Evidence and Design-decision sections cite for **items 6 and 21**, re-read
against this chunk's base `e1eccb17` (= `6c3ba431` + chunks 1/2/2b/3). Verified 2026-09-02 by the
chunk-4 implementer. Verdicts: **OK** (exact), **DRIFT** (right thing, moved), **WRONG** (the claim
does not hold).

### UB.1 Item 6 — status and health

| Contract cite | On `e1eccb17` | Verdict |
|---|---|---|
| `StatusLifecycleHandler.java:406-431` — every `/api/status` hit calls `client().getWorkerOperationalView()` on the request thread | exact: the `if (workerCapability.available())` block at `:412-431`, RPC at `:414` | **OK** |
| `RemoteKnowledgeClient.java:767-768` — that call is one blocking `IngestService.IndexStatus` unary | `:769` `getWorkerOperationalView()` | **DRIFT** (1 line) |
| `CoreApiAssembly.java:238-250` — the taps that feed `ConditionStore` + `HealthEventChangeRegistry` hang off that handler | `:239-250` `setLifecycleSnapshotTap` / `setWorkerSnapshotTap`; the tap block runs to `:308` (`setIndexDriftTap` `:249`, `setAtRestTap` `:265`, `setConversationProtectionTap` `:289`, `setWorkerMetricsPublisher` `:308`) | **OK**, but the range under-counts: there are **six** tap-shaped consumers, not two |
| `StatusLifecycleHandler.java:388-393` also reads Head heap per hit; keep, it is cheap | `:389-393` | **OK** |
| **[R8]** `KnowledgeServerHealthMonitor` `scheduleWithFixedDelay` at `:145`, 10 s default (`DEFAULT_POLL_INTERVAL_MS` `:54`), started at `HeadlessApp.java:509` | `scheduleWithFixedDelay` at `:143-145`; `DEFAULT_POLL_INTERVAL_MS = 10_000` at `:54`; `startHealthMonitor` at `HeadlessApp.java:506-524`, `monitor.start()` at `:522` | **OK** |
| `ResumeDetector` handles OS-resume gaps | `KnowledgeServerHealthMonitor.java:150-154` + `RESUME_TOLERANCE_FACTOR = 3` at `:60` | **OK** |
| Ten mock sites stub `getWorkerOperationalView()` | exactly ten: `LifecycleContractTest` `:185,237,279,336,388,436,486` (7), `StatusReadinessStalenessTest` `:168,269` (2), `ReadinessTriggerCompositionTest` `:87` (1) | **OK** |
| **[R6b]** per-document content capped at 200k chars (`GrpcSearchService.java:77,603`); `GplJobCoordinator.BATCH_SIZE = 50` (`:58`); four callers in app-services (`GplJobCoordinator.java:306,670`, `RemoteDocumentService.java:96,476`) | `MAX_CONTENT_CHARS = 200_000` at `GrpcSearchService.java:77`, applied at `:600`; `BATCH_SIZE = 50` at `GplJobCoordinator.java:58`; callers at `GplJobCoordinator.java:306,670` and `RemoteDocumentService.java:96,476` | **OK** (one 3-line drift on the trim site) |

**UB.1a — the sampler the contract asks for already half-exists, and the contract does not say so.**
This is the single biggest correction in this chunk. Tempdoc 876 §C.8 added
`KnowledgeServerHealthMonitor.onTick` (`:200-212`), wired in `HeadlessApp.java:520` to
`ReadinessReconciliationTrigger::request`, whose thunk is attached in `CoreApiAssembly.java:453` as
`statusLifecycleHandler::buildStatusSnapshot` — i.e. `buildStatusMap()`, i.e. **the Worker RPC plus
every tap, already running on the monitor's tick, on a dedicated daemon thread**
(`ReadinessReconciliationTrigger.java:52-58`; its own javadoc says "it performs a Worker gRPC call").
Design decision 4 reads as though the sampler must be built; what actually had to be built is the
*other* half — the cache that lets a request read what the tick left behind. Framing item 6 as "add
a sampler" would have produced a second observation path beside this one, which is the fork this
repo's registers exist to prevent. The implementation therefore adds no scheduler and no new
thread: it splits `buildStatusMap` into a sampling path and a read path, and re-points the existing
thunk at the sampling one.

**UB.1b — R8's "ten mock sites need updating" is nearly right, for a different reason than stated.**
Only **two** of the ten needed a change, and neither because of the stubbing: `StatusReadinessStalenessTest`
`:180` needed its *second* call to be a sample (contact loss is now discovered by the sampler, not
by a request), and `ReadinessTriggerCompositionTest` `:99` binds the production method reference by
name and therefore had to follow the rename. The other eight make a single status call per test,
which the boot-window fallback (UB.1c) serves with the same one RPC they already stubbed.

**UB.1c — a fact that changes the design: `attach()` self-seeds.**
`ReadinessReconciliationTrigger.attach` (`:75-78`) calls `request()` immediately, so the first sample
is taken at composition time, not at the first monitor tick 10 s later. That removes the reason to
give the read path a max-age re-sample (which would have put a Worker RPC back on the request thread
in exactly the degraded case the item is about). The read path therefore samples synchronously in
one case only — no sample has ever been taken — which can happen at most once per process, and a
stalled sampler surfaces as `workerRpcStale=true` rather than as a request-thread RPC.

**UB.1d — `?fresh=true` had no prior art on this handler.** `handleStatus` (`:372-374` pre-change)
read no query parameters at all. The parameter is new surface, not a restored one.

### UB.2 Item 21 — job queue

| Contract cite | On `e1eccb17` | Verdict |
|---|---|---|
| `SqliteJobQueue.java:46` `DEFAULT_MAX_ATTEMPTS = 3` (private) | `:46`, `private static final` | **OK** |
| `SqliteJobQueue.java:49,60,62` one connection, one `ReentrantLock`, `busy_timeout=5 s` | `BUSY_TIMEOUT_MS` `:49`, `lock` `:60`, `maxAttempts` `:61`, `connection` `:62` | **OK** |
| `KnowledgeServer.java:394-401` passes a bare `3` (the only construction site) | the literal is at `:414` (`new SqliteJobQueue(dbPath, 3, onSwitchBufferWriteFailure)`); still the only construction site in `src/main` | **DRIFT** (~13 lines; chunk 3 moved it) |
| `markFailed` backoff `:642-647`, cap ~17 min | `:640-647` (`1000L * (1L << Math.min(newAttempts - 1, 10))`) | **OK** |
| `markFailedWithOutcome` `:779-855` branches on `outcome.retryPolicy()` | `:779-855` exactly | **OK** |
| `retry_after` column (`:378,640-652`) | selected in `pollPending` at `:378`, written at `:640-652` and `:788-800` | **OK** |
| `IngestionRetryPolicy {NONE, RETRY_WITH_BACKOFF, DEFER_WITHOUT_ATTEMPT}` in worker-core | `modules/worker-core/.../ingest/IngestionRetryPolicy.java:6-10` | **OK** |
| 14 `IngestionOutcomeClass` values | exactly 14 (`SUCCESS_FULL` … `SANDBOX_FAILED`) | **OK** |
| catch-site wiring `JobBatchExtractor.java:250-340` (`IOException`→`IO_FAILED`→RETRY, parser failure→NONE) | six catch sites at `:241-345`: `BudgetExceededException`→NONE, `ExtractionTimeoutException`→RETRY, `SandboxExtractionException`→RETRY, `ContentExtractor.ExtractionException`→NONE, `IOException`→RETRY, `RuntimeException`→`PARSER_FAILED`+**RETRY** | **OK**, with one nuance the contract flattens: a bare `RuntimeException` is `PARSER_FAILED` but *retryable*, so "parser failure → NONE" is true only of the declared `ExtractionException` |
| cloud placeholders → DEFER (`CloudPlaceholderRecorder.java:61`) | `:61` | **OK** |
| `IndexingJobView` carries `attempts` + `retryAfterMs` | `modules/app-api/.../indexing/IndexingJobView.java:22,26` | **OK** |
| schema `TARGET_VERSION 9` + `SqliteQueueMigrationOps` ladder | `SqliteSchema.java:33`; ladder `SqliteQueueMigrationOps.applyMigration` cases 1..9, backup at `:123`, txn `:131-150`, `setSchemaVersion` **after** commit at `:147` | **OK** |
| `governance/operation-surfaces.v1.json` registers `IndexingJobLifecycle` | `canonicalRecord.name = "IndexingJobLifecycle"`, `sourceOfTruth = IndexingJobsChangeStream.java`, `appApiType = IndexingJobView.java` | **OK** |
| **Genuine gaps:** transients count against `MAX_ATTEMPTS`; ladder caps at ~17 min; `error_message` stores fixed literals | all three confirmed: `:799` `terminal \|\| newAttempts >= maxAttempts`; `:806`; the six literals `"Extraction budget exceeded"`, `"Parser timed out"`, `"Sandbox failed"`, `"Parser failed"`, `"I/O failure"`, `"Unexpected processing failure"` | **OK** |

**UB.2a — WRONG, and it changes what item 21e must be named.** The contract says the risk register
"names `queue.dequeue_rate_per_min` under 885 item 21". It does not. `RISK-002` on `origin/main`
(`docs/reference/architectural-risks.md:100`) reads `**Instrument:** tempdoc:885#Item 21 — job queue`
— a pointer to this tempdoc, not a metric name. There is therefore no register name to honour and no
cross-lane rename to request. The metrics are named for the family that already exists in
`WorkerOpsMetricCatalog` (`worker.job_queue.depth`, `worker.job_queue.pending_jobs`, …), so they read
as `worker.job_queue.enqueue_rate_per_min` / `worker.job_queue.dequeue_rate_per_min` rather than
opening a second `worker.queue.*` prefix for the same subject. Lane B's file is untouched.

**UB.2b — the rescan reset needs no new code, and the reason is worth stating.** `enqueueEntries`
(`:288-345`) is `INSERT OR REPLACE` over a 7-column list; every column NOT in that list reverts to
its default. So a re-enqueue already clears `attempts`, `retry_after`, `error_message`, the five
`last_outcome_*` columns — and now `first_failed_at`. `WorkerScanOps.flushBatch` (`:263-278`) enqueues
every admitted file with no state filter, so a rescan of the containing root is the reset, and so is
a watcher event (`WorkerMethvinWatcher.java:192`) and `RetryIndexingJob`
(`GrpcIngestService.java:2149`). The freshness "UNCHANGED" skip happens later, at the extractor, not
at enqueue — so an unchanged file is still re-admitted and still leaves `RETRY_EXHAUSTED`.

**UB.2c — `RETRY_EXHAUSTED` is a string on the wire, not a proto enum.** `IndexingJobView.state`
is `string` (`indexing.proto:1472`), so adding a state is not a `buf` breaking change and the `wire`
gate has nothing to judge. What it *is* is a new value in a vocabulary five consumers switch on:
`SqliteJobQueue.countByPathPrefix` (`:1685`), `IndexingJobsBridgeWiring.terminalIndexEvent` (`:95`),
`ScanRollupLedger.recordIndexOutcome` (`:188`), `indexingJobsBridge.statusFor` (`:285`) and
`ActionLedgerClient.projectBackend` (`:260`). Four of the five had a `default`/`else` arm that would
have silently mis-classified an exhausted job as *not failed* — the folder projection would read
100%, the scan rollup would count it as done, and the ledger would emit nothing at all. All five are
swept in this chunk; the register's three prose notes naming the terminal set are updated with them.

**UB.2d — the exit code needs no new field.** 21c asks for "the child exit code for
`SANDBOX_FAILED`". `PersistentExtractionSandbox` already formats it into the exception message
(`:282,285`: `"Sandbox child exited with code " + exitCode + ": " + tail`), so storing the
exception's message satisfies both halves of 21c with one change.

### UB.3 Claims that no longer hold (chunk 4 summary)

1. The health sampler's **schedule and tap-feeding already existed** (876 §C.8 + the trigger); only
   the cache was missing (UB.1a).
2. **Eight of R8's ten mock sites did not need updating**, and the two that did needed it for
   reasons R8 does not give (UB.1b).
3. **RISK-002 names no metric** — the "use exactly the names the register expects" instruction has
   no register entry behind it (UB.2a).
4. `RuntimeException` at the extraction boundary is `PARSER_FAILED` **retryable**, so "parser
   failure → NONE" holds only for the declared `ExtractionException` (UB.2).
5. Line drift from `6c3ba431` to `e1eccb17` is small except `KnowledgeServer.java`, which chunk 3
   moved by ~13 lines around the queue construction site.

## §UC — post-implementation critical analysis (chunk 4, items 6 and 21)

### UC.1 Wrong-gate: does the sampler's tick feed the SAME taps the handler used?

The failure mode this asks about is a sampler that refreshes a cache while the taps keep reconciling
somewhere else (or stop reconciling at all). Checked structurally rather than by reading:

* There is exactly **one** method that feeds taps — `StatusLifecycleHandler.feedHealthTaps` — and
  exactly **one** call site for it, inside `buildStatusMap(boolean)` under `if (sampleWorker)`. All
  six tap consumers moved into it verbatim (`lifecycleSnapshotTap`, `workerSnapshotTap`,
  `indexDriftTap`, `atRestTap`, `conversationProtectionTap`, `workerMetricsPublisher`); the diff for
  that block is a pure move, so a tap cannot have been dropped in the split.
* `sampleWorker=true` has exactly two callers: `sampleAndBuildStatusSnapshot()` (the trigger's
  thunk) and `handleStatus` under `?fresh=true`. Grepped the set-site rather than trusting the
  symbol: `CoreApiAssembly.java` now reads
  `readinessTrigger.attach(statusLifecycleHandler::sampleAndBuildStatusSnapshot)`, and
  `HeadlessApp.java` still wires `monitor.onTick(readinessTrigger::request)`.
* `ReadinessTriggerCompositionTest` is the test that binds that production method reference by name
  over a real `ConditionStore` and a real `WorkerCapability`, with no HTTP anywhere. It was updated
  to the new name and is green — so "the tick reconciles the condition store" is asserted end-to-end
  through the production wiring, not through a test lambda.
* `WorkerStatusSamplerTest.readPathDoesNotFeedTaps` asserts the *negative* half with a counting
  `IndexDriftHealthTap`: 0 invocations from `buildStatusMap()`, 1 from
  `sampleAndBuildStatusSnapshot()`. Without that case the split would pass every other test while
  silently leaving the drift tap's `getWatchedRoots()` RPC on the request thread.

**One behaviour genuinely changed and is deliberate:** `GET /api/status` no longer reconciles the
health taps. That is 876's own thesis applied ("request-driven reconciliation is a cache, not a
state"), and the trigger fires on every monitor tick plus every capability transition, so the
substrate advances at least as often as it did under the browser poll. Recorded here rather than
left implicit.

### UC.2 Wrong-gate: does the adaptive period actually fire, and can it hurt anything?

* The monitor no longer uses `scheduleWithFixedDelay`; it re-arms with
  `executor.schedule(this::tickAndReschedule, nextTickDelayMs(), …)` in a `finally`, so a throwing
  tick still re-arms. `scheduleNextTick` returns early when `closed`, and swallows
  `RejectedExecutionException`, so `close()` during an in-flight tick cannot resurrect the schedule.
* `nextTickDelayMs()` clamps to `[MIN_TICK_INTERVAL_MS = 1 s, pollIntervalMs]` and falls back to
  `pollIntervalMs` when the supplier is null or throws. Unset supplier ⇒ byte-identical cadence to
  before, so every existing construction site (tests, standalone launchers) is unaffected.
* **The resume-detection trap, and why it is not one.** `ResumeDetector` compares the inter-tick gap
  against `pollIntervalMs * RESUME_TOLERANCE_FACTOR`. Had the reference shrunk with the actual delay,
  a 2 s cadence would treat any 6 s stall — a long GC, a starved CPU under parallel agent builds — as
  an OS resume and fire an eager channel reconnect + reconcile. The reference stays pinned to the
  **configured** interval, so a faster tick can only make resume detection more conservative, never
  more trigger-happy. `KnowledgeServerHealthMonitorTest.tickIntervalSupplierIsClampedToTheConfiguredInterval`
  pins the clamp, the zero case and the throwing case.
* **Cost of the fast arm:** at 2 s the monitor also runs `bootstrap.checkHealth()` five times more
  often. That is accepted and bounded: it only happens while the Worker is already doing index work,
  and the ceiling is the configured poll interval.

### UC.3 Wrong-gate: does `RETRY_EXHAUSTED` actually reset on a rescan?

Asserted, not reasoned: `JobQueueRetryLadderTest.sevenDayBoundExhaustsAndRescanResets` drives the
real `markFailed` path to `RETRY_EXHAUSTED`, then calls `jobQueue.enqueue(...)` — the exact statement
`WorkerScanOps.flushBatch` calls — and asserts `state=PENDING`, `attempts=0`, `retry_after=NULL`,
`first_failed_at=NULL` **and** that the row is claimable again by `pollPending`. The last assertion
is the one that distinguishes "the columns were reset" from "the job actually rejoined the queue".
The same test asserts the negative before the reset (`pollPending` returns empty while exhausted), so
an exhausted job silently rejoining the queue would fail it.

The seven-day boundary is reached by rewriting `first_failed_at` through JDBC. That is the only way
to reach it in a unit test; everything downstream of the column — the ladder call, the state
decision, the write — is the production path. `IngestionRetryLadderTest` pins the boundary
arithmetic itself (6 d not exhausted, exactly 7 d exhausted, the clamp that stops a 24 h step
overshooting the bound).

### UC.4 Test precision: right reason vs wrong reason

* **`statusReadPerformsNoWorkerRpc`** asserts `verifyNoMoreInteractions(client)` *and* that
  `meta.workerRpcAtMs` is byte-identical across three reads. Interaction counting alone could pass on
  a mock that was never reached for an unrelated reason; the timestamp identity is positive evidence
  that the three responses came from one observation, since the pre-change code stamped
  `System.currentTimeMillis()` per request.
* **`GplFetchDocumentsByteBudgetTest`** asserts on the *captured request sizes*, not on "the run
  completed". A mocked client never enforces the 32 MiB ceiling, so a completion assertion would pass
  on the pre-fix code. It additionally asserts `requests.size() > 1` (50 maximal documents cannot
  legitimately ride one request) and that the flattened request ids equal the input list in order —
  so a pager that split correctly but dropped or reordered a page fails.
* **`transientFailuresDoNotCountAgainstTheAttemptsCap`** fails exactly three times, which is
  `DEFAULT_MAX_ATTEMPTS` — the number that *was* the terminal threshold. A test using two failures
  would have passed before the change.
* **`ladderOutgrowsTheOldSeventeenMinuteCap`** names the pre-change ceiling as a literal and asserts
  the third step exceeds it, so the case cannot silently pass if the ladder were reverted to
  exponential-with-cap.
* **`WorkerOpsQueueMetricWireFormatTest`** asserts the supplier *values* (`4242`, `1717`) reach the
  NDJSON, not just the names. A gauge wired to the wrong supplier would still print its own name —
  which is precisely how RISK-002 could have acquired a metric that measures nothing.
* **`untypedFailurePathKeepsTheAttemptsCap`** is the adverse-precondition case for 21a: the cap is
  removed for classified transients and kept for the untyped path, so a change that removed it
  everywhere fails here.

### UC.5 Tri-state / stale-flag / asymmetric-lifecycle checks

* **Unknown ≠ healthy (sampler).** A failed sample is stored *as a sample* with `failed=true`, and
  `workerRpcStale` is derived from it; the previous behaviour (fallback view + stale) is preserved
  bit-for-bit. `WorkerSnapshotTap`'s existing stale-short-circuit is untouched, so a fallback view
  still cannot clear a real `queue-db.unhealthy` condition.
* **Unknown ≠ healthy (period).** `samplingPeriodMs()` returns the **idle** period when the last
  sample failed. Returning the busy period would pin the monitor at 2 s for the whole duration of a
  Worker outage — a stale-flag short-circuit inverted into a cost.
* **Unknown ≠ zero (schema).** `first_failed_at` is `NULL` for every pre-V10 row, and
  `IngestionRetryLadder.exhausted` treats `<= 0` as "no run in progress, never exhausted". Had the
  migration backfilled `0`, every migrated row would have read as "first failed in 1970" and gone
  terminal on its next transient failure. The migration test asserts the NULL explicitly and says
  why.
* **Asymmetric lifecycle.** The monitor's re-arm is the only new lifecycle; `close()` sets `closed`
  before `shutdownNow()`, and `scheduleNextTick` checks `closed` first, so there is no start-without-
  stop. No new executor, thread or file handle is created by either item.
* **Counter drift.** `QueueThroughputMeters` buckets are stamped with their epoch-second and
  re-zeroed on reuse, so a slot from an earlier revolution of the ring cannot be summed into the
  trailing window. Reads take no queue lock, so the OTel flush thread never contends with the lock it
  is measuring.

### UC.6 Deliberate deviations from the chunk brief

1. **No `StatusMeta` field for the sample age.** The brief asks `/api/status` to report the sample's
   age. `meta.workerRpcAtMs` *is* the sample time under the new semantics, so the age is
   `now - workerRpcAtMs` with no wire change. Adding a field would have meant editing
   `contracts/wire/status.proto`, regenerating the FE schema types and running the `wire` gate — for
   a value the consumer can already compute, on a surface design decision 4 explicitly says to keep
   minimal because lane F collapses it.
2. **Metric names are `worker.job_queue.*`, not `worker.queue.*`.** See UB.2a: the register names no
   metric, and `worker.job_queue.*` is the prefix the five existing queue gauges already use.
   Opening a second prefix for the same subject would be the fork the namespace guard exists to
   catch.
3. **Per-outcome counters are ONE tagged counter, not fourteen names.** `worker.job_queue.outcome.total`
   with a single `outcome_class` tag and `cardinalityLimit(32)`. The 14 outcome classes are one
   closed vocabulary always read together; fourteen names would be fourteen things to keep in sync.
4. **Lock-wait is two gauges, not a histogram.** `lock_wait_max_ms` + `lock_wait_avg_ms` over the
   same trailing minute as the rates. A histogram would need bucket boundaries chosen before anyone
   has ever measured this lock; the max is the number RISK-002's trigger reads, and the pair can be
   promoted once there is a distribution to bucket.
5. **Lock-wait is measured on two paths, not forty.** `enqueueEntries` and `pollPending` — the
   enqueue side (≥6 caller threads) and the dequeue side (1 caller). RISK-002 is about contention
   between those two; instrumenting the ~40 read paths would measure the same lock again at the cost
   of touching every method in the class.
6. **`GplJobCoordinator.fetchSingleDocContent` (`:670`) is left calling `fetchDocuments` directly.**
   It fetches exactly one id, so its worst case is one 200k-char document (~600 KB) — already two
   orders of magnitude under the ceiling. Routing it through the pager would add a loop that can
   only ever run once. Stated rather than silently skipped.
7. **No proto change**, per design decision 4: `RETRY_EXHAUSTED` rides the existing `string state`
   field (UB.2c) and the `FetchDocuments` budget is a caller-side pager. Two comment-only edits to
   `modules/ipc-common/src/main/proto/indexing.proto` (the state list, and `error_message`'s "empty
   when not FAILED" which was already inaccurate) are the whole `.proto` diff; `contracts/**` is
   untouched, so the `wire` gate has nothing new to judge.

### UC.7 Sweep: what the two items actually touched

Item 6: `StatusLifecycleHandler` (sample record + cache + `buildStatusMap(boolean)` +
`feedHealthTaps` + `samplingPeriodMs` + `?fresh=true`), `CoreApiAssembly` (the attached thunk),
`LocalApiServer` (`statusSamplingPeriodMs()` accessor), `HeadlessApp` (the interval supplier),
`KnowledgeServerHealthMonitor` (re-arming schedule + `tickIntervalSupplier` + clamp).
`FetchDocuments`: new `BoundedDocumentFetch` + three of the four callers.
Item 21: `IngestionRetryLadder` (new), `SqliteSchema` V10, `SqliteQueueMigrationOps` case 10,
`SqliteJobQueue` (state constants, `readFailureRun`, the ladder branch, `first_failed_at`, five
`state = 'FAILED'` queries widened, `countByPathPrefix` switch, meters, outcome observer, public
`DEFAULT_MAX_ATTEMPTS`, `lockTimed`), `QueueThroughputMeters` (new), `JobBatchExtractor` (six
literals → `failureDetail(e)`), `WorkerOpsMetricCatalog` + `QueueOutcomeTags` (new),
`KnowledgeServer` (cap + observer + four suppliers), `IndexingJobView` (`STATE_*`),
`IndexingJobsBridgeWiring`, `ScanRollupLedger`, `indexingJobsBridge.ts`, `ActionLedgerClient.ts`,
`governance/operation-surfaces.v1.json` (three notes naming the terminal set).
Docs: `03-knowledge-server.md` §job queue (the ladder table + the exhausted state + the attempt
semantics note), `08-observability.md` §health sampling, `health-readiness-contract.v1.md`
(freshness semantics). No new configuration key was introduced — the sampler's two periods are
constants, per the brief's "prefer none".

### UC.8 Findings

Zero actionable findings from the passes above; the two behaviour changes worth naming (taps no
longer reconcile on the request path, `checkHealth` runs at 2 s while indexing) are recorded in
UC.1 and UC.2 as deliberate rather than left implicit.

One **routed** finding, outside this chunk's scope: `GrpcIngestService.retryIndexingJob`
(`:2158-2161`) hard-codes `setPreviousState("FAILED")` on success without ever reading the row's
state. That was already inaccurate for a `PENDING`-in-backoff job and is now also inaccurate for a
`RETRY_EXHAUSTED` one. It is a diagnostic field on the retry response with no consumer that branches
on it; fixing it needs a state read inside the same transaction as the enqueue, which is more than a
comment. Routed to item 21's open items rather than fixed here.

### UC.9 Live items still open (items 6 and 21)

Neither can run in a unit tier; both need the shared dev stack and are scheduled by the
orchestrator, not by this chunk.

1. **Health SSE advances with no client polling** — subscribe to `/api/health/events/stream`, issue
   **zero** `/api/status` calls, stop the Worker, and assert the transition arrives within one
   monitor period. The unit tier proves the sampler feeds the taps
   (`WorkerStatusSamplerTest`, `ReadinessTriggerCompositionTest`); only the live stack proves the
   SSE fan-out carries it to a subscriber.
2. **`/api/status` p50 < 5 ms** — the acceptance number for "no RPC on the request thread". Measure
   with the stack warm, after at least one sample has been taken (the first request of a process is
   allowed to be slow by design, UB.1c).
3. **The fast sampling arm under real indexing** — confirm the monitor actually ticks at ~2 s while
   a scan is in flight and returns to ~10 s when it drains. The idle/busy decision is unit-tested
   against a synthetic view; only a live run proves the Worker's `processingJobsCount` is non-zero
   for long enough to drive it.
4. **The queue metrics under a bulk import** — `worker.job_queue.enqueue_rate_per_min`,
   `dequeue_rate_per_min`, `lock_wait_max_ms`, `lock_wait_avg_ms` non-trivial during a jseval
   pipeline run, and the per-outcome counter carrying at least one class. This is RISK-002's
   instrument; a field run that cannot show a rate means the instrument is wrong, not the result.
5. **A live `RETRY_EXHAUSTED`** cannot be produced in seven days of wall clock, and is not worth a
   clock-injection seam in the queue for one live assertion. The unit tier owns the boundary; the
   live tier can only confirm that an exhausted row, if one is planted by JDBC, renders as a failed
   task on the rail and as "Index gave up" in the ledger.

## Item 6 live (2026-09-02)

Run on the lane-C5 stack (runId `cf50b876-18d6-422d-b3b9-97aee016ede5`, API `127.0.0.1:56253`,
dataDir `…/lane-C5/modules/ui-web/.dev-data`, compact profile, AI offline, Worker READY), which
carries #600's sampler + ladder on top of the pacing/pool/cadence work. HTTP + logs only; no Gradle
and no MCP dev tools (a lane-C5 measurement window was live). Head PID 32516, Worker PID 34104,
extraction sandbox child PID 10288 — the last one is item 14's persistent child, observed alive and
serving, which is incidental live evidence for that item.

### Results

| # | Acceptance item | Result | Evidence |
|---|---|---|---|
| 1 | Health SSE advances with **no** `/api/status` call | **PASS** | See UL.1 |
| 1 | Worker-lost transition within one sampler period | **PASS — 786 ms** | See UL.1 |
| 2 | `/api/status` p50 < 5 ms | **PASS — p50 1.27 ms** | See UL.2 |
| 2 | No Worker RPC on the request thread | **PASS — 260 requests, 4 samples** | See UL.2 |
| 2 | `?fresh=true` forces exactly one synchronous sample | **PASS** | See UL.2 |
| 3 | 2 s sampling arm while indexing | **PASS — 17 consecutive ~2013 ms intervals** | See UL.3 |
| 3 | 10 s sampling arm while idle | **PASS — 10002/10004/10004/10003 ms** | See UL.3 |
| 4 | Queue throughput metrics non-trivial under load | **PASS** | See UL.4 |
| 4 | Per-outcome counter | **NOT EXERCISED** — no failure occurred | See UL.4 |
| 5 | Retry ladder live (locked file → `IO_FAILED`) | **INCONCLUSIVE** — fault injection did not reproduce the catch site | See UL.5 |

### UL.1 The stream advances without anyone polling

`curl -N` on `/api/health/events/stream` for the whole window; **zero `/api/status` calls** were
issued between subscribing and the kill. Adding the 30-file root at 07:33:51.9 produced no condition
delta, which is correct — a healthy scan crosses no condition threshold — only the 15 s heartbeats
(seq 20/22/23/24), so the stream was demonstrably live throughout.

Worker killed at **07:34:59.163** (`Stop-Process -Id 34104 -Force`, the Worker only; the Head was
left alone):

| SSE seq | Frame `ts` | Δ from kill | Event |
|---|---|---|---|
| 25 | 07:34:59.949 | **+786 ms** | `condition-added worker.capability` — `Recovering`, "worker process died; restarting" |
| 26 | 07:34:59.952 | +789 ms | `occurrence-appended worker.restart-attempted` (attempt 1, backoffMs 1000, faultKind `death`) |
| 27 | 07:34:59.955 | +791 ms | `condition-added index.unavailable` (ERROR, recovery `core.rebuild-index`) |
| 28 | 07:34:59.955 | +791 ms | `condition-added embedding.readiness-unknown` |
| 31 | 07:35:08.744 | +9.58 s | `condition-removed worker.capability` |
| 32 | 07:35:08.746 | +9.58 s | `occurrence-appended worker.recovered` (recoveredAfterAttempts 1) |
| 33 | 07:35:09.325 | +10.2 s | `condition-removed index.unavailable` |

786 ms is well inside one sampler period, and it is *faster* than a period on purpose: the
capability transition arm (`ReadinessReconciliationTrigger.wireTo(worker, inference)`) requests a
reconcile the moment the supervisor flips `WorkerCapability`, so the periodic tick is the floor for
a change nobody announces, not the latency for one that is announced. The Head's own supervision
restarted the Worker and the conditions cleared without intervention.

Note `embedding.readiness-unknown` rather than a cleared/healthy assertion while the Worker was
down — the tri-state discipline (`unknown ≠ healthy`) holding on a live outage.

### UL.2 `/api/status` latency and the absent RPC

200 sequential calls over one keep-alive connection:

```
n=200  min=0.60  p50=1.27  p90=2.06  p95=2.67  p99=4.38  max=6.73   (ms)
```

**p50 = 1.27 ms** against the < 5 ms criterion; even p99 is under it.

The stronger evidence is the interaction count, since a fast response could in principle still have
made a call. 260 requests over a 33.2 s idle window returned **4 distinct `workerRpcAtMs` values**,
i.e. one observation per ~10 s regardless of request rate, with the reported age sawtoothing 0 →
9948 ms and `workerRpcStale` false throughout. A per-request RPC would have produced 260 distinct
values.

`?fresh=true`, sampled around one call:

```
cached  age= 7848ms sample=1788334609057
FRESH   age=  -59ms sample=1788334617074   <== NEW SAMPLE
cached  age=   99ms sample=1788334617074
cached  age=  208ms sample=1788334617074
```

Exactly one new sample, and the following cached reads inherit it. The cost difference is itself the
measurement of what left the request thread: **`?fresh=true` 49.8 ms vs cached 1.85 ms** — a 27x
gap, which is the blocking `IndexStatus` unary plus tap reconciliation that every `/api/status` hit
used to pay. (The −59 ms age is clock granularity between the measuring shell and the Head's
`System.currentTimeMillis`.)

### UL.3 The two sampling arms

**Idle** (no watched roots, 260 requests / 33.2 s): inter-sample deltas **10014, 10014, 10003 ms**.

**Busy** — an 822-file root (`lane-C5/docs`) added at 07:39:12.9, in-flight queue depth peaking at
**680**:

```
deltas (ms): 10002, 10004, 2013, 2013, 2011, 2012, 2010, 2019, 2017,
             2007, 2005, 2018, 2012, 2005, 2016, 2019, 2016, 2012, 2013
```

The arm engages and holds ~2013 ms for 17 consecutive intervals, then the run ends. Both arms behave
as specified.

**UL.3a — a real limitation the live run exposed, which the unit tier could not.** The first attempt
at this used the 30-file root and produced **zero** 2 s intervals: 420 requests over ~60 s, 7
samples, all 10 s apart, with `pendingJobsCount + processingJobsCount` reading 0 at every sample.
The scan had drained inside a single idle gap. This is inherent to the design, not a bug in it —
`samplingPeriodMs()` derives "busy" from the **last sample**, so the fast arm can only engage one
period after work becomes observable, and an ingest shorter than the idle period is never seen at
all. The two visible 10 s deltas at the head of the busy sequence above are that same one-period
lag. Stated plainly: **the fast arm is reachable only for ingests longer than ~10 s.** That is
arguably the right trade (a three-second ingest does not need 2 s health sampling, and the
alternative — sampling faster to discover whether to sample faster — is circular), but it was an
assumption before this run and is a measured fact after it.

### UL.4 Queue metrics — RISK-002's instrument, in the field

Read from the Worker's `telemetry/metrics-worker.ndjson`.

First (30-file) ingest, read after the queue had drained:

| Metric | Value |
|---|---|
| `worker.job_queue.enqueue_rate_per_min` | **30.0** |
| `worker.job_queue.dequeue_rate_per_min` | **30.0** |
| `worker.job_queue.depth` | 0.0 |

This one flush is the whole argument for the item. **Depth reads 0 while the rates read 30/min** —
the level says "nothing here", the rate says "thirty files just went through". Depth alone, which is
all RISK-002 had for the years it sat at status *Monitoring*, cannot tell a drained queue from an
idle one.

Under the 822-file load:

| Metric | Value |
|---|---|
| `worker.job_queue.enqueue_rate_per_min` | **1503.0** |
| `worker.job_queue.dequeue_rate_per_min` | **560.0** |
| `worker.job_queue.depth` | **313.0** |
| `worker.job_queue.lock_wait_max_ms` | 0.0 |
| `worker.job_queue.lock_wait_avg_ms` | 0.0 |

Depth 313 **and** drain 560/min together state what neither states alone: the queue is 313 deep and
emptying at 9.3 docs/s, so it clears in ~34 s. That is the shape RISK-002's ">2x throughput
regression" trigger needs on both sides of a comparison.

**The zero lock-wait is a result, not a gap.** At 1503 enqueues/min against one dequeue caller, the
single `ReentrantLock` never made anyone wait a measurable millisecond. RISK-002 hypothesises write
contention; at this scale the instrument says there is none, which is the first evidence either way
the risk has ever had. A corpus large enough to contend is what would move it.

`worker.job_queue.outcome.total` is **absent from the wire**, correctly: no job failed during the
window, so the counter never incremented and no series exists. It stays unexercised live; the unit
tier covers it (`WorkerOpsQueueMetricWireFormatTest` asserts all three tag values reach the NDJSON,
including the `UNKNOWN` fallback).

Rescan behaviour was as expected throughout: removing and re-adding a root reported
`deletedJobs: 31` / `822` and re-enqueued cleanly, with no stale rows and no unexpected resets.

### UL.5 Retry ladder — inconclusive, and why

Two attempts, both using a Windows exclusive handle
(`[System.IO.File]::Open(…, FileShare::None)`) to make a file unreadable, both failing to reach the
`IOException` catch site in `JobBatchExtractor`:

1. **`zz-locked-probe.md` inside the 30-file root**, locked across a full rescan
   (07:37:23 → 07:38:38, rescan at 07:37:32). `failedJobs` stayed 0 and no backoff appeared. Note
   that `failedJobs` reading 0 is *by itself* consistent with item 21a working — a transient failure
   is no longer `FAILED` — so this attempt could not distinguish "the ladder held it PENDING" from
   "no failure happened".
2. **A dedicated two-file root** (`lane-C5/ladder-probe`, one locked + one readable) added at
   07:41:38 to remove that ambiguity. The Worker logged `Indexing batch complete: 1 indexed, 0
   skipped, 0 failed` — the readable file — and the locked file sat at `pendingJobsCount = 1` with
   `pendingBackoffJobsCount = 0`, `failedJobs = 0`, `nextRetryAtMs = 0` for 96 consecutive samples.
   The job existed and was never failed, but it was also never put in backoff, so this does **not**
   demonstrate the ladder; it shows the file never reached the extractor's failure path at all.

An earlier third attempt placed the probe under `lane-C5/tmp/`, which the scan excludes — no job was
created at all (120 samples, all zero). Recorded so the next attempt does not repeat it.

**Not reported as a pass, and not reported as a defect.** Nothing here contradicts the ladder; the
fault injection simply did not produce the fault. A faithful live reproduction needs a fault that
lands *inside* extraction rather than at open time — a file deleted between scan and extract, an ACL
change that makes the read fail mid-stream, or a directory made unreadable — and is a follow-up. The
seven-day bound and the attempts-cap behaviour are unit-tier by construction anyway
(`JobQueueRetryLadderTest`, 8 tests, including the three-`IO_FAILED`-stays-PENDING case and the
`RETRY_EXHAUSTED` → rescan reset). `RETRY_EXHAUSTED` itself remains unit-tier by design.

### UL.6 Two out-of-scope observations, routed not investigated

1. **`POST /api/indexing/roots` appears to drop a supplied `collection`.** Posting
   `{"path": "…/docs/explanation", "collection": "lane-c4-live"}` returned `{"status":"ok"}`, and
   `GET /api/indexing/roots?counts=true` then reported that root with `"collection":"default"`. The
   contract (`docs/reference/api-contract-map.md`, Watched Roots API) says `collection` is optional
   and defaults to `"default"` **when omitted** — it was not omitted. Not this lane's surface;
   routed, not chased. **Owner: `IndexingController.handleAddRoot`
   (`modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java:156-175`)** — either the
   handler drops the field or `GET /api/indexing/roots` projects it wrong; a two-line reproduction
   is `POST` with a `collection` then `GET …?counts=true`. Filed here as lane C residue because it
   surfaced in this lane's live window; it belongs to whichever tempdoc owns the Library surface.
2. **The stack was serving mutating routes with an empty session token — checked, and by design.**
   `GET /api/mcp/token` returned `{"token":""}` and an unauthenticated `POST /api/indexing/roots`
   succeeded. Verified rather than assumed: `ApiSecurityFilters.setupSessionTokenEnforcement`
   (`ApiSecurityFilters.java:441-443`) returns early when `!prodMode`, so dev mode installs no
   enforcement at all, and the production path fails **closed** — the constructor throws when
   `prodMode && (sessionToken == null || sessionToken.isBlank())` (`ApiSecurityFilters.java:116`),
   which is ADR-0046's stated trade ("a Head that will not start is a bug report, a Head serving
   mutations without a token is not"). Not a finding; recorded so the next live run does not
   re-raise it.

### UL.7 What is now closed on §UC.9, and what is not

Closed by this run: item 1 (health SSE with no polling), item 2 (`/api/status` p50), item 3 (the
fast sampling arm — with UL.3a's limitation attached), and item 4's throughput half.

Still open: item 4's per-outcome counter (needs a live failure), and item 5, which this run
downgraded from "not worth doing live" to "attempted, fault injection insufficient" — the note in
§UC.9 that a live `RETRY_EXHAUSTED` is not reachable in wall clock still holds, but a live
*`IO_FAILED` → PENDING with backoff* is reachable and has not yet been shown.

### UL.8 Post-merge verification (2026-09-02, after merging origin/main)

Full sequence on the merged tree, isolated Gradle home, another worker building concurrently.

| Check | Result |
|---|---|
| `spotlessApply` | clean, no diff |
| `build -x test -PskipWebBuild=true` | **green** |
| `:modules:dead-code-audit:test` (sysaccess funnel) | **2/2 green**, allowlist unchanged — this chunk adds and removes no `System.getenv`/`getProperty` site |
| Full `./gradlew.bat test -PskipWebBuild=true` | **8733 tests, 0 failed, 0 errors, 26 skipped, 33 modules** |
| `--gate operation-surface` | pass |
| `--gate config-surface --preflight origin/main` | "No gates affected by this diff" |
| `--gate config-surface` (bare) | **fail — pre-existing on `main`, see below** |
| docs regen `--check` (llms.txt, skills-sync, canonical links, module-deps, runtime-config-matrix) | all OK |
| `npm run typecheck` + the three affected FE suites | clean, 70/70 |

**The 13 sandbox failures this chunk had routed are gone.** `:modules:worker-services:test` is now
**1122 tests, 0 failed** — chunk 2c's `@argfile` fallback fixed the `CreateProcess error=206` at the
root, exactly as §TC.8 (main's version) says.

**Three genuine dead-code items this chunk created, found by `UnreferencedCodeTest` and fixed, not
suppressed.** The full suite's only real failure was
`UnreferencedCodeTest > no_unreferenced_non_public_methods`, naming:

* `SqliteJobQueue.readAttempts` — its only caller became `readFailureRun` (item 21b), so it was
  residue of this chunk's own change. Deleted.
* `StatusLifecycleHandler.buildStatusMap()` (the no-arg overload) — production reaches the read path
  through `buildStatusSnapshot()`, so the overload was a test-only alias, i.e. a
  backwards-compatibility shim. Deleted; the four `StatusReadinessStalenessTest` call sites and
  `WorkerStatusSamplerTest` now use `buildStatusSnapshot()`, which is what production calls.
* `StatusLifecycleHandler.lastWorkerSample()` — a test-only accessor onto handler internals.
  Deleted, and the assertions that used it were rewritten against the emitted DTO
  (`meta.workerRpcAtMs`, `meta.workerRpcStale`, `indexStatusReason`). That is strictly better: the
  failed-sample case now proves the exception text reaches **the response a consumer sees** rather
  than an internal field, and it matches the house style `StatusReadinessStalenessTest` already
  states ("Assertions are on the emitted DTO … not on the handler's internals").

The same pass swept the tap javadoc that still described reconciliation as happening inside the
`GET /api/status` handler — `LifecycleSnapshotTap`, `WorkerSnapshotTap`, `IndexDriftHealthTap`,
`AtRestHealthTap`, `ConversationProtectionHealthTap`, `StatusSnapshotProvider`, and
`ReadinessReconciliationTrigger` (whose "The defect" paragraph is kept as 876's dated statement, with
a new paragraph recording that item 6 inverted the relationship: `/api/status` now reads what the
trigger left behind). Left uncorrected, those comments would have been false authority pointing the
next reader at the request path.

Also fixed: `WorkerOpsPacingWireFormatRegressionTest` arrived from `main` constructing
`WorkerOpsMetricCatalog.Sources` with the pre-21e arity. Item 21e widened the record, so the four
queue suppliers are appended there and zeroed — that case is about the pacing trio, and
`WorkerOpsQueueMetricWireFormatTest` owns the queue wire assertions.

**`--gate config-surface` bare failure is pre-existing on `main`, and is not being papered over.**
The finding is `config-surface/silent-growth: env_sysprop_pairs 244 → 246 … without a declared
changeset` (the other six findings are `dead-key-baselined`, i.e. informational). It is not this
branch's: `git diff origin/main --name-only` lists no `EnvRegistry`, no `ResolvedConfig*`, no YAML
schema and no `gates/config-surface/**` file, and `--preflight origin/main` reports "No gates
affected by this diff". The baseline (`gates/config-surface/baseline.txt`, `env_sysprop_pairs 244`)
is stale relative to what `main` already ships, while `verify-runtime-config-matrix` is content at
246 — so the ratchet, not the surface, is what is behind. **Deliberately not advanced here:**
editing another lane's baseline to turn a red green is the exact move that file's own header calls
out ("appending a line is the one-line way to make the funnel test green without fixing anything").
Routed for an `expected-state` pin plus a tracked baseline advance.
