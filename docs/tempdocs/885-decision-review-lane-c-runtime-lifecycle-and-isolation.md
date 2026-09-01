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

**Thesis.** Four lifecycle decisions from November–December 2025 still run the Worker exactly as
first written, and each has a defect the project already knows about. Indexing pauses whenever
*any* foreground request arrived in the last two seconds, so agents and evals throttle the index
they depend on. The out-of-process extraction sandbox that would make a wedged native parser
survivable is built but unreachable as shipped. The health substrate advances only when a client
polls `/api/status`, and each poll blocks on a Worker RPC. The job queue permanently fails a file
after three transient errors. This lane re-decides each with the long-term shape in mind: the
extraction sandbox becomes a persistent process pool (the correct home for crash isolation, and
the precondition for lane F's single-JVM engine), pacing becomes contention-based and
process-agnostic, health sampling becomes internal, and failure classes become explicit.

Lane C is on the critical path (see `882-…lane0-hygiene.md` for the split and cross-lane rules).
Lane D (index identity + migration) and lane F (engine merge) branch after it merges. Design
everything here so that it survives the Head/Worker merge: no new MMF fields, no new Head→Worker
signals; the Worker observes its own foreground load.

## Scope (contract)

| # | Item | This lane does | Not this lane |
|---|---|---|---|
| 14 | extraction isolation | ship the `process` sandbox mode as a **persistent child process pool** (1 process by default, restart on crash/timeout, bounded queue) with a shipped launch command built from the running JVM + Worker classpath; make it the default for the parser families that can wedge (PDF, Office, archives) and measurable for all; keep `in_process` as an explicit opt-out | changing Tika policy, OCR routing, VDU (790), file-size limits |
| 3 | breath-holding | replace "recent activity" pacing with a Worker-local **foreground-load gauge** (in-flight search / rerank / retrieveContext / fetch RPCs) plus the existing GPU arbitration slot; delete the `justsearch.eval.disable_breath_holding` hatch and the `activity_epoch_ms` yield sites | the MMF layout (lane 0 fixed it; lane F deletes it); LoopPacingPolicy's commit cadence numbers |
| 6 | health sampling | an internal scheduler samples Worker health and feeds `ConditionStore` + `HealthEventChangeRegistry`; `/api/status` returns the latest snapshot without a Worker RPC on the request thread; the health SSE stream advances with no client polling | the frontend's 10 s poll (`modules/ui-web/.../statusPoll.ts`, out of scope; it becomes cheap and can be retired by a UI lane later) |
| 21 | job queue failures | classify errors transient vs permanent; time-based retry with backoff for transient (AV lock, sync-client lock, network drive); permanent-fail only on parser/policy outcomes; single source for max-attempts; a throughput metric (RISK-002's missing instrument) | replacing SQLite; schema ladder |
| 19 | NRT + commit cadence | **measure first**: jseval indexing throughput + search p95 at the current 50/500 ms reopen and 10 s/1000-doc commit versus on-demand reopen + 2–5 s background cadence; change the defaults only if the measurement says so, and record it either way | the Lucene writer config beyond cadence (lane D owns codec/schema) |

## File ownership (no other wave-1 lane edits these)

`modules/worker-services/.../extract/**` (TimeboxedContentExtractor, ProcessExtractionSandbox,
ExtractionSandboxChild, ExtractionSandboxFactory, DefaultWorkerAppServices sandbox wiring),
`modules/worker-services/.../loop/**` (IndexingLoop, LoopPacingPolicy, BackfillScheduler,
`*BackfillOps` yield sites, JobBatchExtractor), `modules/indexer-worker/.../coordination/MmfWorkerSignalBus.java`
(`isUserActive` only), `modules/indexer-worker/.../queue/**`, `KnowledgeServer.java` queue
construction + health sampler wiring, `modules/worker-services/.../services/GrpcSearchService.java`
(in-flight gauge instrumentation only), `modules/ui/.../api/StatusLifecycleHandler.java`,
`CoreApiAssembly.java` (taps), `modules/app-services/.../worker/KnowledgeServerBootstrap.java`
(`signalUserActivity` retirement), `modules/adapters-lucene/.../CommitOps.java` +
`RuntimeSession.java` NRT stale-time defaults (item 19 only), the affected MetricCatalogs,
`docs/explanation/02-process-coordination.md` §breath-holding, `03-knowledge-server.md`
§extraction + §job queue, `08-observability.md` health sampling.

Lane A owns `configuration/**` and the Head config phase; lane B owns `docs/decisions/**`. New
config keys this lane needs go in through `EnvRegistry` as a one-line request to lane A, or land
after lane A merges.

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
- Runtime pieces for a shipped command already exist: the Worker locates its own JVM via
  `java.home` (`WorkerSpawner.java:921-929`), and the Worker classpath is `lib/*`
  (`WorkerSpawner.java:576-586`); the child can be launched with the same pair from inside the Worker.

### Item 3 — breath-holding

- `MmfWorkerSignalBus.java:215-229`: `isUserActive()` = `activity_epoch_ms` written within 2000 ms;
  the eval hatch `justsearch.eval.disable_breath_holding` (`:219`) is read in the **Worker** JVM
  but set only as a Gradle sysprop on the **Head** (`modules/ui/build.gradle.kts:2183`) and is not
  in `WORKER_FORWARDED_PROPS` (`WorkerSpawner.java:71-…`), so it is very likely dead. Verify with
  a jseval run before deleting; if it is dead, tempdoc 326's fix never worked either.
- Pause site: `IndexingLoop.java:604-610` (`transitionToPaused`, sleep `BREATH_HOLD_MS = 500`,
  `LoopPacingPolicy.java:8`); 11 more yield sites in `BackfillScheduler.java:239,430,612`,
  `JobBatchExtractor.java:128`, and the six `*BackfillOps` classes.
- Writers of the activity slot: `WorkerSpawner.signalUserActivity()` (`:334-338`) via
  `KnowledgeServerBootstrap.signalUserActivity()` (`:698`), called from
  `KnowledgeSearchController.java:304,849,887,931` (search/answer paths) and
  `CoreApiAssembly.java:110` (preview). So every search, every agent tool call that searches,
  every eval query, pauses indexing for a 2 s window. "User activity" and "foreground request"
  were conflated in 2025-11 and never separated.
- The GPU arbitration slot (`OFFSET_MAIN_GPU_ACTIVE`, `MainSignalBus.java:176` →
  `MmfWorkerSignalBus.java:234`) already models the one contention that is real: VRAM.
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

### Item 21 — job queue

- `SqliteJobQueue.java:46,49,60,62,154-181`: one connection, one `ReentrantLock`, WAL,
  `synchronous=NORMAL`, `busy_timeout=5 s`, `DEFAULT_MAX_ATTEMPTS = 3`; `KnowledgeServer.java:394-401`
  passes a bare `3` (the only construction site). One dequeue caller (`IndexingLoop.java:613`),
  ≥6 enqueue callers on other threads. No throughput metric; 269 §A9 set RISK-002 to "Monitor"
  with the trigger ">2× throughput regression / >30 min bulk imports" and no instrument.
- Retry is attempt-counted, not error-classified: a file locked by an AV scanner or a sync client
  three times in a row is permanently failed the same as a corrupt PDF.

### Item 19 — cadence

- `ComponentsFactory.java:326` `ControlledRealTimeReopenThread(w, mgr, 0.5, 0.05)`;
  `RuntimeSession.java:103-105` default 50 ms "must match the hardcoded 0.05s";
  `CommitOps.java:34` 10 s timer; `ResolvedConfigBuilder.java:1123-1124` `commit_interval_ms`
  10 000 / `max_docs_before_commit` 1000; `IndexingLoop.java:673-689` the trigger. Unchanged since
  the root commit; 402 fixed writer coordination, never cadence. Each reopen during bulk indexing
  builds a new searcher and re-touches HNSW per new segment.

## Design decisions this lane must make (recommendation in bold)

1. **Sandbox process lifetime.** **Persistent child, length-prefixed request/response frames on
   stdin/stdout, one in flight at a time per child, pool size 1 by default (`justsearch.extraction.sandbox.pool`).**
   On timeout: kill the child, mark the file `FAILED/TIMEOUT` (already a status), respawn lazily.
   On crash: same, with the child's exit code and bounded stderr in the failure reason. The child
   command is built in-process: `<java.home>/bin/java -cp <worker lib/*> io.justsearch.indexerworker.extract.ExtractionSandboxChild --serve`;
   `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND` remains an operator override. Reuse the existing
   JSON records; add a `--serve` loop to `ExtractionSandboxChild`. Memory: child heap fixed
   (`-Xmx512m` default, key `justsearch.extraction.sandbox.heap`), AOT cache reused if present.
2. **Default routing.** **`process` for PDF, Office, archives, and any OCR route; `in_process` for
   plain text, markdown, code, CSV/JSON.** One switch to force all-process for measurement. Record
   per-family p50/p95 extraction latency before/after; the round-trip cost must stay under 10 ms
   per file for the in-process families to justify keeping the split.
3. **Pacing signal.** **A Worker-local `ForegroundLoad` gauge**: a counter of in-flight
   foreground RPCs incremented/decremented by a gRPC `ServerInterceptor` for the search-family
   methods (Search, Rerank, RetrieveContext, FetchDocuments, FetchDocumentSlice, Suggest) plus the
   existing GPU-active slot. Pacing: while the gauge is >0 the loop yields between batches (no
   sleep-based pause); while GPU-active, GPU-bound backfills yield (as today). Delete
   `isUserActive`, the 12 yield sites' calls to it, `signalUserActivity` and its callers, and the
   eval hatch. This needs no Head signal, so it survives lane F unchanged.
4. **Health sampler.** **A `HealthSampler` in the Head** on a scheduled executor (default 2 s,
   backoff to 10 s when the Worker is down) that performs the one `IndexStatus` unary and feeds
   the existing taps; `StatusLifecycleHandler` reads the sampler's last snapshot + age and never
   calls the Worker. A `?fresh=true` query parameter forces one synchronous sample for debug
   tooling (`/api/debug/state` consumers). Under lane F the sampler becomes an in-process call;
   the shape does not change.
5. **Failure classes.** **`JobFailureClass { TRANSIENT_IO, PERMANENT_PARSE, PERMANENT_POLICY }`**
   derived from the exception type / `ExtractionStatus`; TRANSIENT retries with exponential
   backoff (1 min → 1 h, cap 24 h, unlimited attempts, visible in the jobs stream), PERMANENT
   fails on first occurrence (a second attempt at a corrupt file is wasted work). Attempts cap
   lives in one place (`SqliteJobQueue` constant exposed to `KnowledgeServer`). Metric:
   `queue.dequeue_rate_per_min` + `queue.enqueue_rate_per_min` in the Worker MetricCatalog; that
   is RISK-002's instrument.
6. **Cadence.** **Decide by measurement**, not opinion. Candidate: reopen on demand (searcher
   refreshed at query time if stale > 1 s) + background reopen every 2 s during bulk indexing;
   commit at 30 s / 5000 docs / idle. Ship only what the jseval numbers justify.

## Acceptance criteria

- **Chaos (live):** a poison PDF that wedges the parser (the chaos harness has one; if not, a
  synthetic infinite-loop parser behind a test policy) → child killed at the timeout, file marked
  `FAILED/TIMEOUT` with the reason, the next file extracts normally, Worker never restarts. A child
  crash (`kill -9`) → same outcome with the exit code in the reason. `extraction.sandbox.restart_total`
  increments.
- **Throughput (live, jseval):** `jseval run --pipeline` on the standard corpus with a concurrent
  search loop (10 queries/min) shows indexing throughput within 10% of the no-search run; today's
  ~5 → ~1 doc/s collapse (326) is gone. Search p95 during bulk indexing is recorded before/after
  and does not regress by more than 20%.
- **Health (live):** subscribe to `/api/health/events/stream`, issue **no** `/api/status` calls,
  stop the Worker → the stream carries the transition within one sampler period. `/api/status`
  p50 latency drops below 5 ms (no RPC on the request thread) and reports `sampledAt` age.
- **Queue (unit + live):** a file failing with a lock error three times stays `PENDING` with a
  backoff, not `FAILED`; a corrupt file fails on the first attempt; the throughput metrics appear
  in `/api/telemetry` (or the catalog's surface) and in the Worker MetricCatalog test.
- **Cadence:** a jseval comparison table (throughput, search p95, commit count, reopen count) for
  current vs candidate defaults is in this tempdoc; the shipped defaults match the winner.
- `grep -rn "isUserActive\|signalUserActivity\|disable_breath_holding" modules/*/src/main` → no hits.
- Gates: `--gate operation-surface` if any job-lifecycle surface changed; `check-readiness-reason-codes`
  if a reason code was added; MetricCatalog tests; `check-runtime-manifest-closure` if a new
  runtime file appears. Docs regenerated (`/docs-maintenance`): `02-process-coordination.md` no
  longer describes breath-holding as input-driven; `03-knowledge-server.md` describes the pool.
- `./gradlew.bat build -x test`; `:modules:worker-services:test`, `:modules:indexer-worker:test`,
  `:modules:ui:test`, `:modules:system-tests:test` (chaos); full `./gradlew.bat test` before closing.
- Independent review by a session other than the implementer; the reviewer reruns the chaos and
  throughput checks, not just reads the numbers (`static-green ≠ live-working`).

## Verification tier and dev-stack rules

Every live criterion above needs the shared dev stack; jseval campaigns are long holds. Lease
explicitly (`leaseDurationSec`), run the throughput comparisons as detached drivers with
self-terminating polls (agent-lessons: 60-minute task kill), and coordinate windows with lane A
through the user. Never take over another lane's lease. `/jseval` and `/dev-stack` must be loaded
before live work.

## Takeover checklist

1. Branch after `882-decision-review-lane0-hygiene` (#592) merges; lane 0 touched
   `WorkerSpawner` (flags), `MmfWorkerSignalLayoutV1`, `KnowledgeServerConfig`.
2. First live act: confirm whether the eval hatch is dead (run `runHeadlessEval` with polling and
   watch `IndexingLoop` pause logs). Record the answer in this tempdoc; it changes what 326 proved.
3. Implement in the order 14 → 3 → 6 → 21 → 19: 14 is the precondition for lane F, 3 removes the
   dependency 6 would otherwise have on the poll, 19 is measurement-gated and last.
4. Before deleting `isUserActive`, grep the 12 yield sites and replace each with the gauge check;
   `wrong-gate` is the failure mode here — assert in a test that the loop yields when the gauge
   is >0 and does not yield on a `/api/status` call.
5. Write the six design decisions into this tempdoc as §B with `path:line` before coding; run the
   post-impl critical-analysis pass; keep the diff inside the ownership list.

## Open questions for the owner

- Pool size 1 is the conservative default; on machines with ≥8 cores a pool of 2 would overlap
  extraction with embedding. Decide after the per-family latency numbers exist, or set it now?
- Should transient retries be unlimited with a 24 h cap, or give up after N days and surface the
  file in the UI as "could not be indexed"? Recommendation: unlimited with backoff plus a visible
  `RETRYING` state in the jobs stream, so nothing is silently dropped.
