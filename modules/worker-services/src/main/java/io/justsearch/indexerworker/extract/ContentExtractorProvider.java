/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionException;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Service Provider Interface for content extraction.
 *
 * <p>Implementations convert files into searchable text. The default implementation ({@link
 * ContentExtractor}) delegates to Apache Tika. Alternative providers can support additional formats
 * (e.g. audio transcription, custom PDF extraction) without modifying core indexing code.
 *
 * @see ContentExtractor
 * @see TimeboxedContentExtractor
 */
public interface ContentExtractorProvider {

  /**
   * Extracts text content, title, and MIME type from the given file.
   *
   * @param file the file to extract content from
   * @return extraction result containing content, title, and MIME type
   * @throws IOException if the file cannot be read
   * @throws ExtractionException if content extraction fails
   */
  ExtractionResult extract(Path file) throws IOException, ExtractionException;

  /**
   * Detects the MIME type of the given file without extracting content.
   *
   * @param file the file to detect
   * @return the detected MIME type (e.g. "application/pdf", "text/plain")
   */
  String detectMimeType(Path file);
}
