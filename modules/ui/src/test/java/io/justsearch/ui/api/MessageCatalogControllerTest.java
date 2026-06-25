package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.telemetry.Telemetry;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests for {@link MessageCatalogController}.
 *
 * <p>Verifies (per tempdoc 431 §C and Option A followup):
 * <ul>
 *   <li>{@code GET /api/messages/errors/en} returns the cached catalog with the expected
 *       envelope shape: {@code {$schema, schemaVersion, locale, namespace, messages}}.
 *   <li>{@code GET /api/messages/errors/EN} (case-insensitive) also returns the catalog.
 *   <li>{@code GET /api/messages/errors/de} (or any non-en) returns a 404 with the
 *       V1-en-only hint, per tempdoc 434 V1 scope.
 *   <li>{@code messages} contains exactly one key per ApiErrorCode + AgentErrorCode value
 *       (with INTERNAL_ERROR shared).
 *   <li>Response carries {@code Cache-Control} and {@code ETag} headers.
 *   <li>{@code If-None-Match} matching the ETag returns 304 Not Modified with no body.
 * </ul>
 */
@DisplayName("MessageCatalogController")
class MessageCatalogControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static MessageCatalogController controller;
  private static Telemetry telemetry;

  @BeforeAll
  static void setup() {
    telemetry = mock(Telemetry.class);
    controller =
        new MessageCatalogController(
            "errors", "/messages/errors.en.properties", telemetry);
  }

  @Test
  @DisplayName("locale=en returns the cached catalog with caching headers")
  void enLocaleReturnsCatalogWithCachingHeaders() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("locale")).thenReturn("en");
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handle(ctx);

    // Caching headers emitted on 200 path.
    verify(ctx).header(eq("ETag"), startsWith("\""));
    verify(ctx).header("Cache-Control", "public, max-age=3600");

    ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(bodyCaptor.capture());
    assertEnvelope(bodyCaptor.getValue());
  }

  @Test
  @DisplayName("locale=EN (case-insensitive) returns the cached catalog")
  void enLocaleCaseInsensitive() {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("locale")).thenReturn("EN");
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handle(ctx);

    verify(ctx).result(any(byte[].class));
  }

  @Test
  @DisplayName("locale=de returns 404 with V1-en-only hint")
  void nonEnLocaleReturns404() {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("locale")).thenReturn("de");
    when(ctx.status(anyInt())).thenReturn(ctx);
    when(ctx.endpointHandlerPath()).thenReturn("/api/messages/errors/{locale}");

    controller.handle(ctx);

    verify(ctx).status(404);
    verify(ctx, never()).result(any(byte[].class));
  }

  @Test
  @DisplayName("response includes $schema field per tempdoc 434 §Catalog file shape")
  void responseIncludesSchemaField() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("locale")).thenReturn("en");
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handle(ctx);

    ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(bodyCaptor.capture());

    JsonNode root = MAPPER.readTree(bodyCaptor.getValue());
    JsonNode schemaField = root.get("$schema");
    assertNotNull(schemaField, "$schema field must be present per 434 §Catalog file shape");
    assertEquals(
        "https://ssot.justsearch/v1/schemas/i18n-catalog.json",
        schemaField.asString(),
        "$schema must be the i18n-catalog namespace URI");
  }

  @Test
  @DisplayName("messages contains one key per ApiErrorCode + AgentErrorCode value")
  void messagesCoverAllEnumValues() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("locale")).thenReturn("en");
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handle(ctx);

    ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(ctx).result(bodyCaptor.capture());

    JsonNode root = MAPPER.readTree(bodyCaptor.getValue());
    JsonNode messages = root.get("messages");
    assertNotNull(messages, "messages field must be present");
    assertTrue(messages.isObject(), "messages must be an object");

    Set<String> expectedKeys = new HashSet<>();
    Arrays.stream(ApiErrorCode.values()).forEach(c -> expectedKeys.add("errors." + c.name()));
    Arrays.stream(AgentErrorCode.values()).forEach(c -> expectedKeys.add("errors." + c.name()));

    assertEquals(
        expectedKeys.size(),
        messages.size(),
        "messages count must equal |ApiErrorCode ∪ AgentErrorCode|");

    for (String key : expectedKeys) {
      assertTrue(
          messages.has(key),
          () -> "Expected key missing from /api/messages/errors/en response: " + key);
    }
  }

  @Test
  @DisplayName("If-None-Match matching the ETag returns 304 Not Modified")
  void conditionalGetMatchingEtagReturns304() {
    // First fetch the ETag the controller would emit.
    Context primer = mock(Context.class);
    when(primer.pathParam("locale")).thenReturn("en");
    when(primer.contentType("application/json")).thenReturn(primer);
    ArgumentCaptor<String> etagCaptor = ArgumentCaptor.forClass(String.class);
    controller.handle(primer);
    verify(primer).header(eq("ETag"), etagCaptor.capture());
    String etag = etagCaptor.getValue();

    // Second request with matching If-None-Match → 304, no body.
    Context ctx = mock(Context.class);
    when(ctx.pathParam("locale")).thenReturn("en");
    when(ctx.header("If-None-Match")).thenReturn(etag);
    when(ctx.status(anyInt())).thenReturn(ctx);

    controller.handle(ctx);

    verify(ctx).status(304);
    verify(ctx, never()).result(any(byte[].class));
    // 304 still emits ETag + Cache-Control so the client can refresh its cache window.
    verify(ctx).header("ETag", etag);
    verify(ctx).header("Cache-Control", "public, max-age=3600");
  }

  @Test
  @DisplayName("If-None-Match with stale ETag returns 200 with full body")
  void conditionalGetWithStaleEtagReturnsFullBody() {
    Context ctx = mock(Context.class);
    when(ctx.pathParam("locale")).thenReturn("en");
    when(ctx.header("If-None-Match")).thenReturn("\"stale-etag-value\"");
    when(ctx.contentType("application/json")).thenReturn(ctx);

    controller.handle(ctx);

    verify(ctx).result(any(byte[].class));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static void assertEnvelope(byte[] body) throws Exception {
    JsonNode root = MAPPER.readTree(body);
    assertEquals(
        "https://ssot.justsearch/v1/schemas/i18n-catalog.json",
        root.get("$schema").asString());
    assertEquals("1.0", root.get("schemaVersion").asString());
    assertEquals("en", root.get("locale").asString());
    assertEquals("errors", root.get("namespace").asString());
    JsonNode messages = root.get("messages");
    assertNotNull(messages);
    assertTrue(messages.isObject());
    assertTrue(messages.size() > 0, "messages must be non-empty");
  }

  // Mockito argument-matcher helpers for header(String, String).

  private static String eq(String value) {
    return org.mockito.ArgumentMatchers.eq(value);
  }

  private static String startsWith(String prefix) {
    return org.mockito.ArgumentMatchers.startsWith(prefix);
  }
}
