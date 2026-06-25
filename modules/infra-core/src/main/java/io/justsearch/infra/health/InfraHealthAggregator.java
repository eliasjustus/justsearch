/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.infra.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Aggregates subsystem health signals into a single infra status snapshot. */
public final class InfraHealthAggregator {
  private final Duration pollInterval;
  private final Duration nrtThreshold;
  private final Duration translatorThreshold;
  private final int annReadyPercent;
  private final Clock clock;

  public InfraHealthAggregator(Config cfg) {
    this(cfg, Clock.systemUTC());
  }

  public InfraHealthAggregator(Config cfg, Clock clock) {
    Objects.requireNonNull(cfg, "cfg");
    this.pollInterval = cfg.pollInterval();
    this.nrtThreshold = cfg.nrtStaleThreshold();
    this.translatorThreshold = cfg.translatorHandshakeStaleThreshold();
    this.annReadyPercent = cfg.annCacheReadyPercent();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Duration pollInterval() {
    return pollInterval;
  }

  public Snapshot evaluate(Inputs inputs) {
    Objects.requireNonNull(inputs, "inputs");
    Instant now = clock.instant();
    List<ComponentHealth> components = new ArrayList<>();
    components.add(evaluateNrt(inputs));
    components.add(evaluateTranslator(inputs, now));
    components.add(evaluateAnn(inputs));
    if (!inputs.configValid()) {
      components.add(
          new ComponentHealth(
              "config",
              Status.DEGRADED,
              "config_invalid",
              Map.of("config_valid", false)));
    }
    Status overall = components.stream().map(ComponentHealth::status).max(Status::compareTo).orElse(Status.HEALTHY);
    return new Snapshot(overall, List.copyOf(components), now);
  }

  private ComponentHealth evaluateNrt(Inputs inputs) {
    Long lagMs = inputs.nrtLagMs();
    if (lagMs == null) {
      return new ComponentHealth(
          "indexing", Status.DEGRADED, "nrt_stale", Map.of("nrt_lag_ms", "unknown"));
    }
    Status status = statusForThreshold(lagMs, nrtThreshold.toMillis());
    String reason = status == Status.HEALTHY ? null : "nrt_stale";
    return new ComponentHealth("indexing", status, reason, Map.of("nrt_lag_ms", lagMs));
  }

  private ComponentHealth evaluateTranslator(Inputs inputs, Instant now) {
    Instant handshake = inputs.translatorHandshakeAt();
    if (handshake == null) {
      return new ComponentHealth(
          "translator",
          Status.CRITICAL,
          "translator_handshake_stale",
          Map.of("handshake_age_ms", "unknown"));
    }
    long ageMs = Duration.between(handshake, now).toMillis();
    Status status = statusForThreshold(ageMs, translatorThreshold.toMillis());
    String reason = status == Status.HEALTHY ? null : "translator_handshake_stale";
    return new ComponentHealth("translator", status, reason, Map.of("handshake_age_ms", ageMs));
  }

  private ComponentHealth evaluateAnn(Inputs inputs) {
    Integer ready = inputs.annCacheReadyPercent();
    if (ready == null) {
      return new ComponentHealth(
          "ann_cache", Status.DEGRADED, "ann_cache_cold", Map.of("ready_percent", "unknown"));
    }
    Status status;
    if (ready <= 0) {
      status = Status.CRITICAL;
    } else if (ready < annReadyPercent) {
      status = Status.DEGRADED;
    } else {
      status = Status.HEALTHY;
    }
    String reason = status == Status.HEALTHY ? null : "ann_cache_cold";
    return new ComponentHealth("ann_cache", status, reason, Map.of("ready_percent", ready));
  }

  private static Status statusForThreshold(long measurement, long threshold) {
    if (measurement <= threshold) {
      return Status.HEALTHY;
    }
    if (measurement > threshold * 2) {
      return Status.CRITICAL;
    }
    return Status.DEGRADED;
  }

  public enum Status {
    HEALTHY,
    DEGRADED,
    CRITICAL
  }

  public record ComponentHealth(String componentId, Status status, String reasonCode, Map<String, ?> metrics) {}

  public record Snapshot(Status status, List<ComponentHealth> components, Instant generatedAt) {}

  /** Immutable configuration inputs derived from SSOT config. */
  public record Config(
      Duration pollInterval,
      Duration nrtStaleThreshold,
      Duration translatorHandshakeStaleThreshold,
      int annCacheReadyPercent) {

    public Config {
      Objects.requireNonNull(pollInterval, "pollInterval");
      Objects.requireNonNull(nrtStaleThreshold, "nrtStaleThreshold");
      Objects.requireNonNull(translatorHandshakeStaleThreshold, "translatorHandshakeStaleThreshold");
      if (annCacheReadyPercent < 0 || annCacheReadyPercent > 100) {
        throw new IllegalArgumentException(
            "annCacheReadyPercent must be between 0 and 100 (was " + annCacheReadyPercent + ")");
      }
    }
  }

  public static final class Inputs {
    private final Long nrtLagMs;
    private final Instant translatorHandshakeAt;
    private final Integer annCacheReadyPercent;
    private final boolean configValid;

    private Inputs(
        Long nrtLagMs, Instant translatorHandshakeAt, Integer annCacheReadyPercent, boolean configValid) {
      this.nrtLagMs = nrtLagMs;
      this.translatorHandshakeAt = translatorHandshakeAt;
      this.annCacheReadyPercent = annCacheReadyPercent;
      this.configValid = configValid;
    }

    public static Inputs of(
        Long nrtLagMs, Instant translatorHandshakeAt, Integer annCacheReadyPercent, boolean configValid) {
      return new Inputs(nrtLagMs, translatorHandshakeAt, annCacheReadyPercent, configValid);
    }

    public Long nrtLagMs() {
      return nrtLagMs;
    }

    public Instant translatorHandshakeAt() {
      return translatorHandshakeAt;
    }

    public Integer annCacheReadyPercent() {
      return annCacheReadyPercent;
    }

    public boolean configValid() {
      return configValid;
    }
  }
}
