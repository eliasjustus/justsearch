/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.services.policy.UserPolicyWriter;
import io.justsearch.app.services.policy.UserPolicyWriter.UserPolicyWriteException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP routing layer for enterprise policy endpoints. PolicyService impl is in
 * {@code io.justsearch.app.services.policy.PolicyServiceImpl} (tempdoc 519 §9 Step 3).
 */
public final class PolicyController {
  private static final Logger log = LoggerFactory.getLogger(PolicyController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final java.util.function.Supplier<EffectivePolicy> snapshotSupplier;
  private final Telemetry telemetry;

  public PolicyController(EnterprisePolicyService policyService, Telemetry telemetry) {
    this((java.util.function.Supplier<EffectivePolicy>) () -> policyService.snapshot(), telemetry);
  }

  /** Package-private constructor for testing with a custom snapshot supplier. */
  PolicyController(java.util.function.Supplier<EffectivePolicy> snapshotSupplier, Telemetry telemetry) {
    this.snapshotSupplier = snapshotSupplier;
    this.telemetry = telemetry;
  }

  /**
   * Validates the current policy files without making changes.
   *
   * <p>Useful for admins to check if their policy.v1.json syntax is valid before deployment,
   * or to diagnose why AI features are unexpectedly disabled.
   */
  public void handleValidatePolicy(Context ctx) {
    try {
      EffectivePolicy effective = snapshotSupplier.get();
      boolean machineValid = !effective.machinePolicyLoadFailed();
      boolean userValid = effective.user() == null || !effective.user().present() || effective.user().loaded();

      ctx.json(
          Map.of(
              "valid",
              machineValid && userValid,
              "machine",
              Map.of(
                  "path",
                  effective.machine() != null && effective.machine().path() != null
                      ? effective.machine().path().toAbsolutePath().toString()
                      : "",
                  "present",
                  effective.machine() != null && effective.machine().present(),
                  "valid",
                  machineValid,
                  "error",
                  effective.machinePolicyLoadError() != null ? effective.machinePolicyLoadError() : ""),
              "user",
              Map.of(
                  "path",
                  effective.user() != null && effective.user().path() != null
                      ? effective.user().path().toAbsolutePath().toString()
                      : "",
                  "present",
                  effective.user() != null && effective.user().present(),
                  "valid",
                  userValid,
                  "error",
                  effective.user() != null && effective.user().error() != null
                      ? effective.user().error()
                      : ""),
              "aiDisabledDueToPolicy",
              effective.machinePolicyLoadFailed()));
    } catch (Exception e) {
      log.error("Failed to validate policy", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.POLICY_VALIDATE_FAILED, "Failed to validate policy", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleGetEffectivePolicy(Context ctx) {
    try {
      ctx.json(snapshotSupplier.get());
    } catch (Exception e) {
      log.error("Failed to compute effective policy", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.POLICY_EFFECTIVE_FAILED, "Failed to compute effective policy", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Creates a user policy file under AI Home only if missing (safe-by-design).
   *
   * <p>Rules:
   * <ul>
   *   <li>Never writes machine policy</li>
   *   <li>Reject if machine policy is present</li>
   *   <li>Reject if user policy already exists</li>
   * </ul>
   */
  public void handleCreateUserPolicy(Context ctx) {
    String manifestSha256 = null;
    try {
      JsonNode root = MAPPER.readTree(ctx.body());
      if (root != null && root.has("manifestSha256")) {
        manifestSha256 = root.get("manifestSha256").asText(null);
      }
    } catch (Exception ignored) {
      // tolerate invalid/missing body; handled below
    }

    if (manifestSha256 == null || manifestSha256.isBlank()) {
      ctx.status(400)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.MANIFEST_SHA_REQUIRED, "Missing manifestSha256", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      EffectivePolicy effective = snapshotSupplier.get();
      UserPolicyWriter.Result r =
          UserPolicyWriter.createUserPolicyForPackImportIfMissing(effective, manifestSha256);
      ctx.json(
          Map.of(
              "success",
              true,
              "path",
              r.path() == null ? "" : r.path().toAbsolutePath().toString()));
    } catch (UserPolicyWriteException e) {
      ctx.status(e.httpStatus())
          .json(ApiErrorHandler.toResponse(e.errorCode(), e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to create user policy", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.USER_POLICY_CREATE_FAILED, "Failed to create user policy", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Appends a pack manifest digest to the existing user policy allowlist (schemaVersion=1 only).
   *
   * <p>Rules:
   * <ul>
   *   <li>Never writes machine policy</li>
   *   <li>Reject if machine policy is present</li>
   *   <li>Reject if user policy is missing or invalid</li>
   *   <li>Preserve all existing user policy fields; only updates allowlists.packManifestSha256 (+ updatedAt)</li>
   * </ul>
   */
  public void handleAddPackManifestShaToUserAllowlist(Context ctx) {
    String manifestSha256 = null;
    try {
      JsonNode root = MAPPER.readTree(ctx.body());
      if (root != null && root.has("manifestSha256")) {
        manifestSha256 = root.get("manifestSha256").asText(null);
      }
    } catch (Exception ignored) {
      // tolerate invalid/missing body; handled below
    }

    if (manifestSha256 == null || manifestSha256.isBlank()) {
      ctx.status(400)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.MANIFEST_SHA_REQUIRED, "Missing manifestSha256", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      EffectivePolicy effective = snapshotSupplier.get();
      UserPolicyWriter.AppendResult r =
          UserPolicyWriter.addPackManifestShaToUserPolicyAllowlist(effective, manifestSha256);
      ctx.json(
          Map.of(
              "success",
              true,
              "path",
              r.path() == null ? "" : r.path().toAbsolutePath().toString(),
              "changed",
              r.changed(),
              "allowlistedCount",
              r.allowlistedCount()));
    } catch (UserPolicyWriteException e) {
      ctx.status(e.httpStatus())
          .json(ApiErrorHandler.toResponse(e.errorCode(), e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to append pack digest to user policy", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.USER_POLICY_UPDATE_FAILED, "Failed to update user policy", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  // PolicyService impl moved to io.justsearch.app.services.policy.PolicyServiceImpl
  // (tempdoc 519 §9 Step 3).
}
