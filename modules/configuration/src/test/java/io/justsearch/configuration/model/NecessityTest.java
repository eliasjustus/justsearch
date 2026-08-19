package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 840 Phase 2 — the necessity axis and the declinability it derives. */
class NecessityTest {

  @Test
  @DisplayName("declinability is derived from the category, with exactly two declinable values")
  void declinability_isDerived() {
    assertFalse(Necessity.REQUIRED.userDeclinable(), "search does not work without it");
    assertTrue(Necessity.IMPROVES_RESULTS.userDeclinable());
    assertTrue(Necessity.ADDS_FEATURE.userDeclinable());
    assertFalse(
        Necessity.INFRASTRUCTURE.userDeclinable(),
        "plumbing whose decline would remove chat as an unnamed side effect (cuda-runtime also"
            + " carries the cuda12 llama-server) must not be offered as a choice");
  }

  @Test
  @DisplayName("fromId round-trips every declared id")
  void fromId_roundTripsEveryValue() {
    for (Necessity n : Necessity.values()) {
      assertEquals(n, Necessity.fromId(n.id()), "id '" + n.id() + "' must resolve back to " + n);
      assertFalse(n.label().isBlank(), n + " needs a user-facing label");
    }
  }

  @Test
  @DisplayName("fromId is tolerant: null, blank and unknown all yield null for the caller to default")
  void fromId_isTolerant() {
    assertNull(Necessity.fromId(null));
    assertNull(Necessity.fromId(""));
    assertNull(Necessity.fromId("  "));
    assertNull(
        Necessity.fromId("mostly-harmless"),
        "an unrecognized necessity must not throw — it must fall through to the loader's"
            + " fail-closed REQUIRED default rather than failing the whole registry load");
  }
}
