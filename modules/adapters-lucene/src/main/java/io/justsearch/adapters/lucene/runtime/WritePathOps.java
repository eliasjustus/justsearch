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
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.KnnVectorValues;
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

  // Shared lifecycle-status vocabulary driving the reset-status RMW lane (tempdoc 711). Every
  // *_status field in the catalog uses this same COMPLETED/PENDING token set.
  private static final String STATUS_COMPLETED = "COMPLETED";
  private static final String STATUS_PENDING = "PENDING";

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
   * Read-modify-write: loads existing stored fields, applies the caller's updates, and re-indexes.
   * Non-stored, non-docValues data-bearing fields the caller omits (vectors, SPLADE) are preserved
   * per their declared {@code rmwPolicy} in the field catalog (tempdoc 711) — a subset-field RMW can
   * no longer silently destroy the rest. The caller provides a leased searcher (via
   * ReadPathOps.withSearcher) so it stays valid for the read and the subsequent write.
   *
   * @return true if the document was found and updated, false if not found
   */
  boolean readModifyWrite(IndexSearcher searcher, String docId, Map<String, Object> updates)
      throws IOException {
    var topDocs = searcher.search(new TermQuery(new Term(idField, docId)), 1);
    if (topDocs.scoreDocs.length == 0) {
      log.debug("readModifyWrite: document not found: {}", docId);
      return false;
    }
    int globalDocId = topDocs.scoreDocs[0].doc;

    // Load all stored fields from existing document, accumulating multi-valued fields into Lists
    Document oldDoc = searcher.storedFields().document(globalDocId);
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

    // RMW preservation engine (tempdoc 711): stored-field reconstruction above cannot see
    // non-stored, non-docValues data-bearing fields (KnnFloatVectorField vectors, SPLADE
    // FeatureFields). For each catalog field that declares an rmwPolicy and is absent from the
    // caller's update map, apply its declared disposition so the rewrite preserves it structurally
    // instead of relying on per-call-site discipline.
    applyRmwPolicies(searcher, globalDocId, updates, fields);

    // Apply updates (overwrites existing values, including anything the engine restored)
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
   * Applies each catalog field's declared RMW disposition (tempdoc 711) for fields the caller
   * omitted: {@link FieldMapper#RMW_PRESERVE_REREAD} re-reads and carries the field forward;
   * {@code reset-status:<statusField>} drives the named status field so a backfill re-derives data
   * that cannot be cheaply re-read.
   */
  private void applyRmwPolicies(
      IndexSearcher searcher, int globalDocId, Map<String, Object> updates, Map<String, Object> fields)
      throws IOException {
    for (FieldMapper.FieldDef def : session.fieldMapper.rmwPolicyFields()) {
      if (updates.containsKey(def.id)) {
        continue; // caller supplied the field itself — its own value is authoritative
      }
      String policy = def.rmwPolicy;
      if (FieldMapper.RMW_PRESERVE_REREAD.equals(policy)) {
        float[] existing = readFloatVector(searcher, globalDocId, def.id);
        if (existing != null) {
          fields.put(def.id, existing);
        }
        // null => the doc has no vector for this field (common mid-ingest) — nothing to preserve.
      } else if (policy != null && policy.startsWith(FieldMapper.RMW_RESET_STATUS_PREFIX)) {
        applyResetStatus(
            searcher,
            globalDocId,
            policy.substring(FieldMapper.RMW_RESET_STATUS_PREFIX.length()),
            updates,
            fields);
      }
    }
  }

  /**
   * reset-status lane: the field's data cannot be cheaply re-read (SPLADE weights live in postings),
   * so on drop we drive its declared status field. A COMPLETED status is downgraded to PENDING (the
   * data was just dropped; a backfill must re-derive it) and its retry counter reset; a missing
   * status is healed to PENDING; a non-terminal / FAILED status is preserved as-is (resurrecting
   * FAILED as PENDING would mask real failures). A caller-supplied status always wins.
   */
  private void applyResetStatus(
      IndexSearcher searcher,
      int globalDocId,
      String statusField,
      Map<String, Object> updates,
      Map<String, Object> fields)
      throws IOException {
    if (updates.containsKey(statusField)) {
      return; // caller-supplied status always wins
    }
    String retryField = deriveRetryField(statusField);
    boolean callerSuppliedRetry = retryField != null && updates.containsKey(retryField);
    String existingStatus = readKeywordDocValue(searcher, globalDocId, statusField);
    if (existingStatus == null || STATUS_COMPLETED.equals(existingStatus)) {
      // Data just dropped (or the doc never carried a status) — force (re-)derivation.
      fields.put(statusField, STATUS_PENDING);
      if (retryField != null && !callerSuppliedRetry) {
        fields.put(retryField, "0");
      }
    } else {
      // Preserve a non-terminal / FAILED status; don't resurrect FAILED as PENDING.
      fields.put(statusField, existingStatus);
      if (retryField != null && !callerSuppliedRetry) {
        Long existingRetry = readNumericDocValue(searcher, globalDocId, retryField);
        if (existingRetry != null) {
          fields.put(retryField, Long.toString(existingRetry));
        }
      }
    }
  }

  /**
   * Derives the retry-counter field paired with a status field by the {@code <prefix>_status} ->
   * {@code <prefix>_retry_count} convention, or null if the catalog has no such field.
   */
  private String deriveRetryField(String statusField) {
    if (!statusField.endsWith("_status")) {
      return null;
    }
    String candidate =
        statusField.substring(0, statusField.length() - "_status".length()) + "_retry_count";
    return session.fieldMapper.fieldDef(candidate) != null ? candidate : null;
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
   * @return result with counts of updated and not-found documents
   * @throws IOException if any IndexWriter write fails (batch is partially applied)
   */
  LuceneRuntimeTypes.BatchUpdateResult readModifyWriteBatch(
      IndexSearcher searcher, List<Map.Entry<String, Map<String, Object>>> batchUpdates)
      throws IOException {
    int updated = 0;
    int notFound = 0;
    long minNs = Long.MAX_VALUE, maxNs = 0, sumNs = 0;
    for (Map.Entry<String, Map<String, Object>> entry : batchUpdates) {
      long t0 = System.nanoTime();
      if (readModifyWrite(searcher, entry.getKey(), entry.getValue())) {
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
    // 1. Update parent document: DOC_ID, PATH, FILENAME. A MOVE/RENAME does not change content, so
    // the vector, SPLADE, and NER enrichment stay valid; the RMW preservation engine (tempdoc 711)
    // carries the vector forward and the stored NER fields survive — no re-queue needed.
    // OS-independent: newPath may carry Windows (\) or POSIX (/) separators regardless of the
    // host OS. Paths.get() would treat a Windows path as a single segment on Linux (tempdoc 668;
    // same pattern as LuceneRuntimeUtils).
    int lastSep = Math.max(newPath.lastIndexOf('/'), newPath.lastIndexOf('\\'));
    String newFilename = lastSep >= 0 ? newPath.substring(lastSep + 1) : newPath;
    boolean parentUpdated =
        readModifyWrite(
            searcher,
            oldPath,
            Map.ofEntries(
                Map.entry(SchemaFields.DOC_ID, newPath),
                Map.entry(SchemaFields.PATH, newPath),
                Map.entry(SchemaFields.FILENAME, newFilename)));
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

    // 3. Update each chunk's PARENT_DOC_ID and PATH (chunk DOC_ID stays as UUID). The chunk_vector
    // is carried forward by the RMW preservation engine (tempdoc 711); no re-embed re-queue needed.
    int count = 1; // parent
    for (var sd : chunkDocs.scoreDocs) {
      String chunkId = searcher.storedFields().document(sd.doc).get(SchemaFields.DOC_ID);
      readModifyWrite(
          searcher,
          chunkId,
          Map.of(
              SchemaFields.PARENT_DOC_ID, newPath,
              SchemaFields.PATH, newPath));
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
   * Update a document via read-modify-write. Non-stored data-bearing fields the caller omits are
   * preserved per their catalog {@code rmwPolicy} (tempdoc 711).
   */
  boolean updateDocument(String docId, Map<String, Object> updates) {
    guardWritable();
    if (docId == null || docId.isBlank() || updates == null || updates.isEmpty()) {
      return false;
    }
    try {
      LifecycleSnapshot refreshSnap = session.snapshot;
      if (refreshSnap != null && refreshSnap.searcherManager() != null) {
        refreshSnap.searcherManager().maybeRefreshBlocking();
      }
      return bridge.withSearcher(searcher -> readModifyWrite(searcher, docId, updates));
    } catch (IOException e) {
      log.error("Failed to update document {}", docId, e);
      throw new IndexRuntimeIOException(classifyIOException(e), "Failed to update document", e);
    }
  }

  /** Batch update via read-modify-write. See {@link #updateDocument(String, Map)}. */
  LuceneRuntimeTypes.BatchUpdateResult updateDocumentsBatch(
      List<Map.Entry<String, Map<String, Object>>> batchUpdates) {
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
      var result = bridge.withSearcher(searcher -> readModifyWriteBatch(searcher, batchUpdates));
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

  /**
   * Re-reads a doc's stored float vector for {@code field} at the given global doc ID, returning a
   * defensive copy (Lucene may reuse the backing buffer), or {@code null} if the doc has no vector
   * for this field. Lucene 10.4 exposes ordinal-based read-back: resolve the leaf via docBase, then
   * {@code getFloatVectorValues(field)} -> iterator advance -> {@code vectorValue(ord)} (derisk E1,
   * tempdoc 711). Used by the {@code preserve-reread} RMW lane.
   */
  private static float[] readFloatVector(IndexSearcher searcher, int globalDocId, String field)
      throws IOException {
    List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    int leafIndex = ReaderUtil.subIndex(globalDocId, leaves);
    LeafReaderContext leaf = leaves.get(leafIndex);
    int docInLeaf = globalDocId - leaf.docBase;
    FloatVectorValues values = leaf.reader().getFloatVectorValues(field);
    if (values == null) {
      return null; // no float-vector field in this segment (doc indexed without a vector)
    }
    KnnVectorValues.DocIndexIterator iter = values.iterator();
    if (iter.advance(docInLeaf) != docInLeaf) {
      return null; // this doc has no vector for the field
    }
    float[] v = values.vectorValue(iter.index());
    return v == null ? null : v.clone();
  }

  /** Pre-classified document for batch operations. */
  record DocWork(String id, Document doc, Map<String, Object> fields) {}
}
