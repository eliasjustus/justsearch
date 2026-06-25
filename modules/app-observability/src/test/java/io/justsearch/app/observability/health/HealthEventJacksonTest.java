package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationInvocation;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round-trip serialization tests for the sealed {@link HealthEventBody} discriminator.
 *
 * <p>Verifies the Jackson 3 {@code @JsonTypeInfo}/{@code @JsonSubTypes} configuration on
 * {@link HealthEventBody} produces structurally-discriminated JSON and round-trips
 * preserve variant identity. Also verifies the {@link UnknownEventBody} default-subtype
 * forward-compat path: an unknown {@code kind} discriminator deserializes into
 * {@code UnknownEventBody} with the original kind preserved (per tempdoc 430 §B.F).
 */
@DisplayName("HealthEvent Jackson round-trip")
final class HealthEventJacksonTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static HealthEvent sample(HealthEventBody body, String id) {
    return new HealthEvent(
        id,
        Instant.parse("2026-04-30T12:00:00Z"),
        Source.forProcess("worker", "instance-1", "1.0"),
        Severity.WARNING,
        Optional.of("health-events." + id + ".message"),
        body);
  }

  @Test
  @DisplayName("LifecycleEvent round-trips with kind=lifecycle")
  void lifecycleRoundTrip() throws Exception {
    HealthEvent original =
        sample(LifecycleEvent.of("session_id", "abc-123"), "agent.session.completed");
    String json = MAPPER.writeValueAsString(original);
    assertTrue(json.contains("\"kind\":\"lifecycle\""), () -> "kind discriminator missing: " + json);

    HealthEvent decoded = MAPPER.readValue(json, HealthEvent.class);
    assertInstanceOf(LifecycleEvent.class, decoded.body());
    assertEquals(original, decoded);
  }

  @Test
  @DisplayName("AssertedCondition round-trips with kind=condition")
  void conditionRoundTrip() throws Exception {
    HealthEvent original =
        sample(
            new AssertedCondition(
                "worker",
                ConditionStatus.FALSE,
                "WorkerStarting",
                Instant.parse("2026-04-30T11:59:00Z"),
                Optional.of("Worker starting up."),
                Optional.empty(),
                java.util.List.of()),
            "index.unavailable");
    String json = MAPPER.writeValueAsString(original);
    assertTrue(json.contains("\"kind\":\"condition\""), () -> "kind discriminator missing: " + json);

    HealthEvent decoded = MAPPER.readValue(json, HealthEvent.class);
    assertInstanceOf(AssertedCondition.class, decoded.body());
    AssertedCondition decodedCondition = (AssertedCondition) decoded.body();
    assertEquals("worker", decodedCondition.subject());
    assertEquals(ConditionStatus.FALSE, decodedCondition.status());
    assertEquals("WorkerStarting", decodedCondition.reason());
  }

  @Test
  @DisplayName("ThresholdState round-trips with kind=threshold")
  void thresholdRoundTrip() throws Exception {
    HealthEvent original =
        sample(
            new ThresholdState(
                "head.gpu",
                ThresholdPhase.FIRING,
                Map.of("utilization_pct", 87),
                Instant.parse("2026-04-30T11:58:00Z"),
                Optional.empty(),
                Optional.empty(),
                java.util.List.of()),
            "gpu.saturated");
    String json = MAPPER.writeValueAsString(original);
    assertTrue(json.contains("\"kind\":\"threshold\""), () -> "kind discriminator missing: " + json);

    HealthEvent decoded = MAPPER.readValue(json, HealthEvent.class);
    assertInstanceOf(ThresholdState.class, decoded.body());
    ThresholdState decodedThreshold = (ThresholdState) decoded.body();
    assertEquals(ThresholdPhase.FIRING, decodedThreshold.phase());
    assertEquals(87, ((Number) decodedThreshold.magnitudes().get("utilization_pct")).intValue());
  }

  @Test
  @DisplayName("Unknown body kind preserves the original wire kind string for diagnostic")
  void unknownKindPreservesOriginalKindString() throws Exception {
    String wireJson =
        """
        {
          "id": "future.event",
          "timestamp": "2026-04-30T12:00:00Z",
          "source": {"serviceName": "head", "serviceInstanceId": "instance-1"},
          "severity": "INFO",
          "i18nKey": null,
          "body": {
            "kind": "stamped-summary",
            "extraField": 42,
            "nested": {"foo": "bar"}
          }
        }
        """;
    HealthEvent decoded = MAPPER.readValue(wireJson, HealthEvent.class);
    assertInstanceOf(UnknownEventBody.class, decoded.body());
    UnknownEventBody unknown = (UnknownEventBody) decoded.body();

    // The custom deserializer's contract: original wire kind is preserved verbatim, NOT
    // collapsed to the "unknown" fallback. This is the diagnostic the deserializer
    // exists to provide — without this assertion, a regression that drops the wire
    // string and returns the fallback would slip through unchecked.
    assertEquals("stamped-summary", unknown.kind());

    // The full body subtree is captured in raw() for downstream logging / inspection.
    assertEquals(42, unknown.raw().get("extraField").asInt());
    assertEquals("bar", unknown.raw().get("nested").get("foo").asString());
  }

  // Slice 438 §B.B + 447-impl-B: the substrate's `recovery` field (was
  // `recoveryOperationId` pre-impl-B) is exercised across all three body kinds with a
  // populated OperationInvocation. Wire shape is now an object
  // {target: "...", defaultArgsJson: "..."} (impl-B widening) rather than a bare string.

  @Test
  @DisplayName("AssertedCondition with populated recovery round-trips as object")
  void conditionRecoveryRoundTrips() throws Exception {
    OperationInvocation recovery = OperationInvocation.of(new OperationRef("core.rebuild-index"));
    HealthEvent original =
        sample(
            new AssertedCondition(
                "worker",
                ConditionStatus.FALSE,
                "IndexCorrupted",
                Instant.parse("2026-04-30T11:59:00Z"),
                Optional.empty(),
                Optional.of(recovery),
                java.util.List.of()),
            "index.unavailable");
    String json = MAPPER.writeValueAsString(original);
    assertTrue(
        json.contains("\"recovery\":{\"target\":\"core.rebuild-index\""),
        () -> "recovery should serialize as object with target + defaultArgsJson: " + json);

    HealthEvent decoded = MAPPER.readValue(json, HealthEvent.class);
    AssertedCondition decodedBody = (AssertedCondition) decoded.body();
    assertEquals(Optional.of(recovery), decodedBody.recovery());
  }

  @Test
  @DisplayName("ThresholdState with populated recovery round-trips as object")
  void thresholdRecoveryRoundTrips() throws Exception {
    OperationInvocation recovery =
        OperationInvocation.of(new OperationRef("core.reindex-recommended"));
    HealthEvent original =
        sample(
            new ThresholdState(
                "head.gpu",
                ThresholdPhase.FIRING,
                Map.of("utilization_pct", 87),
                Instant.parse("2026-04-30T11:58:00Z"),
                Optional.empty(),
                Optional.of(recovery),
                java.util.List.of()),
            "gpu.saturated");
    String json = MAPPER.writeValueAsString(original);
    assertTrue(
        json.contains("\"recovery\":{\"target\":\"core.reindex-recommended\""),
        () -> "recovery should serialize as object with target + defaultArgsJson: " + json);

    HealthEvent decoded = MAPPER.readValue(json, HealthEvent.class);
    ThresholdState decodedBody = (ThresholdState) decoded.body();
    assertEquals(Optional.of(recovery), decodedBody.recovery());
  }

  @Test
  @DisplayName("LifecycleEvent with populated recovery round-trips as object")
  void lifecycleRecoveryRoundTrips() throws Exception {
    OperationInvocation recovery = OperationInvocation.of(new OperationRef("core.retry-job"));
    HealthEvent original =
        sample(
            new LifecycleEvent(Map.of("seq", 1L), Optional.of(recovery)),
            "worker.job.failed");
    String json = MAPPER.writeValueAsString(original);
    assertTrue(
        json.contains("\"recovery\":{\"target\":\"core.retry-job\""),
        () -> "recovery should serialize as object with target + defaultArgsJson: " + json);

    HealthEvent decoded = MAPPER.readValue(json, HealthEvent.class);
    LifecycleEvent decodedBody = (LifecycleEvent) decoded.body();
    assertEquals(Optional.of(recovery), decodedBody.recovery());
  }

  @Test
  @DisplayName("AssertedCondition recovery with non-empty defaultArgsJson preserves args")
  void conditionRecoveryArgsBearingRoundTrips() throws Exception {
    OperationInvocation recovery =
        new OperationInvocation(new OperationRef("core.reindex"), "{\"force\":true}");
    HealthEvent original =
        sample(
            new AssertedCondition(
                "worker.schema",
                ConditionStatus.TRUE,
                "ReindexRequired",
                Instant.parse("2026-05-08T01:00:00Z"),
                Optional.empty(),
                Optional.of(recovery),
                java.util.List.of()),
            "schema.reindex-required");
    String json = MAPPER.writeValueAsString(original);
    assertTrue(
        json.contains("\"defaultArgsJson\":\"{\\\"force\\\":true}\""),
        () -> "defaultArgsJson should serialize as JSON string literal: " + json);

    HealthEvent decoded = MAPPER.readValue(json, HealthEvent.class);
    AssertedCondition decodedBody = (AssertedCondition) decoded.body();
    assertEquals(Optional.of(recovery), decodedBody.recovery());
  }
}
