package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Audience vocabulary (slice 449)")
final class AudienceTest {

  @Test
  @DisplayName("vocabulary is closed at exactly four values")
  void vocabularyIsClosed() {
    assertEquals(4, Audience.values().length);
  }

  @Test
  @DisplayName("the four canonical names are present (USER / AGENT / OPERATOR / DEVELOPER)")
  void canonicalNamesPresent() {
    assertSame(Audience.USER, Audience.valueOf("USER"));
    assertSame(Audience.AGENT, Audience.valueOf("AGENT"));
    assertSame(Audience.OPERATOR, Audience.valueOf("OPERATOR"));
    assertSame(Audience.DEVELOPER, Audience.valueOf("DEVELOPER"));
  }
}
