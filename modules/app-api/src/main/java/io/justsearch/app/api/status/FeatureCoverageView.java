/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/** Coverage metrics for a single enrichment feature (embedding or SPLADE). */
public record FeatureCoverageView(
    long docCount,
    long completedCount,
    long pendingCount,
    long failedCount,
    double coveragePercent) {
  public static FeatureCoverageView empty() {
    return new FeatureCoverageView(0, 0, 0, 0, 0.0);
  }
}
