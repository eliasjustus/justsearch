/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.OnlineAiService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The install run's blind tail: {@code cancel()} only raises a flag and never interrupts the install
 * thread, so the post-install smoke test's wait for the engine's answer has to poll that flag. A
 * single blocking 60 s {@code get} made both the user's Cancel button and the op-lease drain callback
 * (registered so a pending app update can stop an install) a no-op for a whole minute — and the run
 * then reported the install COMPLETED.
 *
 * <p>Drives the private smoke test directly: staging a real 10 GB plan to reach the tail would test
 * the download loop, not this. Reflection over a production test-seam for the same reason {@link
 * AiInstallServiceReaperTest} uses it.
 */
final class AiInstallServiceSmokeTestCancelTest {

  @TempDir Path tmp;

  /** Never answers — the whole point here is what the install thread does while it waits. */
  private static final class NeverAnsweringAi implements OnlineAiService {
    private final CompletableFuture<String> answer = new CompletableFuture<>();

    @Override
    public CompletableFuture<String> summarize(String content) {
      return answer;
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return answer;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public void switchToOnlineMode() {
      // The engine is "up" for this test; the interface default throws.
    }
  }

  private static AiInstallStatus statusOf(AiInstallService svc) throws Exception {
    Field f = AiInstallService.class.getDeclaredField("status");
    f.setAccessible(true);
    return (AiInstallStatus) f.get(svc);
  }

  private static boolean runSmokeTest(AiInstallService svc) throws Exception {
    Method m = AiInstallService.class.getDeclaredMethod("smokeTestBestEffort");
    m.setAccessible(true);
    return (Boolean) m.invoke(svc);
  }

  @Test
  @Timeout(20)
  @DisplayName("a cancelled install stops waiting for the smoke-test answer instead of completing")
  void cancellationEndsTheSmokeTestWaitAndReportsCancelled() throws Exception {
    NeverAnsweringAi ai = new NeverAnsweringAi();
    AiInstallService svc = new AiInstallService(ai, null, null, null, tmp);
    svc.cancel();

    boolean ok = runSmokeTest(svc);

    assertFalse(ok, "a cancelled smoke test must not report success");
    assertEquals(
        "cancelled",
        statusOf(svc).state,
        "the run must say cancelled — before this it slept through the flag and then said completed"
            + " (or failed the smoke test), which is the one thing a cancelling user cannot be told");
    assertTrue(
        ai.answer.isCancelled(),
        "the engine must not be left answering a question nobody wants any more");
  }
}
