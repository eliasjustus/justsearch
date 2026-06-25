package io.justsearch.infra.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InfraHealthAggregatorTest {
  private static InfraHealthAggregator.Config defaultCfg() {
    return new InfraHealthAggregator.Config(
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), 75);
  }

  @Test
  void aggregatesHealthySignals() {
    Instant now = Instant.parse("2025-01-01T00:00:00Z");
    InfraHealthAggregator aggregator =
        new InfraHealthAggregator(defaultCfg(), Clock.fixed(now, ZoneOffset.UTC));
    InfraHealthAggregator.Inputs inputs =
        InfraHealthAggregator.Inputs.of(10_000L, now.minusSeconds(30), 90, true);
    InfraHealthAggregator.Snapshot snapshot = aggregator.evaluate(inputs);
    assertEquals(InfraHealthAggregator.Status.HEALTHY, snapshot.status());
    assertEquals(3, snapshot.components().size());
    assertEquals(now, snapshot.generatedAt());
  }

  @Test
  void degradesWhenThresholdsExceeded() {
    Instant now = Instant.parse("2025-01-01T00:10:00Z");
    InfraHealthAggregator aggregator =
        new InfraHealthAggregator(defaultCfg(), Clock.fixed(now, ZoneOffset.UTC));
    InfraHealthAggregator.Inputs inputs =
        InfraHealthAggregator.Inputs.of(45_000L, now.minusSeconds(190), 60, true);
    InfraHealthAggregator.Snapshot snapshot = aggregator.evaluate(inputs);
    assertEquals(InfraHealthAggregator.Status.DEGRADED, snapshot.status());
    assertEquals("nrt_stale", snapshot.components().get(0).reasonCode());
    assertEquals("translator_handshake_stale", snapshot.components().get(1).reasonCode());
    assertEquals("ann_cache_cold", snapshot.components().get(2).reasonCode());
  }

  @Test
  void marksCriticalWhenSignalsMissingOrSeverelyOutOfRange() {
    Instant now = Instant.parse("2025-01-01T00:20:00Z");
    InfraHealthAggregator aggregator =
        new InfraHealthAggregator(defaultCfg(), Clock.fixed(now, ZoneOffset.UTC));
    InfraHealthAggregator.Inputs inputs =
        InfraHealthAggregator.Inputs.of(null, null, 0, false);
    InfraHealthAggregator.Snapshot snapshot = aggregator.evaluate(inputs);
    assertEquals(InfraHealthAggregator.Status.CRITICAL, snapshot.status());
    assertNotNull(
        snapshot.components().stream()
            .filter(c -> "translator".equals(c.componentId()) && c.status() == InfraHealthAggregator.Status.CRITICAL)
            .findFirst()
            .orElse(null));
  }
}
