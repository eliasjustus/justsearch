package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OnOverflow vocabulary (slice 444a)")
final class OnOverflowTest {

  @Test
  @DisplayName("vocabulary is closed at exactly three values")
  void vocabularyIsClosed() {
    assertEquals(3, OnOverflow.values().length);
  }

  @Test
  @DisplayName("each vocabulary value is round-trippable via valueOf")
  void valuesRoundTripViaValueOf() {
    for (OnOverflow value : OnOverflow.values()) {
      assertSame(value, OnOverflow.valueOf(value.name()));
    }
  }

  @Test
  @DisplayName("the three canonical names are present")
  void canonicalNamesPresent() {
    assertSame(OnOverflow.EVICT_OLDEST, OnOverflow.valueOf("EVICT_OLDEST"));
    assertSame(OnOverflow.BACKPRESSURE, OnOverflow.valueOf("BACKPRESSURE"));
    assertSame(OnOverflow.DROP_NEWEST, OnOverflow.valueOf("DROP_NEWEST"));
  }
}
