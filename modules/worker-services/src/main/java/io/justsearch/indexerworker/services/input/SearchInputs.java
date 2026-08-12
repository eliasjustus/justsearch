/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.disambiguation.EntityClusterSnapshot;
import io.justsearch.ipc.SearchRequest;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregated request-time inputs captured by {@code SearchInputCapture} (tempdoc 517).
 *
 * <p>The planner is pure over this value. Every pre-retrieval IO call from the
 * legacy {@code SearchOrchestrator.execute(...)} body (lines 244, 248, 260, 263,
 * 288, 307, 317, 350, 360, 365 — see §A.1) writes into a field on this record.
 *
 * <p>Carries the raw {@link SearchRequest} unchanged — the planner reads query
 * text, limit, pipeline config, etc. directly from the proto. The captured
 * fields hold the environmental state the planner cannot probe directly.
 */
public record SearchInputs(
    SearchRequest request,
    LuceneRuntimeTypes.RuntimeSearchFilters runtimeFilters,
    EntityClusterSnapshot clusterSnapshot,
    CorpusCapabilities corpus,
    EncodingResults encoding,
    QppMetrics qpp,
    EmbeddingCompatBoundary compatBoundary,
    Map<String, String> commitMetadata,
    String activeGeneration) {

  public SearchInputs {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(runtimeFilters, "runtimeFilters");
    // clusterSnapshot may be null (when the supplier has not been wired yet)
    Objects.requireNonNull(corpus, "corpus");
    Objects.requireNonNull(encoding, "encoding");
    Objects.requireNonNull(qpp, "qpp");
    Objects.requireNonNull(compatBoundary, "compatBoundary");
    // commitMetadata may be empty but not null
    Objects.requireNonNull(commitMetadata, "commitMetadata");
    // activeGeneration may be null
  }

  /**
   * The request's query syntax in runtime terms — the single projection of the wire
   * {@code SearchQuerySyntax} enum onto {@link LuceneRuntimeTypes.QuerySyntax}.
   *
   * <p>This is what the CLIENT asked for. It is the right source for a stage that parses the user's
   * query for retrieval (the sparse-only shortcut does), and the WRONG source for a stage that
   * re-derives counts over an already-retrieved population — those must mirror the parse their leg
   * used ({@code TextQueryOps.MULTI_LEG_LEXICAL_SYNTAX}), which the multi-leg legs do not derive
   * from here (tempdoc 821 §L.3).
   */
  public LuceneRuntimeTypes.QuerySyntax runtimeSyntax() {
    return request.getQuerySyntax() == io.justsearch.ipc.SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE
        ? LuceneRuntimeTypes.QuerySyntax.LUCENE
        : LuceneRuntimeTypes.QuerySyntax.SIMPLE;
  }
}
