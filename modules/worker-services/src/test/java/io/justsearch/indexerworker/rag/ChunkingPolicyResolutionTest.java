/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.indexing.chunking.ChunkingPolicy;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 916 Part 1 — the seam between resolved configuration and the chunk writer.
 *
 * <p>Deleted with the keys by the PR that lands the chosen constants, except
 * {@link #mirroredDefaultsDoNotDrift()}, which is the reason the mirrors are allowed to exist.
 */
final class ChunkingPolicyResolutionTest {

  private ConfigStore previous;
  private boolean published;

  /**
   * Restores the global to EXACTLY what it was, including "there was none".
   *
   * <p>The first version of this fixture stored the newly-published store as {@code previous} when
   * there had been no prior global, so it re-installed its own store instead of clearing — leaking
   * an all-defaults {@code ConfigStore} into every later test class in the same fork. That is not
   * hypothetical: it turned {@code EnrichmentCompletenessProjectionTest} red, because the Lucene
   * runtime resolves its validation mode through {@code ConfigStore.globalOrNull()} and a leaked
   * store flipped a deliberately-invalid fixture write into a hard failure.
   */
  @AfterEach
  void restore() {
    if (published) {
      TestResolvedConfigHelper.restoreGlobal(previous);
      previous = null;
      published = false;
    }
  }

  private void publish(Map<String, String> entries) {
    if (!published) {
      previous = ConfigStore.globalOrNull();
      published = true;
    }
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(entries)));
  }

  /**
   * {@code modules/configuration} cannot see {@code modules/indexing}, so
   * {@code ResolvedConfig.Index} mirrors the four chunking constants. This module sees both. If
   * the two ever disagree, an un-swept build would resolve a different policy than the splitter's
   * own defaults and every "unset ≡ today" claim in this lane would be false.
   */
  @Test
  @DisplayName("the configuration-module mirrors of the chunking constants do not drift")
  void mirroredDefaultsDoNotDrift() {
    assertEquals(
        ChunkSplitter.DEFAULT_CHUNK_TOKENS, ResolvedConfig.Index.DEFAULT_CHUNK_TARGET_TOKENS);
    assertEquals(
        ChunkSplitter.DEFAULT_OVERLAP_TOKENS, ResolvedConfig.Index.DEFAULT_CHUNK_OVERLAP_TOKENS);
    assertEquals(ChunkSplitter.MIN_CHUNK_TOKENS, ResolvedConfig.Index.DEFAULT_CHUNK_MIN_TOKENS);
    assertEquals(
        ChunkingPolicy.DEFAULT_THRESHOLD_CHARS,
        ResolvedConfig.Index.DEFAULT_CHUNK_THRESHOLD_CHARS);
    assertEquals(ChunkingPolicy.DEFAULT_THRESHOLD_CHARS, ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS);
  }

  /**
   * The genuinely-null branch, not an all-defaults store: chunking runs during Worker startup and
   * in unit tests where no ConfigStore has been published at all, and the fail-safe there must be
   * the shipped policy rather than zeros.
   */
  @Test
  @DisplayName("with NO ConfigStore published at all, activePolicy() is the shipped policy")
  void noConfigStoreIsTheShippedPolicy() {
    ConfigStore saved = ConfigStore.globalOrNull();
    try {
      TestResolvedConfigHelper.restoreGlobal(null);
      assertNull(ConfigStore.globalOrNull(), "precondition: the global must actually be cleared");
      assertTrue(ChunkDocumentWriter.activePolicy().isDefault());
    } finally {
      TestResolvedConfigHelper.restoreGlobal(saved);
    }
  }

  @Test
  @DisplayName("an all-defaults ConfigStore also resolves to the shipped policy")
  void defaultConfigStoreIsTheShippedPolicy() {
    publish(Map.of());
    assertTrue(ChunkDocumentWriter.activePolicy().isDefault());
  }

  @Test
  @DisplayName("an arm's four values reach ChunkDocumentWriter.activePolicy()")
  void armReachesTheWriter() {
    publish(
        Map.of(
            "justsearch.chunking.sweep.target_tokens", "256",
            "justsearch.chunking.sweep.overlap_tokens", "25",
            "justsearch.chunking.sweep.min_tokens", "52",
            "justsearch.chunking.sweep.threshold_chars", "1024"));

    ChunkingPolicy policy = ChunkDocumentWriter.activePolicy();
    assertEquals(new ChunkingPolicy(256, 25, 52, 1024), policy);
    assertTrue(!policy.isDefault());
  }

  /**
   * The wrong-gate leg tempdoc 916 §E.2 says the Part 2 campaign was missing: trace the resolved
   * value forward to what the NEXT stage reads. The next stage after the writer is the chunk
   * documents themselves, so this asserts the arm's policy actually changes the produced chunk
   * boundaries rather than merely being readable from an accessor.
   */
  @Test
  @DisplayName("the arm's policy changes the chunk boundaries the writer would emit")
  void armChangesTheEmittedBoundaries() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append("Sentence ").append(i).append(" of a document long enough to be chunked. ");
    }
    String text = sb.toString();

    publish(Map.of());
    var shipped =
        ChunkSplitter.splitWithMetadata(
            text, ChunkDocumentWriter.activePolicy(), ChunkSplitter.Mode.DEFAULT);

    publish(
        Map.of(
            "justsearch.chunking.sweep.target_tokens", "128",
            "justsearch.chunking.sweep.min_tokens", "26"));
    var arm =
        ChunkSplitter.splitWithMetadata(
            text, ChunkDocumentWriter.activePolicy(), ChunkSplitter.Mode.DEFAULT);

    assertTrue(
        arm.size() > shipped.size(),
        "the 128-token arm must emit more chunks than the shipped 500 (" + arm.size() + " vs "
            + shipped.size() + ")");
    assertEquals(0, arm.get(0).index());
    assertTrue(
        arm.get(1).startChar() != shipped.get(1).startChar(),
        "chunk_index 1 must start at the arm's boundary, not the shipped one");
  }

  @Test
  @DisplayName("threshold_chars decides whether a document is chunked at all")
  void thresholdDecidesChunkingAtAll() {
    publish(Map.of());
    assertEquals(2000, ChunkDocumentWriter.activePolicy().thresholdChars());

    publish(Map.of("justsearch.chunking.sweep.threshold_chars", "512"));
    assertEquals(512, ChunkDocumentWriter.activePolicy().thresholdChars());
  }
}
