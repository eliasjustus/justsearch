/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import io.justsearch.indexerworker.services.SearchReasonCode;

/**
 * Captured compatibility-controller boundary value (tempdoc 517).
 *
 * <p>The boundary maps the upstream {@code EmbeddingCompatibilityController}'s
 * string output into one of the 8 compat {@link SearchReasonCode} members (or
 * {@link SearchReasonCode#EMBEDDING_COMPATIBILITY_UNKNOWN} as fall-through).
 * The original string is kept alongside for diagnostic logging.
 *
 * @param rawString the compatReasonCode parameter passed to {@code execute(...)};
 *     may be null
 * @param mapped the typed enum value; never null
 * @param allowQueryEmbeddings whether vector/hybrid queries are currently
 *     allowed by the compat gate
 */
public record EmbeddingCompatBoundary(
    String rawString, SearchReasonCode mapped, boolean allowQueryEmbeddings) {

  public EmbeddingCompatBoundary {
    if (mapped == null) {
      throw new NullPointerException("mapped");
    }
  }

  public static EmbeddingCompatBoundary of(String rawString, boolean allowQueryEmbeddings) {
    return new EmbeddingCompatBoundary(
        rawString, SearchReasonCode.fromCompatString(rawString), allowQueryEmbeddings);
  }
}
