package io.justsearch.app.observability.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.EmissionPolicy;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.RenderHint;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.observability.operations.PendingAuthorizationEvent;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PendingAuthorizationAdvisoryProjector")
final class PendingAuthorizationAdvisoryProjectorTest {

  private static final Instant T0 = Instant.parse("2026-07-02T10:00:00Z");

  private final PendingAuthorizationAdvisoryProjector projector =
      new PendingAuthorizationAdvisoryProjector();

  private static PendingAuthorizationEvent event(String pendingId) {
    return new PendingAuthorizationEvent(
        pendingId,
        "core.ingest-files",
        SourceTier.UNTRUSTED,
        RiskTier.MEDIUM,
        GateBehavior.TYPED_CONFIRM,
        T0,
        T0.plusSeconds(300));
  }

  @Test
  @DisplayName("classId is authorization.pending")
  void classId() {
    assertEquals(AdvisoryClassId.of("authorization.pending"), projector.classId());
  }

  @Test
  @DisplayName("emission policy is REQUIRES_ACK")
  void emissionPolicyIsRequiresAck() {
    assertEquals(RenderHint.REQUIRES_ACK, projector.emissionPolicy().renderHint());
    assertEquals(EmissionPolicy.requiresAck(), projector.emissionPolicy());
  }

  @Test
  @DisplayName("every gate-firing projects (no filtering)")
  void everyEventProjects() {
    var result = projector.project(event("pa-abc123"));

    assertTrue(result.isPresent());
    AdvisoryProjection p = result.get();
    assertEquals(T0, p.occurredAt());
    assertEquals("pa-abc123", p.classExtras().get("pendingId"));
    assertEquals("core.ingest-files", p.classExtras().get("operationId"));
    assertEquals("MEDIUM", p.classExtras().get("riskTier"));
    assertEquals("TYPED_CONFIRM", p.classExtras().get("gateBehavior"));
  }

  @Test
  @DisplayName("classExtras never carries requestedBy (event withholds it by design)")
  void neverCarriesRequestedBy() {
    var result = projector.project(event("pa-abc123"));

    assertTrue(result.isPresent());
    assertTrue(!result.get().classExtras().containsKey("requestedBy"));
  }

  @Test
  @DisplayName("dedupKey is the pendingId itself")
  void dedupKeyIsPendingId() {
    assertEquals("pa-abc123", projector.dedupKey(event("pa-abc123")));
    assertEquals("pa-xyz789", projector.dedupKey(event("pa-xyz789")));
  }

  @Test
  @DisplayName("dedupKey is idempotent")
  void dedupKeyIdempotent() {
    var ev = event("pa-abc123");
    assertEquals(projector.dedupKey(ev), projector.dedupKey(ev));
  }
}
