/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import java.util.Map;

public record FailureTrackingView(
    long failedJobs,
    String lastFailedPath,
    String lastFailedErrorMessage,
    long lastFailedAtMs,
    long nextRetryAtMs,
    long searchesZeroResultCount,
    Map<String, Long> failedByFileKind) {
  public FailureTrackingView {
    lastFailedPath = lastFailedPath == null ? "" : lastFailedPath;
    lastFailedErrorMessage = lastFailedErrorMessage == null ? "" : lastFailedErrorMessage;
    failedByFileKind = failedByFileKind == null ? Map.of() : Map.copyOf(failedByFileKind);
  }

  public static FailureTrackingView empty() {
    return new FailureTrackingView(0, "", "", 0, 0, 0, Map.of());
  }
}
