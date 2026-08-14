package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the engine's default completion ceiling to the copy the reasoning-budget clamp reasons about
 * (tempdoc 835 §9f). The configuration module cannot depend on app-services, so
 * {@code ResolvedConfigBuilder.ENGINE_DEFAULT_MAX_TOKENS} mirrors {@code
 * ConversationEngine.DEFAULT_MAX_TOKENS}. If the ceiling moves and the mirror does not, the clamp
 * starts guarding the wrong number — silently, because its failure mode is an empty answer with no
 * error. This test makes that drift loud instead.
 */
@DisplayName("reasoning-budget clamp: engine completion ceiling is mirrored, not guessed")
class ConversationEngineTokenCeilingTest {

  @Test
  @DisplayName("ResolvedConfigBuilder's mirrored ceiling equals ConversationEngine's")
  void mirroredCeilingMatchesEngine() {
    assertEquals(
        ConversationEngine.DEFAULT_MAX_TOKENS, ResolvedConfigBuilder.ENGINE_DEFAULT_MAX_TOKENS);
  }
}
