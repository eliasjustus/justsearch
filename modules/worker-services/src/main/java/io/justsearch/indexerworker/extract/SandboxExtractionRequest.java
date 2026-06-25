/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/** Versioned request sent from the Worker extraction parent to a sandbox child process. */
public record SandboxExtractionRequest(
    int schemaVersion, String path, TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public SandboxExtractionRequest(int schemaVersion, String path, TikaExtractionPolicy policy) {
    this(schemaVersion, path, policy, OcrRoutingConfig.disabled());
  }

  public SandboxExtractionRequest {
    if (schemaVersion <= 0) {
      schemaVersion = CURRENT_SCHEMA_VERSION;
    }
    policy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    ocrConfig = ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig;
  }
}
