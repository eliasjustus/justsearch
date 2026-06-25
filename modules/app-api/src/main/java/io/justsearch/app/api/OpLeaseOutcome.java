/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Outcome reported when an {@link OperationLeaseHandle} is released. Tempdoc 542 Layer 2.
 *
 * <p>Recorded in the dev-runner's run history; informs interruptible-with-loss stop-report
 * tagging on subsequent takeovers (so the next admission sees "previous holder reported X").
 */
public enum OpLeaseOutcome {
  /** Op completed successfully. */
  SUCCESS,
  /** Op failed; partial work may persist. */
  FAILURE,
  /** Op was cancelled cleanly before any irreversible step. */
  CANCELLED;
}
