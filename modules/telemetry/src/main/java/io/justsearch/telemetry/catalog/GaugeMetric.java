/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import java.util.Objects;

/**
 * Typed asynchronous gauge backed by a callback supplier. The supplier is invoked at flush time
 * by the OTel SDK; the {@code AutoCloseable} handle holds the registration and is released when
 * the catalog/Telemetry shuts down.
 *
 * <p>This wrapper is intentionally minimal: gauges do not have an {@code emit} call. The supplier
 * passed at registration is the only emission path. Tag schema is preserved on the definition
 * (and applied at the OTel layer via the registered View) but is not exposed as a per-emit
 * parameter.
 *
 * @param <T> the tag schema type associated with this metric
 */
public final class GaugeMetric<T extends TagSchema> implements Metric {

  private final MetricDefinition definition;
  private final T tags;
  // Stored as Object: OTel's gaugeBuilder.buildWithCallback returns an opaque handle that is
  // currently AutoCloseable but not by public-API contract. Test at close-time via instanceof.
  private final Object handle;

  /**
   * Constructor — invoked by {@link MetricRegistry#buildGauge} only. Public to allow the
   * registry implementation to live in a sibling package; production code should not call this
   * directly.
   */
  public GaugeMetric(MetricDefinition definition, T tags, Object handle) {
    if (definition.kind() != InstrumentKind.GAUGE) {
      throw new IllegalArgumentException(
          "GaugeMetric requires kind=GAUGE; got " + definition.kind());
    }
    this.definition = Objects.requireNonNull(definition, "definition");
    this.tags = Objects.requireNonNull(tags, "tags");
    this.handle = Objects.requireNonNull(handle, "handle");
  }

  /**
   * Returns the immutable tag schema this gauge was registered with. Tags are baked into the
   * OTel async-callback at registration; this accessor exists for catalog introspection (e.g.,
   * structural tests verifying tag-schema/definition consistency).
   */
  public T tags() {
    return tags;
  }

  /** Releases the OTel callback registration. Idempotent / best-effort. */
  public void close() {
    if (handle instanceof AutoCloseable c) {
      try {
        c.close();
      } catch (Exception ignored) {
        // best-effort unregister
      }
    }
  }

  @Override
  public MetricDefinition definition() {
    return definition;
  }
}
