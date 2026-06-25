/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Renderer-surface selection hint for advisory-shaped Resource events — declares whether
 * the FE chrome should render an advisory transiently (toast), durably (inbox with
 * read/unread state), or as a must-acknowledge interruption.
 *
 * <p>Per slice 490 §4.C / §4.D: the toast / inbox / rail-badge Surfaces are not free
 * design parameters; their shape is structurally derived from the upstream advisory
 * Resource's declared {@link EmissionPolicy#renderHint()} (paired with its
 * {@link HistoryPolicy}). Toast for ephemeral; inbox for persisted; ack-required for
 * events the user must acknowledge before they roll off the surface.
 *
 * <p>Renamed from {@code AckMode} (Group B1 follow-up). The earlier name conflated two
 * concerns: the field is doing renderer-selection, not discovery-gating. Discovery-gate
 * fields (quiet-hours, importance threshold, dedupe window) are future {@link
 * EmissionPolicy} fields, not synonyms for this one.
 */
public enum RenderHint {
  /**
   * Transient — render as toast / brief overlay; vanish after a short interval. Pair
   * with {@code HistoryPolicy.RING_BUFFER} (or no history) on the host Resource.
   */
  EPHEMERAL,
  /**
   * Durable — render in an inbox / drawer surface with persisted read/unread state.
   * Pair with {@code HistoryPolicy.RING_BUFFER} or {@code DURABLE} on the host
   * Resource.
   */
  PERSISTED,
  /**
   * Durable, must be acknowledged before rolling off the inbox. Used by advisory
   * classes whose contents are load-bearing for user awareness (e.g., PII detection
   * alerts, destructive-action confirmations the user dismissed).
   */
  REQUIRES_ACK
}
