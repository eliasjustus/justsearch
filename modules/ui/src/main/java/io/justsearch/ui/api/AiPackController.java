/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.services.ai.pack.AiPackImportService;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP routing layer for the offline AI Pack import endpoints. Service-impl logic lives in
 * {@code io.justsearch.app.services.packimport.PackImportServiceImpl} (tempdoc 519 §9 Step 3).
 */
public final class AiPackController {
  private static final Logger log = LoggerFactory.getLogger(AiPackController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final AiPackImportService service;
  private final Telemetry telemetry;

  public AiPackController(AiPackImportService service, Telemetry telemetry) {
    this.service = service;
    this.telemetry = telemetry;
  }

  public void handleGetStatus(Context ctx) {
    ctx.json(service.getStatus());
  }

  public void handleGetInstalled(Context ctx) {
    ctx.json(service.getInstalledPacks());
  }

  public void handlePreflight(Context ctx) {
    String path = null;
    try {
      JsonNode root = MAPPER.readTree(ctx.body());
      if (root != null && root.has("path")) {
        path = root.get("path").asText(null);
      }
    } catch (Exception ignored) {
      // tolerate
    }
    if (path == null || path.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.PACK_PATH_REQUIRED, "Missing pack path", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      ctx.json(service.preflight(Path.of(path)));
    } catch (io.justsearch.app.api.AiPackPreflightException e) {
      ctx.status(400)
          .json(ApiErrorHandler.toResponse(e.errorCode(), e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to preflight pack", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(ApiErrorCode.PACK_PREFLIGHT_FAILED, "Failed to preflight pack", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleImport(Context ctx) {
    String path = null;
    boolean allowDowngrade = false;
    try {
      JsonNode root = MAPPER.readTree(ctx.body());
      if (root != null) {
        if (root.has("path")) {
          path = root.get("path").asText(null);
        }
        if (root.has("allowDowngrade")) {
          allowDowngrade = root.get("allowDowngrade").asBoolean(false);
        }
      }
    } catch (Exception ignored) {
      // tolerate
    }
    if (path == null || path.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.PACK_PATH_REQUIRED, "Missing pack path", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      service.startImport(Path.of(path), allowDowngrade);
      ctx.json(service.getStatus());
    } catch (IllegalStateException e) {
      ctx.status(409).json(ApiErrorHandler.toResponse(ApiErrorCode.PACK_IMPORT_RUNNING, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to start pack import", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.PACK_IMPORT_START_FAILED, "Failed to start pack import", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  // PackImportService interface impl is now io.justsearch.app.services.packimport.PackImportServiceImpl
  // (tempdoc 519 §9 Step 3). LocalApiServer constructs both this controller (HTTP routing only)
  // and the impl; the impl is wired into the operation handlers via the typed service records.
}
