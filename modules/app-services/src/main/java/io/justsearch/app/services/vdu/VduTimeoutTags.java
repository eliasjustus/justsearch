/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schema for {@code vdu.timeout_total}. Carries the constant {@code component=vdu} tag for
 * wire-format byte-stability with the pre-refactor emission from {@code VduProcessor}.
 *
 * <p>Tempdoc 417 F2: replaces {@link io.justsearch.telemetry.catalog.EmptyTags} on this metric.
 */
public record VduTimeoutTags(String component) implements TagSchema {

  static final String KEY = "component";
  static final Set<String> KEYS = Set.of(KEY);

  public VduTimeoutTags {
    Objects.requireNonNull(component, "component");
  }

  /** Convenience: construct with the canonical {@code component="vdu"}. */
  public static VduTimeoutTags of() {
    return new VduTimeoutTags("vdu");
  }

  @Override
  public Set<String> allowedKeys() {
    return KEYS;
  }

  @Override
  public Attributes toAttributes() {
    return Attributes.of(AttributeKey.stringKey(KEY), component);
  }
}
