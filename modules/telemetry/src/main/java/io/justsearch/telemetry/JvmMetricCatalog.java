/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.justsearch.telemetry.catalog.EmptyTags;
import io.justsearch.telemetry.catalog.GaugeMetric;
import io.justsearch.telemetry.catalog.MetricCatalog;
import io.justsearch.telemetry.catalog.MetricDefinition;
import io.justsearch.telemetry.catalog.MetricRegistry;
import io.justsearch.telemetry.catalog.RrdArchive;
import io.justsearch.telemetry.catalog.Unit;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Objects;

/**
 * Tempdoc 417 Phase 3d catalog for JVM baseline gauges. Replaces
 * {@link JvmRuntimeGauges#register(Telemetry, String)}'s legacy {@code Telemetry.gauge(...)}
 * emit path with typed catalog gauges.
 *
 * <p>Prefix-parameterized: each process (Head, Worker, Launcher, Headless) constructs its own
 * catalog instance with the appropriate prefix. Metric names are built as
 * {@code prefix + ".jvm.<metric>"} so legacy wire format ({@code head.jvm.threads.live},
 * {@code worker.jvm.memory.heap.used_bytes}, etc.) is byte-stable.
 *
 * <p>Curated metrics ({@code *.jvm.memory.heap.used_bytes}, {@code *.jvm.threads.live}) declare
 * {@link RrdArchive#STANDARD} so the RRD store auto-includes them via
 * {@link RrdMetricStore}'s catalog-derive path (Phase 3b).
 */
public final class JvmMetricCatalog implements MetricCatalog {

  private final String namespace;
  private final List<MetricDefinition> definitions;

  /** Static factory: produces a {@link MetricCatalog} of definitions for the given prefix. */
  public static List<MetricDefinition> definitionsFor(String prefix) {
    String p = requirePrefix(prefix);
    return List.of(
        MetricDefinition.gauge(p + ".jvm.threads.live")
            .unit(Unit.COUNT)
            .archivedTo(RrdArchive.STANDARD)
            .build(),
        MetricDefinition.gauge(p + ".jvm.threads.daemon").unit(Unit.COUNT).build(),
        MetricDefinition.gauge(p + ".jvm.memory.heap.used_bytes")
            .unit(Unit.BYTES)
            .archivedTo(RrdArchive.STANDARD)
            .build(),
        MetricDefinition.gauge(p + ".jvm.memory.heap.committed_bytes").unit(Unit.BYTES).build(),
        // Tempdoc 430 Phase 8 (rev 3.11 §B.X.1): archived so the memory.pressure rule's CEL
        // expression `signals['head.jvm.memory.heap.max_bytes'].latest()` resolves via RRD.
        MetricDefinition.gauge(p + ".jvm.memory.heap.max_bytes")
            .unit(Unit.BYTES)
            .archivedTo(RrdArchive.STANDARD)
            .build(),
        MetricDefinition.gauge(p + ".jvm.memory.nonheap.used_bytes").unit(Unit.BYTES).build(),
        MetricDefinition.gauge(p + ".jvm.memory.nonheap.committed_bytes").unit(Unit.BYTES).build(),
        MetricDefinition.gauge(p + ".jvm.memory.process.virtual_bytes").unit(Unit.BYTES).build(),
        MetricDefinition.gauge(p + ".jvm.gc.collection_count").unit(Unit.COUNT).build(),
        MetricDefinition.gauge(p + ".jvm.gc.collection_time_ms").unit(Unit.MILLISECONDS).build());
  }

  /**
   * Wraps {@link #definitionsFor(String)} as a {@link MetricCatalog} suitable for
   * {@code LocalTelemetry}'s constructor.
   */
  public static MetricCatalog catalogFor(String prefix) {
    return MetricCatalog.of(requirePrefix(prefix), definitionsFor(prefix));
  }

  private static String requirePrefix(String prefix) {
    String p = prefix == null ? "" : prefix.trim();
    if (p.isEmpty()) {
      throw new IllegalArgumentException("prefix");
    }
    return p;
  }

  // Typed instrument fields — public final, populated by constructor.
  public final GaugeMetric<EmptyTags> threadsLive;
  public final GaugeMetric<EmptyTags> threadsDaemon;
  public final GaugeMetric<EmptyTags> heapUsedBytes;
  public final GaugeMetric<EmptyTags> heapCommittedBytes;
  public final GaugeMetric<EmptyTags> heapMaxBytes;
  public final GaugeMetric<EmptyTags> nonheapUsedBytes;
  public final GaugeMetric<EmptyTags> nonheapCommittedBytes;
  public final GaugeMetric<EmptyTags> processVirtualBytes;
  public final GaugeMetric<EmptyTags> gcCollectionCount;
  public final GaugeMetric<EmptyTags> gcCollectionTimeMs;

  public JvmMetricCatalog(MetricRegistry registry, String prefix) {
    Objects.requireNonNull(registry, "registry");
    String p = requirePrefix(prefix);
    this.namespace = p;
    this.definitions = definitionsFor(p);

    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    Runtime rt = Runtime.getRuntime();
    List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    var os = JvmRuntimeGauges.osMxBean();

    this.threadsLive =
        registry.buildGauge(
            p + ".jvm.threads.live",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeThreadCount(threads));
    this.threadsDaemon =
        registry.buildGauge(
            p + ".jvm.threads.daemon",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeDaemonThreadCount(threads));
    this.heapUsedBytes =
        registry.buildGauge(
            p + ".jvm.memory.heap.used_bytes",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeHeapUsedBytes(rt));
    this.heapCommittedBytes =
        registry.buildGauge(
            p + ".jvm.memory.heap.committed_bytes",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeHeapCommittedBytes(rt));
    this.heapMaxBytes =
        registry.buildGauge(
            p + ".jvm.memory.heap.max_bytes",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeHeapMaxBytes(rt));
    this.nonheapUsedBytes =
        registry.buildGauge(
            p + ".jvm.memory.nonheap.used_bytes",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeNonHeapUsedBytes(memory));
    this.nonheapCommittedBytes =
        registry.buildGauge(
            p + ".jvm.memory.nonheap.committed_bytes",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeNonHeapCommittedBytes(memory));
    this.processVirtualBytes =
        registry.buildGauge(
            p + ".jvm.memory.process.virtual_bytes",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeProcessVirtualBytes(os));
    this.gcCollectionCount =
        registry.buildGauge(
            p + ".jvm.gc.collection_count",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeGcCollectionCount(gcBeans));
    this.gcCollectionTimeMs =
        registry.buildGauge(
            p + ".jvm.gc.collection_time_ms",
            EmptyTags.INSTANCE,
            () -> (double) JvmRuntimeGauges.safeGcCollectionTimeMs(gcBeans));
  }

  @Override
  public String namespace() {
    return namespace;
  }

  @Override
  public List<MetricDefinition> definitions() {
    return definitions;
  }
}
