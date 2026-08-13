/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
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
    // Tempdoc 821 §3-C3 — NER reported only pending/completed; the other three stages have
    // carried a failed count since 354, so a stalled NER population was invisible here.
    long failedNerCount,
    // Tempdoc 821 §3-C3 — the per-stage completeness audit, and the two thresholds the auditor
    // owns. Publishing the thresholds is what lets off-process oracles (jseval's
    // chunk_completeness guard) read them instead of mirroring the Java constants and drifting.
    List<StageCompletenessView> completeness,
    int chunkMinChars,
    double vectorReadyPercent,
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
    completeness = completeness == null ? List.of() : List.copyOf(completeness);
  }

  public static EnrichmentProgressView empty() {
    return EnrichmentProgressViewBuilder.builder()
        .chunk(ChunkCoverageView.empty())
        .completeness(List.of())
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
