/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import java.util.List;

/**
 * ORT CUDA GPU reranking status sub-view.
 *
 * <p>Stability: stable (API contract)
 */
public record OrtCudaView(
    boolean configured,
    boolean attempted,
    boolean available,
    String variantId,
    String nativePath,
    String failureReason,
    List<String> missingDlls) {

  /** Reason string when GPU was never requested/configured for a subsystem. */
  public static final String REASON_NOT_CONFIGURED = "GPU not configured";

  /** Returns a view representing a subsystem where GPU was not configured. */
  public static OrtCudaView notConfigured() {
    return new OrtCudaView(false, false, false, "", "", REASON_NOT_CONFIGURED, List.of());
  }

  public OrtCudaView {
    variantId = variantId == null ? "" : variantId;
    nativePath = nativePath == null ? "" : nativePath;
    failureReason = failureReason == null ? "" : failureReason;
    missingDlls = missingDlls == null ? List.of() : List.copyOf(missingDlls);
  }
}
