/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.util.List;
import java.util.Objects;

/** Bounded parser response that must be validated before indexing. */
public record ExtractionArtifact(
    ExtractionStatus status,
    ExtractionResult result,
    String policyId,
    String parserId,
    boolean truncated,
    List<String> warnings,
    int embeddedResourceCount,
    int maxEmbeddedDepth,
    String visualExtractionEvidenceJson) {
  private static final int MAX_METADATA_ENTRIES = 128;
  private static final int MAX_METADATA_KEY_CHARS = 128;
  private static final int MAX_METADATA_VALUE_CHARS = 4096;
  // The scalar-metadata validation backstop. ExtractionResult bounds the title to its own, much
  // smaller MAX_INDEXED_TITLE_CHARS at the source (observation #379), so this stays the defensive
  // last-resort limit for title/mimeType/author (validateScalar below).
  private static final int MAX_SCALAR_METADATA_CHARS = 4096;
  private static final int MAX_WARNING_COUNT = 32;
  private static final int MAX_WARNING_CHARS = 512;
  private static final int MAX_VISUAL_EXTRACTION_EVIDENCE_CHARS = VisualExtractionEvidence.MAX_JSON_CHARS;

  public ExtractionArtifact {
    status = status == null ? ExtractionStatus.SUCCESS_FULL : status;
    result = result == null ? new ExtractionResult("", null, "application/octet-stream") : result;
    policyId = trimToNull(policyId);
    parserId = trimToNull(parserId);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    embeddedResourceCount = Math.max(0, embeddedResourceCount);
    maxEmbeddedDepth = Math.max(0, maxEmbeddedDepth);
    visualExtractionEvidenceJson = trimToNull(visualExtractionEvidenceJson);
  }

  public ExtractionArtifact(
      ExtractionStatus status,
      ExtractionResult result,
      String policyId,
      String parserId,
      boolean truncated,
      List<String> warnings) {
    this(status, result, policyId, parserId, truncated, warnings, 0, 0, null);
  }

  public ExtractionArtifact(
      ExtractionStatus status,
      ExtractionResult result,
      String policyId,
      String parserId,
      boolean truncated,
      List<String> warnings,
      int embeddedResourceCount,
      int maxEmbeddedDepth) {
    this(status, result, policyId, parserId, truncated, warnings, embeddedResourceCount, maxEmbeddedDepth, null);
  }

  public static ExtractionArtifact full(ExtractionResult result, String parserId) {
    return new ExtractionArtifact(
        ExtractionStatus.SUCCESS_FULL,
        result,
        TikaExtractionPolicy.defaults().policyId(),
        parserId,
        false,
        List.of(),
        0,
        0,
        null);
  }

  public static ExtractionArtifact full(
      ExtractionResult result, TikaExtractionPolicy policy, String parserId, boolean truncated) {
    TikaExtractionPolicy effectivePolicy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    return new ExtractionArtifact(
        truncated ? ExtractionStatus.SUCCESS_PARTIAL : ExtractionStatus.SUCCESS_FULL,
        result,
        effectivePolicy.policyId(),
        parserId,
        truncated,
        List.of(),
        0,
        0,
        null);
  }

  public ExtractionArtifact withVisualExtractionEvidence(VisualExtractionEvidence evidence) {
    return withVisualExtractionEvidenceJson(evidence == null ? null : evidence.toJson());
  }

  public ExtractionArtifact withVisualExtractionEvidenceJson(String evidenceJson) {
    return new ExtractionArtifact(
        status,
        result,
        policyId,
        parserId,
        truncated,
        warnings,
        embeddedResourceCount,
        maxEmbeddedDepth,
        evidenceJson);
  }

  /**
   * Sandbox-internal bounds check — used inside the extraction child JVM and by
   * {@code ProcessExtractionSandbox} immediately after deserializing a sandbox response. Enforces
   * content length, scalar metadata caps, warning caps, but NOT policy-id matching or
   * embedded-resource caps (those are caller-side, post-sandbox).
   *
   * <p>Production indexing flow always goes through {@link #validate(TikaExtractionPolicy,
   * String)} which is the full validation including policy-id match. Do not call this overload
   * from any code that feeds {@code IndexingDocumentOps}.
   */
  public ExtractionArtifact validateContentBoundsOnly(int maxContentLength)
      throws ContentExtractor.ExtractionException {
    validateContentAndMetadata(maxContentLength, null);
    return this;
  }

  public ValidatedExtractionArtifact validate(TikaExtractionPolicy expectedPolicy, String sourcePathHash)
      throws ContentExtractor.ExtractionException {
    TikaExtractionPolicy policy = expectedPolicy == null ? TikaExtractionPolicy.defaults() : expectedPolicy;
    validateContentAndMetadata(policy.maxExtractedChars(), policy);
    return new ValidatedExtractionArtifact(
        this,
        policyId,
        parserId,
        status,
        truncated,
        embeddedResourceCount,
        maxEmbeddedDepth,
        warnings,
        visualExtractionEvidenceJson,
        System.currentTimeMillis(),
        sourcePathHash);
  }

  private void validateContentAndMetadata(int maxContentLength, TikaExtractionPolicy expectedPolicy)
      throws ContentExtractor.ExtractionException {
    Objects.requireNonNull(result, "result");
    if (policyId == null || policyId.isBlank()) {
      throw new ContentExtractor.ExtractionException("Extraction artifact is missing policy id");
    }
    if (parserId == null || parserId.isBlank()) {
      throw new ContentExtractor.ExtractionException("Extraction artifact is missing parser id");
    }
    if (expectedPolicy != null && !policyId.equals(expectedPolicy.policyId())) {
      throw new ContentExtractor.ExtractionException("Extraction artifact policy id does not match expected policy");
    }
    if (status == ExtractionStatus.FAILED
        || status == ExtractionStatus.TIMED_OUT
        || status == ExtractionStatus.BUDGET_EXCEEDED) {
      throw new ContentExtractor.ExtractionException("Extraction artifact is not successful: " + status);
    }
    if (result.content().length() > maxContentLength) {
      throw new ContentExtractor.BudgetExceededException(
          "Extracted content exceeds max length", "EXTRACTED_TEXT_TOO_LARGE");
    }
    validateScalar("title", result.title());
    validateScalar("mimeType", result.mimeType());
    validateScalar("author", result.author());
    validateFrontmatter(result.frontmatterMetadata(), expectedPolicy);
    validateEmbeddedResources(expectedPolicy);
    validateWarnings();
    validateVisualExtractionEvidence();
  }

  private static void validateScalar(String name, String value)
      throws ContentExtractor.ExtractionException {
    if (value != null && value.length() > MAX_SCALAR_METADATA_CHARS) {
      throw new ContentExtractor.ExtractionException(name + " metadata exceeds max length");
    }
  }

  private static void validateFrontmatter(
      java.util.Map<String, String> metadata, TikaExtractionPolicy expectedPolicy)
      throws ContentExtractor.ExtractionException {
    if (metadata == null) return;
    int maxEntries = expectedPolicy != null ? expectedPolicy.maxMetadataEntries() : MAX_METADATA_ENTRIES;
    int maxKeyChars = expectedPolicy != null ? expectedPolicy.maxMetadataKeyChars() : MAX_METADATA_KEY_CHARS;
    int maxValueChars =
        expectedPolicy != null ? expectedPolicy.maxMetadataValueChars() : MAX_METADATA_VALUE_CHARS;
    if (metadata.size() > maxEntries) {
      throw new ContentExtractor.ExtractionException("Frontmatter metadata has too many entries");
    }
    for (var entry : metadata.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key != null && key.length() > maxKeyChars) {
        throw new ContentExtractor.ExtractionException("Frontmatter metadata key exceeds max length");
      }
      if (value != null && value.length() > maxValueChars) {
        throw new ContentExtractor.ExtractionException("Frontmatter metadata value exceeds max length");
      }
    }
  }

  private void validateEmbeddedResources(TikaExtractionPolicy expectedPolicy)
      throws ContentExtractor.ExtractionException {
    if (expectedPolicy == null) {
      return;
    }
    if (embeddedResourceCount > expectedPolicy.maxEmbeddedResources()) {
      throw new ContentExtractor.ExtractionException("Extraction artifact embedded resource count exceeds policy");
    }
    if (maxEmbeddedDepth > expectedPolicy.maxEmbeddedDepth()) {
      throw new ContentExtractor.ExtractionException("Extraction artifact embedded resource depth exceeds policy");
    }
  }

  private void validateWarnings() throws ContentExtractor.ExtractionException {
    if (warnings.size() > MAX_WARNING_COUNT) {
      throw new ContentExtractor.ExtractionException("Extraction artifact has too many warnings");
    }
    for (String warning : warnings) {
      if (warning != null && warning.length() > MAX_WARNING_CHARS) {
        throw new ContentExtractor.ExtractionException("Extraction artifact warning exceeds max length");
      }
    }
  }

  private void validateVisualExtractionEvidence() throws ContentExtractor.ExtractionException {
    if (visualExtractionEvidenceJson != null
        && visualExtractionEvidenceJson.length() > MAX_VISUAL_EXTRACTION_EVIDENCE_CHARS) {
      throw new ContentExtractor.ExtractionException("Visual extraction evidence exceeds max length");
    }
  }

  private static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
