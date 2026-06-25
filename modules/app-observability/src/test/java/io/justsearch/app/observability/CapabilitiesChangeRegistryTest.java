package io.justsearch.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.ContractEventPayload;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice 3a-1-8e (ship-option a, 2026-05-07): exercises the four typed
 * contract-event broadcasts on {@link CapabilitiesChangeRegistry}. Mirrors
 * the existing CapabilityChangeEvent broadcast test shape; verifies each
 * event's payload populates only the variant-relevant fields per the
 * single-message-with-discriminator pattern.
 */
@DisplayName("CapabilitiesChangeRegistry — contract-event broadcasts")
final class CapabilitiesChangeRegistryTest {

  @Test
  @DisplayName("legacy broadcast still publishes CapabilityChangeEvent")
  void legacyBroadcast() {
    CapabilitiesChangeRegistry registry = new CapabilitiesChangeRegistry();
    List<SseEnvelope> received = new ArrayList<>();
    registry.subscribe(received::add);
    registry.broadcast(
        CapabilitiesChangeRegistry.CapabilityChangeEvent.Kind.ADDED, "plugin.foo");
    assertEquals(1, received.size());
    assertEquals(SseFrameKind.UPDATE, received.get(0).frameKind());
    Object payload = received.get(0).payload();
    assertInstanceOf(
        CapabilitiesChangeRegistry.CapabilityChangeEvent.class,
        payload,
        "expected CapabilityChangeEvent payload");
  }

  @Test
  @DisplayName("capability-registered broadcast populates id + type + attributes")
  void capabilityRegistered() {
    CapabilitiesChangeRegistry registry = new CapabilitiesChangeRegistry();
    List<SseEnvelope> received = new ArrayList<>();
    registry.subscribe(received::add);

    registry.broadcastCapabilityRegistered(
        "core.library", "resource", Map.of("iconHint", "book"));

    assertEquals(1, received.size());
    ContractEventPayload payload = (ContractEventPayload) received.get(0).payload();
    assertEquals(ContractEventPayload.Kinds.CAPABILITY_REGISTERED, payload.kind());
    assertEquals("core.library", payload.capabilityId());
    assertEquals("resource", payload.capabilityType());
    assertNotNull(payload.attributes());
    assertEquals("book", payload.attributes().get("iconHint"));
    // Other-variant fields are null (elide on the wire via @JsonInclude(NON_NULL)).
    assertNull(payload.category());
    assertNull(payload.consumerId());
    assertNull(payload.outcome());
  }

  @Test
  @DisplayName("capability-unregistered broadcast populates id + type only")
  void capabilityUnregistered() {
    CapabilitiesChangeRegistry registry = new CapabilitiesChangeRegistry();
    List<SseEnvelope> received = new ArrayList<>();
    registry.subscribe(received::add);

    registry.broadcastCapabilityUnregistered("core.library", "resource");

    ContractEventPayload payload = (ContractEventPayload) received.get(0).payload();
    assertEquals(ContractEventPayload.Kinds.CAPABILITY_UNREGISTERED, payload.kind());
    assertEquals("core.library", payload.capabilityId());
    assertEquals("resource", payload.capabilityType());
    assertNull(payload.attributes());
  }

  @Test
  @DisplayName("catalog-membership-changed broadcast populates category + delta lists")
  void catalogMembershipChanged() {
    CapabilitiesChangeRegistry registry = new CapabilitiesChangeRegistry();
    List<SseEnvelope> received = new ArrayList<>();
    registry.subscribe(received::add);

    registry.broadcastCatalogMembershipChanged(
        "operation",
        List.of("core.library.reindex", "core.library.exclude"),
        List.of(),
        List.of());

    ContractEventPayload payload = (ContractEventPayload) received.get(0).payload();
    assertEquals(ContractEventPayload.Kinds.CATALOG_MEMBERSHIP_CHANGED, payload.kind());
    assertEquals("operation", payload.category());
    assertEquals(2, payload.added().size());
    assertNull(payload.capabilityId());
    assertNull(payload.consumerId());
  }

  @Test
  @DisplayName("reaction-outcome broadcast populates capability_id + consumer_id + outcome + reason")
  void reactionOutcome() {
    CapabilitiesChangeRegistry registry = new CapabilitiesChangeRegistry();
    List<SseEnvelope> received = new ArrayList<>();
    registry.subscribe(received::add);

    registry.broadcastReactionOutcome(
        "core.library", "ResourceCatalogClient", ContractEventPayload.Outcomes.APPLIED, null);

    ContractEventPayload payload = (ContractEventPayload) received.get(0).payload();
    assertEquals(ContractEventPayload.Kinds.REACTION_OUTCOME, payload.kind());
    assertEquals("core.library", payload.capabilityId());
    assertEquals("ResourceCatalogClient", payload.consumerId());
    assertEquals("APPLIED", payload.outcome());
    assertNull(payload.reason());
  }

  @Test
  @DisplayName("reaction-outcome with reason populates the reason field")
  void reactionOutcomeWithReason() {
    CapabilitiesChangeRegistry registry = new CapabilitiesChangeRegistry();
    List<SseEnvelope> received = new ArrayList<>();
    registry.subscribe(received::add);

    registry.broadcastReactionOutcome(
        "plugin.foo",
        "PluginRegistry",
        ContractEventPayload.Outcomes.REJECTED,
        "unknown capability type");

    ContractEventPayload payload = (ContractEventPayload) received.get(0).payload();
    assertEquals("REJECTED", payload.outcome());
    assertEquals("unknown capability type", payload.reason());
  }

  @Test
  @DisplayName("multiple broadcasts increment seq monotonically")
  void seqMonotonic() {
    CapabilitiesChangeRegistry registry = new CapabilitiesChangeRegistry();
    long initial = registry.currentSeq();
    registry.broadcastCapabilityRegistered("a", "resource", null);
    long afterFirst = registry.currentSeq();
    registry.broadcastCapabilityUnregistered("a", "resource");
    long afterSecond = registry.currentSeq();
    assertTrue(afterFirst > initial);
    assertTrue(afterSecond > afterFirst);
  }
}
