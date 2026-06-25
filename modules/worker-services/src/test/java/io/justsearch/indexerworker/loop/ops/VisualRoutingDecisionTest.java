package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class VisualRoutingDecisionTest {

  @Test
  void highConfidencePlainOcrDoesNotQueueVdu() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("plain-scan.pdf"),
            new ExtractionResult("Readable OCR text ".repeat(20), null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"textQualityScore\":0.94,\"layoutComplexity\":\"mixed_visual\","
                + "\"imagePageCount\":1,\"mixedPdf\":false,"
                + "\"ocrMeanConfidence\":0.92,\"ocrLowConfidenceWordCount\":1,\"ocrWordCount\":30}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, decision.status());
    assertNull(decision.demandKind());
  }

  @Test
  void lowConfidenceOcrQueuesEnrichment() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("weak-scan.pdf"),
            new ExtractionResult("Readable OCR text ".repeat(20), null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"textQualityScore\":0.82,\"layoutComplexity\":\"none\","
                + "\"ocrMeanConfidence\":0.61,\"ocrLowConfidenceWordCount\":2,\"ocrWordCount\":20}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_PENDING, decision.status());
    assertEquals(SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT, decision.demandKind());
  }

  @Test
  void missingReadableStructuredPagesQueueBaselineDemand() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("partial.pdf"),
            new ExtractionResult("Some digital text", null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
            "{\"schemaVersion\":1,\"pagesMissingReadableText\":1}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_PENDING, decision.status());
    assertEquals(SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT, decision.demandKind());
  }
}
