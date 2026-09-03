/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.EnvRegistry;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 916 Part 1 — the four TEMPORARY chunk-size campaign keys.
 *
 * <p>This whole file is deleted by the PR that lands the chosen constants, together with the keys.
 * Its job is the two properties the campaign depends on and nothing else: unset is
 * indistinguishable from today, and a set value survives the Head→Worker crossing.
 */
final class ChunkSizeSweepKeysTest {

  @Test
  @DisplayName("unset: all four resolve to null and effectiveChunk*() returns the shipped constants")
  void unsetReproducesShippedConstants() {
    ResolvedConfig.Index index = new ResolvedConfigBuilder().build().index();

    assertNull(index.chunkTargetTokens());
    assertNull(index.chunkOverlapTokens());
    assertNull(index.chunkMinTokens());
    assertNull(index.chunkThresholdChars());

    assertEquals(500, index.effectiveChunkTargetTokens());
    assertEquals(50, index.effectiveChunkOverlapTokens());
    assertEquals(100, index.effectiveChunkMinTokens());
    assertEquals(2000, index.effectiveChunkThresholdChars());
  }

  @Test
  @DisplayName("an arm's values resolve onto ResolvedConfig.Index")
  void armValuesResolve() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.put(
        "justsearch.chunking.sweep.target_tokens",
        ResolvedConfigBuilder.ORDINAL_JVM_ARG,
        "jvm_arg",
        "test",
        "128");
    builder.put(
        "justsearch.chunking.sweep.overlap_tokens",
        ResolvedConfigBuilder.ORDINAL_JVM_ARG,
        "jvm_arg",
        "test",
        "25");
    builder.put(
        "justsearch.chunking.sweep.min_tokens",
        ResolvedConfigBuilder.ORDINAL_JVM_ARG,
        "jvm_arg",
        "test",
        "26");
    builder.put(
        "justsearch.chunking.sweep.threshold_chars",
        ResolvedConfigBuilder.ORDINAL_JVM_ARG,
        "jvm_arg",
        "test",
        "512");

    ResolvedConfig.Index index = builder.build().index();
    assertEquals(128, index.effectiveChunkTargetTokens());
    assertEquals(25, index.effectiveChunkOverlapTokens());
    assertEquals(26, index.effectiveChunkMinTokens());
    assertEquals(512, index.effectiveChunkThresholdChars());
  }

  /**
   * The 885 [R1] check, stated as a test rather than as a comment: chunking runs in the WORKER, so
   * an arm's values are worth nothing unless they cross the process boundary. The ordinal-450
   * snapshot is that crossing; this asserts the values survive it, and that an unset key stays
   * absent from the snapshot rather than being frozen in as an explicit default.
   */
  @Test
  @DisplayName("the arm's values reach the Worker through the ordinal-450 snapshot")
  void armValuesCrossTheWorkerBoundary(@TempDir Path tempDir) {
    ResolvedConfigBuilder head = new ResolvedConfigBuilder();
    head.put(
        "justsearch.chunking.sweep.target_tokens",
        ResolvedConfigBuilder.ORDINAL_ENV_VAR,
        "env_var",
        EnvRegistry.CHUNKING_SWEEP_TARGET_TOKENS.envVar(),
        "384");
    head.put(
        "justsearch.chunking.sweep.overlap_tokens",
        ResolvedConfigBuilder.ORDINAL_ENV_VAR,
        "env_var",
        EnvRegistry.CHUNKING_SWEEP_OVERLAP_TOKENS.envVar(),
        "0");

    Path snapshotFile = tempDir.resolve("worker-config-snapshot.json");
    head.build().toWorkerSnapshot(snapshotFile);

    Map<String, String> raw = ResolvedConfig.loadWorkerSnapshot(snapshotFile);
    assertEquals("384", raw.get("justsearch.chunking.sweep.target_tokens"));
    assertEquals("0", raw.get("justsearch.chunking.sweep.overlap_tokens"));
    assertTrue(
        !raw.containsKey("justsearch.chunking.sweep.min_tokens"),
        "an unset sweep key must not be materialized into the snapshot");

    ResolvedConfigBuilder worker = new ResolvedConfigBuilder();
    worker.contributeWorkerSnapshot(snapshotFile);
    ResolvedConfig.Index workerIndex = worker.build().index();

    assertEquals(384, workerIndex.effectiveChunkTargetTokens());
    assertEquals(0, workerIndex.effectiveChunkOverlapTokens());
    assertEquals(100, workerIndex.effectiveChunkMinTokens(), "unset must still be the constant");
    assertEquals(2000, workerIndex.effectiveChunkThresholdChars());
  }

  @Test
  @DisplayName("a non-numeric value is ignored, not fatal, and falls back to the constant")
  void garbageFallsBack() {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    builder.put(
        "justsearch.chunking.sweep.target_tokens",
        ResolvedConfigBuilder.ORDINAL_ENV_VAR,
        "env_var",
        "JUSTSEARCH_CHUNKING_SWEEP_TARGET_TOKENS",
        "not-a-number");
    assertEquals(500, builder.build().index().effectiveChunkTargetTokens());
  }

  @Test
  @DisplayName("all four keys are declared in EnvRegistry with no default value")
  void declaredWithoutDefaults() {
    for (EnvRegistry entry :
        new EnvRegistry[] {
          EnvRegistry.CHUNKING_SWEEP_TARGET_TOKENS,
          EnvRegistry.CHUNKING_SWEEP_OVERLAP_TOKENS,
          EnvRegistry.CHUNKING_SWEEP_MIN_TOKENS,
          EnvRegistry.CHUNKING_SWEEP_THRESHOLD_CHARS
        }) {
      assertNotNull(entry.configKey());
      assertTrue(
          entry.configKey().startsWith("justsearch.chunking.sweep."),
          "campaign keys share a namespace so the deletion sweep is one grep: " + entry.configKey());
      assertNull(
          entry.defaultValue(),
          "a default here would make 'unset' distinguishable from today: " + entry.configKey());
    }
  }
}
