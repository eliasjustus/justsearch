package io.justsearch.indexerworker.services;

import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.TraceStage;

/**
 * Tempdoc 549 Phase E5 test helper: read the per-query signals that the retired flat
 * SearchResponse fields used to carry from the unified SearchTrace instead. The chunk-merge
 * "reason code" reproduces the old flat-field semantics ({@code APPLIED} when the chunk-merge
 * stage executed, else the stage's skip reason) so the reason-code contract tests keep their
 * intent.
 */
final class TraceStageAccess {
  private TraceStageAccess() {}

  static TraceStage stage(SearchResponse r, String id) {
    if (!r.hasSearchTrace()) return TraceStage.getDefaultInstance();
    return r.getSearchTrace().getStagesList().stream()
        .filter(s -> s.getId().equals(id))
        .findFirst()
        .orElse(TraceStage.getDefaultInstance());
  }

  static boolean chunkMergeApplied(SearchResponse r) {
    return "executed".equals(stage(r, "chunk-merge").getStatus());
  }

  /** APPLIED when the chunk-merge stage executed, else the stage skip reason (flat-field parity). */
  static String chunkMergeReason(SearchResponse r) {
    TraceStage s = stage(r, "chunk-merge");
    return "executed".equals(s.getStatus()) ? "APPLIED" : s.getReason();
  }

  static boolean correctionApplied(SearchResponse r) {
    return "executed".equals(stage(r, "correction").getStatus());
  }

  static String correctedQuery(SearchResponse r) {
    return stage(r, "correction").getDetail();
  }
}
