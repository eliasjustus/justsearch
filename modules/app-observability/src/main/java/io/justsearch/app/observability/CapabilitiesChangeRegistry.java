/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability;

import io.justsearch.app.api.stream.ContractEventPayload;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for capability mutations.
 *
 * <p>Per slice 436 retrofit (post-impl): the registry now delegates to a per-stream
 * {@link SseStreamChannel} so every broadcast is wrapped in the universal envelope
 * shape. Legacy event-named SSE wire format (via {@code event:} header) is replaced by
 * the universal envelope ({@code event: frame}, JSON body with discriminator).
 *
 * <p>Subscriber model:
 *
 * <ul>
 *   <li>Snapshot delivered by the SSE controller from {@link CapabilitiesService} on
 *       subscribe — not by this registry.
 *   <li>Push changes only — broadcasts assign the next monotonic seq, append the frame
 *       to the channel's ring buffer, and notify all envelope-listeners.
 *   <li>Heartbeat is emitted by the SSE controller's scheduler.
 *   <li>Listeners that throw on delivery are removed inline (channel-level discipline).
 * </ul>
 */
public final class CapabilitiesChangeRegistry {

  /** Stable StreamId for the capabilities stream (per slice 436 §B.3). */
  public static final StreamId STREAM_ID = StreamId.registry("capabilities");

  private final SseStreamChannel channel;

  public CapabilitiesChangeRegistry() {
    this.channel = new SseStreamChannel(STREAM_ID);
  }

  /** Returns the current monotonic seq cursor (replaces the legacy catalogVersion). */
  public long currentSeq() {
    return channel.currentSeq();
  }

  /** Returns the underlying channel for controller-side per-connection writer wiring. */
  public SseStreamChannel channel() {
    return channel;
  }

  public SseStreamChannel.Subscription subscribe(Consumer<SseEnvelope> listener) {
    return channel.subscribe(listener);
  }

  /**
   * Broadcasts a capability change. Wraps the (kind, detail) tuple into a typed payload
   * and delegates to the channel.
   */
  public void broadcast(CapabilityChangeEvent.Kind kind, String detail) {
    Objects.requireNonNull(kind, "kind");
    CapabilityChangeEvent payload = new CapabilityChangeEvent(kindWireName(kind), detail);
    channel.publish(SseFrameKind.UPDATE, payload);
  }

  private static String kindWireName(CapabilityChangeEvent.Kind kind) {
    return switch (kind) {
      case SNAPSHOT -> "snapshot";
      case ADDED -> "added";
      case MODIFIED -> "modified";
      case REMOVED -> "removed";
      case HEARTBEAT -> "heartbeat";
    };
  }

  // -- Slice 3a-1-8e contract-event broadcasts ----------------------------
  //
  // Per slice 3a-1-8e (ship-option a, 2026-05-07), the substrate's runtime-
  // continuous negotiation surface lives at the Resource layer. The four
  // contract event kinds ride this same channel as typed payloads
  // (mirroring `contracts/wire/contract_events.proto` ContractEvent's
  // single-message-with-discriminator shape).
  //
  // Producer status: V1 ships the broadcast methods + a test-only emit
  // hook on the channel. Production callers materialize when 3a-1-8b lands
  // runtime plugins (capability-registered/unregistered) and 3a-1-8d
  // Phase 2+ ships catalog content evolution (catalog-membership-changed).
  // reaction-outcome is consumer-emitted from the FE side via
  // contractEvents.emitReactionOutcome; the backend may also emit it for
  // server-side consumers that join later.

  /**
   * Broadcasts a {@code capability-registered} contract event.
   *
   * @param capabilityId stable per-capability id (e.g., resource id, operation id)
   * @param capabilityType free-string discriminator within the capability family
   *     (e.g., {@code "resource"}, {@code "operation"}, {@code "surface"})
   * @param attributes per-type opaque payload (the contributed capability's typed
   *     manifest); may be {@code null} for kinds that don't need a payload
   */
  public void broadcastCapabilityRegistered(
      String capabilityId, String capabilityType, Map<String, Object> attributes) {
    Objects.requireNonNull(capabilityId, "capabilityId");
    Objects.requireNonNull(capabilityType, "capabilityType");
    ContractEventPayload payload =
        new ContractEventPayload(
            ContractEventPayload.Kinds.CAPABILITY_REGISTERED,
            capabilityId,
            capabilityType,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            attributes);
    channel.publish(SseFrameKind.UPDATE, payload);
  }

  /** Broadcasts a {@code capability-unregistered} contract event. */
  public void broadcastCapabilityUnregistered(String capabilityId, String capabilityType) {
    Objects.requireNonNull(capabilityId, "capabilityId");
    Objects.requireNonNull(capabilityType, "capabilityType");
    ContractEventPayload payload =
        new ContractEventPayload(
            ContractEventPayload.Kinds.CAPABILITY_UNREGISTERED,
            capabilityId,
            capabilityType,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    channel.publish(SseFrameKind.UPDATE, payload);
  }

  /**
   * Broadcasts a {@code catalog-membership-changed} contract event.
   *
   * @param category catalog Category name (e.g., {@code "resource"}, {@code "operation"},
   *     {@code "severity"}, {@code "reason-code"})
   * @param added entry ids added to the catalog; may be {@code null} or empty
   * @param removed entry ids removed; may be {@code null} or empty
   * @param modified entry ids modified in place; may be {@code null} or empty
   */
  public void broadcastCatalogMembershipChanged(
      String category, List<String> added, List<String> removed, List<String> modified) {
    Objects.requireNonNull(category, "category");
    ContractEventPayload payload =
        new ContractEventPayload(
            ContractEventPayload.Kinds.CATALOG_MEMBERSHIP_CHANGED,
            null,
            null,
            category,
            added,
            removed,
            modified,
            null,
            null,
            null,
            null);
    channel.publish(SseFrameKind.UPDATE, payload);
  }

  /**
   * Broadcasts a {@code reaction-outcome} contract event (xDS-narrow one-way
   * observability emit). Used when a backend-side consumer reports its
   * reaction status; FE consumers emit via {@code contractEvents.emitReactionOutcome}.
   *
   * @param outcome free-string status (use {@link ContractEventPayload.Outcomes}: APPLIED /
   *     REJECTED / DEGRADED)
   * @param reason optional human-readable detail; may be {@code null}
   */
  public void broadcastReactionOutcome(
      String capabilityId, String consumerId, String outcome, String reason) {
    Objects.requireNonNull(capabilityId, "capabilityId");
    Objects.requireNonNull(consumerId, "consumerId");
    Objects.requireNonNull(outcome, "outcome");
    ContractEventPayload payload =
        new ContractEventPayload(
            ContractEventPayload.Kinds.REACTION_OUTCOME,
            capabilityId,
            null,
            null,
            null,
            null,
            null,
            consumerId,
            outcome,
            reason,
            null);
    channel.publish(SseFrameKind.UPDATE, payload);
  }

  /** Wire payload for a single capability-change broadcast. */
  public record CapabilityChangeEvent(String kind, String detail) {
    public CapabilityChangeEvent {
      Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
      SNAPSHOT,
      ADDED,
      MODIFIED,
      REMOVED,
      HEARTBEAT
    }
  }
}
