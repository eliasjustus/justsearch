/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.util.List;
import java.util.Map;

/** Versioned response returned by a sandbox child process. */
public record SandboxExtractionResponse(
    int schemaVersion,
    ExtractionStatus status,
    String content,
    String title,
    String mimeType,
    String author,
    Map<String, String> frontmatterMetadata,
    String policyId,
    String parserId,
    boolean truncated,
    List<String> warnings,
    int embeddedResourceCount,
    int maxEmbeddedDepth,
    String visualExtractionEvidenceJson,
    String errorMessage,
    String reasonCode) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public static SandboxExtractionResponse fromArtifact(ExtractionArtifact artifact) {
    ExtractionResult result = artifact.result();
    return new SandboxExtractionResponse(
        CURRENT_SCHEMA_VERSION,
        artifact.status(),
        result.content(),
        result.title(),
        result.mimeType(),
        result.author(),
        result.frontmatterMetadata(),
        artifact.policyId(),
        artifact.parserId(),
        artifact.truncated(),
        artifact.warnings(),
        artifact.embeddedResourceCount(),
        artifact.maxEmbeddedDepth(),
        artifact.visualExtractionEvidenceJson(),
        null,
        null);
  }

  public static SandboxExtractionResponse failed(
      ExtractionStatus status, TikaExtractionPolicy policy, String parserId, String errorMessage, String reasonCode) {
    return new SandboxExtractionResponse(
        CURRENT_SCHEMA_VERSION,
        status,
        "",
        null,
        "application/octet-stream",
        null,
        Map.of(),
        policy == null ? TikaExtractionPolicy.defaults().policyId() : policy.policyId(),
        parserId,
        false,
        List.of(),
        0,
        0,
        null,
        sanitize(errorMessage),
        reasonCode);
  }

  public ExtractionArtifact toArtifact() {
    ExtractionResult result =
        new ExtractionResult(content, title, mimeType, author, frontmatterMetadata);
    return new ExtractionArtifact(
        status,
        result,
        policyId,
        parserId,
        truncated,
        warnings,
        embeddedResourceCount,
        maxEmbeddedDepth,
        visualExtractionEvidenceJson);
  }

  private static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String oneLine = value.replaceAll("[\\r\\n\\t]+", " ").trim();
    return oneLine.length() <= 512 ? oneLine : oneLine.substring(0, 512);
  }
}
