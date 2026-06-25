package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.metarank.lightgbm4j.LGBMBooster;
import io.justsearch.app.api.gpl.LambdaMartTrainingStatus;
import io.justsearch.app.services.feedback.LabelProjection;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 580 §17.5 Fix-C — guard tests for the minimum-contrast adoption gate: real
 * disposition-derived labels may only displace the GPL synthetic cold-start prior once enough
 * contrast groups (queries with both a positive and a negative) have accrued. The gate is what
 * prevents a handful of single-OPENED queries from training + adopting a degenerate reranker (the
 * F-021 recurrence — single-positive groups score a trivially-perfect nDCG@10, so the trainer's
 * nDCG&gt;0 adoption guard alone would not catch it).
 */
class LambdaMartTrainingTest {

  @Test
  void belowFloor_keepsTheGplPrior() {
    assertFalse(
        LambdaMartTraining.realLabelsReady(new LabelProjection.Result(1000, 0)),
        "zero contrast groups must keep the prior, regardless of raw triple count");
    assertFalse(
        LambdaMartTraining.realLabelsReady(
            new LabelProjection.Result(1000, LambdaMartTraining.MIN_CONTRAST_GROUPS - 1)),
        "one below the floor must keep the prior");
  }

  @Test
  void atOrAboveFloor_adoptsRealLabels() {
    assertTrue(
        LambdaMartTraining.realLabelsReady(
            new LabelProjection.Result(1000, LambdaMartTraining.MIN_CONTRAST_GROUPS)),
        "exactly at the floor must adopt real labels");
    assertTrue(
        LambdaMartTraining.realLabelsReady(
            new LabelProjection.Result(1000, LambdaMartTraining.MIN_CONTRAST_GROUPS + 5)),
        "above the floor must adopt real labels");
  }

  @Test
  void rawTripleCountAloneDoesNotAdopt() {
    // The gate is on CONTRAST groups, not raw triples: a store full of single-positive groups (many
    // triples, zero contrast) must NOT adopt — that is exactly the degenerate case Fix-C blocks.
    assertFalse(LambdaMartTraining.realLabelsReady(new LabelProjection.Result(10_000, 0)));
  }

  // Tempdoc 580 §12.6 regression (observations #432/#446): the GPL synthetic COLD-START path must
  // actually train at bootstrap. Before the fix it routed through the bootstrap's
  // startLambdaMartTrainingAsync → startAsync(null coordinator) → NO-OP, so training never ran and
  // the status stayed PENDING. The fix trains directly from the on-disk triple path via
  // startAsyncFromTriples (no coordinator needed), so the status reaches a terminal SUCCEEDED.

  private static final boolean LIGHTGBM_AVAILABLE = lightgbmAvailable();

  private static boolean lightgbmAvailable() {
    try {
      LGBMBooster.loadNative();
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  @Test
  void loadOrTrain_coldStart_trainsFromGplTriples_notNoOp(@TempDir Path dataDir) throws Exception {
    assumeTrue(LIGHTGBM_AVAILABLE, "lightgbm4j native not available");
    // No feedback stores → realLabelsReady is false → the GPL synthetic cold-start branch. Five
    // contrastful query groups (1 positive + 3 negatives each) are enough for a valid train/eval.
    Files.writeString(
        dataDir.resolve("gpl-training-triples.ndjson"), syntheticTriples(5), StandardCharsets.UTF_8);

    LambdaMartReranker reranker = new LambdaMartReranker();
    LambdaMartTraining.loadOrTrain(dataDir, reranker, true);

    LambdaMartTrainingStatus.Phase phase = awaitTerminalPhase(reranker, Duration.ofSeconds(60));
    assertEquals(
        LambdaMartTrainingStatus.Phase.SUCCEEDED,
        phase,
        "cold-start must TRAIN from the on-disk GPL triples — the old path no-op'd on a null"
            + " coordinator and left the status PENDING");
  }

  private static LambdaMartTrainingStatus.Phase awaitTerminalPhase(
      LambdaMartReranker reranker, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    LambdaMartTrainingStatus.Phase phase = reranker.getTrainingStatus().status();
    while (phase != LambdaMartTrainingStatus.Phase.SUCCEEDED
        && phase != LambdaMartTrainingStatus.Phase.FAILED
        && System.nanoTime() < deadline) {
      Thread.sleep(50);
      phase = reranker.getTrainingStatus().status();
    }
    return phase;
  }

  private static String syntheticTriples(int numQueries) {
    StringBuilder sb = new StringBuilder();
    for (int q = 0; q < numQueries; q++) {
      sb.append(tripleLine("q" + q, "doc-pos-" + q, false, 5.0f + q, q));
      for (int n = 0; n < 3; n++) {
        sb.append(tripleLine("q" + q, "doc-neg-" + q + "-" + n, true, 1.0f + n, n + 2));
      }
    }
    return sb.toString();
  }

  private static String tripleLine(
      String queryId, String docId, boolean isNegative, float sparse, int rank) {
    return ("{\"query_id\":\"%s\",\"doc_id\":\"%s\",\"synthetic_query\":\"test\",\"score\":0.8,"
            + "\"is_negative\":%b,\"sparse\":%f,\"vector\":0.0,\"qpp_max_idf\":8.0,"
            + "\"qpp_avg_ictf\":6.0,\"qpp_query_scope\":0.25,\"rank_position\":%d,"
            + "\"timestamp_ms\":12345}\n")
        .formatted(queryId, docId, isNegative, sparse, rank);
  }
}
