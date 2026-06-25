package io.justsearch.aibackend.backend;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class BackendExceptionTest {

  @Test
  void factoryMethodsCreateCorrectCategories() {
    assertEquals(BackendException.Category.TRANSIENT, BackendException.slotsFull().category());
    assertEquals(BackendException.Category.TRANSIENT, BackendException.deadlineExceeded().category());
    assertEquals(BackendException.Category.CANCELLED, BackendException.cancelled().category());
    assertEquals(BackendException.Category.TRANSIENT, BackendException.contextExhausted().category());
    assertEquals(BackendException.Category.PERMANENT, BackendException.engineStopped().category());
    assertEquals(BackendException.Category.FATAL, BackendException.circuitOpen().category());
  }

  @Test
  void isRetryableForTransientErrors() {
    assertTrue(BackendException.slotsFull().isRetryable());
    assertTrue(BackendException.deadlineExceeded().isRetryable());
    assertFalse(BackendException.engineStopped().isRetryable());
    assertFalse(BackendException.cancelled().isRetryable());
  }

  @Test
  void requiresRestartForFatalErrors() {
    assertTrue(BackendException.circuitOpen().requiresRestart());
    assertTrue(BackendException.nativeError("test", new Error()).requiresRestart());
    assertFalse(BackendException.slotsFull().requiresRestart());
    assertFalse(BackendException.cancelled().requiresRestart());
  }

  @Test
  void isCancellationForCancelledErrors() {
    assertTrue(BackendException.cancelled().isCancellation());
    assertTrue(BackendException.cancelled("user_cancelled").isCancellation());
    assertTrue(BackendException.interrupted(new InterruptedException()).isCancellation());
    assertFalse(BackendException.slotsFull().isCancellation());
  }

  @Test
  void categoryInferenceFromErrorCode() {
    // Transient patterns
    assertEquals(BackendException.Category.TRANSIENT,
        new BackendException("slots_full").category());
    assertEquals(BackendException.Category.TRANSIENT,
        new BackendException("deadline_exceeded").category());
    assertEquals(BackendException.Category.TRANSIENT,
        new BackendException("queue_full").category());

    // Cancelled patterns
    assertEquals(BackendException.Category.CANCELLED,
        new BackendException("cancelled").category());
    assertEquals(BackendException.Category.CANCELLED,
        new BackendException("user_cancelled").category());

    // Fatal patterns
    assertEquals(BackendException.Category.FATAL,
        new BackendException("native_error").category());
    assertEquals(BackendException.Category.FATAL,
        new BackendException("GGML_ASSERT failed").category());

    // Default to permanent
    assertEquals(BackendException.Category.PERMANENT,
        new BackendException("unknown_error").category());
  }

  @Test
  void categoryInferenceFromCause() {
    // Error causes are fatal
    assertEquals(BackendException.Category.FATAL,
        new BackendException("test", new OutOfMemoryError()).category());

    // InterruptedException with explicit category or factory method
    assertEquals(BackendException.Category.CANCELLED,
        BackendException.interrupted(new InterruptedException()).category());
  }

  @Test
  void errorCodeIsPreserved() {
    BackendException ex = new BackendException("my_error_code", BackendException.Category.TRANSIENT);
    assertEquals("my_error_code", ex.errorCode());
    assertEquals("my_error_code", ex.getMessage());
  }

  @Test
  void toStringIncludesAllInfo() {
    BackendException ex = new BackendException("test_error", BackendException.Category.TRANSIENT);
    String str = ex.toString();
    assertTrue(str.contains("test_error"));
    assertTrue(str.contains("TRANSIENT"));
  }
}
