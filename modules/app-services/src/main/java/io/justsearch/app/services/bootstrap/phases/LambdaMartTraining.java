/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.gpl.LambdaMartTrainingStatus;
import io.justsearch.app.services.feedback.FeedbackLabels;
import io.justsearch.app.services.feedback.LabelProjection;
import io.justsearch.app.services.gpl.LambdaMartTrainer;
import io.justsearch.app.services.gpl.GplJobCoordinator;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: LambdaMART training launch helper extracted from
 * {@code HeadAssembly}. Fires a virtual-thread training run from the persisted GPL triple
 * store; on success, adopts the booster into the supplied {@link LambdaMartReranker} and persists
 * the model to disk. Non-fatal: logs + records failure status on the reranker rather than
 * throwing.
 */
public final class LambdaMartTraining {

  private static final Logger log = LoggerFactory.getLogger(LambdaMartTraining.class);

  private LambdaMartTraining() {}

  /**
   * Tempdoc 519 F5 step 5: bootstrap startup-load helper. Resolves the model file path,
   * checks the configured flag, attempts to load a persisted model, and falls back to
   * triggering async retraining if the model is missing but training triples exist on disk.
   * Returns the resolved model file path so the bootstrap can hold it for later
   * {@code startLambdaMartTrainingAsync} calls (typically fired from the GPL snapshot
   * callback).
   *
   * <p>Tempdoc 580 §12.6 fix: the cold-start retrain trains DIRECTLY from the on-disk triple store
   * via {@link #startAsyncFromTriples}, NOT through the bootstrap's {@code startLambdaMartTrainingAsync}
   * — whose GPL coordinator field is assigned only AFTER {@code OrchestrationPhase} returns, so at this
   * call site it is still null and {@link #startAsync} silently no-ops. The triple path is
   * deterministic, so no coordinator is needed (resolves observations.md §12.6).
   *
   * @param dataDir bootstrap data directory.
   * @param reranker the LambdaMART reranker (mutable, holds the loaded booster).
   * @param lambdamartEnabled config flag from {@code ConfigStore.search().lambdamartEnabled()}.
   * @return the resolved model file path (so the bootstrap can hold it).
   */
  public static Path loadOrTrain(
      Path dataDir, LambdaMartReranker reranker, boolean lambdamartEnabled) {
    Path modelFile = dataDir.resolve("lambdamart-model.txt");
    if (!lambdamartEnabled) {
      log.info("LambdaMART: disabled via config");
      return modelFile;
    }
    if (!reranker.loadModel(modelFile)) {
      // Tempdoc 580 §17.5 — prefer REAL disposition-derived labels (the F-021 reconciliation: the
      // learned layer needed real labels). The GPL synthetic store is only the cold-start prior.
      // Fix-C gate: real-label training displaces the prior ONLY once enough CONTRAST groups exist
      // (queries with both a positive and a negative). Without this, a handful of single-OPENED
      // queries would train + adopt a degenerate model — each single-positive group scores a
      // trivially-perfect nDCG@10=1.0, so the trainer's nDCG>0 adoption guard would not catch it,
      // re-introducing the exact F-021 harm (a learned reranker that hurts).
      LabelProjection.Result feedback = FeedbackLabels.rebuild(dataDir);
      if (realLabelsReady(feedback)) {
        Path realPath = FeedbackLabels.realLabelPath(dataDir);
        log.info(
            "LambdaMART: training from real feedback ({} triples, {} contrast groups >= {}) at {}",
            feedback.triples(), feedback.contrastGroups(), MIN_CONTRAST_GROUPS, realPath);
        startAsyncFromTriples(reranker, modelFile, realPath);
      } else {
        Path triplePath = dataDir.resolve("gpl-training-triples.ndjson");
        if (java.nio.file.Files.isRegularFile(triplePath)) {
          log.info(
              "LambdaMART: only {} contrast groups (< {}); cold-start from GPL synthetic triples",
              feedback.contrastGroups(), MIN_CONTRAST_GROUPS);
          // §12.6 fix: train directly from the deterministic triple path — NOT trainAsync (the
          // bootstrap's startLambdaMartTrainingAsync), whose GPL coordinator is null here and no-ops.
          startAsyncFromTriples(reranker, modelFile, triplePath);
        }
      }
    }
    return modelFile;
  }

  /**
   * Minimum contrast groups (queries with both a positive and a negative label) before real
   * disposition-derived labels displace the GPL synthetic cold-start prior (tempdoc 580 §17.5
   * Fix-C). Below this, the real store is too sparse for a non-degenerate learning-to-rank fit.
   */
  static final int MIN_CONTRAST_GROUPS = 20;

  /**
   * The Fix-C adoption decision (extracted for testability): real disposition-derived labels are
   * ready to displace the GPL cold-start prior only once enough contrast groups have accrued.
   */
  static boolean realLabelsReady(LabelProjection.Result feedback) {
    return feedback.contrastGroups() >= MIN_CONTRAST_GROUPS;
  }

  /**
   * Tempdoc 580 §17.5 — train asynchronously from an explicit triple-store path, independent of any
   * GPL coordinator (the disposition-derived real-feedback store has no coordinator). Returns
   * immediately.
   */
  public static void startAsyncFromTriples(
      LambdaMartReranker reranker, Path modelFile, Path storePath) {
    Thread.ofVirtual()
        .name("lambdamart-trainer")
        .start(() -> trainAndAdopt(reranker, modelFile, storePath));
  }

  /**
   * Start an async LambdaMART training run on a virtual thread. Returns immediately. The supplied
   * coordinator must be non-null; the model file may be null to skip persistence.
   */
  public static void startAsync(
      GplJobCoordinator coordinator, LambdaMartReranker reranker, Path modelFile) {
    if (coordinator == null) {
      return;
    }
    Path storePath = coordinator.getTripleStorePath();
    Thread.ofVirtual()
        .name("lambdamart-trainer")
        .start(() -> trainAndAdopt(reranker, modelFile, storePath));
  }

  private static void trainAndAdopt(LambdaMartReranker reranker, Path modelFile, Path storePath) {
    reranker.setTrainingStatus(
        new LambdaMartTrainingStatus(
            LambdaMartTrainingStatus.Phase.TRAINING, null, null, null, null, null, null));
    try {
      log.info("LambdaMART: starting training from {}", storePath);
      LambdaMartTrainer trainer = new LambdaMartTrainer();
      LambdaMartTrainer.TrainingResult result = trainer.train(storePath);
      boolean adopted = false;
      try {
        if (result.ndcg10() > 0.0) {
          reranker.setModel(result.booster());
          adopted = true;
          log.info(
              "LambdaMART model loaded: NDCG@10={} MRR@10={} groups(train={} eval={})",
              result.ndcg10(), result.mrr10(), result.trainGroups(), result.evalGroups());
          if (modelFile != null) {
            reranker.saveModel(modelFile);
          }
          reranker.setTrainingStatus(
              new LambdaMartTrainingStatus(
                  LambdaMartTrainingStatus.Phase.SUCCEEDED,
                  result.ndcg10(),
                  result.mrr10(),
                  result.trainGroups(),
                  result.evalGroups(),
                  Instant.now(),
                  null));
        } else {
          log.warn("LambdaMART training produced NDCG@10=0.0; model not loaded");
          reranker.setTrainingStatus(
              new LambdaMartTrainingStatus(
                  LambdaMartTrainingStatus.Phase.FAILED,
                  0.0,
                  result.mrr10(),
                  result.trainGroups(),
                  result.evalGroups(),
                  Instant.now(),
                  "NDCG@10=0.0; insufficient data or degenerate features"));
        }
      } finally {
        if (!adopted) {
          result.booster().close();
        }
      }
    } catch (Exception e) {
      log.warn("LambdaMART training failed (non-fatal)", e);
      reranker.setTrainingStatus(
          new LambdaMartTrainingStatus(
              LambdaMartTrainingStatus.Phase.FAILED,
              null, null, null, null,
              Instant.now(),
              e.getMessage()));
    }
  }
}
