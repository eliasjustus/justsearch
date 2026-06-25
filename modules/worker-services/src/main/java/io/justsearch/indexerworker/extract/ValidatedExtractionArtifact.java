/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.util.List;

/** Parser output that has passed the Worker artifact trust boundary. */
public record ValidatedExtractionArtifact(
    ExtractionArtifact artifact,
    String policyId,
    String parserId,
    ExtractionStatus status,
    boolean truncated,
    int embeddedResourceCount,
    int maxEmbeddedDepth,
    List<String> warnings,
    String visualExtractionEvidenceJson,
    long validatedAtMs,
    String sourcePathHash) {

  public ValidatedExtractionArtifact {
    if (artifact == null) {
      throw new IllegalArgumentException("artifact is required");
    }
    policyId = policyId == null || policyId.isBlank() ? "UNKNOWN" : policyId.trim();
    parserId = parserId == null || parserId.isBlank() ? "UNKNOWN" : parserId.trim();
    status = status == null ? artifact.status() : status;
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    validatedAtMs = validatedAtMs > 0 ? validatedAtMs : System.currentTimeMillis();
  }

  public ExtractionResult result() {
    return artifact.result();
  }

  public ExtractionArtifact rawArtifact() {
    return artifact;
  }
}
