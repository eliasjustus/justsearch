/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import io.opentelemetry.api.common.Attributes;
import java.util.Set;

/**
 * Compile-time tag schema for a metric. Concrete implementations are typically records that
 * carry sealed-type field values (e.g., {@code CommitTags(CommitReason reason)}); the record's
 * canonical constructor enforces non-null values, and {@link #toAttributes()} translates to OTel
 * {@code Attributes} for emission.
 *
 * <p>Catalog-registered metrics carry their {@code TagSchema} type as a generic parameter, so
 * passing the wrong tag type to {@code Metric.record(value, tags)} fails at compile time.
 */
public interface TagSchema {

  /**
   * Returns the set of tag keys this schema declares. Order is preserved by implementations
   * (recommended: {@link java.util.LinkedHashSet} or list-backed). The catalog wires this set as
   * the per-View {@code setAttributeFilter} at SDK boot, so any key absent from this set will be
   * stripped by the SDK before reaching the exporter.
   */
  Set<String> allowedKeys();

  /** Returns the OTel {@link Attributes} representation of this schema's current values. */
  Attributes toAttributes();
}
