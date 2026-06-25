package io.justsearch.app.inference;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for package-private utility methods in InferenceLifecycleManager.
 *
 * <p>These are pure functions that can be tested in isolation without mocking.
 */
@DisplayName("InferenceLifecycleManager utility methods")
class InferenceLifecycleManagerUtilsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // ==================== asIntOrNull tests ====================

  @Nested
  @DisplayName("asIntOrNull()")
  class AsIntOrNullTests {

    @Test
    @DisplayName("null node returns null")
    void nullNode_returnsNull() {
      assertNull(invokeAsIntOrNull(null));
    }

    @Test
    @DisplayName("NullNode returns null")
    void nullJsonNode_returnsNull() {
      assertNull(invokeAsIntOrNull(NullNode.getInstance()));
    }

    @Test
    @DisplayName("integer node returns value")
    void intNode_returnsValue() throws Exception {
      JsonNode node = MAPPER.readTree("42");
      assertEquals(42, invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("long node returns int value")
    void longNode_returnsIntValue() throws Exception {
      JsonNode node = MAPPER.readTree("100");
      assertEquals(100, invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("decimal number node returns int part")
    void decimalNode_returnsIntPart() throws Exception {
      JsonNode node = MAPPER.readTree("42.7");
      assertEquals(42, invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("text node with valid number parses value")
    void textNodeWithNumber_parsesValue() throws Exception {
      JsonNode node = MAPPER.readTree("\"123\"");
      assertEquals(123, invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("text node with whitespace-padded number parses value")
    void textNodeWithWhitespacePaddedNumber_parsesValue() throws Exception {
      JsonNode node = MAPPER.readTree("\"  456  \"");
      assertEquals(456, invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("text node with invalid number returns null")
    void textNodeWithInvalid_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("\"not-a-number\"");
      assertNull(invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("text node with blank string returns null")
    void textNodeWithBlank_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("\"   \"");
      assertNull(invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("empty text node returns null")
    void textNodeWithEmpty_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("\"\"");
      assertNull(invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("negative integer returns negative value")
    void negativeInt_returnsNegativeValue() throws Exception {
      JsonNode node = MAPPER.readTree("-99");
      assertEquals(-99, invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("zero returns zero")
    void zero_returnsZero() throws Exception {
      JsonNode node = MAPPER.readTree("0");
      assertEquals(0, invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("boolean node returns null")
    void booleanNode_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("true");
      assertNull(invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("array node returns null")
    void arrayNode_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("[1, 2, 3]");
      assertNull(invokeAsIntOrNull(node));
    }

    @Test
    @DisplayName("object node returns null")
    void objectNode_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("{\"value\": 42}");
      assertNull(invokeAsIntOrNull(node));
    }
  }

  // ==================== asPositiveInt tests ====================

  @Nested
  @DisplayName("asPositiveInt()")
  class AsPositiveIntTests {

    @Test
    @DisplayName("positive value returns value")
    void positiveValue_returnsValue() throws Exception {
      JsonNode node = MAPPER.readTree("42");
      assertEquals(42, invokeAsPositiveInt(node));
    }

    @Test
    @DisplayName("zero returns null")
    void zeroValue_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("0");
      assertNull(invokeAsPositiveInt(node));
    }

    @Test
    @DisplayName("negative value returns null")
    void negativeValue_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("-5");
      assertNull(invokeAsPositiveInt(node));
    }

    @Test
    @DisplayName("null node returns null")
    void nullNode_returnsNull() throws Exception {
      assertNull(invokeAsPositiveInt(null));
    }

    @Test
    @DisplayName("NullNode returns null")
    void nullJsonNode_returnsNull() throws Exception {
      assertNull(invokeAsPositiveInt(NullNode.getInstance()));
    }

    @Test
    @DisplayName("text node with positive number parses value")
    void textNodeWithPositiveNumber_parsesValue() throws Exception {
      JsonNode node = MAPPER.readTree("\"256\"");
      assertEquals(256, invokeAsPositiveInt(node));
    }

    @Test
    @DisplayName("text node with zero returns null")
    void textNodeWithZero_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("\"0\"");
      assertNull(invokeAsPositiveInt(node));
    }

    @Test
    @DisplayName("text node with negative returns null")
    void textNodeWithNegative_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("\"-10\"");
      assertNull(invokeAsPositiveInt(node));
    }

    @Test
    @DisplayName("text node with invalid returns null")
    void textNodeWithInvalid_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("\"abc\"");
      assertNull(invokeAsPositiveInt(node));
    }

    @Test
    @DisplayName("boolean node returns null")
    void booleanNode_returnsNull() throws Exception {
      JsonNode node = MAPPER.readTree("true");
      assertNull(invokeAsPositiveInt(node));
    }
  }

  // ==================== safeMessage tests ====================

  @Nested
  @DisplayName("safeMessage()")
  class SafeMessageTests {

    @Test
    @DisplayName("null throwable returns 'unknown'")
    void nullThrowable_returnsUnknown() {
      assertEquals("unknown", invokeSafeMessage(null));
    }

    @Test
    @DisplayName("throwable with message returns message")
    void throwableWithMessage_returnsMessage() {
      Exception e = new RuntimeException("Something went wrong");
      assertEquals("Something went wrong", invokeSafeMessage(e));
    }

    @Test
    @DisplayName("throwable with blank message returns class name")
    void throwableWithBlankMessage_returnsClassName() {
      Exception e = new RuntimeException("   ");
      assertEquals("RuntimeException", invokeSafeMessage(e));
    }

    @Test
    @DisplayName("throwable with empty message returns class name")
    void throwableWithEmptyMessage_returnsClassName() {
      Exception e = new RuntimeException("");
      assertEquals("RuntimeException", invokeSafeMessage(e));
    }

    @Test
    @DisplayName("throwable with null message returns class name")
    void throwableWithNullMessage_returnsClassName() {
      Exception e = new RuntimeException((String) null);
      assertEquals("RuntimeException", invokeSafeMessage(e));
    }

    @Test
    @DisplayName("custom exception returns class simple name")
    void customException_returnsSimpleName() {
      Exception e = new IllegalStateException((String) null);
      assertEquals("IllegalStateException", invokeSafeMessage(e));
    }
  }

  // ==================== modeTransition helper tests ====================

  @Nested
  @DisplayName("modeTransition helper methods")
  class ModeTransitionHelperTests {

    @Test
    @DisplayName("modeTransition(reason, message) preserves reason and message")
    void modeTransition_withoutCause_preservesReasonAndMessage() {
      ModeTransitionException ex = invokeModeTransition(
          ModeTransitionException.Reason.CONFIG_REQUIRED,
          "Config is required");
      assertEquals(ModeTransitionException.Reason.CONFIG_REQUIRED, ex.reason());
      assertEquals("[CONFIG_REQUIRED] Config is required", ex.getMessage());
      assertNull(ex.getCause());
    }

    @Test
    @DisplayName("modeTransition(reason, message, cause) preserves cause")
    void modeTransition_withCause_preservesCause() {
      IllegalStateException cause = new IllegalStateException("boom");
      ModeTransitionException ex = invokeModeTransition(
          ModeTransitionException.Reason.ONLINE_START_FAILED,
          "Failed to start",
          cause);
      assertEquals(ModeTransitionException.Reason.ONLINE_START_FAILED, ex.reason());
      assertEquals("[ONLINE_START_FAILED] Failed to start", ex.getMessage());
      assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("modeTransitionWithCauseMessage appends cause message")
    void modeTransitionWithCauseMessage_appendsCauseMessage() {
      RuntimeException cause = new RuntimeException("props unavailable");
      ModeTransitionException ex = invokeModeTransitionWithCauseMessage(
          ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT,
          "Failed waiting: ",
          cause);
      assertEquals(ModeTransitionException.Reason.HEALTH_CHECK_TIMEOUT, ex.reason());
      assertEquals("[HEALTH_CHECK_TIMEOUT] Failed waiting: props unavailable", ex.getMessage());
      assertSame(cause, ex.getCause());
    }

    @Test
    @DisplayName("asModeTransition returns same instance for ModeTransitionException")
    void asModeTransition_forModeTransitionException_returnsSameInstance() {
      ModeTransitionException existing = new ModeTransitionException(
          ModeTransitionException.Reason.HEALTH_CHECK_INTERRUPTED,
          "Interrupted");
      ModeTransitionException mapped = invokeAsModeTransition(
          existing,
          ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
          "Fallback: ");
      assertSame(existing, mapped);
    }

    @Test
    @DisplayName("asModeTransition wraps generic throwable with fallback reason")
    void asModeTransition_forGenericThrowable_wrapsWithFallbackReason() {
      IllegalArgumentException cause = new IllegalArgumentException("bad input");
      ModeTransitionException mapped = invokeAsModeTransition(
          cause,
          ModeTransitionException.Reason.CONFIG_APPLY_FAILED,
          "Fallback: ");
      assertEquals(ModeTransitionException.Reason.CONFIG_APPLY_FAILED, mapped.reason());
      assertEquals("[CONFIG_APPLY_FAILED] Fallback: bad input", mapped.getMessage());
      assertSame(cause, mapped.getCause());
    }
  }

  // ==================== Helper methods ====================

  private static Integer invokeAsIntOrNull(JsonNode node) {
    return InferenceLifecycleManager.asIntOrNull(node);
  }

  private static Integer invokeAsPositiveInt(JsonNode node) {
    return InferenceLifecycleManager.asPositiveInt(node);
  }

  private static String invokeSafeMessage(Throwable t) {
    return InferenceLifecycleManager.safeMessage(t);
  }

  private static ModeTransitionException invokeModeTransition(
      ModeTransitionException.Reason reason,
      String message) {
    return InferenceLifecycleManager.modeTransition(reason, message);
  }

  private static ModeTransitionException invokeModeTransition(
      ModeTransitionException.Reason reason,
      String message,
      Throwable cause) {
    return InferenceLifecycleManager.modeTransition(reason, message, cause);
  }

  private static ModeTransitionException invokeModeTransitionWithCauseMessage(
      ModeTransitionException.Reason reason,
      String messagePrefix,
      Throwable cause) {
    return InferenceLifecycleManager.modeTransitionWithCauseMessage(reason, messagePrefix, cause);
  }

  private static ModeTransitionException invokeAsModeTransition(
      Throwable throwable,
      ModeTransitionException.Reason fallbackReason,
      String fallbackMessagePrefix) {
    return InferenceLifecycleManager.asModeTransition(
        throwable, fallbackReason, fallbackMessagePrefix);
  }
}
