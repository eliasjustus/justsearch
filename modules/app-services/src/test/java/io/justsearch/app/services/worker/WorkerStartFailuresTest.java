package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Classification and bounded retry of a failed Knowledge Server start.
 *
 * <p>Regression cover for the Head self-bricking failure: a transient post-spawn validation timeout
 * was treated exactly like a missing worker build — the start was abandoned after one attempt, the
 * worker capability was pinned DEGRADED for the life of the process, and the operator was told to
 * build a module that was already built.
 */
@DisplayName("Worker start failure classification and retry")
final class WorkerStartFailuresTest {

  private static final String BUILD_HINT_FRAGMENT = "indexer-worker module is built";

  private static PidValidationTimeoutException transientFailure() {
    return new PidValidationTimeoutException(
        "PID validation timeout after 5000ms: expected PID 8556");
  }

  @Test
  @DisplayName("a PID-validation timeout is transient; an unstartable worker is not")
  void classification() {
    assertTrue(WorkerStartFailures.isTransient(transientFailure()));
    assertTrue(
        WorkerStartFailures.isTransient(new IllegalStateException("wrapped", transientFailure())),
        "the classifier walks the cause chain");

    assertFalse(WorkerStartFailures.isTransient(new IOException("Cannot run program java")));
    assertFalse(WorkerStartFailures.isTransient(new IllegalStateException("Timeout waiting for worker port")));

    assertTrue(WorkerStartFailures.isLikelyUnstartableWorker(new IOException("Cannot run program java")));
    assertFalse(WorkerStartFailures.isLikelyUnstartableWorker(transientFailure()));
  }

  @Test
  @DisplayName("a cyclic cause chain terminates instead of spinning")
  void cyclicCauseChainTerminates() {
    IllegalStateException a = new IllegalStateException("a");
    IllegalStateException b = new IllegalStateException("b");
    a.initCause(b);
    b.initCause(a);
    assertFalse(WorkerStartFailures.isTransient(a));
    assertFalse(WorkerStartFailures.isLikelyUnstartableWorker(a));
  }

  @Test
  @DisplayName("only an unstartable worker gets the 'build the module' hint")
  void hintRouting() {
    assertTrue(
        WorkerStartFailures.operatorHint(new IOException("Cannot run program java"))
            .contains(BUILD_HINT_FRAGMENT),
        "a launch failure genuinely is a build/installation problem");

    String timeoutHint = WorkerStartFailures.operatorHint(transientFailure());
    assertFalse(
        timeoutHint.contains(BUILD_HINT_FRAGMENT),
        "a post-spawn validation timeout must NOT blame a missing build: " + timeoutHint);
    assertTrue(timeoutHint.contains("worker.log"), "it must point at the worker's own log");
    assertTrue(
        timeoutHint.contains("pid_validation_timeout_ms"),
        "it must name the knob that widens the window");
  }

  @Test
  @DisplayName("an unclassified failure gets an honest hint, not a confident wrong one")
  void unclassifiedHintIsConditional() {
    String hint = WorkerStartFailures.operatorHint(new IllegalStateException("Worker process crashed"));
    assertTrue(hint.contains("worker.log"));
    assertTrue(hint.contains("If the worker never started"), "the build advice stays conditional");
  }

  @Test
  @DisplayName("a transient first failure is retried and the second attempt sticks")
  void retriesTransientFailure() throws Exception {
    int[] attempts = {0};

    WorkerStartFailures.startWithRetry(
        () -> {
          if (++attempts[0] == 1) {
            throw transientFailure();
          }
        },
        3,
        0);

    assertEquals(2, attempts[0], "the start recovered on its second attempt");
  }

  @Test
  @DisplayName("a non-transient failure is rethrown from the first attempt, unwrapped")
  void doesNotRetryGenuineFailure() {
    int[] attempts = {0};
    IOException missingBinary = new IOException("Cannot run program java");

    IOException thrown =
        assertThrows(
            IOException.class,
            () ->
                WorkerStartFailures.startWithRetry(
                    () -> {
                      attempts[0]++;
                      throw missingBinary;
                    },
                    3,
                    0));

    assertSame(missingBinary, thrown, "the original error reaches the operator unchanged");
    assertEquals(1, attempts[0], "no added boot latency for a genuinely broken installation");
  }

  @Test
  @DisplayName("the attempt budget is honoured; the last failure propagates")
  void exhaustsBudget() {
    int[] attempts = {0};

    assertThrows(
        PidValidationTimeoutException.class,
        () ->
            WorkerStartFailures.startWithRetry(
                () -> {
                  attempts[0]++;
                  throw transientFailure();
                },
                3,
                0));

    assertEquals(3, attempts[0]);
  }

  @Test
  @DisplayName("an interrupt is never retried")
  void interruptIsNotRetried() {
    int[] attempts = {0};

    assertThrows(
        InterruptedException.class,
        () ->
            WorkerStartFailures.startWithRetry(
                () -> {
                  attempts[0]++;
                  throw new InterruptedException("shutting down");
                },
                3,
                0));

    assertEquals(1, attempts[0]);
  }
}
