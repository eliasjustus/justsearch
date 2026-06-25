/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import io.justsearch.indexerworker.services.SearchReasonCode;
import java.util.List;
import java.util.Objects;

/**
 * Three-state outcome of dense-vector encoding for the request's query (tempdoc 517).
 *
 * <p>The {@link NotRequested} / {@link Success} / {@link Failed} sum prevents
 * the "didn't try" vs "tried and failed" conflation an {@code Optional<float[]>}
 * would suffer. A failed encode carries its {@link SearchReasonCode} as data;
 * the wire emission reads the code off this value rather than from a separate
 * branch.
 */
public sealed interface VectorEncoding
    permits VectorEncoding.NotRequested, VectorEncoding.Success, VectorEncoding.Failed {

  /** The pipeline did not request a dense vector for this query. */
  record NotRequested() implements VectorEncoding {}

  /** Encoding succeeded; payload is the vector + the encoder source ("explicit"|"bgem3"|"embedding-service"). */
  record Success(List<Float> vector, String source) implements VectorEncoding {
    public Success {
      Objects.requireNonNull(vector, "vector");
      Objects.requireNonNull(source, "source");
    }
  }

  /** Encoding was attempted but failed; carries the wire-visible reason. */
  record Failed(SearchReasonCode reason) implements VectorEncoding {
    public Failed {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
