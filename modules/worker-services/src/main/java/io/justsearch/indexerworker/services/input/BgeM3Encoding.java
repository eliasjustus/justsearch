/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import io.justsearch.indexerworker.bgem3.BgeM3Output;
import io.justsearch.indexerworker.services.SearchReasonCode;
import java.util.Objects;

/**
 * Three-state outcome of BGE-M3 encoding for the request's query (tempdoc 517).
 *
 * <p>BGE-M3 is the multi-modal encoder that produces dense + SPLADE outputs from
 * a single forward pass. When successful, downstream encoder routines may pull
 * from this rather than running the dedicated encoders.
 */
public sealed interface BgeM3Encoding
    permits BgeM3Encoding.NotRequested, BgeM3Encoding.Success, BgeM3Encoding.Failed {

  record NotRequested() implements BgeM3Encoding {}

  record Success(BgeM3Output output) implements BgeM3Encoding {
    public Success {
      Objects.requireNonNull(output, "output");
    }
  }

  record Failed(SearchReasonCode reason) implements BgeM3Encoding {
    public Failed {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
