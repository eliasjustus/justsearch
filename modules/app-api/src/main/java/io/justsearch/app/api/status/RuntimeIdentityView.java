/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Status-record projection of {@code RuntimeIdentity}. Identifies the current open period of
 * the inference runtime — consumers compare {@link #generationId} across status reads to
 * detect runtime restarts (e.g., after admin reload).
 *
 * <p>Tempdoc 412 Phase 3.
 *
 * <p>Stability: stable (API contract)
 */
public record RuntimeIdentityView(
    long generationId,
    String modelId,
    int port,
    long loadedAtEpochMs) {

  public RuntimeIdentityView {
    modelId = modelId == null ? "" : modelId;
  }
}
