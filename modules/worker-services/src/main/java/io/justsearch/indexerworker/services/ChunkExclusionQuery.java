/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.ChunkRef;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * Tempdoc 610 §J.3 — composes a {@code MUST_NOT} exclusion over a set of {@code (parentDocId,
 * chunkIndex)} pairs with the existing chunk filter, so the Worker drops user-hidden retrieved sources
 * BEFORE ranking (over-retrieval then refills top-K from the remaining candidates and every downstream
 * signal reflects the filtered set — no desync). {@code PARENT_DOC_ID} is a keyword (TermQuery);
 * {@code CHUNK_INDEX} is a long (LongPoint exact).
 */
final class ChunkExclusionQuery {

  private ChunkExclusionQuery() {}

  /**
   * Returns {@code chunkFilter} unchanged when there is nothing to exclude; otherwise the existing
   * filter (or {@link MatchAllDocsQuery} when absent) combined with the {@code MUST_NOT} exclusion. The
   * positive base is required so the BooleanQuery is not a pure-MUST_NOT (which matches nothing) when
   * added as a FILTER clause downstream.
   */
  static Query compose(Query chunkFilter, List<ChunkRef> excludedChunks) {
    if (excludedChunks == null || excludedChunks.isEmpty()) {
      return chunkFilter;
    }
    BooleanQuery.Builder exclusion = new BooleanQuery.Builder();
    boolean any = false;
    for (ChunkRef ref : excludedChunks) {
      String parentDocId = ref.getParentDocId();
      if (parentDocId.isBlank()) {
        continue;
      }
      Query chunkIdentity =
          new BooleanQuery.Builder()
              .add(
                  new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, parentDocId)),
                  BooleanClause.Occur.MUST)
              .add(
                  LongPoint.newExactQuery(SchemaFields.CHUNK_INDEX, ref.getChunkIndex()),
                  BooleanClause.Occur.MUST)
              .build();
      exclusion.add(chunkIdentity, BooleanClause.Occur.SHOULD);
      any = true;
    }
    if (!any) {
      return chunkFilter;
    }
    Query base = chunkFilter != null ? chunkFilter : new MatchAllDocsQuery();
    return new BooleanQuery.Builder()
        .add(base, BooleanClause.Occur.FILTER)
        .add(exclusion.build(), BooleanClause.Occur.MUST_NOT)
        .build();
  }

  /**
   * Tempdoc 610 §J.3 — drop any parent doc that appears in {@code excludedChunks} from {@code docIds}.
   * Used to keep the WHOLE-DOCUMENT fallback (which can't honour chunk-level exclusion) from re-injecting
   * a doc whose retrievable chunks the user hid. Returns the input unchanged when there is nothing to drop.
   */
  static Set<String> dropExcludedParents(Set<String> docIds, List<ChunkRef> excludedChunks) {
    if (excludedChunks == null || excludedChunks.isEmpty() || docIds.isEmpty()) {
      return docIds;
    }
    Set<String> excludedParents = new LinkedHashSet<>();
    for (ChunkRef ref : excludedChunks) {
      if (!ref.getParentDocId().isBlank()) {
        excludedParents.add(ref.getParentDocId());
      }
    }
    if (excludedParents.isEmpty()) {
      return docIds;
    }
    Set<String> kept = new LinkedHashSet<>();
    for (String d : docIds) {
      if (!excludedParents.contains(d)) {
        kept.add(d);
      }
    }
    return kept;
  }
}
