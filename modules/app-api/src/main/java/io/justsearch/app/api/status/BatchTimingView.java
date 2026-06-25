/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import java.util.Map;

/** Map-based batch timing for enrichment phases (354). */
public record BatchTimingView(Map<String, Long> batchCount, Map<String, Long> totalMs) {
  public static BatchTimingView empty() {
    return new BatchTimingView(Map.of(), Map.of());
  }
}
