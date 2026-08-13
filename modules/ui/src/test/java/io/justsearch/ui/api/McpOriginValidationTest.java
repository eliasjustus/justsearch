package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MCP Streamable-HTTP Origin validation — the spec's transport-security MUST: "Servers MUST validate
 * the {@code Origin} header on all incoming connections to prevent DNS rebinding attacks... If the
 * {@code Origin} header is present and invalid, servers MUST respond with HTTP 403 Forbidden"
 * (Transports §Streamable HTTP, Security Warning).
 *
 * <p>The live half runs the REAL {@link ApiSecurityFilters#install} wiring — not a re-declared
 * mirror — so a guard registered on the wrong path, or dropped from {@code install()}, fails here.
 */
@DisplayName("MCP endpoint Origin validation (Streamable HTTP transport security)")
class McpOriginValidationTest {

  private HttpClient client;
  private Javalin app;
  private ExecutorService executor;
  private int port;

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
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  // ---- pure predicate ----

  @Test
  @DisplayName("Absent Origin is allowed — native MCP hosts are not browsers and send none")
  void allowsAbsentOrigin() {
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin(null));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin(""));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("   "));
  }

  @Test
  @DisplayName("Loopback origins are allowed: 127.0.0.1 / localhost / ::1, any port, http or https")
  void allowsLoopbackOrigins() {
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://127.0.0.1"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://127.0.0.1:8080"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://localhost"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://localhost:5173"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("https://localhost:5173"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("HTTP://LOCALHOST:5173"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://[::1]:8080"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("  http://127.0.0.1:8080  "));
  }

  @Test
  @DisplayName("Desktop-shell origins are allowed (tauri://localhost, https://tauri.localhost)")
  void allowsDesktopShellOrigins() {
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("tauri://localhost"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("https://tauri.localhost"));
    assertTrue(ApiSecurityFilters.isAllowedMcpOrigin("http://tauri.localhost"));
  }

  @Test
  @DisplayName("Foreign web origins are rejected")
  void rejectsForeignOrigins() {
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://evil.example.com"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("https://evil.example.com:8080"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://169.254.169.254"));
  }

  @Test
  @DisplayName("The opaque `null` origin is rejected — it carries no host that can be verified")
  void rejectsNullOrigin() {
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("null"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("NULL"));
  }

  /**
   * The property that makes the check sound: the Origin is parsed as a URI and its HOST COMPONENT is
   * compared for equality. A substring/prefix/suffix match would admit every host below — each
   * contains a loopback name but resolves to a foreign authority.
   */
  @Test
  @DisplayName("Loopback LOOKALIKE origins are rejected (host equality, never substring matching)")
  void rejectsLoopbackLookalikes() {
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://127.0.0.1.evil.com"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://localhost.evil.com"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://evil-localhost.com"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://tauri.localhost.evil.com"));
    // userinfo, not host
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://127.0.0.1@evil.com"));
    // path / fragment, not host
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://evil.com/127.0.0.1"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("http://evil.com#http://localhost"));
  }

  @Test
  @DisplayName("Unparseable values and non-web schemes are rejected")
  void rejectsMalformedAndNonWebSchemes() {
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("localhost"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("not a uri"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("file:///C:/tmp"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("javascript:alert(1)"));
    assertFalse(ApiSecurityFilters.isAllowedMcpOrigin("tauri://evil.com"));
  }

  // ---- live filter, installed exactly as production installs it ----

  @Test
  @DisplayName("Live: POST /mcp with no Origin is served (native MCP host)")
  void livePostWithoutOriginIsServed() throws Exception {
    startServerWithProductionFilters();
    HttpResponse<String> resp = post(null);
    assertEquals(200, resp.statusCode());
  }

  @Test
  @DisplayName("Live: POST /mcp from a loopback Origin is served")
  void livePostFromLoopbackOriginIsServed() throws Exception {
    startServerWithProductionFilters();
    assertEquals(200, post("http://127.0.0.1:5173").statusCode());
    assertEquals(200, post("http://localhost").statusCode());
  }

  @Test
  @DisplayName("Live: POST /mcp from a foreign Origin is 403 with a JSON-RPC error body")
  void livePostFromForeignOriginIsForbidden() throws Exception {
    startServerWithProductionFilters();
    HttpResponse<String> resp = post("http://evil.example.com");
    assertEquals(403, resp.statusCode());
    assertTrue(resp.body().contains("\"jsonrpc\":\"2.0\""), "Body should be JSON-RPC shaped: " + resp.body());
    assertTrue(resp.body().contains("\"id\":null"), "JSON-RPC error must carry no request id: " + resp.body());
    assertTrue(resp.body().contains("MCP_ORIGIN_FORBIDDEN"), "Body should name the error code: " + resp.body());
    assertFalse(resp.body().contains("\"handled\""), "The handler must not have run");
  }

  @Test
  @DisplayName("Live: POST /mcp from a loopback-lookalike Origin is 403")
  void livePostFromLookalikeOriginIsForbidden() throws Exception {
    startServerWithProductionFilters();
    assertEquals(403, post("http://127.0.0.1.evil.com").statusCode());
  }

  @Test
  @DisplayName("Live: DELETE /mcp is validated too — the check covers all methods on the endpoint")
  void liveDeleteIsValidated() throws Exception {
    startServerWithProductionFilters();
    assertEquals(403, delete("http://evil.example.com").statusCode());
    assertEquals(204, delete(null).statusCode());
    assertEquals(204, delete("http://127.0.0.1:5173").statusCode());
  }

  @Test
  @DisplayName("Live: the MCP guard is endpoint-scoped — other routes keep browser-enforced CORS")
  void liveGuardDoesNotLeakToOtherRoutes() throws Exception {
    startServerWithProductionFilters();
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/status"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .header("Origin", "http://evil.example.com")
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), "Only /mcp carries the MCP transport-security check");
    assertTrue(
        resp.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
        "A foreign origin still gets no CORS grant");
  }

  private HttpResponse<String> post(String origin) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
    if (origin != null) {
      builder.header("Origin", origin);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> delete(String origin) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(3))
            .DELETE();
    if (origin != null) {
      builder.header("Origin", origin);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Starts a hermetic app whose filters are installed by the production {@link
   * ApiSecurityFilters#install} (dev mode: no session token, no lease service, no capability gates),
   * plus stand-ins for the two methods {@code LocalApiServer} registers at {@code /mcp}.
   */
  private void startServerWithProductionFilters() {
    app =
        Javalin.create(
            cfg -> {
              cfg.showJavalinBanner = false;
              cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
            });

    executor = Executors.newSingleThreadExecutor();
    new ApiSecurityFilters(false, null, new EventBuffer(), executor, null).install(app);

    app.post("/mcp", ctx -> ctx.json(Map.of("handled", true)));
    app.delete("/mcp", ctx -> ctx.status(204));
    app.get("/api/status", ctx -> ctx.json(Map.of("status", "ok")));

    app.start("127.0.0.1", 0);
    port = app.port();
  }
}
