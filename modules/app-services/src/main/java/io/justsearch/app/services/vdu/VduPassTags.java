/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schema for {@code vdu.pass1.duration_ms}, {@code vdu.pass2.duration_ms} and
 * {@code vdu.total.duration_ms}. Carries the constant {@code component=vdu} tag for wire-format
 * byte-stability with the pre-refactor emission from {@link VduProcessor}.
 *
 * <p>Tempdoc 417 Phase 3d: replaces the legacy {@code Telemetry.Timer} with
 * {@code component="vdu"} tags.
 */
public record VduPassTags(String component) implements TagSchema {

  static final String KEY = "component";
  static final Set<String> KEYS = Set.of(KEY);

  public VduPassTags {
    Objects.requireNonNull(component, "component");
  }

  /** Convenience: construct with the canonical {@code component="vdu"}. */
  public static VduPassTags of() {
    return new VduPassTags("vdu");
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
