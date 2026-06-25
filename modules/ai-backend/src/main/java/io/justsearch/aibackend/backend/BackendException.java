/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import java.util.Locale;
import java.util.Objects;

/**
 * Exception thrown by backend operations.
 *
 * <p>Each exception has a {@link Category} that indicates whether the error is retryable,
 * requires engine restart, or was user-initiated. This enables proper error handling without
 * string matching on messages.
 *
 * <h2>Error Categories</h2>
 * <ul>
 *   <li>{@link Category#TRANSIENT} - Retry may succeed (busy, timeout, resource contention)</li>
 *   <li>{@link Category#PERMANENT} - Retry won't help (invalid input, unsupported operation)</li>
 *   <li>{@link Category#FATAL} - Engine is broken and requires restart (native crash, OOM)</li>
 *   <li>{@link Category#CANCELLED} - User-initiated cancellation (not an error condition)</li>
 * </ul>
 *
 * <h2>Common Error Codes</h2>
 * <ul>
 *   <li>{@code slots_full} - All inference slots are busy (TRANSIENT)</li>
 *   <li>{@code deadline_exceeded} - Request timed out (TRANSIENT)</li>
 *   <li>{@code cancelled} - Request was cancelled (CANCELLED)</li>
 *   <li>{@code context_exhausted} - KV cache is full (TRANSIENT with SHIFT policy, PERMANENT with FAIL_FAST)</li>
 *   <li>{@code engine_stopped} - Engine is shut down (PERMANENT)</li>
 *   <li>{@code native_error} - Native library failure (FATAL)</li>
 *   <li>{@code invalid_input} - Bad request data (PERMANENT)</li>
 * </ul>
 */
public class BackendException extends Exception {

  /**
   * Categorizes exceptions by recoverability.
   */
  public enum Category {
    /** Retry may succeed - resource contention, timeouts */
    TRANSIENT,
    /** Retry won't help - invalid input, unsupported operation */
    PERMANENT,
    /** Engine is broken - native crash, assertion failure, OOM */
    FATAL,
    /** User-initiated cancellation - not an error */
    CANCELLED
  }

  // Well-known error codes
  public static final String CODE_SLOTS_FULL = "slots_full";
  public static final String CODE_DEADLINE_EXCEEDED = "deadline_exceeded";
  public static final String CODE_CANCELLED = "cancelled";
  public static final String CODE_CONTEXT_EXHAUSTED = "context_exhausted";
  public static final String CODE_ENGINE_STOPPED = "engine_stopped";
  public static final String CODE_NATIVE_ERROR = "native_error";
  public static final String CODE_INVALID_INPUT = "invalid_input";
  public static final String CODE_CIRCUIT_OPEN = "circuit_open";
  public static final String CODE_BACKEND_ERROR = "backend_error";
  public static final String CODE_INTERRUPTED = "interrupted";

  private final Category category;
  private final String errorCode;

  /**
   * Creates an exception with automatic category inference from the error code.
   */
  public BackendException(String errorCode) {
    super(errorCode);
    this.errorCode = errorCode != null ? errorCode : "unknown";
    this.category = inferCategory(this.errorCode, null);
  }

  /**
   * Creates an exception with automatic category inference.
   */
  public BackendException(String errorCode, Throwable cause) {
    super(errorCode, cause);
    this.errorCode = errorCode != null ? errorCode : "unknown";
    this.category = inferCategory(this.errorCode, cause);
  }

  /**
   * Creates an exception with explicit category.
   */
  public BackendException(String errorCode, Category category) {
    super(errorCode);
    this.errorCode = errorCode != null ? errorCode : "unknown";
    this.category = category != null ? category : Category.PERMANENT;
  }

  /**
   * Creates an exception with explicit category and cause.
   */
  public BackendException(String errorCode, Category category, Throwable cause) {
    super(errorCode, cause);
    this.errorCode = errorCode != null ? errorCode : "unknown";
    this.category = category != null ? category : inferCategory(this.errorCode, cause);
  }

  /**
   * Returns the error code (e.g., "slots_full", "deadline_exceeded").
   */
  public String errorCode() {
    return errorCode;
  }

  /**
   * Returns the error category.
   */
  public Category category() {
    return category;
  }

  /**
   * Returns true if retrying the operation may succeed.
   */
  public boolean isRetryable() {
    return category == Category.TRANSIENT;
  }

  /**
   * Returns true if the engine requires restart to recover.
   */
  public boolean requiresRestart() {
    return category == Category.FATAL;
  }

  /**
   * Returns true if this was a user-initiated cancellation.
   */
  public boolean isCancellation() {
    return category == Category.CANCELLED;
  }

  /**
   * Returns true if this is a permanent failure (retrying won't help).
   */
  public boolean isPermanent() {
    return category == Category.PERMANENT;
  }

  /**
   * Returns a structured string for debugging. Includes errorCode and category.
   *
   * <p>Note: Intentionally overrides toString() rather than getMessage() to preserve the error code
   * as the message (for catch handlers that log ex.getMessage()) while providing richer debug info.
   */
  @SuppressWarnings("OverrideThrowableToString")
  @Override
  public String toString() {
    return "BackendException{" +
        "errorCode='" + errorCode + '\'' +
        ", category=" + category +
        ", message='" + getMessage() + '\'' +
        '}';
  }

  // --- Factory methods for common errors ---

  public static BackendException slotsFull() {
    return new BackendException(CODE_SLOTS_FULL, Category.TRANSIENT);
  }

  public static BackendException deadlineExceeded() {
    return new BackendException(CODE_DEADLINE_EXCEEDED, Category.TRANSIENT);
  }

  public static BackendException cancelled() {
    return new BackendException(CODE_CANCELLED, Category.CANCELLED);
  }

  public static BackendException cancelled(String reason) {
    return new BackendException(reason != null ? reason : CODE_CANCELLED, Category.CANCELLED);
  }

  public static BackendException contextExhausted() {
    return new BackendException(CODE_CONTEXT_EXHAUSTED, Category.TRANSIENT);
  }

  public static BackendException engineStopped() {
    return new BackendException(CODE_ENGINE_STOPPED, Category.PERMANENT);
  }

  public static BackendException nativeError(String details, Throwable cause) {
    return new BackendException(CODE_NATIVE_ERROR + ": " + details, Category.FATAL, cause);
  }

  public static BackendException invalidInput(String details) {
    return new BackendException(CODE_INVALID_INPUT + ": " + details, Category.PERMANENT);
  }

  public static BackendException circuitOpen() {
    return new BackendException(CODE_CIRCUIT_OPEN, Category.FATAL);
  }

  public static BackendException interrupted(InterruptedException cause) {
    return new BackendException(CODE_INTERRUPTED, Category.CANCELLED, cause);
  }

  // --- Category inference ---

  private static Category inferCategory(String errorCode, Throwable cause) {
    if (errorCode == null) {
      return inferFromCause(cause);
    }

    String lower = errorCode.toLowerCase(Locale.ROOT);

    // Cancellation patterns
    if (lower.contains("cancel") || lower.contains("interrupt")) {
      return Category.CANCELLED;
    }

    // Transient patterns (retryable)
    if (lower.contains("busy") || lower.contains("slots_full") ||
        lower.contains("deadline") || lower.contains("timeout") ||
        lower.contains("context_exhausted") || lower.contains("queue_full")) {
      return Category.TRANSIENT;
    }

    // Fatal patterns (requires restart)
    if (lower.contains("native") || lower.contains("circuit") ||
        lower.contains("ggml_assert") || lower.contains("sigsegv") ||
        lower.contains("fatal") || lower.contains("oom")) {
      return Category.FATAL;
    }

    // Check cause for additional context
    if (cause != null) {
      Category fromCause = inferFromCause(cause);
      if (fromCause == Category.FATAL) {
        return Category.FATAL;
      }
    }

    // Default to permanent (invalid input, unsupported, etc.)
    return Category.PERMANENT;
  }

  private static Category inferFromCause(Throwable cause) {
    if (cause == null) {
      return Category.PERMANENT;
    }

    // Java Errors are fatal
    if (cause instanceof Error) {
      return Category.FATAL;
    }

    // Interrupts are cancellations
    if (cause instanceof InterruptedException) {
      return Category.CANCELLED;
    }

    // Check message for patterns
    String msg = cause.getMessage();
    if (msg != null) {
      String lower = msg.toLowerCase(Locale.ROOT);
      if (lower.contains("ggml_assert") || lower.contains("sigsegv")) {
        return Category.FATAL;
      }
    }

    return Category.PERMANENT;
  }
}
