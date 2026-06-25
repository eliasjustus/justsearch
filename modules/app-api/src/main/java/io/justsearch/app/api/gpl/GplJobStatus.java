/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpl;

import java.time.Instant;

/**
 * Snapshot of the GPL job state, suitable for embedding in a status response.
 *
 * @param status current execution status
 * @param processedDocs documents processed in the last / current run
 * @param totalDocs total documents discovered in the corpus for the last run
 * @param tripleCount total triples in the store (may be from multiple runs)
 * @param lastRunAt start time of the last run, or {@code null} if never run
 * @param lastError error message from the last failed run, or {@code null}
 */
public record GplJobStatus(
    Status status,
    long processedDocs,
    long totalDocs,
    long tripleCount,
    Instant lastRunAt,
    String lastError) {

  /** Job execution status. */
  public enum Status {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED
  }
}
