/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Audit policy for an Operation. Controls how invocations are recorded for retention/replay.
 *
 * <p>Per tempdoc 429 §A.6 / §A.7: validators flag {@code risk == HIGH && audit == NONE}
 * as ERROR. PII-flagged inputs with {@code audit == FULL_PAYLOAD} surface a WARN.
 */
public enum AuditPolicy {
  /** No audit record. Only valid for low-risk read operations. */
  NONE,
  /** Record metadata (id, timestamp, principal, outcome) but not arguments. */
  METADATA_ONLY,
  /** Record metadata plus full input/output payloads. May exceed retention budget for PII. */
  FULL_PAYLOAD
}
