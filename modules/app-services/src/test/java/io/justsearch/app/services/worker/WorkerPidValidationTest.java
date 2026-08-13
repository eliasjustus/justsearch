package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Boot-time worker PID validation must spend its window over SEVERAL attempts.
 *
 * <p>Regression cover for the Head self-bricking failure observed on a contended CI runner: every
 * attempt inherited the STANDARD gRPC deadline, which equals the whole validation window by
 * default, so one slow cold health check consumed the entire budget and the "retry loop" never
 * iterated. Validation then threw, the bootstrap tore down a perfectly healthy worker, and the Head
 * served 503 for the life of the process.
 */
@DisplayName("Worker PID validation attempt schedule")
final class WorkerPidValidationTest {

  private static final long WINDOW_MS = 5_000;
  private static final long EXPECTED_PID = 4242;

  /** Records the per-attempt deadline it was handed, and behaves per a scripted plan. */
  private static final class RecordingProbe implements KnowledgeServerBootstrap.PidProbe {
    private final List<Long> deadlines = new CopyOnWriteArrayList<>();
    private final int timeoutsBeforeSuccess;
    private final long pidToReport;

    RecordingProbe(int timeoutsBeforeSuccess, long pidToReport) {
      this.timeoutsBeforeSuccess = timeoutsBeforeSuccess;
      this.pidToReport = pidToReport;
    }

    @Override
    public long reportedPid(long attemptDeadlineMs) throws Exception {
      deadlines.add(attemptDeadlineMs);
      if (deadlines.size() <= timeoutsBeforeSuccess) {
        // Faithful to gRPC: the call burns its whole deadline, then fails DEADLINE_EXCEEDED.
        Thread.sleep(attemptDeadlineMs);
        throw new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
      }
      return pidToReport;
    }
  }

  private static void neverRecovers(long remainingMs) {
    throw new AssertionError("stale-port recovery must not run when PIDs match or calls fail");
  }

  @Test
  @DisplayName("a first attempt that burns its deadline is followed by more attempts in the window")
  void slowFirstCallDoesNotConsumeTheWholeWindow() throws Exception {
    // Never succeeds: every attempt burns its full per-attempt deadline. The point is HOW MANY
    // attempts the window buys. Pre-fix that number was 1 (one 5s call ate the 5s budget).
    RecordingProbe probe = new RecordingProbe(Integer.MAX_VALUE, EXPECTED_PID);

    assertThrows(
        PidValidationTimeoutException.class,
        () ->
            KnowledgeServerBootstrap.awaitWorkerPid(
                EXPECTED_PID, WINDOW_MS, probe, WorkerPidValidationTest::neverRecovers));

    assertTrue(
        probe.deadlines.size() >= 3,
        "expected >= 3 attempts inside the " + WINDOW_MS + "ms window, got " + probe.deadlines);
    long total = probe.deadlines.stream().mapToLong(Long::longValue).sum();
    assertTrue(total <= WINDOW_MS, "attempt deadlines must not overspend the window: " + total);
  }

  @Test
  @DisplayName("per-attempt deadlines escalate from 1s and stay clamped to the remaining budget")
  void attemptDeadlinesEscalate() throws Exception {
    RecordingProbe probe = new RecordingProbe(Integer.MAX_VALUE, EXPECTED_PID);

    assertThrows(
        PidValidationTimeoutException.class,
        () ->
            KnowledgeServerBootstrap.awaitWorkerPid(
                EXPECTED_PID, WINDOW_MS, probe, WorkerPidValidationTest::neverRecovers));

    assertEquals(1_000L, probe.deadlines.get(0), "first attempt is short — it is the cold one");
    assertEquals(2_000L, probe.deadlines.get(1), "second attempt doubles");
    for (long d : probe.deadlines) {
      assertTrue(d <= WINDOW_MS, "no attempt may claim more than the whole window: " + d);
    }
  }

  @Test
  @DisplayName("a cold first call that times out still validates once the worker answers")
  void secondAttemptValidates() throws Exception {
    RecordingProbe probe = new RecordingProbe(1, EXPECTED_PID);

    KnowledgeServerBootstrap.awaitWorkerPid(
        EXPECTED_PID, WINDOW_MS, probe, WorkerPidValidationTest::neverRecovers);

    assertEquals(2, probe.deadlines.size(), "one timeout, then a successful attempt");
  }

  @Test
  @DisplayName("a fast first call validates without spending further attempts")
  void firstAttemptValidates() throws Exception {
    RecordingProbe probe = new RecordingProbe(0, EXPECTED_PID);

    KnowledgeServerBootstrap.awaitWorkerPid(
        EXPECTED_PID, WINDOW_MS, probe, WorkerPidValidationTest::neverRecovers);

    assertEquals(1, probe.deadlines.size());
  }

  @Test
  @DisplayName("a PID mismatch runs stale-port recovery, then validates the fresh worker")
  void mismatchRecoversThenValidates() throws Exception {
    List<Long> recoveries = new CopyOnWriteArrayList<>();
    int[] calls = {0};
    KnowledgeServerBootstrap.PidProbe probe =
        attemptDeadlineMs -> (++calls[0] == 1) ? 999 : EXPECTED_PID;

    KnowledgeServerBootstrap.awaitWorkerPid(EXPECTED_PID, WINDOW_MS, probe, recoveries::add);

    assertEquals(1, recoveries.size(), "recovery runs exactly once, for the one mismatch");
    assertTrue(recoveries.get(0) > 0, "recovery is handed the remaining budget");
    assertEquals(2, calls[0]);
  }
}
