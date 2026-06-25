package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmissionPolicyTest {

  @Test
  void requiresRenderHintNonNull() {
    assertThrows(NullPointerException.class, () -> new EmissionPolicy(null));
  }

  @Test
  void factoryMethodsProduceExpectedRenderHint() {
    assertEquals(RenderHint.EPHEMERAL, EmissionPolicy.ephemeral().renderHint());
    assertEquals(RenderHint.PERSISTED, EmissionPolicy.persisted().renderHint());
    assertEquals(RenderHint.REQUIRES_ACK, EmissionPolicy.requiresAck().renderHint());
  }

  @Test
  void allRenderHintsAreDistinct() {
    assertEquals(3, RenderHint.values().length);
  }

  // -------- Slice 490 substrate-completion follow-up: dedupeWindow field --------

  @Test
  void dedupeWindowDefaultsToEmpty() {
    assertTrue(EmissionPolicy.persisted().dedupeWindow().isEmpty());
    assertTrue(EmissionPolicy.ephemeral().dedupeWindow().isEmpty());
    assertTrue(EmissionPolicy.requiresAck().dedupeWindow().isEmpty());
  }

  @Test
  void backCompatOneArgConstructorDefaultsDedupeEmpty() {
    EmissionPolicy p = new EmissionPolicy(RenderHint.PERSISTED);
    assertTrue(p.dedupeWindow().isEmpty());
    assertEquals(RenderHint.PERSISTED, p.renderHint());
  }

  @Test
  void canonicalConstructorAcceptsExplicitDedupeWindow() {
    Duration window = Duration.ofMinutes(5);
    EmissionPolicy p = new EmissionPolicy(RenderHint.PERSISTED, Optional.of(window));
    assertEquals(Optional.of(window), p.dedupeWindow());
  }

  @Test
  void canonicalConstructorRejectsNullDedupeOptional() {
    assertThrows(
        NullPointerException.class, () -> new EmissionPolicy(RenderHint.PERSISTED, null));
  }

  @Test
  void canonicalConstructorRejectsZeroOrNegativeDedupeDuration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EmissionPolicy(RenderHint.PERSISTED, Optional.of(Duration.ZERO)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EmissionPolicy(RenderHint.PERSISTED, Optional.of(Duration.ofSeconds(-1))));
  }

  @Test
  void withDedupeWindowProducesNewInstance() {
    EmissionPolicy original = EmissionPolicy.persisted();
    EmissionPolicy withDedupe = original.withDedupeWindow(Duration.ofMinutes(1));
    assertNotSame(original, withDedupe);
    assertEquals(RenderHint.PERSISTED, withDedupe.renderHint());
    assertEquals(Optional.of(Duration.ofMinutes(1)), withDedupe.dedupeWindow());
    // Original is unchanged.
    assertTrue(original.dedupeWindow().isEmpty());
  }

  @Test
  void withDedupeWindowRejectsNull() {
    assertThrows(
        NullPointerException.class, () -> EmissionPolicy.persisted().withDedupeWindow(null));
  }

  @Test
  void withDedupeWindowRejectsNonPositive() {
    assertThrows(
        IllegalArgumentException.class,
        () -> EmissionPolicy.persisted().withDedupeWindow(Duration.ZERO));
  }

  @Test
  void renderHintPreservedAcrossWithDedupeWindow() {
    EmissionPolicy ephemeral = EmissionPolicy.ephemeral().withDedupeWindow(Duration.ofSeconds(30));
    assertEquals(RenderHint.EPHEMERAL, ephemeral.renderHint());
    EmissionPolicy ack = EmissionPolicy.requiresAck().withDedupeWindow(Duration.ofMinutes(10));
    assertEquals(RenderHint.REQUIRES_ACK, ack.renderHint());
  }

  @Test
  void equalsSemanticsHonorBothFields() {
    EmissionPolicy a = new EmissionPolicy(RenderHint.PERSISTED, Optional.of(Duration.ofMinutes(1)));
    EmissionPolicy b = new EmissionPolicy(RenderHint.PERSISTED, Optional.of(Duration.ofMinutes(1)));
    EmissionPolicy c = new EmissionPolicy(RenderHint.PERSISTED, Optional.of(Duration.ofMinutes(2)));
    EmissionPolicy d = new EmissionPolicy(RenderHint.PERSISTED, Optional.empty());
    assertEquals(a, b);
    assertNotSame(a, b);
    assertFalse(a.equals(c));
    assertFalse(a.equals(d));
    // Sanity: both back-compat ctor & one-arg factories yield the same shape.
    assertSame(Optional.empty(), d.dedupeWindow());
  }
}
