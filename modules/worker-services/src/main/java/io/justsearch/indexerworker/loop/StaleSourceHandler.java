/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort deletion of an index entry whose source disappeared or changed between
 * admission and validation.
 *
 * <p>Tempdoc 516 Slice 3 substrate (Appendix A.1). Lifted from {@code IndexingLoop}'s
 * {@code bestEffortDeleteMissingSource} so the upcoming Slice 4a Extractor + Writer
 * extractions can both invoke it without straddling the seam (today the method mutates
 * {@code indexedSinceCommit} as a side effect — that side effect moves to the caller per
 * the return-outcomes contract).
 */
public final class StaleSourceHandler {

  private static final Logger log = LoggerFactory.getLogger(StaleSourceHandler.class);

  private final IndexingCoordinator indexingCoordinator;

  public StaleSourceHandler(IndexingCoordinator indexingCoordinator) {
    this.indexingCoordinator = indexingCoordinator;
  }

  /**
   * Attempts to delete the index entry for {@code filePath} (and any associated chunks).
   *
   * @return {@code 1} when the delete was issued (the caller should bump its
   *     {@code indexedSinceCommit} counter so the commit driver sees pending work);
   *     {@code 0} when the delete failed (logged at DEBUG).
   */
  public int deleteMissingSource(Path filePath) {
    try {
      String normalizedPath = PathNormalizer.normalizePath(filePath.toAbsolutePath().toString());
      indexingCoordinator.deleteByIdAndChunks(normalizedPath);
      return 1;
    } catch (Exception e) {
      log.debug("Best-effort delete failed for missing file {}: {}", filePath, e.getMessage());
      return 0;
    }
  }
}
