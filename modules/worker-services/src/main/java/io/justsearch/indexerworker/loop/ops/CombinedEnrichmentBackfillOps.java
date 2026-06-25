/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import static io.justsearch.indexerworker.metrics.BatchTimingKeys.EMBED;
import static io.justsearch.indexerworker.metrics.BatchTimingKeys.FETCH;
import static io.justsearch.indexerworker.metrics.BatchTimingKeys.NER;
import static io.justsearch.indexerworker.metrics.BatchTimingKeys.SPLADE;
import static io.justsearch.indexerworker.metrics.BatchTimingKeys.TOTAL;
import static io.justsearch.indexerworker.metrics.BatchTimingKeys.WRITE;

import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Combined enrichment backfill: embedding + SPLADE + NER in a single RMW pass per document.
 *
 * <p>Eliminates cross-stage RMW churn where each independent backfill stage drops non-stored fields
 * from other stages (tempdoc 312 BUG-1). By running all enrichments on the same document and
 * writing once, no data is lost between stages. Follows the industry-standard pipeline-in-memory,
 * write-once pattern (ES ingest pipelines, Solr URPs, Vespa document processors).
 *
 * <p>Tempdoc 334 item 3.
 */
public final class CombinedEnrichmentBackfillOps {
  private CombinedEnrichmentBackfillOps() {}

  public record BackfillContext(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps,
      WorkerSignalBus signalBus,
      Supplier<EmbeddingProvider> embeddingProviderSupplier,
      Supplier<SpladeEncoder> spladeEncoderSupplier,
      Supplier<NerService> nerServiceSupplier,
      BooleanSupplier runningSupplier,
      BooleanSupplier allowEmbeddingWritesSupplier,
      int batchSize,
      Logger log,
      boolean chunkVectorsEnabled,
      java.util.ArrayDeque<String> parentIdCache,
      java.util.ArrayDeque<String> chunkIdCache,
      int[] batchesSinceCommit) {}

  /**
   * Processes a batch of documents through all available enrichments in a single pass. Each
   * document is read once, enriched with embedding + SPLADE + NER as needed, and written once via a
   * single batch RMW call.
   *
   * @return true if any work was done (for backfillDidWork tracking)
   */
  public static boolean processCombinedBackfill(BackfillContext context) {
    // Timing/count accumulators hoisted so they survive exceptions (350).
    int embedProcessed = 0;
    int spladeProcessed = 0;
    int nerProcessed = 0;
    long embedMs = 0;
    long spladeMs = 0;
    long nerMs = 0;
    long fetchMs = 0;
    long writeMs = 0;
    long totalMs = 0;
    boolean recordTiming = false;

    // Tempdoc 400 LR2-a: enrichment.batch parent span. Encoder ORT spans
    // emitted inside are parented under this when detailed tracing is on;
    // noop (Span.getInvalid) when off so there is no measurable overhead.
    Span enrichmentSpan = EncoderOrtRunSpans.maybeEnrichmentBatch();
    try (Scope _ = enrichmentSpan.makeCurrent()) {
    try {
      // Phase 0: Query pending docs. Uses caches from tight loop — first call queries all
      // pending IDs (no limit), subsequent calls pop from cache. Eliminates 4 Lucene queries
      // per iteration after the first (334 Phase 10).
      boolean embedAvailable =
          context.allowEmbeddingWritesSupplier().getAsBoolean()
              && context.embeddingProviderSupplier().get() != null
              && context.embeddingProviderSupplier().get().isAvailable();
      boolean spladeAvailable = context.spladeEncoderSupplier().get() != null;
      boolean nerAvailable =
          context.nerServiceSupplier().get() != null
              && context.nerServiceSupplier().get().isAvailable();

      // Populate parent cache on first call (or when drained)
      if (context.parentIdCache().isEmpty()) {
        Set<String> allPending = new LinkedHashSet<>();
        if (embedAvailable) {
          allPending.addAll(
              context
                  .documentFieldOps()
                  .queryDocIdsByField(
                      SchemaFields.EMBEDDING_STATUS,
                      SchemaFields.EMBEDDING_STATUS_PENDING,
                      Integer.MAX_VALUE));
        }
        if (spladeAvailable) {
          allPending.addAll(
              context
                  .documentFieldOps()
                  .queryDocIdsByField(
                      SchemaFields.SPLADE_STATUS,
                      SchemaFields.SPLADE_STATUS_PENDING,
                      Integer.MAX_VALUE));
        }
        if (nerAvailable) {
          allPending.addAll(
              context
                  .documentFieldOps()
                  .queryDocIdsByField(
                      SchemaFields.NER_STATUS,
                      SchemaFields.NER_STATUS_PENDING,
                      Integer.MAX_VALUE));
        }
        context.parentIdCache().addAll(allPending);
      }

      // Populate chunk cache on first call (or when drained)
      int chunkSlotsPerBatch = 50;
      if (context.chunkIdCache().isEmpty() && embedAvailable && context.chunkVectorsEnabled()) {
        context
            .chunkIdCache()
            .addAll(
                context
                    .documentFieldOps()
                    .queryDocIdsByField(
                        SchemaFields.CHUNK_EMBEDDING_STATUS,
                        SchemaFields.EMBEDDING_STATUS_PENDING,
                        Integer.MAX_VALUE));
      }

      if (context.parentIdCache().isEmpty() && context.chunkIdCache().isEmpty()) {
        return false;
      }

      // Pop batchSize parents + chunkSlots chunks from caches
      List<String> pendingIds = new ArrayList<>(context.batchSize() + chunkSlotsPerBatch);
      for (int i = 0; i < context.batchSize() && !context.parentIdCache().isEmpty(); i++) {
        pendingIds.add(context.parentIdCache().poll());
      }
      Set<String> chunkDocIds = new LinkedHashSet<>();
      for (int i = 0; i < chunkSlotsPerBatch && !context.chunkIdCache().isEmpty(); i++) {
        String id = context.chunkIdCache().poll();
        chunkDocIds.add(id);
        pendingIds.add(id);
      }

      if (pendingIds.isEmpty()) {
        return false;
      }

      long t0 = System.nanoTime();

      // Phase 1: Batch content fetch (single searcher, all docs)
      Map<String, String> contentByDocId =
          context.documentFieldOps().getDocumentContentBatch(pendingIds);

      // Phase 1b: Batch status + chunk_content fetch (single searcher, all docs).
      // Replaces 300-400 individual getDocumentField() calls with one batched read.
      // All status fields are DocValues-backed (O(1) per read). CHUNK_CONTENT is stored.
      Set<String> fieldsToFetch = new LinkedHashSet<>();
      if (embedAvailable) fieldsToFetch.add(SchemaFields.EMBEDDING_STATUS);
      if (spladeAvailable) {
        fieldsToFetch.add(SchemaFields.SPLADE_STATUS);
        fieldsToFetch.add(SchemaFields.CHUNK_CONTENT);
      }
      if (nerAvailable) fieldsToFetch.add(SchemaFields.NER_STATUS);
      if (!chunkDocIds.isEmpty()) fieldsToFetch.add(SchemaFields.CHUNK_EMBEDDING_STATUS);

      Map<String, Map<String, String>> batchedFields =
          fieldsToFetch.isEmpty()
              ? Map.of()
              : context.documentFieldOps().getDocumentFieldsBatch(pendingIds, fieldsToFetch);

      // Phase 2: Collect docs needing each enrichment
      List<String> embedDocIds = new ArrayList<>();
      List<String> embedContents = new ArrayList<>();
      List<String> spladeDocIds = new ArrayList<>();
      List<String> spladeContents = new ArrayList<>();

      Map<String, Map<String, Object>> updatesByDocId = new LinkedHashMap<>();

      // Track which doc IDs are chunk docs (need CHUNK_VECTOR instead of VECTOR)
      Set<String> chunkIdsInBatch = new HashSet<>();

      for (String docId : pendingIds) {
        boolean isChunkDoc = chunkDocIds.contains(docId);
        updatesByDocId.put(docId, new HashMap<>());
        Map<String, String> docFields = batchedFields.getOrDefault(docId, Map.of());

        if (isChunkDoc) {
          // Chunk doc: only needs embedding. Content from CHUNK_CONTENT field.
          String chunkContent = docFields.get(SchemaFields.CHUNK_CONTENT);
          if (chunkContent == null || chunkContent.isBlank()) {
            // Also try the main content batch (getDocumentContentBatch reads CONTENT)
            chunkContent = contentByDocId.get(docId);
          }
          if (chunkContent == null || chunkContent.isBlank()) {
            updatesByDocId.get(docId).put(
                SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
            continue;
          }
          embedDocIds.add(docId);
          embedContents.add(chunkContent);
          chunkIdsInBatch.add(docId);
          continue;
        }

        // Parent doc: full enrichment (embed + SPLADE + NER)
        String content = contentByDocId.get(docId);

        String embedStatus = docFields.getOrDefault(
            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
        String spladeStatus = docFields.getOrDefault(
            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
        String nerStatus = docFields.getOrDefault(
            SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING);

        if (content == null || content.isBlank()) {
          Map<String, Object> updates = updatesByDocId.get(docId);
          if (embedAvailable && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
            updates.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
          }
          if (spladeAvailable && SchemaFields.SPLADE_STATUS_PENDING.equals(spladeStatus)) {
            updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
          }
          if (nerAvailable && SchemaFields.NER_STATUS_PENDING.equals(nerStatus)) {
            updates.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED);
          }
          continue;
        }

        if (embedAvailable && SchemaFields.EMBEDDING_STATUS_PENDING.equals(embedStatus)) {
          embedDocIds.add(docId);
          embedContents.add(content);
        }
        if (spladeAvailable && SchemaFields.SPLADE_STATUS_PENDING.equals(spladeStatus)) {
          spladeDocIds.add(docId);
          String chunkContent = docFields.get(SchemaFields.CHUNK_CONTENT);
          spladeContents.add(
              (chunkContent != null && !chunkContent.isBlank()) ? chunkContent : content);
        }
      }

      fetchMs = (System.nanoTime() - t0) / 1_000_000;

      // Check for interruption
      if (!context.runningSupplier().getAsBoolean() || context.signalBus().isUserActive()) {
        return false;
      }

      // Past early returns — any work from here should be recorded.
      recordTiming = true;

      // Phase 3a: Batch embedding
      long tEmbed = System.nanoTime();
      int embedFailed = 0;
      if (!embedDocIds.isEmpty() && embedAvailable) {
        EmbeddingProvider provider = context.embeddingProviderSupplier().get();
        List<float[]> vectors = provider.embedDocumentBatch(embedContents);
        if (vectors != null) {
          for (int i = 0; i < embedDocIds.size(); i++) {
            float[] vector = vectors.get(i);
            String eid = embedDocIds.get(i);
            Map<String, Object> updates = updatesByDocId.get(eid);
            boolean isChunk = chunkIdsInBatch.contains(eid);
            if (vector != null && vector.length > 0) {
              // Chunk docs use CHUNK_VECTOR/CHUNK_EMBEDDING_STATUS; parents use VECTOR/EMBEDDING_STATUS
              updates.put(
                  isChunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR, vector);
              updates.put(
                  isChunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS,
                  SchemaFields.EMBEDDING_STATUS_COMPLETED);
              updates.put(
                  isChunk
                      ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT
                      : SchemaFields.EMBEDDING_RETRY_COUNT,
                  "0");
              embedProcessed++;
            } else {
              updates.put(
                  isChunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS,
                  SchemaFields.EMBEDDING_STATUS_PENDING);
              embedFailed++;
            }
          }
        } else {
          // Batch failed — mark all as still pending (will retry next cycle)
          context.log().warn("Combined backfill: batch embedding returned null");
          embedFailed = embedDocIds.size();
        }
      }
      embedMs = (System.nanoTime() - tEmbed) / 1_000_000;

      // Phase 3b: Batch SPLADE (CPU to avoid GPU VRAM contention with embedding)
      long tSplade = System.nanoTime();
      int spladeFailed = 0;
      if (!spladeDocIds.isEmpty() && spladeAvailable) {
        SpladeEncoder encoder = context.spladeEncoderSupplier().get();
        try {
          List<Map<String, Float>> sparseVecs = encoder.encodeBatch(spladeContents);
          for (int i = 0; i < spladeDocIds.size(); i++) {
            Map<String, Object> updates = updatesByDocId.get(spladeDocIds.get(i));
            updates.put(SchemaFields.SPLADE, sparseVecs.get(i));
            updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
            updates.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
            spladeProcessed++;
          }
        } catch (Exception e) {
          context.log().warn("Combined backfill: SPLADE batch encode failed: {}", e.getMessage());
          spladeFailed = spladeDocIds.size();
        }
      }
      spladeMs = (System.nanoTime() - tSplade) / 1_000_000;

      // Phase 3c: NER (per-doc GPU inference — batching tested in item 22, regressed due to
      // padding waste exceeding the 467us/call overhead. Per-doc at 2.0ms/call is near-optimal.)
      long tNer = System.nanoTime();
      int nerFailed = 0;
      if (nerAvailable) {
        NerService nerService = context.nerServiceSupplier().get();
        for (String docId : pendingIds) {
          Map<String, String> docFields = batchedFields.getOrDefault(docId, Map.of());
          String nerSt = docFields.getOrDefault(
              SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING);
          if (!SchemaFields.NER_STATUS_PENDING.equals(nerSt)) {
            continue;
          }
          String content = contentByDocId.get(docId);
          if (content == null || content.isBlank()) {
            continue;
          }
          try {
            List<NerResult> nerBatch = nerService.extractEntitiesBatch(List.of(content));
            NerResult result = nerBatch.isEmpty() ? NerResult.EMPTY : nerBatch.get(0);
            Map<String, Object> updates = updatesByDocId.get(docId);
            updates.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED);
            updates.put(SchemaFields.NER_RETRY_COUNT, "0");
            if (!result.isEmpty()) {
              if (!result.persons().isEmpty()) {
                updates.put(SchemaFields.ENTITY_PERSONS_RAW, new ArrayList<>(result.persons()));
                updates.put(
                    SchemaFields.ENTITY_PERSONS_TEXT, String.join(" ", result.persons()));
              }
              if (!result.organizations().isEmpty()) {
                updates.put(
                    SchemaFields.ENTITY_ORGANIZATIONS_RAW,
                    new ArrayList<>(result.organizations()));
                updates.put(
                    SchemaFields.ENTITY_ORGANIZATIONS_TEXT,
                    String.join(" ", result.organizations()));
              }
              if (!result.locations().isEmpty()) {
                updates.put(
                    SchemaFields.ENTITY_LOCATIONS_RAW, new ArrayList<>(result.locations()));
                updates.put(
                    SchemaFields.ENTITY_LOCATIONS_TEXT, String.join(" ", result.locations()));
              }
            }
            nerProcessed++;
          } catch (Exception e) {
            context
                .log()
                .warn("Combined backfill: NER failed for {}: {}", docId, e.getMessage());
            nerFailed++;
          }
        }
      }
      nerMs = (System.nanoTime() - tNer) / 1_000_000;

      // Phase 4: Single batch write (one RMW per doc with all enrichments)
      long tWrite = System.nanoTime();
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
      for (var entry : updatesByDocId.entrySet()) {
        if (!entry.getValue().isEmpty()) {
          batchUpdates.add(Map.entry(entry.getKey(), entry.getValue()));
        }
      }
      int written = 0;
      if (!batchUpdates.isEmpty()) {
        var result = context.indexingCoordinator().updateDocumentsBatch(batchUpdates);
        written = result.updatedCount();
      }
      writeMs = (System.nanoTime() - tWrite) / 1_000_000;

      // Commit every 5 batches (334 Phase 10). Lucene 10.4 fixed the MMapDirectory
      // .si arena leak (issue #15068): confined arenas are closed immediately. This
      // should prevent the 24GB native memory growth that killed deferred commits in
      // Phase 8 (tested on Lucene 9.x). If OOM recurs, revert to per-batch commits.
      // NRT refresh is suspended during the tight loop (managed by caller) to prevent
      // mmap accumulation from ControlledRealTimeReopenThread reader snapshots.
      context.batchesSinceCommit()[0]++;
      if (written > 0 && context.batchesSinceCommit()[0] >= 5) {
        context
            .commitOps()
            .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.BACKFILL_COMBINED);
        context.batchesSinceCommit()[0] = 0;
      }

      totalMs = (System.nanoTime() - t0) / 1_000_000;

      context
          .log()
          .info(
              "Combined backfill: docs={} (embed={},splade={},chunks={}),"
                  + " fetch={}ms, embed={}ms(ok={},fail={}),"
                  + " splade={}ms(ok={},fail={}), ner={}ms(ok={},fail={}),"
                  + " write={}ms(written={}), total={}ms",
              pendingIds.size(),
              embedDocIds.size(),
              spladeDocIds.size(),
              chunkIdsInBatch.size(),
              fetchMs,
              embedMs,
              embedProcessed,
              embedFailed,
              spladeMs,
              spladeProcessed,
              spladeFailed,
              nerMs,
              nerProcessed,
              nerFailed,
              writeMs,
              written,
              totalMs);

      return written > 0;

    } catch (Exception e) {
      context.log().error("Error during combined enrichment backfill", e);
      return false;
    } finally {
      // 335 §10, 354: Record per-stage enrichment counts and timing for /api/status.
      // In a finally block so timing from completed stages survives exceptions
      // in later stages (e.g., embed succeeds but Lucene write throws).
      if (recordTiming) {
        var metrics = OperationalMetrics.getInstance();
        metrics.recordEnrichmentCompleted(EMBED, embedProcessed);
        metrics.recordEnrichmentCompleted(SPLADE, spladeProcessed);
        metrics.recordEnrichmentCompleted(NER, nerProcessed);
        metrics.recordStageTiming(EMBED, embedProcessed, embedMs);
        metrics.recordStageTiming(SPLADE, spladeProcessed, spladeMs);
        metrics.recordStageTiming(NER, nerProcessed, nerMs);
        metrics.recordBatchTiming(FETCH, fetchMs);
        metrics.recordBatchTiming(WRITE, writeMs);
        metrics.recordBatchTiming(TOTAL, totalMs);
      }
    }
    } finally {
      enrichmentSpan.end();
    }
  }
}
