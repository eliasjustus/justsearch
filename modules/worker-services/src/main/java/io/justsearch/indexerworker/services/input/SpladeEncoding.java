/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import io.justsearch.indexerworker.services.SearchReasonCode;
import java.util.Map;
import java.util.Objects;

/**
 * Three-state outcome of SPLADE encoding for the request's query (tempdoc 517).
 *
 * <p>Mirrors {@link VectorEncoding} for the SPLADE modality. The {@link Success}
 * payload is the sparse term-weight map produced by the SPLADE encoder.
 */
public sealed interface SpladeEncoding
    permits SpladeEncoding.NotRequested, SpladeEncoding.Success, SpladeEncoding.Failed {

  record NotRequested() implements SpladeEncoding {}

  record Success(Map<String, Float> weights) implements SpladeEncoding {
    public Success {
      Objects.requireNonNull(weights, "weights");
    }
  }

  record Failed(SearchReasonCode reason) implements SpladeEncoding {
    public Failed {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
