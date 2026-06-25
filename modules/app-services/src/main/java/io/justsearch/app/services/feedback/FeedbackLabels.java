/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.app.services.gpl.GplTrainingTripleStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 580 §17.5 (Track C P5) — the glue that runs the {@link LabelProjection} from the persisted
 * feedback stores into the real-label triple file the trainer reads.
 *
 * <p>Reads the canonical per-query {@link FeatureSnapshot}s (P1) and {@link ResultDisposition}s
 * (P2/P3/P4), joins them by {@code interactionId}, and rebuilds
 * {@code feedback/real-feedback-triples.ndjson} — kept distinct from the GPL synthetic store so the
 * synthetic set demotes to a cold-start prior (the F-021 reconciliation). Idempotent: each rebuild
 * clears and reprojects.
 */
public final class FeedbackLabels {

  private static final Logger log = LoggerFactory.getLogger(FeedbackLabels.class);

  /** The real-label triple file, relative to the data dir. */
  public static final String REAL_LABEL_FILE = "feedback/real-feedback-triples.ndjson";

  private static final String SNAPSHOTS = "feedback/feature-snapshots.ndjson";
  private static final String DISPOSITIONS = "feedback/result-dispositions.ndjson";

  private FeedbackLabels() {}

  /** Resolves the real-label triple path under {@code dataDir}. */
  public static Path realLabelPath(Path dataDir) {
    return dataDir.resolve("feedback").resolve("real-feedback-triples.ndjson");
  }

  /**
   * Rebuilds the real-label triple store from the persisted disposition + snapshot streams.
   *
   * @return the projection result — triples written + the number of <em>contrast groups</em> (queries
   *     with both a positive and a negative label). {@code (0, 0)} when there is no joinable feedback
   *     yet — the cold-start reality, in which the GPL synthetic prior remains the training source.
   *     {@code contrastGroups} is the Fix-C adoption gate: real-label training only displaces the
   *     prior once enough contrastful groups exist for a non-degenerate fit.
   */
  public static LabelProjection.Result rebuild(Path dataDir) {
    try {
      List<FeatureSnapshot> snapshots =
          new NdjsonAppendStore<>(dataDir.resolve(SNAPSHOTS), FeatureSnapshot.class).readAll();
      List<ResultDisposition> dispositions =
          new NdjsonAppendStore<>(dataDir.resolve(DISPOSITIONS), ResultDisposition.class).readAll();
      if (snapshots.isEmpty() || dispositions.isEmpty()) {
        return new LabelProjection.Result(0, 0);
      }
      GplTrainingTripleStore real = new GplTrainingTripleStore(dataDir, REAL_LABEL_FILE);
      real.clear(); // idempotent rebuild
      LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, real);
      log.info(
          "Feedback labels: projected {} real-label triples ({} contrast groups) from {} dispositions"
              + " ⋈ {} snapshots",
          result.triples(), result.contrastGroups(), dispositions.size(), snapshots.size());
      return result;
    } catch (IOException e) {
      log.warn("Failed to rebuild feedback labels: {}", e.getMessage());
      log.debug("Feedback label rebuild failure (stack trace)", e);
      return new LabelProjection.Result(0, 0);
    }
  }
}
