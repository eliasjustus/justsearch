/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class HeadShutdownCoordinatorTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void upgradeShutdownIsIdempotentAndWritesNonceBoundReceipt(@TempDir Path dataDir)
      throws Exception {
    var shutdownCalls = new AtomicInteger();
    var exitCalls = new AtomicInteger();
    var exitCode = new AtomicInteger(-1);
    var coordinator =
        new HeadShutdownCoordinator(
            dataDir,
            () -> {
              shutdownCalls.incrementAndGet();
              return new HeadShutdownCoordinator.ShutdownResult(true, "GRACEFUL", List.of());
            },
            code -> {
              exitCode.set(code);
              exitCalls.incrementAndGet();
            });

    coordinator.shutdown("prep-1", "nonce-1");
    coordinator.shutdown("prep-2", "nonce-2");

    assertEquals(1, shutdownCalls.get());
    assertEquals(1, exitCalls.get());
    assertEquals(0, exitCode.get());
    Path receipt = dataDir.resolve("upgrade").resolve(HeadShutdownCoordinator.RECEIPT_FILE);
    assertTrue(Files.isRegularFile(receipt));
    var body = JSON.readTree(Files.readString(receipt));
    assertEquals("prep-1", body.get("preparationId").asText());
    assertEquals("nonce-1", body.get("shutdownNonce").asText());
    assertTrue(body.get("headPid").asLong() > 0);
    assertTrue(body.get("clean").asBoolean());
    assertEquals("GRACEFUL", body.get("workerOutcome").asText());
    assertEquals(0, body.get("errors").size());
    assertTrue(!body.get("completedAt").asText().isBlank());
  }

  @Test
  void failedOrderedShutdownProducesFailureReceiptAndExitCode(@TempDir Path dataDir)
      throws Exception {
    var exitCode = new AtomicInteger(-1);
    var coordinator =
        new HeadShutdownCoordinator(
            dataDir,
            () ->
                new HeadShutdownCoordinator.ShutdownResult(
                    false, "FORCED", List.of("worker-forced")),
            exitCode::set);

    coordinator.shutdown("prep-1", "nonce-1");

    assertEquals(1, exitCode.get());
    var body =
        JSON.readTree(
            Files.readString(
                dataDir.resolve("upgrade").resolve(HeadShutdownCoordinator.RECEIPT_FILE)));
    assertEquals("FORCED", body.get("workerOutcome").asText());
    assertEquals("worker-forced", body.get("errors").get(0).asText());
  }
}
