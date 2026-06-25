/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

/** Retry behavior attached to a typed ingestion outcome. */
public enum IngestionRetryPolicy {
  NONE,
  RETRY_WITH_BACKOFF,
  DEFER_WITHOUT_ATTEMPT
}
