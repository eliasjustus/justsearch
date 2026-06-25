package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Live-stack verification for tempdoc 550 Slice F2 (Preview face): {@code
 * GET /api/operations/{id}/preview} returns an EVALUATED availability answer + risk,
 * against a real {@code HeadlessApp} child JVM — proving {@code Operation.availability}
 * is no longer a dead channel (it is now read and evaluated, not just serialized for
 * display).
 *
 * <p>Exercises the {@code Always} branch on a real operation ({@code core.ping-backend},
 * LOW risk, no conditional gating → {@code availableNow == true}); the {@code
 * ConditionMatches} branch is unit-verified in {@code AvailabilityEvaluatorTest} +
 * {@code ConditionAvailabilityProbeTest} and lights up once operations declare
 * conditional availability (550 §Build status).
 */
@DisplayName("Operation Preview E2E (tempdoc 550 Slice F2)")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class OperationPreviewE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  static void startBackend() throws Exception {
    BACKEND.start();
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.stop();
  }

  @Test
  @DisplayName("Preview evaluates availability + surfaces risk for a real operation")
  void previewEvaluatesAvailabilityAndRisk() throws Exception {
    HttpResponse<String> resp =
        CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(base() + "/api/operations/core.ping-backend/preview"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), "preview endpoint is wired: " + resp.body());

    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("core.ping-backend", json.path("operationId").asText());
    assertTrue(json.has("riskTier"), "risk tier surfaced: " + resp.body());
    assertTrue(json.has("availableNow"), "availability is EVALUATED, not just declared");
    assertTrue(
        json.path("availableNow").asBoolean(false),
        "a LOW-risk op with no conditional gating is available now: " + resp.body());
  }

  @Test
  @DisplayName("S2: preview predicts the trust gate for a transport without executing")
  void previewPredictsGateBehavior() throws Exception {
    // Tempdoc 550 S2: the gate is predicted (SourceTier × RiskTier) without running the op.
    // core.ping-backend is LOW risk; via the default LLM_EMISSION transport (UNTRUSTED), the
    // lattice yields AUTO (UNTRUSTED × LOW = AUTO).
    HttpResponse<String> resp =
        CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(base() + "/api/operations/core.ping-backend/preview"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), "preview readable: " + resp.body());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("LLM_EMISSION", json.path("transport").asText(), "default transport: " + resp.body());
    assertEquals("UNTRUSTED", json.path("sourceTier").asText(), "LLM_EMISSION → UNTRUSTED: " + resp.body());
    assertEquals(
        "AUTO",
        json.path("gateBehavior").asText(),
        "UNTRUSTED × LOW predicts AUTO, computed without executing: " + resp.body());
  }

  @Test
  @DisplayName("F1: the preview's predicted gate reflects the live Global Hard Stop")
  void previewReflectsGlobalHardStop() throws Exception {
    // Tempdoc 550 F1: when the hard stop is engaged, the actual lattice DENIES UNTRUSTED dispatch,
    // so the preview must predict DENY (not a stale permissive gate). core.bulk-reindex via the
    // default LLM_EMISSION (UNTRUSTED).
    setHardStop(true);
    try {
      JsonNode engaged = MAPPER.readTree(getBody("/api/operations/core.bulk-reindex/preview"));
      assertEquals("DENY", engaged.path("gateBehavior").asText(), "engaged → predict DENY: " + engaged);
      assertTrue(engaged.path("hardStopEngaged").asBoolean(), "hardStopEngaged surfaced: " + engaged);
    } finally {
      setHardStop(false);
    }
    JsonNode released = MAPPER.readTree(getBody("/api/operations/core.bulk-reindex/preview"));
    assertNotEquals(
        "DENY",
        released.path("gateBehavior").asText(),
        "released → the normal predicted gate returns: " + released);
  }

  private static void setHardStop(boolean engaged) throws Exception {
    HttpResponse<String> resp =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create(base() + "/api/agent/hard-stop"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"engaged\":" + engaged + "}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), "hard-stop toggle: " + resp.body());
  }

  private static String getBody(String path) throws Exception {
    return CLIENT.send(
            HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }

  @Test
  @DisplayName("E3: a WorkerOnline op has capability-DERIVED availability, evaluated live")
  void capabilityDerivedAvailabilityIsLive() throws Exception {
    // Tempdoc 550 E3: core.bulk-reindex declares only RequiredCapability.WorkerOnline (no
    // hand-authored availability). The substrate now derives Not(ConditionMatches("worker.capability"))
    // from that capability. In the fixture the worker is READY → worker.capability is cleared →
    // the op is available now; availabilityKind reflects the DERIVED expression (not "Always").
    HttpResponse<String> resp =
        CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(base() + "/api/operations/core.bulk-reindex/preview"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), "preview readable: " + resp.body());
    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals(
        "Not",
        json.path("availabilityKind").asText(),
        "WorkerOnline → availability DERIVED as Not(worker.capability), not Always: " + resp.body());
    assertTrue(
        json.path("availableNow").asBoolean(false),
        "worker READY in the fixture → worker.capability cleared → available now: " + resp.body());
  }

  @Test
  @DisplayName("Unknown operation id returns 404")
  void unknownOperationReturns404() throws Exception {
    HttpResponse<String> resp =
        CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(base() + "/api/operations/core.does-not-exist/preview"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(404, resp.statusCode(), "unknown op → 404: " + resp.body());
  }

  private static String base() {
    return "http://127.0.0.1:" + BACKEND.port();
  }
}
