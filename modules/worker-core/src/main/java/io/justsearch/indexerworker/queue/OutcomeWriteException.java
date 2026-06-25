/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

/**
 * Thrown when an outcome-aware queue mutation fails after rollback.
 *
 * <p>Outcome-aware methods ({@code markDone(... outcome ...)},
 * {@code markFailedTerminal/Retryable}, {@code defer},
 * {@code markDoneTransitions}, {@code recordOutcome}, {@code recordIngestionEvent}) wrap the
 * queue UPDATE and the {@code ingestion_ledger} INSERT in a single SQLite transaction. If the
 * ledger insert fails the transaction rolls back so queue state stays consistent — and this
 * exception is thrown so the caller can decide whether to retry, defer, or surface the failure
 * rather than continuing as if the write landed.
 *
 * <p>Legacy best-effort methods ({@code markDone(Path)}, {@code markFailed(Path, String)},
 * {@code markDoneBatch(paths)}) preserve their pre-410 contract and never throw this — they
 * log and return on failure.
 */
public final class OutcomeWriteException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public OutcomeWriteException(String message, Throwable cause) {
    super(message, cause);
  }
}
