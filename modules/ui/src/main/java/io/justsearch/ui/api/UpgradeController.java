/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.OperationLease;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.OperationLeaseSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Session-token-protected Head half of the two-step upgrade prepare/shutdown handshake. */
final class UpgradeController {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final OperationLeaseService leases;
  private final Runnable orderlyShutdown;

  UpgradeController(OperationLeaseService leases, Runnable orderlyShutdown) {
    this.leases = leases;
    this.orderlyShutdown = orderlyShutdown;
  }

  void prepare(Context ctx) {
    OperationLeaseSnapshot snapshot = leases.freezeAdmission("application upgrade");
    ctx.json(response(snapshot));
  }

  void cancel(Context ctx) {
    String preparationId = requiredPreparationId(ctx);
    leases.releaseAdmission(preparationId);
    ctx.json(Map.of("cancelled", true, "preparationId", preparationId));
  }

  void commitShutdown(Context ctx) {
    String preparationId = requiredPreparationId(ctx);
    OperationLeaseSnapshot snapshot = leases.snapshot();
    if (!snapshot.admissionFrozen() || !preparationId.equals(snapshot.preparationId())) {
      ctx.status(409)
          .json(
              Map.of(
                  "error", "Preparation id does not own the active admission barrier",
                  "errorCode", "UPGRADE_PREPARATION_MISMATCH"));
      return;
    }
    List<OperationLease> blockers = blocking(snapshot);
    if (!blockers.isEmpty()) {
      ctx.status(409).json(response(snapshot));
      return;
    }
    byte[] response =
        JSON.writeValueAsBytes(
            Map.of("shutdownAccepted", true, "preparationId", preparationId));
    ctx.contentType("application/json");
    ctx.res().setContentLength(response.length);
    try {
      ctx.res().getOutputStream().write(response);
      ctx.res().flushBuffer();
    } catch (IOException e) {
      throw new UncheckedIOException("failed to acknowledge orderly upgrade shutdown", e);
    }
    Thread.ofPlatform()
        .daemon(true)
        .name("upgrade-orderly-shutdown")
        .start(orderlyShutdown);
  }

  private static Map<String, Object> response(OperationLeaseSnapshot snapshot) {
    List<OperationLease> blockers = blocking(snapshot);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("preparationId", snapshot.preparationId());
    response.put("admissionFrozen", snapshot.admissionFrozen());
    response.put("ready", blockers.isEmpty());
    response.put("blockers", blockers);
    response.put("interruptibleWithLoss", List.of());
    response.put("activeLeases", snapshot.activeLeases());
    return response;
  }

  private static List<OperationLease> blocking(OperationLeaseSnapshot snapshot) {
    // Until operation owners expose an explicit cancellation acknowledgement protocol,
    // every active lease must drain. Treating "interruptible" as already interrupted
    // would let the installer race a writer that is still running.
    return List.copyOf(snapshot.activeLeases());
  }

  private static String requiredPreparationId(Context ctx) {
    try {
      JsonNode root = JSON.readTree(ctx.body());
      JsonNode value = root == null ? null : root.get("preparationId");
      String preparationId = value == null ? null : value.asText();
      if (preparationId == null || preparationId.isBlank()) {
        throw new IllegalArgumentException("preparationId is required");
      }
      return preparationId;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("Request body must contain preparationId", e);
    }
  }
}
