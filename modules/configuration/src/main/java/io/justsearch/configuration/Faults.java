/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Fault-isolation utilities that make correct exception handling as convenient as silent swallowing.
 *
 * <p>Each method catches {@link Exception}, logs it, and continues — replacing the
 * {@code catch (Exception ignored) {}} anti-pattern with a single-line call that preserves
 * visibility. Use {@link #logAndContinue} for fire-and-forget actions, {@link #debugAndContinue}
 * for shutdown/cleanup paths, and {@link #logAndFallback} for suppliers that must return a value.
 */
public final class Faults {

  private Faults() {} // utility class

  /**
   * Executes an action with fault isolation. On exception, logs at WARN and continues.
   *
   * @param log the logger to use
   * @param context a short description for the log message (e.g. "config listener FooListener")
   * @param action the action to execute
   */
  public static void logAndContinue(Logger log, String context, Runnable action) {
    try {
      action.run();
    } catch (Exception e) {
      log.warn("{}: {}", context, e.getMessage(), e);
    }
  }

  /**
   * Executes an action with fault isolation. On exception, logs at DEBUG and continues.
   *
   * <p>Use this for shutdown, cleanup, and other best-effort paths where failures are routine.
   *
   * @param log the logger to use
   * @param context a short description for the log message
   * @param action the action to execute
   */
  public static void debugAndContinue(Logger log, String context, Runnable action) {
    try {
      action.run();
    } catch (Exception e) {
      log.debug("{}: {}", context, e.getMessage(), e);
    }
  }

  /**
   * Executes a supplier with fault isolation. On exception, logs at WARN and returns the fallback.
   *
   * @param log the logger to use
   * @param context a short description for the log message
   * @param supplier the supplier to execute
   * @param fallback the value to return on exception
   * @param <T> the return type
   * @return the supplier's result, or {@code fallback} on exception
   */
  public static <T> T logAndFallback(Logger log, String context, Supplier<T> supplier, T fallback) {
    try {
      return supplier.get();
    } catch (Exception e) {
      log.warn("{}: {}", context, e.getMessage(), e);
      return fallback;
    }
  }
}
