/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.BudgetExceededException;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionException;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.tika.Tika;
import org.apache.tika.config.TikaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Policy-composed Tika extractor.
 *
 * <p>This is the Worker-side adapter where JustSearch budgets are translated to Tika-native
 * parser configuration, output limits, MIME admission, and artifact provenance.
 */
public final class PolicyDrivenTikaExtractor implements ContentExtractorProvider {
  private static final Logger log = LoggerFactory.getLogger(PolicyDrivenTikaExtractor.class);
  private static final String OCR_FALLBACK_DIRECT_TESSERACT = "direct_tesseract";
  private static final String OCR_FALLBACK_RENDERED_PDF = "rendered_pdf";

  private final TikaExtractionPolicy policy;
  private final OcrRoutingConfig ocrConfig;
  private final OcrMetricCatalog ocrMetricCatalog;
  private final Tika tika;
  private final StructuredContentExtractor structuredExtractor;

  public PolicyDrivenTikaExtractor() {
    this(TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled());
  }

  public PolicyDrivenTikaExtractor(TikaExtractionPolicy policy) {
    this(policy, OcrRoutingConfig.disabled());
  }

  public PolicyDrivenTikaExtractor(TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig) {
    this(policy, ocrConfig, OcrMetricCatalog.noop());
  }

  public PolicyDrivenTikaExtractor(
      TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig, OcrMetricCatalog ocrMetricCatalog) {
    this.policy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    this.ocrConfig = ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig;
    this.ocrMetricCatalog = ocrMetricCatalog == null ? OcrMetricCatalog.noop() : ocrMetricCatalog;
    TikaConfig config = TikaConfig.getDefaultConfig();
    this.tika = new Tika(config);
    this.tika.setMaxStringLength(this.policy.maxExtractedChars());
    this.structuredExtractor = new StructuredContentExtractor(this.policy.maxExtractedChars());
  }

  public TikaExtractionPolicy policy() {
    return policy;
  }

  @Override
  public ExtractionResult extract(Path file) throws IOException, ExtractionException {
    return extractArtifact(file).result();
  }

  public ExtractionArtifact extractArtifact(Path file) throws IOException, ExtractionException {
    Objects.requireNonNull(file, "file");
    if (!Files.exists(file)) {
      throw new IOException("File does not exist: " + file);
    }
    if (!Files.isReadable(file)) {
      throw new IOException("File is not readable: " + file);
    }

    long fileSize = Files.size(file);
    if (fileSize > policy.maxInputBytes()) {
      throw new BudgetExceededException("Input exceeds policy size limit", "INPUT_TOO_LARGE");
    }

    String detectedMime = detectMimeType(file);
    if (!policy.permitsMimeType(detectedMime)) {
      throw new ExtractionException("MIME type excluded by extraction policy");
    }
    if (fileSize > policy.maxOfficeInputBytes() && ContentExtractor.isOfficeMimeType(detectedMime)) {
      throw new BudgetExceededException("Office input exceeds policy size limit", "OFFICE_INPUT_TOO_LARGE");
    }
    if (fileSize == 0) {
      return ExtractionArtifact.full(
          new ExtractionResult("", null, "text/plain"), policy, "tika-policy", false);
    }

    StructuredContentExtractor.StructuredExtractionResult structured =
        structuredExtractor.extractWithStatus(file);
    ExtractionResult result = structured.result();
    StructuredDocumentSummary summary = structured.summary();
    if (isPdfFile(file, detectedMime)) {
      summary = PdfVisualAnalyzer.enrich(file, summary);
    }
    int pageCount = summary.pageCount();
    if (isRasterImageFile(file, detectedMime) && result.content() != null && !result.content().isBlank()) {
      // Tika's image parser can invoke Tesseract from the default config even when the first pass
      // is intended to be structured/non-OCR. Raster images have no text layer, so treat their
      // baseline as empty and let the explicit bounded OCR route own text, metrics, and provenance.
      result =
          new ExtractionResult(
              "",
              result.title(),
              result.mimeType() == null || result.mimeType().isBlank() ? detectedMime : result.mimeType(),
              result.author(),
              result.frontmatterMetadata());
      summary =
          new StructuredDocumentSummary(
              Math.max(pageCount, 1), 0, 0, Math.max(pageCount, 1), 0, 0, 0, 0, 1);
      pageCount = summary.pageCount();
    }
    boolean truncated = structured.truncated();
    if (truncated && result.content().length() > policy.maxExtractedChars()) {
      // Defensive trim — the SAX handler caps to maxExtractedChars, but a chunk that overflows
      // by N characters could leave the buffer slightly above the cap.
      result =
          new ExtractionResult(
              result.content().substring(0, policy.maxExtractedChars()),
              result.title(),
              result.mimeType(),
              result.author(),
              result.frontmatterMetadata());
    }

    OcrEvidenceBuilder ocrEvidence = new OcrEvidenceBuilder();
    OcrAttemptDecision ocrAttempt = evaluateOcrAttempt(file, detectedMime, result.content(), summary);
    if (ocrAttempt.skipReason() != null) {
      ocrEvidence.skip(ocrAttempt.skipReason());
    }
    if (ocrAttempt.shouldAttempt()) {
      ExtractionArtifact ocrArtifact =
          summary.mixedPdf()
              ? trySelectivePdfOcr(file, result, summary, ocrEvidence)
              : tryOcr(file, result, ocrEvidence);
      if (ocrArtifact != null) {
        return ocrArtifact;
      }
    }
    return ExtractionArtifact.full(result, policy, "tika-policy-structured", truncated)
        .withVisualExtractionEvidence(
            VisualExtractionEvidence.from(
                result.content(),
                summary,
                "structured",
                ocrConfig,
                false,
                OcrConfidenceExtractor.Summary.empty(),
                false,
                ocrEvidence.facts(truncated)));
  }

  private OcrAttemptDecision evaluateOcrAttempt(
      Path file, String detectedMime, String content, StructuredDocumentSummary summary) {
    if (!isOcrEligibleFile(file, detectedMime)) {
      log.debug("Skipping OCR for {}: file is not OCR-eligible (mime={})", file.getFileName(), detectedMime);
      return OcrAttemptDecision.skip(null);
    }
    int pageCount = summary == null ? 0 : summary.pageCount();
    if (ocrConfig.maxPages() != null && ocrConfig.maxPages() > 0 && pageCount > ocrConfig.maxPages()) {
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE));
      log.debug("Skipping OCR for {}: page count {} exceeds limit {}", file.getFileName(), pageCount, ocrConfig.maxPages());
      return OcrAttemptDecision.skip(OcrSkipReason.SIZE);
    }
    if (!imageWithinConfiguredGuards(file, detectedMime)) {
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE));
      log.debug("Skipping OCR for {}: image dimensions exceed configured OCR guards", file.getFileName());
      return OcrAttemptDecision.skip(OcrSkipReason.SIZE);
    }
    boolean hasMissingReadablePages = summary != null && summary.pagesMissingReadableText() > 0;
    if (!hasMissingReadablePages && TextQualityAnalyzer.computeQualityScore(content, pageCount) >= 0.3d) {
      log.debug("Skipping OCR for {}: structured text quality is already sufficient", file.getFileName());
      return OcrAttemptDecision.skip(OcrSkipReason.TEXTUAL);
    }
    if (!ocrConfig.enabled()) {
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.DISABLED));
      log.debug("Skipping OCR for {}: OCR config is disabled", file.getFileName());
      return OcrAttemptDecision.skip(OcrSkipReason.DISABLED);
    }
    String blockedReason = TikaOcrRuntime.blockedReason(ocrConfig);
    if (!blockedReason.isBlank()) {
      OcrSkipReason reason = OcrSkipReason.fromBlockedReason(blockedReason);
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(reason));
      log.debug("Skipping OCR for {}: {}", file.getFileName(), blockedReason);
      return OcrAttemptDecision.skip(reason);
    }
    log.debug(
        "Attempting OCR for {} (mime={}, pageCount={}, missingReadablePages={}, contentChars={})",
        file.getFileName(),
        detectedMime,
        pageCount,
        summary == null ? 0 : summary.pagesMissingReadableText(),
        content == null ? 0 : content.length());
    return OcrAttemptDecision.yes();
  }

  boolean shouldAttemptOcrForTesting(
      Path file, String detectedMime, String content, StructuredDocumentSummary summary) {
    return evaluateOcrAttempt(file, detectedMime, content, summary).shouldAttempt();
  }

  private boolean imageWithinConfiguredGuards(Path file, String detectedMime) {
    if (!isRasterImageFile(file, detectedMime)) {
      return true;
    }
    Integer maxDimension = ocrConfig.maxImageDimension();
    Integer maxPixels = ocrConfig.maxImagePixels();
    if ((maxDimension == null || maxDimension <= 0) && (maxPixels == null || maxPixels <= 0)) {
      return true;
    }
    ImageSize size = readImageSize(file);
    if (size == null) {
      return true;
    }
    if (maxDimension != null && maxDimension > 0 && Math.max(size.width(), size.height()) > maxDimension) {
      return false;
    }
    long pixels = (long) size.width() * (long) size.height();
    return maxPixels == null || maxPixels <= 0 || pixels <= maxPixels;
  }

  private static ImageSize readImageSize(Path file) {
    try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
      if (stream == null) {
        return null;
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) {
        return null;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        return new ImageSize(reader.getWidth(0), reader.getHeight(0));
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      log.debug("Could not read image dimensions for {}: {}", file, e.getMessage());
      return null;
    }
  }

  private ExtractionArtifact tryOcr(
      Path file, ExtractionResult baseline, OcrEvidenceBuilder ocrEvidence) {
    long startedAtNanos = System.nanoTime();
    try {
      StructuredContentExtractor.StructuredExtractionResult ocr =
          structuredExtractor.extractWithOcr(file, ocrConfig);
      ExtractionResult ocrResult = ocr.result();
      StructuredDocumentSummary ocrSummary = ocr.summary();
      if ((ocrResult.content() == null || ocrResult.content().isBlank())
          && isRasterImageFile(file, ocrResult.mimeType())) {
        ExtractionArtifact direct = tryDirectImageOcr(file, ocrResult, startedAtNanos, ocrEvidence);
        if (direct != null) {
          return direct;
        }
      }
      if ((ocrResult.content() == null || ocrResult.content().isBlank())
          && isPdfFile(file, ocrResult.mimeType())) {
        ExtractionArtifact renderedPdfOcr = tryRenderedPdfOcr(file, baseline, ocrSummary, ocrEvidence);
        if (renderedPdfOcr != null) {
          return renderedPdfOcr;
        }
      }
      long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
      ocrMetricCatalog.timeMs.record(elapsedMs, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
      double ocrQuality =
          TextQualityAnalyzer.computeQualityScore(ocrResult.content(), ocrSummary.pageCount());
      double baselineQuality = TextQualityAnalyzer.computeQualityScore(baseline.content());
      if (ocrResult.content() != null
          && !ocrResult.content().isBlank()
          && ocrQuality >= baselineQuality) {
        ocrMetricCatalog.succeededTotal.increment(OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
        if (isPdfFile(file, ocrResult.mimeType())) {
          ocrSummary = ocrCoveredPdfSummary(file, ocrSummary, ocrResult.content());
        }
        OcrConfidenceExtractor.Summary confidence =
            OcrConfidenceExtractor.extract(file, ocrConfig, log);
        return ExtractionArtifact.full(ocrResult, policy, OcrRoutingConfig.PARSER_ID, ocr.truncated())
            .withVisualExtractionEvidence(
                VisualExtractionEvidence.from(
                    ocrResult.content(),
                    ocrSummary,
                    "ocr_full",
                    ocrConfig,
                    true,
                    confidence,
                    false,
                    ocrEvidence.facts(ocr.truncated())));
      }
      ocrEvidence.skip(OcrSkipReason.TEXTUAL);
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.TEXTUAL));
      log.debug(
          "OCR did not improve extraction for {} (ocrQuality={}, baselineQuality={})",
          file.getFileName(),
          ocrQuality,
          baselineQuality);
    } catch (IOException | ExtractionException e) {
      log.debug("OCR extraction failed for {}: {}", file.getFileName(), e.getMessage());
      ExtractionArtifact direct =
          isPdfFile(file, baseline.mimeType())
              ? tryRenderedPdfOcr(file, baseline, StructuredDocumentSummary.empty(), ocrEvidence)
              : isRasterImageFile(file, baseline.mimeType())
                  ? tryDirectImageOcr(file, baseline, startedAtNanos, ocrEvidence)
                  : null;
      if (direct != null) {
        return direct;
      }
      ocrEvidence.skip(OcrSkipReason.UNKNOWN);
      ocrMetricCatalog.failedTotal.increment(
          OcrTags.OcrFailureTags.of(OcrRoutingConfig.ENGINE, e.getClass().getSimpleName()));
    }
    return null;
  }

  private ExtractionArtifact tryDirectImageOcr(
      Path file,
      ExtractionResult baseline,
      long startedAtNanos,
      OcrEvidenceBuilder ocrEvidence) {
    OcrConfidenceExtractor.TextResult direct =
        OcrConfidenceExtractor.extractPlainTextBounded(file, ocrConfig, log, policy.maxExtractedChars());
    if (direct.failureReason() != null) {
      ocrEvidence.skip(direct.failureReason());
    }
    String directText = direct.text();
    if (directText.isBlank()) {
      return null;
    }
    double directQuality = TextQualityAnalyzer.computeQualityScore(directText, 1);
    double baselineQuality = TextQualityAnalyzer.computeQualityScore(baseline.content());
    if (directQuality < baselineQuality) {
      return null;
    }
    long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    ocrMetricCatalog.timeMs.record(elapsedMs, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
    ocrMetricCatalog.succeededTotal.increment(OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
    ocrEvidence.fallback(OCR_FALLBACK_DIRECT_TESSERACT);
    ocrEvidence.truncated(direct.truncated());
    ExtractionResult result =
        new ExtractionResult(
            directText,
            baseline.title(),
            baseline.mimeType(),
            baseline.author(),
            baseline.frontmatterMetadata());
    StructuredDocumentSummary summary =
        new StructuredDocumentSummary(
            1, directText.length(), 1, 0, 0, Math.max(1, directText.split("\\R+").length), 0, 0, 0);
    OcrConfidenceExtractor.Summary confidence =
        OcrConfidenceExtractor.extract(file, ocrConfig, log);
    return ExtractionArtifact.full(result, policy, OcrRoutingConfig.PARSER_ID, direct.truncated())
        .withVisualExtractionEvidence(
            VisualExtractionEvidence.from(
                directText,
                summary,
                "ocr_full",
                ocrConfig,
                true,
                confidence,
                false,
                ocrEvidence.facts(direct.truncated())));
  }

  private ExtractionArtifact trySelectivePdfOcr(
      Path file,
      ExtractionResult baseline,
      StructuredDocumentSummary baselineSummary,
      OcrEvidenceBuilder ocrEvidence) {
    PdfVisualAnalyzer.PdfPageEvidence pageEvidence = PdfVisualAnalyzer.analyze(file);
    if (pageEvidence == null || !pageEvidence.mixed()) {
      return tryOcr(file, baseline, ocrEvidence);
    }
    long startedAtNanos = System.nanoTime();
    String baselineText = baseline.content() == null ? "" : baseline.content().stripTrailing();
    StringBuilder appended = new StringBuilder();
    List<OcrConfidenceExtractor.Summary> confidenceSummaries = new ArrayList<>();
    boolean truncated = false;
    try (PDDocument document = Loader.loadPDF(file.toFile())) {
      PDFRenderer renderer = new PDFRenderer(document);
      for (int pageIndex : pageEvidence.missingReadableTextPages()) {
        Path pageImage = Files.createTempFile("justsearch-ocr-page-", ".png");
        try {
          ImageIO.write(renderer.renderImageWithDPI(pageIndex, 160, ImageType.RGB), "png", pageImage.toFile());
          if (!imageWithinConfiguredGuards(pageImage, "image/png")) {
            ocrEvidence.skip(OcrSkipReason.SIZE);
            ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE));
            continue;
          }
          String pageText = "";
          try {
            StructuredContentExtractor.StructuredExtractionResult ocr =
                structuredExtractor.extractWithOcr(pageImage, ocrConfig);
            pageText = ocr.result().content();
          } catch (IOException | ExtractionException e) {
            log.debug("Tika page OCR failed for {} page {}: {}", file.getFileName(), pageIndex + 1, e.getMessage());
          }
          if (pageText == null || pageText.isBlank()) {
            int remaining = Math.max(0, policy.maxExtractedChars() - baselineText.length() - appended.length());
            OcrConfidenceExtractor.TextResult direct =
                OcrConfidenceExtractor.extractPlainTextBounded(pageImage, ocrConfig, log, remaining);
            if (direct.failureReason() != null) {
              ocrEvidence.skip(direct.failureReason());
            }
            pageText = direct.text();
            truncated = truncated || direct.truncated();
            if (!pageText.isBlank()) {
              ocrEvidence.fallback(OCR_FALLBACK_RENDERED_PDF);
            }
          }
          if (pageText != null && !pageText.isBlank()) {
            confidenceSummaries.add(OcrConfidenceExtractor.extract(pageImage, ocrConfig, log));
            truncated = appendOcrPageText(appended, pageText, pageIndex + 1, baselineText.length()) || truncated;
            if (baselineText.length() + appended.length() >= policy.maxExtractedChars()) {
              truncated = true;
              ocrEvidence.truncated(true);
              break;
            }
          }
        } finally {
          try {
            Files.deleteIfExists(pageImage);
          } catch (IOException ignored) {
            // Best-effort cleanup of a bounded temp image.
          }
        }
      }
      long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
      ocrMetricCatalog.timeMs.record(elapsedMs, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
      if (!appended.isEmpty()) {
        String merged = baselineText + appended;
        ExtractionResult mergedResult =
            new ExtractionResult(
                merged, baseline.title(), baseline.mimeType(), baseline.author(), baseline.frontmatterMetadata());
        double mergedQuality = TextQualityAnalyzer.computeQualityScore(merged, baselineSummary.pageCount());
        double baselineQuality = TextQualityAnalyzer.computeQualityScore(baseline.content(), baselineSummary.pageCount());
        if (mergedQuality >= baselineQuality) {
          ocrMetricCatalog.succeededTotal.increment(OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
          StructuredDocumentSummary mergedSummary =
              new StructuredDocumentSummary(
                  baselineSummary.pageCount(),
                  merged.length(),
                  baselineSummary.pageCount(),
                  0,
                  baselineSummary.headingCount(),
                  baselineSummary.paragraphCount(),
                  baselineSummary.tableCount(),
                  baselineSummary.listCount(),
                  baselineSummary.imagePageCount());
          return ExtractionArtifact.full(mergedResult, policy, OcrRoutingConfig.PARSER_ID, truncated)
              .withVisualExtractionEvidence(
                  VisualExtractionEvidence.from(
                      merged,
                      mergedSummary,
                      "ocr_selective",
                      ocrConfig,
                      true,
                      OcrConfidenceExtractor.aggregate(confidenceSummaries),
                      true,
                      ocrEvidence.facts(truncated)));
        }
      }
      ocrEvidence.skip(OcrSkipReason.TEXTUAL);
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.TEXTUAL));
    } catch (IOException e) {
      ocrEvidence.skip(OcrSkipReason.UNKNOWN);
      ocrMetricCatalog.failedTotal.increment(
          OcrTags.OcrFailureTags.of(OcrRoutingConfig.ENGINE, e.getClass().getSimpleName()));
      log.debug("Selective PDF OCR failed for {}: {}", file.getFileName(), e.getMessage());
    }
    return null;
  }

  private ExtractionArtifact tryRenderedPdfOcr(
      Path file,
      ExtractionResult baseline,
      StructuredDocumentSummary baselineSummary,
      OcrEvidenceBuilder ocrEvidence) {
    long startedAtNanos = System.nanoTime();
    String baselineText = baseline.content() == null ? "" : baseline.content().stripTrailing();
    StringBuilder text = new StringBuilder();
    List<OcrConfidenceExtractor.Summary> confidenceSummaries = new ArrayList<>();
    int pages = 0;
    boolean truncated = false;
    try (PDDocument document = Loader.loadPDF(file.toFile())) {
      PDFRenderer renderer = new PDFRenderer(document);
      pages = document.getNumberOfPages();
      for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
        Path pageImage = Files.createTempFile("justsearch-ocr-page-", ".png");
        try {
          ImageIO.write(renderer.renderImageWithDPI(pageIndex, 160, ImageType.RGB), "png", pageImage.toFile());
          if (!imageWithinConfiguredGuards(pageImage, "image/png")) {
            ocrEvidence.skip(OcrSkipReason.SIZE);
            ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE));
            continue;
          }
          int remaining = Math.max(0, policy.maxExtractedChars() - baselineText.length() - text.length());
          OcrConfidenceExtractor.TextResult direct =
              OcrConfidenceExtractor.extractPlainTextBounded(pageImage, ocrConfig, log, remaining);
          if (direct.failureReason() != null) {
            ocrEvidence.skip(direct.failureReason());
          }
          String pageText = direct.text();
          truncated = truncated || direct.truncated();
          if (pageText != null && !pageText.isBlank()) {
            ocrEvidence.fallback(OCR_FALLBACK_RENDERED_PDF);
            confidenceSummaries.add(OcrConfidenceExtractor.extract(pageImage, ocrConfig, log));
            truncated = appendOcrPageText(text, pageText, pageIndex + 1, baselineText.length()) || truncated;
            if (baselineText.length() + text.length() >= policy.maxExtractedChars()) {
              truncated = true;
              ocrEvidence.truncated(true);
              break;
            }
          }
        } finally {
          try {
            Files.deleteIfExists(pageImage);
          } catch (IOException ignored) {
            // Best-effort cleanup of a bounded temp image.
          }
        }
      }
      long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
      ocrMetricCatalog.timeMs.record(elapsedMs, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
      if (!text.isEmpty()) {
        String merged = baselineText + text;
        double mergedQuality = TextQualityAnalyzer.computeQualityScore(merged, Math.max(1, pages));
        double baselineQuality =
            TextQualityAnalyzer.computeQualityScore(
                baseline.content(), baselineSummary == null ? pages : baselineSummary.pageCount());
        if (mergedQuality >= baselineQuality) {
          ocrMetricCatalog.succeededTotal.increment(OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE));
          ExtractionResult result =
              new ExtractionResult(
                  merged, baseline.title(), baseline.mimeType(), baseline.author(), baseline.frontmatterMetadata());
          StructuredDocumentSummary summary =
              new StructuredDocumentSummary(
                  Math.max(1, pages),
                  merged.length(),
                  Math.max(1, pages),
                  0,
                  baselineSummary == null ? 0 : baselineSummary.headingCount(),
                  baselineSummary == null ? 0 : baselineSummary.paragraphCount(),
                  baselineSummary == null ? 0 : baselineSummary.tableCount(),
                  baselineSummary == null ? 0 : baselineSummary.listCount(),
                  baselineSummary == null ? 0 : baselineSummary.imagePageCount());
          summary = ocrCoveredPdfSummary(file, summary, merged);
          return ExtractionArtifact.full(result, policy, OcrRoutingConfig.PARSER_ID, truncated)
              .withVisualExtractionEvidence(
                  VisualExtractionEvidence.from(
                      merged,
                      summary,
                      "ocr_full",
                      ocrConfig,
                      true,
                      OcrConfidenceExtractor.aggregate(confidenceSummaries),
                      false,
                      ocrEvidence.facts(truncated)));
        }
      }
      ocrEvidence.skip(OcrSkipReason.TEXTUAL);
      ocrMetricCatalog.skippedTotal.increment(OcrTags.OcrSkipTags.of(OcrSkipReason.TEXTUAL));
    } catch (IOException e) {
      ocrEvidence.skip(OcrSkipReason.UNKNOWN);
      ocrMetricCatalog.failedTotal.increment(
          OcrTags.OcrFailureTags.of(OcrRoutingConfig.ENGINE, e.getClass().getSimpleName()));
      log.debug("Rendered PDF OCR fallback failed for {}: {}", file.getFileName(), e.getMessage());
    }
    return null;
  }

  private boolean appendOcrPageText(
      StringBuilder target, String pageText, int pageNumber, int baselineLength) {
    String block = "\n\n--- OCR page " + pageNumber + " ---\n" + pageText.strip() + "\n";
    int remaining = policy.maxExtractedChars() - baselineLength - target.length();
    if (remaining <= 0) {
      return true;
    }
    if (block.length() > remaining) {
      target.append(block, 0, remaining);
      return true;
    }
    target.append(block);
    return false;
  }

  private static StructuredDocumentSummary ocrCoveredPdfSummary(
      Path file, StructuredDocumentSummary base, String content) {
    StructuredDocumentSummary summary = base == null ? StructuredDocumentSummary.empty() : base;
    PdfVisualAnalyzer.PdfPageEvidence evidence = PdfVisualAnalyzer.analyze(file);
    int pages = Math.max(1, Math.max(summary.pageCount(), evidence == null ? 0 : evidence.pageCount()));
    int imagePages = evidence == null ? summary.imagePageCount() : evidence.imagePages().size();
    return new StructuredDocumentSummary(
        pages,
        content == null ? summary.textCharCount() : content.length(),
        pages,
        0,
        summary.headingCount(),
        summary.paragraphCount(),
        summary.tableCount(),
        summary.listCount(),
        Math.max(summary.imagePageCount(), imagePages));
  }

  private static boolean isOcrEligibleFile(Path file, String mimeType) {
    String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if ("application/pdf".equals(mime) || mime.startsWith("image/")) {
      return true;
    }
    String name =
        file == null || file.getFileName() == null
            ? ""
            : file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".pdf")
        || name.endsWith(".png")
        || name.endsWith(".jpg")
        || name.endsWith(".jpeg")
        || name.endsWith(".tif")
        || name.endsWith(".tiff")
        || name.endsWith(".bmp");
  }

  private static boolean isPdfFile(Path file, String mimeType) {
    String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if ("application/pdf".equals(mime)) {
      return true;
    }
    String name =
        file == null || file.getFileName() == null
            ? ""
            : file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".pdf");
  }

  private static boolean isRasterImageFile(Path file, String mimeType) {
    String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    if (mime.startsWith("image/")) {
      return true;
    }
    String name =
        file == null || file.getFileName() == null
            ? ""
            : file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".png")
        || name.endsWith(".jpg")
        || name.endsWith(".jpeg")
        || name.endsWith(".tif")
        || name.endsWith(".tiff")
        || name.endsWith(".bmp");
  }

  private record ImageSize(int width, int height) {}

  private record OcrAttemptDecision(boolean shouldAttempt, OcrSkipReason skipReason) {
    static OcrAttemptDecision yes() {
      return new OcrAttemptDecision(true, null);
    }

    static OcrAttemptDecision skip(OcrSkipReason reason) {
      return new OcrAttemptDecision(false, reason);
    }
  }

  private static final class OcrEvidenceBuilder {
    private boolean truncated;
    private String fallbackRoute;
    private OcrSkipReason skipReason;

    void truncated(boolean value) {
      truncated = truncated || value;
    }

    void fallback(String route) {
      if (route != null && !route.isBlank()) {
        fallbackRoute = route;
      }
    }

    void skip(OcrSkipReason reason) {
      if (reason != null && skipReason == null) {
        skipReason = reason;
      }
    }

    VisualExtractionEvidence.RoutingFacts facts(boolean artifactTruncated) {
      return VisualExtractionEvidence.RoutingFacts.of(
          truncated || artifactTruncated, fallbackRoute, skipReason);
    }
  }

  @Override
  public String detectMimeType(Path file) {
    try {
      return tika.detect(file);
    } catch (IOException e) {
      log.debug("MIME detection failed for {}: {}", file, e.getMessage());
      return "application/octet-stream";
    }
  }

}
