/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Recovery for a legacy index whose embedding fingerprint was never stamped.
 *
 * <p>When an index is {@code BLOCKED_LEGACY} (documents present, no stored
 * {@code embedding_model_sha256}) the vectors on documents already marked
 * {@code EMBEDDING_STATUS=COMPLETED} — or {@code FAILED} — have unknowable provenance: they may have
 * been written by a different embedding model, and there is no fingerprint to prove otherwise. The
 * only sound recovery is to re-embed them under the current model. This op re-marks those parent
 * documents back to {@code PENDING} so the existing embedding backfill picks them up; the backfill
 * overwrites each stale vector and, once {@code pending==0}, the rebuild certifies and stamps the
 * current fingerprint.
 *
 * <p>Only parent documents carry {@code EMBEDDING_STATUS} (chunks use
 * {@code CHUNK_EMBEDDING_STATUS}), so the term query naturally scopes to parents.
 */
public final class EmbeddingRecoveryOps {
  private EmbeddingRecoveryOps() {}

  /**
   * Re-marks every parent document currently {@code COMPLETED} or {@code FAILED} back to
   * {@code PENDING} so the embedding backfill re-embeds it under the current model.
   *
   * <p>The full COMPLETED/FAILED id set is collected FIRST (the set is stable while nothing has been
   * mutated), then re-marked with read-modify-write batch updates (other fields, e.g. content, are
   * preserved). Collect-then-update avoids the NRT-visibility lag that a query-mutate-requery drain
   * would hit — a just-updated document can still appear in the next status query for an iteration,
   * which would otherwise double-count or spin.
   *
   * @return the number of distinct documents re-marked PENDING
   */
  public static int remarkEmbeddedParentDocsPending(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator coordinator,
      int batchSize,
      Logger log) {
    if (documentFieldOps == null || coordinator == null || batchSize <= 0) {
      return 0;
    }
    Set<String> ids = new LinkedHashSet<>();
    ids.addAll(
        collectAllIds(documentFieldOps, SchemaFields.EMBEDDING_STATUS_COMPLETED, batchSize));
    ids.addAll(collectAllIds(documentFieldOps, SchemaFields.EMBEDDING_STATUS_FAILED, batchSize));
    if (ids.isEmpty()) {
      return 0;
    }

    int total = 0;
    List<Map.Entry<String, Map<String, Object>>> batch = new ArrayList<>(batchSize);
    for (String id : ids) {
      batch.add(
          Map.entry(
              id, Map.of(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING)));
      if (batch.size() >= batchSize) {
        total += coordinator.updateDocumentsBatch(batch).updatedCount();
        batch.clear();
      }
    }
    if (!batch.isEmpty()) {
      total += coordinator.updateDocumentsBatch(batch).updatedCount();
    }

    if (total > 0 && log != null) {
      log.warn(
          "Embedding recovery: re-marked {} parent document(s) (COMPLETED/FAILED) back to PENDING"
              + " for re-embed under the current model (legacy index had no stored fingerprint)",
          total);
    }
    return total;
  }

  /**
   * Collects every parent-doc id whose {@code EMBEDDING_STATUS} equals {@code value}. Widens the
   * query limit until the result is smaller than the limit (meaning all matches were returned);
   * safe because no mutation happens during collection, so the match set is stable.
   */
  private static List<String> collectAllIds(
      DocumentFieldOps documentFieldOps, String value, int batchSize) {
    int limit = Math.max(batchSize, 1);
    while (true) {
      List<String> ids =
          documentFieldOps.queryDocIdsByField(SchemaFields.EMBEDDING_STATUS, value, limit);
      if (ids.size() < limit || limit >= 1 << 26) {
        // Fewer than the limit → we have them all. The 2^26 cap bounds allocation on a
        // pathologically huge index; the remainder recovers on the next boot (still BLOCKED_LEGACY).
        return ids;
      }
      limit <<= 1;
    }
  }
}
