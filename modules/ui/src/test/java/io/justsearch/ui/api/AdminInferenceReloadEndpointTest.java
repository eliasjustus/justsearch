package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.RuntimeIdentityView;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 412 follow-up: validates the {@code POST /api/admin/inference/reload} handler
 * dispatches correctly across the four documented response classes (200 OK, 503 unavailable,
 * 500 unexpected error, operator-reason echo).
 */
@DisplayName("AdminInferenceReloadHandlers — POST /api/admin/inference/reload")
final class AdminInferenceReloadEndpointTest {

  private static final Logger log =
      LoggerFactory.getLogger(AdminInferenceReloadEndpointTest.class);

  private Context ctx;
  private OnlineAiService onlineAiService;
  private Supplier<InferenceRuntimeView> inferenceSnapshotSupplier;

  @BeforeEach
  void setUp() {
    ctx = mock(Context.class);
    onlineAiService = mockCombinedService();
    inferenceSnapshotSupplier = () -> viewOffline();
    // Default: ctx.status(N).json(payload) chain returns ctx for fluency.
    when(ctx.status(any(int.class))).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);
  }

  @Test
  @DisplayName("200 OK: returns transitionDurationMs + phase + generationId + echoed reason")
  void successPath() {
    when(((OnlineAiRuntimeControl) onlineAiService).reloadRuntime()).thenReturn(42L);
    inferenceSnapshotSupplier = () -> viewWithIdentity("ONLINE", 7L, "model.gguf", 8080);
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("reason", "validation_test"));

    AdminInferenceReloadHandlers.handleAdminInferenceReload(ctx, onlineAiService, inferenceSnapshotSupplier, log);

    verify(ctx).status(200);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(ctx, atLeastOnce()).json(captor.capture());
    Map<String, Object> payload = captor.getValue();
    assertEquals(42L, payload.get("transitionDurationMs"));
    assertEquals("ONLINE", payload.get("phase"));
    assertEquals(7L, payload.get("generationId"));
    assertEquals("validation_test", payload.get("reason"));
    verify((OnlineAiRuntimeControl) onlineAiService).reloadRuntime();
  }

  @Test
  @DisplayName("200 OK: defaults reason to admin_triggered when body is empty")
  void successDefaultReason() {
    when(((OnlineAiRuntimeControl) onlineAiService).reloadRuntime()).thenReturn(0L);
    when(ctx.bodyAsClass(Map.class)).thenReturn(new HashMap<>());

    AdminInferenceReloadHandlers.handleAdminInferenceReload(ctx, onlineAiService, inferenceSnapshotSupplier, log);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(ctx, atLeastOnce()).json(captor.capture());
    assertEquals("admin_triggered", captor.getValue().get("reason"));
  }

  @Test
  @DisplayName("200 OK: omits generationId when identity is null (OFFLINE)")
  void successOfflineNoIdentity() {
    when(((OnlineAiRuntimeControl) onlineAiService).reloadRuntime()).thenReturn(0L);
    when(ctx.bodyAsClass(Map.class)).thenThrow(new RuntimeException("no body"));

    AdminInferenceReloadHandlers.handleAdminInferenceReload(ctx, onlineAiService, inferenceSnapshotSupplier, log);

    verify(ctx).status(200);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(ctx, atLeastOnce()).json(captor.capture());
    Map<String, Object> payload = captor.getValue();
    assertEquals("OFFLINE", payload.get("phase"));
    assertEquals(false, payload.containsKey("generationId"));
  }

  @Test
  @DisplayName("503: when OnlineAiService is not also a RuntimeControl")
  void unavailable503() {
    OnlineAiService notControl = mock(OnlineAiService.class);
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("reason", "x"));

    AdminInferenceReloadHandlers.handleAdminInferenceReload(ctx, notControl, inferenceSnapshotSupplier, log);

    verify(ctx).status(503);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(ctx, times(1)).json(captor.capture());
    assertNotNull(captor.getValue().get("error"));
  }

  @Test
  @DisplayName("500: when reloadRuntime throws unexpectedly")
  void unexpectedError500() {
    when(((OnlineAiRuntimeControl) onlineAiService).reloadRuntime())
        .thenThrow(new RuntimeException("boom"));
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("reason", "x"));

    AdminInferenceReloadHandlers.handleAdminInferenceReload(ctx, onlineAiService, inferenceSnapshotSupplier, log);

    verify(ctx).status(500);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(ctx, times(1)).json(captor.capture());
    assertEquals("boom", captor.getValue().get("error"));
  }

  // ---- helpers ----

  /**
   * Returns a Mockito mock that implements both {@link OnlineAiService} and
   * {@link OnlineAiRuntimeControl}, so the handler's {@code instanceof} check passes.
   */
  private static OnlineAiService mockCombinedService() {
    return mock(
        OnlineAiService.class,
        org.mockito.Mockito.withSettings().extraInterfaces(OnlineAiRuntimeControl.class));
  }

  private static InferenceRuntimeView viewOffline() {
    return new InferenceRuntimeView(
        "OFFLINE", null, false, null, new LifecycleCounters(0L, 0L, 0L));
  }

  private static InferenceRuntimeView viewWithIdentity(
      String phase, long generationId, String modelId, int port) {
    return new InferenceRuntimeView(
        phase,
        new RuntimeIdentityView(generationId, modelId, port, 1_700_000_000_000L),
        false,
        null,
        new LifecycleCounters(0L, 0L, generationId));
  }
}
