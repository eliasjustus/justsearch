/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

public record CompatibilityStatusView(
    String embeddingCompatState,
    String embeddingCompatReason,
    String embeddingFingerprintCurrent,
    String embeddingFingerprintStored,
    String indexSchemaFpCurrent,
    String indexSchemaFpStored,
    String indexSchemaCompatState,
    boolean reindexRequired,
    String reindexRequiredReason) {
  public CompatibilityStatusView {
    embeddingCompatState = embeddingCompatState == null ? "" : embeddingCompatState;
    embeddingCompatReason = embeddingCompatReason == null ? "" : embeddingCompatReason;
    embeddingFingerprintCurrent = embeddingFingerprintCurrent == null ? "" : embeddingFingerprintCurrent;
    embeddingFingerprintStored = embeddingFingerprintStored == null ? "" : embeddingFingerprintStored;
    indexSchemaFpCurrent = indexSchemaFpCurrent == null ? "" : indexSchemaFpCurrent;
    indexSchemaFpStored = indexSchemaFpStored == null ? "" : indexSchemaFpStored;
    indexSchemaCompatState = indexSchemaCompatState == null ? "" : indexSchemaCompatState;
    reindexRequiredReason = reindexRequiredReason == null ? "" : reindexRequiredReason;
  }

  public static CompatibilityStatusView empty() {
    return new CompatibilityStatusView("", "", "", "", "", "", "", false, "");
  }
}
