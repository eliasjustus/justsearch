/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler for {@code core.set-chat-enabled} (tempdoc 737 §12b).
 *
 * <p>The intent-write operation that supersedes {@code core.switch-inference-mode}: it records the
 * user's desired {@code chatEnabled} state on the runtime spec and nudges the reconciler to
 * converge. It carries <b>no preconditions</b> — an intent to turn chat on/off cannot be "denied
 * because the thing it asks for is off" (§8a's circular class made inexpressible). Enforcement
 * (enterprise online-AI policy, GPU availability) happens at convergence inside the reconciler, not
 * at intent time; the operation always records the intent and returns the observed state so the UI
 * can render honestly (soft-off, transitioning, etc.).
 *
 * <p>Args: {@code {"enabled": boolean}} (required). Returns {@code structuredData} carrying the
 * now-current {@code chatEnabled} spec bit and the observed {@code engineState}
 * ({@code Down}/{@code Starting}/{@code Healthy}/{@code Recovering}).
 */
public final class SetChatEnabledHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(SetChatEnabledHandler.class);

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final Supplier<RuntimeSpecStore> specStoreSupplier;
  private final Supplier<RuntimeReconciler> reconcilerSupplier;

  public SetChatEnabledHandler(
      Supplier<RuntimeSpecStore> specStoreSupplier,
      Supplier<RuntimeReconciler> reconcilerSupplier) {
    this.specStoreSupplier = Objects.requireNonNull(specStoreSupplier, "specStoreSupplier");
    this.reconcilerSupplier = Objects.requireNonNull(reconcilerSupplier, "reconcilerSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    boolean enabled;
    try {
      JsonNode root =
          MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode enabledNode = root.get("enabled");
      if (enabledNode == null || !enabledNode.isBoolean()) {
        return OperationResult.failure("Missing required arg: enabled (boolean)");
      }
      enabled = enabledNode.asBoolean();
    } catch (Exception e) {
      return OperationResult.failure("Invalid args: " + e.getMessage());
    }
    return RuntimeIntentWrite.apply(specStoreSupplier, reconcilerSupplier, enabled, log);
  }

  /**
   * Shared spec-write path for the intent-write op and the {@code core.switch-inference-mode} alias
   * (tempdoc 737 §12b): write the {@code chatEnabled} bit via {@link RuntimeSpecStore}, nudge the
   * reconciler via {@link RuntimeReconciler#specChanged()}, and project the observed state back.
   */
  static final class RuntimeIntentWrite {
    private RuntimeIntentWrite() {}

    static OperationResult apply(
        Supplier<RuntimeSpecStore> specStoreSupplier,
        Supplier<RuntimeReconciler> reconcilerSupplier,
        boolean enabled,
        Logger log) {
      RuntimeSpecStore specStore;
      RuntimeReconciler reconciler;
      try {
        specStore = specStoreSupplier.get();
        reconciler = reconcilerSupplier.get();
      } catch (RuntimeException e) {
        log.warn("SetChatEnabled: runtime-authority supplier threw", e);
        return OperationResult.failure("Runtime authority unavailable: " + e.getMessage());
      }
      if (specStore == null || reconciler == null) {
        return OperationResult.failure(
            "Runtime authority unavailable (AI runtime not configured)");
      }

      specStore.setChatEnabled(enabled);
      reconciler.specChanged();

      boolean chatEnabled = reconciler.currentSpec().chatEnabled();
      String engineState = engineStateOf(reconciler);
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("chatEnabled", chatEnabled);
      data.put("engineState", engineState);
      return OperationResult.success(
          enabled ? "Chat AI enabled" : "Chat AI disabled", data);
    }

    private static String engineStateOf(RuntimeReconciler reconciler) {
      var status = reconciler.current();
      if (status == null) {
        return "";
      }
      return status
          .condition(io.justsearch.app.services.runtimestate.RuntimeStatus.Axis.ENGINE)
          .map(io.justsearch.app.services.runtimestate.RuntimeStatus.Condition::status)
          .orElse("");
    }
  }
}
