/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

/**
 * Pre-retrieval query-performance-prediction signals captured at request entry.
 *
 * <p>Computed once via {@code TextQueryOps.getQppSignals(...)}; carried in
 * {@link SearchInputs} and projected into the wire response by
 * {@code SearchResponseBuilder}.
 */
public record QppMetrics(float maxIdf, float avgIctf, float queryScope) {
  public static final QppMetrics ZERO = new QppMetrics(0f, 0f, 0f);
}
