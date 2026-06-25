/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Slice 3a-1-8e (ship-option a, 2026-05-07): contract events ridden on
 * `/infra/capabilities/stream`.
 *
 * Wire shape mirrors `contracts/wire/contract_events.proto`'s ContractEvent
 * message — single-message-with-discriminator pattern per ADR-09a (precedent:
 * HealthEventBody). The `kind` discriminator is a free string at the wire
 * level (forward-compat for unknown variants); per-kind required-field
 * invariants are enforced by protovalidate on the consumer side
 * (WireContractValidator + FE wireValidator.ts).
 *
 * Per ADR-09 substrate discipline: wire-shaped records carry data + accessors
 * only; behavior lives in producer/consumer code. This record is the data
 * payload; emission happens through CapabilitiesChangeRegistry.broadcast*.
 */
package io.justsearch.app.api.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wire payload for a single contract event. Rides as the SseEnvelope.payload
 * on UPDATE frames of {@code /infra/capabilities/stream}.
 *
 * <p>Variant fields are nullable; absent fields elide on the wire via
 * {@code @JsonInclude(Include.NON_NULL)} so the per-kind shape stays minimal
 * (e.g., a `catalog-membership-changed` envelope carries no
 * `capability_id` / `capability_type` / `consumer_id` / `outcome` fields).
 *
 * <p>Builders (typed broadcast methods on {@code CapabilitiesChangeRegistry})
 * construct instances with only the relevant fields populated per kind.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContractEventPayload(
    String kind,
    String capabilityId,
    String capabilityType,
    String category,
    List<String> added,
    List<String> removed,
    List<String> modified,
    String consumerId,
    String outcome,
    String reason,
    Map<String, Object> attributes) {

  public ContractEventPayload {
    Objects.requireNonNull(kind, "kind");
    if (kind.isBlank()) {
      throw new IllegalArgumentException("kind must not be blank");
    }
    added = added == null ? null : List.copyOf(added);
    removed = removed == null ? null : List.copyOf(removed);
    modified = modified == null ? null : List.copyOf(modified);
    attributes = attributes == null ? null : Map.copyOf(attributes);
  }

  /** Outcome variant for {@code reaction-outcome} events. Free string at the wire level. */
  public static final class Outcomes {
    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";
    public static final String DEGRADED = "DEGRADED";

    private Outcomes() {}
  }

  /** Kind variants for the four V1 event types. Free string at the wire level (forward-compat). */
  public static final class Kinds {
    public static final String CAPABILITY_REGISTERED = "capability-registered";
    public static final String CAPABILITY_UNREGISTERED = "capability-unregistered";
    public static final String CATALOG_MEMBERSHIP_CHANGED = "catalog-membership-changed";
    public static final String REACTION_OUTCOME = "reaction-outcome";

    private Kinds() {}
  }
}
