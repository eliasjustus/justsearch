/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.app.services.HeadAssembly;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 583 Stage 4: the request-filter security plumbing, lifted out of {@link LocalApiServer}
 * (§B.7 remedy).
 *
 * <p>This is the loopback-CORS / session-token / capability-gate / slow-request-dump policy —
 * ~300 LOC of {@code app.before(...)} filter wiring plus the rate-limited deny/slow-dump bookkeeping
 * it carries. It keeps the loopback bind policy (Hard Invariant #2) single-authority: {@link
 * #resolveAllowedOrigin} is the one CORS-origin decision and is still exercised verbatim by {@code
 * LocalApiCorsPolicyTest} / {@code LocalApiUiTokenPolicyTest}. Behaviour is identical — the filter
 * bodies moved verbatim; LocalApiServer constructs one instance and calls {@link #install} from
 * {@code buildAndStartApp} (and {@link #maybeCaptureSlowRequestDump} from the shared after-hook).
 */
final class ApiSecurityFilters {
  private static final Logger log = LoggerFactory.getLogger(ApiSecurityFilters.class);

  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
  // Newer Tauri/WebView2 versions use an http(s) origin for the bundled app assets.
  // This is not a general DNS-backed host; treat it as a special-case desktop origin.
  private static final String TAURI_WEBVIEW_HOST = "tauri.localhost";
  /** Methods that require the session token in prod mode. */
  private static final Set<String> TOKEN_REQUIRED_METHODS = Set.of("POST", "PUT", "DELETE");
  private static final long SLOW_REQUEST_THRESHOLD_MS = 3000;
  private static final long SLOW_DUMP_RATE_LIMIT_MS = 30_000;

  private final boolean prodMode;
  private final String sessionToken;
  private final EventBuffer eventBuffer;
  private final ExecutorService slowRequestExecutor;
  private final HeadAssembly headAssembly;

  // Rate-limit bookkeeping for deny / slow-dump logging.
  private final AtomicLong lastCorsDenyUiReadyAtMs = new AtomicLong(0);
  private final AtomicReference<String> lastCorsDenyUiReadyOrigin = new AtomicReference<>("");
  private final AtomicLong lastTokenDenyAtMs = new AtomicLong(0);
  private final AtomicLong lastHostDenyAtMs = new AtomicLong(0);
  private final AtomicLong lastSlowDumpAtMs = new AtomicLong(0);

  ApiSecurityFilters(
      boolean prodMode,
      String sessionToken,
      EventBuffer eventBuffer,
      ExecutorService slowRequestExecutor,
      HeadAssembly headAssembly) {
    this.prodMode = prodMode;
    this.sessionToken = sessionToken;
    this.eventBuffer = eventBuffer;
    this.slowRequestExecutor = slowRequestExecutor;
    this.headAssembly = headAssembly;
  }

  /** Installs the Host-allowlist, CORS, session-token, and capability-gate before-filters on the app. */
  void install(Javalin app) {
    setupHostValidation(app);
    setupCors(app, prodMode);
    setupSessionTokenEnforcement(app);
    setupCapabilityGates(app);
  }

  /**
   * Tempdoc 633 §1a: Host-header allowlist — the DNS-rebinding defense. The loopback bind (Hard
   * Invariant #2) and the CORS Origin allowlist are necessary but not sufficient: after a DNS-rebinding
   * attack a malicious page becomes *same-origin* with the loopback service, so CORS no longer applies,
   * and the token-exempt GET reads (e.g. {@code /api/knowledge/search}) would still execute and return
   * data. The canonical defense (MCP security best-practices "Local MCP Server Compromise"; Ollama
   * CVE-2024-28224) is to reject any request whose {@code Host} header is not a loopback host — even when
   * rebinding points the browser at 127.0.0.1, the server still sees the attacker's domain in {@code
   * Host} and returns 403. Applies in dev and prod alike; legitimate webview/dev-server requests target
   * the loopback interface and carry {@code Host: 127.0.0.1:<port>} (or {@code localhost:<port>}).
   */
  private void setupHostValidation(Javalin app) {
    app.before(
        ctx -> {
          String hostHeader = ctx.header("Host");
          if (!isAllowedHost(hostHeader)) {
            maybeRecordHostDeny(ctx, hostHeader);
            ctx.status(403);
            ctx.json(
                Map.of(
                    "error", "Request Host is not a loopback host",
                    "errorCode", "NON_LOOPBACK_HOST"));
            throw new io.javalin.http.HttpResponseException(403, "Forbidden");
          }
        });
  }

  private void setupCors(Javalin app, boolean prod) {
    app.before(ctx -> {
      String origin = resolveAllowedOrigin(ctx.header("Origin"), prod);
      if (origin == null) {
        return;
      }
      ctx.header("Access-Control-Allow-Origin", origin);
      ctx.header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
      ctx.res().addHeader("Vary", "Origin");
    });

    app.options("/*", ctx -> {
      String originHeader = ctx.header("Origin");
      String origin = resolveAllowedOrigin(originHeader, prod);
      if (origin == null) {
        maybeRecordCorsDenyUiReadyPreflight(ctx, originHeader);
        ctx.status(403);
        return;
      }

      String requestHeaders = ctx.header("Access-Control-Request-Headers");
      ctx.header(
          "Access-Control-Allow-Headers",
          requestHeaders == null || requestHeaders.isBlank() ? "Content-Type" : requestHeaders);
      ctx.header("Access-Control-Max-Age", "3600");
      ctx.status(200);
    });
  }

  /**
   * Sets up session token enforcement for non-GET requests in prod mode.
   *
   * <p>This is a security hardening measure to ensure that only the legitimate desktop UI
   * can make mutating API calls. The token is generated at startup and delivered to the UI
   * via the Tauri bridge.
   *
   * <p>Enforcement rules:
   * <ul>
   *   <li>OPTIONS (preflight) - always allowed (needed for CORS)
   *   <li>GET - always allowed (read-only operations)
   *   <li>POST/PUT/DELETE - require valid token in prod mode
   * </ul>
   */
  private void setupSessionTokenEnforcement(Javalin app) {
    // Only enforce in prod mode with a valid token
    if (!prodMode || sessionToken == null || sessionToken.isBlank()) {
      if (prodMode && (sessionToken == null || sessionToken.isBlank())) {
        log.warn("Prod mode is enabled but no session token was provided - token enforcement is DISABLED");
        eventBuffer.warn("LocalApiServer", "TOKEN_ENFORCEMENT_DISABLED", Map.of(
            "reason", "no_token_provided",
            "prodMode", prodMode));
      }
      return;
    }

    log.info("Session token enforcement enabled for non-GET requests");
    eventBuffer.info("LocalApiServer", "Session token enforcement enabled");

    app.before(ctx -> {
      String method = ctx.method().name().toUpperCase(Locale.ROOT);

      // Always allow OPTIONS (CORS preflight) and GET (read-only)
      if ("OPTIONS".equals(method) || "GET".equals(method)) {
        return;
      }

      // Check if method requires token
      if (!TOKEN_REQUIRED_METHODS.contains(method)) {
        return;
      }

      // Validate token
      String providedToken = ctx.header(LocalApiServer.SESSION_TOKEN_HEADER);
      if (providedToken == null || !sessionToken.equals(providedToken)) {
        maybeRecordTokenDeny(ctx, providedToken);
        ctx.status(401);
        ctx.json(Map.of(
            "error", "Missing or invalid session token",
            "errorCode", "UI_TOKEN_REQUIRED"));
        // Halt further processing (Javalin 5+ style)
        throw new io.javalin.http.HttpResponseException(401, "Unauthorized");
      }
    });
  }

  /**
   * Tempdoc 502 §4.2.1: path-aware before-handlers that check capability health and
   * return structured 503 responses when a required capability is unavailable. This
   * replaces the scattered inline checks (knowledgeServer.state() != READY, sentinel
   * catch patterns) with one uniform mechanism.
   */
  private void setupCapabilityGates(Javalin app) {
    if (headAssembly == null) return;

    var workerCap = headAssembly.capabilities().worker();
    var inferenceCap = headAssembly.capabilities().inference();

    // Tempdoc 583 §D.3a: the {path → required-capability} rules live in RouteCapabilityPolicy (the
    // single authority the route manifest also reads, so enforced and advertised can't drift).
    // Behaviour is identical to the prior hard-coded gates: get-exempt rules (knowledge/indexing)
    // skip GET; each rule's caps are checked in 503-precedence order.
    for (RouteCapabilityPolicy.Rule rule : RouteCapabilityPolicy.RULES) {
      app.before(rule.pathPattern(), ctx -> {
        if (rule.getExempt() && "GET".equals(ctx.method().name())) {
          return;
        }
        for (RouteCapabilityPolicy.Capability required : rule.required()) {
          io.justsearch.app.api.lifecycle.Capability cap =
              required == RouteCapabilityPolicy.Capability.WORKER ? workerCap : inferenceCap;
          if (!cap.available()) {
            ctx.status(503);
            ctx.json(Map.of(
                "error", required.errorLabel,
                "unavailable", cap.name(),
                "health", cap.health().name(),
                "reason", cap.pendingReason() != null ? cap.pendingReason() : ""));
            throw new io.javalin.http.HttpResponseException(503, required.haltMessage);
          }
        }
      });
    }
  }

  /**
   * Rate-limited logging for token denial events (avoid log spam from repeated failures).
   */
  private void maybeRecordTokenDeny(Context ctx, String providedToken) {
    long now = System.currentTimeMillis();
    long lastAt = lastTokenDenyAtMs.get();

    // Rate limit: max once per 10 seconds
    if ((now - lastAt) < 10_000) {
      return;
    }

    if (lastTokenDenyAtMs.compareAndSet(lastAt, now)) {
      String path = null;
      try {
        path = ctx.path();
      } catch (Exception ignored) {
        // best-effort
      }
      String tokenPresent = providedToken == null ? "absent" : "present_but_invalid";
      eventBuffer.warn("LocalApiServer", "TOKEN_DENY", Map.of(
          "path", path == null ? "<unknown>" : path,
          "method", ctx.method().name(),
          "tokenState", tokenPresent));
      log.debug("Session token denied: path={}, method={}, tokenState={}",
          path, ctx.method().name(), tokenPresent);
    }
  }

  /** Rate-limited logging for Host-allowlist denial events (DNS-rebinding guard). */
  private void maybeRecordHostDeny(Context ctx, String hostHeader) {
    long now = System.currentTimeMillis();
    long lastAt = lastHostDenyAtMs.get();

    // Rate limit: max once per 10 seconds (a rebinding probe could otherwise flood).
    if ((now - lastAt) < 10_000) {
      return;
    }

    if (lastHostDenyAtMs.compareAndSet(lastAt, now)) {
      String path = null;
      try {
        path = ctx.path();
      } catch (Exception ignored) {
        // best-effort
      }
      eventBuffer.warn("LocalApiServer", "HOST_DENY", Map.of(
          "path", path == null ? "<unknown>" : path,
          "method", ctx.method().name(),
          "host", hostHeader == null ? "<absent>" : hostHeader));
      log.debug("Non-loopback Host denied: path={}, method={}, host={}",
          path, ctx.method().name(), hostHeader);
    }
  }

  private void maybeRecordCorsDenyUiReadyPreflight(Context ctx, String originHeader) {
    // Only record for the UI-ready handshake path to avoid spamming /api/status probes.
    String path = null;
    try {
      path = ctx.path();
    } catch (Exception ignored) {
      // best-effort
    }
    if (!"/api/ui/ready".equals(path)) {
      return;
    }

    String normalized = originHeader == null ? "<absent>" : originHeader;
    long now = System.currentTimeMillis();
    long lastAt = lastCorsDenyUiReadyAtMs.get();
    String lastOrigin = lastCorsDenyUiReadyOrigin.get();

    // Rate limit: avoid emitting the same warning repeatedly during a flapping UI.
    if ((now - lastAt) < 10_000 && normalized.equals(lastOrigin)) {
      return;
    }

    lastCorsDenyUiReadyOrigin.set(normalized);
    lastCorsDenyUiReadyAtMs.set(now);
    eventBuffer.warn(
        "LocalApiServer",
        "CORS_DENY_UI_READY_PREFLIGHT",
        Map.of("originHeader", normalized));
  }

  /**
   * Captures a thread dump if the request exceeded the slow-request threshold.
   *
   * <p>Rate-limited to max 1 dump per 30 seconds to avoid flooding during degradation.
   */
  void maybeCaptureSlowRequestDump(Context ctx) {
    Long startNs = ctx.attribute("__request_start_ns__");
    if (startNs == null) {
      return;
    }

    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
    if (durationMs < SLOW_REQUEST_THRESHOLD_MS) {
      return;
    }

    // Rate limit: max 1 dump per 30 seconds
    long now = System.currentTimeMillis();
    long lastAt = lastSlowDumpAtMs.get();
    if ((now - lastAt) < SLOW_DUMP_RATE_LIMIT_MS) {
      return;
    }
    if (!lastSlowDumpAtMs.compareAndSet(lastAt, now)) {
      return;
    }

    // Capture async to avoid adding latency to the response
    String route = ctx.path();
    String method = ctx.method().name();
    int status = ctx.res() != null ? ctx.res().getStatus() : 0;
    // Tempdoc 518 Wave A defect Fix-9: extract the active trace ID for cross-correlation
    // with the trace stream. When tracing is off the span context is invalid → traceId is
    // null and the dumper omits the field. Composes with the Wave A.3 X-Trace-Id response
    // header so a slow-request bug report can be matched to its full span tree.
    String traceId = null;
    Object spanAttr = ctx.attribute("__otel_span__");
    if (spanAttr instanceof io.opentelemetry.api.trace.Span span
        && span.getSpanContext().isValid()) {
      traceId = span.getSpanContext().getTraceId();
    }
    final String capturedTraceId = traceId;
    slowRequestExecutor.execute(
        () ->
            SlowRequestDumper.captureDump(
                route, method, status, durationMs, SLOW_REQUEST_THRESHOLD_MS, capturedTraceId));
  }

  /**
   * Returns true iff the {@code Host} header names a loopback host (port ignored). The DNS-rebinding
   * guard ({@link #setupHostValidation}). A missing/blank Host is rejected — HTTP/1.1 mandates Host and
   * its absence is anomalous. Package-private for {@code LocalApiHostValidationTest}.
   */
  static boolean isAllowedHost(String hostHeader) {
    if (hostHeader == null || hostHeader.isBlank()) {
      return false;
    }
    String host = hostHeader.trim();
    if (host.startsWith("[")) {
      // IPv6 literal with optional port: "[::1]:8080" -> "::1"
      int close = host.indexOf(']');
      if (close < 0) {
        return false;
      }
      host = host.substring(1, close);
    } else {
      // Strip ":port" for IPv4 / hostnames (a bare IPv4/hostname has no colon).
      int colon = host.indexOf(':');
      if (colon >= 0) {
        host = host.substring(0, colon);
      }
    }
    return LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
  }

  // Package-private for targeted regression tests (CORS allowlist / loopback safety).
  static String resolveAllowedOrigin(String originHeader, boolean prod) {
    if (originHeader == null || originHeader.isBlank()) {
      return null;
    }
    if ("null".equalsIgnoreCase(originHeader.trim())) {
      return null;
    }

    try {
      URI origin = URI.create(originHeader);
      String scheme = origin.getScheme();
      String host = origin.getHost();
      if (scheme == null || host == null) {
        return null;
      }

      String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
      String normalizedHost = host.toLowerCase(Locale.ROOT);

      // Prod mode: only allow desktop origins (not general browser localhost origins).
      // - Tauri v1:  tauri://localhost
      // - Tauri v2+: http(s)://tauri.localhost (varies by WebView2 / runtime)
      if (prod) {
        if ("tauri".equals(normalizedScheme) && LOOPBACK_HOSTS.contains(normalizedHost)) {
          return originHeader;
        }
        if (("https".equals(normalizedScheme) || "http".equals(normalizedScheme))
            && TAURI_WEBVIEW_HOST.equals(normalizedHost)) {
          return originHeader;
        }
        return null;
      }

      // Dev mode: allow loopback and the Tauri https host for local development and UI smoke tests.
      if (!LOOPBACK_HOSTS.contains(normalizedHost) && !TAURI_WEBVIEW_HOST.equals(normalizedHost)) {
        return null;
      }
      Set<String> allowedSchemes = Set.of("http", "https", "tauri");
      return allowedSchemes.contains(normalizedScheme) ? originHeader : null;
    } catch (Exception e) {
      return null;
    }
  }
}
