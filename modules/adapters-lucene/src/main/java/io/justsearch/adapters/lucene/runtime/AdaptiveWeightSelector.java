/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tempdoc 580 §13.3 / §13.8 — per-query adaptive CC fusion-weight selection.
 *
 * <p>The premise (§9.3, validated at HEAD): the optimal fusion recipe <em>flips by corpus length
 * regime</em> — long documents want a BM25-dominant blend, short documents want a balanced blend.
 * A whole-index {@code CorpusProfile.isLongCorpus()} statistic is structurally useless on a mixed
 * personal corpus (it always reads "mixed"); the right granularity is the <em>retrieved candidate
 * set of this query</em>, whose length distribution this selector reads directly.
 *
 * <p>This is the <strong>v0</strong> realization: a per-query length-gated pick between the two
 * §9.3-validated recipes — the §4b corpus-level binary switch moved to query granularity. It keys
 * on one signal (the median parent-token-count of the top-K retrieved candidates). The richer v1
 * (continuous function reading per-leg score dispersion and cross-leg rank overlap, fit on the
 * register's per-corpus optima) is left as future work, gated on this v0's eval result.
 *
 * <p>Default OFF — selection only runs when {@code index.hybrid.adaptive_weights_enabled} is true.
 * When no length signal is available (no candidate carries a parent-token-count), it returns the
 * caller's configured static weights unchanged, so it can never silently degrade a configured run.
 */
public final class AdaptiveWeightSelector {

  private AdaptiveWeightSelector() {}

  /** §9.3 BM25-dominant recipe for long retrieved sets. Order: {sparse(BM25), dense, splade}. */
  static final double[] BM25_DOMINANT = {0.60, 0.20, 0.20};

  /** §9.3 balanced recipe for short retrieved sets. Order: {sparse(BM25), dense, splade}. */
  static final double[] BALANCED = {0.34, 0.33, 0.33};

  /**
   * Per-query median-token threshold above which the retrieved set is treated as "long" (mirrors
   * {@code CorpusProfile.isLongCorpus()}'s 2048-token cut, applied per query rather than per index).
   */
  static final double LONG_MEDIAN_TOKENS = 2048.0;

  /** Top hits per leg inspected for the length signal (the user-visible head of the ranking). */
  static final int TOP_K = 20;

  /**
   * Selects CC fusion weights for this query from its retrieved candidates' length distribution.
   *
   * @param bm25 the lexical (BM25) leg result; may be null
   * @param dense the dense (vector) leg result; may be null
   * @param splade the learned-sparse (SPLADE) leg result; may be null
   * @param staticWeights the configured static weights, returned verbatim when no signal exists
   * @return the per-query weight vector {sparse, dense, splade}
   */
  public static double[] selectWeights(
      SearchResult bm25, SearchResult dense, SearchResult splade, double[] staticWeights) {
    Double lenMedian = medianParentTokens(bm25, dense, splade);
    if (lenMedian == null) {
      return staticWeights; // no length signal → honor the configured weights
    }
    return (lenMedian > LONG_MEDIAN_TOKENS ? BM25_DOMINANT : BALANCED).clone();
  }

  /**
   * Median {@code parent_token_count} over the union of the top-{@link #TOP_K} hits of each leg,
   * deduped by doc id. Returns null when no candidate carries a parsable token count.
   */
  static Double medianParentTokens(SearchResult... legs) {
    Set<String> seen = new HashSet<>();
    List<Float> tokens = new ArrayList<>();
    for (SearchResult leg : legs) {
      if (leg == null || leg.hits() == null) {
        continue;
      }
      int pos = 0;
      for (SearchHit hit : leg.hits()) {
        if (pos++ >= TOP_K) {
          break;
        }
        if (hit.docId() == null || !seen.add(hit.docId())) {
          continue;
        }
        Float t = parentTokens(hit.fields());
        if (t != null) {
          tokens.add(t);
        }
      }
    }
    if (tokens.isEmpty()) {
      return null;
    }
    tokens.sort(null);
    int mid = tokens.size() / 2;
    return tokens.size() % 2 == 1
        ? (double) tokens.get(mid)
        : (tokens.get(mid - 1) + tokens.get(mid)) / 2.0;
  }

  private static Float parentTokens(Map<String, String> fields) {
    if (fields == null) {
      return null;
    }
    String raw = fields.get(SchemaFields.PARENT_TOKEN_COUNT);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Float.parseFloat(raw);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
