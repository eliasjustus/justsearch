/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Active search pipeline configuration, exposed via /api/status for eval provenance.
 *
 * <p>jseval snapshots these values at run start so runs are reproducible and
 * distinguishable (e.g., chunk-ON vs chunk-OFF, balanced vs BM25-dominant weights).
 *
 * <p>Uses default camelCase naming (consistent with other status view records).
 *
 * <p>Stability: internal (status endpoint only)
 *
 * @see io.justsearch.configuration.resolved.ResolvedConfig.Search
 * @see io.justsearch.configuration.resolved.ResolvedConfig.HybridSearch
 */
public record SearchConfigView(
    boolean chunkAwareEnabled,
    double ccWeightSparse,
    double ccWeightDense,
    double ccWeightSplade,
    double branchCcWeightWhole,
    double branchCcWeightChunk,
    double branchChunkMinWeightMultiplier,
    double titleBoost,
    double entityBoost,
    boolean queryClassificationEnabled) {

  public static SearchConfigView empty() {
    return new SearchConfigView(true, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, true);
  }
}
