package io.justsearch.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.infra.health.InfraHealthAggregator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InfraDiagnosticsServiceTest {

  @Test
  void currentPayloadUsesSuppliedValues() {
    InfraDiagnosticsService service = new InfraDiagnosticsService(defaultConfig());
    AtomicBoolean configChecked = new AtomicBoolean(false);
    service.setNrtLagSupplier(() -> 10L);
    service.setTranslatorHandshakeSupplier(() -> Instant.now().minusSeconds(2));
    service.setAnnReadySupplier(() -> 90);
    service.setConfigValidSupplier(
        () -> {
          configChecked.set(true);
          return true;
        });
    service.setMetadataSupplier(() -> Map.of("source", "test"));

    InfraDiagnosticsService.InfraHealthPayload payload = service.currentPayload();
    assertEquals("healthy", payload.status());
    assertEquals("test", payload.metadata().get("source"));
    assertTrue(configChecked.get(), "Config supplier should be invoked");
    assertEquals(3, payload.components().size());
    assertTrue(
        payload.components().stream().allMatch(component -> component.status().equals("healthy")),
        "All components should be healthy");
  }

  @Test
  void currentPayloadHandlesExceptionalSuppliers() {
    InfraDiagnosticsService service = new InfraDiagnosticsService(defaultConfig());
    service.setNrtLagSupplier(() -> {
      throw new IllegalStateException("boom");
    });
    service.setTranslatorHandshakeSupplier(() -> null);
    service.setAnnReadySupplier(() -> null);
    service.setConfigValidSupplier(() -> false);
    service.setMetadataSupplier(() -> null);

    InfraDiagnosticsService.InfraHealthPayload payload = service.currentPayload();
    assertEquals("critical", payload.status());
    assertTrue(payload.metadata().isEmpty());
    assertEquals(
        "critical",
        payload.components().stream()
            .filter(component -> component.componentId().equals("translator"))
            .findFirst()
            .orElseThrow()
            .status());
  }

  @Test
  void updateConfigReplacesAggregatorThresholds() {
    InfraDiagnosticsService service = new InfraDiagnosticsService(defaultConfig());
    service.setNrtLagSupplier(() -> 25L);
    service.setTranslatorHandshakeSupplier(() -> Instant.now());
    service.setAnnReadySupplier(() -> 60);

    String baseline = service.currentPayload().status();
    assertEquals("healthy", baseline);

    InfraHealthAggregator.Config stricter =
        new InfraHealthAggregator.Config(
            Duration.ofSeconds(1), Duration.ofMillis(20), Duration.ofMillis(20), 80);
    service.updateConfig(stricter);
    InfraDiagnosticsService.InfraHealthPayload payload = service.currentPayload();
    assertEquals("degraded", payload.status());
    assertTrue(
        payload.components().stream()
            .anyMatch(component -> component.componentId().equals("ann_cache") && component.status().equals("degraded")));
  }

  @Test
  void setterFallbacksUseDefaultsWhenNull() {
    InfraDiagnosticsService service = new InfraDiagnosticsService(defaultConfig());
    service.setNrtLagSupplier(null);
    service.setTranslatorHandshakeSupplier(null);
    service.setAnnReadySupplier(null);
    service.setConfigValidSupplier(null);
    service.setMetadataSupplier(null);
    InfraDiagnosticsService.InfraHealthPayload payload = service.currentPayload();
    assertNotNull(payload);
    assertFalse(payload.components().isEmpty());
  }

  private InfraHealthAggregator.Config defaultConfig() {
    return new InfraHealthAggregator.Config(
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(120), 50);
  }
}
