/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.loop.FileFreshnessSnapshot.SourceValidationResult;

/**
 * Pure freshness classification — the law-bearing core extracted from {@link FileFreshnessSnapshot}
 * (tempdoc 555 §4, functional-core/imperative-shell). The snapshot record mixes filesystem IO
 * ({@code capture}/{@code validateNow}) with this pure priority-ordered comparison; isolating the
 * comparison makes the law independently testable and mutation-targeted, with no IO in the seam.
 *
 * <p>The LAW is the priority order: path-identity → file-kind → size → mtime → file-key. The first
 * difference (in that order) decides the result; a silent reorder or a flipped comparison
 * misclassifies a changed file as FRESH (or vice versa), corrupting the index.
 *
 * <p><b>Freshness contract (tempdoc 626 §I.3-E):</b> change is detected by size + mtime (+ file-key),
 * NOT by content hash. This is the accepted contract — content-hashing every file is too expensive at
 * scale. The known limitation: a content edit that preserves BOTH size AND mtime is classified FRESH
 * and not re-indexed (the same blind-spot the {@code LAST_MODIFIED_TIME} watcher hasher has). Only a
 * user-initiated force-reindex re-extracts such a file. This is a documented limitation, not a bug to
 * fix here; revisit only if a real workload needs sub-mtime change fidelity.
 */
final class FileFreshness {

  private FileFreshness() {}

  static SourceValidationResult classify(FileFreshnessSnapshot prev, FileFreshnessSnapshot current) {
    if (!prev.normalizedPath().equals(current.normalizedPath())) {
      return SourceValidationResult.DELETED;
    }
    if (prev.regularFile() != current.regularFile()) {
      return SourceValidationResult.SOURCE_KIND_CHANGED;
    }
    if (prev.sizeBytes() != current.sizeBytes()) {
      return SourceValidationResult.SIZE_CHANGED;
    }
    if (prev.modifiedAtMs() != current.modifiedAtMs()) {
      return SourceValidationResult.MODIFIED_TIME_CHANGED;
    }
    if (prev.fileKey() != null
        && current.fileKey() != null
        && !prev.fileKey().equals(current.fileKey())) {
      return SourceValidationResult.FILE_KEY_CHANGED;
    }
    return SourceValidationResult.FRESH;
  }
}
