/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * NER entity extraction backfill for documents with {@code ner_status=PENDING}.
 *
 * <p>Runs after embedding backfill is complete (NER facets are supplementary). CPU-only — no GPU
 * conflict management needed.
 */
public final class NerBackfillOps {
  private NerBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      Supplier<NerService> nerServiceSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      Logger log) {}

  /**
   * Processes one batch of NER backfill.
   *
   * @return the batch outcome (tempdoc 710 Move 2 item 4) — {@link BackfillScheduler} records
   *     per-stage timing/counts from this instead of relying on a metrics call inside the op.
   */
  public static StageOutcome processNerBackfill(BackfillContext context) {
    long methodStart = System.nanoTime();
    try {
      List<String> pendingIds =
          context
              .documentFieldOps()
              .queryDocIdsByField(
                  SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING, context.batchSize());

      if (pendingIds.isEmpty()) {
        return StageOutcome.none();
      }

      context.log().info("Processing NER backfill for {} documents", pendingIds.size());
      int processed = 0;
      int failed = 0;
      int markedFailed = 0;

      // Collect updates during per-doc NER inference, then batch-write at the end
      // (single NRT refresh instead of 100 × 85ms per-doc refreshes)
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();

      for (String docId : pendingIds) {
        // NER is CPU-only: interrupt on shutdown or user activity, but no GPU check
        boolean shouldInterrupt =
            !context.runningSupplier().getAsBoolean() || context.signalBus().isUserActive();
        if (shouldInterrupt) {
          context
              .log()
              .debug(
                  "NER backfill interrupted: user active={}, stopping={}",
                  context.signalBus().isUserActive(),
                  !context.runningSupplier().getAsBoolean());
          break;
        }

        try {
          String content = context.documentFieldOps().getDocumentContent(docId);
          if (content == null || content.isBlank()) {
            context.log().debug("NER backfill: no content for {}, marking COMPLETED", docId);
            Map<String, Object> updates = new HashMap<>();
            updates.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED);
            batchUpdates.add(Map.entry(docId, updates));
            processed++;
            continue;
          }

          NerService nerService = context.nerServiceSupplier().get();
          if (nerService == null) {
            context.log().debug("NER backfill: service unavailable, stopping batch");
            break;
          }
          List<NerResult> nerBatch = nerService.extractEntitiesBatch(List.of(content));
          NerResult result = nerBatch.isEmpty() ? NerResult.EMPTY : nerBatch.get(0);

          Map<String, Object> updates = new HashMap<>();
          updates.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED);
          updates.put(SchemaFields.NER_RETRY_COUNT, "0");

          if (!result.isEmpty()) {
            if (!result.persons().isEmpty()) {
              updates.put(SchemaFields.ENTITY_PERSONS_RAW, new ArrayList<>(result.persons()));
              updates.put(SchemaFields.ENTITY_PERSONS_TEXT, String.join(" ", result.persons()));
            }
            if (!result.organizations().isEmpty()) {
              updates.put(
                  SchemaFields.ENTITY_ORGANIZATIONS_RAW, new ArrayList<>(result.organizations()));
              updates.put(
                  SchemaFields.ENTITY_ORGANIZATIONS_TEXT, String.join(" ", result.organizations()));
            }
            if (!result.locations().isEmpty()) {
              updates.put(SchemaFields.ENTITY_LOCATIONS_RAW, new ArrayList<>(result.locations()));
              updates.put(
                  SchemaFields.ENTITY_LOCATIONS_TEXT, String.join(" ", result.locations()));
            }
          }

          batchUpdates.add(Map.entry(docId, updates));
          processed++;

        } catch (Exception e) {
          context.log().error("NER backfill failed for doc {}: {}", docId, e.getMessage());
          markedFailed +=
              handleNerFailure(context.documentFieldOps(), context.indexingCoordinator(), docId, e.getMessage(), context.log());
          failed++;
        }
      }

      // Batch-write all collected updates (single NRT refresh for the entire batch)
      if (!batchUpdates.isEmpty()) {
        context.indexingCoordinator().updateDocumentsBatch(batchUpdates, true);
      }

      if (processed > 0 || failed > 0) {
        context
            .commitOps()
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_NER);
        context
            .log()
            .info(
                "NER backfill complete: {} processed, {} failed ({} permanently marked FAILED)",
                processed,
                failed,
                markedFailed);
      }
      return new StageOutcome(true, processed, (System.nanoTime() - methodStart) / 1_000_000);

    } catch (Exception e) {
      context.log().error("Error during NER backfill", e);
      return new StageOutcome(false, 0, (System.nanoTime() - methodStart) / 1_000_000);
    }
  }

  static int handleNerFailure(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator, String docId, String reason, Logger log) {
    try {
      String retryCountStr = documentFieldOps.getDocumentField(docId, SchemaFields.NER_RETRY_COUNT);
      int currentRetryCount = parseRetryCountOrZero(retryCountStr);
      Map<String, Object> updates = computeNerFailureUpdate(currentRetryCount);
      int retryCount = currentRetryCount + 1;

      if (updates.containsKey(SchemaFields.NER_STATUS)) {
        log.warn("NER permanently FAILED for {} after {} retries: {}", docId, retryCount, reason);
        indexingCoordinator.updateDocument(docId, updates, true);
        return 1;
      } else {
        log.debug(
            "NER retry {}/{} for {}: {}",
            retryCount,
            SchemaFields.NER_MAX_RETRIES,
            docId,
            reason);
        indexingCoordinator.updateDocument(docId, updates, true);
        return 0;
      }

    } catch (Exception e) {
      log.error("Failed to update NER retry count for {}", docId, e);
      return 0;
    }
  }

  /**
   * Pure computation of the retry-count/status update for a NER failure — no I/O. Shared by {@link
   * #handleNerFailure} (immediate single-doc write) and the combined enrichment path (merges the
   * result into its own single batched write), so the two stay in escalation-parity by
   * construction (tempdoc 700).
   *
   * @param currentRetryCount the doc's retry count *before* this failure
   * @return field updates: always {@code NER_RETRY_COUNT}; additionally {@code
   *     NER_STATUS=FAILED} once the incremented count reaches {@code NER_MAX_RETRIES}
   */
  static Map<String, Object> computeNerFailureUpdate(int currentRetryCount) {
    int retryCount = currentRetryCount + 1;
    Map<String, Object> updates = new HashMap<>();
    updates.put(SchemaFields.NER_RETRY_COUNT, String.valueOf(retryCount));
    if (retryCount >= SchemaFields.NER_MAX_RETRIES) {
      updates.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_FAILED);
    }
    return updates;
  }

  private static int parseRetryCountOrZero(String retryCountStr) {
    if (retryCountStr == null || retryCountStr.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(retryCountStr);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }
}
