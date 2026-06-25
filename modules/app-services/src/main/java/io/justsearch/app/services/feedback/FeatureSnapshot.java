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
   * {@code parent_token_count} field. {@code parentTokenCount} is nullable (not all hits carry it).
   */
  public record HitFeatures(
      String docId,
      int rank,
      float sparse,
      float dense,
      float splade,
      float fused,
      Long parentTokenCount) {}
}
