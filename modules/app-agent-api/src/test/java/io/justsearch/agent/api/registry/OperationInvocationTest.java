package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OperationInvocation (slice 447-impl-B + 447-followup/3.1)")
final class OperationInvocationTest {

  private static final OperationRef TARGET = new OperationRef("core.test-op");

  @Test
  @DisplayName("of(OperationRef) returns invocation with empty defaults")
  void ofConvenienceFactory() {
    OperationInvocation inv = OperationInvocation.of(TARGET);
    assertEquals(TARGET, inv.target());
    assertEquals("{}", inv.defaultArgsJson());
  }

  @Test
  @DisplayName("canonical constructor accepts JSON object literals")
  void canonicalConstructorAcceptsObjects() {
    OperationInvocation forceTrue = new OperationInvocation(TARGET, "{\"force\":true}");
    assertEquals("{\"force\":true}", forceTrue.defaultArgsJson());

    OperationInvocation nested = new OperationInvocation(TARGET, "{\"a\":{\"b\":1}}");
    assertEquals("{\"a\":{\"b\":1}}", nested.defaultArgsJson());

    OperationInvocation withWhitespace = new OperationInvocation(TARGET, "  {\"x\": 1}  ");
    assertEquals("  {\"x\": 1}  ", withWhitespace.defaultArgsJson());
  }

  @Test
  @DisplayName("blank defaultArgsJson is rejected")
  void blankRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new OperationInvocation(TARGET, ""));
    assertThrows(
        IllegalArgumentException.class, () -> new OperationInvocation(TARGET, "   "));
  }

  @Test
  @DisplayName("non-object literals are rejected (447-followup/3.1)")
  void nonObjectRejected() {
    // Truncated braces.
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationInvocation(TARGET, "{not-valid"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationInvocation(TARGET, "broken}"));

    // Non-object literals.
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationInvocation(TARGET, "[1,2,3]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationInvocation(TARGET, "\"a string\""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationInvocation(TARGET, "42"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationInvocation(TARGET, "null"));
  }

  @Test
  @DisplayName("null target / defaultArgsJson are rejected")
  void nullsRejected() {
    assertThrows(
        NullPointerException.class, () -> new OperationInvocation(null, "{}"));
    assertThrows(
        NullPointerException.class, () -> new OperationInvocation(TARGET, null));
  }
}
