package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link IngestionSuccessClassifier} seam (governance/logic-seams.v1.json) — the
 * pure, total ledger-transition-to-success-bucket mapping extracted from
 * {@code IngestionOutcomeJournal.drainPending} (tempdoc 671, Long-term design part 2). Asserts
 * all three buckets are reachable, that unrecognized/legacy/null strings fall safely to FULL
 * (never throw), and that the mapping is injective across its named buckets.
 */
class IngestionSuccessClassifierTest {

  @Test
  @DisplayName("\"SUCCESS_PARTIAL\" maps to PARTIAL")
  void successPartialMapsToPartial() {
    assertEquals(
        IngestionSuccessClassifier.Bucket.PARTIAL,
        IngestionSuccessClassifier.classify("SUCCESS_PARTIAL"));
  }

  @Test
  @DisplayName("\"SUCCESS_EMPTY\" maps to EMPTY")
  void successEmptyMapsToEmpty() {
    assertEquals(
        IngestionSuccessClassifier.Bucket.EMPTY,
        IngestionSuccessClassifier.classify("SUCCESS_EMPTY"));
  }

  @Test
  @DisplayName("\"SUCCESS_FULL\", null, and unrecognized/legacy strings all map to FULL")
  void everythingElseMapsToFull() {
    assertEquals(
        IngestionSuccessClassifier.Bucket.FULL, IngestionSuccessClassifier.classify("SUCCESS_FULL"));
    assertEquals(IngestionSuccessClassifier.Bucket.FULL, IngestionSuccessClassifier.classify(null));
    assertEquals(
        IngestionSuccessClassifier.Bucket.FULL,
        IngestionSuccessClassifier.classify("SOME_UNRECOGNIZED_LEGACY_VALUE"));
  }

  @Test
  @DisplayName("the three buckets are pairwise distinct (mapping is injective across the boundaries)")
  void bucketsArePairwiseDistinct() {
    IngestionSuccessClassifier.Bucket partial = IngestionSuccessClassifier.classify("SUCCESS_PARTIAL");
    IngestionSuccessClassifier.Bucket empty = IngestionSuccessClassifier.classify("SUCCESS_EMPTY");
    IngestionSuccessClassifier.Bucket full = IngestionSuccessClassifier.classify("SUCCESS_FULL");
    assertNotEquals(partial, empty);
    assertNotEquals(empty, full);
    assertNotEquals(partial, full);
  }
}
