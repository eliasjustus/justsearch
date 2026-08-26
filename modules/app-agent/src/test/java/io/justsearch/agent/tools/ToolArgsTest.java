/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tempdoc 877 §2.5 — the shared arg helper's contract, written as the union of what the five tools
 * did separately, so adopting it cannot silently change any of their behaviours.
 */
class ToolArgsTest {

  private static JsonNode args(String json) {
    return ToolArgs.parse(json);
  }

  @Test
  @DisplayName("a numeric STRING coerces — the divergence that silently reset paged reads")
  void numericStringCoerces() {
    // SearchTool's asInt already accepted "5"; ReadDocumentTool's isNumber() check did not, so
    // offset_chars:"3000" fell back to 0 and restarted the read at page 0. One answer now.
    assertEquals(3000, ToolArgs.intArg(args("{\"offset_chars\":\"3000\"}"), "offset_chars", 0, 0, 99999));
    assertEquals(5, ToolArgs.intArg(args("{\"limit\":\"5\"}"), "limit", 3, 1, 20));
    assertEquals(5, ToolArgs.intArg(args("{\"limit\":5}"), "limit", 3, 1, 20));
    assertEquals(7, ToolArgs.intArg(args("{\"limit\":\" 7 \"}"), "limit", 3, 1, 20), "whitespace tolerated");
  }

  @Test
  @DisplayName("a non-numeric value is rejected LOUDLY, naming the field")
  void nonNumericRejected() {
    ToolArgs.BadArgument e =
        assertThrows(
            ToolArgs.BadArgument.class,
            () -> ToolArgs.intArg(args("{\"limit\":\"lots\"}"), "limit", 3, 1, 20));
    assertTrue(e.getMessage().contains("limit"), "the message names the field: " + e.getMessage());
    assertTrue(e.getMessage().contains("lots"), "the message quotes the value: " + e.getMessage());
  }

  @Test
  @DisplayName("absent / JSON-null / blank all yield the fallback, never an exception")
  void absentYieldsFallback() {
    assertEquals(3, ToolArgs.intArg(args("{}"), "limit", 3, 1, 20));
    assertEquals(3, ToolArgs.intArg(args("{\"limit\":null}"), "limit", 3, 1, 20));
    assertEquals(3, ToolArgs.intArg(args("{\"limit\":\"\"}"), "limit", 3, 1, 20));
    assertEquals(3, ToolArgs.intArg(args("{\"limit\":\"   \"}"), "limit", 3, 1, 20));
  }

  @Test
  @DisplayName("above max clamps to max; below min yields the FALLBACK, not min")
  void boundsReproduceEveryCallSite() {
    // SearchTool: Math.min(limit, MAX_LIMIT) then `if (limit < 1) limit = DEFAULT_LIMIT`.
    assertEquals(20, ToolArgs.intArg(args("{\"limit\":500}"), "limit", 3, 1, 20), "clamps to max");
    assertEquals(3, ToolArgs.intArg(args("{\"limit\":0}"), "limit", 3, 1, 20), "below min ⇒ fallback");
    assertEquals(3, ToolArgs.intArg(args("{\"limit\":-4}"), "limit", 3, 1, 20), "negative ⇒ fallback");
    // ReadDocumentTool: `requested <= 0 ? PAGE : min(requested, PAGE)`.
    assertEquals(3000, ToolArgs.intArg(args("{\"max_chars\":0}"), "max_chars", 3000, 1, 3000));
    assertEquals(3000, ToolArgs.intArg(args("{\"max_chars\":9999}"), "max_chars", 3000, 1, 3000));
    assertEquals(500, ToolArgs.intArg(args("{\"max_chars\":500}"), "max_chars", 3000, 1, 3000));
    // ReadDocumentTool's offset: min is 0, so 0 is a legitimate value and must NOT become fallback.
    assertEquals(0, ToolArgs.intArg(args("{\"offset_chars\":0}"), "offset_chars", 0, 0, 99999));
  }

  @Test
  @DisplayName("booleans: absent, null and non-boolean are all false")
  void boolArgSemantics() {
    assertTrue(ToolArgs.boolArg(args("{\"list_files\":true}"), "list_files"));
    assertFalse(ToolArgs.boolArg(args("{\"list_files\":false}"), "list_files"));
    assertFalse(ToolArgs.boolArg(args("{}"), "list_files"));
    assertFalse(ToolArgs.boolArg(args("{\"list_files\":null}"), "list_files"));
  }

  @Test
  @DisplayName("strings: null when absent or JSON-null; blank is returned, not swallowed")
  void stringArgSemantics() {
    assertEquals("hello", ToolArgs.stringArg(args("{\"query\":\"hello\"}"), "query"));
    assertNull(ToolArgs.stringArg(args("{}"), "query"));
    assertNull(ToolArgs.stringArg(args("{\"query\":null}"), "query"));
    // Callers disagree about what a blank means (search rejects it, browse treats it as "roots"),
    // so the helper must hand the blank back rather than decide for them.
    assertEquals("", ToolArgs.stringArg(args("{\"query\":\"\"}"), "query"));
    assertEquals("fallback", ToolArgs.stringArg(args("{}"), "query", "fallback"));
  }

  @Test
  @DisplayName("parse rejects malformed JSON with a Jackson exception AgentToolErrors classifies")
  void parseThrowsOnMalformedJson() {
    Exception e = assertThrows(Exception.class, () -> ToolArgs.parse("{not json"));
    assertEquals(
        io.justsearch.app.api.ApiErrorCode.BAD_REQUEST.name(),
        AgentToolErrors.classify("t", "Test error", e).errorCode().orElseThrow(),
        "malformed model JSON is a BAD_REQUEST, not an INTERNAL_ERROR");
  }
}
