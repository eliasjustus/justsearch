/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small, low-cardinality JVM baseline gauges intended for local perf evidence and regression diffing.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>No high-cardinality labels</li>
 *   <li>Suppliers must be cheap and exception-safe</li>
 *   <li>Prefer role-prefixed names (e.g. head.* vs worker.*) to avoid accidental merging</li>
 * </ul>
 */
public final class JvmRuntimeGauges {

  private static final Logger log = LoggerFactory.getLogger(JvmRuntimeGauges.class);

  /** Counts errors encountered in safe* methods (for meta-telemetry observability). */
  private static final AtomicLong jvmGaugeErrors = new AtomicLong();

  /** Cached OS MX bean (null if jdk.management module unavailable). */
  private static final OperatingSystemMXBean cachedOsMxBean;

  static {
    cachedOsMxBean = resolveOsMxBean();
    if (cachedOsMxBean == null) {
      log.warn(
          "com.sun.management.OperatingSystemMXBean unavailable - process virtual memory metrics "
              + "will be disabled. Ensure jdk.management module is included in bundled runtime.");
    }
  }

  private JvmRuntimeGauges() {}

  /**
   * Returns the total number of errors encountered by JVM gauge suppliers.
   *
   * <p>This is useful for meta-telemetry: detecting when JVM metrics are silently failing.
   */
  @SuppressWarnings("unused") // Called from JvmRuntimeGaugesTest
  static long getJvmGaugeErrorCount() {
    return jvmGaugeErrors.get();
  }

  /**
   * Tempdoc 417 Phase 3d/3e: JVM gauges now flow through {@link JvmMetricCatalog}. This method
   * remains as a convenience that constructs the typed catalog when {@code telemetry} is a
   * {@link LocalTelemetry}; tests / non-production callers see no effect.
   *
   * <p>Async-gauge handles are owned by the catalog instance and drained by
   * {@link LocalTelemetry#close()}.
   */
  public static void register(Telemetry telemetry, String prefix) {
    if (telemetry instanceof LocalTelemetry lt) {
      // Construct the typed catalog — its async-gauge handles are retained on
      // LocalTelemetry's gaugeHandles list (drained on shutdown).
      new JvmMetricCatalog(lt.registry(), prefix);
    }
  }

  /** Returns the cached OS MX bean (null if unavailable). Package-private for catalog use. */
  static OperatingSystemMXBean osMxBean() {
    return cachedOsMxBean;
  }

  private static OperatingSystemMXBean resolveOsMxBean() {
    try {
      java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
      if (bean instanceof OperatingSystemMXBean) {
        return (OperatingSystemMXBean) bean;
      }
    } catch (Exception e) {
      // Fall through - module not available
    }
    return null;
  }

  static long safeThreadCount(ThreadMXBean mx) {
    try {
      return Math.max(0L, (long) mx.getThreadCount());
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return 0L;
    }
  }

  static long safeDaemonThreadCount(ThreadMXBean mx) {
    try {
      return Math.max(0L, (long) mx.getDaemonThreadCount());
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return 0L;
    }
  }

  static long safeHeapCommittedBytes(Runtime rt) {
    try {
      return Math.max(0L, rt.totalMemory());
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return 0L;
    }
  }

  static long safeHeapMaxBytes(Runtime rt) {
    try {
      return Math.max(0L, rt.maxMemory());
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return 0L;
    }
  }

  static long safeHeapUsedBytes(Runtime rt) {
    try {
      long used = rt.totalMemory() - rt.freeMemory();
      return Math.max(0L, used);
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return 0L;
    }
  }

  static long safeGcCollectionCount(List<GarbageCollectorMXBean> beans) {
    try {
      return beans.stream()
          .mapToLong(GarbageCollectorMXBean::getCollectionCount)
          .filter(c -> c >= 0)
          .sum();
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return 0L;
    }
  }

  static long safeGcCollectionTimeMs(List<GarbageCollectorMXBean> beans) {
    try {
      return beans.stream()
          .mapToLong(GarbageCollectorMXBean::getCollectionTime)
          .filter(t -> t >= 0)
          .sum();
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return 0L;
    }
  }

  static long safeNonHeapUsedBytes(MemoryMXBean memory) {
    try {
      return Math.max(0L, memory.getNonHeapMemoryUsage().getUsed());
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return 0L;
    }
  }

  static long safeNonHeapCommittedBytes(MemoryMXBean memory) {
    try {
      return Math.max(0L, memory.getNonHeapMemoryUsage().getCommitted());
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return 0L;
    }
  }

  /**
   * Returns process virtual memory size (not RSS).
   *
   * <p>Uses com.sun.management.OperatingSystemMXBean.getCommittedVirtualMemorySize().
   * Note: This includes mmap'd files (like Lucene indexes) which inflate the value
   * beyond actual physical RAM usage. For true RSS, platform-specific code is needed.
   *
   * <p>Returns -1 if unavailable.
   */
  static long safeProcessVirtualBytes(OperatingSystemMXBean os) {
    try {
      if (os == null) return -1L;
      long committed = os.getCommittedVirtualMemorySize();
      return committed >= 0 ? committed : -1L;
    } catch (Exception e) {
      jvmGaugeErrors.incrementAndGet();
      return -1L;
    }
  }
}
