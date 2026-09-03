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
 * Tempdoc 916 Part 2 — the forwarding proof for the two chunk-collapse aggregation keys.
 *
 * <p>Same shape and same reason as {@code NrtCadenceConfigForwardingTest}: the 885 [R1] defect was
 * a key read INSIDE the Worker JVM whose only setter lived on the Head, so it could never fire.
 * Both keys here are read in the Worker — {@code SearchExecutor.mergeChunkResults} runs in the
 * Worker process — so an env var exported to the Head is worthless unless it survives the
 * Head → snapshot → Worker round trip. This walks that whole path rather than asserting the
 * accessors exist.
 *
 * <p>It is also the unit-test half of the wrong-gate check for the A/B this lever exists to run:
 * if the ON arm silently measured the OFF configuration, this is the test that would have caught it
 * before an hour of machine time was spent.
 */
final class ChunkCollapseAggregationConfigForwardingTest {

  private static final String SCAN_CAP_KEY = "index.hybrid.chunk_collapse_scan_cap_multiplier";
  private static final String LAMBDA_KEY = "index.hybrid.chunk_collapse_aggregation_lambda";

  @Test
  @DisplayName("both keys are declared in EnvRegistry with the documented names")
  void keysAreDeclared() {
    assertEquals(SCAN_CAP_KEY, EnvRegistry.HYBRID_CHUNK_COLLAPSE_SCAN_CAP_MULTIPLIER.configKey());
    assertEquals(
        "JUSTSEARCH_HYBRID_CHUNK_COLLAPSE_SCAN_CAP_MULTIPLIER",
        EnvRegistry.HYBRID_CHUNK_COLLAPSE_SCAN_CAP_MULTIPLIER.envVar());
    assertEquals(LAMBDA_KEY, EnvRegistry.HYBRID_CHUNK_COLLAPSE_AGGREGATION_LAMBDA.configKey());
    assertEquals(
        "JUSTSEARCH_HYBRID_CHUNK_COLLAPSE_AGGREGATION_LAMBDA",
        EnvRegistry.HYBRID_CHUNK_COLLAPSE_AGGREGATION_LAMBDA.envVar());
  }

  @Test
  @DisplayName("defaults resolve onto ResolvedConfig.HybridSearch as the control arm")
  void defaultsResolveOntoTheRecord() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.contributeEnvRegistry();
    ResolvedConfig.HybridSearch h = builder.build().hybridSearch();

    assertEquals(1, h.chunkCollapseScanCapMultiplier());
    assertEquals(0.0, h.chunkCollapseAggregationLambda(), 0.0);
  }

  @Test
  @DisplayName("a Head-side override survives the worker snapshot round-trip the Worker reads")
  void overrideReachesTheWorkerThroughTheSnapshot(@TempDir Path tmp) throws IOException {
    ResolvedConfigBuilder head = new ResolvedConfigBuilder();
    head.contributeEnvRegistry();
    head.put(SCAN_CAP_KEY, 400, "env_var", SCAN_CAP_KEY, "5");
    head.put(LAMBDA_KEY, 400, "env_var", LAMBDA_KEY, "0.3");
    ResolvedConfig headConfig = head.build();
    assertEquals(5, headConfig.hybridSearch().chunkCollapseScanCapMultiplier());
    assertEquals(0.3, headConfig.hybridSearch().chunkCollapseAggregationLambda(), 0.0001);

    Path snapshot = tmp.resolve("worker-config-snapshot.json");
    headConfig.toWorkerSnapshot(snapshot);
    String json = Files.readString(snapshot);
    assertTrue(json.contains(SCAN_CAP_KEY), "the key must actually be written to the snapshot");
    assertTrue(json.contains(LAMBDA_KEY));

    // Worker side: exactly what IndexerWorker does — snapshot at ordinal 450 over EnvRegistry.
    ResolvedConfigBuilder worker = new ResolvedConfigBuilder();
    worker.contributeWorkerSnapshot(snapshot);
    worker.contributeEnvRegistry();
    ResolvedConfig.HybridSearch workerHybrid = worker.build().hybridSearch();

    assertEquals(
        5,
        workerHybrid.chunkCollapseScanCapMultiplier(),
        "the Worker must see the Head's scan cap multiplier, not the default");
    assertEquals(
        0.3,
        workerHybrid.chunkCollapseAggregationLambda(),
        0.0001,
        "the Worker must see the Head's lambda, not the default");
  }

  @Test
  @DisplayName("with no override the Worker inherits the control arm, not a null or a zero cap")
  void unsetRoundTripKeepsTheControlArm(@TempDir Path tmp) {
    ResolvedConfigBuilder head = new ResolvedConfigBuilder();
    head.contributeEnvRegistry();
    Path snapshot = tmp.resolve("worker-config-snapshot.json");
    head.build().toWorkerSnapshot(snapshot);

    ResolvedConfigBuilder worker = new ResolvedConfigBuilder();
    worker.contributeWorkerSnapshot(snapshot);
    worker.contributeEnvRegistry();
    ResolvedConfig.HybridSearch h = worker.build().hybridSearch();

    assertEquals(1, h.chunkCollapseScanCapMultiplier());
    assertEquals(0.0, h.chunkCollapseAggregationLambda(), 0.0);
  }
}
