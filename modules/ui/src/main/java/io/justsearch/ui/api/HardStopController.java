/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.services.registry.executor.GlobalHardStop;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operator control for the Global Hard Stop — tempdoc 550 E2.
 *
 * <p>{@code GET /api/agent/hard-stop} → {@code {engaged}}; {@code POST /api/agent/hard-stop}
 * with {@code {"engaged": true|false}} engages/releases it. While engaged, the trust lattice
 * denies every UNTRUSTED (agent/MCP/plugin/emission) dispatch — the circuit breaker, enforced at
 * the sole chokepoint, outside the agent's control. User-driven (button/url-bar) dispatch is
 * unaffected.
 */
public final class HardStopController {

  private static final Logger log = LoggerFactory.getLogger(HardStopController.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final GlobalHardStop hardStop;

  public HardStopController(GlobalHardStop hardStop) {
    this.hardStop = Objects.requireNonNull(hardStop, "hardStop");
  }

  /** Handles {@code GET /api/agent/hard-stop}. */
  public void handleGet(Context ctx) {
    write(ctx);
  }

  /** Handles {@code POST /api/agent/hard-stop} — body {@code {"engaged": boolean}}. */
  public void handlePost(Context ctx) {
    boolean engaged;
    try {
      var body = MAPPER.readTree(ctx.body());
      engaged = body.path("engaged").asBoolean(false);
    } catch (Exception e) {
      ctx.status(400)
          .contentType("application/json")
          .result("{\"error\":\"expected {\\\"engaged\\\": boolean}\"}");
      return;
    }
    hardStop.set(engaged);
    log.warn("Global Hard Stop {} via /api/agent/hard-stop", engaged ? "ENGAGED" : "released");
    write(ctx);
  }

  private void write(Context ctx) {
    try {
      ctx.contentType("application/json")
          .result(MAPPER.writeValueAsBytes(Map.of("engaged", hardStop.isEngaged())));
    } catch (Exception e) {
      throw new IllegalStateException("hard-stop serialization failed", e);
    }
  }
}
