/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Detects document types from MIME types and filename patterns.
 *
 * <p>Used to optimize RAG query terms based on document characteristics.
 * All methods are pure functions with no external state dependencies.
 */
public final class DocumentTypeDetector {

  private DocumentTypeDetector() {
    // Utility class - no instantiation
  }

  // ==========================================================================
  // Document Type Enum
  // ==========================================================================

  /** Document type categories for query optimization. */
  public enum DocumentType {
    PDF_DOCUMENT,
    CODE_FILE,
    OFFICE_DOCUMENT,
    MARKDOWN,
    BOOK_OR_LITERATURE,
    LEGAL,
    EMAIL,
    GENERAL
  }

  // ==========================================================================
  // Detection Methods
  // ==========================================================================

  /**
   * Detects document type from MIME type and filename heuristics.
   *
   * @param mimeType the MIME type of the document (may be null)
   * @param docIds list of document IDs/paths for filename-based fallback
   * @return detected document type
   */
  public static DocumentType detect(String mimeType, List<String> docIds) {
    if (mimeType == null || mimeType.isBlank()) {
      // Fall back to filename-based detection
      return detectFromFilename(docIds);
    }

    String mime = mimeType.toLowerCase(Locale.ROOT);

    // PDF documents
    if (mime.equals("application/pdf")) {
      // Check filename for specific PDF types
      String filename = docIds.isEmpty() ? "" : extractFilename(docIds.get(0)).toLowerCase(Locale.ROOT);
      if (filename.contains("contract") || filename.contains("agreement") || filename.contains("legal")) {
        return DocumentType.LEGAL;
      }
      return DocumentType.PDF_DOCUMENT;
    }

    // Office documents (check before code files - officedocument contains "xml")
    if (mime.contains("officedocument") || mime.contains("msword") ||
        mime.contains("ms-excel") || mime.contains("ms-powerpoint") ||
        mime.contains("opendocument")) {
      return DocumentType.OFFICE_DOCUMENT;
    }

    // Markdown (check before code files - text/x-markdown starts with "text/x-")
    if (mime.contains("markdown")) {
      return DocumentType.MARKDOWN;
    }

    // Code files
    if (mime.startsWith("text/x-") || mime.contains("javascript") || mime.contains("json") ||
        mime.contains("xml") || mime.contains("yaml") || mime.contains("python") ||
        mime.contains("java") || mime.contains("c-") || mime.contains("c++")) {
      return DocumentType.CODE_FILE;
    }

    // Email
    if (mime.contains("message/") || mime.contains("email")) {
      return DocumentType.EMAIL;
    }

    // Plain text - check filename for hints
    if (mime.startsWith("text/plain")) {
      return detectFromFilename(docIds);
    }

    return DocumentType.GENERAL;
  }

  /**
   * Fallback detection based on filename patterns.
   *
   * @param docIds list of document IDs/paths
   * @return detected document type based on filename patterns
   */
  public static DocumentType detectFromFilename(List<String> docIds) {
    if (docIds == null || docIds.isEmpty()) return DocumentType.GENERAL;

    String filename = extractFilename(docIds.get(0)).toLowerCase(Locale.ROOT);

    // Code file extensions
    if (filename.endsWith(".java") || filename.endsWith(".py") || filename.endsWith(".js") ||
        filename.endsWith(".ts") || filename.endsWith(".c") || filename.endsWith(".cpp") ||
        filename.endsWith(".h") || filename.endsWith(".go") || filename.endsWith(".rs") ||
        filename.endsWith(".rb") || filename.endsWith(".php") || filename.endsWith(".cs")) {
      return DocumentType.CODE_FILE;
    }

    // Markdown
    if (filename.endsWith(".md") || filename.endsWith(".markdown")) {
      return DocumentType.MARKDOWN;
    }

    // Common book/literature patterns
    if (filename.contains("chapter") || filename.contains("book") ||
        filename.contains("novel") || filename.contains("story")) {
      return DocumentType.BOOK_OR_LITERATURE;
    }

    // Legal patterns
    if (filename.contains("contract") || filename.contains("agreement") ||
        filename.contains("legal") || filename.contains("terms")) {
      return DocumentType.LEGAL;
    }

    return DocumentType.GENERAL;
  }

  // ==========================================================================
  // Filename Extraction
  // ==========================================================================

  /**
   * Extract filename from document ID (path).
   *
   * @param docId the document ID or path
   * @return the filename portion, or "unknown" if null/blank
   */
  public static String extractFilename(String docId) {
    if (docId == null || docId.isBlank()) {
      return "unknown";
    }
    try {
      return Path.of(docId).getFileName().toString();
    } catch (Exception e) {
      // Fallback for non-path IDs
      int lastSlash = Math.max(docId.lastIndexOf('/'), docId.lastIndexOf('\\'));
      return lastSlash >= 0 ? docId.substring(lastSlash + 1) : docId;
    }
  }
}
