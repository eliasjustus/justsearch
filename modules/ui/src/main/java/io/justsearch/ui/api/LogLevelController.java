/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.telemetry.Telemetry;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.LoggerFactory;

/**
 * Handles runtime log level inspection and modification.
 *
 * <p>Changes are immediate but NOT persisted — they reset on restart.
 */
public final class LogLevelController {
  private static final org.slf4j.Logger log = LoggerFactory.getLogger(LogLevelController.class);
  private static final Set<String> VALID_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");
  private final Telemetry telemetry;

  public LogLevelController(Telemetry telemetry) {
    this.telemetry = telemetry;
  }

  /** GET /api/debug/logging - returns all loggers with explicit levels set. */
  public void handleGetLogLevels(Context ctx) {
    if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
      ctx.status(501).json(ApiErrorHandler.toResponse(ApiErrorCode.NOT_SUPPORTED, "Runtime log control requires Logback backend", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    Map<String, String> levels = new LinkedHashMap<>();
    for (Logger logger : context.getLoggerList()) {
      Level level = logger.getLevel();
      if (level != null) {
        levels.put(logger.getName(), level.toString());
      }
    }

    ctx.json(Map.of("loggers", levels));
  }

  /** POST /api/debug/logging - sets level for a specific logger. */
  @SuppressWarnings("unchecked")
  public void handleSetLogLevel(Context ctx) {
    Map<String, String> body;
    try {
      body = ctx.bodyAsClass(Map.class);
    } catch (Exception e) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_JSON, "Invalid JSON body", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    String loggerName = body.get("logger");
    String levelStr = body.get("level");

    if (loggerName == null || loggerName.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Missing 'logger' field", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    if (levelStr == null || levelStr.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Missing 'level' field", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    String normalizedLevel = levelStr.toUpperCase(Locale.ROOT);
    if (!VALID_LEVELS.contains(normalizedLevel)) {
      Map<String, Object> levelErr = ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Invalid log level: " + levelStr, telemetry, ApiErrorHandler.routeOf(ctx));
      levelErr.put("valid", "TRACE, DEBUG, INFO, WARN, ERROR, OFF");
      ctx.status(400).json(levelErr);
      return;
    }
    Level level = Level.valueOf(normalizedLevel);

    if (!(LoggerFactory.getLogger(loggerName) instanceof Logger logger)) {
      ctx.status(501).json(ApiErrorHandler.toResponse(ApiErrorCode.NOT_SUPPORTED, "Runtime log control requires Logback backend", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    Level previousLevel = logger.getLevel();
    logger.setLevel(level);

    log.info(
        "Log level changed: {} {} -> {}",
        loggerName,
        previousLevel != null ? previousLevel : "(inherited)",
        level);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("success", true);
    response.put("logger", loggerName);
    response.put("previousLevel", previousLevel != null ? previousLevel.toString() : null);
    response.put("level", level.toString());
    ctx.json(response);
  }
}
