/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.EffectivePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP routing layer for runtime-variant activation endpoints. Service-impl logic for
 * {@code RuntimeVariantService} lives in
 * {@code io.justsearch.app.services.runtimevariant.RuntimeVariantServiceImpl}
 * (tempdoc 519 §9 Step 3).
 */
public final class AiRuntimeController {
  private static final Logger log = LoggerFactory.getLogger(AiRuntimeController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final RuntimeActivationService service;
  private final EnterprisePolicyService policyService;
  private final Telemetry telemetry;

  public AiRuntimeController(RuntimeActivationService service, EnterprisePolicyService policyService, Telemetry telemetry) {
    this.service = service;
    this.policyService = policyService;
    this.telemetry = telemetry;
  }

  public void handleGetStatus(Context ctx) {
    ctx.json(service.getStatus());
  }

  public void handleActivate(Context ctx) {
    String variantId = null;
    try {
      JsonNode root = MAPPER.readTree(ctx.body());
      if (root != null && root.has("variantId")) {
        variantId = root.get("variantId").asText(null);
      }
    } catch (Exception ignored) {
      // tolerate
    }
    if (variantId == null || variantId.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.VARIANT_ID_REQUIRED, "Missing variantId", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    // v3 enforcement: block activation when policy disables Online AI or GPU acceleration.
    try {
      EffectivePolicy p = policyService != null ? policyService.snapshot() : null;
      if (p != null) {
        if (!p.onlineAiEnabled()) {
          ctx.status(403)
              .json(
                  ApiErrorHandler.toResponse(
                      ApiErrorCode.POLICY_ONLINE_AI_DISABLED,
                      "Online AI is disabled by administrator policy.",
                      telemetry,
                      "/api/ai/runtime/activate"));
          return;
        }
        if (!p.gpuAccelerationEnabled()) {
          ctx.status(403)
              .json(
                  ApiErrorHandler.toResponse(
                      ApiErrorCode.POLICY_GPU_DISABLED,
                      "GPU acceleration is disabled by administrator policy.",
                      telemetry,
                      "/api/ai/runtime/activate"));
          return;
        }
      }
    } catch (Exception ignored) {
      // best-effort: enforcement also exists in RuntimeActivationService
    }

    try {
      service.startActivate(variantId);
      ctx.json(service.getStatus());
    } catch (IllegalStateException e) {
      ctx.status(409).json(ApiErrorHandler.toResponse(ApiErrorCode.RUNTIME_ACTIVATION_RUNNING, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to start runtime activation", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.RUNTIME_ACTIVATION_START_FAILED, "Failed to start runtime activation", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleDeactivate(Context ctx) {
    try {
      service.startDeactivate();
      ctx.json(service.getStatus());
    } catch (IllegalStateException e) {
      ctx.status(409).json(ApiErrorHandler.toResponse(ApiErrorCode.RUNTIME_ACTIVATION_RUNNING, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to start runtime deactivation", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.RUNTIME_DEACTIVATION_START_FAILED, "Failed to start runtime deactivation", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  // RuntimeVariantService interface impl moved to
  // io.justsearch.app.services.runtimevariant.RuntimeVariantServiceImpl
  // (tempdoc 519 §9 Step 3). LocalApiServer constructs both this controller and the impl.
}
