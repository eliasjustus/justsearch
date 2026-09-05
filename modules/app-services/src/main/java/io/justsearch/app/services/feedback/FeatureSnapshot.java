/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import java.util.List;

/**
 * Tempdoc 580 §17 (Track C P1) — a per-query snapshot of the ranking features the engine computed,
 * persisted under a stable {@code interactionId}.
 *
 * <p>The de-risking pass (580 §17.10) found that {@code SearchTrace} is <em>ephemeral</em> (built,
 * returned on the HTTP response, then discarded), so its per-hit features cannot be recovered later
 * to build a training/eval example. This record is the durable capture of those features at search
 * time, keyed by {@code interactionId}, so a later result-disposition (what the user/agent did with
 * a result) can join back to "what we ranked and why" — the §17.4 join, made real.
 */
public record FeatureSnapshot(
    String interactionId, String query, long occurredAtMs, List<HitFeatures> hits) {

  /**
   * Per-hit ranking features, extractable head-side from each response hit's per-hit
   * {@code SearchTrace.HitStage} list (sparse/dense/splade/fused stage scores) plus the
   * {@code parent_token_count} field. New rows key {@code docId} with the stable parent
   * {@code doc_uid}; {@code sourceDocId} retains the path-oriented id used by unchanged UI and agent
   * events only long enough to correlate a later disposition. Records written before Phase 2 have
   * no {@code sourceDocId}; their path-keyed {@code docId} remains the legacy join key.
   * {@code parentTokenCount} is nullable (not all hits carry it).
   *
   * <p>{@code contentRevision} (tempdoc 931 §C.6) is the parent's {@code content_sha256} at capture
   * time: identity says WHICH document was ranked, this says which VERSION of it. Nullable, and
   * null means UNKNOWN — rows written before this field, and hits from an index predating it,
   * deserialize with null and are never treated as revision mismatches.
   */
  public record HitFeatures(
      String docId,
      String sourceDocId,
      int rank,
      float sparse,
      float dense,
      float splade,
      float fused,
      Long parentTokenCount,
      String contentRevision) {

    /** Source-compatible constructor for callers that carry no content revision. */
    public HitFeatures(
        String docId,
        String sourceDocId,
        int rank,
        float sparse,
        float dense,
        float splade,
        float fused,
        Long parentTokenCount) {
      this(docId, sourceDocId, rank, sparse, dense, splade, fused, parentTokenCount, null);
    }

    /** Source-compatible constructor for legacy path-keyed rows and callers. */
    public HitFeatures(
        String docId,
        int rank,
        float sparse,
        float dense,
        float splade,
        float fused,
        Long parentTokenCount) {
      this(docId, null, rank, sparse, dense, splade, fused, parentTokenCount, null);
    }
  }
}
