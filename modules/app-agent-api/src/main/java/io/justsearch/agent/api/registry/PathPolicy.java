/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Path-privacy policy axis on {@link Privacy}.
 *
 * <p>Slice 445 substrate-extension. Lifts the convention currently enforced by
 * ADR-0028 + {@code LibraryResolveHashOnlyCallerPin} into a typed declaration on
 * the {@link Resource} record. The validator can then enforce policy presence
 * (and, in a follow-up slice when the validator gains schema-parsing
 * infrastructure, schema-vs-policy field consistency).
 *
 * <p>Vocabulary intentionally closed; new values follow shape-governance per
 * {@code 10-kernel/04-shape-governance.md} §"Vocabulary Governance".
 */
public enum PathPolicy {
  /**
   * Resource declares no path-typed fields. Default for Resources whose schema
   * carries no filesystem-path strings (Health, OperationHistory, Capabilities,
   * RuntimeContext, all metric Resources today).
   */
  NO_PATHS,

  /**
   * Raw filesystem paths permitted in the wire payload. Caller is responsible
   * for ensuring the wire is loopback-only (matches {@link Privacy#loopbackOnly}
   * convention). Used by legacy diagnostic exports that pre-date ADR-0028 and
   * by interactive Resources where the user already sees raw paths in the
   * underlying UI.
   */
  RAW,

  /**
   * Wire payload carries SHA-256 path hashes only. Resolution to raw paths
   * goes through the {@link Privacy#resolver} Operation (typically
   * {@code core.resolve-path-hash}). Matches the ADR-0028 enforcement
   * precedent for diagnostic exports and (per slice 445) for live worker→head
   * data planes that cross the trust boundary.
   */
  HASHED_REQUIRES_RESOLVER
}
