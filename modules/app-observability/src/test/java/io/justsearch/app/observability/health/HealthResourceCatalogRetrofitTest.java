package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.Resource;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the §B.6 retrofit values that landed via slice 444a Phase 2 (per the §B.C "Phase 4
 * collapse" note). Each assertion catches a specific drift the
 * {@link io.justsearch.app.services.registry.validator.ResourceAreaValidator} can't —
 * e.g., a capacity of 200 vs 500 both pass the validator's structural rules, but only 200
 * matches the spec.
 *
 * <p>Sister to {@link HealthResourceCatalogTest} (which covers basic shape: presence,
 * SubscriptionMode, endpoint format). This test focuses exclusively on the Phase 2
 * retrofit values.
 */
@DisplayName("HealthResourceCatalog retrofit (slice 444a §B.6 + §B.A.1)")
final class HealthResourceCatalogRetrofitTest {

  private static Resource entry() {
    return new HealthResourceCatalog().definitions().get(0);
  }

  private static HistoryPolicy policy() {
    return entry().history().orElseThrow();
  }

  @Test
  @DisplayName("category is EVENT_STREAM")
  void categoryIsEventStream() {
    assertSame(Category.EVENT_STREAM, entry().category());
  }

  @Test
  @DisplayName("history is present (EVENT_STREAM Categories require HistoryPolicy)")
  void historyPolicyPresent() {
    assertTrue(entry().history().isPresent(), "EVENT_STREAM Resource must declare HistoryPolicy");
  }

  @Test
  @DisplayName("history mode is RING_BUFFER (matches OccurrenceLog implementation)")
  void historyPolicyModeIsRingBuffer() {
    assertSame(HistoryPolicy.Mode.RING_BUFFER, policy().mode());
  }

  @Test
  @DisplayName("history capacity matches OccurrenceLog.DEFAULT_CAPACITY (lockstep pin)")
  void historyPolicyCapacityMatchesOccurrenceLogDefault() {
    int declared = policy().capacity().orElseThrow();
    assertEquals(
        OccurrenceLog.DEFAULT_CAPACITY,
        declared,
        "HealthResourceCatalog.OCCURRENCE_BUFFER_CAPACITY must equal "
            + "OccurrenceLog.DEFAULT_CAPACITY; if either changes without the other, "
            + "the wire-declared retention diverges from the actual in-memory retention "
            + "(per slice 444a §B.C deviation note).");
  }

  @Test
  @DisplayName("history retention is empty (RING_BUFFER doesn't use retention)")
  void historyPolicyRetentionEmpty() {
    assertTrue(policy().retention().isEmpty());
  }

  @Test
  @DisplayName("history onOverflow is EVICT_OLDEST")
  void historyPolicyOnOverflowIsEvictOldest() {
    assertSame(OnOverflow.EVICT_OLDEST, policy().onOverflow());
  }

  @Test
  @DisplayName("history resumeWindow is 5 minutes (per §B.6)")
  void historyPolicyResumeWindowIsFiveMinutes() {
    assertEquals(Duration.ofMinutes(5), policy().resumeWindow());
  }

  @Test
  @DisplayName("recovery is empty (per §B.A.1: per-event recovery is body-level via slice 438)")
  void recoveryIsEmpty() {
    assertTrue(
        entry().recovery().isEmpty(),
        "HealthEvent's heterogeneous catalog has no singular per-Resource recovery; "
            + "per-event recoveries (one Operation per HealthEvent id) live at the body "
            + "level via slice 438. Per slice 444a §B.A.1.");
  }

  @Test
  @DisplayName("OCCURRENCE_BUFFER_CAPACITY constant is 200 (pinned)")
  void occurrenceBufferCapacityConstantPinned() {
    assertEquals(200, HealthResourceCatalog.OCCURRENCE_BUFFER_CAPACITY);
  }

  @Test
  @DisplayName("RESUME_WINDOW constant is 5 minutes (pinned)")
  void resumeWindowConstantPinned() {
    assertEquals(Duration.ofMinutes(5), HealthResourceCatalog.RESUME_WINDOW);
  }
}
