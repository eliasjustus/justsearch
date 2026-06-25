/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.configuration.PlatformPaths;
import io.justsearch.telemetry.DiagnosticFileRetention;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures thread dumps when API requests exceed a latency threshold.
 *
 * <p>Follows the CrashReporter pattern for JSON storage. Dumps are written to {@code
 * {dataDir}/slowapi/} with naming format {@code slow-{pid}-{epochMs}.json}.
 */
final class SlowRequestDumper {
  private static final Logger log = LoggerFactory.getLogger(SlowRequestDumper.class);

  private SlowRequestDumper() {}

  /**
   * Deletes slow-request dumps older than {@code maxAgeDays}.
   *
   * <p>Best-effort: failures are logged at DEBUG and never propagated.
   */
  static void pruneOldDumps(int maxAgeDays) {
    try {
      Path dir = PlatformPaths.resolveDataDir().resolve("slowapi");
      DiagnosticFileRetention.pruneBefore(
          dir, "slow-", Instant.now().minus(Duration.ofDays(maxAgeDays)));
    } catch (Exception e) {
      log.debug("Failed to prune old slow-request dumps", e);
    }
  }

  /**
   * Captures and stores a thread dump for a slow API request.
   *
   * @param route the request path
   * @param method the HTTP method
   * @param status the HTTP response status
   * @param durationMs actual request duration in milliseconds
   * @param thresholdMs the threshold that was exceeded
   * @param traceId active OTel trace ID for cross-correlation with traces.ndjson, or
   *     {@code null} when tracing is off / the span context is invalid. Tempdoc 518 Wave A
   *     defect Fix-9.
   */
  static void captureDump(
      String route,
      String method,
      int status,
      long durationMs,
      long thresholdMs,
      String traceId) {
    try {
      Path dir = PlatformPaths.resolveDataDir().resolve("slowapi");
      Files.createDirectories(dir);

      long pid = ProcessHandle.current().pid();
      long epochMs = System.currentTimeMillis();
      Path file = dir.resolve("slow-" + pid + "-" + epochMs + ".json");

      String threadDump = captureThreadDump();
      String json = buildJson(route, method, status, durationMs, thresholdMs, traceId, threadDump);
      Files.writeString(file, json);

      if (traceId != null) {
        log.info(
            "Captured slow-request dump: {} ({}ms, threshold {}ms, trace_id={})",
            file.getFileName(),
            durationMs,
            thresholdMs,
            traceId);
      } else {
        log.info(
            "Captured slow-request dump: {} ({}ms, threshold {}ms)",
            file.getFileName(),
            durationMs,
            thresholdMs);
      }
    } catch (Exception e) {
      log.debug("Failed to capture slow-request dump", e);
    }
  }

  private static String captureThreadDump() {
    StringBuilder sb = new StringBuilder();
    Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
    for (Map.Entry<Thread, StackTraceElement[]> entry : allStacks.entrySet()) {
      Thread t = entry.getKey();
      sb.append("\"")
          .append(t.getName())
          .append("\" ")
          .append(t.isDaemon() ? "daemon " : "")
          .append("prio=")
          .append(t.getPriority())
          .append(" tid=")
          .append(t.threadId())
          .append(" ")
          .append(t.getState())
          .append("\n");
      for (StackTraceElement frame : entry.getValue()) {
        sb.append("    at ").append(frame).append("\n");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

  private static String buildJson(
      String route,
      String method,
      int status,
      long durationMs,
      long thresholdMs,
      String traceId,
      String threadDump) {
    MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
    long heapUsed = mem.getHeapMemoryUsage().getUsed();
    long heapMax = mem.getHeapMemoryUsage().getMax();
    long uptime = ManagementFactory.getRuntimeMXBean().getUptime();

    // Manual JSON to avoid Jackson dependency (follows CrashReporter pattern). The traceId
    // field is null-rendered when tracing is off; the literal "null" is valid JSON and lets
    // consumers cleanly distinguish "no trace" from "no field" (tempdoc 518 Wave A Fix-9).
    String traceIdJson = traceId == null ? "null" : "\"" + escapeJson(traceId) + "\"";
    return """
        {
          "schema": "slowapi-dump.v1",
          "timestamp": "%s",
          "request": {
            "route": "%s",
            "method": "%s",
            "status": %d
          },
          "timing": {
            "durationMs": %d,
            "thresholdMs": %d
          },
          "trace": {
            "traceId": %s
          },
          "system": {
            "heapUsedBytes": %d,
            "heapMaxBytes": %d,
            "uptimeMs": %d
          },
          "threadDump": "%s"
        }
        """
        .formatted(
            Instant.now().toString(),
            escapeJson(route),
            escapeJson(method),
            status,
            durationMs,
            thresholdMs,
            traceIdJson,
            heapUsed,
            heapMax,
            uptime,
            escapeJson(threadDump));
  }

  private static String escapeJson(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
