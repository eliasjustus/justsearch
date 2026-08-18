/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Tempdoc 834 §5.1/§15.2 — the path-scoped token requirement on the run family.
 *
 * <p><strong>Why this test exists at all.</strong> {@code GET /api/chat/runs/live} dispenses the
 * runIds every other run read is keyed by, over a journal carrying prompts, answers, retrieved
 * passage text and tool arguments. The session-token filter exempts GET by default, so without the
 * path-scoped change this endpoint would ship unauthenticated to any local process — loopback-only
 * (Hard Invariant #2) is not a trust boundary here, which is precisely why the token exists.
 *
 * <p><strong>Why it drives the REAL filter.</strong> The adverse precondition is the whole point: a
 * test that only proves "with a token it works" is green in exactly the world where the guard was
 * never installed. So this exercises {@link ApiSecurityFilters#install} itself (the precedent is
 * {@code McpOriginValidationTest}), not a hand-copied mirror of its logic — a mirror can pass while
 * production ships open.
 */
@DisplayName("Live-run enumeration — path-scoped session token (834 S4)")
class LiveRunsAuthTest {

  private static final String TEST_TOKEN = "test-session-token-abc123";

  private HttpClient client;
  private ExecutorService executor;
  private Javalin app;
  private int port;

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    executor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  // ── The decision rule itself ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("the rule: the run family requires the token for GET; the rest of the API does not")
  void ruleMatrix() {
    assertTrue(
        ApiSecurityFilters.requiresSessionToken("GET", RunRoutes.LIVE_PATH),
        "the enumeration hands out runIds — a GET exemption here ships them unauthenticated");
    assertTrue(
        ApiSecurityFilters.requiresSessionToken("GET", RunRoutes.PATH_PREFIX + "/anything-future"),
        "the guard is a PREFIX so a future read route under the family inherits it");
    assertTrue(ApiSecurityFilters.requiresSessionToken("POST", RunRoutes.PATH_PREFIX));

    assertFalse(
        ApiSecurityFilters.requiresSessionToken("GET", "/api/status"),
        "an ordinary read-only GET keeps the original bargain");
    assertFalse(
        ApiSecurityFilters.requiresSessionToken("OPTIONS", RunRoutes.LIVE_PATH),
        "a CORS preflight cannot carry the header — requiring it there breaks every call it "
            + "precedes");
    assertTrue(ApiSecurityFilters.requiresSessionToken("POST", "/api/settings/v2"));
  }

  // ── The rule, as the running server applies it ───────────────────────────────────────────────

  @Test
  @DisplayName("ADVERSE PRECONDITION: GET /api/chat/runs/live without the token is rejected")
  void enumerationWithoutTokenIsRejected() throws Exception {
    startWithRealFilters();

    HttpResponse<String> resp = get(RunRoutes.LIVE_PATH, null);

    assertEquals(401, resp.statusCode(), "an untokened enumeration must not be served");
    assertTrue(resp.body().contains("UI_TOKEN_REQUIRED"));
    assertFalse(
        resp.body().contains("run-should-never-be-disclosed"),
        "the 401 body must not leak the enumeration it refused to serve");
  }

  @Test
  @DisplayName("GET /api/chat/runs/live with the token is served")
  void enumerationWithTokenIsServed() throws Exception {
    startWithRealFilters();

    HttpResponse<String> resp = get(RunRoutes.LIVE_PATH, TEST_TOKEN);

    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("run-should-never-be-disclosed"));
  }

  @Test
  @DisplayName("a wrong token is rejected exactly like a missing one")
  void enumerationWithWrongTokenIsRejected() throws Exception {
    startWithRealFilters();

    HttpResponse<String> resp = get(RunRoutes.LIVE_PATH, "not-the-token");

    assertEquals(401, resp.statusCode());
    assertTrue(resp.body().contains("UI_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("the path scope is narrow: an ordinary GET still needs no token")
  void ordinaryGetIsUnaffected() throws Exception {
    startWithRealFilters();

    HttpResponse<String> resp = get("/api/status", null);

    assertEquals(200, resp.statusCode(), "the change must not tighten the whole read surface");
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .header("Origin", "tauri://localhost");
    if (token != null) {
      req = req.header(LocalApiServer.SESSION_TOKEN_HEADER, token);
    }
    return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Prod mode with a token, and the PRODUCTION filter chain — not a re-statement of it. */
  private void startWithRealFilters() {
    app =
        Javalin.create(
            cfg -> {
              cfg.showJavalinBanner = false;
              cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
            });
    app.exception(
        io.javalin.http.HttpResponseException.class,
        (e, ctx) -> {
          // Body and status already set by the filter; mirrors LocalApiServer.
        });

    new ApiSecurityFilters(true, TEST_TOKEN, new EventBuffer(), executor, null).install(app);

    app.get(RunRoutes.LIVE_PATH, ctx -> ctx.json(Map.of("runs", java.util.List.of(
        Map.of("runId", "run-should-never-be-disclosed")))));
    app.get("/api/status", ctx -> ctx.json(Map.of("status", "ok")));

    app.start("127.0.0.1", 0);
    port = app.port();
  }
}
