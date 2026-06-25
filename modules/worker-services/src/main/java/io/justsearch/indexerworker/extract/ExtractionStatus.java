/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/** Internal trust/provenance status for parser output before document construction. */
public enum ExtractionStatus {
  SUCCESS_FULL,
  SUCCESS_PARTIAL,
  FAILED,
  TIMED_OUT,
  BUDGET_EXCEEDED
}
