/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Entity disambiguation backfill for documents with completed NER extraction.
 *
 * <p>Harvests raw entity mentions from NER-completed documents, feeds them to the
 * {@link DisambiguationService} for clustering. Runs after NER backfill is complete
 * (disambiguation is supplementary to raw entity facets).
 */
public final class DisambiguationBackfillOps {
  private DisambiguationBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      WorkerSignalBus signalBus,
      Supplier<DisambiguationService> serviceSupplier,
      BooleanSupplier runningSupplier,
      int batchSize,
      Logger log) {}

  /**
   * Processes a batch of NER-completed documents through disambiguation.
   *
   * <p>Collects all unique raw entity mentions from recently NER-completed documents and
   * feeds them to the disambiguation service for clustering.
   */
  public static void processDisambiguationBackfill(BackfillContext context) {
    try {
      DisambiguationService service = context.serviceSupplier().get();
      if (service == null || !service.isAvailable()) {
        return;
      }

      // Query documents with completed NER
      List<String> completedIds =
          context
              .documentFieldOps()
              .queryDocIdsByField(
                  SchemaFields.NER_STATUS,
                  SchemaFields.NER_STATUS_COMPLETED,
                  context.batchSize());

      if (completedIds.isEmpty()) {
        return;
      }

      // Harvest raw entity mentions from these documents
      Map<String, List<String>> mentionsByType = new HashMap<>();
      int docsScanned = 0;

      for (String docId : completedIds) {
        boolean shouldInterrupt =
            !context.runningSupplier().getAsBoolean() || context.signalBus().isUserActive();
        if (shouldInterrupt) {
          context
              .log()
              .debug(
                  "Disambiguation backfill interrupted: user active={}, stopping={}",
                  context.signalBus().isUserActive(),
                  !context.runningSupplier().getAsBoolean());
          break;
        }

        try {
          collectEntityMentions(context.documentFieldOps(), docId, mentionsByType);
          docsScanned++;
        } catch (Exception e) {
          context
              .log()
              .warn("Failed to collect entity mentions from doc {}: {}", docId, e.getMessage());
        }
      }

      if (mentionsByType.isEmpty()) {
        return;
      }

      // Feed to disambiguation service
      int created = service.processBatch(mentionsByType);
      if (created > 0 || docsScanned > 0) {
        context
            .log()
            .info(
                "Disambiguation backfill: scanned {} docs, {} new cluster entries",
                docsScanned,
                created);
      }

    } catch (Exception e) {
      context.log().error("Error during disambiguation backfill", e);
    }
  }

  private static void collectEntityMentions(
      DocumentFieldOps documentFieldOps, String docId, Map<String, List<String>> mentionsByType) {
    collectField(documentFieldOps, docId, SchemaFields.ENTITY_PERSONS_RAW, "PERSON", mentionsByType);
    collectField(
        documentFieldOps, docId, SchemaFields.ENTITY_ORGANIZATIONS_RAW, "ORGANIZATION", mentionsByType);
    collectField(documentFieldOps, docId, SchemaFields.ENTITY_LOCATIONS_RAW, "LOCATION", mentionsByType);
  }

  private static void collectField(
      DocumentFieldOps documentFieldOps,
      String docId,
      String field,
      String typeName,
      Map<String, List<String>> mentionsByType) {
    List<String> values = documentFieldOps.getDocumentFieldValues(docId, field);
    for (String mention : values) {
      String trimmed = mention.trim();
      if (!trimmed.isEmpty()) {
        mentionsByType.computeIfAbsent(typeName, k -> new ArrayList<>()).add(trimmed);
      }
    }
  }
}
