/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

/** Stable coarse classes for the Worker ingestion boundary. */
public enum IngestionOutcomeClass {
  SUCCESS_FULL,
  SUCCESS_PARTIAL,
  // Tempdoc 671, Long-term design part 2: mirrors ExtractionStatus.SUCCESS_EMPTY at the
  // ingestion-boundary altitude — the pipeline completed cleanly but produced no usable content.
  SUCCESS_EMPTY,
  SKIPPED_POLICY,
  DEFERRED_POLICY,
  STALE_SOURCE,
  UNSUPPORTED,
  BUDGET_EXCEEDED,
  PARSER_FAILED,
  PARSER_TIMEOUT,
  IO_FAILED,
  WRITE_FAILED,
  WRITE_UNAVAILABLE_DRAINING,
  SANDBOX_FAILED
}
