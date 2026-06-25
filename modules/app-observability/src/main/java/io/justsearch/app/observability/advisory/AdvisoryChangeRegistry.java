/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import io.justsearch.agent.api.registry.EmissionPolicy;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Central advisory broadcast registry. Replaces per-class registries (previously
 * {@code OperationCompletedAdvisoryChangeRegistry}) with a single registry holding
 * per-class dedup state.
 *
 * <p>Per slice 494 §7: the registry holds {@code Map<classId, Map<dedupKey, Instant>>}
 * dedup state. The {@link #project} method takes a projector + source event, computes
 * the projection + dedup key, stamps the advisory id, applies dedup-window suppression,
 * and broadcasts the stamped {@link AdvisoryRecord}.
 *
 * <p>Preserves the boolean-return contract from the previous registry: {@code true}
 * when broadcast actually published; {@code false} when dedup suppressed. Callers
 * coordinating downstream side-effects (log append, metrics) MUST gate on the return.
 *
 * <p>Each advisory class gets its own {@link SseStreamChannel} for per-class Resource
 * discovery (Q1=b per-class Resources). FE discovers advisory Resources via
 * {@code listResources(kind=KIND_ADVISORY)} and subscribes to each class's channel.
 */
public final class AdvisoryChangeRegistry {

  private final AdvisoryClassRegistry classRegistry;
  private final Map<AdvisoryClassId, SseStreamChannel> channels;
  private final Map<AdvisoryClassId, ConcurrentHashMap<String, Instant>> dedupeState;
  private final Clock clock;

  public AdvisoryChangeRegistry(AdvisoryClassRegistry classRegistry, Clock clock) {
    this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.channels = new ConcurrentHashMap<>();
    this.dedupeState = new ConcurrentHashMap<>();
    for (AdvisoryClassId classId : classRegistry.classIds()) {
      channels.put(classId, new SseStreamChannel(streamIdFor(classId)));
      dedupeState.put(classId, new ConcurrentHashMap<>());
    }
  }

  /** Convenience constructor with system-UTC clock. */
  public AdvisoryChangeRegistry(AdvisoryClassRegistry classRegistry) {
    this(classRegistry, Clock.systemUTC());
  }

  /**
   * Projects a source event through its projector, stamps the advisory id,
   * applies dedup-window suppression, and broadcasts to the class's channel.
   *
   * @return the stamped {@link AdvisoryRecord} if broadcast published; empty if
   *     filtered by the projector or dedup-suppressed. Callers coordinating
   *     downstream side-effects (log append, metrics) use the returned record.
   */
  public <E> Optional<AdvisoryRecord> project(AdvisoryProjector<E> projector, E sourceEvent) {
    Objects.requireNonNull(projector, "projector");
    Objects.requireNonNull(sourceEvent, "sourceEvent");

    Optional<AdvisoryProjection> projected = projector.project(sourceEvent);
    if (projected.isEmpty()) {
      return Optional.empty();
    }

    String dedupKey = projector.dedupKey(sourceEvent);
    Objects.requireNonNull(dedupKey, "dedupKey must not be null");

    AdvisoryClassId classId = projector.classId();
    String id = classId.value() + ":" + dedupKey;
    String renderHint = projector.emissionPolicy().renderHint().name();
    AdvisoryRecord record =
        AdvisoryRecord.fromProjection(classId, id, renderHint, projected.get());

    if (broadcast(classId, record, projector.emissionPolicy())) {
      return Optional.of(record);
    }
    return Optional.empty();
  }

  /**
   * Returns the SSE channel for a specific advisory class. Used by SSE controllers
   * to wire per-class stream endpoints.
   */
  public SseStreamChannel channel(AdvisoryClassId classId) {
    SseStreamChannel ch = channels.get(classId);
    if (ch == null) {
      throw new IllegalArgumentException("No channel for advisory class: " + classId);
    }
    return ch;
  }

  /** Subscribes a listener to a specific advisory class's stream. */
  public SseStreamChannel.Subscription subscribe(
      AdvisoryClassId classId, Consumer<SseEnvelope> listener) {
    return channel(classId).subscribe(listener);
  }

  /** Returns the current seq cursor for a specific advisory class. */
  public long currentSeq(AdvisoryClassId classId) {
    return channel(classId).currentSeq();
  }

  public AdvisoryClassRegistry classRegistry() {
    return classRegistry;
  }

  /** Derives the stable StreamId for an advisory class's SSE stream. */
  public static StreamId streamIdFor(AdvisoryClassId classId) {
    return StreamId.surface("advisory-" + classId.value().replace('.', '-'));
  }

  private boolean broadcast(
      AdvisoryClassId classId, AdvisoryRecord record, EmissionPolicy policy) {
    Optional<Duration> window = policy.dedupeWindow();
    if (window.isPresent()) {
      ConcurrentHashMap<String, Instant> classState = dedupeState.get(classId);
      if (classState != null) {
        Instant now = clock.instant();
        pruneStaleEntries(classState, now, window.get());
        Instant previous = classState.get(record.id());
        if (previous != null
            && Duration.between(previous, now).compareTo(window.get()) < 0) {
          return false;
        }
        classState.put(record.id(), now);
      }
    }

    SseStreamChannel ch = channels.get(classId);
    if (ch != null) {
      ch.publish(SseFrameKind.UPDATE, record);
    }
    return true;
  }

  private static void pruneStaleEntries(
      ConcurrentHashMap<String, Instant> state, Instant now, Duration window) {
    for (Map.Entry<String, Instant> entry : state.entrySet()) {
      if (Duration.between(entry.getValue(), now).compareTo(window) >= 0) {
        state.remove(entry.getKey(), entry.getValue());
      }
    }
  }
}
