/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.telemetry.Telemetry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a deterministic "UI is connected" handshake emitted by the frontend.
 *
 * <p>Designed for smoke verification (desktop parity harness) and debugging, not for end-user
 * features. Storage is in-memory (single-latest) and resets on backend restart.
 */
public final class UiReadyController {
  private static final Logger log = LoggerFactory.getLogger(UiReadyController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  public static final String HANDSHAKE_SCHEMA = "UI_READY_HANDSHAKE_V1";

  /**
   * v1 handshake payload emitted by the UI once it has successfully connected to the backend.
   *
   * @param schema must be {@link #HANDSHAKE_SCHEMA}
   * @param runId correlation id (required for smoke runs; best-effort optional otherwise)
   * @param runtime "browser" | "tauri" | "javafx" | "unknown"
   * @param apiSource "tauri" | "env" | "url" | "auto" | "proxy" | "bridge" | "unresolved"
   * @param uiConnectedAtMs UI-side timestamp (optional)
   * @param meta optional debug metadata (UI build, version, etc.)
   */
  public record UiReadyHandshakeV1(
      String schema,
      String runId,
      String runtime,
      String apiSource,
      Long uiConnectedAtMs,
      Map<String, Object> meta) {}

  /** Backend-recorded snapshot (includes request headers for CORS/origin evidence). */
  public record UiReadySnapshot(
      long receivedAtEpochMs, String originHeader, String userAgent, UiReadyHandshakeV1 handshake) {}

  private final AtomicReference<UiReadySnapshot> last = new AtomicReference<>();
  private final EventBuffer eventBuffer;
  private final Telemetry telemetry;

  public UiReadyController(EventBuffer eventBuffer, Telemetry telemetry) {
    this.eventBuffer = Objects.requireNonNull(eventBuffer, "eventBuffer");
    this.telemetry = telemetry;
  }

  public void handlePost(Context ctx) {
    final UiReadyHandshakeV1 payload;
    try {
      payload = MAPPER.readValue(ctx.body(), UiReadyHandshakeV1.class);
    } catch (Exception e) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_JSON, "Invalid JSON body", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    if (payload == null || payload.schema() == null || !HANDSHAKE_SCHEMA.equals(payload.schema())) {
      ctx.status(400)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INVALID_SCHEMA, "Invalid schema; expected " + HANDSHAKE_SCHEMA, telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    long receivedAt = System.currentTimeMillis();
    String originHeader = ctx.header("Origin");
    String userAgent = ctx.header("User-Agent");
    UiReadySnapshot snap = new UiReadySnapshot(receivedAt, originHeader, userAgent, payload);
    last.set(snap);

    try {
      String runIdForEvent = payload.runId() == null ? "" : payload.runId();
      String runtimeForEvent = payload.runtime() == null ? "unknown" : payload.runtime();
      String apiSourceForEvent = payload.apiSource() == null ? "unresolved" : payload.apiSource();
      String originForEvent = originHeader == null ? "<absent>" : originHeader;
      eventBuffer.info(
          "UiReadyController",
          HANDSHAKE_SCHEMA,
          Map.of(
              "runId",
              runIdForEvent,
              "runtime",
              runtimeForEvent,
              "apiSource",
              apiSourceForEvent,
              "originHeader",
              originForEvent));
    } catch (Exception ignored) {
      // best-effort
    }

    log.info(
        "Recorded UI ready handshake (runtime={}, apiSource={}, runId={})",
        payload.runtime(),
        payload.apiSource(),
        payload.runId());

    // NOTE: Header values may be null depending on runtime/origin behavior; avoid Map.of which forbids nulls.
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", true);
    response.put("receivedAtEpochMs", receivedAt);
    response.put("originHeader", originHeader);
    response.put("userAgent", userAgent);
    response.put("handshake", payload);
    ctx.json(response);
  }

  public void handleGet(Context ctx) {
    UiReadySnapshot snap = last.get();
    if (snap == null) {
      ctx.json(Map.of("ready", false));
      return;
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ready", true);
    response.put("receivedAtEpochMs", snap.receivedAtEpochMs());
    response.put("originHeader", snap.originHeader());
    response.put("userAgent", snap.userAgent());
    response.put("handshake", snap.handshake());
    ctx.json(response);
  }
}
