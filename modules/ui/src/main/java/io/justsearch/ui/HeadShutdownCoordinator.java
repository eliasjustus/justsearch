/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.ui.api.UpgradeShutdownAction;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent owner of the Head's ordered close. Upgrade shutdown writes a nonce-bound receipt after
 * the same close sequence used by the JVM hook, then exits.
 */
final class HeadShutdownCoordinator implements UpgradeShutdownAction {
  static final String RECEIPT_FILE = "head-shutdown-receipt.v1.json";
  private static final ObjectMapper JSON = new ObjectMapper();

  record ShutdownResult(boolean clean, String workerOutcome, List<String> errors) {
    ShutdownResult {
      workerOutcome = workerOutcome == null ? "UNKNOWN" : workerOutcome;
      errors = errors == null ? List.of() : List.copyOf(errors);
    }
  }

  private final Path receiptPath;
  private final Supplier<ShutdownResult> orderedShutdown;
  private final IntConsumer exit;
  private final AtomicReference<ShutdownResult> result = new AtomicReference<>();
  private final AtomicBoolean exitRequested = new AtomicBoolean();

  HeadShutdownCoordinator(
      Path dataDir, Supplier<ShutdownResult> orderedShutdown, IntConsumer exit) {
    this.receiptPath = dataDir.resolve("upgrade").resolve(RECEIPT_FILE);
    this.orderedShutdown = orderedShutdown;
    this.exit = exit;
  }

  ShutdownResult shutdownNormally() {
    ShutdownResult existing = result.get();
    if (existing != null) return existing;
    synchronized (result) {
      existing = result.get();
      if (existing == null) {
        existing = orderedShutdown.get();
        result.set(existing);
      }
      return existing;
    }
  }

  @Override
  public void shutdown(String preparationId, String shutdownNonce) {
    if (!exitRequested.compareAndSet(false, true)) {
      return;
    }
    ShutdownResult shutdown = shutdownNormally();
    try {
      AtomicFileWrites.replace(
          receiptPath,
          JSON.writeValueAsBytes(
              java.util.Map.of(
                  "schemaVersion", 1,
                  "preparationId", preparationId,
                  "shutdownNonce", shutdownNonce,
                  "headPid", ProcessHandle.current().pid(),
                  "clean", shutdown.clean(),
                  "workerOutcome", shutdown.workerOutcome(),
                  "errors", shutdown.errors(),
                  "completedAt", Instant.now().toString())));
    } catch (Exception e) {
      // Missing receipt is a fail-closed signal to the shell.
    }
    exit.accept(shutdown.clean() ? 0 : 1);
  }
}
