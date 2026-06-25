/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Resource altitude-role — the authority a Resource carries, classified so a surface's
 * {@link Altitude} can be <strong>derived</strong> from the roles of the Resources it consumes
 * (tempdoc 571 §4c). This is the seed that makes altitude a projection rather than a hand-set
 * declaration: a surface consuming a {@code DIAGNOSTIC}-role Resource is a diagnostic surface; one
 * consuming a {@code TRUST}-role Resource is a trust surface; consuming two different non-PRODUCT
 * roles is a derivation <em>conflict</em> (the merge-foreclosure — 571 §4c).
 *
 * <p>Distinct from {@link Category} (the data shape — EVENT_STREAM / TABULAR / …), {@link Audience}
 * (who may read it), and {@link Privacy} (path-typed sensitivity): none of those faithfully separate
 * an operator-facing diagnostic stream from a product stream, nor a trust read-view from either.
 * {@code role} is that missing axis.
 *
 * <p>{@link #PRODUCT} is the benign default (a Resource that omits a role cannot accidentally lift a
 * consuming surface to a foreclosed altitude — DIAGNOSTIC / TRUST are explicit).
 */
public enum Role {
  /** Ordinary product data — carries no altitude lift. The default. */
  PRODUCT,
  /**
   * Operator-facing diagnostic stream (health events, failed-job tables, recovery indices). A surface
   * consuming a DIAGNOSTIC Resource derives {@link Altitude#DIAGNOSTIC}.
   */
  DIAGNOSTIC,
  /**
   * Trust / authorization-lifecycle record (the action ledger). A surface consuming a TRUST Resource
   * derives {@link Altitude#TRUST} — CORE-only by construction, never plugin-authored.
   */
  TRUST
}
