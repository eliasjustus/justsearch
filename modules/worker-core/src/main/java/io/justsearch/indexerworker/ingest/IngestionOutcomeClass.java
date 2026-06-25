/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

/** Stable coarse classes for the Worker ingestion boundary. */
public enum IngestionOutcomeClass {
  SUCCESS_FULL,
  SUCCESS_PARTIAL,
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
