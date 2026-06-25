package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP Mode Transition Tests - Verifies ONLINE ↔ INDEXING mode switching.
 *
 * <p>Tests the inference mode API:
 * <ul>
 *   <li>GET /api/inference/status - returns current mode and availability</li>
 *   <li>POST /api/inference/mode - switches between "online" and "indexing"</li>
 * </ul>
 *
 * <p>Mode behavior:
 * <ul>
 *   <li>ONLINE: llama-server running, AI features available</li>
 *   <li>INDEXING: llama-server stopped, AI features unavailable (returns AI_OFFLINE)</li>
 * </ul>
 *
 * <p>REQUIRES a running JustSearch server with llama-server configured.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :modules:system-tests:integrationTest --tests "*HttpModeTransitionTest*" \
 *       -Djustsearch.api.port=9001
 * </pre>
 *
 * <p><b>WARNING:</b> These tests modify server state. They attempt to restore
 * the original mode in @AfterAll, but if tests crash, the server may be left
 * in INDEXING mode.
 */
@DisplayName("HTTP Mode Transition Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpModeTransitionTest {

    private static final Logger log = LoggerFactory.getLogger(HttpModeTransitionTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpClient client;
    private static int port;
    private static boolean serverAvailable = false;
    private static String originalMode = null; // Saved to restore after tests

    // Timeouts
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MODE_SWITCH_TIMEOUT = Duration.ofSeconds(45); // llama-server startup can be slow

    @BeforeAll
    static void setup() {
        port = Integer.getInteger("justsearch.api.port", 8080);
        client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

        serverAvailable = checkServerAvailable();
        if (!serverAvailable) {
            log.warn("⚠️  Server not available at localhost:{}", port);
            return;
        }

        // Save original mode to restore later
        originalMode = getCurrentMode();
        log.info("Original mode saved: {}", originalMode);
    }

    @AfterAll
    static void restoreOriginalMode() {
        if (!serverAvailable || originalMode == null) {
            return;
        }

        try {
            String currentMode = getCurrentMode();
            if (!originalMode.equals(currentMode)) {
                log.info("Restoring original mode: {} -> {}", currentMode, originalMode);
                switchMode(originalMode);

                // Wait for mode to stabilize if switching to online
                if ("online".equals(originalMode)) {
                    awaitModeAvailable(MODE_SWITCH_TIMEOUT);
                }
                log.info("Mode restored to: {}", originalMode);
            }
        } catch (Exception e) {
            log.error("Failed to restore original mode. Server may be in unexpected state.", e);
        }
    }

    private static boolean checkServerAvailable() {
        try {
            var resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/status"))
                    .timeout(Duration.ofSeconds(2))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getCurrentMode() {
        try {
            var resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/status"))
                    .timeout(REQUEST_TIMEOUT)
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (resp.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(resp.body());
                return json.path("mode").asText("unknown");
            }
        } catch (Exception e) {
            log.warn("Failed to get current mode", e);
        }
        return "unknown";
    }

    private static void switchMode(String mode) throws Exception {
        String jsonBody = MAPPER.writeValueAsString(Map.of("mode", mode));
        var resp = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/mode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(MODE_SWITCH_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to switch mode: " + resp.body());
        }
    }

    /**
     * Polls until mode is "online" and available, or timeout.
     */
    private static boolean awaitModeAvailable(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                var resp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/status"))
                        .timeout(REQUEST_TIMEOUT)
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                if (resp.statusCode() == 200) {
                    JsonNode json = MAPPER.readTree(resp.body());
                    String mode = json.path("mode").asText("");
                    boolean available = json.path("available").asBoolean(false);
                    if ("online".equals(mode) && available) {
                        return true;
                    }
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.debug("Polling failed: {}", e.getMessage());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("GET /api/inference/status returns valid response")
    void getInferenceStatusReturnsValidResponse() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/status"))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());

        JsonNode json = MAPPER.readTree(response.body());
        assertTrue(json.has("mode"), "Response should contain 'mode'");
        assertTrue(json.has("available"), "Response should contain 'available'");
        assertTrue(json.has("starting"), "Response should contain 'starting'");

        String mode = json.get("mode").asText();
        assertTrue(
            "online".equals(mode) || "indexing".equals(mode) || "transitioning".equals(mode),
            "Mode should be 'online', 'indexing', or 'transitioning', got: " + mode
        );

        log.info("Current inference status: mode={}, available={}",
            json.get("mode").asText(), json.get("available").asBoolean());
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/inference/mode with invalid mode returns 400")
    void invalidModeReturns400() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        String jsonBody = MAPPER.writeValueAsString(Map.of("mode", "invalid_mode"));
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/mode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(400, response.statusCode(), "Invalid mode should return 400");
        assertTrue(response.body().contains("error"), "Response should contain error message");
    }

    @Test
    @Order(3)
    @DisplayName("POST /api/inference/mode with missing mode returns 400")
    void missingModeReturns400() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        String jsonBody = "{}";
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/mode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(400, response.statusCode(), "Missing mode should return 400");
    }

    @Test
    @Order(4)
    @DisplayName("Switch to INDEXING mode succeeds and makes AI unavailable")
    void switchToIndexingModeSucceeds() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        // Switch to indexing mode
        String jsonBody = MAPPER.writeValueAsString(Map.of("mode", "indexing"));
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/mode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(MODE_SWITCH_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode(), "Mode switch should succeed");

        JsonNode json = MAPPER.readTree(response.body());
        assertTrue(json.get("success").asBoolean(), "Response should indicate success");
        assertEquals("indexing", json.get("mode").asText(), "Mode should be 'indexing'");

        // Verify status endpoint reflects the change
        var statusResponse = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/status"))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        JsonNode statusJson = MAPPER.readTree(statusResponse.body());
        assertEquals("indexing", statusJson.get("mode").asText(), "Status should show 'indexing' mode");
        assertFalse(statusJson.get("available").asBoolean(), "AI should be unavailable in indexing mode");

        log.info("Successfully switched to INDEXING mode");
    }

    @Test
    @Order(5)
    @DisplayName("AI endpoint returns AI_OFFLINE error in INDEXING mode")
    void aiEndpointReturnsOfflineErrorInIndexingMode() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        // Ensure we're in indexing mode
        String currentMode = getCurrentMode();
        if (!"indexing".equals(currentMode)) {
            switchMode("indexing");
        }

        // Try to use AI endpoint - should get AI_OFFLINE error
        // Provide a fake docId to pass the docIds check and reach the AI availability check
        String jsonBody = MAPPER.writeValueAsString(Map.of(
            "docIds", java.util.List.of("fake-doc-id-for-testing")
        ));
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat/batch-summarize"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        // SSE response - parse the error event
        String body = response.body();
        log.debug("AI endpoint response in indexing mode: {}", body);

        // Should contain AI_OFFLINE error (or AI_STARTING during transition)
        assertTrue(
            body.contains("AI_OFFLINE") || body.contains("AI unavailable") || body.contains("AI_STARTING"),
            "Response should indicate AI is offline, got: " + body
        );

        log.info("AI endpoint correctly returns offline error in INDEXING mode");
    }

    @Test
    @Order(6)
    @DisplayName("Switch to ONLINE mode succeeds and makes AI available")
    void switchToOnlineModeSucceeds() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        // Switch to online mode
        String jsonBody = MAPPER.writeValueAsString(Map.of("mode", "online"));
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/mode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(MODE_SWITCH_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode(), "Mode switch should succeed");

        JsonNode json = MAPPER.readTree(response.body());
        assertTrue(json.get("success").asBoolean(), "Response should indicate success");

        // Mode might be "online" or "transitioning" immediately after switch
        String mode = json.get("mode").asText();
        assertTrue(
            "online".equals(mode) || "transitioning".equals(mode),
            "Mode should be 'online' or 'transitioning' after switch, got: " + mode
        );

        // Wait for llama-server to become available (can take 5-15 seconds)
        log.info("Waiting for llama-server to become available...");
        boolean available = awaitModeAvailable(MODE_SWITCH_TIMEOUT);
        assertTrue(available, "AI should become available within " + MODE_SWITCH_TIMEOUT.toSeconds() + " seconds");

        // Verify status
        var statusResponse = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/status"))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        JsonNode statusJson = MAPPER.readTree(statusResponse.body());
        assertEquals("online", statusJson.get("mode").asText(), "Status should show 'online' mode");
        assertTrue(statusJson.get("available").asBoolean(), "AI should be available in online mode");

        log.info("Successfully switched to ONLINE mode, AI is available");
    }

    @Test
    @Order(7)
    @DisplayName("AI endpoint works after switching back to ONLINE mode")
    void aiEndpointWorksAfterSwitchBackToOnline() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        // Ensure we're in online mode and available
        String currentMode = getCurrentMode();
        if (!"online".equals(currentMode)) {
            switchMode("online");
            assertTrue(awaitModeAvailable(MODE_SWITCH_TIMEOUT), "AI should become available");
        }

        // Try AI endpoint with fake docId - should NOT get AI_OFFLINE error
        // (may get other errors like "document not found" but not AI_OFFLINE)
        String jsonBody = MAPPER.writeValueAsString(Map.of(
            "docIds", java.util.List.of("fake-doc-id-for-testing")
        ));
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat/batch-summarize"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        String body = response.body();
        log.debug("AI endpoint response in online mode: {}", body);

        // Should NOT contain AI_OFFLINE error (may contain other errors like NO_CONTENT)
        assertFalse(
            body.contains("AI_OFFLINE"),
            "Response should not indicate AI is offline after switching to online mode"
        );

        log.info("AI endpoint accessible after switching to ONLINE mode");
    }

    @Test
    @Order(8)
    @DisplayName("Switching to same mode is idempotent")
    void switchToSameModeIsIdempotent() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        // Get current mode
        String currentMode = getCurrentMode();

        // Switch to same mode
        String jsonBody = MAPPER.writeValueAsString(Map.of("mode", currentMode));
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/mode"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(MODE_SWITCH_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode(), "Switching to same mode should succeed");

        JsonNode json = MAPPER.readTree(response.body());
        assertTrue(json.get("success").asBoolean(), "Response should indicate success");
        assertEquals(currentMode, json.get("mode").asText(), "Mode should remain unchanged");

        log.info("Idempotent switch to same mode succeeded");
    }
}
