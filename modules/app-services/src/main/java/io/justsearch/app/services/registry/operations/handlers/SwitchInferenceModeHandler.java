/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
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
 * Handler for {@code core.switch-inference-mode}.
 *
 * <p>Slice 3a-2-c continuation: BrainRuntimeSection Switch-to-Online /
 * Switch-to-Indexing buttons (single Operation with mode arg). Delegates to
 * {@link BrainRuntimeService#switchInferenceMode(String)} via lazy supplier.
 *
 * <p>Args shape: {@code {"mode": "online" | "indexing"}}. Returns the
 * post-switch current mode in {@code structuredData.mode}.
 */
public final class SwitchInferenceModeHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(SwitchInferenceModeHandler.class);

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final Supplier<BrainRuntimeService> brainRuntimeSupplier;

  public SwitchInferenceModeHandler(Supplier<BrainRuntimeService> brainRuntimeSupplier) {
    this.brainRuntimeSupplier = Objects.requireNonNull(brainRuntimeSupplier, "brainRuntimeSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    String mode;
    try {
      JsonNode root = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode modeNode = root.get("mode");
      if (modeNode == null || !modeNode.isTextual() || modeNode.asString().isBlank()) {
        return OperationResult.failure("Missing required arg: mode (use 'online' or 'indexing')");
      }
      mode = modeNode.asString();
    } catch (Exception e) {
      return OperationResult.failure("Invalid args: " + e.getMessage());
    }

    BrainRuntimeService brainRuntime;
    try {
      brainRuntime = brainRuntimeSupplier.get();
    } catch (RuntimeException e) {
      log.warn("SwitchInferenceModeHandler: brain-runtime supplier threw", e);
      return OperationResult.failure("Brain runtime service unavailable: " + e.getMessage());
    }
    if (brainRuntime == null) {
      return OperationResult.failure("Brain runtime service unavailable");
    }

    try {
      String currentMode = brainRuntime.switchInferenceMode(mode);
      return OperationResult.success(
          "Inference mode switched to " + currentMode, Map.of("mode", currentMode));
    } catch (IllegalArgumentException e) {
      // Bad input (mode value missing or unrecognized). Schema validation in
      // Phase C will catch most of these earlier; the handler still validates
      // for defense-in-depth.
      return OperationResult.failure(e.getMessage(), "INVALID_REQUEST", Map.of("mode", mode), false);
    } catch (IllegalStateException e) {
      // Used by BrainRuntimeService impl for "Online AI is disabled by
      // administrator policy."
      return OperationResult.failure(
          e.getMessage(), "POLICY_ONLINE_AI_DISABLED", Map.of("mode", mode), false);
    } catch (Exception e) {
      ModeTransitionException mte = findCause(e, ModeTransitionException.class);
      if (mte != null) {
        return failureForMteReason(mte, mode);
      }
      log.error("SwitchInferenceModeHandler: switchInferenceMode threw", e);
      return OperationResult.failure(
          "Mode switch failed: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "MODE_SWITCH_FAILED",
          Map.of("mode", mode),
          true);
    }
  }

  /**
   * Tempdoc 518 P3: pattern-match on the typed {@link InferenceFailure} payload (a sealed
   * sum-type with four sub-records, each carrying a per-category code enum) instead of the
   * legacy flat {@link ModeTransitionException.Reason} enum. Feature parity preserved against
   * the previous switch — INVALID_CONFIG / INSUFFICIENT_VRAM / CONFIG_REQUIRED /
   * EXTERNAL_SERVER_POLICY_BLOCKED / MISSING_DLL stay PERMANENT (not retryable);
   * EXTERNAL_SERVER_CONFLICT / ALREADY_TRANSITIONING stay retryable; all
   * {@link InferenceFailure.TransitionFailure} variants + remaining {@link
   * InferenceFailure.HealthFailure} + remaining {@link InferenceFailure.StartupFailure}
   * variants collapse into {@code MODE_SWITCH_FAILED} retryable. The {@code wireCode} from the
   * sub-record is surfaced in {@code details} so dashboards keep the underlying code.
   */
  private static OperationResult failureForMteReason(
      ModeTransitionException mte, String mode) {
    String message = mte.getMessage() == null ? mte.getClass().getSimpleName() : mte.getMessage();
    InferenceFailure failure = mte.failure();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("mode", mode);
    details.put("reason", mte.reason().name());
    details.put("wireCode", failure.wireCode());

    // Permanent (not retryable) — caller-fixable.
    if (failure instanceof InferenceFailure.ConfigFailure cf) {
      return switch (cf.code()) {
        case INVALID_CONFIG ->
            OperationResult.failure(message, "INVALID_CONFIG", details, false);
        case CONFIG_REQUIRED ->
            OperationResult.failure(message, "CONFIG_REQUIRED", details, false);
        case EXTERNAL_SERVER_CONFLICT ->
            OperationResult.failure(message, "EXTERNAL_SERVER_CONFLICT", details, true);
        case ALREADY_TRANSITIONING ->
            OperationResult.failure(message, "ALREADY_TRANSITIONING", details, true);
        case UNKNOWN ->
            OperationResult.failure(message, "MODE_SWITCH_FAILED", details, true);
      };
    }
    if (failure instanceof InferenceFailure.StartupFailure sf) {
      return switch (sf.code()) {
        case INSUFFICIENT_VRAM ->
            OperationResult.failure(message, "INSUFFICIENT_VRAM", details, false);
        case EXTERNAL_SERVER_POLICY_BLOCKED ->
            OperationResult.failure(
                message, "POLICY_EXTERNAL_SERVER_DISALLOWED", details, false);
        case MISSING_DLL -> OperationResult.failure(message, "MISSING_DLL", details, false);
        // Transient / runtime — caller MAY retry once underlying issue clears.
        case PORT_ALLOCATION_FAILED, PROCESS_EXITED, UNKNOWN ->
            OperationResult.failure(message, "MODE_SWITCH_FAILED", details, true);
      };
    }
    // TransitionFailure + HealthFailure — all transient.
    return OperationResult.failure(message, "MODE_SWITCH_FAILED", details, true);
  }

  /** Walks the cause chain looking for the first instance of {@code type}. */
  private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
    Throwable cur = e;
    int depth = 0;
    while (cur != null && depth < 10) {
      if (type.isInstance(cur)) {
        return type.cast(cur);
      }
      cur = cur.getCause();
      depth++;
    }
    return null;
  }
}
