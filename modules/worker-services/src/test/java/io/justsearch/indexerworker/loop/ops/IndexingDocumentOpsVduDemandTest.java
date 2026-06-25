package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

final class IndexingDocumentOpsVduDemandTest {

  @Test
  void ocrPdfWithoutEvidenceSignalsDoesNotQueueVisualEnrichment() {
    Map<String, Object> fields = new HashMap<>();

    IndexingDocumentOps.markVduIfNeeded(
        Path.of("scan.pdf"),
        new ExtractionResult(
            "This OCR output is readable and has enough words to pass quality. "
                .repeat(4),
            null,
            "application/pdf"),
        SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
        "{\"schemaVersion\":1,\"textQualityScore\":0.92,\"layoutComplexity\":\"none\","
            + "\"ocrMeanConfidence\":0.91,\"ocrLowConfidenceWordCount\":1,\"ocrWordCount\":20,"
            + "\"route\":\"ocr_full\"}",
        fields,
        LoggerFactory.getLogger(getClass()));

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, fields.get(SchemaFields.VDU_STATUS));
    assertFalse(fields.containsKey(SchemaFields.VDU_DEMAND_KIND));
  }

  @Test
  void ocrPdfWithOnlyImagePageSignalDoesNotQueueVisualEnrichment() {
    Map<String, Object> fields = new HashMap<>();

    IndexingDocumentOps.markVduIfNeeded(
        Path.of("scan.pdf"),
        new ExtractionResult(
            "This OCR output is readable and has enough words to pass quality. "
                .repeat(4),
            null,
            "application/pdf"),
        SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
        "{\"schemaVersion\":1,\"textQualityScore\":0.92,\"layoutComplexity\":\"mixed_visual\","
            + "\"imagePageCount\":1,\"mixedPdf\":false,"
            + "\"ocrMeanConfidence\":0.91,\"ocrLowConfidenceWordCount\":1,\"ocrWordCount\":20,"
            + "\"route\":\"ocr_full\"}",
        fields,
        LoggerFactory.getLogger(getClass()));

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, fields.get(SchemaFields.VDU_STATUS));
    assertFalse(fields.containsKey(SchemaFields.VDU_DEMAND_KIND));
  }

  @Test
  void ocrPdfWithLayoutSignalsQueuesVisualEnrichment() {
    Map<String, Object> fields = new HashMap<>();

    IndexingDocumentOps.markVduIfNeeded(
        Path.of("scan.pdf"),
        new ExtractionResult(
            "This OCR output is readable and has enough words to pass quality. "
                .repeat(4),
            null,
            "application/pdf"),
        SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
        "{\"schemaVersion\":1,\"textQualityScore\":0.88,\"layoutComplexity\":\"table_like\","
            + "\"structuredElementCounts\":{\"tables\":1},\"route\":\"ocr_full\"}",
        fields,
        LoggerFactory.getLogger(getClass()));

    assertEquals(SchemaFields.VDU_STATUS_PENDING, fields.get(SchemaFields.VDU_STATUS));
    assertEquals(
        SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT,
        fields.get(SchemaFields.VDU_DEMAND_KIND));
  }

  @Test
  void ocrPdfWithLowConfidenceQueuesVisualEnrichment() {
    Map<String, Object> fields = new HashMap<>();

    IndexingDocumentOps.markVduIfNeeded(
        Path.of("scan.pdf"),
        new ExtractionResult(
            "This OCR output is readable enough for baseline search. "
                .repeat(4),
            null,
            "application/pdf"),
        SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
        "{\"schemaVersion\":1,\"textQualityScore\":0.82,\"layoutComplexity\":\"none\","
            + "\"ocrMeanConfidence\":0.62,\"ocrLowConfidenceWordCount\":1,\"ocrWordCount\":8,"
            + "\"route\":\"ocr_full\"}",
        fields,
        LoggerFactory.getLogger(getClass()));

    assertEquals(SchemaFields.VDU_STATUS_PENDING, fields.get(SchemaFields.VDU_STATUS));
    assertEquals(
        SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT,
        fields.get(SchemaFields.VDU_DEMAND_KIND));
  }

  @Test
  void ocrPdfWithHighLowConfidenceRatioQueuesVisualEnrichment() {
    Map<String, Object> fields = new HashMap<>();

    IndexingDocumentOps.markVduIfNeeded(
        Path.of("scan.pdf"),
        new ExtractionResult(
            "This OCR output is readable enough for baseline search. "
                .repeat(4),
            null,
            "application/pdf"),
        SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
        "{\"schemaVersion\":1,\"textQualityScore\":0.82,\"layoutComplexity\":\"none\","
            + "\"ocrMeanConfidence\":0.84,\"ocrLowConfidenceWordCount\":3,\"ocrWordCount\":10,"
            + "\"route\":\"ocr_full\"}",
        fields,
        LoggerFactory.getLogger(getClass()));

    assertEquals(SchemaFields.VDU_STATUS_PENDING, fields.get(SchemaFields.VDU_STATUS));
    assertEquals(
        SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT,
        fields.get(SchemaFields.VDU_DEMAND_KIND));
  }

  @Test
  void ocrImageClearsBaselineDemandWithoutVisualEnrichment() {
    Map<String, Object> fields = new HashMap<>();

    IndexingDocumentOps.markVduIfNeeded(
        Path.of("scan.png"),
        new ExtractionResult("", null, "image/png"),
        SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
        "{\"schemaVersion\":1,\"textQualityScore\":0.7,\"layoutComplexity\":\"none\",\"route\":\"ocr_full\"}",
        fields,
        LoggerFactory.getLogger(getClass()));

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, fields.get(SchemaFields.VDU_STATUS));
    assertFalse(fields.containsKey(SchemaFields.VDU_DEMAND_KIND));
  }

  @Test
  void lowQualityNonOcrVisualDocumentQueuesBaselineDemand() {
    Map<String, Object> fields = new HashMap<>();

    IndexingDocumentOps.markVduIfNeeded(
        Path.of("scan.pdf"),
        new ExtractionResult("", null, "application/pdf"),
        SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
        "{\"schemaVersion\":1,\"pagesMissingReadableText\":1,\"layoutComplexity\":\"mixed_visual\"}",
        fields,
        LoggerFactory.getLogger(getClass()));

    assertEquals(SchemaFields.VDU_STATUS_PENDING, fields.get(SchemaFields.VDU_STATUS));
    assertEquals(
        SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT, fields.get(SchemaFields.VDU_DEMAND_KIND));
  }
}
