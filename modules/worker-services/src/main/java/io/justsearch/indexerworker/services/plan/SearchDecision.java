/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.plan;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.services.SearchReasonCode;
import io.justsearch.indexerworker.services.input.SpladeEncoding;
import io.justsearch.indexerworker.services.input.VectorEncoding;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Complete search decision as a single immutable value (tempdoc 517).
 *
 * <p>Produced once by {@code SearchPlanner.plan(SearchInputs)} before any
 * retrieval IO. The executor pattern-matches on this value via a {@code switch}
 * — the dispatch is exhaustive at compile time and is the canonical
 * data-plus-dispatch shape established by {@code IndexWriteOperation} (canonical
 * reference: {@code IndexingCoordinator.java:100–111}).
 *
 * <p>The {@link #summary()} method is the single permitted observability
 * primitive — privacy-safe, never carries query text or filter values — and is
 * recorded as span attributes by {@code EffectScope}. No other behaviour-
 * carrying methods are added; variants are otherwise data-only.
 */
public sealed interface SearchDecision
    permits SearchDecision.EmptyQueryDecision,
        SearchDecision.BlockedDecision,
        SearchDecision.SparseShortcut,
        SearchDecision.MultiLegDecision {

  /** Privacy-safe diagnostic key set for {@code EffectScope} span attributes + debug logs. */
  Map<String, Object> summary();

  /** No retrieval performed; returns 0 hits. Examples: blank query against TEXT mode. */
  record EmptyQueryDecision(int limit, String effectiveModeLabel) implements SearchDecision {
    @Override
    public Map<String, Object> summary() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("decision_kind", "empty_query");
      m.put("effective_mode", effectiveModeLabel);
      return Map.copyOf(m);
    }
  }

  /**
   * Vector-only request blocked by encoding failure or compat gate.
   *
   * @param encodingFailure carries the wire reason code (one of the 5 routing
   *     codes or the 8 compat codes mapped via
   *     {@link io.justsearch.indexerworker.services.input.EmbeddingCompatBoundary})
   * @param effectiveModeLabel typically {@code "VECTOR"}
   */
  record BlockedDecision(VectorEncoding.Failed encodingFailure, String effectiveModeLabel)
      implements SearchDecision {
    public BlockedDecision {
      Objects.requireNonNull(encodingFailure, "encodingFailure");
    }

    @Override
    public Map<String, Object> summary() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("decision_kind", "blocked");
      m.put("vector_blocked_reason", encodingFailure.reason().name());
      m.put("effective_mode", effectiveModeLabel);
      return Map.copyOf(m);
    }
  }

  /**
   * Sparse-only <i>request</i> path (legacy lines 408–521).
   *
   * <p>This variant carries the per-term correction policy and the
   * {@link FacetCompute.FromRetrievalQuery} variant. It is <b>not</b> the same
   * as {@code MultiLegDecision(LegSet.Bm25Only)} (which is reached only via
   * hybrid degradation and uses {@link FacetCompute.FromFreshBm25}).
   */
  record SparseShortcut(
      LuceneRuntimeTypes.QuerySyntax runtimeSyntax,
      int retrievalLimit,
      boolean correctionRetryEnabled,
      Optional<FacetCompute.FromRetrievalQuery> facets,
      ChunkMergeDirective chunkMerge)
      implements SearchDecision {
    public SparseShortcut {
      Objects.requireNonNull(runtimeSyntax, "runtimeSyntax");
      Objects.requireNonNull(facets, "facets");
      Objects.requireNonNull(chunkMerge, "chunkMerge");
    }

    @Override
    public Map<String, Object> summary() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("decision_kind", "sparse_shortcut");
      m.put("effective_mode", "TEXT");
      m.put("correction_eligible", correctionRetryEnabled);
      m.put("facet_source", facets.isPresent() ? "from_retrieval_query" : "absent");
      m.put("chunk_merge_kind", chunkMerge.kind());
      if (chunkMerge instanceof ChunkMergeDirective.Skip s) {
        m.put("chunk_merge_reason", s.reason().name());
      }
      return Map.copyOf(m);
    }
  }

  /**
   * General multi-leg path (legacy composable cascade lines 522–663).
   *
   * <p>{@link LegSet} enumerates the 7 leg-execution shapes. A vector-only or
   * SPLADE-only single-leg request lands here (not in {@code SparseShortcut}).
   */
  record MultiLegDecision(
      LegSet legs,
      Optional<VectorEncoding.Failed> hybridFallback,
      Optional<SpladeEncoding.Failed> spladeSkip,
      Optional<FacetCompute.FromFreshBm25> facets,
      ChunkMergeDirective chunkMerge)
      implements SearchDecision {
    public MultiLegDecision {
      Objects.requireNonNull(legs, "legs");
      Objects.requireNonNull(hybridFallback, "hybridFallback");
      Objects.requireNonNull(spladeSkip, "spladeSkip");
      Objects.requireNonNull(facets, "facets");
      Objects.requireNonNull(chunkMerge, "chunkMerge");
    }

    @Override
    public Map<String, Object> summary() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("decision_kind", "multi_leg");
      m.put("legs", legs.kind());
      m.put("effective_mode", legs.effectiveModeLabel());
      hybridFallback.ifPresent(f -> m.put("hybrid_fallback_reason", f.reason().name()));
      spladeSkip.ifPresent(f -> m.put("splade_skip_reason", f.reason().name()));
      m.put("facet_source", facets.isPresent() ? "from_fresh_bm25" : "absent");
      m.put("chunk_merge_kind", chunkMerge.kind());
      if (chunkMerge instanceof ChunkMergeDirective.Skip s) {
        m.put("chunk_merge_reason", s.reason().name());
      }
      return Map.copyOf(m);
    }
  }
}
