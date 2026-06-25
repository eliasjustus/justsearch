/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.util.Set;

/**
 * Worker-owned extraction policy composed before any parser output enters indexing.
 *
 * <p><b>{@code requireXmlEntitySafeTikaDefaults}</b> is a tripwire on policy construction, not a
 * parser-config enforcement. Setting it to {@code false} fails fast at construction so callers
 * cannot accidentally build a policy that admits XML external entity expansion. The actual XML
 * hardening at parse time relies on Tika's default {@code TikaConfig} secure-XML-factory
 * behavior — wiring the flag through to {@code TikaConfig} construction is future work
 * (tempdoc 410 §4 "Tika-native controls"). The XXE leak test in
 * {@code PolicyDrivenTikaExtractorTest} verifies the current Tika defaults reject DTDs / external
 * entities for the parsers exercised; a Tika upgrade should re-run that test.
 */
public record TikaExtractionPolicy(
    String policyId,
    int maxExtractedChars,
    long maxInputBytes,
    long maxOfficeInputBytes,
    int maxMetadataEntries,
    int maxMetadataKeyChars,
    int maxMetadataValueChars,
    int maxEmbeddedResources,
    int maxEmbeddedDepth,
    double maxCompressionRatio,
    boolean requireXmlEntitySafeTikaDefaults,
    Set<String> allowedMimeTypes,
    Set<String> excludedMimeTypes) {

  public static final int DEFAULT_MAX_EXTRACTED_CHARS = 10 * 1024 * 1024;
  public static final long DEFAULT_MAX_INPUT_BYTES = 100L * 1024 * 1024;
  public static final long DEFAULT_MAX_OFFICE_INPUT_BYTES = 30L * 1024 * 1024;

  public TikaExtractionPolicy {
    policyId = policyId == null || policyId.isBlank() ? "tika-default-v1" : policyId.trim();
    maxExtractedChars = positive(maxExtractedChars, DEFAULT_MAX_EXTRACTED_CHARS);
    maxInputBytes = positive(maxInputBytes, DEFAULT_MAX_INPUT_BYTES);
    maxOfficeInputBytes = positive(maxOfficeInputBytes, DEFAULT_MAX_OFFICE_INPUT_BYTES);
    maxMetadataEntries = positive(maxMetadataEntries, 128);
    maxMetadataKeyChars = positive(maxMetadataKeyChars, 128);
    maxMetadataValueChars = positive(maxMetadataValueChars, 4096);
    maxEmbeddedResources = Math.max(0, maxEmbeddedResources);
    maxEmbeddedDepth = Math.max(0, maxEmbeddedDepth);
    maxCompressionRatio = maxCompressionRatio <= 0 ? 100.0d : maxCompressionRatio;
    if (!requireXmlEntitySafeTikaDefaults) {
      throw new IllegalArgumentException(
          "Policy must opt into Tika's XML-entity-safe defaults (tripwire only — "
              + "actual parser-config enforcement is future work; see record Javadoc)");
    }
    allowedMimeTypes = allowedMimeTypes == null ? Set.of() : Set.copyOf(allowedMimeTypes);
    excludedMimeTypes = excludedMimeTypes == null ? Set.of() : Set.copyOf(excludedMimeTypes);
  }

  public static TikaExtractionPolicy defaults() {
    return new TikaExtractionPolicy(
        "tika-default-v1",
        DEFAULT_MAX_EXTRACTED_CHARS,
        DEFAULT_MAX_INPUT_BYTES,
        DEFAULT_MAX_OFFICE_INPUT_BYTES,
        128,
        128,
        4096,
        256,
        8,
        100.0d,
        true,
        Set.of(),
        Set.of());
  }

  public boolean permitsMimeType(String mimeType) {
    String normalized = normalizeMime(mimeType);
    if (excludedMimeTypes.contains(normalized)) {
      return false;
    }
    return allowedMimeTypes.isEmpty() || allowedMimeTypes.contains(normalized);
  }

  private static int positive(int value, int fallback) {
    return value > 0 ? value : fallback;
  }

  private static long positive(long value, long fallback) {
    return value > 0 ? value : fallback;
  }

  private static String normalizeMime(String mimeType) {
    return mimeType == null || mimeType.isBlank()
        ? "application/octet-stream"
        : mimeType.strip().toLowerCase(java.util.Locale.ROOT);
  }
}
