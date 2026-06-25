/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 560 §4c Phase B — the typed wire-view of an {@link Operation} as served at {@code
 * /api/registry/operations}. {@code UIOperationEmitter} builds this record and serializes it, so the
 * Operation wire has ONE authority and its FE projection (record→JSON-Schema→{TS,Zod}) is faithful
 * (closing the §13.2 record↔emitter divergence: the emitter previously hand-built a divergent JSON
 * tree the record schema did not describe).
 *
 * <p>The shape mirrors the historical hand-built emitter output byte-for-byte (pinned by {@code
 * UIOperationViewConformanceTest}): field order is declaration order; enums serialize as their
 * uppercase {@code name()} (the registry enums carry no transforming {@code @JsonValue}); {@code
 * iconHint}/{@code category}/{@code rateLimitMs}/availability fields are omitted when absent;
 * {@code inverseOperationId} is always present (null when the op declares no inverse). The {@code
 * intf} inputs/result and availability expressions are arbitrary JSON (a JSON-Schema source / an
 * availability AST), carried as {@code Object} (a Jackson tree node at runtime; {@code app-agent-api}
 * has only the Jackson annotations on its classpath, not databind) — opaque to the FE, which only
 * forwards them.
 */
public record UIOperationView(
    String id,
    String type,
    PresentationView presentation,
    InterfaceView intf,
    PolicyView policy,
    AvailabilityView availability,
    LineageView lineage,
    ProvenanceView provenance,
    List<ExecutorTag> executors,
    Audience audience,
    List<ConsumerView> consumers)
    implements PreciseWire {

  /**
   * Display copy + iconography hooks; iconHint/category omitted when absent (field-level {@code
   * NON_NULL} so the always-present copy keys stay {@code required} under {@link PreciseWire}).
   */
  public record PresentationView(
      String labelKey,
      String descriptionKey,
      @JsonInclude(JsonInclude.Include.NON_NULL) String iconHint,
      @JsonInclude(JsonInclude.Include.NON_NULL) String category)
      implements PreciseWire {}

  /** Interface escape hatch: inputs/result are arbitrary JSON-Schema source; uiHints a typed map. */
  public record InterfaceView(
      Object inputs, Object result, List<String> errors, Map<String, Object> uiHints)
      implements PreciseWire {}

  /**
   * Policy axes. {@code rateLimitMs} is omitted when absent; {@code inverseOperationId} is always
   * present (null when the op declares no inverse, matching the FE {@code inverseOperationId?} field)
   * — present-as-null, so {@link Nullable} keeps it {@code required} + nullable in the schema.
   */
  public record PolicyView(
      RiskTier risk,
      ConfirmView confirm,
      AuditPolicy audit,
      boolean undoSupported,
      @JsonInclude(JsonInclude.Include.NON_NULL) Long rateLimitMs,
      @Nullable String inverseOperationId)
      implements PreciseWire {}

  /** Confirmation strategy discriminated by {@code kind}; {@code confirmTextKey} present only for TYPED. */
  public record ConfirmView(
      String kind, @JsonInclude(JsonInclude.Include.NON_NULL) String confirmTextKey)
      implements PreciseWire {}

  /** Discovery-time availability gate; both fields omitted when absent. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AvailabilityView(Object expression, Object argumentDefaultsJson)
      implements PreciseWire {}

  /** Cross-primitive lineage (sorted id lists). */
  public record LineageView(List<String> affects, List<String> supersedes) implements PreciseWire {}

  /** Three-field provenance (the operation wire omits the identity axis). */
  public record ProvenanceView(TrustTier tier, String contributorId, String version)
      implements PreciseWire {}
}
