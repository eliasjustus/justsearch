/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schema for {@code index.watcher.events_total} as emitted by {@link WorkerMethvinWatcher}.
 * Carries the constant {@code component=worker_watcher} tag plus the lowercase enum-name
 * {@code kind} value (matching the pre-merge wire format introduced by tempdoc 418 Phase B3).
 *
 * <p>The Head-side {@code WatcherMetricCatalog} (in {@code app-indexing}) emits the same metric
 * name with only a {@code kind} tag. Worker and Head processes register their own
 * {@code LocalTelemetry}, so the per-View tag schemas don't conflict.
 */
public record WorkerWatcherEventTags(
    String component, WorkerMethvinWatcher.Kind kind) implements TagSchema {

  static final String KEY_COMPONENT = "component";
  static final String KEY_KIND = "kind";
  static final Set<String> KEYS;

  static {
    Set<String> keys = new LinkedHashSet<>();
    keys.add(KEY_COMPONENT);
    keys.add(KEY_KIND);
    KEYS = Set.copyOf(keys);
  }

  public WorkerWatcherEventTags {
    Objects.requireNonNull(component, "component");
    Objects.requireNonNull(kind, "kind");
  }

  /** Convenience: construct with the canonical {@code component="worker_watcher"}. */
  public static WorkerWatcherEventTags of(WorkerMethvinWatcher.Kind kind) {
    return new WorkerWatcherEventTags("worker_watcher", kind);
  }

  @Override
  public Set<String> allowedKeys() {
    return KEYS;
  }

  @Override
  public Attributes toAttributes() {
    return Attributes.builder()
        .put(AttributeKey.stringKey(KEY_COMPONENT), component)
        .put(AttributeKey.stringKey(KEY_KIND), kind.name().toLowerCase(Locale.ROOT))
        .build();
  }
}
