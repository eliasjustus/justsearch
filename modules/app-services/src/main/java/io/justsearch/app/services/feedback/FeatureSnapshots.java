/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 580 §17 (Track C P1) — projects a search response into a {@link FeatureSnapshot} by
 * reading each hit's per-hit {@link SearchTrace.HitStage} scores.
 *
 * <p>Pure (response → snapshot), so the head-side capture point ({@code KnowledgeSearchController})
 * stays lean: it generates the {@code interactionId}, calls {@link #capture}, and persists via
 * {@link FeatureSnapshotStore}. The de-risking (580 §17.10) established that these features are
 * otherwise lost when the ephemeral {@code SearchTrace} is dropped after the response is sent.
 */
public final class FeatureSnapshots {

  /** Field carrying the parent document's token count (mirrors {@code SchemaFields.PARENT_TOKEN_COUNT}). */
  private static final String PARENT_TOKEN_COUNT = "parent_token_count";

  private FeatureSnapshots() {}

  /** Builds a per-query feature snapshot from the response's per-hit trace stages. */
  public static FeatureSnapshot capture(
      String interactionId, String query, long occurredAtMs, KnowledgeSearchResponse response) {
    List<FeatureSnapshot.HitFeatures> hits = new ArrayList<>();
    if (response != null && response.results() != null) {
      int rank = 1;
      for (KnowledgeSearchResponse.Hit hit : response.results()) {
        hits.add(hitFeatures(hit, rank++));
      }
    }
    return new FeatureSnapshot(interactionId, query, occurredAtMs, hits);
  }

  private static FeatureSnapshot.HitFeatures hitFeatures(
      KnowledgeSearchResponse.Hit hit, int rank) {
    SearchTrace.LegScores legs = SearchTrace.legScores(hit.trace(), (float) hit.score());
    return new FeatureSnapshot.HitFeatures(
        hit.id(),
        rank,
        legs.sparse(),
        legs.dense(),
        legs.splade(),
        legs.fused(),
        parentTokenCount(hit.fields()));
  }

  private static Long parentTokenCount(Map<String, String> fields) {
    if (fields == null) {
      return null;
    }
    String raw = fields.get(PARENT_TOKEN_COUNT);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return (long) Double.parseDouble(raw);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
