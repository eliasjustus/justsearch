/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

/**
 * API status surface that a metric's value appears in. Metrics declared with
 * {@link MetricDefinition.Builder#surfacedAt(StatusEndpoint, String)} announce that their value
 * shows up as a named field on a specific JSON record served at runtime; an ArchUnit rule
 * validates that the declared field name matches an actual record component.
 *
 * <p>Tempdoc 417 Phase 3a: reintroduces the type that F5 deleted. Phase 3b adds the ArchUnit
 * field-name validator and registers the 4 {@code index.runtime.*} gauges with
 * {@link #CORE_INDEX_VIEW}.
 */
public enum StatusEndpoint {
  /** {@code io.justsearch.app.api.status.CoreIndexView} — fields surfaced via {@code /api/status}. */
  CORE_INDEX_VIEW,
  /**
   * {@code io.justsearch.app.api.status.AgentSessionView} — fields surfaced via {@code
   * /api/status} under the {@code agentSessions} key. Tempdoc 415: the
   * {@code agent.session.active_count} gauge declares
   * {@code surfacedAt(AGENT_SESSION_VIEW, "activeCount")}.
   */
  AGENT_SESSION_VIEW,
  /**
   * {@code io.justsearch.app.api.status.GpuStatusView} — fields surfaced via {@code /api/status}
   * under the {@code gpu} key. Tempdoc 419 C3 V1: 30-min trends of {@code gpu.utilization.percent}
   * + {@code gpu.memory.utilization.percent} declare {@code surfacedAt(GPU_STATUS_VIEW, ...)}.
   */
  GPU_STATUS_VIEW,
  /**
   * {@code io.justsearch.app.api.status.TelemetryHealthView} — fields surfaced via {@code
   * /api/status} under the {@code telemetryHealth} key. Tempdoc 419 C3 V1: telemetry-subsystem
   * failure counters from {@code TelemetryHealthState} declare
   * {@code surfacedAt(TELEMETRY_HEALTH_VIEW, ...)}.
   */
  TELEMETRY_HEALTH_VIEW,
  /**
   * {@code io.justsearch.app.api.status.InferenceRuntimeView} — fields surfaced via
   * {@code /api/status} (Tempdoc 412 Phase 4). The current {@code inference.*} catalog does
   * not declare any {@code surfacedAt(INFERENCE_VIEW, ...)} mappings yet — the queue and
   * generation gauges are deferred until the llama-server Prometheus scraper lands. The
   * enum value is declared here so that future scraper wiring can point at it without
   * re-touching this file.
   */
  INFERENCE_VIEW
}
