package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractionFallbackBudget;
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
  void emptyOcrOutputQueuesTheNextTierInsteadOfDeclaringItselfSufficient() {
    // Tempdoc 790 regression: before the dropout gate, an OCR pass that produced NOTHING took the
    // "no visual-enrichment demand" branch and returned notNeeded("ocr_baseline_sufficient") — the
    // VLM tier was never reached for exactly the documents that needed it most.
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("empty-scan.pdf"),
            new ExtractionResult("", null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"textQualityScore\":0.0,\"layoutComplexity\":\"none\","
                + "\"ocrMeanConfidence\":0.0,\"ocrLowConfidenceWordCount\":0,\"ocrWordCount\":0}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_PENDING, decision.status());
    assertEquals(SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT, decision.demandKind());
    assertEquals("extraction_dropout", decision.reason());
  }

  @Test
  void trivialOcrOutputQueuesTheNextTier() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("wordless-scan.pdf"),
            new ExtractionResult("\\", null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"ocrWordCount\":0}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_PENDING, decision.status());
    assertEquals("extraction_dropout", decision.reason());
  }

  @Test
  void dropoutStillRespectsTheFallbackBudget() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("empty-scan.pdf"),
            new ExtractionResult("", null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"ocrWordCount\":0}",
            0.3d,
            new ExtractionFallbackBudget(1, 30_000L));

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, decision.status());
    assertNull(decision.demandKind());
    assertEquals("fallback_budget_spent", decision.reason());
  }

  @Test
  void healthyOcrOutputStillDoesNotQueueTheNextTier() {
    // Precision guard: the dropout gate must not turn every OCR result into a VDU demand.
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("clean-scan.pdf"),
            new ExtractionResult("Readable OCR text ".repeat(20), null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"textQualityScore\":0.94,\"layoutComplexity\":\"none\","
                + "\"ocrMeanConfidence\":0.92,\"ocrLowConfidenceWordCount\":1,\"ocrWordCount\":30}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, decision.status());
    assertEquals("ocr_baseline_sufficient", decision.reason());
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
