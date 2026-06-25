/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks.util;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures machine fingerprint information for benchmark reproducibility.
 *
 * <p>This record provides a standardized way to capture machine characteristics that may affect
 * benchmark results, enabling comparison across different environments.
 *
 * @param hostname the machine hostname
 * @param osName the operating system name (e.g., "Windows 11")
 * @param osVersion the operating system version
 * @param javaVersion the Java version (e.g., "25")
 * @param availableProcessors the number of available processors
 * @param totalRamBytes total physical RAM in bytes, or -1 if unavailable
 */
public record MachineFingerprint(
    String hostname,
    String osName,
    String osVersion,
    String javaVersion,
    int availableProcessors,
    long totalRamBytes) {

  /**
   * Capture the current machine's fingerprint.
   *
   * <p>All fields are guaranteed non-null; unknown values use "unknown" as fallback. RAM is -1 if
   * unavailable (e.g., on exotic JVM implementations).
   *
   * @return a new MachineFingerprint with current system information
   */
  public static MachineFingerprint capture() {
    String hostname;
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      hostname = "unknown";
    }

    // Get total RAM via com.sun.management API (available on HotSpot/OpenJ9)
    long totalRam = -1;
    try {
      var osBean =
          (com.sun.management.OperatingSystemMXBean)
              ManagementFactory.getOperatingSystemMXBean();
      totalRam = osBean.getTotalMemorySize();
    } catch (Exception e) {
      // Fallback: -1 indicates unavailable (e.g., non-HotSpot JVM)
    }

    return new MachineFingerprint(
        hostname,
        nullSafe(System.getProperty("os.name")),
        nullSafe(System.getProperty("os.version")),
        nullSafe(System.getProperty("java.version")),
        Runtime.getRuntime().availableProcessors(),
        totalRam);
  }

  private static String nullSafe(String value) {
    return value != null ? value : "unknown";
  }

  /**
   * Convert to a Map for JSON serialization.
   *
   * <p>The total_ram_bytes field is only included if available (not -1).
   *
   * @return a map containing all fingerprint fields
   */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("hostname", hostname);
    map.put("os_name", osName);
    map.put("os_version", osVersion);
    map.put("java_version", javaVersion);
    map.put("available_processors", availableProcessors);
    if (totalRamBytes > 0) {
      map.put("total_ram_bytes", totalRamBytes);
    }
    return map;
  }
}
