/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.plan;

import io.justsearch.indexerworker.services.input.SpladeEncoding;
import io.justsearch.indexerworker.services.input.VectorEncoding;
import java.util.Map;
import java.util.Objects;

/**
 * The seven non-empty subsets of {BM25, Dense, SPLADE} (tempdoc 517).
 *
 * <p>The current orchestrator's 8-way {@code if/else} cascade at lines 527–663
 * is replaced by pattern-matching over this sealed sum. Each variant carries
 * its leg-specific inputs. No methods on variants (data + dispatch).
 *
 * <p>{@code Bm25Only} is reached only via hybrid-request degradation
 * (composable path, line 656 in the legacy code) — the sparse-only-request
 * case lives in {@code SparseShortcut}, not here, because the two have
 * different facet behaviour and correction-retry policy.
 */
public sealed interface LegSet
    permits LegSet.Bm25Only,
        LegSet.DenseOnly,
        LegSet.SpladeOnly,
        LegSet.Bm25Dense,
        LegSet.Bm25Splade,
        LegSet.DenseSplade,
        LegSet.ThreeWay {

  /** BM25-only via hybrid degradation (vector encoding {@link VectorEncoding.Failed}). */
  record Bm25Only(int retrievalLimit) implements LegSet {}

  /** Dense-only retrieval; carries the encoded vector. */
  record DenseOnly(VectorEncoding.Success vector, int retrievalLimit) implements LegSet {
    public DenseOnly {
      Objects.requireNonNull(vector, "vector");
    }
  }

  /** SPLADE-only retrieval; carries the encoded weight map. */
  record SpladeOnly(SpladeEncoding.Success splade, int retrievalLimit) implements LegSet {
    public SpladeOnly {
      Objects.requireNonNull(splade, "splade");
    }
  }

  /** Hybrid BM25 + Dense; uses {@code HybridSearchOps.searchHybridFiltered}. */
  record Bm25Dense(VectorEncoding.Success vector, int retrievalLimit, double hybridWeight)
      implements LegSet {
    public Bm25Dense {
      Objects.requireNonNull(vector, "vector");
    }
  }

  /** BM25 + SPLADE; fused via {@code fuseLegs(RRF)}. */
  record Bm25Splade(SpladeEncoding.Success splade, int retrievalLimit) implements LegSet {
    public Bm25Splade {
      Objects.requireNonNull(splade, "splade");
    }
  }

  /** Dense + SPLADE; fused via {@code fuseLegs(RRF)}. */
  record DenseSplade(
      VectorEncoding.Success vector, SpladeEncoding.Success splade, int retrievalLimit)
      implements LegSet {
    public DenseSplade {
      Objects.requireNonNull(vector, "vector");
      Objects.requireNonNull(splade, "splade");
    }
  }

  /** All three legs; fused via {@code HybridFusionUtils.fuseWithCC3}. */
  record ThreeWay(
      VectorEncoding.Success vector,
      SpladeEncoding.Success splade,
      int retrievalLimit,
      double hybridWeight)
      implements LegSet {
    public ThreeWay {
      Objects.requireNonNull(vector, "vector");
      Objects.requireNonNull(splade, "splade");
    }
  }

  /** Stable short identifier for {@link SearchDecision#summary()}. */
  default String kind() {
    return switch (this) {
      case Bm25Only b -> "bm25_only";
      case DenseOnly d -> "dense_only";
      case SpladeOnly p -> "splade_only";
      case Bm25Dense bd -> "bm25_dense";
      case Bm25Splade bs -> "bm25_splade";
      case DenseSplade ds -> "dense_splade";
      case ThreeWay t -> "three_way";
    };
  }

  /** Mode label for the wire response's {@code effective_mode} field. */
  default String effectiveModeLabel() {
    return switch (this) {
      case Bm25Only b -> "TEXT";
      case DenseOnly d -> "VECTOR";
      case SpladeOnly p -> "TEXT"; // splade is a sparse text variant
      case Bm25Dense bd -> "HYBRID";
      case Bm25Splade bs -> "HYBRID";
      case DenseSplade ds -> "HYBRID";
      case ThreeWay t -> "HYBRID";
    };
  }
}
