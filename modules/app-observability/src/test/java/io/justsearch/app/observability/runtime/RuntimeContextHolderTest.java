package io.justsearch.app.observability.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuntimeContextHolder")
final class RuntimeContextHolderTest {

  @Test
  @DisplayName("constructor rejects null initial")
  void constructorRejectsNull() {
    assertThrows(NullPointerException.class, () -> new RuntimeContextHolder(null));
  }

  @Test
  @DisplayName("current() returns the initial context")
  void currentReturnsInitial() {
    RuntimeContext initial = new RuntimeContext(SystemMode.PRODUCTION, false);
    RuntimeContextHolder holder = new RuntimeContextHolder(initial);
    assertSame(initial, holder.current());
  }

  @Test
  @DisplayName("set() replaces and returns prior value")
  void setReplacesAndReturnsPrior() {
    RuntimeContext initial = new RuntimeContext(SystemMode.PRODUCTION, false);
    RuntimeContext next = new RuntimeContext(SystemMode.EVAL, true);
    RuntimeContextHolder holder = new RuntimeContextHolder(initial);
    RuntimeContext returned = holder.set(next);
    assertSame(initial, returned);
    assertSame(next, holder.current());
  }

  @Test
  @DisplayName("set() rejects null")
  void setRejectsNull() {
    RuntimeContextHolder holder =
        new RuntimeContextHolder(new RuntimeContext(SystemMode.PRODUCTION, false));
    assertThrows(NullPointerException.class, () -> holder.set(null));
  }

  @Test
  @DisplayName("RuntimeContext rejects null systemMode")
  void recordRejectsNullMode() {
    assertThrows(NullPointerException.class, () -> new RuntimeContext(null, false));
  }

  @Test
  @DisplayName("RuntimeContext components round-trip")
  void recordComponentsAccessible() {
    RuntimeContext ctx = new RuntimeContext(SystemMode.EVAL, true);
    assertEquals(SystemMode.EVAL, ctx.systemMode());
    assertEquals(true, ctx.automationEnabled());
  }
}
