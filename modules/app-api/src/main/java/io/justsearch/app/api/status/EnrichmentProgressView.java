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
    boolean nerEnabled,
    // Tempdoc 710 Move 2 item 4: which backfill pass ran last idle cycle —
    // "combined" | "individual" | "idle". Previously unobservable: batchTiming/
    // enrichmentCompleted counters froze in individual mode with no signal explaining why
    // (710 S-B3 finding).
    String backfillMode) {
  public EnrichmentProgressView {
    backfillMode = backfillMode == null ? "idle" : backfillMode;
  }

  public static EnrichmentProgressView empty() {
    return EnrichmentProgressViewBuilder.builder()
        .chunk(ChunkCoverageView.empty())
        .enrichmentCompleted(Map.of())
        .batchTiming(BatchTimingView.empty())
        .encoderProfiles(Map.of())
        .embeddingEnabled(true)
        .spladeEnabled(true)
        .nerEnabled(true)
        .backfillMode("idle")
        .build();
  }
}
