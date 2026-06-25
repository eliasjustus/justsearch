package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live-stack verification for tempdoc 550 Slice F1 (Outcome face): a forwarded Navigation
 * leaves an attributed record on {@code GET /api/navigation-history}.
 *
 * <p>Runs against a real {@link IsolatedBackendFixture}-spawned {@code HeadlessApp} child
 * JVM (lite mode — no AI/models needed for navigation), so the FULL bootstrap is exercised
 * — including the {@code navigationHistoryStore} wiring through {@code OperationSubstrateInit
 * → SubstrateGraph → LocalApiServer} that the test-only {@code LocalApiServer.builder()}
 * path does NOT exercise. This is the worktree-faithful "running backend" check the
 * implementation goal requires; unit tests alone are necessary but not sufficient.
 *
 * <p>Flow: invoking the LOW-risk (AUTO-gated) {@code core.navigate-to-surface} Operation
 * routes through {@code NavigateToSurfaceHandler → BackendIntentRouter.forwardNavigation},
 * which records the {@code NavigationHistoryEntry}.
 */
@DisplayName("Navigation History Ledger E2E (tempdoc 550 Slice F1)")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class NavigationHistoryE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  @BeforeAll
  static void startBackend() throws Exception {
    BACKEND.start();
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.stop();
  }

  @Test
  @DisplayName("Invoking navigate-to-surface records an attributed entry in the ledger")
  void navigationInvokeIsRecordedInLedger() throws Exception {
    // Sanity: the ledger endpoint exists in the real bootstrap (would 404 on the
    // test-only Builder path) and starts empty.
    HttpResponse<String> before = get("/api/navigation-history");
    assertEquals(200, before.statusCode(), "navigation-history endpoint is wired: " + before.body());
    JsonNode beforeJson = MAPPER.readTree(before.body());
    assertTrue(beforeJson.has("entries"), "snapshot has entries array");
    int beforeCount = beforeJson.get("entries").size();

    // Trigger a navigation by invoking the LOW-risk navigate-to-surface Operation. Its
    // handler forwards a ShellAddress.Navigation through the BackendIntentRouter, which is
    // what records the ledger entry (tempdoc 550 Slice F1).
    HttpResponse<String> invoke =
        post(
            "/api/operations/core.navigate-to-surface/invoke",
            "{\"args\":{\"surfaceId\":\"core.library\"}}");
    assertEquals(200, invoke.statusCode(), "navigate-to-surface invoked (AUTO gate): " + invoke.body());

    // The record must now be present and attributed.
    HttpResponse<String> after = get("/api/navigation-history");
    assertEquals(200, after.statusCode());
    JsonNode entries = MAPPER.readTree(after.body()).get("entries");
    assertEquals(beforeCount + 1, entries.size(), "exactly one new navigation recorded: " + after.body());

    JsonNode latest = entries.get(entries.size() - 1);
    assertEquals("core.library", latest.get("targetSurface").asText(), "target surface recorded");
    assertTrue(
        latest.get("envelopeId").asText().startsWith("ie-"),
        "server-assigned envelope id correlates with the broadcast envelope");
    assertTrue(!latest.get("sourceId").asText().isBlank(), "source id attributed");
    assertTrue(latest.has("occurredAt"), "occurredAt present");
    assertTrue(latest.has("provenance"), "full provenance retained on the record");
  }

  private static HttpResponse<String> get(String path) throws Exception {
    return CLIENT.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(String path, String json) throws Exception {
    return CLIENT.send(
        HttpRequest.newBuilder(URI.create(base() + path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String base() {
    return "http://127.0.0.1:" + BACKEND.port();
  }
}
