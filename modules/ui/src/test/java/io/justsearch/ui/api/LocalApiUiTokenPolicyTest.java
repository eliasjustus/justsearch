package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UI Session Token enforcement (prod mode)")
class LocalApiUiTokenPolicyTest {

  private HttpClient client;
  private Javalin app;
  private int port;
  private static final String TEST_TOKEN = "test-session-token-abc123";

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  @Test
  @DisplayName("OPTIONS preflight succeeds without token")
  void optionsPreflightSucceedsWithoutToken() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "tauri://localhost")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type, X-JustSearch-Session")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    // Should echo back the requested headers
    String allowHeaders = resp.headers().firstValue("Access-Control-Allow-Headers").orElse("");
    assertTrue(allowHeaders.contains("X-JustSearch-Session"), "Should allow X-JustSearch-Session header");
  }

  @Test
  @DisplayName("GET requests succeed without token")
  void getSucceedsWithoutToken() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .header("Origin", "tauri://localhost")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"status\":\"ok\""));
  }

  @Test
  @DisplayName("POST without token returns 401 in prod mode")
  void postWithoutTokenReturns401() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{\"ui\":{}}"))
                .header("Content-Type", "application/json")
                .header("Origin", "tauri://localhost")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(401, resp.statusCode());
    assertTrue(resp.body().contains("UI_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("POST with correct token succeeds")
  void postWithCorrectTokenSucceeds() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{\"ui\":{}}"))
                .header("Content-Type", "application/json")
                .header("Origin", "tauri://localhost")
                .header(LocalApiServer.SESSION_TOKEN_HEADER, TEST_TOKEN)
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
  }

  @Test
  @DisplayName("POST with wrong token returns 401")
  void postWithWrongTokenReturns401() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{\"ui\":{}}"))
                .header("Content-Type", "application/json")
                .header("Origin", "tauri://localhost")
                .header(LocalApiServer.SESSION_TOKEN_HEADER, "wrong-token")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(401, resp.statusCode());
    assertTrue(resp.body().contains("UI_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("DELETE without token returns 401 in prod mode")
  void deleteWithoutTokenReturns401() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/indexing/roots"))
                .timeout(Duration.ofSeconds(3))
                .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"path\":\"/test\"}"))
                .header("Content-Type", "application/json")
                .header("Origin", "tauri://localhost")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(401, resp.statusCode());
    assertTrue(resp.body().contains("UI_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("Dev mode (no token configured) allows all requests")
  void devModeAllowsAllRequests() throws Exception {
    // Start with prodMode=false (no token enforcement)
    startTokenTestServer(false, null);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings/v2"))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{\"ui\":{}}"))
                .header("Content-Type", "application/json")
                .header("Origin", "http://localhost:5173")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    // Should succeed even without token
    assertEquals(200, resp.statusCode());
  }

  @Test
  @DisplayName("Token generation produces valid base64url tokens")
  void tokenGenerationProducesValidTokens() {
    String token1 = LocalApiServer.generateSessionToken();
    String token2 = LocalApiServer.generateSessionToken();

    assertNotNull(token1);
    assertNotNull(token2);
    assertFalse(token1.isEmpty());
    assertFalse(token2.isEmpty());
    assertNotEquals(token1, token2, "Each generated token should be unique");
    // Base64URL should not contain + / = (only alphanumeric, -, _)
    assertTrue(token1.matches("[A-Za-z0-9_-]+"), "Token should be base64url encoded");
    // Should be reasonable length (32 bytes encoded ~ 43 chars without padding)
    assertTrue(token1.length() >= 40, "Token should be at least 40 chars (32 bytes base64url)");
  }

  @Test
  @DisplayName("GET /api/mcp/token returns session token in prod mode")
  void getMcpTokenReturnsTokenInProdMode() throws Exception {
    startTokenTestServer(true, TEST_TOKEN);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mcp/token"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains(TEST_TOKEN), "Response should contain the session token");
  }

  @Test
  @DisplayName("GET /api/mcp/token returns empty token in dev mode")
  void getMcpTokenReturnsEmptyInDevMode() throws Exception {
    startTokenTestServer(false, null);

    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/mcp/token"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"token\":\"\""), "Token should be empty string in dev mode");
  }

  /**
   * Starts a minimal test server that mirrors LocalApiServer's token enforcement behavior.
   */
  private void startTokenTestServer(boolean prodMode, String sessionToken) {
    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); });

    // Register no-op exception handler to preserve ctx.json() body (mirrors LocalApiServer)
    app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> {
      // Body and status already set by handler; do nothing.
    });

    // Mirror CORS behavior
    app.before(ctx -> {
      String origin = ApiSecurityFilters.resolveAllowedOrigin(ctx.header("Origin"), prodMode);
      if (origin == null) {
        return;
      }
      ctx.header("Access-Control-Allow-Origin", origin);
      ctx.header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
      ctx.res().addHeader("Vary", "Origin");
    });

    app.options("/*", ctx -> {
      String originHeader = ctx.header("Origin");
      String origin = ApiSecurityFilters.resolveAllowedOrigin(originHeader, prodMode);
      if (origin == null) {
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

    // Mirror token enforcement behavior
    if (prodMode && sessionToken != null && !sessionToken.isBlank()) {
      app.before(ctx -> {
        String method = ctx.method().name().toUpperCase(java.util.Locale.ROOT);
        // Skip OPTIONS and GET
        if ("OPTIONS".equals(method) || "GET".equals(method)) {
          return;
        }
        // Require token for POST/PUT/DELETE
        if ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)) {
          String providedToken = ctx.header(LocalApiServer.SESSION_TOKEN_HEADER);
          if (providedToken == null || !sessionToken.equals(providedToken)) {
            ctx.status(401);
            ctx.json(Map.of(
                "error", "Missing or invalid session token",
                "errorCode", "UI_TOKEN_REQUIRED"));
            throw new io.javalin.http.HttpResponseException(401, "Unauthorized");
          }
        }
      });
    }

    // Test endpoints
    app.get("/api/status", ctx -> ctx.json(Map.of("status", "ok")));
    app.post("/api/settings/v2", ctx -> ctx.json(Map.of("success", true)));
    app.delete("/api/indexing/roots", ctx -> ctx.json(Map.of("success", true)));
    String finalToken = sessionToken;
    app.get(
        "/api/mcp/token",
        ctx -> ctx.json(Map.of("token", finalToken != null ? finalToken : "")));

    app.start("127.0.0.1", 0);
    port = app.port();
  }
}
