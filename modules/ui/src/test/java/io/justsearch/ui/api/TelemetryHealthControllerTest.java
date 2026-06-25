package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.justsearch.telemetry.TelemetryHealthSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TelemetryHealthController unit tests")
class TelemetryHealthControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private HttpClient client;
  private Javalin app;
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
  }

  private void startServer(Supplier<TelemetryHealthSnapshot> supplier) {
    TelemetryHealthController controller = new TelemetryHealthController(supplier);
    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); });
    app.get("/api/telemetry/health", controller::handleGetHealth);
    app.start("127.0.0.1", 0);
    port = app.port();
  }

  private HttpResponse<String> get() throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/telemetry/health"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("Supplier returns null -> 503 ERROR + TELEMETRY_UNAVAILABLE")
  void nullSupplierReturns503Error() throws Exception {
    startServer(() -> null);

    HttpResponse<String> resp = get();

    assertEquals(503, resp.statusCode());
    Map<?, ?> json = MAPPER.readValue(resp.body(), Map.class);
    assertEquals(1, json.get("schema_version"));
    assertEquals("LIFECYCLE_STATE_ERROR", json.get("state"));
    assertEquals("telemetry.unavailable", json.get("reason_code"));
    assertNotNull(json.get("observed_at"));
  }

  @Test
  @DisplayName("Supplier throws exception -> 503 ERROR + TELEMETRY_UNAVAILABLE")
  void supplierExceptionReturns503Error() throws Exception {
    startServer(
        () -> {
          throw new RuntimeException("Simulated failure");
        });

    HttpResponse<String> resp = get();

    assertEquals(503, resp.statusCode());
    Map<?, ?> json = MAPPER.readValue(resp.body(), Map.class);
    assertEquals("LIFECYCLE_STATE_ERROR", json.get("state"));
    assertEquals("telemetry.unavailable", json.get("reason_code"));
  }

  @Test
  @DisplayName("Healthy snapshot -> 200 READY + null reason")
  void healthySnapshotReturns200Ready() throws Exception {
    // Healthy snapshot: recent export, high success rate, no disk issues
    TelemetryHealthSnapshot snapshot =
        new TelemetryHealthSnapshot(
            0, // metricExportFailures
            0, // spanExportFailures
            0, // rotationFailures
            0, // pruneFailures
            0, // gaugeCallbackFailures
            0, // flushFailures
            0, // diskSpaceLowEvents
            100, // metricExportSuccesses
            50, // spanExportSuccesses
            5, // rotationSuccesses
            Instant.now().minus(Duration.ofSeconds(30)), // lastSuccessfulMetricExport
            Instant.now().minus(Duration.ofSeconds(30)), // lastSuccessfulSpanExport
            Instant.now().minus(Duration.ofMinutes(1)), // lastSuccessfulRotation
            Instant.now() // observedAt
            );

    startServer(() -> snapshot);

    HttpResponse<String> resp = get();

    assertEquals(200, resp.statusCode());
    Map<?, ?> json = MAPPER.readValue(resp.body(), Map.class);
    assertEquals("LIFECYCLE_STATE_READY", json.get("state"));
    assertNull(json.get("reason_code"));
  }

  @Test
  @DisplayName("Stale export (>5min old) -> 200 DEGRADED + TELEMETRY_METRICS_STALE")
  void staleExportReturnsDegraded() throws Exception {
    // Stale: last successful export was 10 minutes ago
    TelemetryHealthSnapshot snapshot =
        new TelemetryHealthSnapshot(
            0,
            0,
            0,
            0,
            0,
            0,
            0, // diskSpaceLowEvents
            100,
            50,
            5,
            Instant.now().minus(Duration.ofMinutes(10)), // >5 min ago = stale
            Instant.now().minus(Duration.ofMinutes(10)),
            Instant.now().minus(Duration.ofMinutes(10)),
            Instant.now());

    startServer(() -> snapshot);

    HttpResponse<String> resp = get();

    assertEquals(200, resp.statusCode());
    Map<?, ?> json = MAPPER.readValue(resp.body(), Map.class);
    assertEquals("LIFECYCLE_STATE_DEGRADED", json.get("state"));
    assertEquals("telemetry.metrics.stale", json.get("reason_code"));
  }

  @Test
  @DisplayName("High failure rate (<90% success) -> 200 DEGRADED + TELEMETRY_METRICS_HIGH_FAILURE_RATE")
  void highFailureRateReturnsDegraded() throws Exception {
    // 80% success rate (20 failures out of 100 total = 80% success)
    TelemetryHealthSnapshot snapshot =
        new TelemetryHealthSnapshot(
            20, // metricExportFailures
            0,
            0,
            0,
            0,
            0,
            0, // diskSpaceLowEvents
            80, // metricExportSuccesses (80/(80+20) = 80% < 90%)
            50,
            5,
            Instant.now().minus(Duration.ofSeconds(30)),
            Instant.now().minus(Duration.ofSeconds(30)),
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now());

    startServer(() -> snapshot);

    HttpResponse<String> resp = get();

    assertEquals(200, resp.statusCode());
    Map<?, ?> json = MAPPER.readValue(resp.body(), Map.class);
    assertEquals("LIFECYCLE_STATE_DEGRADED", json.get("state"));
    assertEquals("telemetry.metrics.high_failure_rate", json.get("reason_code"));
  }

  @Test
  @DisplayName("Disk space low events > 0 -> 200 DEGRADED + TELEMETRY_DISK_SPACE_LOW")
  void diskSpaceLowReturnsDegraded() throws Exception {
    // Has disk space low events
    TelemetryHealthSnapshot snapshot =
        new TelemetryHealthSnapshot(
            0,
            0,
            0,
            0,
            0,
            0,
            3, // diskSpaceLowEvents > 0
            100,
            50,
            5,
            Instant.now().minus(Duration.ofSeconds(30)),
            Instant.now().minus(Duration.ofSeconds(30)),
            Instant.now().minus(Duration.ofMinutes(1)),
            Instant.now());

    startServer(() -> snapshot);

    HttpResponse<String> resp = get();

    assertEquals(200, resp.statusCode());
    Map<?, ?> json = MAPPER.readValue(resp.body(), Map.class);
    assertEquals("LIFECYCLE_STATE_DEGRADED", json.get("state"));
    assertEquals("telemetry.disk_space_low", json.get("reason_code"));
  }

  @Test
  @DisplayName("Disk space low overrides other degraded states")
  void diskSpaceLowOverridesOtherDegradedStates() throws Exception {
    // Has both stale exports AND disk space low - disk space should take precedence
    TelemetryHealthSnapshot snapshot =
        new TelemetryHealthSnapshot(
            0,
            0,
            0,
            0,
            0,
            0,
            1, // diskSpaceLowEvents
            100,
            50,
            5,
            Instant.now().minus(Duration.ofMinutes(10)), // stale
            Instant.now().minus(Duration.ofMinutes(10)),
            Instant.now().minus(Duration.ofMinutes(10)),
            Instant.now());

    startServer(() -> snapshot);

    HttpResponse<String> resp = get();

    assertEquals(200, resp.statusCode());
    Map<?, ?> json = MAPPER.readValue(resp.body(), Map.class);
    assertEquals("LIFECYCLE_STATE_DEGRADED", json.get("state"));
    // Disk space low should override stale
    assertEquals("telemetry.disk_space_low", json.get("reason_code"));
  }
}
