/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Surface altitude — the governing axis that determines a surface's home (its rail band) and its
 * core-vs-plugin eligibility, as a projection of the authority the surface carries (tempdoc 571).
 *
 * <p>Altitude is <strong>declared</strong> per surface and <strong>gate-witnessed</strong> (the
 * {@code surface-altitude} discipline gate), not derived purely from {@link SurfaceConsumes}: a
 * surface may project its primary authority out-of-band (the Activity surface projects the trust
 * action-ledger via the {@code jf-action-ledger} read-view, which is <em>not</em> in its
 * {@code consumes} graph — tempdoc 571 §8 R1). The <em>primary-authority</em> rule (571 §8 R2):
 * consumed Operations are affordances, not a second altitude; a surface has exactly one altitude.
 *
 * <p>The foreclosures the gate enforces:
 *
 * <ul>
 *   <li>{@code TRUST ⟹ CORE provenance} — a plugin cannot ship a trust surface. This is the
 *       surface-tier completion of tempdoc 569's reserved channel (the component-tier
 *       {@code RESERVED_COMPONENTS} forecloses authoring a trust <em>component</em>; this forecloses
 *       authoring a trust <em>surface</em>). Same rule at two granularities: trust-role ⟹ host-owned.
 *   <li>{@code consumes a DiagnosticChannel ⟹ DIAGNOSTIC} — a channel-consuming surface cannot hide
 *       as PRODUCT.
 * </ul>
 *
 * <p>{@link #PRODUCT} is the benign default (incl. empty-consumes surfaces per 571 §8 R3): it carries
 * no foreclosure, so a surface that omits an explicit altitude cannot accidentally acquire a dangerous
 * (TRUST / DIAGNOSTIC) classification — those are explicit and gate-checked.
 */
public enum Altitude {
  /** User-facing product / interaction surface. The default (incl. empty-consumes surfaces). */
  PRODUCT,
  /**
   * Operator-facing diagnostic surface — projects a DiagnosticChannel (Logs) or diagnostic Resources
   * (Health). Homed in the Diagnostics rail band.
   */
  DIAGNOSTIC,
  /**
   * Trust read-view — projects the authorization / action-lifecycle record (the Activity surface's
   * {@code jf-action-ledger}). CORE-only by construction; first-class, never plugin-authored.
   */
  TRUST,
  /** Headless agent-tool surface ({@link Placement#HEADLESS_AGENT_TOOL}); no human chrome zone. */
  TOOL
}
