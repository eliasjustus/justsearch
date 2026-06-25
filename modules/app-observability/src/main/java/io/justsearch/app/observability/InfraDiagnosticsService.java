/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability;

import io.justsearch.configuration.Faults;
import io.justsearch.infra.health.InfraHealthAggregator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides snapshots of infrastructure health backed by {@link InfraHealthAggregator}. */
public final class InfraDiagnosticsService {
  private static final Logger LOG = LoggerFactory.getLogger(InfraDiagnosticsService.class);

  private final AtomicReference<InfraHealthAggregator> aggregatorRef;
  private final AtomicReference<Supplier<Long>> nrtLagSupplier = new AtomicReference<>(() -> null);
  private final AtomicReference<Supplier<Instant>> translatorHandshakeSupplier =
      new AtomicReference<>(() -> null);
  private final AtomicReference<Supplier<Integer>> annReadySupplier = new AtomicReference<>(() -> null);
  private final AtomicReference<BooleanSupplier> configValidSupplier =
      new AtomicReference<>(() -> true);
  private final AtomicReference<Supplier<Map<String, Object>>> metadataSupplier =
      new AtomicReference<>(() -> Map.of());

  public InfraDiagnosticsService(InfraHealthAggregator.Config config) {
    this(config, Map::of);
  }

  public InfraDiagnosticsService(
      InfraHealthAggregator.Config config, Supplier<Map<String, Object>> metadataSupplier) {
    this.aggregatorRef = new AtomicReference<>(new InfraHealthAggregator(config));
    setMetadataSupplier(metadataSupplier);
  }

  /** Updates aggregator thresholds when configuration changes. */
  public void updateConfig(InfraHealthAggregator.Config config) {
    aggregatorRef.set(new InfraHealthAggregator(config));
  }

  public void setNrtLagSupplier(Supplier<Long> supplier) {
    nrtLagSupplier.set(supplier != null ? supplier : () -> null);
  }

  public void setTranslatorHandshakeSupplier(Supplier<Instant> supplier) {
    translatorHandshakeSupplier.set(supplier != null ? supplier : () -> null);
  }

  public void setAnnReadySupplier(Supplier<Integer> supplier) {
    annReadySupplier.set(supplier != null ? supplier : () -> null);
  }

  public void setConfigValidSupplier(BooleanSupplier supplier) {
    configValidSupplier.set(supplier != null ? supplier : () -> true);
  }

  public void setMetadataSupplier(Supplier<Map<String, Object>> supplier) {
    metadataSupplier.set(supplier != null ? supplier : Map::of);
  }

  /** Returns the latest health payload built from current suppliers. */
  public InfraHealthPayload currentPayload() {
    InfraHealthAggregator aggregator = aggregatorRef.get();
    InfraHealthAggregator.Inputs inputs =
        InfraHealthAggregator.Inputs.of(
            Faults.logAndFallback(LOG, "NRT lag supplier", () -> nrtLagSupplier.get().get(), null),
            Faults.logAndFallback(
                LOG, "translator handshake supplier",
                () -> translatorHandshakeSupplier.get().get(), null),
            Faults.logAndFallback(
                LOG, "ANN ready supplier", () -> annReadySupplier.get().get(), null),
            Faults.logAndFallback(
                LOG, "config validity supplier",
                () -> configValidSupplier.get().getAsBoolean(), false));
    InfraHealthAggregator.Snapshot snapshot = aggregator.evaluate(inputs);
    return toPayload(snapshot, metadataSupplier.get().get());
  }

  private static InfraHealthPayload toPayload(
      InfraHealthAggregator.Snapshot snapshot, Map<String, Object> metadata) {
    List<ComponentStatus> components = new ArrayList<>();
    snapshot.components().forEach(component -> {
      String status = component.status().name().toLowerCase(Locale.ROOT);
      components.add(
          new ComponentStatus(
              component.componentId(),
              status,
              component.reasonCode(),
              component.metrics()));
    });
    String topStatus = snapshot.status().name().toLowerCase(Locale.ROOT);
    String generatedAt = snapshot.generatedAt().toString();
    return new InfraHealthPayload(topStatus, generatedAt, List.copyOf(components), metadata);
  }

  /** JSON-friendly payload returned by the diagnostics endpoints. */
  public record InfraHealthPayload(
      String status, String generatedAt, List<ComponentStatus> components, Map<String, Object> metadata) {
    public InfraHealthPayload {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(generatedAt, "generatedAt");
      components = components == null ? List.of() : List.copyOf(components);
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  /** Component-level status summary. */
  public record ComponentStatus(
      String componentId, String status, String reasonCode, Map<String, ?> metrics) {}
}
