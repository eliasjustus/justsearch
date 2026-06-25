/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Worker-owned decision from extraction evidence to VDU demand fields. */
record VisualRoutingDecision(String status, String demandKind, String reason) {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final Set<String> VDU_ELIGIBLE_EXTENSIONS =
      Set.of(".pdf", ".png", ".jpg", ".jpeg", ".tiff", ".bmp", ".gif");

  static VisualRoutingDecision decide(
      Path filePath,
      ExtractionResult extraction,
      String extractionMethod,
      String visualExtractionEvidenceJson,
      double vduQualityThreshold) {
    if (!isVduEligible(filePath)) {
      return notNeeded("ineligible");
    }

    Map<String, Object> evidence = parseEvidence(visualExtractionEvidenceJson);
    if (SchemaFields.EXTRACTION_METHOD_OCR_TIKA.equals(extractionMethod)) {
      if (hasVisualEnrichmentDemand(evidence)) {
        return new VisualRoutingDecision(
            SchemaFields.VDU_STATUS_PENDING,
            SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT,
            "visual_enrichment_signal");
      }
      return notNeeded("ocr_baseline_sufficient");
    }

    double qualityScore = TextQualityAnalyzer.computeQualityScore(extraction.content());
    if (qualityScore < vduQualityThreshold || pagesMissingReadableText(evidence) > 0) {
      return new VisualRoutingDecision(
          SchemaFields.VDU_STATUS_PENDING,
          SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT,
          "baseline_text_missing");
    }
    return notNeeded("structured_baseline_sufficient");
  }

  private static VisualRoutingDecision notNeeded(String reason) {
    return new VisualRoutingDecision(SchemaFields.VDU_STATUS_NOT_NEEDED, null, reason);
  }

  private static boolean isVduEligible(Path filePath) {
    String fileName =
        filePath == null || filePath.getFileName() == null
            ? ""
            : filePath.getFileName().toString().toLowerCase(Locale.ROOT);
    return VDU_ELIGIBLE_EXTENSIONS.stream().anyMatch(fileName::endsWith);
  }

  static boolean hasVisualEnrichmentDemand(Map<String, Object> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return false;
    }
    double meanConfidence = doubleValue(evidence.get("ocrMeanConfidence"));
    int wordCount = intValue(evidence.get("ocrWordCount"));
    int lowConfidenceWords = intValue(evidence.get("ocrLowConfidenceWordCount"));
    if (wordCount > 0
        && ((meanConfidence > 0.0d && meanConfidence < 0.70d)
            || lowConfidenceWords / (double) wordCount >= 0.20d)) {
      return true;
    }
    if ("table_like".equals(stringValue(evidence.get("layoutComplexity")))) {
      return true;
    }
    if (booleanValue(evidence.get("mixedPdf"))) {
      return true;
    }
    Object countsObj = evidence.get("structuredElementCounts");
    if (countsObj instanceof Map<?, ?> counts && intValue(counts.get("tables")) > 0) {
      return true;
    }
    double textQuality = doubleValue(evidence.get("textQualityScore"));
    return textQuality > 0.0d && textQuality < 0.65d;
  }

  static int pagesMissingReadableText(Map<String, Object> evidence) {
    return intValue(evidence == null ? null : evidence.get("pagesMissingReadableText"));
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> parseEvidence(String visualExtractionEvidenceJson) {
    if (visualExtractionEvidenceJson == null || visualExtractionEvidenceJson.isBlank()) {
      return Map.of();
    }
    try {
      Object parsed = JSON.readValue(visualExtractionEvidenceJson, Map.class);
      if (parsed instanceof Map<?, ?> map) {
        Map<String, Object> out = new HashMap<>();
        map.forEach((key, value) -> {
          if (key instanceof String s) {
            out.put(s, value);
          }
        });
        return out;
      }
    } catch (RuntimeException ignored) {
      // Malformed evidence should not block indexing; it only weakens routing explanation.
    }
    return Map.of();
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
  }

  private static boolean booleanValue(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    return "true".equalsIgnoreCase(String.valueOf(value));
  }

  private static int intValue(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static double doubleValue(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    try {
      return value == null ? 0.0d : Double.parseDouble(String.valueOf(value));
    } catch (NumberFormatException e) {
      return 0.0d;
    }
  }
}
