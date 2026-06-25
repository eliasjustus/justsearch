/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.services.LanguageUtils;
import io.justsearch.indexing.extraction.StructuredDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * Extracts structured text content from files using Tika's SAX event stream.
 *
 * <p>Unlike {@link ContentExtractor} which uses {@code Tika.parseToString()} (discarding all
 * document structure), this extractor captures headings, tables, page boundaries, and lists via a
 * custom SAX handler, then serializes to search-optimized annotated text.
 *
 * <p>Falls back to {@link ContentExtractor} on any structured parsing failure, ensuring zero
 * regression risk.
 *
 * <p>This class is thread-safe — the {@link AutoDetectParser} and {@link Tika} instances are
 * thread-safe, and the SAX handler is created per-parse.
 */
public final class StructuredContentExtractor implements ContentExtractorProvider {

  private static final Logger log = LoggerFactory.getLogger(StructuredContentExtractor.class);

  /** Default maximum content length (10MB of text). */
  private static final int DEFAULT_MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

  /** Maximum file size to attempt extraction (100MB). */
  private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

  /** Maximum file size for Office documents (30MB). POI OOM risk. */
  private static final long MAX_OFFICE_FILE_SIZE = 30 * 1024 * 1024;

  private final AutoDetectParser parser;
  private final Tika tika; // for detectMimeType only
  private final int maxContentLength;

  public StructuredContentExtractor() {
    this(DEFAULT_MAX_CONTENT_LENGTH);
  }

  public StructuredContentExtractor(int maxContentLength) {
    this.parser = new AutoDetectParser();
    this.tika = new Tika();
    this.maxContentLength = maxContentLength;
  }

  @Override
  public ContentExtractor.ExtractionResult extract(Path file)
      throws IOException, ContentExtractor.ExtractionException {
    return extractWithStatus(file).result();
  }

  /**
   * Extraction result paired with the SAX handler's authoritative truncation flag. Callers that
   * need to surface truncation as an outcome (policy-driven extraction → SUCCESS_PARTIAL ledger
   * events) must use this method rather than post-checking {@code result.content().length()}; the
   * length-based check is structurally unable to fire when the input chunk boundary aligns exactly
   * with the cap.
   */
  public StructuredExtractionResult extractWithStatus(Path file)
      throws IOException, ContentExtractor.ExtractionException {
    Objects.requireNonNull(file, "file");
    validateFileForExtraction(file);

    if (Files.size(file) == 0) {
      return new StructuredExtractionResult(
          new ContentExtractor.ExtractionResult("", null, "text/plain"),
          false,
          StructuredDocumentSummary.empty());
    }

    try {
      return extractStructured(file, parseContextWithMarkedPdfContent());
    } catch (Exception e) {
      log.warn(
          "Structured extraction failed for {}, falling back to flat extraction",
          file.getFileName(),
          e);
      // Flat fallback can't observe SAX-level truncation; report not-truncated. This path is rare
      // (only fires when structured parsing throws) and the flat extractor enforces its own cap.
      return new StructuredExtractionResult(
          new ContentExtractor(maxContentLength).extract(file),
          false,
          StructuredDocumentSummary.empty());
    }
  }

  public StructuredExtractionResult extractWithOcr(Path file, OcrRoutingConfig ocrConfig)
      throws IOException, ContentExtractor.ExtractionException {
    Objects.requireNonNull(file, "file");
    validateFileForExtraction(file);

    if (Files.size(file) == 0) {
      return new StructuredExtractionResult(
          new ContentExtractor.ExtractionResult("", null, "text/plain"),
          false,
          StructuredDocumentSummary.empty());
    }

    try {
      return extractStructured(
          file, parseContextWithOcr(ocrConfig == null ? OcrRoutingConfig.defaults() : ocrConfig));
    } catch (Exception e) {
      throw new ContentExtractor.ExtractionException(
          "OCR extraction failed for: " + file.getFileName(), e);
    }
  }

  private void validateFileForExtraction(Path file)
      throws IOException, ContentExtractor.ExtractionException {
    if (!Files.exists(file)) {
      throw new IOException("File does not exist: " + file);
    }
    if (!Files.isReadable(file)) {
      throw new IOException("File is not readable: " + file);
    }

    long fileSize = Files.size(file);
    if (fileSize > MAX_FILE_SIZE) {
      log.warn("File too large for extraction: {} ({} bytes)", file, fileSize);
      throw new ContentExtractor.ExtractionException(
          "File too large: " + fileSize + " bytes (max: " + MAX_FILE_SIZE + ")");
    }

    if (fileSize > MAX_OFFICE_FILE_SIZE && ContentExtractor.isOfficeMimeType(tika.detect(file))) {
      log.warn("Office file too large for extraction: {} ({} bytes)", file, fileSize);
      throw new ContentExtractor.ExtractionException(
          "Office file too large: " + fileSize + " bytes (max: " + MAX_OFFICE_FILE_SIZE + ")");
    }
  }

  /** Pairs an {@link ContentExtractor.ExtractionResult} with the SAX handler's truncation flag. */
  public record StructuredExtractionResult(
      ContentExtractor.ExtractionResult result,
      boolean truncated,
      StructuredDocumentSummary summary) {
    public int pageCount() {
      return summary == null ? 0 : summary.pageCount();
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

  private StructuredExtractionResult extractStructured(Path file, ParseContext parseContext)
      throws IOException, TikaException, SAXException {
    log.debug("Structured extraction from: {} ({} bytes)", file.getFileName(), Files.size(file));

    StructuredContentHandler handler = new StructuredContentHandler(maxContentLength);
    Metadata metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());

    // Enable marked content extraction for tagged PDFs — this enables table, heading,
    // and list extraction for the subset of PDFs that have accessibility tags.
    // Falls back gracefully for untagged PDFs (no additional cost).
    // PDFParserConfig is in tika-parsers-standard (runtimeOnly), so we configure via reflection
    // to avoid a compile-time dependency.
    try (InputStream is = Files.newInputStream(file)) {
      parser.parse(is, handler, metadata, parseContext);
    }

    StructuredDocument doc = handler.getDocument();

    // Remove repeated headers/footers for multi-page documents
    doc = doc.removeHeadersFooters();

    String content = doc.toAnnotatedText();
    String mimeType = metadata.get(Metadata.CONTENT_TYPE);
    String title = metadata.get(TikaCoreProperties.TITLE);

    // Fallback: YAML frontmatter title (same as ContentExtractor)
    Map<String, String> frontmatterMeta = Map.of();
    if (content != null && content.startsWith("---")) {
      if (title == null) {
        title = ContentExtractor.extractFrontmatterTitle(content);
      }
      frontmatterMeta = LanguageUtils.extractFrontmatterMetadata(content);
    }

    log.debug(
        "Structured extraction: {} chars, {} elements, {} pages from {} (type: {})",
        content.length(),
        doc.elements().size(),
        doc.pageCount(),
        file.getFileName(),
        mimeType);

    return new StructuredExtractionResult(
        new ContentExtractor.ExtractionResult(content, title, mimeType, null, frontmatterMeta),
        handler.isLimitReached(),
        StructuredDocumentSummary.fromDocument(doc));
  }

  private static ParseContext parseContextWithMarkedPdfContent() {
    ParseContext parseContext = new ParseContext();
    configurePdfMarkedContent(parseContext);
    configureTesseractSkipOcr(parseContext);
    return parseContext;
  }

  private static ParseContext parseContextWithOcr(OcrRoutingConfig config) {
    ParseContext parseContext = new ParseContext();
    configurePdfOcrOnly(parseContext);
    configureTesseractOcr(parseContext, config);
    return parseContext;
  }

  /**
   * Configures PDF marked content extraction via reflection. PDFParserConfig is in
   * tika-parsers-standard (runtimeOnly dependency), so we avoid a compile-time import.
   */
  @SuppressWarnings("unchecked")
  private static void configurePdfMarkedContent(ParseContext parseContext) {
    try {
      Class<?> configClass = Class.forName("org.apache.tika.parser.pdf.PDFParserConfig");
      Object config = configClass.getDeclaredConstructor().newInstance();
      configClass
          .getMethod("setExtractMarkedContent", boolean.class)
          .invoke(config, true);
      // ParseContext.set(Class<T>, T) — use raw types to match
      parseContext
          .getClass()
          .getMethod("set", Class.class, Object.class)
          .invoke(parseContext, configClass, config);
      log.debug("PDF marked content extraction enabled");
    } catch (Exception e) {
      log.debug("Could not enable PDF marked content extraction: {}", e.getMessage());
      // Non-fatal: structured extraction still works for page boundaries and paragraphs
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void configurePdfOcrOnly(ParseContext parseContext) {
    try {
      Class<?> configClass = Class.forName("org.apache.tika.parser.pdf.PDFParserConfig");
      Object config = configClass.getDeclaredConstructor().newInstance();
      Class<?> strategyClass = Class.forName("org.apache.tika.parser.pdf.PDFParserConfig$OCR_STRATEGY");
      Object strategy = Enum.valueOf((Class<Enum>) strategyClass.asSubclass(Enum.class), "OCR_ONLY");
      configClass.getMethod("setOcrStrategy", strategyClass).invoke(config, strategy);
      invokeIfPresent(configClass, config, "setExtractInlineImages", boolean.class, true);
      parseContext
          .getClass()
          .getMethod("set", Class.class, Object.class)
          .invoke(parseContext, configClass, config);
      log.debug("PDF OCR-only extraction enabled");
    } catch (Exception e) {
      log.debug("Could not enable PDF OCR strategy: {}", e.getMessage());
    }
  }

  private static void configureTesseractOcr(ParseContext parseContext, OcrRoutingConfig config) {
    try {
      Class<?> configClass = Class.forName("org.apache.tika.parser.ocr.TesseractOCRConfig");
      Object tesseract = configClass.getDeclaredConstructor().newInstance();
      invokeIfPresent(configClass, tesseract, "setLanguage", String.class, config.tikaLanguage());
      invokeIfPresent(configClass, tesseract, "setPageSegMode", String.class, "6");
      invokeIfPresent(configClass, tesseract, "setOutputType", String.class, "txt");
      invokeIfPresent(configClass, tesseract, "setInlineContent", boolean.class, true);
      invokeIfPresent(configClass, tesseract, "setTimeoutSeconds", int.class, config.tikaTimeoutSeconds());
      invokeIfPresent(configClass, tesseract, "setTimeout", int.class, config.tikaTimeoutSeconds());
      parseContext
          .getClass()
          .getMethod("set", Class.class, Object.class)
          .invoke(parseContext, configClass, tesseract);

      TikaOcrRuntime.RuntimePaths runtime = TikaOcrRuntime.resolve();
      if (runtime.available()) {
        Class<?> parserClass = Class.forName("org.apache.tika.parser.ocr.TesseractOCRParser");
        Object parser = parserClass.getDeclaredConstructor().newInstance();
        if (runtime.executableDirectory() != null) {
          invokeIfPresent(
              parserClass,
              parser,
              "setTesseractPath",
              String.class,
              runtime.executableDirectory().toString());
        }
        if (runtime.tessdataDirectory() != null) {
          invokeIfPresent(
              parserClass,
              parser,
              "setTessdataPath",
              String.class,
              runtime.tessdataDirectory().toString());
        }
        invokeIfPresent(parserClass, parser, "setLanguage", String.class, config.tikaLanguage());
        invokeIfPresent(parserClass, parser, "setPageSegMode", String.class, "6");
        invokeIfPresent(parserClass, parser, "setOutputType", String.class, "txt");
        invokeIfPresent(parserClass, parser, "setInlineContent", boolean.class, true);
        invokeIfPresent(parserClass, parser, "setTimeout", int.class, config.tikaTimeoutSeconds());
        parseContext
            .getClass()
            .getMethod("set", Class.class, Object.class)
            .invoke(parseContext, parserClass, parser);
      }
      log.debug("Tesseract OCR configured");
    } catch (Exception e) {
      log.debug("Could not configure Tesseract OCR: {}", e.getMessage());
    }
  }

  private static void configureTesseractSkipOcr(ParseContext parseContext) {
    try {
      Class<?> configClass = Class.forName("org.apache.tika.parser.ocr.TesseractOCRConfig");
      Object tesseract = configClass.getDeclaredConstructor().newInstance();
      invokeIfPresent(configClass, tesseract, "setSkipOcr", boolean.class, true);
      invokeIfPresent(configClass, tesseract, "setSkipOCR", boolean.class, true);
      parseContext
          .getClass()
          .getMethod("set", Class.class, Object.class)
          .invoke(parseContext, configClass, tesseract);
      Class<?> parserClass = Class.forName("org.apache.tika.parser.ocr.TesseractOCRParser");
      Object parser = parserClass.getDeclaredConstructor().newInstance();
      invokeIfPresent(parserClass, parser, "setSkipOCR", boolean.class, true);
      parseContext
          .getClass()
          .getMethod("set", Class.class, Object.class)
          .invoke(parseContext, parserClass, parser);
    } catch (Exception e) {
      log.debug("Could not disable first-pass Tesseract OCR: {}", e.getMessage());
    }
  }

  private static void invokeIfPresent(
      Class<?> targetClass, Object target, String method, Class<?> parameterType, Object value) {
    try {
      targetClass.getMethod(method, parameterType).invoke(target, value);
    } catch (ReflectiveOperationException ignored) {
      // Tika version differences are handled by configuring only supported setters.
    }
  }
}
