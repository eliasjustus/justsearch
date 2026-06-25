package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationInvocation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 447 §X.11.5 Phase 5 — agent retrospection: verify
 * {@link HeadAssembly#renderConditionContext} produces the expected
 * natural-language block the AgentLoopService prepends to the system prompt.
 */
@DisplayName("renderConditionContext (slice 447 §X.11.5 Phase 5)")
final class RenderConditionContextTest {

  private static final Source SRC = Source.forProcess("test", "test-instance", "1.0");

  @Test
  @DisplayName("empty store renders empty string (no conditions, no section)")
  void emptyStore() {
    ConditionStore store = new ConditionStore();
    assertEquals("", io.justsearch.app.services.bootstrap.phases.BootstrapProjections.renderConditionContext(store));
  }

  @Test
  @DisplayName("null store renders empty string (defensive)")
  void nullStore() {
    assertEquals("", io.justsearch.app.services.bootstrap.phases.BootstrapProjections.renderConditionContext(null));
  }

  @Test
  @DisplayName("conditions without recovery are omitted from the rendered block")
  void noRecoveryOmitted() {
    ConditionStore store = new ConditionStore();
    store.upsert(condition("schema.reindex-required", "worker.schema", Optional.empty()));
    assertEquals("", io.justsearch.app.services.bootstrap.phases.BootstrapProjections.renderConditionContext(store));
  }

  @Test
  @DisplayName("conditions with recovery render as bulleted lines")
  void rendersBulletedLines() {
    ConditionStore store = new ConditionStore();
    OperationInvocation reindexRecovery =
        new OperationInvocation(new OperationRef("core.reindex"), "{\"force\":true}");
    OperationInvocation rebuildRecovery =
        OperationInvocation.of(new OperationRef("core.rebuild-index"));
    store.upsert(
        condition("schema.reindex-required", "worker.schema", Optional.of(reindexRecovery)));
    store.upsert(condition("index.unavailable", "worker", Optional.of(rebuildRecovery)));

    String text = io.justsearch.app.services.bootstrap.phases.BootstrapProjections.renderConditionContext(store);
    assertTrue(text.startsWith("Currently asserted conditions"));
    assertTrue(text.contains("schema.reindex-required"));
    assertTrue(text.contains("worker.schema"));
    assertTrue(text.contains("core.reindex"));
    assertTrue(text.contains("index.unavailable"));
    assertTrue(text.contains("core.rebuild-index"));
    assertTrue(text.contains("severity=WARNING"));
    assertTrue(
        text.contains(
            "When the user asks how to address these issues, reference the recommended"));
  }

  @Test
  @DisplayName("rendering is stable: same store produces same output across calls")
  void renderingIsStable() {
    ConditionStore store = new ConditionStore();
    OperationInvocation rec = OperationInvocation.of(new OperationRef("core.reindex"));
    store.upsert(condition("c1", "subj", Optional.of(rec)));
    String first = io.justsearch.app.services.bootstrap.phases.BootstrapProjections.renderConditionContext(store);
    String second = io.justsearch.app.services.bootstrap.phases.BootstrapProjections.renderConditionContext(store);
    assertEquals(first, second);
    assertFalse(first.isBlank());
  }

  private static HealthEvent condition(
      String id, String subject, Optional<OperationInvocation> recovery) {
    return new HealthEvent(
        id,
        Instant.parse("2026-05-08T12:00:00Z"),
        SRC,
        Severity.WARNING,
        Optional.of("health-events." + id + ".message"),
        new AssertedCondition(
            subject,
            ConditionStatus.TRUE,
            "TestReason",
            Instant.parse("2026-05-08T12:00:00Z"),
            Optional.empty(),
            recovery,
            List.of()));
  }
}
