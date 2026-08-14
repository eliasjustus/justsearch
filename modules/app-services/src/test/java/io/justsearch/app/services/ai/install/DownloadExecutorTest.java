package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DownloadExecutorTest {

  @Test
  void bitsCountExpressionHandlesUInt64UnknownSentinelBeforeSignedCast() {
    String expression = DownloadExecutor.bitsCountExpression("$job.BytesTotal");

    assertTrue(expression.contains("[UInt64]$v"));
    assertTrue(expression.contains("[UInt64]::MaxValue"));
    assertTrue(expression.contains("[Int64]::MaxValue"));
    assertFalse(
        expression.contains("[Int64]$job.BytesTotal"),
        "the BITS field must not be cast directly to Int64 because unknown totals are UInt64 max");
  }

  // -- curl invocation pinning (round 16 F1 §3.2) --------------------------------

  /**
   * The regression home for the measured round-16 finding: with plain {@code --retry 3}, curl made
   * exactly ONE connection on exit 52 (empty reply) and returned in 3 ms. {@code --retry-all-errors}
   * is what makes its retry budget cover that class at all.
   */
  @Test
  void curlCommandCarriesTheHardenedRetryAndTimeoutFlags() {
    List<String> cmd = DownloadExecutor.curlCommand("https://example/m.onnx", Path.of("m.partial"),
        false);
    String flat = String.join(" ", cmd);

    assertTrue(flat.contains("--retry-all-errors"), flat);
    assertTrue(flat.contains("--retry-connrefused"), flat);
    assertTrue(flat.contains("--connect-timeout 20"), flat);
    assertTrue(flat.contains("--speed-limit 1024"), flat);
    assertTrue(flat.contains("--speed-time 60"), flat);
    assertTrue(flat.contains("--user-agent JustSearch/"), flat);
    assertTrue(flat.contains("--fail"), flat);
    assertTrue(flat.contains("--location"), flat);
    assertTrue(flat.contains("--continue-at -"), "resume must survive the hardening");
    assertEquals("https://example/m.onnx", cmd.get(cmd.size() - 1), "the URL stays last");
  }

  @Test
  void onlyTheHttp11TierForcesHttp11() {
    assertFalse(
        DownloadExecutor.curlCommand("https://example/m", Path.of("m.partial"), false)
            .contains("--http1.1"));
    assertTrue(
        DownloadExecutor.curlCommand("https://example/m", Path.of("m.partial"), true)
            .contains("--http1.1"));
  }

  /** Round 16's fallback fired 0.8 s after the failure it answered; an escalation must not re-pay it. */
  @Test
  void onlyTheFirstTierSpendsTheBitsBudget() {
    assertTrue(DownloadExecutor.usesBits(0));
    assertFalse(DownloadExecutor.usesBits(1));
    assertFalse(DownloadExecutor.usesBits(DownloadExecutor.TIER_CURL_HTTP1_1));
    assertFalse(DownloadExecutor.usesBits(TransportRetryPolicy.MAX_TRANSPORT_TIER));
  }

  @Test
  void userAgentNamesTheProduct() {
    assertTrue(DownloadExecutor.userAgent().startsWith("JustSearch/"));
  }

  // -- curl output tail (round 16 F1 §3.2 / D5) ----------------------------------

  @Test
  void tailBufferKeepsTheLastBytesNotTheFirst() {
    DownloadExecutor.TailBuffer tail = new DownloadExecutor.TailBuffer(16);
    tail.append("noise that scrolls past and then: curl: (52) Empty reply");

    assertEquals("(52) Empty reply", tail.text(), "the last 16 bytes, i.e. the diagnosis");
  }

  @Test
  void tailBufferIsEmptyBeforeAnythingIsWritten() {
    assertEquals("", new DownloadExecutor.TailBuffer(32).text());
  }

  @Test
  void tailBufferShorterThanCapacityKeepsEverything() {
    DownloadExecutor.TailBuffer tail = new DownloadExecutor.TailBuffer(2048);
    tail.append("curl: (35) OpenSSL SSL_connect: Connection was reset");

    assertEquals("curl: (35) OpenSSL SSL_connect: Connection was reset", tail.text());
  }

  // -- BITS transient tolerance (round 16 F1 §3.2 / D3) --------------------------

  /** Scripted {@link DownloadExecutor.BitsControl} on a virtual clock — no PowerShell, no waiting. */
  private static final class FakeBits implements DownloadExecutor.BitsControl {
    private final Deque<DownloadExecutor.BitsSnapshot> snapshots = new ArrayDeque<>();
    private final List<String> calls = new ArrayList<>();
    private DownloadExecutor.BitsSnapshot last;
    private long nowMs;

    FakeBits(DownloadExecutor.BitsSnapshot... scripted) {
      for (DownloadExecutor.BitsSnapshot s : scripted) snapshots.add(s);
    }

    /** Once the script runs out, the job stays in its last reported state (a wedged job). */
    @Override
    public DownloadExecutor.BitsSnapshot snapshot(String jobId) {
      if (!snapshots.isEmpty()) last = snapshots.poll();
      return last;
    }

    @Override
    public void complete(String jobId) {
      calls.add("complete");
    }

    @Override
    public void remove(String jobId) {
      calls.add("remove");
    }

    @Override
    public void suspend() {
      calls.add("suspend");
    }

    @Override
    public void sleep(long millis) {
      nowMs += millis;
    }

    @Override
    public long nowMs() {
      return nowMs;
    }
  }

  private static DownloadExecutor.BitsSnapshot snap(String state, long transferred, int errors) {
    return new DownloadExecutor.BitsSnapshot(state, 1000L, transferred, errors, "conn reset");
  }

  /**
   * Round 16's D3: BITS retries a transient failure on its own schedule, and the product threw on
   * the first poll that saw one — converting a recoverable blip into a package failure.
   */
  @Test
  void transientErrorIsToleratedAndTheJobStillCompletes() throws Exception {
    FakeBits bits =
        new FakeBits(
            snap("Connecting", 0, 0),
            snap("TransientError", 0, 1),
            snap("Transferring", 400, 1),
            snap("Transferred", 1000, 1));

    boolean ok = DownloadExecutor.pollBitsJob(bits, "JOB-1", (b, t) -> {}, () -> false);

    assertTrue(ok, "a transient error that BITS recovers from must not fail the download");
    assertEquals(List.of("complete"), bits.calls);
  }

  @Test
  void hardErrorIsStillImmediatelyFatal() {
    FakeBits bits = new FakeBits(snap("Error", 0, 3));

    DownloadExecutor.BitsTransferException e =
        assertThrows(
            DownloadExecutor.BitsTransferException.class,
            () -> DownloadExecutor.pollBitsJob(bits, "JOB-2", null, () -> false));

    assertEquals("Error", e.jobState());
    assertEquals(List.of("remove"), bits.calls);
  }

  /**
   * The risk the tolerance introduces, pinned: a job stuck in TransientError with no progress must
   * hit a hard wall-clock cap rather than keeping the install alive forever.
   */
  @Test
  void transientErrorWithoutProgressGivesUpAtTheHardDeadline() {
    FakeBits bits = new FakeBits(snap("TransientError", 0, 1));

    DownloadExecutor.BitsTransferException e =
        assertThrows(
            DownloadExecutor.BitsTransferException.class,
            () -> DownloadExecutor.pollBitsJob(bits, "JOB-3", null, () -> false));

    assertEquals("Stalled", e.jobState());
    assertTrue(
        bits.nowMs > DownloadExecutor.BITS_NO_PROGRESS_DEADLINE_MS,
        "it must actually have waited out the deadline, not given up early");
    assertEquals(List.of("remove"), bits.calls);
  }

  @Test
  void tooManyBitsErrorsEndTheWaitEvenWhileBytesMove() {
    FakeBits bits =
        new FakeBits(snap("TransientError", 10, DownloadExecutor.MAX_BITS_ERROR_COUNT + 1));

    DownloadExecutor.BitsTransferException e =
        assertThrows(
            DownloadExecutor.BitsTransferException.class,
            () -> DownloadExecutor.pollBitsJob(bits, "JOB-4", null, () -> false));

    assertEquals("TransientError", e.jobState());
    assertTrue(e.getMessage().contains("errors while retrying"), e.getMessage());
  }

  @Test
  void cancellationSuspendsRatherThanDestroyingTheJob() throws Exception {
    FakeBits bits = new FakeBits(snap("Transferring", 100, 0));

    assertFalse(DownloadExecutor.pollBitsJob(bits, "JOB-5", null, () -> true));
    assertEquals(List.of("suspend"), bits.calls);
  }

  /** Tolerating a TransientError is only meaningful if BITS was actually told to keep retrying. */
  @Test
  void bitsJobIsStartedWithAnExplicitRetrySchedule() {
    String script = DownloadExecutor.startBitsScript("https://example/m", Path.of("m.partial"));

    assertTrue(script.contains("-RetryInterval 60"), script);
    assertTrue(script.contains("-RetryTimeout 300"), script);
    assertTrue(script.contains("-Asynchronous"), script);
  }
}
