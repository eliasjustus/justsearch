/*
 * Phase 3 of slice 3a-1-8: round-trip verification that protovalidate-java
 * runtime invariants fire symmetrically with the FE side
 * (@bufbuild/protovalidate, exercised in tmp/contract-spike/).
 *
 * Mirrors the spike's Test 1 (regex on AssertedCondition.reason). Confirms
 * that a malformed wire payload constructed in Java fails validation with
 * the exact same regex pattern that the FE side enforces.
 */
package io.justsearch.contract.wire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import build.buf.protovalidate.ValidationResult;
import com.google.protobuf.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WireContractValidator — Java-TS invariant symmetry")
final class WireContractValidatorTest {

  @Test
  @DisplayName("AssertedCondition.reason regex invariant — valid PascalCase accepted")
  void regexInvariant_acceptsValidPascalCase() throws Exception {
    HealthEventBody body =
        HealthEventBody.newBuilder()
            .setKind("condition")
            .setSubject("worker")
            .setConditionStatus(ConditionStatus.CONDITION_STATUS_TRUE)
            .setReason("WorkerStarting")
            .setLastTransitionTime("2026-05-05T00:00:00Z")
            .build();
    ValidationResult result = WireContractValidator.validate(body);
    assertTrue(result.isSuccess(), () -> "expected accept, got: " + result);
  }

  @Test
  @DisplayName("AssertedCondition.reason regex invariant — hyphens rejected (matches FE)")
  void regexInvariant_rejectsHyphens() throws Exception {
    HealthEventBody body =
        HealthEventBody.newBuilder()
            .setKind("condition")
            .setSubject("worker")
            .setConditionStatus(ConditionStatus.CONDITION_STATUS_TRUE)
            .setReason("worker-starting")
            .setLastTransitionTime("2026-05-05T00:00:00Z")
            .build();
    ValidationResult result = WireContractValidator.validate(body);
    assertFalse(result.isSuccess(), "expected reject for 'worker-starting' (hyphens not allowed)");
  }

  @Test
  @DisplayName("Cross-field CEL invariant — condition body without subject rejected")
  void cel_conditionRequiresSubject() throws Exception {
    HealthEventBody body =
        HealthEventBody.newBuilder()
            .setKind("condition")
            // subject deliberately omitted
            .setConditionStatus(ConditionStatus.CONDITION_STATUS_TRUE)
            .setReason("WorkerStarting")
            .setLastTransitionTime("2026-05-05T00:00:00Z")
            .build();
    ValidationResult result = WireContractValidator.validate(body);
    assertFalse(
        result.isSuccess(), "expected reject for condition body without required subject field");
  }

  @Test
  @DisplayName("Forward-compat — unknown discriminator kind accepted (no in: constraint)")
  void forwardCompat_unknownKindAccepted() throws Exception {
    HealthEventBody body =
        HealthEventBody.newBuilder()
            .setKind("future-variant")
            .putAttributes("foo", Value.newBuilder().setStringValue("bar").build())
            .build();
    ValidationResult result = WireContractValidator.validate(body);
    // Per ADR-09a: production specs do NOT carry `in: [...]` on discriminator
    // fields; unknown variants pass validation and consumer-side fallback
    // dispatches to UnknownEventBody.
    assertTrue(result.isSuccess(), () -> "expected accept (forward-compat), got: " + result);
  }

  @Test
  @DisplayName("KnowledgeStatusView dual-name alias consistency invariant")
  void knowledgeStatusView_aliasConsistencyEnforced() throws Exception {
    KnowledgeStatusView view =
        KnowledgeStatusView.newBuilder()
            .setPendingJobs(10)
            .setQueueDepth(99) // INTENTIONAL mismatch: violates CEL invariant
            .setIndexedDocuments(0)
            .setDocCount(0)
            .setActiveIndexedDocuments(0)
            .setActiveDocCount(0)
            .setBuildingIndexedDocuments(0)
            .setBuildingDocCount(0)
            .build();
    ValidationResult result = WireContractValidator.validate(view);
    assertFalse(
        result.isSuccess(),
        "expected reject: pending_jobs=10 != queue_depth=99 violates alias_consistency CEL");
  }
}
