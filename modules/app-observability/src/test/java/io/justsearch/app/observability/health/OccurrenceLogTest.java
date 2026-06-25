package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OccurrenceLog")
final class OccurrenceLogTest {

  private static final Source SRC = Source.forProcess("worker", "instance-1", "1.0");

  private static HealthEvent occurrence(int seq) {
    return new HealthEvent(
        "worker.job.failed",
        Instant.parse("2026-04-30T12:00:00Z").plusSeconds(seq),
        SRC,
        Severity.INFO,
        Optional.of("health-events.worker.job.failed.message"),
        new LifecycleEvent(Map.of("seq", seq), Optional.empty()));
  }

  @Test
  @DisplayName("default capacity is 200")
  void defaultCapacity() {
    OccurrenceLog log = new OccurrenceLog();
    assertEquals(200, log.capacity());
  }

  @Test
  @DisplayName("ring buffer evicts oldest beyond capacity")
  void evictsOldest() {
    OccurrenceLog log = new OccurrenceLog(3);
    log.append(occurrence(0));
    log.append(occurrence(1));
    log.append(occurrence(2));
    log.append(occurrence(3));
    List<HealthEvent> recent = log.recent();
    assertEquals(3, recent.size());
    assertEquals(1, ((Number) ((LifecycleEvent) recent.get(0).body()).attributes().get("seq")).intValue());
    assertEquals(3, ((Number) ((LifecycleEvent) recent.get(2).body()).attributes().get("seq")).intValue());
  }

  @Test
  @DisplayName("recent(n) returns last n in chronological order")
  void recentNReturnsTrailingWindow() {
    OccurrenceLog log = new OccurrenceLog(10);
    for (int i = 0; i < 5; i++) {
      log.append(occurrence(i));
    }
    List<HealthEvent> last3 = log.recent(3);
    assertEquals(3, last3.size());
    assertEquals(2, ((Number) ((LifecycleEvent) last3.get(0).body()).attributes().get("seq")).intValue());
    assertEquals(4, ((Number) ((LifecycleEvent) last3.get(2).body()).attributes().get("seq")).intValue());
  }

  @Test
  @DisplayName("recent(n) where n >= size returns full retained set")
  void recentNLargeReturnsAll() {
    OccurrenceLog log = new OccurrenceLog(10);
    log.append(occurrence(0));
    log.append(occurrence(1));
    assertEquals(2, log.recent(99).size());
  }

  @Test
  @DisplayName("zero capacity is rejected")
  void zeroCapacityRejected() {
    assertThrows(IllegalArgumentException.class, () -> new OccurrenceLog(0));
  }

  @Test
  @DisplayName("negative recent(n) is rejected")
  void negativeNRejected() {
    OccurrenceLog log = new OccurrenceLog(10);
    assertThrows(IllegalArgumentException.class, () -> log.recent(-1));
  }
}
