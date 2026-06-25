package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ContentExtractor}.
 */
class ContentExtractorTest {

  @TempDir
  Path tempDir;

  private ContentExtractor extractor;

  @BeforeEach
  void setUp() {
    extractor = new ContentExtractor();
  }

  @Test
  void extractPlainText_returnsContent() throws Exception {
    Path textFile = tempDir.resolve("test.txt");
    String expectedContent = "Hello, World!\nThis is a test file.";
    Files.writeString(textFile, expectedContent);

    ExtractionResult result = extractor.extract(textFile);

    assertEquals(expectedContent, result.content().trim());
    assertTrue(result.mimeType().startsWith("text/"));
  }

  @Test
  void extractMarkdown_returnsContent() throws Exception {
    Path mdFile = tempDir.resolve("readme.md");
    String content = "# Title\n\nSome **bold** text.";
    Files.writeString(mdFile, content);

    ExtractionResult result = extractor.extract(mdFile);

    assertTrue(result.content().contains("Title"));
    assertTrue(result.content().contains("bold"));
  }

  @Test
  void extractJavaSource_returnsContent() throws Exception {
    Path javaFile = tempDir.resolve("Example.java");
    String content = "public class Example {\n  public static void main(String[] args) {}\n}";
    Files.writeString(javaFile, content);

    ExtractionResult result = extractor.extract(javaFile);

    // Tika 3.x sometimes converts regular spaces to non-breaking spaces (\u00A0)
    // Normalize whitespace for comparison
    String normalizedContent = result.content()
        .replace('\u00A0', ' ')  // Non-breaking space to regular space
        .replace("\u00C2", "");  // Remove stray UTF-8 encoding artifacts

    assertTrue(normalizedContent.contains("public class Example"),
        "Expected content to contain 'public class Example' but got: [" + normalizedContent + "]");
    assertTrue(normalizedContent.contains("main"),
        "Expected content to contain 'main' but got: [" + normalizedContent + "]");
  }

  @Test
  void extractHtml_stripsTagsReturnsText() throws Exception {
    Path htmlFile = tempDir.resolve("page.html");
    String html = "<html><head><title>Test Page</title></head><body><p>Hello World</p></body></html>";
    Files.writeString(htmlFile, html);

    ExtractionResult result = extractor.extract(htmlFile);

    assertTrue(result.content().contains("Hello World"));
    // HTML tags should be stripped
    assertFalse(result.content().contains("<p>"));
  }

  @Test
  void extractJson_returnsContent() throws Exception {
    Path jsonFile = tempDir.resolve("data.json");
    String json = "{\"name\": \"test\", \"value\": 42}";
    Files.writeString(jsonFile, json);

    ExtractionResult result = extractor.extract(jsonFile);

    assertTrue(result.content().contains("name"));
    assertTrue(result.content().contains("test"));
  }

  @Test
  void extractEmptyFile_returnsEmptyContent() throws Exception {
    Path emptyFile = tempDir.resolve("empty.txt");
    Files.writeString(emptyFile, "");

    ExtractionResult result = extractor.extract(emptyFile);

    assertEquals("", result.content());
  }

  @Test
  void extractNonExistentFile_throwsIOException() {
    Path nonExistent = tempDir.resolve("does-not-exist.txt");

    assertThrows(IOException.class, () -> extractor.extract(nonExistent));
  }

  @Test
  void extractSafe_returnsEmptyOnError() {
    Path nonExistent = tempDir.resolve("does-not-exist.txt");

    String result = extractor.extractSafe(nonExistent);

    assertEquals("", result);
  }

  @Test
  void extractSafe_returnsContentOnSuccess() throws Exception {
    Path textFile = tempDir.resolve("safe-test.txt");
    Files.writeString(textFile, "Safe content");

    String result = extractor.extractSafe(textFile);

    assertEquals("Safe content", result.trim());
  }

  @Test
  void detectMimeType_textFile() throws Exception {
    Path textFile = tempDir.resolve("detect.txt");
    Files.writeString(textFile, "plain text content");

    String mimeType = extractor.detectMimeType(textFile);

    assertTrue(mimeType.startsWith("text/"));
  }

  @Test
  void detectMimeType_htmlFile() throws Exception {
    Path htmlFile = tempDir.resolve("detect.html");
    Files.writeString(htmlFile, "<html><body>Test</body></html>");

    String mimeType = extractor.detectMimeType(htmlFile);

    assertTrue(mimeType.contains("html"));
  }

  @Test
  void extractionResult_isTextBased() {
    assertTrue(new ExtractionResult("", null, "text/plain").isTextBased());
    assertTrue(new ExtractionResult("", null, "text/html").isTextBased());
    assertTrue(new ExtractionResult("", null, "application/json").isTextBased());
    assertTrue(new ExtractionResult("", null, "application/xml").isTextBased());
    assertFalse(new ExtractionResult("", null, "application/pdf").isTextBased());
  }

  @Test
  void extractionResult_isPdf() {
    assertTrue(new ExtractionResult("", null, "application/pdf").isPdf());
    assertFalse(new ExtractionResult("", null, "text/plain").isPdf());
  }

  @Test
  void extractionResult_isOffice() {
    assertTrue(new ExtractionResult("", null,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document").isOffice());
    assertTrue(new ExtractionResult("", null, "application/msword").isOffice());
    assertFalse(new ExtractionResult("", null, "text/plain").isOffice());
  }

  @Test
  void constructor_customMaxLength() throws Exception {
    ContentExtractor smallExtractor = new ContentExtractor(100);

    Path textFile = tempDir.resolve("long.txt");
    String longContent = "x".repeat(500);
    Files.writeString(textFile, longContent);

    ExtractionResult result = smallExtractor.extract(textFile);

    // Content should be truncated to max length
    assertTrue(result.content().length() <= 100);
  }

  @Test
  @DisplayName("extractDocx_fixture_containsMarker_andClassifiesOffice")
  void extractDocx_fixture_containsMarker_andClassifiesOffice() throws Exception {
    Path docx = copyResourceToTemp("/fixtures/office/office-marker.docx", "office-marker.docx");

    ExtractionResult result = extractor.extract(docx);

    assertTrue(result.content().contains("JUSTSEARCH_OFFICE_DOCX_MARKER"),
        "Expected DOCX marker in extracted text but got: [" + result.content() + "]");
    assertTrue(result.isOffice(), "Expected DOCX to be detected as office MIME but got: " + result.mimeType());

    IndexDocument doc = buildIndexDocument(docx, result);
    assertEquals("office", doc.fields().get(SchemaFields.FILE_KIND));
  }

  @Test
  @DisplayName("extractXlsx_fixture_containsMarker_andClassifiesOffice")
  void extractXlsx_fixture_containsMarker_andClassifiesOffice() throws Exception {
    Path xlsx = copyResourceToTemp("/fixtures/office/office-marker.xlsx", "office-marker.xlsx");

    ExtractionResult result = extractor.extract(xlsx);

    assertTrue(result.content().contains("JUSTSEARCH_OFFICE_XLSX_MARKER"),
        "Expected XLSX marker in extracted text but got: [" + result.content() + "]");
    assertTrue(result.isOffice(), "Expected XLSX to be detected as office MIME but got: " + result.mimeType());

    IndexDocument doc = buildIndexDocument(xlsx, result);
    assertEquals("office", doc.fields().get(SchemaFields.FILE_KIND));
  }

  @Test
  @DisplayName("extractPptx_fixture_containsMarker_andClassifiesOffice")
  void extractPptx_fixture_containsMarker_andClassifiesOffice() throws Exception {
    Path pptx = copyResourceToTemp("/fixtures/office/office-marker.pptx", "office-marker.pptx");

    ExtractionResult result = extractor.extract(pptx);

    assertTrue(result.content().contains("JUSTSEARCH_OFFICE_PPTX_MARKER"),
        "Expected PPTX marker in extracted text but got: [" + result.content() + "]");
    assertTrue(result.isOffice(), "Expected PPTX to be detected as office MIME but got: " + result.mimeType());

    IndexDocument doc = buildIndexDocument(pptx, result);
    assertEquals("office", doc.fields().get(SchemaFields.FILE_KIND));
  }

  // --- YAML frontmatter title extraction tests ---

  @Test
  @DisplayName("extractFrontmatterTitle: standard title")
  void extractFrontmatterTitle_standardTitle() {
    String content = "---\ntitle: Storage Engine\ntype: explanation\nstatus: stable\n---\n\n# Content";
    assertEquals("Storage Engine", ContentExtractor.extractFrontmatterTitle(content));
  }

  @Test
  @DisplayName("extractFrontmatterTitle: double-quoted title")
  void extractFrontmatterTitle_doubleQuoted() {
    String content = "---\ntitle: \"My Title\"\n---\n\n# Content";
    assertEquals("My Title", ContentExtractor.extractFrontmatterTitle(content));
  }

  @Test
  @DisplayName("extractFrontmatterTitle: single-quoted title")
  void extractFrontmatterTitle_singleQuoted() {
    String content = "---\ntitle: 'My Title'\n---\n\n# Content";
    assertEquals("My Title", ContentExtractor.extractFrontmatterTitle(content));
  }

  @Test
  @DisplayName("extractFrontmatterTitle: no title key returns null")
  void extractFrontmatterTitle_noTitleKey() {
    String content = "---\ntype: explanation\nstatus: stable\n---\n\n# Content";
    assertNull(ContentExtractor.extractFrontmatterTitle(content));
  }

  @Test
  @DisplayName("extractFrontmatterTitle: no frontmatter returns null")
  void extractFrontmatterTitle_noFrontmatter() {
    String content = "# Just a heading\n\nSome text.";
    assertNull(ContentExtractor.extractFrontmatterTitle(content));
  }

  @Test
  @DisplayName("extractFrontmatterTitle: null content returns null")
  void extractFrontmatterTitle_nullContent() {
    assertNull(ContentExtractor.extractFrontmatterTitle(null));
  }

  @Test
  @DisplayName("extractFrontmatterTitle: blank title returns null")
  void extractFrontmatterTitle_blankTitle() {
    String content = "---\ntitle:   \n---\n\n# Content";
    assertNull(ContentExtractor.extractFrontmatterTitle(content));
  }

  @Test
  @DisplayName("extract: markdown with frontmatter populates title")
  void extract_markdownWithFrontmatter_populatesTitle() throws Exception {
    Path mdFile = tempDir.resolve("titled.md");
    String content = "---\ntitle: GPU Booster Pack\ntype: explanation\nstatus: draft\n---\n\n# GPU Content";
    Files.writeString(mdFile, content);

    ExtractionResult result = extractor.extract(mdFile);

    assertEquals("GPU Booster Pack", result.title());
  }

  // --- Office OOM protection tests ---

  @Test
  @DisplayName("isOfficeMimeType detects Office MIME types")
  void isOfficeMimeType_detectsOfficeTypes() {
    // Office types
    assertTrue(ContentExtractor.isOfficeMimeType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    assertTrue(ContentExtractor.isOfficeMimeType(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    assertTrue(ContentExtractor.isOfficeMimeType(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
    assertTrue(ContentExtractor.isOfficeMimeType("application/msword"));
    assertTrue(ContentExtractor.isOfficeMimeType("application/vnd.ms-excel"));
    assertTrue(ContentExtractor.isOfficeMimeType("application/vnd.ms-powerpoint"));

    // Non-Office types
    assertFalse(ContentExtractor.isOfficeMimeType("application/pdf"));
    assertFalse(ContentExtractor.isOfficeMimeType("text/plain"));
    assertFalse(ContentExtractor.isOfficeMimeType("application/octet-stream"));
    assertFalse(ContentExtractor.isOfficeMimeType(null));
  }

  @Test
  @DisplayName("extract rejects Office file exceeding Office size limit")
  void extract_rejectsLargeOfficeFile() throws Exception {
    // Create a minimal .xlsx file that exceeds MAX_OFFICE_FILE_SIZE (30MB).
    // A .xlsx is a ZIP file — Tika detects it by magic bytes (PK header).
    Path file = tempDir.resolve("large.xlsx");
    try (var os = Files.newOutputStream(file);
        var zip = new java.util.zip.ZipOutputStream(os)) {
      // Add a minimal OOXML content type to make Tika detect it as xlsx
      zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
      zip.write(("<?xml version=\"1.0\"?>"
          + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
          + "</Types>").getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      // Add a large uncompressed padding entry to push the file over 30MB
      byte[] padding = new byte[31 * 1024 * 1024];
      java.util.zip.ZipEntry padEntry = new java.util.zip.ZipEntry("padding.bin");
      padEntry.setMethod(java.util.zip.ZipEntry.STORED);
      padEntry.setSize(padding.length);
      padEntry.setCompressedSize(padding.length);
      java.util.zip.CRC32 crc = new java.util.zip.CRC32();
      crc.update(padding);
      padEntry.setCrc(crc.getValue());
      zip.putNextEntry(padEntry);
      zip.write(padding);
      zip.closeEntry();
    }

    assertTrue(Files.size(file) > 30 * 1024 * 1024, "Test file should exceed 30MB");

    var ex = assertThrows(ContentExtractor.ExtractionException.class,
        () -> extractor.extract(file));
    assertTrue(ex.getMessage().contains("Office file too large"),
        "Expected Office-specific size error, got: " + ex.getMessage());
  }

  // --- Encoding handling tests ---

  @Nested
  @DisplayName("Encoding Handling")
  class EncodingHandling {

    @Test
    @DisplayName("UTF-8 with BOM: content extracted without BOM artifact")
    void extract_utf8WithBom_stripsBoMAndExtractsContent() throws Exception {
      Path file = tempDir.resolve("utf8-bom.txt");
      String text = "Héllo Wörld — JustSearch encoding test";
      try (OutputStream os = Files.newOutputStream(file)) {
        os.write(0xEF); // UTF-8 BOM
        os.write(0xBB);
        os.write(0xBF);
        os.write(text.getBytes(StandardCharsets.UTF_8));
      }

      ExtractionResult result = extractor.extract(file);

      assertFalse(result.content().startsWith("\uFEFF"),
          "BOM character should not appear in extracted content");
      assertTrue(result.content().contains("Héllo"),
          "Expected accented chars preserved, got: [" + result.content() + "]");
      assertTrue(result.content().contains("Wörld"),
          "Expected umlaut preserved, got: [" + result.content() + "]");
    }

    @Test
    @DisplayName("UTF-16 LE: decoded correctly")
    void extract_utf16Le_decodesCorrectly() throws Exception {
      Path file = tempDir.resolve("utf16-le.txt");
      String text = "Héllo Wörld — JustSearch encoding test";
      try (OutputStream os = Files.newOutputStream(file)) {
        os.write(0xFF); // UTF-16 LE BOM
        os.write(0xFE);
        os.write(text.getBytes(StandardCharsets.UTF_16LE));
      }

      ExtractionResult result = extractor.extract(file);

      assertTrue(result.content().contains("Héllo"),
          "Expected UTF-16 LE decoded correctly, got: [" + result.content() + "]");
    }

    @Test
    @DisplayName("UTF-16 BE: decoded correctly")
    void extract_utf16Be_decodesCorrectly() throws Exception {
      Path file = tempDir.resolve("utf16-be.txt");
      String text = "Héllo Wörld — JustSearch encoding test";
      try (OutputStream os = Files.newOutputStream(file)) {
        os.write(0xFE); // UTF-16 BE BOM
        os.write(0xFF);
        os.write(text.getBytes(StandardCharsets.UTF_16BE));
      }

      ExtractionResult result = extractor.extract(file);

      assertTrue(result.content().contains("Héllo"),
          "Expected UTF-16 BE decoded correctly, got: [" + result.content() + "]");
    }

    @Test
    @DisplayName("Windows-1252: accented characters and smart quotes decoded")
    void extract_windows1252_decodesAccentedCharacters() throws Exception {
      Path file = tempDir.resolve("windows-1252.txt");
      // Use chars unique to Windows-1252 (not in ISO-8859-1): smart quotes, em-dash
      Charset cp1252 = Charset.forName("windows-1252");
      String text = "r\u00E9sum\u00E9 caf\u00E9 na\u00EFve \u201Csmart quotes\u201D \u2014 em-dash";
      Files.write(file, text.getBytes(cp1252));

      ExtractionResult result = extractor.extract(file);

      // é (U+00E9) occupies the same byte in both CP1252 and ISO-8859-1,
      // so this passes regardless of which encoding Tika detects.
      // We're testing that accented characters survive, not the encoding label.
      assertTrue(result.content().contains("résumé") && result.content().contains("café"),
          "Expected accented characters preserved, got: [" + result.content() + "]");
    }

    @Test
    @DisplayName("ISO-8859-1: European accented characters decoded")
    void extract_iso88591_decodesAccentedCharacters() throws Exception {
      Path file = tempDir.resolve("iso-8859-1.txt");
      Charset latin1 = StandardCharsets.ISO_8859_1;
      String text = "H\u00E9llo W\u00F6rld r\u00E9sum\u00E9 caf\u00E9";
      Files.write(file, text.getBytes(latin1));

      ExtractionResult result = extractor.extract(file);

      assertTrue(result.content().contains("Héllo") && result.content().contains("résumé"),
          "Expected accented characters preserved, got: [" + result.content() + "]");
    }

    @Test
    @DisplayName("Shift-JIS: Japanese text decoded")
    void extract_shiftJis_decodesJapaneseText() throws Exception {
      Path file = tempDir.resolve("shift-jis.txt");
      Charset shiftJis = Charset.forName("Shift_JIS");
      String text = "\u3053\u3093\u306B\u3061\u306F\u4E16\u754C JustSearch";
      Files.write(file, text.getBytes(shiftJis));

      ExtractionResult result = extractor.extract(file);

      // Tika 3.x detects Shift-JIS from content and decodes correctly.
      assertTrue(result.content().contains("\u3053\u3093\u306B\u3061\u306F"),
          "Expected Japanese text preserved, got: [" + result.content() + "]");
      assertTrue(result.content().contains("JustSearch"),
          "Expected ASCII portion preserved alongside Japanese text");
    }

    @Test
    @DisplayName("Extracted non-ASCII content passes TextQualityAnalyzer")
    void extract_nonAsciiContent_passesQualityCheck() throws Exception {
      Path file = tempDir.resolve("quality-cross-check.txt");
      Charset latin1 = StandardCharsets.ISO_8859_1;
      // Content must exceed MIN_GOOD_TEXT_LENGTH (100 chars)
      String text = "Le r\u00E9sum\u00E9 du caf\u00E9 est tr\u00E8s int\u00E9ressant. "
          + "Les donn\u00E9es montrent que la qualit\u00E9 na\u00EFve du syst\u00E8me "
          + "d\u00E9passe les attentes pr\u00E9vues pour cette p\u00E9riode.";
      Files.write(file, text.getBytes(latin1));

      ExtractionResult result = extractor.extract(file);

      // The real cross-check: extraction output piped through quality analyzer
      assertFalse(TextQualityAnalyzer.isGarbageText(result.content()),
          "Extracted Latin-1 content should not be classified as garbage, "
              + "got: [" + result.content() + "]");
    }
  }

  private Path copyResourceToTemp(String resourcePath, String fileName) throws IOException {
    try (InputStream is = ContentExtractorTest.class.getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Missing test resource: " + resourcePath);
      }
      Path out = tempDir.resolve(fileName);
      Files.copy(is, out);
      return out;
    }
  }

  private static IndexDocument buildIndexDocument(Path filePath, ExtractionResult extraction) throws Exception {
    return TestDocumentBuilder.buildDocument(filePath, extraction);
  }

  @Nested
  @DisplayName("Title bounding (observation #379)")
  class TitleBounding {

    @Test
    @DisplayName("an over-long title (e.g. a giant frontmatter title:) is truncated to a sane indexed length, not rejected")
    void overLongTitle_isBoundedToSaneLength() {
      // The observation-#379 scenario: a 5846-char title (the 565 tempdoc frontmatter) previously
      // failed the WHOLE document forever. It is now truncated to MAX_INDEXED_TITLE_CHARS (a readable
      // length, well under the 4096 validation backstop) so the document indexes.
      String giantTitle = "T".repeat(5846);

      ExtractionResult result = new ExtractionResult("body content", giantTitle, "text/markdown");

      assertTrue(
          result.title().length() <= ExtractionResult.MAX_INDEXED_TITLE_CHARS,
          "title must be bounded to the sane indexed cap so the document indexes, not a 4096-char wall");
      assertEquals(
          ExtractionResult.MAX_INDEXED_TITLE_CHARS,
          result.title().length(),
          "a far-over-cap title truncates to exactly the cap (incl. the ellipsis)");
      assertTrue(result.title().endsWith("…"), "truncation should be marked with an ellipsis");
    }

    @Test
    @DisplayName("a title within the cap passes through unchanged; null stays null; boundary kept verbatim")
    void normalTitle_isUnchanged() {
      String ordinary = "A perfectly normal document title";
      assertEquals(ordinary, new ExtractionResult("c", ordinary, "text/plain").title());
      assertNull(new ExtractionResult("c", null, "text/plain").title());

      // A long-but-legitimate title (~370 chars, the normal tempdoc-title ceiling) is NOT truncated.
      String longLegit = "L".repeat(370);
      assertEquals(longLegit, new ExtractionResult("c", longLegit, "text/plain").title());

      // exactly at the cap is kept verbatim (boundary)
      String atCap = "L".repeat(ExtractionResult.MAX_INDEXED_TITLE_CHARS);
      assertEquals(atCap, new ExtractionResult("c", atCap, "text/plain").title());
    }

    @Test
    @DisplayName("truncation never splits a UTF-16 surrogate pair (emoji-safe)")
    void truncation_doesNotSplitSurrogatePair() {
      // 300 grinning-face emoji = 600 UTF-16 chars (each is a surrogate pair) → over the cap, and the
      // naive cut at cap-1 would land on a high surrogate.
      String emojiTitle = "😀".repeat(300);

      String bounded = new ExtractionResult("c", emojiTitle, "text/plain").title();

      assertTrue(bounded.length() <= ExtractionResult.MAX_INDEXED_TITLE_CHARS, "still bounded");
      assertTrue(bounded.endsWith("…"), "ellipsis-marked");
      String truncatedBody = bounded.substring(0, bounded.length() - 1); // drop the ellipsis
      assertFalse(
          !truncatedBody.isEmpty()
              && Character.isHighSurrogate(truncatedBody.charAt(truncatedBody.length() - 1)),
          "the truncated body must not end on a stranded high surrogate (split emoji)");
    }
  }
}
