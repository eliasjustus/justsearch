/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;

/**
 * Writes structured JSON crash reports to disk when an uncaught exception terminates a thread.
 *
 * <p>Design constraints:
 *
 * <ul>
 *   <li>Zero dependencies beyond {@code java.base} and {@code java.management} — this class must
 *       not itself crash during crash handling.
 *   <li>No Jackson or Gson — JSON is built via {@link StringBuilder} to avoid transitive failures.
 *   <li>Entire public method is wrapped in try-catch — a failing crash reporter must never mask the
 *       original exception.
 * </ul>
 *
 * <p>Intended to be called from {@link Thread#setDefaultUncaughtExceptionHandler}.
 */
public final class CrashReporter {

  /** Schema version for crash report JSON files. */
  static final String SCHEMA_VERSION = "crash-report.v1";

  private CrashReporter() {}

  /**
   * Returns the default crash directory based on {@code justsearch.data.dir} system property.
   *
   * <p>Falls back to {@code ./crashes} if the property is not set.
   */
  public static Path defaultCrashDir() {
    return Path.of(System.getProperty("justsearch.data.dir", "."), "crashes");
  }

  /**
   * Deletes crash reports older than {@code maxAgeDays} from the given directory.
   *
   * <p>Best-effort: failures are logged to stderr and never propagated.
   */
  @SuppressWarnings("PMD.SystemPrintln")
  public static void pruneOldCrashReports(Path crashDir, int maxAgeDays) {
    try {
      DiagnosticFileRetention.pruneBefore(
          crashDir, "crash-", Instant.now().minus(Duration.ofDays(maxAgeDays)));
    } catch (Exception e) {
      System.err.println("[CrashReporter] Failed to prune old crash reports: " + e.getMessage());
    }
  }

  /**
   * Writes a JSON crash report to {@code crashDir}.
   *
   * <p>Filename format: {@code crash-<role>-<pid>-<epochMs>.json}
   *
   * @param crashDir directory to write into (created if missing)
   * @param processRole identifier for the process (e.g. "head", "worker")
   * @param thread the thread that threw the uncaught exception
   * @param throwable the uncaught exception
   */
  @SuppressWarnings("PMD.SystemPrintln")
  public static void writeCrashReport(
      Path crashDir, String processRole, Thread thread, Throwable throwable) {
    try {
      Files.createDirectories(crashDir);

      long pid = ProcessHandle.current().pid();
      long epochMs = System.currentTimeMillis();
      String fileName = "crash-" + processRole + "-" + pid + "-" + epochMs + ".json";

      Runtime rt = Runtime.getRuntime();
      long heapUsed = rt.totalMemory() - rt.freeMemory();
      long heapMax = rt.maxMemory();
      long uptime = ManagementFactory.getRuntimeMXBean().getUptime();

      StringBuilder sb = new StringBuilder(2048);
      sb.append("{\n");
      appendField(sb, "schema", SCHEMA_VERSION);
      appendField(sb, "timestamp", Instant.ofEpochMilli(epochMs).toString());
      appendField(sb, "process", processRole);
      appendNumericField(sb, "pid", pid);

      // Thread info
      sb.append("  \"thread\": {\n");
      appendField(sb, "    ", "name", thread.getName());
      appendLastNumericField(sb, "    ", "id", thread.threadId());
      sb.append("  },\n");

      // Exception info
      sb.append("  \"exception\": {\n");
      appendField(sb, "    ", "type", throwable.getClass().getName());
      appendField(sb, "    ", "message", throwable.getMessage());
      appendLastField(sb, "    ", "stackTrace", stackTraceToString(throwable));
      sb.append("  },\n");

      // System info
      sb.append("  \"system\": {\n");
      appendField(sb, "    ", "jvmVersion", System.getProperty("java.version", "unknown"));
      appendField(sb, "    ", "os", System.getProperty("os.name", "unknown"));
      appendNumericField(sb, "    ", "heapUsed", heapUsed);
      appendNumericField(sb, "    ", "heapMax", heapMax);
      appendLastNumericField(sb, "    ", "uptime", uptime);
      sb.append("  }\n");

      sb.append("}\n");

      Files.writeString(crashDir.resolve(fileName), sb.toString());

      // Also log to stderr as a last resort (Logback may be dead)
      System.err.println("[CrashReporter] Wrote crash report: " + crashDir.resolve(fileName));
    } catch (Exception e) {
      // Last resort: print to stderr. Never let the crash reporter mask the original exception.
      System.err.println("[CrashReporter] Failed to write crash report: " + e.getMessage());
    }
  }

  private static String stackTraceToString(Throwable throwable) {
    StringWriter sw = new StringWriter(1024);
    throwable.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }

  // --- JSON helpers (no library dependency) ---

  private static void appendField(StringBuilder sb, String key, String value) {
    appendField(sb, "  ", key, value);
    sb.setLength(sb.length()); // no-op but keeps pattern consistent
  }

  private static void appendField(StringBuilder sb, String indent, String key, String value) {
    sb.append(indent).append('"').append(key).append("\": \"");
    appendEscaped(sb, value);
    sb.append("\",\n");
  }

  private static void appendLastField(StringBuilder sb, String indent, String key, String value) {
    sb.append(indent).append('"').append(key).append("\": \"");
    appendEscaped(sb, value);
    sb.append("\"\n");
  }

  private static void appendNumericField(StringBuilder sb, String key, long value) {
    sb.append("  \"").append(key).append("\": ").append(value).append(",\n");
  }

  private static void appendNumericField(
      StringBuilder sb, String indent, String key, long value) {
    sb.append(indent).append('"').append(key).append("\": ").append(value).append(",\n");
  }

  private static void appendLastNumericField(
      StringBuilder sb, String indent, String key, long value) {
    sb.append(indent).append('"').append(key).append("\": ").append(value).append('\n');
  }

  private static void appendEscaped(StringBuilder sb, String value) {
    if (value == null) {
      sb.append("null");
      return;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append("\\u").append(String.format("%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
  }
}
