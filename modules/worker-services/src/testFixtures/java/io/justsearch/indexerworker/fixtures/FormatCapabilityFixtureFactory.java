/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.fixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

/** Deterministic, repository-owned format fixtures for extraction capability tests. */
public final class FormatCapabilityFixtureFactory {

  private static final LocalDateTime FIXED_ARCHIVE_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 2);
  private static final String PPTX_SLIDE_MARKER = "JUSTSEARCH_PPTX_SLIDE_MARKER";
  private static final String PPTX_SPEAKER_NOTES_MARKER =
      "JUSTSEARCH_PPTX_SPEAKER_NOTES_MARKER";
  private static final Set<String> PPTX_LOCALE_NORMALIZED_ENTRIES =
      Set.of("ppt/slides/slide1.xml", "ppt/notesSlides/notesSlide1.xml");
  private static final Pattern PPTX_END_PARAGRAPH_LANGUAGE =
      Pattern.compile("<a:endParaRPr lang=\"[^\"]+\"/>");
  private static final Pattern PPTX_XML_DECLARATION_LINE_BREAK =
      Pattern.compile("^(<\\?xml[^>]*\\?>)\\r?\\n");

  public enum FormatId {
    EML,
    MBOX,
    RTF,
    EPUB,
    ODT,
    XLSX,
    XLSX_MERGED_HEADERS,
    XLSX_TYPED_CELLS,
    PPTX_WITH_NOTES,
    ZIP_WITH_XLSX
  }

  public record GeneratedFixture(FormatId id, String recipeId, String fileName, byte[] bytes) {
    public GeneratedFixture {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  private record ArchiveEntry(String name, byte[] content) {
    ArchiveEntry {
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }
  }

  private FormatCapabilityFixtureFactory() {}

  public static GeneratedFixture generate(FormatId id) {
    return switch (id) {
      case EML ->
          new GeneratedFixture(id, "eml-multipart-v1", "format-capability.eml", emlBytes());
      case MBOX ->
          new GeneratedFixture(id, "mbox-two-messages-v1", "format-capability.mbox", mboxBytes());
      case RTF ->
          new GeneratedFixture(
              id,
              "rtf-ascii-v1",
              "format-capability.rtf",
              ascii(
                  "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}"
                      + "\\fs24 JUSTSEARCH_RTF_SMOKE_MARKER\\par "
                      + "Deterministic rich text fixture.\\par}"));
      case EPUB ->
          new GeneratedFixture(id, "epub3-chapter-v1", "format-capability.epub", epubBytes());
      case ODT ->
          new GeneratedFixture(id, "odt-structure-v1", "format-capability.odt", odtBytes());
      case XLSX ->
          new GeneratedFixture(
              id, "xlsx-inline-strings-v1", "format-capability.xlsx", xlsxBytes());
      case XLSX_MERGED_HEADERS ->
          new GeneratedFixture(
              id,
              "xlsx-merged-multirow-v1",
              "format-capability-merged.xlsx",
              xlsxMergedHeadersBytes());
      case XLSX_TYPED_CELLS ->
          new GeneratedFixture(
              id,
              "xlsx-numeric-date-formula-v1",
              "format-capability-typed.xlsx",
              xlsxTypedCellsBytes());
      case PPTX_WITH_NOTES ->
          new GeneratedFixture(
              id,
              "pptx-speaker-notes-poi-v1",
              "format-capability-notes.pptx",
              pptxWithNotesBytes());
      case ZIP_WITH_XLSX ->
          new GeneratedFixture(
              id,
              "zip-text-plus-xlsx-v1",
              "format-capability.zip",
              zipBytes(
                  Map.of(
                      "documents/format-capability.xlsx", xlsxBytes(),
                      "readme.txt",
                          ascii(
                              "JUSTSEARCH_ZIP_TEXT_MARKER\n"
                                  + "The sibling workbook marker must also be extracted.\n"))));
    };
  }

  public static Path write(Path directory, FormatId id) throws IOException {
    GeneratedFixture fixture = generate(id);
    Files.createDirectories(directory);
    Path target = directory.resolve(fixture.fileName());
    Files.write(target, fixture.bytes());
    return target;
  }

  private static byte[] emlBytes() {
    String attachment =
        Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(
                ascii(
                    "JUSTSEARCH_EML_ATTACHMENT_MARKER\n"
                        + "Owned attachment content from the deterministic EML recipe.\n"));
    return ascii(
        "From: sender@example.invalid\r\n"
            + "To: reader@example.invalid\r\n"
            + "Date: Sat, 01 Jan 2000 00:00:00 +0000\r\n"
            + "Subject: JUSTSEARCH_EML_SUBJECT_MARKER\r\n"
            + "Message-ID: <format-capability-eml@example.invalid>\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=\"justsearch-eml-boundary\"\r\n\r\n"
            + "--justsearch-eml-boundary\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "Content-Transfer-Encoding: 7bit\r\n\r\n"
            + "JUSTSEARCH_EML_BODY_MARKER\r\nOwned message body.\r\n"
            + "--justsearch-eml-boundary\r\n"
            + "Content-Type: text/plain; charset=UTF-8; name=\"owned-attachment.txt\"\r\n"
            + "Content-Disposition: attachment; filename=\"owned-attachment.txt\"\r\n"
            + "Content-Transfer-Encoding: base64\r\n\r\n"
            + attachment
            + "\r\n--justsearch-eml-boundary--\r\n");
  }

  private static byte[] mboxBytes() {
    String attachment =
        Base64.getMimeEncoder(76, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(ascii("JUSTSEARCH_MBOX_ATTACHMENT_MARKER\nOwned MBOX attachment.\n"));
    return ascii(
        "From first@example.invalid Sat Jan 01 00:00:00 2000\n"
            + "From: first@example.invalid\nTo: reader@example.invalid\n"
            + "Date: Sat, 01 Jan 2000 00:00:00 +0000\n"
            + "Subject: JUSTSEARCH_MBOX_FIRST_SUBJECT\n"
            + "Message-ID: <format-capability-mbox-1@example.invalid>\n"
            + "Content-Type: text/plain; charset=UTF-8\n\n"
            + "JUSTSEARCH_MBOX_FIRST_BODY\nOwned first message.\n\n"
            + "From second@example.invalid Sun Jan 02 00:00:00 2000\n"
            + "From: second@example.invalid\nTo: reader@example.invalid\n"
            + "Date: Sun, 02 Jan 2000 00:00:00 +0000\n"
            + "Subject: JUSTSEARCH_MBOX_SECOND_SUBJECT\n"
            + "Message-ID: <format-capability-mbox-2@example.invalid>\n"
            + "MIME-Version: 1.0\n"
            + "Content-Type: multipart/mixed; boundary=\"justsearch-mbox-boundary\"\n\n"
            + "--justsearch-mbox-boundary\nContent-Type: text/plain; charset=UTF-8\n\n"
            + "JUSTSEARCH_MBOX_SECOND_BODY\nOwned second message.\n"
            + "--justsearch-mbox-boundary\n"
            + "Content-Type: text/plain; name=\"mbox-attachment.txt\"\n"
            + "Content-Disposition: attachment; filename=\"mbox-attachment.txt\"\n"
            + "Content-Transfer-Encoding: base64\n\n"
            + attachment
            + "\n--justsearch-mbox-boundary--\n");
  }

  private static byte[] epubBytes() {
    List<ArchiveEntry> entries = new ArrayList<>();
    entries.add(new ArchiveEntry("mimetype", ascii("application/epub+zip")));
    entries.add(
        new ArchiveEntry(
            "META-INF/container.xml",
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """)));
    entries.add(
        new ArchiveEntry(
            "OEBPS/package.opf",
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" xmlns:dcterms="http://purl.org/dc/terms/" version="3.0" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">urn:uuid:justsearch-format-capability</dc:identifier>
                    <dc:title>Owned EPUB capability</dc:title><dc:language>en</dc:language>
                    <meta property="dcterms:modified">2000-01-01T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  </manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
                """)));
    entries.add(
        new ArchiveEntry(
            "OEBPS/chapter.xhtml",
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Capability chapter</title></head>
                  <body>
                    <h1>JUSTSEARCH_EPUB_HEADING_MARKER</h1>
                    <p>Owned deterministic EPUB chapter.</p>
                    <ul><li>JUSTSEARCH_EPUB_LIST_MARKER</li><li>Second owned item</li></ul>
                  </body>
                </html>
                """)));
    entries.add(
        new ArchiveEntry(
            "OEBPS/nav.xhtml",
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>Contents</title></head>
                  <body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Capability chapter</a></li></ol></nav></body>
                </html>
                """)));
    return orderedZipBytes(entries);
  }

  private static byte[] odtBytes() {
    List<ArchiveEntry> entries = new ArrayList<>();
    entries.add(
        new ArchiveEntry("mimetype", ascii("application/vnd.oasis.opendocument.text")));
    entries.add(
        new ArchiveEntry(
            "META-INF/manifest.xml",
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3">
                  <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>
                  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                </manifest:manifest>
                """)));
    entries.add(
        new ArchiveEntry(
            "content.xml",
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content
                    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                    office:version="1.3">
                  <office:body><office:text>
                    <text:h text:outline-level="1">JUSTSEARCH_ODT_HEADING_MARKER</text:h>
                    <text:p>Owned deterministic OpenDocument text.</text:p>
                    <text:list>
                      <text:list-item><text:p>JUSTSEARCH_ODT_LIST_MARKER</text:p></text:list-item>
                      <text:list-item><text:p>Second owned list item</text:p></text:list-item>
                    </text:list>
                    <table:table table:name="Capability">
                      <table:table-row>
                        <table:table-cell office:value-type="string"><text:p>Key</text:p></table:table-cell>
                        <table:table-cell office:value-type="string"><text:p>Value</text:p></table:table-cell>
                      </table:table-row>
                      <table:table-row>
                        <table:table-cell office:value-type="string"><text:p>Owned row</text:p></table:table-cell>
                        <table:table-cell office:value-type="string"><text:p>JUSTSEARCH_ODT_TABLE_MARKER</text:p></table:table-cell>
                      </table:table-row>
                    </table:table>
                  </office:text></office:body>
                </office:document-content>
                """)));
    return orderedZipBytes(entries);
  }

  private static byte[] xlsxBytes() {
    Map<String, byte[]> entries = new TreeMap<>();
    entries.put(
        "[Content_Types].xml",
        xml(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>
            """));
    entries.put(
        "_rels/.rels",
        xml(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
            """));
    entries.put(
        "xl/_rels/workbook.xml.rels",
        xml(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>
            """));
    entries.put(
        "xl/workbook.xml",
        xml(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="Capability" sheetId="1" r:id="rId1"/></sheets>
            </workbook>
            """));
    entries.put(
        "xl/worksheets/sheet1.xml",
        xml(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="inlineStr"><is><t>Fixture</t></is></c>
                  <c r="B1" t="inlineStr"><is><t>Evidence</t></is></c>
                </row>
                <row r="2">
                  <c r="A2" t="inlineStr"><is><t>Format breadth</t></is></c>
                  <c r="B2" t="inlineStr"><is><t>JUSTSEARCH_XLSX_SMOKE_MARKER</t></is></c>
                </row>
              </sheetData>
            </worksheet>
            """));
    return zipBytes(entries);
  }

  private static byte[] xlsxMergedHeadersBytes() {
    return xlsxPackage(
        "Merged headers",
        """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData>
            <row r="1">
              <c r="A1" t="inlineStr"><is><t>Region</t></is></c>
              <c r="B1" t="inlineStr"><is><t>Quarterly revenue</t></is></c>
            </row>
            <row r="2">
              <c r="B2" t="inlineStr"><is><t>Q1</t></is></c>
              <c r="C2" t="inlineStr"><is><t>Q2</t></is></c>
            </row>
            <row r="3">
              <c r="A3" t="inlineStr"><is><t>North</t></is></c>
              <c r="B3" t="inlineStr"><is><t>JUSTSEARCH_XLSX_MERGED_MARKER</t></is></c>
              <c r="C3"><v>20</v></c>
            </row>
          </sheetData>
          <mergeCells count="1"><mergeCell ref="B1:C1"/></mergeCells>
        </worksheet>
        """,
        null);
  }

  private static byte[] xlsxTypedCellsBytes() {
    String styles =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
          <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
          <borders count="1"><border/></borders>
          <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
          <numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd"/></numFmts>
          <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
        </styleSheet>
        """;
    return xlsxPackage(
        "Typed cells",
        """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData>
            <row r="1"><c r="A1" t="inlineStr"><is><t>Field</t></is></c><c r="B1" t="inlineStr"><is><t>Value</t></is></c></row>
            <row r="2"><c r="A2" t="inlineStr"><is><t>Typed number</t></is></c><c r="B2"><v>42</v></c></row>
            <row r="3"><c r="A3" t="inlineStr"><is><t>Typed date</t></is></c><c r="B3" s="1"><v>45292</v></c></row>
            <row r="4"><c r="A4" t="inlineStr"><is><t>JUSTSEARCH_XLSX_FORMULA_MARKER</t></is></c><c r="B4"><f>B2*2</f><v>84</v></c></row>
          </sheetData>
        </worksheet>
        """,
        styles);
  }

  private static byte[] pptxWithNotesBytes() {
    try {
      ByteArrayOutputStream poiBytes = new ByteArrayOutputStream();
      try (XMLSlideShow slideShow = new XMLSlideShow()) {
        XSLFSlide slide = slideShow.createSlide();
        slide.createTextBox().setText(PPTX_SLIDE_MARKER);
        XSLFNotes notes = slideShow.getNotesSlide(slide);
        notes.createTextBox().setText(PPTX_SPEAKER_NOTES_MARKER);
        slideShow.write(poiBytes);
      }

      List<ArchiveEntry> entries = new ArrayList<>();
      try (ZipInputStream zip =
          new ZipInputStream(
              new java.io.ByteArrayInputStream(poiBytes.toByteArray()), StandardCharsets.UTF_8)) {
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          if (!entry.isDirectory()) {
            entries.add(
                new ArchiveEntry(
                    entry.getName(), normalizePptxEntry(entry.getName(), zip.readAllBytes())));
          }
        }
      }
      entries.sort(Comparator.comparing(ArchiveEntry::name));
      byte[] deterministic = orderedZipBytes(entries);

      // The POI constructor is the fixture validity gate. Never publish hand-authored or partially
      // normalized OOXML that cannot be consumed as a presentation with real slide/notes parts.
      try (XMLSlideShow reopened =
          new XMLSlideShow(new java.io.ByteArrayInputStream(deterministic))) {
        if (reopened.getSlides().size() != 1
            || reopened.getSlides().getFirst().getNotes() == null
            || !textFrom(reopened.getSlides().getFirst()).contains(PPTX_SLIDE_MARKER)
            || !textFrom(reopened.getSlides().getFirst().getNotes())
                .contains(PPTX_SPEAKER_NOTES_MARKER)) {
          throw new IllegalStateException("POI did not preserve the owned PPTX slide/notes graph");
        }
      }
      return deterministic;
    } catch (IOException e) {
      throw new IllegalStateException("Could not build deterministic PPTX fixture", e);
    }
  }

  private static byte[] normalizePptxEntry(String name, byte[] content) {
    if (!name.endsWith(".xml") && !name.endsWith(".rels")) {
      return content;
    }
    String xmlContent = new String(content, StandardCharsets.UTF_8);
    // POI/XMLBeans uses the host line separator after XML declarations. Preserve the existing
    // pinned CRLF fixture bytes on every host, including parts copied from POI's bundled template.
    xmlContent = PPTX_XML_DECLARATION_LINE_BREAK.matcher(xmlContent).replaceFirst("$1\r\n");
    if (!PPTX_LOCALE_NORMALIZED_ENTRIES.contains(name)) {
      return xmlContent.getBytes(StandardCharsets.UTF_8);
    }
    long localeDerivedAttributes = PPTX_END_PARAGRAPH_LANGUAGE.matcher(xmlContent).results().count();
    if (localeDerivedAttributes != 1) {
      throw new IllegalStateException(
          "Expected one locale-derived end-paragraph attribute in "
              + name
              + ", found "
              + localeDerivedAttributes);
    }
    String normalized =
        PPTX_END_PARAGRAPH_LANGUAGE
            .matcher(xmlContent)
            .replaceAll("<a:endParaRPr lang=\"en-US\"/>");
    return normalized.getBytes(StandardCharsets.UTF_8);
  }

  private static String textFrom(XSLFSheet sheet) {
    return sheet.getShapes().stream()
        .filter(XSLFTextShape.class::isInstance)
        .map(XSLFTextShape.class::cast)
        .map(XSLFTextShape::getText)
        .collect(Collectors.joining("\n"));
  }

  private static byte[] xlsxPackage(String sheetName, String worksheet, String styles) {
    Map<String, byte[]> entries = new TreeMap<>();
    String styleOverride =
        styles == null
            ? ""
            : "  <Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>\n";
    entries.put(
        "[Content_Types].xml",
        xml(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            """
                + styleOverride
                + "</Types>\n"));
    entries.put(
        "_rels/.rels",
        xml(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
            """));
    String styleRelationship =
        styles == null
            ? ""
            : "  <Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n";
    entries.put(
        "xl/_rels/workbook.xml.rels",
        xml(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            """
                + styleRelationship
                + "</Relationships>\n"));
    entries.put(
        "xl/workbook.xml",
        xml(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n"
                + "  <sheets><sheet name=\""
                + sheetName
                + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>\n"
                + "</workbook>\n"));
    entries.put("xl/worksheets/sheet1.xml", xml(worksheet));
    if (styles != null) {
      entries.put("xl/styles.xml", xml(styles));
    }
    return zipBytes(entries);
  }

  private static byte[] orderedZipBytes(List<ArchiveEntry> entries) {
    return writeZip(entries);
  }

  /**
   * Builds byte-identical ZIPs: entry names are sorted and every entry is STORED with explicit
   * size, compressed size, CRC, and epoch timestamp.
   */
  private static byte[] zipBytes(Map<String, byte[]> unsortedEntries) {
    List<ArchiveEntry> entries = new ArrayList<>();
    unsortedEntries.entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .forEach(item -> entries.add(new ArchiveEntry(item.getKey(), item.getValue())));
    return writeZip(entries);
  }

  private static byte[] writeZip(List<ArchiveEntry> entries) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
        for (ArchiveEntry item : entries) {
          byte[] content = item.content();
          CRC32 crc = new CRC32();
          crc.update(content);

          ZipEntry entry = new ZipEntry(item.name());
          entry.setMethod(ZipEntry.STORED);
          entry.setSize(content.length);
          entry.setCompressedSize(content.length);
          entry.setCrc(crc.getValue());
          entry.setTimeLocal(FIXED_ARCHIVE_TIME);
          zip.putNextEntry(entry);
          zip.write(content);
          zip.closeEntry();
        }
      }
      return bytes.toByteArray();
    } catch (IOException impossibleForMemoryBuffer) {
      throw new IllegalStateException(
          "Could not build deterministic fixture archive", impossibleForMemoryBuffer);
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] xml(String value) {
    return value.stripIndent().getBytes(StandardCharsets.UTF_8);
  }
}
