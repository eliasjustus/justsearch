/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import io.justsearch.configuration.resolved.ConfigStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates whether the GPL pipeline should re-run based on corpus state changes.
 *
 * <p>Compares the current corpus state (doc count, MIME distribution) against the last-evaluated
 * snapshot. Triggers re-evaluation when the corpus has changed materially — size doubled or a new
 * content type appeared.
 */
public final class GplRevalidationTrigger {

  private static final long DEFAULT_MAX_AUTO_DOC_COUNT = 500L;

  private final double sizeDoubleFactor;
  private final long maxAutoDocCount;

  public GplRevalidationTrigger() {
    this.sizeDoubleFactor = ConfigStore.global().get().ai().gplReevalSizeFactor();
    this.maxAutoDocCount = DEFAULT_MAX_AUTO_DOC_COUNT;
  }

  /** Visible for testing. */
  GplRevalidationTrigger(double sizeDoubleFactor) {
    this(sizeDoubleFactor, Long.MAX_VALUE);
  }

  /** Visible for testing. */
  GplRevalidationTrigger(double sizeDoubleFactor, long maxAutoDocCount) {
    this.sizeDoubleFactor = sizeDoubleFactor;
    this.maxAutoDocCount = maxAutoDocCount;
  }

  /**
   * Result of trigger evaluation.
   *
   * @param shouldRun whether a GPL re-run is warranted
   * @param reasons human-readable descriptions of which conditions fired
   */
  public record TriggerResult(boolean shouldRun, List<String> reasons) {}

  /**
   * Evaluates trigger conditions.
   *
   * @param lastEval the last-evaluated snapshot, or {@code null} if never evaluated
   * @param currentDocCount current corpus document count
   * @param currentMimeDistribution current MIME type distribution (type → count)
   * @return trigger result with reasons
   */
  public TriggerResult evaluate(
      GplEvalSnapshot lastEval,
      long currentDocCount,
      Map<String, Long> currentMimeDistribution) {

    List<String> reasons = new ArrayList<>();
    if (currentDocCount > maxAutoDocCount) {
      reasons.add(
          String.format(
              "corpus has %,d docs, above automatic GPL limit %,d",
              currentDocCount, maxAutoDocCount));
      return new TriggerResult(false, reasons);
    }

    if (lastEval == null) {
      reasons.add("first evaluation (no previous snapshot)");
      return new TriggerResult(true, reasons);
    }

    // Condition 1: corpus size grew by the configured factor
    long lastCount = lastEval.docCount();
    if (lastCount > 0 && currentDocCount >= (long) (lastCount * sizeDoubleFactor)) {
      reasons.add(
          String.format(
              "corpus size grew %.1fx: %d -> %d (threshold: %.1fx)",
              (double) currentDocCount / lastCount,
              lastCount,
              currentDocCount,
              sizeDoubleFactor));
    }

    // Condition 2: new MIME types appeared
    Set<String> previousTypes = lastEval.mimeDistribution().keySet();
    Set<String> newTypes = new TreeSet<>();
    for (String mimeType : currentMimeDistribution.keySet()) {
      if (!previousTypes.contains(mimeType)) {
        newTypes.add(mimeType);
      }
    }
    if (!newTypes.isEmpty()) {
      reasons.add("new content types appeared: " + newTypes);
    }

    return new TriggerResult(!reasons.isEmpty(), reasons);
  }
}
