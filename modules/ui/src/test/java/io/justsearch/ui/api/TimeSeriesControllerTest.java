package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.justsearch.telemetry.RrdMetricStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link TimeSeriesController}.
 *
 * <p>Tests the HTTP endpoint behavior including input validation, error handling,
 * and response format.
 */
class TimeSeriesControllerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Javalin app;
  private int port;
  private HttpClient client;

  @BeforeEach
  void setUp() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  private void startServer(RrdMetricStore store) {
    TimeSeriesController controller = new TimeSeriesController(() -> store);
    app = Javalin.create(cfg -> { cfg.showJavalinBanner = false; cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper()); });
    app.get("/api/debug/metrics/timeseries", controller::handleGetTimeSeries);
    app.get("/api/debug/metrics/timeseries/available", controller::handleGetAvailable);
    app.start("127.0.0.1", 0);
    port = app.port();
  }

  private HttpResponse<String> fetchRaw(String path) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + port + path))
        .GET()
        .build();
    return client.send(req, HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode fetch(String path, int expectedStatus) throws Exception {
    HttpResponse<String> resp = fetchRaw(path);
    assertEquals(expectedStatus, resp.statusCode(), "Expected HTTP " + expectedStatus + " for " + path);
    return MAPPER.readTree(resp.body());
  }

  // ------ Store Unavailable Tests ------

  @Test
  void storeUnavailable_returns503() throws Exception {
    startServer(null);

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=head.http.inflight_requests", 503);

    assertEquals("RRD metric store unavailable", json.get("error").asText());
    assertEquals("STORE_UNAVAILABLE", json.get("errorCode").asText());
    assertTrue(json.has("timestamp"), "Error response should include timestamp");
  }

  @Test
  void available_storeUnavailable_returns503() throws Exception {
    startServer(null);

    JsonNode json = fetch("/api/debug/metrics/timeseries/available", 503);

    assertEquals("STORE_UNAVAILABLE", json.get("errorCode").asText());
  }

  // ------ Missing/Unknown Metric Tests ------

  @Test
  void missingMetric_returns400() throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries", 400);

    assertEquals("Missing required parameter: metric", json.get("error").asText());
    assertEquals("MISSING_METRIC", json.get("errorCode").asText());
    assertFalse(json.has("available_metrics"), "L1: Error should not expose metric list");
  }

  @Test
  void blankMetric_returns400() throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=", 400);

    assertEquals("MISSING_METRIC", json.get("errorCode").asText());
  }

  @Test
  void unknownMetric_returns404() throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=nonexistent.metric", 404);

    assertEquals("Unknown metric", json.get("error").asText());
    assertEquals("UNKNOWN_METRIC", json.get("errorCode").asText());
    assertFalse(json.has("available_metrics"), "L1: Error should not expose metric list");
  }

  // ------ Time Range Overflow Tests (C2) ------

  @ParameterizedTest
  @ValueSource(strings = {"-999999999d", "-9999999h", "-999999999m", "999999d"})
  void overflowTimeRange_returns400(String timeSpec) throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=" + timeSpec, 400);

    assertEquals("TIME_RANGE_OVERFLOW", json.get("errorCode").asText());
    assertTrue(json.get("error").asText().contains("max 1 year"), "Error should mention max 1 year limit");
  }

  @Test
  void overflowStart_returns400() throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=-500d", 400);

    assertEquals("TIME_RANGE_OVERFLOW", json.get("errorCode").asText());
  }

  @Test
  void overflowEnd_returns400() throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&end=-500d", 400);

    assertEquals("TIME_RANGE_OVERFLOW", json.get("errorCode").asText());
  }

  // ------ Invalid Timestamp Format Tests (C3) ------

  @ParameterizedTest
  @ValueSource(strings = {"invalid", "2025-13-45T99:99:99Z", "abc123", "-1x", "1.5h"})
  void invalidTimestamp_returns400(String timeSpec) throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=" + timeSpec, 400);

    assertEquals("INVALID_TIMESTAMP", json.get("errorCode").asText());
    assertTrue(json.get("error").asText().contains("ISO-8601"), "Error should mention ISO-8601 format");
  }

  // ------ Invalid Range Tests ------

  @Test
  void startAfterEnd_returns400() throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=2025-01-02T00:00:00Z&end=2025-01-01T00:00:00Z", 400);

    assertEquals("INVALID_RANGE", json.get("errorCode").asText());
    assertEquals("start must be before end", json.get("error").asText());
  }

  @Test
  void startEqualsEnd_returns400() throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=2025-01-01T00:00:00Z&end=2025-01-01T00:00:00Z", 400);

    assertEquals("INVALID_RANGE", json.get("errorCode").asText());
  }

  // ------ Valid Relative Time Specs ------

  @ParameterizedTest
  @ValueSource(strings = {"-1h", "-24h", "1h", "24h"})
  void validRelativeHours_accepted(String timeSpec) throws Exception {
    startServer(new MockRrdStore());

    // Should not return overflow or invalid timestamp errors
    HttpResponse<String> resp = fetchRaw("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=" + timeSpec);
    JsonNode json = MAPPER.readTree(resp.body());

    // Either 200 (success) or a different error (not TIME_RANGE_OVERFLOW or INVALID_TIMESTAMP)
    if (resp.statusCode() == 400) {
      String errorCode = json.get("errorCode").asText();
      assertNotEquals("TIME_RANGE_OVERFLOW", errorCode, "Valid time spec should not overflow: " + timeSpec);
      assertNotEquals("INVALID_TIMESTAMP", errorCode, "Valid time spec should not be invalid: " + timeSpec);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"-7d", "-30d", "7d", "30d", "-365d", "365d"})
  void validRelativeDays_accepted(String timeSpec) throws Exception {
    startServer(new MockRrdStore());

    HttpResponse<String> resp = fetchRaw("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=" + timeSpec);
    JsonNode json = MAPPER.readTree(resp.body());

    if (resp.statusCode() == 400) {
      String errorCode = json.get("errorCode").asText();
      assertNotEquals("TIME_RANGE_OVERFLOW", errorCode, "Valid time spec should not overflow: " + timeSpec);
      assertNotEquals("INVALID_TIMESTAMP", errorCode, "Valid time spec should not be invalid: " + timeSpec);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"-30m", "-60m", "30m", "60m"})
  void validRelativeMinutes_accepted(String timeSpec) throws Exception {
    startServer(new MockRrdStore());

    HttpResponse<String> resp = fetchRaw("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=" + timeSpec);
    JsonNode json = MAPPER.readTree(resp.body());

    if (resp.statusCode() == 400) {
      String errorCode = json.get("errorCode").asText();
      assertNotEquals("TIME_RANGE_OVERFLOW", errorCode, "Valid time spec should not overflow: " + timeSpec);
      assertNotEquals("INVALID_TIMESTAMP", errorCode, "Valid time spec should not be invalid: " + timeSpec);
    }
  }

  @Test
  void validIsoTimestamp_accepted() throws Exception {
    startServer(new MockRrdStore());
    String validIso = Instant.now().minusSeconds(3600).toString();

    HttpResponse<String> resp = fetchRaw("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=" + validIso);
    JsonNode json = MAPPER.readTree(resp.body());

    if (resp.statusCode() == 400) {
      String errorCode = json.get("errorCode").asText();
      assertNotEquals("INVALID_TIMESTAMP", errorCode, "Valid ISO timestamp should be accepted");
    }
  }

  @Test
  void nowKeyword_accepted() throws Exception {
    startServer(new MockRrdStore());

    HttpResponse<String> resp = fetchRaw("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&end=now");
    JsonNode json = MAPPER.readTree(resp.body());

    if (resp.statusCode() == 400) {
      String errorCode = json.get("errorCode").asText();
      assertNotEquals("INVALID_TIMESTAMP", errorCode, "'now' keyword should be accepted");
    }
  }

  // ------ Response Metadata Tests (M5) ------

  @Test
  void successResponse_includesMetadata() throws Exception {
    MockRrdStore store = new MockRrdStore();
    store.setQueryResult(new RrdMetricStore.TimeSeriesResult("head.http.inflight_requests", new long[]{1000}, new double[]{5.0}));
    startServer(store);

    JsonNode json = fetch("/api/debug/metrics/timeseries?metric=head.http.inflight_requests&start=-1h", 200);

    assertEquals("head.http.inflight_requests", json.get("metric").asText());
    assertTrue(json.has("queryStart"), "M5: Response should include queryStart");
    assertTrue(json.has("queryEnd"), "M5: Response should include queryEnd");
    assertTrue(json.has("stepSeconds"), "M5: Response should include stepSeconds");
    assertEquals(60, json.get("stepSeconds").asInt(), "Step should be 60 seconds");
    assertTrue(json.has("timestamps"), "Response should include timestamps");
    assertTrue(json.has("values"), "Response should include values");
  }

  // ------ Available Endpoint Tests ------

  @Test
  void available_returnsCuratedMetrics() throws Exception {
    startServer(new MockRrdStore());

    JsonNode json = fetch("/api/debug/metrics/timeseries/available", 200);

    assertTrue(json.has("metrics"), "Should have metrics array");
    assertTrue(json.get("metrics").isArray(), "metrics should be an array");
    assertTrue(json.get("metrics").size() > 0, "Should have at least one metric");
  }

  // ------ Mock RrdMetricStore ------

  /**
   * Mock RrdMetricStore for testing controller behavior without real RRD database.
   */
  private static class MockRrdStore extends RrdMetricStore {
    private RrdMetricStore.TimeSeriesResult queryResult;

    MockRrdStore() {
      super(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")));
      // Don't call initialize() - we're mocking
    }

    void setQueryResult(RrdMetricStore.TimeSeriesResult result) {
      this.queryResult = result;
    }

    @Override
    public Set<String> getCuratedMetrics() {
      return Set.of(
          "head.http.inflight_requests",
          "head.jvm.memory.heap.used_bytes",
          "worker.documents.indexed.total"
      );
    }

    @Override
    public synchronized TimeSeriesResult query(String metricName, long startEpochSeconds, long endEpochSeconds) {
      if (queryResult != null) {
        return queryResult;
      }
      // Return empty result by default
      return new TimeSeriesResult(metricName, new long[0], new double[0]);
    }
  }
}
