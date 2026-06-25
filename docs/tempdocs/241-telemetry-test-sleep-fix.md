---
title: "241: Fix telemetry test sleep-for-flush pattern"
---

# 241: Fix telemetry test sleep-for-flush pattern

**Status:** Complete
**Parent:** tempdoc 238 (F3, F3-b)
**Goal:** Eliminate sleep-for-flush patterns in the `telemetry` module's unit tests. Add `flush()` to `LocalTelemetry` and `TracingBootstrap`, replace 11 metric sleeps and 2 tracing sleeps across 7 test files. Separately, add `awaitCompletion()` to `GplJobCoordinator` and replace 14 polling loops in `GplJobCoordinatorTest` with it.

---

## Root Causes

**F3 — Telemetry metrics:** `LocalTelemetry` enforces `Math.max(1000, flushMs)` as its minimum flush interval. Tests pass `200` or `500` ms but always get 1000 ms. They sleep 1500 ms to guarantee one flush cycle completes before reading the output file. The sleeps are redundant (the `close()` path already calls `forceFlush()`), but they add ~19.5 s of wall-clock time to the test suite and obscure intent.

**F3 — Telemetry tracing:** The same sleep-for-flush pattern exists in `TracingLocalExportTest` and `LogTraceCorrelationTest`. These use `TracingBootstrap` (span exporter backed by `BatchSpanProcessor`), which had no explicit flush API. They sleep 1000 ms before reading `traces.ndjson`. The sleeps are similarly redundant because `TracingBootstrap.close()` already flushes via `SdkTracerProvider.close()`.

**F3-b — GplJobCoordinator:** No blocking-wait API exists on the coordinator, so tests poll `getStatus()` every 50 ms in a deadline loop. The `onJobCompleted` Runnable callback (5-arg constructor) cannot substitute for a latch because it fires **only on `COMPLETED`**, not `FAILED`. Any test expecting a `FAILED` terminal state would hang 10 seconds before timing out if backed by that callback.

---

## F3 — Telemetry: add `flush()` and replace sleeps

- [x] **T1. Add `flush()` to `LocalTelemetry`.**

  File: `modules/telemetry/src/main/java/io/justsearch/telemetry/LocalTelemetry.java`

  Added after `getHealthState()`:
  ```java
  /**
   * Synchronously flushes all pending metrics to disk.
   *
   * <p>Useful for checkpointing before a crash report, and for driving tests that need to
   * inspect exported metrics without waiting for the next periodic flush cycle.
   *
   * <p>Failures are logged and recorded in the health state but not thrown; callers can
   * inspect {@link #getHealthSnapshot()} to detect flush failures.
   */
  public void flush() {
      try {
          meterProvider.forceFlush().join(5, TimeUnit.SECONDS);
      } catch (Exception e) {
          healthState.recordFlushFailure();
          log.warn("Explicit metrics flush failed: {}", e.getMessage());
      }
  }
  ```

  Not added to the `Telemetry` interface — this is a `LocalTelemetry`-specific capability (like `getRrdStore()` and `getHealthSnapshot()`), and forcing all implementations to carry it adds interface bloat without benefit.

  The `close()` path uses a 2s join timeout with a comment (`// 2s is intentional: best-effort during shutdown`). `flush()` uses 5s because its callers expect a reliable synchronous guarantee, not graceful-shutdown cleanup.

- [x] **T2. Replace `Thread.sleep(1500)` with `t.flush()` in 5 metric test files.**

  Note: `CollectorSmokeIT`'s 2 sleeps are **not** flush-related — they wait for an external `otelcol` process to start and ingest data. They were correctly left untouched. The actual count replaced was 11 calls in 5 files.

  | File | Occurrences replaced |
  |------|---------------------|
  | `modules/telemetry/src/test/java/io/justsearch/telemetry/LocalTelemetryTest.java` | 1 |
  | `modules/telemetry/src/test/java/io/justsearch/telemetry/LocalMetricsExporterTest.java` | 5 |
  | `modules/telemetry/src/test/java/io/justsearch/telemetry/LlmLatencyMetricTest.java` | 1 |
  | `modules/telemetry/src/test/java/io/justsearch/telemetry/HistogramBucketsTest.java` | 1 |
  | `modules/telemetry/src/test/java/io/justsearch/telemetry/JvmRuntimeGaugesTest.java` | 2 |

  All test variables hold `LocalTelemetry` directly (via `var`), so no casts were needed. The `Thread.sleep(5)` calls that remain in these files are measuring elapsed time for histogram samples — they are not flush waits and were correctly left untouched.

- [x] **T3. Add `flush()` to `TracingBootstrap` and replace `Thread.sleep(1000)` in 2 tracing test files.**

  File: `modules/telemetry/src/main/java/io/justsearch/telemetry/TracingBootstrap.java`

  Added `Logger` field and `flush()` method:
  ```java
  /**
   * Synchronously flushes all pending spans to disk.
   *
   * <p>Useful for driving tests that need to inspect exported traces without waiting for the
   * next batch export cycle. Failures are logged but not thrown.
   */
  public void flush() {
      try {
          tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
      } catch (Exception e) {
          log.warn("Explicit span flush failed: {}", e.getMessage());
      }
  }
  ```

  `SdkTracerProvider.forceFlush()` triggers `BatchSpanProcessor.forceFlush()`, which drains the in-flight span queue synchronously. No health-state recording here because `TracingBootstrap` has no equivalent of `TelemetryHealthState`; the log warning is sufficient.

  Test files updated (variable named `ignored` in both):

  | File | Sleep replaced |
  |------|---------------|
  | `modules/telemetry/src/test/java/io/justsearch/telemetry/TracingLocalExportTest.java` | `Thread.sleep(1000)` → `ignored.flush()` |
  | `modules/telemetry/src/test/java/io/justsearch/telemetry/LogTraceCorrelationTest.java` | `Thread.sleep(1000)` → `ignored.flush()` |

---

## F3-b — GplJobCoordinator: add `awaitCompletion()` and replace polling loops

- [x] **G1. Add `awaitCompletion()` to `GplJobCoordinator`.**

  File: `modules/app-services/src/main/java/io/justsearch/app/services/gpl/GplJobCoordinator.java`

  Added `CountDownLatch terminalLatch` field and `awaitCompletion()` method. The latch is counted down at all three terminal-state transitions:
  - FAILED — CompletableFuture exception handler (after `status.set(Status.FAILED)`)
  - FAILED — AI timeout path (after `status.set(Status.FAILED)`)
  - COMPLETED — normal completion (after `status.set(Status.COMPLETED)`, before `onJobCompleted` callback)

  `status.set()` always precedes `countDown()`, so the happens-before guarantee of `CountDownLatch` ensures callers see the correct terminal status when they read `getStatus()` after `awaitCompletion()` returns.

  The existing `onJobCompleted` callback is unaffected — it continues to fire only on `COMPLETED`. `awaitCompletion()` is a separate, additive API that fires on both terminal states.

  Javadoc documents: immediate return for already-completed coordinators, single-use-per-instance semantics, `@throws InterruptedException`, and `false` return on timeout.

- [x] **G2. Replace the 12 standard completion-polling loops in `GplJobCoordinatorTest`.**

  File: `modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplJobCoordinatorTest.java`

  Replaced every 5-line deadline/polling loop with:
  ```java
  assertTrue(<coordinator>.awaitCompletion(10, TimeUnit.SECONDS), "job did not reach terminal state");
  ```
  Covered variables: `coordinator` (8×), `coordinatorWithReranker` (1×), `coordWithReranker` (1×), `coordWithSpy` (1×), `coordWithCallback` (1×). Includes the test expecting `FAILED` status — `awaitCompletion()` fires correctly in that case.

- [x] **G3. Replace the thread-capture polling loop in `aiTimeoutSetsFailedAndSkipsCallback()` (special case).**

  Added `CountDownLatch threadCaptured = new CountDownLatch(1)` in that test. The `thenAnswer` lambda now calls `threadCaptured.countDown()` when `jobThread.compareAndSet()` succeeds. The polling loop was replaced with:
  ```java
  assertTrue(threadCaptured.await(10, TimeUnit.SECONDS), "job thread not captured");
  ```

---

## Verification

**F3 (telemetry):** `./gradlew.bat :modules:telemetry:test` — BUILD SUCCESSFUL, all tests pass.

**F3-b (app-services):** `./gradlew.bat :modules:app-services:test` — BUILD SUCCESSFUL, all tests pass. (Previously blocked by a pre-existing lock file issue from tempdoc 239's in-progress RuntimeConfig migration; that work was reverted by a parallel agent and the tests now run cleanly.)
