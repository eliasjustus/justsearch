/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

/**
 * Captured corpus-shape probes used by the planner to gate chunk-merge.
 *
 * <p>Materialised once at {@code SearchInputCapture} time from
 * {@code DocumentFieldOps.queryDocIdsByField(IS_CHUNK,...)} and
 * {@code IndexCountOps.getOrComputeCorpusProfile()} — replaces the duplicate
 * probe at the old {@code SearchOrchestrator.java:248} + {@code :745}.
 */
public record CorpusCapabilities(
    boolean hasChunkDocs,
    boolean isShortCorpus,
    long medianTokenCount,
    double chunkRate) {

  /** Returns {@code true} if the corpus supports chunk augmentation. */
  public boolean corpusSupportsChunks() {
    return hasChunkDocs && !isShortCorpus;
  }
}
