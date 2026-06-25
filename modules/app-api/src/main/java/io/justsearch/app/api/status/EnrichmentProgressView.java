/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.Map;

/** Enrichment coverage for all subsystems (embedding, SPLADE, chunk, NER). */
@RecordBuilder
public record EnrichmentProgressView(
    ChunkCoverageView chunk,
    long embeddingDocCount,
    long embeddingCompletedCount,
    long embeddingPendingCount,
    long embeddingFailedCount,
    double embeddingCoveragePercent,
    long spladeDocCount,
    long spladeCompletedCount,
    long spladePendingCount,
    long spladeFailedCount,
    double spladeCoveragePercent,
    long pendingNerCount,
    long completedNerCount,
    Map<String, Long> enrichmentCompleted,
    BatchTimingView batchTiming,
    Map<String, EncoderProfileView> encoderProfiles,
    // Per-stage enabled state — consumers polling for completion must skip
    // disabled stages (their coverage stays at 0 forever). See tempdoc 394
    // for the investigation that surfaced this.
    boolean embeddingEnabled,
    boolean spladeEnabled,
    boolean nerEnabled) {
  public static EnrichmentProgressView empty() {
    return EnrichmentProgressViewBuilder.builder()
        .chunk(ChunkCoverageView.empty())
        .enrichmentCompleted(Map.of())
        .batchTiming(BatchTimingView.empty())
        .encoderProfiles(Map.of())
        .embeddingEnabled(true)
        .spladeEnabled(true)
        .nerEnabled(true)
        .build();
  }
}
