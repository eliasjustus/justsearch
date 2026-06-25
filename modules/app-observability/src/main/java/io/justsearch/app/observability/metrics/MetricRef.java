/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.metrics;

import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.ResourceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Cross-link from a HealthEvent body (or other Resource entry) to a TIMESERIES Resource
 * the FE should render alongside it.
 *
 * <p>Per slice 3a.1.4 §B.9: body-level cross-links replace per-event-ID FE branching. A
 * Condition declares which metrics it correlates with; the FE iterates {@code relatedMetrics}
 * and dispatches each through the Resource-view renderer registry. Mirrors the
 * {@code recoveryResourceId} cross-link precedent from slice 438 — same pattern: body-level
 * {@link ResourceRef} pointer to a separately-registered registry entry.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@link #resourceId}: the {@link ResourceRef} of the TIMESERIES Resource entry to
 *       render alongside the parent event. The Resource must exist in a
 *       {@code ResourceCatalog} with {@code category = TIMESERIES}.
 *   <li>{@link #label}: optional override for the rendered label. Falls back to the
 *       Resource's {@link io.justsearch.agent.api.registry.Resource#presentation()} label
 *       when absent.
 *   <li>{@link #hint}: optional rendering shape ({@link RenderHint#SPARK SPARK} /
 *       {@link RenderHint#GAUGE GAUGE} / {@link RenderHint#HISTOGRAM HISTOGRAM}). Default
 *       at consumer site is {@code SPARK}.
 * </ul>
 *
 * <p>Wire format: camelCase Java fields, no {@code @JsonProperty} annotations. Mirrors the
 * {@link io.justsearch.app.observability.health.AssertedCondition} convention (FE-consumed
 * records ship as camelCase). Per ADR-08 §B.B (3a.1.3 §B.B moderation): the wire is
 * camelCase for FE-consumed records; the snake_case convention applies only to debug-state
 * records that opt in via class-level {@code @JsonNaming(SnakeCaseStrategy)}.
 */
public record MetricRef(
    ResourceRef resourceId, Optional<I18nKey> label, Optional<RenderHint> hint) {

  public MetricRef {
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(hint, "hint");
  }
}
