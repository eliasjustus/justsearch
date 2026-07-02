/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * v2 AI install controller — hardware-aware, package-based.
 *
 * <p>Same 5 endpoints as v1, same URLs. Uses {@link AiInstallService} internally.
 */
public final class AiInstallController {
  private static final Logger log = LoggerFactory.getLogger(AiInstallController.class);
  private static final JsonMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final AiInstallService service;
  private final Telemetry telemetry;

  public AiInstallController(AiInstallService service, Telemetry telemetry) {
    this.service = service;
    this.telemetry = telemetry;
  }

  /**
   * Tempdoc 374 alpha.17 R3: forwarder for {@link AiInstallService#setKnowledgeServer}.
   * Called from {@code LocalApiServer.lateBindKnowledgeServer} once the Worker
   * bootstrap completes.
   */
  public void setKnowledgeServer(KnowledgeServerBootstrap knowledgeServer) {
    service.setKnowledgeServer(knowledgeServer);
  }

  public void handleGetManifest(Context ctx) {
    try {
      ctx.json(service.getManifest());
    } catch (Exception e) {
      log.error("Failed to load AI install manifest", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.MANIFEST_UNAVAILABLE,
                  "Failed to load manifest",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleGetStatus(Context ctx) {
    ctx.json(service.getStatus());
  }

  /**
   * Side-effect-free per-tier weight preview (tempdoc 657). Drives the honest first-run download
   * breakdown in the UI before the user commits.
   */
  public void handleGetPlanPreview(Context ctx) {
    try {
      ctx.json(service.previewInstallPlan());
    } catch (Exception e) {
      log.error("Failed to compute AI install plan preview", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.MANIFEST_UNAVAILABLE,
                  "Failed to compute install plan preview",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleStart(Context ctx) {
    boolean acceptTerms = parseAcceptTerms(ctx);
    try {
      service.startInstall(acceptTerms);
      ctx.json(service.getStatus());
    } catch (AiInstallException e) {
      ctx.status(e.httpStatus())
          .json(
              ApiErrorHandler.toResponse(
                  e.errorCode(), e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to start AI install", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INSTALL_START_FAILED,
                  "Failed to start install",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleCancel(Context ctx) {
    try {
      service.cancel();
      ctx.json(service.getStatus());
    } catch (Exception e) {
      log.error("Failed to cancel AI install", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INSTALL_CANCEL_FAILED,
                  "Failed to cancel install",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleRepair(Context ctx) {
    boolean acceptTerms = parseAcceptTerms(ctx);
    try {
      service.repair(acceptTerms);
      ctx.json(service.getStatus());
    } catch (AiInstallException e) {
      ctx.status(e.httpStatus())
          .json(
              ApiErrorHandler.toResponse(
                  e.errorCode(), e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to repair AI install", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INSTALL_REPAIR_FAILED,
                  "Failed to repair",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  private static boolean parseAcceptTerms(Context ctx) {
    try {
      JsonNode root = MAPPER.readTree(ctx.body());
      if (root != null && root.has("acceptTerms")) {
        return root.get("acceptTerms").asBoolean(false);
      }
    } catch (Exception ignored) {
      // tolerate empty body
    }
    return false;
  }

  // ==========================================================================
  // BrainInstallService impl (slice 3a-2-c continuation).
  //
  // Mirrors handleStart / handleCancel / handleRepair's delegate path,
  // returning the post-call status as a Map<String, Object> so the app-api
  // interface doesn't leak the modules/ui-side AiInstallStatus type. HTTP
  // handlers retain typed-error → status-code mapping (AiInstallException
  // carries its own httpStatus + errorCode).
  // ==========================================================================
  // BrainInstallService impl moved to io.justsearch.app.services.braininstall.BrainInstallServiceImpl
  // (tempdoc 519 §9 Step 3).
}
