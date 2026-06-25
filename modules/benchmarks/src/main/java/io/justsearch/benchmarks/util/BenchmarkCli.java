/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks.util;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Utility functions for parsing command-line arguments in benchmark classes.
 *
 * <p>This class provides simple argument parsing helpers that match the existing ad-hoc parsing
 * patterns used in the benchmark classes.
 */
public final class BenchmarkCli {
  private BenchmarkCli() {}

  /**
   * Parse a string argument of the form {@code --key=value}.
   *
   * @param arg the argument string to parse
   * @param key the key to look for (without the {@code --} prefix)
   * @return the value if the argument matches, empty otherwise
   */
  public static Optional<String> parseString(String arg, String key) {
    String prefix = "--" + key + "=";
    if (arg.startsWith(prefix)) {
      return Optional.of(arg.substring(prefix.length()));
    }
    return Optional.empty();
  }

  /**
   * Parse an integer argument of the form {@code --key=123}.
   *
   * <p>If the value cannot be parsed as an integer, a warning is printed to stderr and empty is
   * returned.
   *
   * @param arg the argument string to parse
   * @param key the key to look for (without the {@code --} prefix)
   * @return the integer value if valid, empty otherwise
   */
  @SuppressWarnings("PMD.SystemPrintln")
  public static Optional<Integer> parseInt(String arg, String key) {
    return parseString(arg, key)
        .map(
            s -> {
              try {
                return Integer.parseInt(s);
              } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid integer for --" + key + ": " + s);
                return null;
              }
            });
  }

  /**
   * Parse a comma-separated list of positive integers.
   *
   * <p>Matches {@code FilteredKnnBench.parseSizes()} behavior:
   *
   * <ul>
   *   <li>Ignores empty strings
   *   <li>Ignores invalid integers (with warning to stderr)
   *   <li>Filters out non-positive values (v &lt;= 0)
   * </ul>
   *
   * @param csv comma-separated string like "10,100,1000"
   * @return list of positive integers
   */
  @SuppressWarnings("PMD.SystemPrintln")
  public static List<Integer> parsePositiveIntCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(
            s -> {
              try {
                return Integer.parseInt(s);
              } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid integer in CSV: " + s);
                return null;
              }
            })
        .filter(Objects::nonNull)
        .filter(v -> v > 0) // Match FilteredKnnBench: only positive values
        .collect(Collectors.toList());
  }

  /**
   * Parse a comma-separated list of non-negative integers (0 allowed).
   *
   * <p>Similar to {@link #parsePositiveIntCsv} but allows 0 values. Useful for parameters where 0
   * has a special meaning (e.g., "use system default").
   *
   * <ul>
   *   <li>Ignores empty strings
   *   <li>Ignores invalid integers (with warning to stderr)
   *   <li>Filters out negative values (v &lt; 0)
   * </ul>
   *
   * @param csv comma-separated string like "0,50,100"
   * @return list of non-negative integers
   */
  @SuppressWarnings("PMD.SystemPrintln")
  public static List<Integer> parseNonNegativeIntCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(
            s -> {
              try {
                return Integer.parseInt(s);
              } catch (NumberFormatException e) {
                System.err.println("Warning: Invalid integer in CSV: " + s);
                return null;
              }
            })
        .filter(Objects::nonNull)
        .filter(v -> v >= 0) // Allow 0, filter negative
        .collect(Collectors.toList());
  }

  /**
   * Check if an argument is a flag of the form {@code --key}.
   *
   * @param arg the argument string to check
   * @param key the key to look for (without the {@code --} prefix)
   * @return true if the argument matches the flag
   */
  public static boolean parseFlag(String arg, String key) {
    return arg.equals("--" + key);
  }
}
