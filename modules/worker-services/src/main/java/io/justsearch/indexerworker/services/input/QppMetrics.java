/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

/**
 * Pre-retrieval query-performance-prediction signals captured at request entry.
 *
 * <p>Computed once via {@code TextQueryOps.getQppSignals(...)}; carried in
 * {@link SearchInputs}. The original three metrics are projected into the wire response by {@code
 * SearchResponseBuilder}; {@code fieldDocCount} and {@code minTermDocFreqFraction} stay internal
 * and let the planner decide whether a redundant dense leg has any discriminative lexical signal.
 */
public record QppMetrics(
    float maxIdf,
    float avgIctf,
    float queryScope,
    long fieldDocCount,
    float minTermDocFreqFraction) {
  public static final QppMetrics ZERO = new QppMetrics(0f, 0f, 0f, 0L, 0f);
}
