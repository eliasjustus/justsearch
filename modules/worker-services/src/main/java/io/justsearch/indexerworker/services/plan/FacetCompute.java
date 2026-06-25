/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.plan;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import java.util.Map;
import java.util.Objects;

/**
 * Two facet construction paths, discriminated by the parent decision variant (tempdoc 517).
 *
 * <p>{@code FromRetrievalQuery} reuses the already-boosted Lucene query produced
 * for the sparse-only request path (legacy lines 502–519) — honours the request's
 * syntax (LUCENE or SIMPLE), propagates {@code ParseException}.
 *
 * <p>{@code FromFreshBm25} builds a fresh SIMPLE-syntax BM25 query, omitting boost
 * filters and swallowing {@code ParseException} (legacy lines 678–705).
 *
 * <p>These are not duplicates and cannot be unified — both observable behaviours
 * are part of the wire contract. The discriminator lives on the decision variant
 * so the wrong choice is a compile error, not a silent fall-through.
 */
public sealed interface FacetCompute
    permits FacetCompute.FromRetrievalQuery, FacetCompute.FromFreshBm25 {

  /** Reuse the boosted Lucene query that retrieval already built. Sparse-only path. */
  record FromRetrievalQuery(
      org.apache.lucene.search.Query luceneQuery, Map<String, Integer> fields, int maxDocsScanned)
      implements FacetCompute {
    public FromRetrievalQuery {
      Objects.requireNonNull(luceneQuery, "luceneQuery");
      Objects.requireNonNull(fields, "fields");
    }
  }

  /** Build a fresh SIMPLE BM25 query without boost filters. Composable / multi-leg path. */
  record FromFreshBm25(
      String queryString,
      LuceneRuntimeTypes.RuntimeSearchFilters filters,
      Map<String, Integer> fields,
      int maxDocsScanned)
      implements FacetCompute {
    public FromFreshBm25 {
      Objects.requireNonNull(queryString, "queryString");
      Objects.requireNonNull(filters, "filters");
      Objects.requireNonNull(fields, "fields");
    }
  }
}
