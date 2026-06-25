/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Maps one field of a {@link SurfaceStateSchema} JSON-Schema source to an abstract
 * FE-store identifier the chrome resolves at runtime.
 *
 * <p>Per slice 489 §5: each addressable surface state field declares
 * <em>where</em> the FE stores its value. The mapping is intentionally
 * abstract — {@code storeId} is a stable identifier the FE registers under,
 * not the literal module path of a Lit pub-sub module. The FE binds the
 * abstract identifier to whatever its current store implementation is (today:
 * module-level pub-sub modules under {@code modules/ui-web/src/shell-v0/state/};
 * tomorrow: Zustand / signals / anything).
 *
 * <p>This follows the {@code Operation.Interface.uiHints} precedent
 * ({@code Map<String, UIHint>}) — Layer-1 escape-hatch FE-aware metadata
 * declared on a backend record (tempdoc 429 §C.E). The codebase has settled
 * precedent for this shape.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@link #schemaPath}: JSON Pointer ({@code RFC 6901}) into the
 *       {@link SurfaceStateSchema#schema()} — e.g. {@code "/query"} or
 *       {@code "/filters/dateRange"}.
 *   <li>{@link #storeId}: abstract identifier the FE binds to a concrete store
 *       (e.g. {@code "search"}, {@code "search.filters"}, {@code "inspector"}).
 *   <li>{@link #storeKey}: field path within that store (e.g. {@code "query"},
 *       {@code "dateRange"}). Empty string means "the whole store value."
 * </ul>
 */
public record StateBinding(String schemaPath, String storeId, String storeKey) {

  public StateBinding {
    Objects.requireNonNull(schemaPath, "schemaPath");
    Objects.requireNonNull(storeId, "storeId");
    Objects.requireNonNull(storeKey, "storeKey");
    if (schemaPath.isBlank()) {
      throw new IllegalArgumentException("schemaPath must be non-blank");
    }
    if (!schemaPath.startsWith("/")) {
      throw new IllegalArgumentException(
          "schemaPath must be a JSON Pointer starting with '/', got: " + schemaPath);
    }
    if (storeId.isBlank()) {
      throw new IllegalArgumentException("storeId must be non-blank");
    }
  }
}
