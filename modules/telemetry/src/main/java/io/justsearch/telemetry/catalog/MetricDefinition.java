/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry.catalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable description of a metric: identity, schema, and emit-time configuration. The catalog
 * hands a list of these to {@link io.justsearch.telemetry.LocalTelemetry} at boot, which uses
 * them to register OTel Views before the {@code SdkMeterProvider} is built.
 *
 * <p>Use the builder factories ({@link #counter}, {@link #histogram}, {@link #gauge},
 * {@link #observableCounter}) rather than the canonical record constructor — the builders enforce
 * which fields are required for each instrument kind.
 *
 * <p>Tempdoc 417 Phase 3a: {@code rrdArchive} (RRD time-series archive policy) and
 * {@code statusEndpoint} + {@code statusFieldName} (status-endpoint wiring) reintroduced after
 * F5 deletion. Phase 3b drives the RRD curated-set derivation and the ArchUnit field-name
 * validator off these declarations.
 *
 * @param name fully-qualified metric name (must match the catalog's namespace prefix)
 * @param kind instrument kind
 * @param unit typed unit
 * @param allowedTagKeys set of tag keys (LinkedHashSet preserves declaration order for
 *     deterministic NDJSON wire-format ordering)
 * @param bucketBoundaries histogram bucket boundaries; null for non-histograms
 * @param exemplarPolicy per-metric exemplar policy
 * @param cardinalityLimit upper bound on distinct attribute combinations the SDK will track for
 *     this metric; null falls through to the OTel default (2000). Tempdoc 417 Phase 2a: defensive
 *     guard for tag schemas with open {@code String} fields (route paths, status codes).
 * @param rrdArchive optional RRD time-series archive policy. When non-null, the metric is
 *     included in {@code RrdMetricStore}'s curated set and recorded on every flush. Tempdoc 417
 *     Phase 3a.
 * @param statusEndpoint optional API status surface this metric's value appears in. When set,
 *     {@link #statusFieldName} must be the record-component name on the corresponding API
 *     record. An ArchUnit rule validates the match.
 * @param statusFieldName API record field name corresponding to this metric. Required iff
 *     {@code statusEndpoint} is non-null.
 */
public record MetricDefinition(
    String name,
    InstrumentKind kind,
    Unit unit,
    Set<String> allowedTagKeys,
    List<Long> bucketBoundaries,
    Exemplars exemplarPolicy,
    Integer cardinalityLimit,
    RrdArchive rrdArchive,
    StatusEndpoint statusEndpoint,
    String statusFieldName) {

  public MetricDefinition {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(allowedTagKeys, "allowedTagKeys");
    Objects.requireNonNull(exemplarPolicy, "exemplarPolicy");

    allowedTagKeys = new LinkedHashSet<>(allowedTagKeys); // preserve order, defensive copy
    if (bucketBoundaries != null) {
      bucketBoundaries = List.copyOf(bucketBoundaries);
    }
    if (kind != InstrumentKind.HISTOGRAM && bucketBoundaries != null) {
      throw new IllegalArgumentException(
          "bucketBoundaries are only valid for HISTOGRAM kind; got " + kind + " for " + name);
    }
    if (cardinalityLimit != null && cardinalityLimit <= 0) {
      throw new IllegalArgumentException(
          "cardinalityLimit must be positive; got " + cardinalityLimit + " for " + name);
    }
    if (statusEndpoint != null && (statusFieldName == null || statusFieldName.isBlank())) {
      throw new IllegalArgumentException(
          "statusFieldName is required when statusEndpoint is set; got null/blank for " + name);
    }
    if (statusEndpoint == null && statusFieldName != null) {
      throw new IllegalArgumentException(
          "statusFieldName supplied without statusEndpoint for " + name);
    }
  }

  public static Builder counter(String name) {
    return new Builder(name, InstrumentKind.COUNTER);
  }

  public static Builder histogram(String name) {
    return new Builder(name, InstrumentKind.HISTOGRAM);
  }

  public static Builder gauge(String name) {
    return new Builder(name, InstrumentKind.GAUGE);
  }

  public static Builder observableCounter(String name) {
    return new Builder(name, InstrumentKind.OBSERVABLE_COUNTER);
  }

  /** Fluent builder. Required fields throw at {@link #build()}. */
  public static final class Builder {
    private final String name;
    private final InstrumentKind kind;
    private Unit unit = Unit.NONE;
    private Set<String> allowedTagKeys = Set.of();
    private List<Long> bucketBoundaries;
    private Exemplars exemplarPolicy = Exemplars.TRACE_BASED;
    private Integer cardinalityLimit;
    private RrdArchive rrdArchive;
    private StatusEndpoint statusEndpoint;
    private String statusFieldName;

    private Builder(String name, InstrumentKind kind) {
      this.name = name;
      this.kind = kind;
    }

    public Builder unit(Unit unit) {
      this.unit = unit;
      return this;
    }

    public Builder tagKeys(Set<String> keys) {
      this.allowedTagKeys = keys;
      return this;
    }

    public Builder buckets(List<Long> bucketBoundaries) {
      this.bucketBoundaries = bucketBoundaries;
      return this;
    }

    public Builder exemplars(Exemplars policy) {
      this.exemplarPolicy = policy;
      return this;
    }

    /**
     * Cap the number of distinct attribute combinations this metric tracks. Defaults to OTel's
     * 2000 when unset. Set to a tighter value when a tag schema includes open {@code String}
     * fields (route paths, HTTP status codes) that have a knowable upper bound in practice.
     */
    public Builder cardinalityLimit(int limit) {
      this.cardinalityLimit = limit;
      return this;
    }

    /**
     * Declare that this metric should be archived to {@code RrdMetricStore} for time-series
     * trend analysis. Tempdoc 417 Phase 3a.
     */
    public Builder archivedTo(RrdArchive archive) {
      this.rrdArchive = archive;
      return this;
    }

    /**
     * Declare that this metric's value appears as {@code fieldName} on the API record
     * corresponding to {@code endpoint}. An ArchUnit rule validates the field name matches an
     * actual record component. Tempdoc 417 Phase 3a.
     */
    public Builder surfacedAt(StatusEndpoint endpoint, String fieldName) {
      this.statusEndpoint = endpoint;
      this.statusFieldName = fieldName;
      return this;
    }

    public MetricDefinition build() {
      return new MetricDefinition(
          name,
          kind,
          unit,
          allowedTagKeys,
          bucketBoundaries,
          exemplarPolicy,
          cardinalityLimit,
          rrdArchive,
          statusEndpoint,
          statusFieldName);
    }
  }
}
