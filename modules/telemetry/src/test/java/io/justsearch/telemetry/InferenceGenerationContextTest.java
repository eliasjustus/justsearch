package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 518 Appendix F W2.2 — pin the static-slot contract of {@link
 * InferenceGenerationContext}: default returns sentinel; registered supplier flows; reset
 * restores sentinel.
 */
@DisplayName("InferenceGenerationContext — static slot contract")
final class InferenceGenerationContextTest {

  @AfterEach
  void cleanup() {
    InferenceGenerationContext.resetForTest();
  }

  @Test
  @DisplayName("default supplier returns sentinel -1 (no registration)")
  void defaultReturnsSentinel() {
    InferenceGenerationContext.resetForTest();
    assertEquals(-1L, InferenceGenerationContext.current());
  }

  @Test
  @DisplayName("registered supplier flows through; subsequent transitions reflected")
  void registeredSupplierFlows() {
    AtomicLong gen = new AtomicLong(0L);
    InferenceGenerationContext.set(gen::get);
    assertEquals(0L, InferenceGenerationContext.current());
    gen.incrementAndGet();
    assertEquals(1L, InferenceGenerationContext.current());
    gen.set(42L);
    assertEquals(42L, InferenceGenerationContext.current());
  }

  @Test
  @DisplayName("set(null) restores sentinel")
  void setNullRestoresSentinel() {
    InferenceGenerationContext.set(() -> 99L);
    assertEquals(99L, InferenceGenerationContext.current());
    InferenceGenerationContext.set(null);
    assertEquals(-1L, InferenceGenerationContext.current());
  }
}
