package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 633 §1a — the Host-header allowlist (DNS-rebinding defense). The loopback bind + CORS Origin
 * allowlist do not stop a DNS-rebound page from executing token-exempt GET reads (post-rebind the page
 * is same-origin, so CORS no longer applies); only a {@code Host} allowlist does. These tests pin both
 * the pure predicate ({@link ApiSecurityFilters#isAllowedHost}) and the live before-filter behaviour.
 */
@DisplayName("Local API Host allowlist & DNS-rebinding safety")
class LocalApiHostValidationTest {

  private Javalin app;
  private int port;

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  // ---- pure predicate ----

  @Test
  @DisplayName("isAllowedHost: loopback hosts (with/without port, IPv6) are allowed")
  void allowsLoopbackHosts() {
    assertTrue(ApiSecurityFilters.isAllowedHost("127.0.0.1:8080"));
    assertTrue(ApiSecurityFilters.isAllowedHost("127.0.0.1"));
    assertTrue(ApiSecurityFilters.isAllowedHost("localhost:5173"));
    assertTrue(ApiSecurityFilters.isAllowedHost("LOCALHOST:5173"));
    assertTrue(ApiSecurityFilters.isAllowedHost("[::1]:8080"));
  }

  @Test
  @DisplayName("isAllowedHost: non-loopback hosts and missing Host are rejected")
  void rejectsNonLoopbackHosts() {
    assertFalse(ApiSecurityFilters.isAllowedHost("evil.com:8080"));
    assertFalse(ApiSecurityFilters.isAllowedHost("evil.com"));
    assertFalse(ApiSecurityFilters.isAllowedHost("attacker.localhost.evil.com:8080"));
    assertFalse(ApiSecurityFilters.isAllowedHost("169.254.169.254"));
    assertFalse(ApiSecurityFilters.isAllowedHost(null));
    assertFalse(ApiSecurityFilters.isAllowedHost(""));
    assertFalse(ApiSecurityFilters.isAllowedHost("   "));
  }

  // ---- live before-filter (mirrors the production install() guard) ----
  // A raw socket is used so we can set an arbitrary Host header deterministically — the JDK HttpClient
  // restricts overriding Host, and its `allowRestrictedHeaders` property is read too early to toggle here.

  @Test
  @DisplayName("Live: a token-exempt GET read with a foreign Host is rejected with 403")
  void liveGetReadWithForeignHostIsForbidden() throws Exception {
    startHostGuardedServer();
    RawResponse response = rawGet("evil.com", "/api/knowledge/search");
    assertEquals(403, response.status(), "A foreign Host must be rejected even on a token-exempt GET read");
    ContractSchemaAssertions.assertConforms(
        "Host rejection status 403", "api-error-response.v1.json", response.body());
  }

  @Test
  @DisplayName("Live: the same GET read with the real loopback Host succeeds")
  void liveLoopbackHostSucceeds() throws Exception {
    startHostGuardedServer();
    RawResponse response = rawGet("127.0.0.1:" + port, "/api/knowledge/search");
    assertEquals(200, response.status(), "Legitimate loopback Host must pass the guard");
  }

  /** Sends a raw HTTP/1.1 GET with an explicit Host header and returns its status and body. */
  private RawResponse rawGet(String hostHeader, String path) throws Exception {
    try (java.net.Socket socket = new java.net.Socket()) {
      socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
      socket.setSoTimeout(3000);
      String request =
          "GET " + path + " HTTP/1.1\r\n"
              + "Host: " + hostHeader + "\r\n"
              + "Connection: close\r\n"
              + "\r\n";
      socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      String wire =
          new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      int separator = wire.indexOf("\r\n\r\n");
      assertTrue(separator >= 0, "Expected HTTP headers and a response body: " + wire);
      String headers = wire.substring(0, separator);
      String statusLine = headers.lines().findFirst().orElse(null); // e.g. "HTTP/1.1 403 Forbidden"
      assertNotNull(statusLine, "Expected an HTTP status line");
      String[] parts = statusLine.split(" ");
      assertTrue(parts.length >= 2, "Malformed status line: " + statusLine);
      return new RawResponse(Integer.parseInt(parts[1]), wire.substring(separator + 4));
    }
  }

  private record RawResponse(int status, String body) {}

  /**
   * Minimal hermetic server that installs ONLY the Host-validation guard (the same predicate the
   * production {@code install()} uses), plus a representative token-exempt GET read endpoint.
   */
  private void startHostGuardedServer() {
    app = Javalin.create(cfg -> {
      cfg.showJavalinBanner = false;
      cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    // Production preserves a body already written by a filter while propagating its status.
    app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> ctx.status(e.getStatus()));

    app.before(
        ctx -> {
          if (!ApiSecurityFilters.isAllowedHost(ctx.header("Host"))) {
            ctx.status(403);
            ctx.json(Map.of("error", "Request Host is not a loopback host", "errorCode", "NON_LOOPBACK_HOST"));
            throw new io.javalin.http.HttpResponseException(403, "Forbidden");
          }
        });

    app.get("/api/knowledge/search", ctx -> ctx.json(Map.of("status", "ok", "results", java.util.List.of())));

    app.start("127.0.0.1", 0);
    port = app.port();
  }
}
