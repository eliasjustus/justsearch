/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

import java.util.Objects;

/**
 * Head-side wire record for a single indexing-job row. Backs the
 * {@code core.indexing-jobs} TABULAR Resource (slice 445).
 *
 * <p>Privacy contract: {@code pathHash} is the SHA-256 hex of the absolute
 * normalized path. Raw paths NEVER appear on this wire — callers resolve
 * {@code pathHash} via the {@code core.resolve-path-hash} Operation when
 * a user gesture demands the path string. Pinned by ADR-0028 +
 * {@code LibraryResolveHashOnlyCallerPin}.
 *
 * <p>Worker analogue: {@code IndexingJobChangeFeed.JobRow} (worker-core).
 * The {@code RemoteIndexingJobsBridge} translates worker proto frames →
 * this head-side record one-for-one for the V1 lean scope.
 */
public record IndexingJobView(
    String pathHash,
    String state,
    int attempts,
    long lastUpdatedMs,
    String errorMessage,
    long retryAfterMs,
    String collection) {

  public IndexingJobView {
    Objects.requireNonNull(pathHash, "pathHash");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(collection, "collection");
    errorMessage = errorMessage == null ? "" : errorMessage;
  }
}
