/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.status.InferenceRuntimeView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Tempdoc 412 follow-up: extracted handler logic for {@code POST /api/admin/inference/reload}.
 * Static method form so the unit test can exercise the handler directly without spinning up
 * the full {@code LocalApiServer}.
 *
 * <p>Behavior:
 * <ul>
 *   <li>Reads optional {@code reason} from the JSON body; defaults to {@code admin_triggered}.
 *   <li>Returns 503 if the inference runtime control is unavailable (AI disabled or no
 *       {@link OnlineAiRuntimeControl}-typed service).
 *   <li>Calls {@link OnlineAiRuntimeControl#reloadRuntime()} and returns the elapsed ms +
 *       post-swap phase + generationId.
 *   <li>Returns 500 on any unexpected exception.
 * </ul>
 */
public final class AdminInferenceReloadHandlers {

  private AdminInferenceReloadHandlers() {}

  public static void handleAdminInferenceReload(
      Context ctx,
      OnlineAiService onlineAiService,
      Supplier<InferenceRuntimeView> inferenceSnapshotSupplier,
      Logger log) {
    try {
      String reason = parseReason(ctx);

      if (!(onlineAiService instanceof OnlineAiRuntimeControl control)) {
        log.warn("admin inference reload requested but runtime control unavailable");
        ctx.status(503).json(Map.of("error", "inference runtime unavailable"));
        return;
      }

      log.info("admin inference reload triggered (reason={})", reason);
      long transitionDurationMs = control.reloadRuntime();
      InferenceRuntimeView view = inferenceSnapshotSupplier.get();
      log.info(
          "admin inference reload complete in {}ms (phase={}, generationId={})",
          transitionDurationMs,
          view.phase(),
          view.identity() != null ? view.identity().generationId() : null);

      Map<String, Object> response = new LinkedHashMap<>();
      response.put("transitionDurationMs", transitionDurationMs);
      response.put("phase", view.phase());
      if (view.identity() != null) {
        response.put("generationId", view.identity().generationId());
      }
      response.put("reason", reason);
      ctx.status(200).json(response);
    } catch (Exception e) {
      log.error("admin inference reload failed", e);
      ctx.status(500).json(Map.of("error", e.getMessage() == null ? "unknown" : e.getMessage()));
    }
  }

  /** Parses {@code reason} from the request body; returns {@code admin_triggered} as default. */
  @SuppressWarnings("unchecked")
  private static String parseReason(Context ctx) {
    String reason = "admin_triggered";
    try {
      Map<String, Object> body = ctx.bodyAsClass(Map.class);
      Object r = body == null ? null : body.get("reason");
      if (r instanceof String s && !s.isBlank()) {
        reason = s;
      }
    } catch (Exception ignored) {
      // Body is optional; default reason applies.
    }
    return reason;
  }
}
