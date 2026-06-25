/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

public record TelemetryMetricsView(
    double contentLengthAvgChars,
    long contentLengthMinChars,
    long contentLengthMaxChars,
    double throughputDocsPerSec,
    String throughputWindowState) {
  public TelemetryMetricsView {
    throughputWindowState = throughputWindowState == null ? "" : throughputWindowState;
  }

  public static TelemetryMetricsView empty() {
    return new TelemetryMetricsView(0.0, 0, 0, 0.0, "");
  }
}
