/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal point-lookup collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates document field retrieval by ID: content, field values, existence checks,
 * and ID-based queries. All methods that need up-to-date data call {@code refreshBeforeFetch}
 * to ensure write-after-read visibility.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code
 * close()}. Access from the runtime must go through a volatile snapshot to ensure visibility
 * across threads.
 */
public final class DocumentFieldOps {
  private static final Logger log = LoggerFactory.getLogger(DocumentFieldOps.class);

  private final SearcherBridge bridge;
  private final String idField;
  private final RuntimeSession session;
  private final ReadPathOps readPathOps;

  DocumentFieldOps(
      RuntimeSession session,
      SearcherBridge bridge,
      String idField,
      ReadPathOps readPathOps) {
    this.session = session;
    this.bridge = bridge;
    this.idField = idField;
    this.readPathOps = readPathOps;
  }

  /**
   * Forces a refresh when a commit happened after the last visible refresh.
   *
   * <p>This avoids surprising read-after-write behavior for "fetch by id" style APIs
   * that expect committed documents to be immediately visible.
   */
  private void maybeRefreshBlockingIfCommittedSinceRefresh() {
    LifecycleSnapshot snap = session.snapshot;
    org.apache.lucene.search.SearcherManager mgr =
        snap != null ? snap.searcherManager() : null;
    if (mgr == null) return;
    long commit = session.lastCommitNanos.get();
    long refreshed = session.lastRefreshNanos.get();
    if (commit <= 0L || commit <= refreshed) return;
    try {
      mgr.maybeRefreshBlocking();
    } catch (IOException e) {
      log.debug("maybeRefreshBlockingIfCommittedSinceRefresh failed: {}", e.getMessage());
    }
  }

  /**
   * Fetches the content of a document by ID.
   *
   * <p>Calls {@code refreshBeforeFetch} to ensure write-after-read visibility.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public String getDocumentContent(String docId) {
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        Query query = new TermQuery(new Term(idField, docId));
        var topDocs = searcher.search(query, 1);

        if (topDocs.scoreDocs.length == 0) {
          return null;
        }

        int docNum = topDocs.scoreDocs[0].doc;
        Set<String> storedAllowlist = Set.of(SchemaFields.CONTENT);
        Map<String, String> fields =
            SearchResultFormatter.extractFromStoredFields(
                searcher.storedFields(), docNum, true, storedAllowlist);
        return fields.get(SchemaFields.CONTENT);
      });
    } catch (IOException e) {
      log.debug("Failed to get content for {}: {}", docId, e.getMessage());
      return null;
    }
  }

  /**
   * Fetches content for a batch of document IDs using a single shared searcher.
   *
   * <p>Acquires ONE searcher, resolves all doc IDs, then iterates in ascending Lucene
   * internal doc-ID order for LZ4 block cache locality. Doc IDs not found in the index
   * are silently omitted from the result map (stale IDs from the pending query).
   *
   * <p>Calls {@code refreshBeforeFetch} to ensure write-after-read visibility.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public Map<String, String> getDocumentContentBatch(List<String> docIds) {
    if (docIds == null || docIds.isEmpty()) {
      return Map.of();
    }
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        // Phase 1: Resolve all doc IDs to Lucene internal doc numbers.
        // Store as (luceneDocNum -> externalDocId) entries for sorted iteration.
        List<Map.Entry<Integer, String>> resolved = new ArrayList<>(docIds.size());
        for (String docId : docIds) {
          Query query = new TermQuery(new Term(idField, docId));
          var topDocs = searcher.search(query, 1);
          if (topDocs.scoreDocs.length > 0) {
            resolved.add(Map.entry(topDocs.scoreDocs[0].doc, docId));
          }
        }

        if (resolved.isEmpty()) {
          return Map.of();
        }

        // Phase 2: Sort by ascending Lucene doc number for sequential stored-field
        // access (LZ4 block cache locality).
        resolved.sort(Map.Entry.comparingByKey());

        // Phase 3: Extract content in sorted order.
        Set<String> storedAllowlist = Set.of(SchemaFields.CONTENT);
        org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();
        Map<String, String> result = new LinkedHashMap<>(resolved.size());
        for (Map.Entry<Integer, String> entry : resolved) {
          Map<String, String> fields =
              SearchResultFormatter.extractFromStoredFields(
                  storedFields, entry.getKey(), true, storedAllowlist);
          String content = fields.get(SchemaFields.CONTENT);
          if (content != null) {
            result.put(entry.getValue(), content);
          }
        }
        return Collections.unmodifiableMap(result);
      });
    } catch (IOException e) {
      log.debug("Failed to batch-fetch content for {} docs: {}", docIds.size(), e.getMessage());
      return Map.of();
    }
  }

  /**
   * Fetches multiple field values for a batch of document IDs using a single shared searcher.
   *
   * <p>Same 3-phase pattern as {@link #getDocumentContentBatch}: acquires ONE searcher,
   * resolves all doc IDs, then reads fields in ascending doc-number order. For DocValues-backed
   * fields (status fields), reads are O(1) per field via {@code projectDocValues}. Stored
   * fields (e.g., chunk_content) go through {@code extractFromStoredFields}.
   *
   * <p>Reduces 300+ individual {@code getDocumentField} calls to 1 searcher acquisition +
   * N TermQuery resolutions + batch DocValues/stored reads. ~8-10s savings per enrichment
   * pipeline (tempdoc 334 Phase 10).
   */
  public Map<String, Map<String, String>> getDocumentFieldsBatch(
      List<String> docIds, Set<String> fieldNames) {
    if (docIds == null || docIds.isEmpty() || fieldNames == null || fieldNames.isEmpty()) {
      return Map.of();
    }
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        // Phase 1: Resolve all doc IDs to Lucene internal doc numbers.
        List<Map.Entry<Integer, String>> resolved = new ArrayList<>(docIds.size());
        for (String docId : docIds) {
          var topDocs = searcher.search(new TermQuery(new Term(idField, docId)), 1);
          if (topDocs.scoreDocs.length > 0) {
            resolved.add(Map.entry(topDocs.scoreDocs[0].doc, docId));
          }
        }
        if (resolved.isEmpty()) {
          return Map.of();
        }

        // Phase 2: Sort by ascending Lucene doc number for cache locality.
        resolved.sort(Map.Entry.comparingByKey());

        // Phase 3: Separate fields into DocValues vs stored, read in doc order.
        Set<String> dvFieldIds = new java.util.HashSet<>();
        Set<String> storedFieldNames = new java.util.HashSet<>();
        for (String fn : fieldNames) {
          FieldMapper.FieldDef def = session.fieldMapper.fieldDef(fn);
          if (def != null && def.docValues) {
            dvFieldIds.add(def.id);
          } else {
            storedFieldNames.add(fn);
          }
        }

        boolean hasStored = !storedFieldNames.isEmpty();
        org.apache.lucene.index.StoredFields storedFields =
            hasStored ? searcher.storedFields() : null;

        Map<String, Map<String, String>> result = new LinkedHashMap<>(resolved.size());
        for (Map.Entry<Integer, String> entry : resolved) {
          int docNum = entry.getKey();
          String docId = entry.getValue();
          Map<String, String> values = new HashMap<>(fieldNames.size());

          if (!dvFieldIds.isEmpty()) {
            readPathOps.projectDocValues(searcher, docNum, dvFieldIds, values);
          }
          if (hasStored) {
            boolean includeContent = storedFieldNames.contains(SchemaFields.CONTENT)
                || storedFieldNames.contains(SchemaFields.CHUNK_CONTENT);
            Map<String, String> stored =
                SearchResultFormatter.extractFromStoredFields(
                    storedFields, docNum, includeContent, storedFieldNames);
            values.putAll(stored);
          }

          result.put(docId, values);
        }
        return Collections.unmodifiableMap(result);
      });
    } catch (IOException e) {
      log.debug(
          "Failed to batch-fetch fields {} for {} docs: {}",
          fieldNames, docIds.size(), e.getMessage());
      return Map.of();
    }
  }

  /**
   * Fetches a specific field value from a document by ID.
   *
   * <p>Prefers DocValues for DocValues-backed fields, falls back to stored fields.
   * Calls {@code refreshBeforeFetch} to ensure write-after-read visibility.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public String getDocumentField(String docId, String fieldName) {
    if (docId == null || fieldName == null) {
      return null;
    }
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        Query query = new TermQuery(new Term(idField, docId));
        var topDocs = searcher.search(query, 1);

        if (topDocs.scoreDocs.length == 0) {
          return null;
        }

        int docNum = topDocs.scoreDocs[0].doc;

        // Prefer DocValues for DocValues-backed fields (e.g., mime/language/size_bytes).
        FieldMapper.FieldDef def = session.fieldMapper.fieldDef(fieldName);
        if (def != null && def.docValues) {
          Map<String, String> projected = new HashMap<>();
          readPathOps.projectDocValues(searcher, docNum, Set.of(def.id), projected);
          String value = projected.get(def.id);
          if (value != null) {
            return value;
          }
        }

        // Fallback to stored fields.
        boolean includeContent = SchemaFields.CONTENT.equals(fieldName);
        Set<String> allow = Set.of(fieldName);
        Map<String, String> stored =
            SearchResultFormatter.extractFromStoredFields(
                searcher.storedFields(), docNum, includeContent, allow);
        return stored.get(fieldName);
      });
    } catch (IOException e) {
      log.debug("Failed to get field {} for {}: {}", fieldName, docId, e.getMessage());
      return null;
    }
  }

  /**
   * Fetches individual values of a multi-valued field from a document by ID.
   *
   * <p>Returns each value separately (no comma-join). Falls back to {@link #getDocumentField}
   * wrapped in a singleton list for non-multi-valued fields.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public List<String> getDocumentFieldValues(String docId, String fieldName) {
    if (docId == null || fieldName == null) {
      return List.of();
    }
    try {
      maybeRefreshBlockingIfCommittedSinceRefresh();
      return bridge.withSearcher(searcher -> {
        Query query = new TermQuery(new Term(idField, docId));
        var topDocs = searcher.search(query, 1);

        if (topDocs.scoreDocs.length == 0) {
          return List.of();
        }

        int docNum = topDocs.scoreDocs[0].doc;

        FieldMapper.FieldDef def = session.fieldMapper.fieldDef(fieldName);
        if (def != null && def.docValues && def.multiValued) {
          List<String> values = readPathOps.projectMultiValuedDocValues(searcher, docNum, def.id);
          if (!values.isEmpty()) {
            return values;
          }
        }

        // Fallback: single-valued path (inline to avoid double searcher acquisition)
        FieldMapper.FieldDef singleDef = session.fieldMapper.fieldDef(fieldName);
        if (singleDef != null && singleDef.docValues) {
          Map<String, String> projected = new HashMap<>();
          readPathOps.projectDocValues(searcher, docNum, Set.of(singleDef.id), projected);
          String value = projected.get(singleDef.id);
          if (value != null) {
            return List.of(value);
          }
        }
        boolean includeContent = SchemaFields.CONTENT.equals(fieldName);
        Set<String> allow = Set.of(fieldName);
        Map<String, String> stored =
            SearchResultFormatter.extractFromStoredFields(
                searcher.storedFields(), docNum, includeContent, allow);
        String sv = stored.get(fieldName);
        return sv != null ? List.of(sv) : List.of();
      });
    } catch (IOException e) {
      log.debug("Failed to get field values {} for {}: {}", fieldName, docId, e.getMessage());
      return List.of();
    }
  }

  /**
   * Checks if a document exists and has the same lastModified timestamp.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public boolean isUnmodified(String docId, long currentLastModified) {
    // No blocking refresh here — false negatives (re-indexing unmodified docs) are harmless.
    // The CRTRT thread refreshes every 500ms, bounding staleness. Blocking refresh on every
    // point lookup causes "refresh storms" (the same anti-pattern Elasticsearch abandoned in 5.0).
    try {
      return bridge.withSearcher(
          searcher -> {
            org.apache.lucene.search.TopDocs docs =
                searcher.search(new TermQuery(new Term(idField, docId)), 1);
            if (docs.scoreDocs.length == 0) {
              log.trace("isUnmodified: not indexed yet: {}", docId);
              return false;
            }

            int docNum = docs.scoreDocs[0].doc;
            long modifiedAt =
                readPathOps.readLongDocValueOrStoredLong(searcher, docNum, SchemaFields.MODIFIED_AT);
            if (modifiedAt < 0) {
              log.trace("isUnmodified: no modified_at field (legacy doc): {}", docId);
              return false;
            }
            boolean unchanged = modifiedAt == currentLastModified;
            if (unchanged) {
              log.trace(
                  "isUnmodified: unchanged (stored={}, current={}): {}",
                  modifiedAt,
                  currentLastModified,
                  docId);
            }
            return unchanged;
          });
    } catch (IOException e) {
      log.debug("Failed to check if modified: {}", docId, e);
      return false;
    }
  }

  /**
   * Queries document IDs matching a specific field value.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public List<String> queryDocIdsByField(String field, String value, int limit) {
    if (field == null || value == null || limit <= 0) {
      return List.of();
    }
    try {
      return bridge.withSearcher(searcher -> {
        Query query = new TermQuery(new Term(field, value));
        var topDocs = searcher.search(query, limit);

        List<String> docIds = new ArrayList<>(topDocs.scoreDocs.length);
        org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();
        Set<String> storedAllowlist = Set.of(idField);
        for (var scoreDoc : topDocs.scoreDocs) {
          Map<String, String> fields =
              SearchResultFormatter.extractFromStoredFields(
                  storedFields, scoreDoc.doc, false, storedAllowlist);
          String docId = fields.get(idField);
          if (docId != null && !docId.isBlank()) {
            docIds.add(docId);
          }
        }
        return docIds;
      });
    } catch (IOException e) {
      log.debug("Failed to query {}={}: {}", field, value, e.getMessage());
      return List.of();
    }
  }
}
