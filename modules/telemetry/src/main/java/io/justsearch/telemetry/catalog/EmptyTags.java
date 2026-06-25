/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import io.opentelemetry.api.common.Attributes;
import java.util.Set;

/**
 * Singleton {@link TagSchema} for tagless metrics. Use for counters/gauges that have no tag
 * dimensions (e.g., {@code index.runtime.hard_delete_total}).
 */
public final class EmptyTags implements TagSchema {

  public static final EmptyTags INSTANCE = new EmptyTags();

  private EmptyTags() {}

  @Override
  public Set<String> allowedKeys() {
    return Set.of();
  }

  @Override
  public Attributes toAttributes() {
    return Attributes.empty();
  }
}
