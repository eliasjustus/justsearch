package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FaultsTest {

  private static final Logger LOG = LoggerFactory.getLogger(FaultsTest.class);

  @Test
  void logAndContinue_doesNotPropagate() {
    // Should not throw despite the action throwing.
    assertDoesNotThrow(
        () -> Faults.logAndContinue(LOG, "test action", () -> { throw new RuntimeException("boom"); }));
  }

  @Test
  void logAndContinue_executesAction() {
    List<String> evidence = new ArrayList<>();
    Faults.logAndContinue(LOG, "test action", () -> evidence.add("executed"));
    assertEquals(List.of("executed"), evidence);
  }

  @Test
  void debugAndContinue_doesNotPropagate() {
    assertDoesNotThrow(
        () -> Faults.debugAndContinue(LOG, "cleanup", () -> { throw new RuntimeException("boom"); }));
  }

  @Test
  void logAndFallback_returnsSupplierValueOnSuccess() {
    String result = Faults.logAndFallback(LOG, "supplier", () -> "ok", "fallback");
    assertEquals("ok", result);
  }

  @Test
  void logAndFallback_returnsFallbackOnException() {
    String result =
        Faults.logAndFallback(LOG, "supplier", () -> { throw new RuntimeException("boom"); }, "fallback");
    assertEquals("fallback", result);
  }

  @Test
  void logAndFallback_returnsFallbackNull() {
    String result =
        Faults.logAndFallback(LOG, "supplier", () -> { throw new RuntimeException("boom"); }, null);
    assertNull(result);
  }
}
