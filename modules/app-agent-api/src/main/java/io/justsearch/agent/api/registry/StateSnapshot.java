/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.Objects;

/**
 * Per-surface state values carried by a {@link ShellAddress.Navigation} address.
 *
 * <p>Per slice 489 §4 — the navigation half of {@link ShellAddress} carries an
 * opaque-to-the-backend snapshot of FE state (query, filter ranges, scroll
 * position, selected hit id, etc.) that the surface restores on activation.
 *
 * <p>The map's value type is {@code Object} because URL-arg semantics produce a
 * mix of strings, numeric strings, and arrays of strings; downstream consumers
 * (the surface's restore handler on the FE) coerce against the surface's
 * declared {@link SurfaceStateSchema}. This matches the precedent set by
 * {@code HealthEventBody.attributes} in {@code contracts/wire/health.proto},
 * which carries arbitrary JSON-shaped data via {@code map<string,
 * google.protobuf.Value>} — JSON-shape losslessly. {@code app-agent-api} has
 * no Jackson databind dependency (annotations only per {@code Interface.java}
 * §E.5); the {@code Map<String, Object>} carrier is the Java-side equivalent.
 *
 * <p>Empty snapshot ({@link #empty}) is the canonical default for surface
 * activations that carry no state — refresh-restores a "back to defaults"
 * surface view.
 */
public record StateSnapshot(@JsonValue Map<String, Object> values) {

  public StateSnapshot {
    Objects.requireNonNull(values, "values");
    values = Map.copyOf(values);
  }

  public static StateSnapshot empty() {
    return new StateSnapshot(Map.of());
  }
}
