package io.justsearch.core.util;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.core.util.DocumentTypeDetector.DocumentType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DocumentTypeDetector utility class.
 *
 * <p>Tests document type detection from MIME types, filename patterns, and path extraction.
 */
@DisplayName("DocumentTypeDetector")
final class DocumentTypeDetectorTest {

  // ==========================================================================
  // detect() tests - MIME type based detection
  // ==========================================================================

  @Nested
  @DisplayName("detect()")
  class DetectTests {

    @Test
    @DisplayName("PDF mime type returns PDF_DOCUMENT")
    void pdfMimeType_returnsPdfDocument() {
      DocumentType result = DocumentTypeDetector.detect("application/pdf", List.of("doc.pdf"));
      assertEquals(DocumentType.PDF_DOCUMENT, result);
    }

    @Test
    @DisplayName("PDF with legal keyword returns LEGAL")
    void pdfWithLegalKeyword_returnsLegal() {
      DocumentType result = DocumentTypeDetector.detect("application/pdf", List.of("contract.pdf"));
      assertEquals(DocumentType.LEGAL, result);
    }

    @Test
    @DisplayName("PDF with agreement keyword returns LEGAL")
    void pdfWithAgreementKeyword_returnsLegal() {
      DocumentType result = DocumentTypeDetector.detect("application/pdf", List.of("service-agreement.pdf"));
      assertEquals(DocumentType.LEGAL, result);
    }

    @Test
    @DisplayName("JavaScript mime type returns CODE_FILE")
    void javascriptMimeType_returnsCodeFile() {
      DocumentType result = DocumentTypeDetector.detect("application/javascript", List.of());
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("JSON mime type returns CODE_FILE")
    void jsonMimeType_returnsCodeFile() {
      DocumentType result = DocumentTypeDetector.detect("application/json", List.of());
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("text/x-java returns CODE_FILE")
    void textXJava_returnsCodeFile() {
      DocumentType result = DocumentTypeDetector.detect("text/x-java", List.of());
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("text/x-python returns CODE_FILE")
    void textXPython_returnsCodeFile() {
      DocumentType result = DocumentTypeDetector.detect("text/x-python", List.of());
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("Office document mime types return OFFICE_DOCUMENT")
    void officeMimeTypes_returnsOfficeDocument() {
      assertEquals(DocumentType.OFFICE_DOCUMENT,
          DocumentTypeDetector.detect("application/vnd.openxmlformats-officedocument.wordprocessingml.document", List.of()));
      assertEquals(DocumentType.OFFICE_DOCUMENT,
          DocumentTypeDetector.detect("application/msword", List.of()));
      assertEquals(DocumentType.OFFICE_DOCUMENT,
          DocumentTypeDetector.detect("application/vnd.ms-excel", List.of()));
      assertEquals(DocumentType.OFFICE_DOCUMENT,
          DocumentTypeDetector.detect("application/vnd.oasis.opendocument.text", List.of()));
    }

    @Test
    @DisplayName("Markdown mime type returns MARKDOWN")
    void markdownMimeType_returnsMarkdown() {
      assertEquals(DocumentType.MARKDOWN, DocumentTypeDetector.detect("text/markdown", List.of()));
      assertEquals(DocumentType.MARKDOWN, DocumentTypeDetector.detect("text/x-markdown", List.of()));
    }

    @Test
    @DisplayName("Email mime type returns EMAIL")
    void emailMimeType_returnsEmail() {
      assertEquals(DocumentType.EMAIL, DocumentTypeDetector.detect("message/rfc822", List.of()));
    }

    @Test
    @DisplayName("null mime type falls back to filename")
    void nullMimeType_fallsBackToFilename() {
      DocumentType result = DocumentTypeDetector.detect(null, List.of("app.java"));
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("blank mime type falls back to filename")
    void blankMimeType_fallsBackToFilename() {
      DocumentType result = DocumentTypeDetector.detect("  ", List.of("readme.md"));
      assertEquals(DocumentType.MARKDOWN, result);
    }

    @Test
    @DisplayName("text/plain falls back to filename detection")
    void textPlain_fallsBackToFilename() {
      DocumentType result = DocumentTypeDetector.detect("text/plain", List.of("chapter1.txt"));
      assertEquals(DocumentType.BOOK_OR_LITERATURE, result);
    }

    @Test
    @DisplayName("unknown mime type returns GENERAL")
    void unknownMimeType_returnsGeneral() {
      DocumentType result = DocumentTypeDetector.detect("application/octet-stream", List.of());
      assertEquals(DocumentType.GENERAL, result);
    }
  }

  // ==========================================================================
  // detectFromFilename() tests - filename pattern detection
  // ==========================================================================

  @Nested
  @DisplayName("detectFromFilename()")
  class DetectFromFilenameTests {

    @Test
    @DisplayName("Java file returns CODE_FILE")
    void javaFile_returnsCodeFile() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("Main.java"));
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("Python file returns CODE_FILE")
    void pythonFile_returnsCodeFile() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("script.py"));
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("TypeScript file returns CODE_FILE")
    void typescriptFile_returnsCodeFile() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("component.ts"));
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("Go file returns CODE_FILE")
    void goFile_returnsCodeFile() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("main.go"));
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("Rust file returns CODE_FILE")
    void rustFile_returnsCodeFile() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("lib.rs"));
      assertEquals(DocumentType.CODE_FILE, result);
    }

    @Test
    @DisplayName("Markdown file returns MARKDOWN")
    void markdownFile_returnsMarkdown() {
      assertEquals(DocumentType.MARKDOWN, DocumentTypeDetector.detectFromFilename(List.of("README.md")));
      assertEquals(DocumentType.MARKDOWN, DocumentTypeDetector.detectFromFilename(List.of("guide.markdown")));
    }

    @Test
    @DisplayName("book keyword returns BOOK_OR_LITERATURE")
    void bookKeyword_returnsBookOrLiterature() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("my-book.pdf"));
      assertEquals(DocumentType.BOOK_OR_LITERATURE, result);
    }

    @Test
    @DisplayName("chapter keyword returns BOOK_OR_LITERATURE")
    void chapterKeyword_returnsBookOrLiterature() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("chapter-3.txt"));
      assertEquals(DocumentType.BOOK_OR_LITERATURE, result);
    }

    @Test
    @DisplayName("contract keyword returns LEGAL")
    void contractKeyword_returnsLegal() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("service-contract.docx"));
      assertEquals(DocumentType.LEGAL, result);
    }

    @Test
    @DisplayName("terms keyword returns LEGAL")
    void termsKeyword_returnsLegal() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("terms-of-service.txt"));
      assertEquals(DocumentType.LEGAL, result);
    }

    @Test
    @DisplayName("empty list returns GENERAL")
    void emptyList_returnsGeneral() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of());
      assertEquals(DocumentType.GENERAL, result);
    }

    @Test
    @DisplayName("null list returns GENERAL")
    void nullList_returnsGeneral() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(null);
      assertEquals(DocumentType.GENERAL, result);
    }

    @Test
    @DisplayName("unknown extension returns GENERAL")
    void unknownExtension_returnsGeneral() {
      DocumentType result = DocumentTypeDetector.detectFromFilename(List.of("data.xyz"));
      assertEquals(DocumentType.GENERAL, result);
    }
  }

  // ==========================================================================
  // extractFilename() tests - path extraction
  // ==========================================================================

  @Nested
  @DisplayName("extractFilename()")
  class ExtractFilenameTests {

    @Test
    @DisplayName("full Unix path extracts filename")
    void fullUnixPath_extractsFilename() {
      String result = DocumentTypeDetector.extractFilename("/home/user/docs/report.pdf");
      assertEquals("report.pdf", result);
    }

    @Test
    @DisplayName("full Windows path extracts filename")
    void fullWindowsPath_extractsFilename() {
      String result = DocumentTypeDetector.extractFilename("C:\\Users\\Admin\\Documents\\report.pdf");
      assertEquals("report.pdf", result);
    }

    @Test
    @DisplayName("filename only returns as-is")
    void filenameOnly_returnsAsIs() {
      String result = DocumentTypeDetector.extractFilename("document.txt");
      assertEquals("document.txt", result);
    }

    @Test
    @DisplayName("null input returns unknown")
    void nullInput_returnsUnknown() {
      String result = DocumentTypeDetector.extractFilename(null);
      assertEquals("unknown", result);
    }

    @Test
    @DisplayName("blank input returns unknown")
    void blankInput_returnsUnknown() {
      String result = DocumentTypeDetector.extractFilename("   ");
      assertEquals("unknown", result);
    }

    @Test
    @DisplayName("empty input returns unknown")
    void emptyInput_returnsUnknown() {
      String result = DocumentTypeDetector.extractFilename("");
      assertEquals("unknown", result);
    }

    @Test
    @DisplayName("path with trailing slash extracts empty filename")
    void pathWithTrailingSlash_handlesGracefully() {
      // Path.of("/home/user/").getFileName() returns "user"
      String result = DocumentTypeDetector.extractFilename("/home/user/");
      assertNotNull(result);
      assertFalse(result.isBlank());
    }

    @Test
    @DisplayName("deep nested path extracts filename")
    void deepNestedPath_extractsFilename() {
      String result = DocumentTypeDetector.extractFilename("/a/b/c/d/e/f/g/file.txt");
      assertEquals("file.txt", result);
    }
  }
}
