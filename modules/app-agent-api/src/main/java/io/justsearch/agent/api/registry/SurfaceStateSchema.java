/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Typed contract declaring what state a {@link Surface} accepts and exposes.
 *
 * <p>Per slice 489 §5 (load-bearing addition): {@code Operation.intf.inputSchema}
 * declares the typed contract for an Operation's args; {@code SurfaceStateSchema}
 * is the symmetric declaration for a Surface's addressable state. Without it
 * every surface's URL serialization is reverse-engineered from its FE store
 * (the failure mode §11.1 enumerates).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@link #schema}: JSON Schema source text describing the addressable
 *       state shape (e.g. {@code {"type":"object","properties":{"query":
 *       {"type":"string"},"filters":{...}}}}). Same carrier convention as
 *       {@code Interface.inputs}: {@code String} rather than parsed
 *       {@code JsonNode}, because {@code app-agent-api} has no Jackson
 *       databind dependency (annotations only per {@code Interface.java} §E.5).
 *       Emitters in {@code app-services} parse this through their own
 *       {@code ObjectMapper} when needed.
 *   <li>{@link #bindings}: per-field {@link StateBinding} mapping each
 *       schema field to an abstract FE store identifier. The FE resolves the
 *       abstract identifier to a concrete store implementation at runtime.
 * </ul>
 *
 * <p>Per slice 489 §17.2 — this field is <strong>optional</strong> on
 * {@link Surface}. Surfaces that have not yet declared state-schema cannot
 * be URL-addressed; the chrome falls through to default state on activation.
 * This is the backwards-compat-tractable default for the initial substrate
 * rollout; a future slice may promote it to mandatory.
 *
 * <p>Per slice 489 §5 secondary benefit: the schema also serves agent / palette /
 * health-diagnostic / E2E-fixture introspection — "what state does this surface
 * expose?" gets a typed answer.
 */
public record SurfaceStateSchema(String schema, List<StateBinding> bindings) {

  public SurfaceStateSchema {
    Objects.requireNonNull(schema, "schema");
    bindings = bindings == null ? List.of() : List.copyOf(bindings);
    if (schema.isBlank()) {
      throw new IllegalArgumentException("schema must be non-blank JSON Schema source");
    }
  }

  /** Convenience: schema with no declared bindings (treated as opaque state). */
  public static SurfaceStateSchema ofSchema(String schema) {
    return new SurfaceStateSchema(schema, List.of());
  }
}
