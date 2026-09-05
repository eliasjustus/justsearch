/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.execute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.ipc.PipelineConfig;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 931 §E item 8 — {@code rag.chunk_splade.enabled} gates the chunk-SPLADE RETRIEVAL leg,
 * not only the write-side backfill lanes.
 *
 * <p>{@link SearchExecutor#chunkSpladeLegEnabled} is the single decision point: {@code
 * SearchPlanner#planChunkMerge} hands the whole-doc SPLADE weights straight through as {@code
 * ChunkMergeInputs.chunkSpladeWeights} without a second chunk-specific decision, and {@code
 * executeChunkBranchFusion} calls {@code ChunkSearchOps#searchChunksSplade} iff this predicate said
 * yes. Asserting on the predicate therefore asserts on whether the leg runs.
 */
@DisplayName("SearchExecutor chunk-SPLADE leg flag gate (931 §E item 8)")
final class SearchExecutorChunkSpladeFlagTest {

  private static final Map<String, Float> WEIGHTS = Map.of("term", 1.0f);

  private static PipelineConfig spladePipeline(boolean spladeEnabled) {
    return PipelineConfig.newBuilder().setSpladeEnabled(spladeEnabled).build();
  }

  private static ResolvedConfig configWithChunkSplade(boolean enabled) {
    return new ResolvedConfigBuilder()
        .putDefault("rag.chunk_splade.enabled", Boolean.toString(enabled))
        .build();
  }

  @Test
  @DisplayName("flag OFF: the leg is skipped even with splade enabled and non-empty weights")
  void flagOffSkipsLeg() {
    assertFalse(
        SearchExecutor.chunkSpladeLegEnabled(
            spladePipeline(true), WEIGHTS, configWithChunkSplade(false)),
        "rag.chunk_splade.enabled=false must skip the chunk-SPLADE leg");
  }

  @Test
  @DisplayName("flag ON: the leg runs with splade enabled and non-empty weights")
  void flagOnRunsLeg() {
    assertTrue(
        SearchExecutor.chunkSpladeLegEnabled(
            spladePipeline(true), WEIGHTS, configWithChunkSplade(true)),
        "rag.chunk_splade.enabled=true must let the chunk-SPLADE leg run");
  }

  @Test
  @DisplayName("the flag does not resurrect a leg the pipeline or the weights already ruled out")
  void flagOnDoesNotOverrideTheOtherPreconditions() {
    ResolvedConfig on = configWithChunkSplade(true);
    assertFalse(
        SearchExecutor.chunkSpladeLegEnabled(spladePipeline(false), WEIGHTS, on),
        "pipeline.spladeEnabled=false still wins");
    assertFalse(
        SearchExecutor.chunkSpladeLegEnabled(spladePipeline(true), null, on),
        "null weights still win");
    assertFalse(
        SearchExecutor.chunkSpladeLegEnabled(spladePipeline(true), Map.of(), on),
        "empty weights still win");
  }

  @Test
  @DisplayName("an absent resolved config reads as OFF, not as unconstrained")
  void nullConfigReadsAsOff() {
    assertFalse(
        SearchExecutor.chunkSpladeLegEnabled(spladePipeline(true), WEIGHTS, null),
        "a null ResolvedConfig must fail closed to the flag's own default (false)");
  }
}
