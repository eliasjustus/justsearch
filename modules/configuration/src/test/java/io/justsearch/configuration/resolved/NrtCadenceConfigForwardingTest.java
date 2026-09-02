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
 * Tempdoc 885 item 19 — the forwarding proof for the four cadence keys.
 *
 * <p>Same shape, same reason as {@code ForegroundPacingConfigForwardingTest}: the [R1] defect this
 * lane already documented was a key read INSIDE the Worker JVM whose only setter lived on the Head,
 * so it could never fire. All four cadence knobs are read in the Worker — three by {@code
 * ComponentsFactory} when it opens the index, one by {@code IndexingLoop} — so this test walks the
 * whole Head → snapshot → Worker path rather than asserting a key exists.
 */
final class NrtCadenceConfigForwardingTest {

  private static final String MODE_KEY = "index.nrt.mode";
  private static final String BACKGROUND_KEY = "index.nrt.background_reopen_ms";
  private static final String ON_DEMAND_KEY = "index.nrt.on_demand_max_stale_ms";
  private static final String COMMIT_IDLE_KEY = "index.commit.idle_ms";

  @Test
  @DisplayName("the four keys are declared in EnvRegistry with the documented defaults")
  void keysAreDeclaredWithDefaults() {
    assertEquals(MODE_KEY, EnvRegistry.INDEX_NRT_MODE.sysProp());
    assertEquals("JUSTSEARCH_INDEX_NRT_MODE", EnvRegistry.INDEX_NRT_MODE.envVar());
    assertEquals("continuous", EnvRegistry.INDEX_NRT_MODE.defaultValue());

    assertEquals(BACKGROUND_KEY, EnvRegistry.INDEX_NRT_BACKGROUND_REOPEN_MS.sysProp());
    assertEquals("2000", EnvRegistry.INDEX_NRT_BACKGROUND_REOPEN_MS.defaultValue());

    assertEquals(ON_DEMAND_KEY, EnvRegistry.INDEX_NRT_ON_DEMAND_MAX_STALE_MS.sysProp());
    assertEquals("1000", EnvRegistry.INDEX_NRT_ON_DEMAND_MAX_STALE_MS.defaultValue());

    assertEquals(COMMIT_IDLE_KEY, EnvRegistry.INDEX_COMMIT_IDLE_MS.sysProp());
    assertEquals("0", EnvRegistry.INDEX_COMMIT_IDLE_MS.defaultValue());
  }

  @Test
  @DisplayName("defaults resolve onto ResolvedConfig.Index as the no-change arm")
  void defaultsResolveOntoTheRecord() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();
    ResolvedConfig.Index index = builder.build().index();

    assertEquals(ResolvedConfig.Index.NRT_MODE_CONTINUOUS, index.nrtMode());
    assertEquals(2000, index.nrtBackgroundReopenMs());
    assertEquals(1000, index.nrtOnDemandMaxStaleMs());
    assertEquals(0, index.commitIdleMs(), "0 keeps the historical commit-on-first-empty-poll");
  }

  @Test
  @DisplayName("a Head-side override survives the worker snapshot round-trip the Worker reads")
  void overrideReachesTheWorkerThroughTheSnapshot(@TempDir Path tmp) throws IOException {
    ResolvedConfigBuilder head = new ResolvedConfigBuilder();
    head.contributeEnvRegistry();
    head.put(MODE_KEY, 500, "jvm_arg", MODE_KEY, ResolvedConfig.Index.NRT_MODE_ON_DEMAND);
    head.put(BACKGROUND_KEY, 500, "jvm_arg", BACKGROUND_KEY, "3500");
    head.put(ON_DEMAND_KEY, 500, "jvm_arg", ON_DEMAND_KEY, "250");
    head.put(COMMIT_IDLE_KEY, 500, "jvm_arg", COMMIT_IDLE_KEY, "5000");
    ResolvedConfig headConfig = head.build();
    assertEquals(ResolvedConfig.Index.NRT_MODE_ON_DEMAND, headConfig.index().nrtMode());

    Path snapshot = tmp.resolve("worker-config-snapshot.json");
    headConfig.toWorkerSnapshot(snapshot);
    String json = Files.readString(snapshot);
    assertTrue(json.contains(MODE_KEY), "the key must actually be written to the snapshot");
    assertTrue(json.contains(BACKGROUND_KEY));
    assertTrue(json.contains(ON_DEMAND_KEY));
    assertTrue(json.contains(COMMIT_IDLE_KEY));

    // Worker side: exactly what IndexerWorker does — snapshot at ordinal 450 over EnvRegistry.
    ResolvedConfigBuilder worker = new ResolvedConfigBuilder();
    worker.contributeWorkerSnapshot(snapshot);
    worker.contributeEnvRegistry();
    ResolvedConfig.Index workerIndex = worker.build().index();

    assertEquals(
        ResolvedConfig.Index.NRT_MODE_ON_DEMAND,
        workerIndex.nrtMode(),
        "the Worker must see the Head's mode, not the default");
    assertEquals(3500, workerIndex.nrtBackgroundReopenMs());
    assertEquals(250, workerIndex.nrtOnDemandMaxStaleMs());
    assertEquals(5000, workerIndex.commitIdleMs());
  }
}
