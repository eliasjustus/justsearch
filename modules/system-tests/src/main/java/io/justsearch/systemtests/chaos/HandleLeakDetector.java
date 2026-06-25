/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.chaos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects file handle leaks in processes.
 *
 * <p>This utility queries the OS to find which files a process has open.
 * Used to verify that the Main process never directly opens Lucene index
 * files or SQLite databases.
 *
 * <p><b>Windows:</b> Uses PowerShell to query file handles
 * <p><b>Linux/macOS:</b> Uses lsof to query open files
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * HandleLeakDetector detector = new HandleLeakDetector();
 * List<String> luceneHandles = detector.findHandles(mainPid, "lucene");
 * assertTrue(luceneHandles.isEmpty(), "Main should not hold Lucene handles");
 * }</pre>
 */
public final class HandleLeakDetector {
  private static final Logger log = LoggerFactory.getLogger(HandleLeakDetector.class);
  private static final boolean IS_WINDOWS = System.getProperty("os.name")
      .toLowerCase(Locale.ROOT).contains("windows");

  /**
   * Finds file handles matching a pattern for a given process.
   *
   * @param pid The process ID to inspect
   * @param pattern Regex pattern to match in file paths
   * @return List of matching file paths
   */
  public List<String> findHandles(long pid, String pattern) throws IOException, InterruptedException {
    if (IS_WINDOWS) {
      return findHandlesWindows(pid, pattern);
    } else {
      return findHandlesUnix(pid, pattern);
    }
  }

  /**
   * Finds all open file handles for a process.
   *
   * @param pid The process ID to inspect
   * @return List of all open file paths
   */
  public List<String> findAllHandles(long pid) throws IOException, InterruptedException {
    return findHandles(pid, ".*");
  }

  /**
   * Checks if a process has any handles matching the pattern.
   *
   * @param pid The process ID to inspect
   * @param pattern Regex pattern to match
   * @return true if any matching handles are found
   */
  public boolean hasHandles(long pid, String pattern) throws IOException, InterruptedException {
    return !findHandles(pid, pattern).isEmpty();
  }

  /**
   * Checks for Lucene index file handles.
   *
   * @param pid The process ID to inspect
   * @return List of Lucene-related file handles
   */
  public List<String> findLuceneHandles(long pid) throws IOException, InterruptedException {
    // Match common Lucene file extensions: .cfs, .cfe, .si, .fnm, etc.
    return findHandles(pid, "(?i).*\\.(cfs|cfe|si|fnm|fdx|fdt|tim|tip|doc|pos|pay|nvd|nvm)$");
  }

  /**
   * Checks for SQLite database file handles.
   *
   * @param pid The process ID to inspect
   * @return List of SQLite-related file handles
   */
  public List<String> findSqliteHandles(long pid) throws IOException, InterruptedException {
    return findHandles(pid, "(?i).*\\.db$|.*-wal$|.*-shm$");
  }

  private List<String> findHandlesWindows(long pid, String pattern) throws IOException, InterruptedException {
    // PowerShell command to get file handles
    // Note: This requires admin rights for full visibility
    String command = String.format(
        "Get-Process -Id %d -ErrorAction SilentlyContinue | " +
        "ForEach-Object { $_.Modules } | " +
        "Select-Object -ExpandProperty FileName 2>$null",
        pid
    );

    List<String> result = new ArrayList<>();
    Pattern regex = Pattern.compile(pattern);

    ProcessBuilder pb = new ProcessBuilder(
        "powershell.exe", "-NoProfile", "-Command", command);
    pb.redirectErrorStream(true);

    Process process = pb.start();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (regex.matcher(line).matches()) {
          result.add(line);
        }
      }
    }

    int exitCode = process.waitFor();
    if (exitCode != 0) {
      log.debug("PowerShell command exited with code {}", exitCode);
    }

    // Also try to find open files using alternative method
    result.addAll(findHandlesWithHandle64(pid, pattern));

    return result;
  }

  /**
   * Uses Sysinternals Handle64.exe if available.
   * This is more comprehensive but requires the tool to be installed.
   */
  private List<String> findHandlesWithHandle64(long pid, String pattern)
      throws IOException, InterruptedException {
    List<String> result = new ArrayList<>();

    // Check if handle64.exe is available
    ProcessBuilder checkPb = new ProcessBuilder("where", "handle64.exe");
    Process checkProcess = checkPb.start();
    if (checkProcess.waitFor() != 0) {
      log.debug("handle64.exe not found, skipping enhanced detection");
      return result;
    }

    Pattern regex = Pattern.compile(pattern);
    ProcessBuilder pb = new ProcessBuilder(
        "handle64.exe", "-p", String.valueOf(pid), "-nobanner");
    pb.redirectErrorStream(true);

    Process process = pb.start();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        // Handle64 output format: "pid: File  (---): path"
        if (line.contains("File") && regex.matcher(line).find()) {
          result.add(line.trim());
        }
      }
    }

    process.waitFor();
    return result;
  }

  private List<String> findHandlesUnix(long pid, String pattern) throws IOException, InterruptedException {
    List<String> result = new ArrayList<>();
    Pattern regex = Pattern.compile(pattern);

    ProcessBuilder pb = new ProcessBuilder("lsof", "-p", String.valueOf(pid));
    pb.redirectErrorStream(true);

    Process process = pb.start();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        // lsof output has filename in the last column
        String[] parts = line.split("\\s+");
        if (parts.length >= 9) {
          String filename = parts[parts.length - 1];
          if (regex.matcher(filename).matches()) {
            result.add(filename);
          }
        }
      }
    }

    process.waitFor();
    return result;
  }

  /**
   * Verifies that a process holds no Lucene or SQLite handles.
   *
   * @param pid The process ID to verify
   * @throws AssertionError if forbidden handles are found
   */
  public void assertNoIndexHandles(long pid) throws IOException, InterruptedException {
    List<String> luceneHandles = findLuceneHandles(pid);
    List<String> sqliteHandles = findSqliteHandles(pid);

    if (!luceneHandles.isEmpty() || !sqliteHandles.isEmpty()) {
      StringBuilder msg = new StringBuilder("Process " + pid + " holds forbidden handles:\n");
      for (String h : luceneHandles) {
        msg.append("  [Lucene] ").append(h).append("\n");
      }
      for (String h : sqliteHandles) {
        msg.append("  [SQLite] ").append(h).append("\n");
      }
      throw new AssertionError(msg.toString());
    }

    log.info("Process {} holds no Lucene or SQLite handles", pid);
  }
}
