/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexing.extraction.StructuredDocument;
import java.util.HashSet;
import java.util.Set;

/** Compact routing summary derived from structured extraction output. */
public record StructuredDocumentSummary(
    int pageCount,
    int textCharCount,
    int pagesWithReadableText,
    int pagesMissingReadableText,
    int headingCount,
    int paragraphCount,
    int tableCount,
    int listCount,
    int imagePageCount) {

  public static StructuredDocumentSummary empty() {
    return new StructuredDocumentSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public static StructuredDocumentSummary fromDocument(StructuredDocument doc) {
    if (doc == null) {
      return empty();
    }
    int pages = Math.max(0, doc.pageCount());
    int[] charsByPage = pages > 0 ? new int[pages] : new int[0];
    int textChars = 0;
    int headings = 0;
    int paragraphs = 0;
    int tables = 0;
    int lists = 0;
    for (StructuredDocument.Element element : doc.elements()) {
      String text = elementText(element);
      int len = text == null ? 0 : text.strip().length();
      textChars += len;
      if (pages > 0 && element.pageIndex() >= 0 && element.pageIndex() < pages) {
        charsByPage[element.pageIndex()] += len;
      }
      switch (element) {
        case StructuredDocument.Heading ignored -> headings++;
        case StructuredDocument.Paragraph ignored -> paragraphs++;
        case StructuredDocument.Table ignored -> tables++;
        case StructuredDocument.ListBlock ignored -> lists++;
        case StructuredDocument.PageBreak ignored -> {
          // Page boundaries are counted through pageCount, not as layout elements.
        }
      }
    }
    int readable = 0;
    int missing = 0;
    if (pages > 0) {
      for (int chars : charsByPage) {
        if (chars >= VisualPageEvidencePolicy.READABLE_PAGE_MIN_CHARS) {
          readable++;
        } else {
          missing++;
        }
      }
    }
    return new StructuredDocumentSummary(
        pages, textChars, readable, missing, headings, paragraphs, tables, lists, 0);
  }

  public StructuredDocumentSummary withPdfPageSignals(
      int pdfPageCount, Set<Integer> readablePages, Set<Integer> imagePages) {
    int pages = Math.max(pageCount, Math.max(0, pdfPageCount));
    if (pages <= 0) {
      return this;
    }
    Set<Integer> readable = readablePages == null ? Set.of() : new HashSet<>(readablePages);
    Set<Integer> images = imagePages == null ? Set.of() : new HashSet<>(imagePages);
    int readableCount = 0;
    int missingCount = 0;
    int imageCount = 0;
    for (int i = 0; i < pages; i++) {
      if (readable.contains(i)) {
        readableCount++;
      } else {
        missingCount++;
      }
      if (images.contains(i)) {
        imageCount++;
      }
    }
    return new StructuredDocumentSummary(
        pages,
        textCharCount,
        Math.max(pagesWithReadableText, readableCount),
        Math.max(pagesMissingReadableText, missingCount),
        headingCount,
        paragraphCount,
        tableCount,
        listCount,
        Math.max(imagePageCount, imageCount));
  }

  public boolean mixedPdf() {
    return pageCount > 1 && pagesWithReadableText > 0 && pagesMissingReadableText > 0;
  }

  public boolean hasLayoutSignals() {
    return tableCount > 0 || listCount > 2 || headingCount > 3 || imagePageCount > 0 || mixedPdf();
  }

  public String layoutComplexity() {
    return VisualPageEvidencePolicy.layoutComplexity(
        tableCount, listCount, headingCount, imagePageCount, mixedPdf());
  }

  private static String elementText(StructuredDocument.Element element) {
    return switch (element) {
      case StructuredDocument.Heading h -> h.text();
      case StructuredDocument.Paragraph p -> p.text();
      case StructuredDocument.Table t -> {
        StringBuilder sb = new StringBuilder();
        for (var row : t.rows()) {
          for (String cell : row) {
            if (cell != null) {
              sb.append(cell).append(' ');
            }
          }
        }
        yield sb.toString();
      }
      case StructuredDocument.ListBlock lb -> String.join(" ", lb.items());
      case StructuredDocument.PageBreak ignored -> "";
    };
  }
}
