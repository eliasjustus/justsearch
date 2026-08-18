package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 842 review N2: {@code POST /api/ai/runtime/activate} must REJECT an unknown
 * {@code chatProfile} instead of letting {@link
 * io.justsearch.configuration.model.ChatModelProfile#resolve(String)}'s boot-time warn-fallback
 * silently activate the standard 9B — the exact outcome 842 exists to prevent. The fallback itself
 * stays lenient (a bad launch flag must not brick the engine); the guard lives at the request edge.
 *
 * <p>Harness mirrors {@link AdminInferenceReloadEndpointTest}: a mocked Javalin {@link Context}
 * with the {@code status(...).json(...)} chain stubbed for fluency.
 */
@DisplayName("AiRuntimeController — POST /api/ai/runtime/activate chatProfile validation")
final class AiRuntimeControllerChatProfileTest {

  private Context ctx;
  private RuntimeActivationService service;
  private AiRuntimeController controller;

  @BeforeEach
  void setUp() {
    ctx = mock(Context.class);
    service = mock(RuntimeActivationService.class);
    controller = new AiRuntimeController(service, null);
    when(ctx.status(any(int.class))).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);
  }

  @Test
  @DisplayName("typo profile → 400 naming the value and the valid ids, activation never started")
  void unknownProfileRejectedWithoutActivating() {
    when(ctx.body()).thenReturn("{\"variantId\":\"cuda12\",\"chatProfile\":\"compct\"}");

    controller.handleActivate(ctx);

    verify(ctx).status(400);
    verify(service, never()).startActivate(anyString(), any());
    verify(service, never()).startActivate(anyString());

    String error = errorMessage();
    assertTrue(error.contains("compct"), "names the rejected value: " + error);
    assertTrue(error.contains("standard"), "lists standard: " + error);
    assertTrue(error.contains("compact"), "lists compact: " + error);
    assertTrue(error.contains("paddle-ocr-vl"), "lists paddle-ocr-vl: " + error);
    assertTrue(error.contains("qwen-vl"), "lists the legacy qwen-vl alias: " + error);
    assertEquals("INVALID_REQUEST", errorPayload().get("errorCode"));
  }

  @Test
  @DisplayName("valid ids are accepted case-insensitively and threaded through verbatim")
  void knownProfilesAccepted() {
    for (String raw : new String[] {"compact", "standard", "qwen-vl", "COMPACT", "Standard", "paddle_ocr_vl"}) {
      Context c = mock(Context.class);
      RuntimeActivationService svc = mock(RuntimeActivationService.class);
      when(c.status(any(int.class))).thenReturn(c);
      when(c.json(any())).thenReturn(c);
      when(c.body()).thenReturn("{\"variantId\":\"cuda12\",\"chatProfile\":\"" + raw + "\"}");

      new AiRuntimeController(svc, null).handleActivate(c);

      verify(c, never()).status(400);
      verify(svc).startActivate("cuda12", raw);
    }
  }

  @Test
  @DisplayName("absent chatProfile stays a no-profile activation")
  void absentProfileAccepted() {
    when(ctx.body()).thenReturn("{\"variantId\":\"cuda12\"}");

    controller.handleActivate(ctx);

    verify(ctx, never()).status(400);
    verify(service).startActivate("cuda12", null);
  }

  @Test
  @DisplayName("blank chatProfile stays a no-profile activation (pre-842 behavior)")
  void blankProfileAccepted() {
    when(ctx.body()).thenReturn("{\"variantId\":\"cuda12\",\"chatProfile\":\"  \"}");

    controller.handleActivate(ctx);

    verify(ctx, never()).status(400);
    verify(service).startActivate("cuda12", "  ");
  }

  // ---- helpers ----

  private Map<String, Object> errorPayload() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(ctx, atLeastOnce()).json(captor.capture());
    return captor.getValue();
  }

  private String errorMessage() {
    Object error = errorPayload().get("error");
    return error == null ? "" : error.toString();
  }
}
