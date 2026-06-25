/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpl;

import java.time.Instant;
import java.util.Map;

/**
 * Read-only view of a GPL evaluation snapshot, suitable for consumption by the UI layer without
 * importing the concrete {@code GplEvalSnapshot} implementation from {@code app-services}.
 */
public interface GplEvalData {

  /** Number of documents in the corpus at evaluation time. */
  long docCount();

  /** MIME-type distribution of indexed documents. */
  Map<String, Long> mimeDistribution();

  /** Total number of training triples accumulated across all runs. */
  long tripleCount();

  /** Timestamp of when this snapshot was captured, or {@code null} if unavailable. */
  Instant evaluatedAt();
}
