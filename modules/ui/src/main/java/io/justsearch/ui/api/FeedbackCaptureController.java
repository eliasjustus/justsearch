/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.services.feedback.FeedbackCaptureSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 778 — the settings surface for the default-on LOCAL feedback-capture flag (the 580 §17
 * disposition stream). Read/write the ONE {@link FeedbackCaptureSettings} authority every capture
 * site consults, and expose the visible privacy note (the product's loopback-only story is the
 * feature). Purely local: no outbound call, no egress.
 *
 * <ul>
 *   <li>{@code GET /api/feedback/capture} → {@code {enabled, local, privacyNote}}
 *   <li>{@code POST /api/feedback/capture} (body {@code {"enabled": bool}}) → the updated state
 * </ul>
 */
public final class FeedbackCaptureController {

  private static final Logger log = LoggerFactory.getLogger(FeedbackCaptureController.class);

  private final FeedbackCaptureSettings settings;

  public FeedbackCaptureController(FeedbackCaptureSettings settings) {
    this.settings = settings;
  }

  /** {@code GET /api/feedback/capture}. */
  public void handleGet(Context ctx) {
    ctx.json(state());
  }

  /** {@code POST /api/feedback/capture} — body {@code {"enabled": bool}}. */
  @SuppressWarnings("unchecked")
  public void handlePost(Context ctx) {
    try {
      Map<String, Object> body =
          ctx.body() == null || ctx.body().isEmpty()
              ? Map.of()
              : (Map<String, Object>) ctx.bodyAsClass(Map.class);
      if (!(body.get("enabled") instanceof Boolean enabled)) {
        ctx.status(400).json(Map.of("error", "enabled (boolean) is required"));
        return;
      }
      if (settings != null) {
        settings.setEnabled(enabled);
      }
      ctx.json(state());
    } catch (Exception e) {
      log.warn("Failed to update feedback-capture flag", e);
      ctx.status(400).json(Map.of("error", "malformed request body"));
    }
  }

  private Map<String, Object> state() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("enabled", settings == null || settings.isEnabled());
    out.put("local", true);
    out.put("privacyNote", FeedbackCaptureSettings.PRIVACY_NOTE);
    return out;
  }
}
