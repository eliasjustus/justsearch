/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schema for {@code ingestion.outcome_write_failures_total}. Carries the constant
 * {@code component} tag (typically {@code "indexing_loop"}) for byte-stable wire format with the
 * pre-417-merge legacy emission introduced by tempdoc 410.
 */
public record IngestionOutcomeTags(String component) implements TagSchema {

  static final String KEY = "component";
  static final Set<String> KEYS = Set.of(KEY);

  public IngestionOutcomeTags {
    Objects.requireNonNull(component, "component");
  }

  /** Convenience: construct with the canonical {@code component="indexing_loop"}. */
  public static IngestionOutcomeTags ofIndexingLoop() {
    return new IngestionOutcomeTags("indexing_loop");
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
