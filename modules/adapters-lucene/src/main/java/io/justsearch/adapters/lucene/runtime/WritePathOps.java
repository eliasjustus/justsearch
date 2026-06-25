/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.*;
import static io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.normalizePathPrefix;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents;
import io.justsearch.indexing.SchemaFields;
import net.jcip.annotations.ThreadSafe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Internal write-path collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates core index-hub operations (single-doc write, batch apply, soft-delete
 * maintenance) that were previously inlined in the runtime. Guards (ensureStarted, guardWritable,
 * guardBackpressure) and queue-depth accounting remain in the facade.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code close()}.
 * Access from the runtime must go through a volatile snapshot to ensure visibility across threads.
 */
@ThreadSafe
public final class WritePathOps {
  private static final Logger log = LoggerFactory.getLogger(WritePathOps.class);

  private final RuntimeSession session;
  private final String idField;
  private final SearcherBridge bridge;

  WritePathOps(RuntimeSession session, String idField, SearcherBridge bridge) {
    this.session = session;
    this.idField = idField;
    this.bridge = bridge;
  }

  /**
   * Core single-document write. Caller is responsible for guards and queue-depth accounting.
   *
   * @param fields the document fields (already validated)
   */
  void indexDocument(Map<String, Object> fields) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null) {
        throw new IllegalStateException(
            "IndexWriter not available (runtime not started or closed)");
      }
      String idValue = asString(fields.get(idField));
      if (idValue == null || idValue.isBlank()) {
        throw new IllegalArgumentException("IndexDocument missing required id field " + idField);
      }
      boolean hardDelete = asBoolean(fields.get(session.hardDeleteField));
      if (hardDelete) {
        w.deleteDocuments(new Term(idField, idValue));
        TelemetryEvents te = session.telemetryEvents;
        if (te != null) te.onHardDelete();
        return;
      }
      Document luceneDoc = session.fieldMapper.toDocument(fields);
      boolean softDelete = asBoolean(fields.get(session.softDeleteField));
      if (softDelete) {
        appendSoftDeleteMaintenanceFields(luceneDoc, fields);
        w.softUpdateDocument(
            new Term(idField, idValue),
            luceneDoc,
            new NumericDocValuesField(session.softDeleteField, 1));
      } else {
        w.updateDocument(new Term(idField, idValue), luceneDoc);
      }
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to add document", e);
    }
  }

  /**
   * Applies a pre-classified batch of updates, soft-deletes, and hard-deletes to the writer.
   */
  void applyBatch(
      List<DocWork> updates, List<DocWork> softDeletes, List<String> hardDeletes) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null) {
        throw new IllegalStateException(
            "IndexWriter not available (runtime not started or closed)");
      }
      if (!hardDeletes.isEmpty()) {
        Term[] terms = hardDeletes.stream().map(id -> new Term(idField, id)).toArray(Term[]::new);
        w.deleteDocuments(terms);
      }
      for (DocWork work : updates) {
        w.updateDocument(new Term(idField, work.id()), work.doc());
      }
      for (DocWork work : softDeletes) {
        appendSoftDeleteMaintenanceFields(work.doc(), work.fields());
        w.softUpdateDocument(
            new Term(idField, work.id()),
            work.doc(),
            new NumericDocValuesField(session.softDeleteField, 1));
      }
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to apply batch", e);
    }
  }

  void appendSoftDeleteMaintenanceFields(Document doc, Map<String, Object> fields) {
    Long ts = asLong(fields.get(softDeleteTimestampField(session.softDeleteField)));
    long effectiveTs = ts != null ? ts : System.currentTimeMillis();
    doc.add(
        new SortedNumericDocValuesField(softDeleteTimestampField(session.softDeleteField), effectiveTs));
    Long ordinal = asLong(fields.get(softDeleteVersionField(session.softDeleteField)));
    if (ordinal != null) {
      doc.add(
          new SortedNumericDocValuesField(softDeleteVersionField(session.softDeleteField), ordinal));
    }
    doc.add(new NumericDocValuesField(session.softDeleteField, 1));
  }

  /**
   * Deletes a single document by id. Caller is responsible for guards and queue-depth accounting.
   */
  void deleteById(String id) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null) {
        throw new IllegalStateException("IndexWriter not available (runtime not started or closed)");
      }
      w.deleteDocuments(new Term(idField, id));
      session.pendingDocs.incrementAndGet();
      TelemetryEvents te = session.telemetryEvents;
      if (te != null) te.onHardDelete();
    } catch (IOException e) {
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to delete document", e);
    }
  }

  /**
   * Deletes a parent document and all of its chunk documents. Caller is responsible for guards and
   * queue-depth accounting.
   */
  void deleteByIdAndChunks(String parentDocId) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      // 1) Delete the parent doc (exact id).
      w.deleteDocuments(new Term(idField, parentDocId));

      // 2) Delete chunk docs using field-based query (P0.8: no string pattern matching)
      // This works for both legacy and new opaque chunk IDs.
      BooleanQuery chunkQuery =
          new BooleanQuery.Builder()
              .add(
                  new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentDocId)),
                  BooleanClause.Occur.FILTER)
              .add(
                  new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                  BooleanClause.Occur.FILTER)
              .build();
      w.deleteDocuments(chunkQuery);

      session.pendingDocs.incrementAndGet();
      TelemetryEvents te = session.telemetryEvents;
      if (te != null) te.onHardDelete();
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to delete document and chunks", e);
    }
  }

  /**
   * Deletes chunk documents for a parent document using field-based filtering. Caller is
   * responsible for guards and queue-depth accounting.
   */
  void deleteChunksForParentDocId(String parentDocId) {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      // Build query: parent_doc_id == parentDocId AND is_chunk == "true"
      BooleanQuery query =
          new BooleanQuery.Builder()
              .add(
                  new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentDocId)),
                  BooleanClause.Occur.FILTER)
              .add(
                  new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                  BooleanClause.Occur.FILTER)
              .build();
      w.deleteDocuments(query);
      session.pendingDocs.incrementAndGet();
      TelemetryEvents te = session.telemetryEvents;
      if (te != null) te.onHardDelete();
      log.debug("deleteChunksForParentDocId: submitted delete for chunks of {}", parentDocId);
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to delete chunks for parent", e);
    }
  }

  /**
   * Deletes all documents (parents and chunks) under the given path prefix. No pendingDocs/queue
   * accounting — caller may handle this differently.
   */
  void deleteByPathPrefix(String pathPrefix) {
    // Normalize the prefix (case-insensitive on Windows, ensure trailing separator)
    String normalized = normalizePathPrefix(pathPrefix);
    try {
      // Use 'path' field for prefix matching (works for both parent docs and chunks)
      Query query = new PrefixQuery(new Term(SchemaFields.PATH, normalized));
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null) {
        throw new IllegalStateException("IndexWriter not available (runtime not started or closed)");
      }
      w.deleteDocuments(query);
      log.info("deleteByPathPrefix: deletion submitted for path prefix: {}", normalized);
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to delete by path prefix", e);
    }
  }

  /**
   * Read-modify-write: loads existing stored fields, merges with updates, and re-indexes. The caller
   * provides a leased searcher (via ReadPathOps.withSearcher) to ensure the searcher stays valid for
   * the duration of the read and subsequent write.
   *
   * @return true if the document was found and updated, false if not found
   */
  boolean readModifyWrite(IndexSearcher searcher, String docId, Map<String, Object> updates)
      throws IOException {
    return readModifyWrite(searcher, docId, updates, false);
  }

  /**
   * Read-modify-write with optional SPLADE preservation. When {@code preserveSplade} is true, the
   * guard that resets SPLADE_STATUS to PENDING is skipped. Use this for writes that don't affect
   * document content (retry counters, lifecycle status changes) where existing SPLADE vectors should
   * be preserved. See tempdoc 334 Phase 15 item 36.
   */
  boolean readModifyWrite(
      IndexSearcher searcher, String docId, Map<String, Object> updates, boolean preserveSplade)
      throws IOException {
    var topDocs = searcher.search(new TermQuery(new Term(idField, docId)), 1);
    if (topDocs.scoreDocs.length == 0) {
      log.debug("readModifyWrite: document not found: {}", docId);
      return false;
    }

    // Load all stored fields from existing document, accumulating multi-valued fields into Lists
    Document oldDoc = searcher.storedFields().document(topDocs.scoreDocs[0].doc);
    Map<String, Object> fields = new HashMap<>();
    for (IndexableField field : oldDoc.getFields()) {
      String name = field.name();
      Object val = field.stringValue() != null ? field.stringValue() : field.numericValue();
      if (val == null) continue;
      if (fields.containsKey(name)) {
        // Multi-valued field: accumulate into a mutable List
        Object existing = fields.get(name);
        if (existing instanceof List<?>) {
          @SuppressWarnings("unchecked")
          List<Object> mutable = (List<Object>) existing;
          mutable.add(val);
        } else {
          List<Object> list = new ArrayList<>();
          list.add(existing);
          list.add(val);
          fields.put(name, list);
        }
      } else {
        fields.put(name, val);
      }
    }

    // Non-stored doc-values-only status fields (splade_status, splade_retry_count) are
    // invisible to storedFields().document() above. Restore them from doc-values so RMW
    // preserves lifecycle metadata regardless of preserveSplade. Without this, any caller
    // passing preserveSplade=true (e.g., NerBackfillOps) silently drops splade_status —
    // the doc disappears from both the PENDING backfill query and the COMPLETED counter,
    // and SPLADE coverage stalls below 100% forever.
    if (!updates.containsKey(SchemaFields.SPLADE_STATUS)) {
      String existingStatus =
          readKeywordDocValue(
              searcher, topDocs.scoreDocs[0].doc, SchemaFields.SPLADE_STATUS);
      if (existingStatus != null) {
        fields.put(SchemaFields.SPLADE_STATUS, existingStatus);
      }
    }
    if (!updates.containsKey(SchemaFields.SPLADE_RETRY_COUNT)) {
      Long existingRetry =
          readNumericDocValue(
              searcher, topDocs.scoreDocs[0].doc, SchemaFields.SPLADE_RETRY_COUNT);
      if (existingRetry != null) {
        fields.put(SchemaFields.SPLADE_RETRY_COUNT, Long.toString(existingRetry));
      }
    }

    // Non-stored fields (SPLADE FeatureFields) are also invisible to storedFields().document().
    // If the caller doesn't supply SPLADE fields in the update map AND didn't ask to preserve,
    // reset the status to PENDING so the backfill re-encodes. See tempdoc 312 Phase 8 BUG-1.
    // When preserveSplade=true, skip this guard — the caller is writing non-content fields
    // (retry counters, lifecycle status) and the existing SPLADE vector should be preserved.
    // See tempdoc 334 Phase 15 item 36.
    if (!preserveSplade
        && !updates.containsKey(SchemaFields.SPLADE_STATUS)
        && !updates.containsKey(SchemaFields.SPLADE)) {
      fields.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
      fields.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
    }

    // Final safety net: if after all of the above the doc still has no splade_status
    // (e.g., corrupted by a prior RMW before this fix was deployed), mark it PENDING
    // so the backfill can recover it. Without this, existing broken docs stay invisible
    // to both the backfill query and the counters forever.
    if (!fields.containsKey(SchemaFields.SPLADE_STATUS)) {
      // Observability: tempdoc 393 item 1.2. Under current code paths this branch
      // should not fire — every ingest path sets SPLADE_STATUS and the doc-values
      // read above restores it across RMW. A fire here signals either a pre-fix
      // corrupted doc (expected one-time heal) or a new ingest path that forgot
      // to initialize the field (regression — investigate).
      log.debug("splade_status safety-net fired for doc {}", docId);
      fields.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);
      if (!fields.containsKey(SchemaFields.SPLADE_RETRY_COUNT)) {
        fields.put(SchemaFields.SPLADE_RETRY_COUNT, "0");
      }
    }

    // NER entity text fields are stored (326), so they survive RMW without a guard.
    // Unlike SPLADE FeatureFields (non-stored, invisible to storedFields()), entity
    // text fields are TextField(Store.YES) and are recovered from the old document.
    // An unconditional NER guard would create an infinite NER↔SPLADE reset loop
    // (NER RMW resets SPLADE → SPLADE RMW resets NER → repeat).

    // Apply updates (overwrites existing values)
    fields.putAll(updates);

    // Re-index with updated fields
    Document newDoc = session.fieldMapper.toDocument(fields);
    LifecycleSnapshot rmwSnap = session.snapshot;
    if (rmwSnap == null || rmwSnap.writer() == null) {
      throw new IllegalStateException("IndexWriter not available during read-modify-write");
    }
    rmwSnap.writer().updateDocument(new Term(idField, docId), newDoc);
    log.debug("readModifyWrite: updated document: {}", docId);
    return true;
  }

  /**
   * Batch read-modify-write: executes {@link #readModifyWrite} for each entry against the same
   * point-in-time searcher snapshot. The caller provides a leased searcher (via {@link
   * ReadPathOps#withSearcher}) with a single NRT refresh covering all writes.
   *
   * <p>Follows the same pattern as {@link #updateDocumentPaths}, which also performs multiple
   * {@code readModifyWrite} calls under one searcher lease.
   *
   * @param searcher the leased IndexSearcher (point-in-time snapshot for all reads)
   * @param batchUpdates list of (docId, updates) pairs
   * @param preserveSplade preserve SPLADE sparse-vector fields during the write
   * @return result with counts of updated and not-found documents
   * @throws IOException if any IndexWriter write fails (batch is partially applied)
   */
  LuceneRuntimeTypes.BatchUpdateResult readModifyWriteBatch(
      IndexSearcher searcher,
      List<Map.Entry<String, Map<String, Object>>> batchUpdates,
      boolean preserveSplade)
      throws IOException {
    int updated = 0;
    int notFound = 0;
    long minNs = Long.MAX_VALUE, maxNs = 0, sumNs = 0;
    for (Map.Entry<String, Map<String, Object>> entry : batchUpdates) {
      long t0 = System.nanoTime();
      if (readModifyWrite(searcher, entry.getKey(), entry.getValue(), preserveSplade)) {
        updated++;
      } else {
        notFound++;
      }
      long elapsed = System.nanoTime() - t0;
      sumNs += elapsed;
      if (elapsed < minNs) minNs = elapsed;
      if (elapsed > maxNs) maxNs = elapsed;
    }
    int total = updated + notFound;
    if (total > 0) {
      log.info(
          "RMW batch: docs={}, min={}ms, max={}ms, avg={}ms, total={}ms",
          total,
          minNs / 1_000_000,
          maxNs / 1_000_000,
          (sumNs / total) / 1_000_000,
          sumNs / 1_000_000);
    }
    return new LuceneRuntimeTypes.BatchUpdateResult(updated, notFound);
  }

  /**
   * Updates document paths after a file MOVE/RENAME operation. Rewrites the parent document with
   * updated DOC_ID, PATH, and FILENAME fields, then updates all chunk documents' PARENT_DOC_ID and
   * PATH fields. Reuses {@link #readModifyWrite} for each document.
   *
   * <p>The caller provides a leased searcher (point-in-time snapshot). All writes go to the
   * IndexWriter and are not visible through the searcher, which is safe: we read old state and
   * write new state in a single pass.
   *
   * @return number of documents updated (parent + chunks), or 0 if old path not found
   */
  int updateDocumentPaths(IndexSearcher searcher, String oldPath, String newPath)
      throws IOException {
    // 1. Update parent document: DOC_ID, PATH, FILENAME + re-queue for embedding/NER
    // Vector embeddings and NER status are non-stored fields that are lost during
    // readModifyWrite. Setting status to PENDING triggers backfill pipelines to re-process.
    String newFilename = java.nio.file.Paths.get(newPath).getFileName().toString();
    boolean parentUpdated =
        readModifyWrite(
            searcher,
            oldPath,
            Map.ofEntries(
                Map.entry(SchemaFields.DOC_ID, newPath),
                Map.entry(SchemaFields.PATH, newPath),
                Map.entry(SchemaFields.FILENAME, newFilename),
                Map.entry(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING),
                Map.entry(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING),
                Map.entry(SchemaFields.EMBEDDING_RETRY_COUNT, "0"),
                Map.entry(SchemaFields.NER_RETRY_COUNT, "0")));
    if (!parentUpdated) {
      log.debug("updateDocumentPaths: parent document not found: {}", oldPath);
      return 0;
    }

    // 2. Find all chunks for the old parent path
    BooleanQuery chunkQuery =
        new BooleanQuery.Builder()
            .add(
                new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, oldPath)),
                BooleanClause.Occur.FILTER)
            .add(
                new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.FILTER)
            .build();
    var chunkDocs = searcher.search(chunkQuery, 10_000);

    // 3. Update each chunk's PARENT_DOC_ID and PATH (chunk DOC_ID stays as UUID)
    // Re-queue chunks for embedding since vector data is lost during readModifyWrite
    int count = 1; // parent
    for (var sd : chunkDocs.scoreDocs) {
      String chunkId = searcher.storedFields().document(sd.doc).get(SchemaFields.DOC_ID);
      readModifyWrite(
          searcher,
          chunkId,
          Map.of(
              SchemaFields.PARENT_DOC_ID, newPath,
              SchemaFields.PATH, newPath,
              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
              SchemaFields.EMBEDDING_RETRY_COUNT, "0"));
      count++;
    }

    log.info(
        "updateDocumentPaths: {} -> {} ({} parent + {} chunks)",
        oldPath,
        newPath,
        1,
        chunkDocs.scoreDocs.length);
    return count;
  }

  private void guardWritable() {
    // Tempdoc 406 Phase 4b (A1): replaced state-machine check with snapshot/writer
    // null check. WritePathOps is only reachable through RunningRuntime, which by
    // construction has an open writer. Snapshot null means the runtime was closed.
    // Tempdoc 406 Gap G: also reject writes during drain — caller should retry on the
    // upgraded holder reference (UNAVAILABLE on the gRPC layer).
    if (session.draining) {
      throw new IllegalStateException(
          "Runtime is draining; retry on the new instance via the supplier holder");
    }
    LifecycleSnapshot snap = session.snapshot;
    if (snap == null || snap.writer() == null) {
      throw new IllegalStateException("Index is not writable (writer unavailable)");
    }
  }

  // Tempdoc 402 Phase D: these mutating methods are package-private. All external callers
  // write through IndexingCoordinator (single-writer invariant). The coordinator lives in the
  // same package and dispatches here under its dispatchLock, which is how the concurrent-RMW
  // race fix (393 § 1.4) is enforced.

  /**
   * Update a document with optional SPLADE preservation. When {@code preserveSplade} is true, the
   * guard that resets SPLADE_STATUS to PENDING is skipped — use for non-content writes (retry
   * counters, lifecycle status) where existing SPLADE vectors should be preserved.
   */
  boolean updateDocument(String docId, Map<String, Object> updates, boolean preserveSplade) {
    guardWritable();
    if (docId == null || docId.isBlank() || updates == null || updates.isEmpty()) {
      return false;
    }
    try {
      LifecycleSnapshot refreshSnap = session.snapshot;
      if (refreshSnap != null && refreshSnap.searcherManager() != null) {
        refreshSnap.searcherManager().maybeRefreshBlocking();
      }
      return bridge.withSearcher(
          searcher -> readModifyWrite(searcher, docId, updates, preserveSplade));
    } catch (IOException e) {
      log.error("Failed to update document {}", docId, e);
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to update document", e);
    }
  }

  /**
   * Batch update with optional SPLADE preservation. See {@link #updateDocument(String, Map,
   * boolean)}.
   */
  LuceneRuntimeTypes.BatchUpdateResult updateDocumentsBatch(
      List<Map.Entry<String, Map<String, Object>>> batchUpdates, boolean preserveSplade) {
    guardWritable();
    if (batchUpdates == null || batchUpdates.isEmpty()) {
      return new LuceneRuntimeTypes.BatchUpdateResult(0, 0);
    }
    try {
      long tRefreshStart = System.nanoTime();
      LifecycleSnapshot refreshSnap = session.snapshot;
      if (refreshSnap != null && refreshSnap.searcherManager() != null) {
        refreshSnap.searcherManager().maybeRefreshBlocking();
      }
      long tRefreshEnd = System.nanoTime();
      var result =
          bridge.withSearcher(
              searcher -> readModifyWriteBatch(searcher, batchUpdates, preserveSplade));
      long tWriteEnd = System.nanoTime();
      log.info(
          "updateDocumentsBatch: refresh={}ms, withSearcher+RMW={}ms, total={}ms",
          (tRefreshEnd - tRefreshStart) / 1_000_000,
          (tWriteEnd - tRefreshEnd) / 1_000_000,
          (tWriteEnd - tRefreshStart) / 1_000_000);
      return result;
    } catch (IOException e) {
      log.error("Failed to batch-update {} documents", batchUpdates.size(), e);
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to batch update documents", e);
    }
  }

  int updateDocumentPaths(String oldPath, String newPath) {
    guardWritable();
    if (oldPath == null || oldPath.isBlank() || newPath == null || newPath.isBlank()) {
      return 0;
    }
    try {
      LifecycleSnapshot refreshSnap = session.snapshot;
      if (refreshSnap != null && refreshSnap.searcherManager() != null) {
        refreshSnap.searcherManager().maybeRefreshBlocking();
      }
      return bridge.withSearcher(searcher -> updateDocumentPaths(searcher, oldPath, newPath));
    } catch (IOException e) {
      log.error("Failed to update document paths {} -> {}", oldPath, newPath, e);
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to update document paths", e);
    }
  }

  /** Deletes all documents from the index. Used by profiling reset. */
  void deleteAll() {
    try {
      LifecycleSnapshot snap = session.snapshot;
      IndexWriter w = snap != null ? snap.writer() : null;
      if (w == null) {
        throw new IllegalStateException("IndexWriter not available (runtime not started or closed)");
      }
      w.deleteAll();
      log.info("deleteAll: all documents deleted from index");
    } catch (IOException e) {
      throw new IndexRuntimeIOException(
          classifyIOException(e), "Failed to delete all documents", e);
    }
  }

  /**
   * Reads a keyword-field doc-value for the given global doc ID, returning {@code null} if the
   * field has no DocValues in this segment or the value isn't set for this doc. Used by
   * {@link #readModifyWrite} to restore non-stored status fields that
   * {@code storedFields().document()} cannot return.
   */
  private static String readKeywordDocValue(
      IndexSearcher searcher, int globalDocId, String field) throws IOException {
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    int leafIndex = ReaderUtil.subIndex(globalDocId, leaves);
    LeafReaderContext leaf = leaves.get(leafIndex);
    int docInLeaf = globalDocId - leaf.docBase;
    try {
      SortedDocValues dv = DocValues.getSorted(leaf.reader(), field);
      if (dv != null && dv.advanceExact(docInLeaf)) {
        return dv.lookupOrd(dv.ordValue()).utf8ToString();
      }
    } catch (IllegalStateException e) {
      // Common case: field has no SortedDocValues in this segment (not indexed / sparse) — return
      // null. Rare case: reader-state corruption, schema drift, or concurrent segment merge. We
      // return null either way so the caller falls back to the stored-field path; the debug log
      // lets future investigators distinguish the rare causes from the common one (tempdoc 393 § 2.1).
      log.debug("docValues read failed for field={} doc={}: {}", field, globalDocId, e.getMessage());
    }
    return null;
  }

  /**
   * Reads a long-typed NumericDocValues for the given global doc ID, returning {@code null} if the
   * field has no NumericDocValues in this segment or the value isn't set for this doc.
   */
  private static Long readNumericDocValue(
      IndexSearcher searcher, int globalDocId, String field) throws IOException {
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    int leafIndex = ReaderUtil.subIndex(globalDocId, leaves);
    LeafReaderContext leaf = leaves.get(leafIndex);
    int docInLeaf = globalDocId - leaf.docBase;
    try {
      NumericDocValues dv = DocValues.getNumeric(leaf.reader(), field);
      if (dv != null && dv.advanceExact(docInLeaf)) {
        return dv.longValue();
      }
    } catch (IllegalStateException e) {
      // See readKeywordDocValue — same rationale for null-return + debug log (tempdoc 393 § 2.1).
      log.debug("docValues read failed for field={} doc={}: {}", field, globalDocId, e.getMessage());
    }
    return null;
  }

  /** Pre-classified document for batch operations. */
  record DocWork(String id, Document doc, Map<String, Object> fields) {}
}
