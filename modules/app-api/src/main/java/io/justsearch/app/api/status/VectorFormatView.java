/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

public record VectorFormatView(
    String vectorFormatConfig,
    String vectorFormatStored,
    String vectorFormatActual,
    long vectorSegmentsFloat32,
    long vectorSegmentsQuantized) {
  public VectorFormatView {
    vectorFormatConfig = vectorFormatConfig == null ? "" : vectorFormatConfig;
    vectorFormatStored = vectorFormatStored == null ? "" : vectorFormatStored;
    vectorFormatActual = vectorFormatActual == null ? "" : vectorFormatActual;
  }

  public static VectorFormatView empty() {
    return new VectorFormatView("", "", "", 0, 0);
  }
}
