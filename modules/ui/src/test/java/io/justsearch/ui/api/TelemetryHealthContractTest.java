package io.justsearch.ui.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.telemetry.TelemetryHealthSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TelemetryHealthController contract tests (schema v1)")
class TelemetryHealthContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern REASON_CODE_PATTERN = Pattern.compile("^[a-z][a-z_]+(\\.[a-z_]+)+$");

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

  private TelemetryHealthSnapshot healthySnapshot() {
    return new TelemetryHealthSnapshot(
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        100,
        50,
        5,
        Instant.now().minus(Duration.ofSeconds(30)),
        Instant.now().minus(Duration.ofSeconds(30)),
        Instant.now().minus(Duration.ofMinutes(1)),
        Instant.now());
  }

  private TelemetryHealthSnapshot degradedSnapshot() {
    return new TelemetryHealthSnapshot(
        0,
        0,
        0,
        0,
        0,
        0,
        1, // disk space low
        100,
        50,
        5,
        Instant.now().minus(Duration.ofSeconds(30)),
        Instant.now().minus(Duration.ofSeconds(30)),
        Instant.now().minus(Duration.ofMinutes(1)),
        Instant.now());
  }

  @Test
  @DisplayName("Response has schema_version=1 and all required fields")
  void responseHasSchemaV1AndRequiredFields() throws Exception {
    startServer(this::healthySnapshot);

    HttpResponse<String> resp = get();
    assertEquals(200, resp.statusCode());

    JsonNode json = MAPPER.readTree(resp.body());

    // Exact root field set — catches accidental additions or removals
    assertExactFields(json, "telemetry health root",
        "schema_version", "observed_at", "state", "reason_code",
        "counters", "rates", "timestamps");

    // schema_version
    assertTrue(json.has("schema_version"), "Response must have schema_version");
    assertEquals(1, json.get("schema_version").asInt(), "schema_version must be 1");

    // observed_at
    assertTrue(json.has("observed_at"), "Response must have observed_at");
    assertFalse(
        json.get("observed_at").asText().isEmpty(), "observed_at must not be empty");

    // state
    assertTrue(json.has("state"), "Response must have state");
    String state = json.get("state").asText();
    assertTrue(
        state.equals("LIFECYCLE_STATE_READY") || state.equals("LIFECYCLE_STATE_DEGRADED") || state.equals("LIFECYCLE_STATE_ERROR"),
        "state must be READY, DEGRADED, or ERROR");

    // counters — exact field set
    assertTrue(json.has("counters"), "Response must have counters");
    JsonNode counters = json.get("counters");
    assertExactFields(counters, "counters",
        "metric_export_failures", "span_export_failures", "rotation_failures",
        "prune_failures", "gauge_callback_failures", "flush_failures",
        "disk_space_low_events", "metric_export_successes", "span_export_successes");

    // rates — exact field set
    assertTrue(json.has("rates"), "Response must have rates");
    JsonNode rates = json.get("rates");
    assertExactFields(rates, "rates",
        "metric_export_success_rate", "span_export_success_rate");

    // timestamps — exact field set
    assertTrue(json.has("timestamps"), "Response must have timestamps");
    JsonNode timestamps = json.get("timestamps");
    assertExactFields(timestamps, "timestamps",
        "last_successful_metric_export", "last_successful_span_export",
        "last_successful_rotation");
  }

  @Test
  @DisplayName("reason_code matches pattern and is a known LifecycleReasonCode")
  void reasonCodeMatchesPatternAndIsKnown() throws Exception {
    startServer(this::degradedSnapshot);

    HttpResponse<String> resp = get();
    assertEquals(200, resp.statusCode());

    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("LIFECYCLE_STATE_DEGRADED", json.get("state").asText());

    // reason_code must be present for degraded state
    assertTrue(json.has("reason_code"), "DEGRADED state must have reason_code");
    String reasonCode = json.get("reason_code").asText();
    assertNotNull(reasonCode, "reason_code must not be null");
    assertFalse(reasonCode.isEmpty(), "reason_code must not be empty");

    // Must match pattern (telemetry.something.something or similar)
    assertTrue(
        REASON_CODE_PATTERN.matcher(reasonCode).matches(),
        "reason_code must match pattern ^[a-z][a-z_]+(\\.[a-z_]+)+$: " + reasonCode);

    // Must be in the known list
    assertTrue(
        LifecycleReasonCode.isKnown(reasonCode),
        "reason_code must be a known LifecycleReasonCode: " + reasonCode);
  }

  @Test
  @DisplayName("reason_code is null for READY state")
  void reasonCodeNullForReadyState() throws Exception {
    startServer(this::healthySnapshot);

    HttpResponse<String> resp = get();
    assertEquals(200, resp.statusCode());

    JsonNode json = MAPPER.readTree(resp.body());
    assertEquals("LIFECYCLE_STATE_READY", json.get("state").asText());

    // reason_code should be null for READY state
    JsonNode reasonCodeNode = json.get("reason_code");
    assertTrue(
        reasonCodeNode == null || reasonCodeNode.isNull(),
        "READY state should have null reason_code");
  }

  @Test
  @DisplayName("Success rates are in valid range [0.0, 1.0]")
  void successRatesInValidRange() throws Exception {
    startServer(this::healthySnapshot);

    HttpResponse<String> resp = get();
    assertEquals(200, resp.statusCode());

    JsonNode json = MAPPER.readTree(resp.body());
    JsonNode rates = json.get("rates");

    double metricRate = rates.get("metric_export_success_rate").asDouble();
    double spanRate = rates.get("span_export_success_rate").asDouble();

    assertTrue(metricRate >= 0.0, "metric_export_success_rate must be >= 0.0");
    assertTrue(metricRate <= 1.0, "metric_export_success_rate must be <= 1.0");
    assertTrue(spanRate >= 0.0, "span_export_success_rate must be >= 0.0");
    assertTrue(spanRate <= 1.0, "span_export_success_rate must be <= 1.0");
  }

  @Test
  @DisplayName("503 response also follows schema v1")
  void errorResponseFollowsSchemaV1() throws Exception {
    startServer(() -> null);

    HttpResponse<String> resp = get();
    assertEquals(503, resp.statusCode());

    JsonNode json = MAPPER.readTree(resp.body());

    // Must have schema_version
    assertTrue(json.has("schema_version"), "Error response must have schema_version");
    assertEquals(1, json.get("schema_version").asInt());

    // Must have observed_at
    assertTrue(json.has("observed_at"), "Error response must have observed_at");

    // Must have state = ERROR
    assertTrue(json.has("state"), "Error response must have state");
    assertEquals("LIFECYCLE_STATE_ERROR", json.get("state").asText());

    // Must have reason_code
    assertTrue(json.has("reason_code"), "Error response must have reason_code");
    String reasonCode = json.get("reason_code").asText();
    assertTrue(
        LifecycleReasonCode.isKnown(reasonCode),
        "Error reason_code must be a known LifecycleReasonCode: " + reasonCode);
  }

  @Test
  @DisplayName("Counters are non-negative integers")
  void countersAreNonNegative() throws Exception {
    startServer(this::healthySnapshot);

    HttpResponse<String> resp = get();
    assertEquals(200, resp.statusCode());

    JsonNode json = MAPPER.readTree(resp.body());
    JsonNode counters = json.get("counters");

    String[] counterFields = {
      "metric_export_failures",
      "span_export_failures",
      "rotation_failures",
      "prune_failures",
      "gauge_callback_failures",
      "flush_failures",
      "disk_space_low_events",
      "metric_export_successes",
      "span_export_successes"
    };

    for (String field : counterFields) {
      assertTrue(counters.has(field), "counters must have " + field);
      assertTrue(counters.get(field).isNumber(), field + " must be a number");
      assertTrue(counters.get(field).asLong() >= 0, field + " must be non-negative");
    }
  }

  private static void assertExactFields(JsonNode node, String context, String... expected) {
    var actual = new java.util.ArrayList<String>();
    for (var entry : node.properties()) {
      actual.add(entry.getKey());
    }
    assertThat(actual)
        .as("Exact field set for %s", context)
        .containsExactlyInAnyOrder(expected);
  }
}
