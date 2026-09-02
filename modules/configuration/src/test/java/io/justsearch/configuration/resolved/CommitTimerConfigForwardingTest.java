/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.EnvRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 885's tracked commit-cadence item — the forwarding proof for
 * {@code index.commit.timer_interval_ms}.
 *
 * <p>Same shape and same reason as {@code NrtCadenceConfigForwardingTest}: {@code CommitOps} runs in
 * the WORKER, so a key it reads must arrive through the ordinal-450 config snapshot. The [R1]
 * defect this lane documented was a key read inside the Worker JVM whose only setter lived on the
 * Head, which is unreachable by construction — so this walks the whole Head → snapshot → Worker
 * path rather than asserting a key exists.
 */
final class CommitTimerConfigForwardingTest {

  private static final String KEY = "index.commit.timer_interval_ms";

  @Test
  @DisplayName("the key is declared in EnvRegistry with the constant it replaces as its default")
  void keyIsDeclaredWithTheDocumentedDefault() {
    assertEquals(KEY, EnvRegistry.INDEX_COMMIT_TIMER_INTERVAL_MS.sysProp());
    assertEquals(
        "JUSTSEARCH_INDEX_COMMIT_TIMER_INTERVAL_MS",
        EnvRegistry.INDEX_COMMIT_TIMER_INTERVAL_MS.envVar());
    assertEquals("10000", EnvRegistry.INDEX_COMMIT_TIMER_INTERVAL_MS.defaultValue());
  }

  @Test
  @DisplayName("the default resolves onto ResolvedConfig.Index as the no-change arm")
  void defaultResolvesOntoTheRecord() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();

    assertEquals(
        10_000,
        builder.build().index().commitTimerIntervalMs(),
        "the default must reproduce CommitOps' previous hardcoded 10 s exactly");
  }

  @Test
  @DisplayName("a Head-side override survives the worker snapshot round-trip the Worker reads")
  void overrideReachesTheWorkerThroughTheSnapshot(@TempDir Path tmp) throws IOException {
    ResolvedConfigBuilder head = new ResolvedConfigBuilder();
    head.contributeEnvRegistry();
    head.put(KEY, 500, "jvm_arg", KEY, "30000");
    ResolvedConfig headConfig = head.build();
    assertEquals(30_000, headConfig.index().commitTimerIntervalMs());

    Path snapshot = tmp.resolve("worker-config-snapshot.json");
    headConfig.toWorkerSnapshot(snapshot);
    assertTrue(
        Files.readString(snapshot).contains(KEY),
        "the key must actually be written to the snapshot");

    // Worker side: exactly what IndexerWorker does — snapshot at ordinal 450 over EnvRegistry.
    ResolvedConfigBuilder worker = new ResolvedConfigBuilder();
    worker.contributeWorkerSnapshot(snapshot);
    worker.contributeEnvRegistry();

    assertEquals(
        30_000,
        worker.build().index().commitTimerIntervalMs(),
        "the Worker must see the Head's period, not the default");
  }
}
