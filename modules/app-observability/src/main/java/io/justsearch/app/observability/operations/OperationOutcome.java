/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

/**
 * Outcome discriminator for an {@link OperationHistoryEntry}.
 *
 * <p>Per slice 444b: SUCCESS / FAILURE matches what {@code OperationResult} actually
 * distinguishes today. CANCELLED / PARTIAL are speculative and not produced by any
 * current dispatcher path; they would be added when the dispatcher gains those signals.
 */
public enum OperationOutcome {
  SUCCESS,
  FAILURE,
  UNDONE
}
