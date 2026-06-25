package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GplRevalidationTrigger}. */
class GplRevalidationTriggerTest {

  private final GplRevalidationTrigger trigger = new GplRevalidationTrigger(2.0);

  @Test
  @DisplayName("null lastEval triggers first evaluation")
  void firstEvaluation() {
    GplRevalidationTrigger.TriggerResult result =
        trigger.evaluate(null, 100, Map.of("text/plain", 100L));
    assertTrue(result.shouldRun());
    assertEquals(1, result.reasons().size());
    assertTrue(result.reasons().get(0).contains("first evaluation"));
  }

  @Test
  @DisplayName("corpus size doubled triggers revalidation")
  void corpusSizeDoubled() {
    GplEvalSnapshot lastEval =
        new GplEvalSnapshot(1000, Map.of("text/plain", 1000L), 500, Instant.now().toString());
    GplRevalidationTrigger.TriggerResult result =
        trigger.evaluate(lastEval, 2000, Map.of("text/plain", 2000L));
    assertTrue(result.shouldRun());
    assertTrue(result.reasons().stream().anyMatch(r -> r.contains("corpus size grew")));
  }

  @Test
  @DisplayName("corpus size less than double does not trigger")
  void corpusSizeBelowThreshold() {
    GplEvalSnapshot lastEval =
        new GplEvalSnapshot(1000, Map.of("text/plain", 1000L), 500, Instant.now().toString());
    GplRevalidationTrigger.TriggerResult result =
        trigger.evaluate(lastEval, 1500, Map.of("text/plain", 1500L));
    assertFalse(result.shouldRun());
    assertTrue(result.reasons().isEmpty());
  }

  @Test
  @DisplayName("new MIME type triggers revalidation")
  void newMimeType() {
    GplEvalSnapshot lastEval =
        new GplEvalSnapshot(1000, Map.of("text/plain", 1000L), 500, Instant.now().toString());
    Map<String, Long> currentMimes = Map.of("text/plain", 1000L, "application/pdf", 50L);
    GplRevalidationTrigger.TriggerResult result =
        trigger.evaluate(lastEval, 1050, currentMimes);
    assertTrue(result.shouldRun());
    assertTrue(result.reasons().stream().anyMatch(r -> r.contains("new content types")));
    assertTrue(result.reasons().stream().anyMatch(r -> r.contains("application/pdf")));
  }

  @Test
  @DisplayName("same MIME types and small growth does not trigger")
  void noChange() {
    GplEvalSnapshot lastEval =
        new GplEvalSnapshot(1000, Map.of("text/plain", 900L, "application/pdf", 100L), 500,
            Instant.now().toString());
    Map<String, Long> currentMimes = Map.of("text/plain", 950L, "application/pdf", 100L);
    GplRevalidationTrigger.TriggerResult result =
        trigger.evaluate(lastEval, 1050, currentMimes);
    assertFalse(result.shouldRun());
  }

  @Test
  @DisplayName("both size and MIME triggers fire together")
  void bothTriggers() {
    GplEvalSnapshot lastEval =
        new GplEvalSnapshot(500, Map.of("text/plain", 500L), 200, Instant.now().toString());
    Map<String, Long> currentMimes = Map.of("text/plain", 900L, "application/pdf", 100L);
    GplRevalidationTrigger.TriggerResult result =
        trigger.evaluate(lastEval, 1000, currentMimes);
    assertTrue(result.shouldRun());
    assertEquals(2, result.reasons().size());
  }

  @Test
  @DisplayName("custom size factor is respected")
  void customFactor() {
    GplRevalidationTrigger customTrigger = new GplRevalidationTrigger(3.0);
    GplEvalSnapshot lastEval =
        new GplEvalSnapshot(100, Map.of("text/plain", 100L), 50, Instant.now().toString());

    // 2x growth does not trigger with 3.0 factor
    assertFalse(customTrigger.evaluate(lastEval, 200, Map.of("text/plain", 200L)).shouldRun());

    // 3x growth triggers
    assertTrue(customTrigger.evaluate(lastEval, 300, Map.of("text/plain", 300L)).shouldRun());
  }

  @Test
  @DisplayName("zero lastEval docCount does not trigger size condition")
  void zeroLastDocCount() {
    GplEvalSnapshot lastEval =
        new GplEvalSnapshot(0, Map.of("text/plain", 0L), 0, Instant.now().toString());
    GplRevalidationTrigger.TriggerResult result =
        trigger.evaluate(lastEval, 100, Map.of("text/plain", 100L));
    // Zero lastCount → size condition doesn't fire (avoids division issues),
    // but new MIME type "text/plain" absent from empty map doesn't apply since it exists.
    // However text/plain IS in lastEval, so no trigger.
    assertFalse(result.shouldRun());
  }

  @Test
  @DisplayName("large corpora do not auto-trigger GPL")
  void largeCorpusDoesNotAutoTrigger() {
    GplRevalidationTrigger cappedTrigger = new GplRevalidationTrigger(2.0, 500);
    GplEvalSnapshot lastEval =
        new GplEvalSnapshot(5, Map.of("text/plain", 5L), 50, Instant.now().toString());
    GplRevalidationTrigger.TriggerResult result =
        cappedTrigger.evaluate(lastEval, 5189, Map.of("text/plain", 5189L));

    assertFalse(result.shouldRun());
    assertTrue(result.reasons().stream().anyMatch(r -> r.contains("automatic GPL limit")));
  }
}
