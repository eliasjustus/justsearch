package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.loop.FileFreshnessSnapshot.SourceValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link AdmissionPolicy} seam (governance/logic-seams.v1.json) — the pure, total
 * stale-reason mapping extracted from WorkerIngestionAuthority. Asserts every enum value maps to its
 * specific reason code and that the mapping is injective across the change kinds, so a swapped or
 * dropped switch arm (a silent wrong diagnostic) is caught. Plain JUnit; no jqwik (tempdoc 555 §10).
 */
class AdmissionPolicyTest {

  @Test
  @DisplayName("each validation result maps to its specific reason code")
  void exhaustiveMapping() {
    assertEquals(
        IngestionReasonCodes.SIZE_CHANGED_AFTER_SNAPSHOT,
        AdmissionPolicy.staleReasonCode(SourceValidationResult.SIZE_CHANGED));
    assertEquals(
        IngestionReasonCodes.MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT,
        AdmissionPolicy.staleReasonCode(SourceValidationResult.MODIFIED_TIME_CHANGED));
    assertEquals(
        IngestionReasonCodes.FILE_KEY_CHANGED_AFTER_SNAPSHOT,
        AdmissionPolicy.staleReasonCode(SourceValidationResult.FILE_KEY_CHANGED));
    assertEquals(
        IngestionReasonCodes.SOURCE_KIND_CHANGED_AFTER_SNAPSHOT,
        AdmissionPolicy.staleReasonCode(SourceValidationResult.SOURCE_KIND_CHANGED));
    assertEquals(
        IngestionReasonCodes.DELETED_AFTER_SNAPSHOT,
        AdmissionPolicy.staleReasonCode(SourceValidationResult.DELETED));
    assertEquals(
        IngestionReasonCodes.STALE_AFTER_EXTRACTION,
        AdmissionPolicy.staleReasonCode(SourceValidationResult.FRESH));
  }

  @Test
  @DisplayName("the change-kind reason codes are distinct (mapping is injective where it matters)")
  void changeKindsAreDistinct() {
    SourceValidationResult[] kinds = {
      SourceValidationResult.SIZE_CHANGED,
      SourceValidationResult.MODIFIED_TIME_CHANGED,
      SourceValidationResult.FILE_KEY_CHANGED,
      SourceValidationResult.SOURCE_KIND_CHANGED,
      SourceValidationResult.DELETED,
    };
    for (int i = 0; i < kinds.length; i++) {
      for (int j = i + 1; j < kinds.length; j++) {
        assertNotEquals(
            AdmissionPolicy.staleReasonCode(kinds[i]),
            AdmissionPolicy.staleReasonCode(kinds[j]),
            kinds[i] + " and " + kinds[j] + " must map to distinct reason codes");
      }
    }
  }
}
