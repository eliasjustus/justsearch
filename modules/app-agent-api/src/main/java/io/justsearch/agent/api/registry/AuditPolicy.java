/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Audit policy for an Operation. Controls whether an invocation is recorded in the
 * Operation history.
 *
 * <p>Enforced at dispatch: {@code OperationExecutorImpl.emitHistory} suppresses the
 * {@code OperationHistoryEntry} for {@link #NONE} and emits the metadata-shaped entry for
 * {@link #METADATA_ONLY}. Advisory emission is a separate axis
 * ({@code OperationPolicy.advisoryClass}) and is NOT gated by this policy.
 *
 * <p>Per tempdoc 429 §A.6 / §A.7: validators flag {@code risk == HIGH && audit == NONE}
 * as ERROR.
 *
 * <p>Tempdoc 879 removed the third value, {@code FULL_PAYLOAD}. No Operation declared it,
 * and the machinery it promised (input redaction / summarisation, a PII flag on
 * {@link Interface} inputs) never existed — so it named a capability the system could not
 * deliver. Re-introducing it means building the redaction pipeline first, for a declared
 * consumer.
 */
public enum AuditPolicy {
  /** No audit record — the dispatch emits no history entry. Only valid for low-risk read operations. */
  NONE,
  /** Record metadata (id, timestamp, principal, outcome) but not arguments. */
  METADATA_ONLY
}
