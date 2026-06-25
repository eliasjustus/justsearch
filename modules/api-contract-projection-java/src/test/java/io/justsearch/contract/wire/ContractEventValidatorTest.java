/*
 * Slice 3a-1-8e (ship-option a, 2026-05-07): protovalidate verification of
 * ContractEvent's per-kind required-field CEL invariants. Mirrors the
 * WireContractValidatorTest shape; verifies that the single-message-with-
 * discriminator pattern enforces variant-specific structure.
 *
 * Forward-compat: unknown discriminator kinds pass validation per ADR-09a
 * (no in:[...] constraint on the kind field).
 */
package io.justsearch.contract.wire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import build.buf.protovalidate.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ContractEvent — per-kind CEL invariants + forward-compat")
final class ContractEventValidatorTest {

  @Test
  @DisplayName("capability-registered with id + type — accepted")
  void capabilityRegistered_validShape_accepted() throws Exception {
    ContractEvent event =
        ContractEvent.newBuilder()
            .setKind("capability-registered")
            .setCapabilityId("core.library")
            .setCapabilityType("resource")
            .build();
    ValidationResult result = WireContractValidator.validate(event);
    assertTrue(result.isSuccess(), () -> "expected accept, got: " + result);
  }

  @Test
  @DisplayName("capability-registered without capability_type — rejected by CEL")
  void capabilityRegistered_missingType_rejected() throws Exception {
    ContractEvent event =
        ContractEvent.newBuilder()
            .setKind("capability-registered")
            .setCapabilityId("core.library")
            // capability_type deliberately omitted
            .build();
    ValidationResult result = WireContractValidator.validate(event);
    assertFalse(
        result.isSuccess(),
        "expected reject for capability-registered missing capability_type");
  }

  @Test
  @DisplayName("capability-unregistered with id + type — accepted")
  void capabilityUnregistered_validShape_accepted() throws Exception {
    ContractEvent event =
        ContractEvent.newBuilder()
            .setKind("capability-unregistered")
            .setCapabilityId("core.library")
            .setCapabilityType("resource")
            .build();
    ValidationResult result = WireContractValidator.validate(event);
    assertTrue(result.isSuccess(), () -> "expected accept, got: " + result);
  }

  @Test
  @DisplayName("capability-unregistered without capability_id — rejected by CEL")
  void capabilityUnregistered_missingId_rejected() throws Exception {
    ContractEvent event =
        ContractEvent.newBuilder()
            .setKind("capability-unregistered")
            // capability_id deliberately omitted
            .setCapabilityType("resource")
            .build();
    ValidationResult result = WireContractValidator.validate(event);
    assertFalse(
        result.isSuccess(),
        "expected reject for capability-unregistered missing capability_id");
  }

  @Test
  @DisplayName("catalog-membership-changed with category — accepted")
  void catalogMembershipChanged_validShape_accepted() throws Exception {
    ContractEvent event =
        ContractEvent.newBuilder()
            .setKind("catalog-membership-changed")
            .setCategory("operation")
            .addAdded("core.library.reindex")
            .build();
    ValidationResult result = WireContractValidator.validate(event);
    assertTrue(result.isSuccess(), () -> "expected accept, got: " + result);
  }

  @Test
  @DisplayName("catalog-membership-changed without category — rejected by CEL")
  void catalogMembershipChanged_missingCategory_rejected() throws Exception {
    ContractEvent event =
        ContractEvent.newBuilder()
            .setKind("catalog-membership-changed")
            // category deliberately omitted
            .addAdded("core.library.reindex")
            .build();
    ValidationResult result = WireContractValidator.validate(event);
    assertFalse(
        result.isSuccess(),
        "expected reject for catalog-membership-changed missing category");
  }

  @Test
  @DisplayName("reaction-outcome with all required fields — accepted")
  void reactionOutcome_validShape_accepted() throws Exception {
    ContractEvent event =
        ContractEvent.newBuilder()
            .setKind("reaction-outcome")
            .setCapabilityId("core.library")
            .setConsumerId("ResourceCatalogClient")
            .setOutcome(ReactionOutcome.REACTION_OUTCOME_APPLIED)
            .build();
    ValidationResult result = WireContractValidator.validate(event);
    assertTrue(result.isSuccess(), () -> "expected accept, got: " + result);
  }

  @Test
  @DisplayName("reaction-outcome without consumer_id — rejected by CEL")
  void reactionOutcome_missingConsumer_rejected() throws Exception {
    ContractEvent event =
        ContractEvent.newBuilder()
            .setKind("reaction-outcome")
            .setCapabilityId("core.library")
            // consumer_id deliberately omitted
            .setOutcome(ReactionOutcome.REACTION_OUTCOME_APPLIED)
            .build();
    ValidationResult result = WireContractValidator.validate(event);
    assertFalse(
        result.isSuccess(),
        "expected reject for reaction-outcome missing consumer_id");
  }

  @Test
  @DisplayName(
      "Forward-compat — unknown discriminator kind accepted (no in: constraint per ADR-09a)")
  void forwardCompat_unknownKindAccepted() throws Exception {
    ContractEvent event =
        ContractEvent.newBuilder()
            .setKind("future-event-variant")
            .build();
    ValidationResult result = WireContractValidator.validate(event);
    assertTrue(
        result.isSuccess(),
        () ->
            "expected accept for unknown kind 'future-event-variant' (forward-compat); got: "
                + result);
  }

  @Test
  @DisplayName("ReactionOutcome enum — UNSPECIFIED is the proto3 zero value (forward-compat)")
  void reactionOutcome_unspecifiedZeroValue() {
    // Sanity check that the enum's zero value exists and is named per
    // proto3 convention. Forward-compat: future variants don't break older
    // consumers because the zero value is interpreted as "unspecified."
    assert ReactionOutcome.REACTION_OUTCOME_UNSPECIFIED.getNumber() == 0;
    assert ReactionOutcome.REACTION_OUTCOME_APPLIED.getNumber() == 1;
    assert ReactionOutcome.REACTION_OUTCOME_REJECTED.getNumber() == 2;
    assert ReactionOutcome.REACTION_OUTCOME_DEGRADED.getNumber() == 3;
  }
}
