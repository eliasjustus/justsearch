/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.SseEnvelope;
import java.util.Optional;

/**
 * Retention bounds for a {@link FrameHistoryRingBuffer}: how many frames, how many bytes,
 * and which frames are <em>evidence</em> rather than narrative.
 *
 * <p>Tempdoc 834 §2 ("bounds that cohere"). A single frame axis does not bound memory
 * honestly — one large replace-only frame (a run's {@code rag.citations} carrying passage
 * text) can evict most of a narrative ring, making truncation the norm. So retention is two
 * tier:
 *
 * <ul>
 *   <li><strong>Narrative ring</strong> — chunk / progress-shaped frames, bounded by BOTH
 *       {@code maxFrames} and {@code maxBytes}, oldest-evicted.
 *   <li><strong>Evidence slot</strong> — large replace-only frames in a keyed latest-wins
 *       map with its own {@code maxEvidenceBytes} budget. These are state, not narrative: a
 *       reattacher needs the <em>latest</em> citations, never their history. Segregation
 *       stops one 500 KB frame evicting thousands of narrative frames.
 * </ul>
 *
 * <p>Which frames are evidence is a <em>caller</em> decision, not a substrate one — the
 * substrate has no frame vocabulary. {@link EvidenceClassifier} is the seam; the run
 * policies that use it land with the run channels (S3b).
 *
 * <p>{@link #DEFAULT} is today's behaviour exactly: {@link
 * FrameHistoryRingBuffer#DEFAULT_CAPACITY} frames, no byte bound, no evidence slot. Under
 * an unbounded byte budget with no classifier, {@code append} never sizes a frame, so the
 * 18 production catalog routes pay nothing for machinery they do not use.
 *
 * @param maxFrames narrative ring capacity in frames; must be &gt; 0
 * @param maxBytes narrative ring byte bound, or {@link #UNBOUNDED_BYTES} for none
 * @param maxEvidenceBytes evidence slot byte budget; 0 disables the slot
 * @param evidenceClassifier maps a frame to its evidence key, or empty for narrative
 */
public record FrameRetentionPolicy(
    int maxFrames, long maxBytes, long maxEvidenceBytes, EvidenceClassifier evidenceClassifier) {

  /** Sentinel for "no byte bound" — the frame axis alone binds. */
  public static final long UNBOUNDED_BYTES = Long.MAX_VALUE;

  /** Today's catalog-stream behaviour: 9000 frames, no byte bound, no evidence slot. */
  public static final FrameRetentionPolicy DEFAULT =
      new FrameRetentionPolicy(
          FrameHistoryRingBuffer.DEFAULT_CAPACITY,
          UNBOUNDED_BYTES,
          0L,
          EvidenceClassifier.NARRATIVE_ONLY);

  public FrameRetentionPolicy {
    if (maxFrames <= 0) {
      throw new IllegalArgumentException("maxFrames must be > 0, got " + maxFrames);
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be > 0, got " + maxBytes);
    }
    if (maxEvidenceBytes < 0) {
      throw new IllegalArgumentException(
          "maxEvidenceBytes must be >= 0, got " + maxEvidenceBytes);
    }
    if (evidenceClassifier == null) {
      throw new IllegalArgumentException("evidenceClassifier must not be null");
    }
  }

  /** Frame-bounded only, no byte bound and no evidence slot (the pre-834 shape). */
  public static FrameRetentionPolicy ofFrames(int maxFrames) {
    return new FrameRetentionPolicy(
        maxFrames, UNBOUNDED_BYTES, 0L, EvidenceClassifier.NARRATIVE_ONLY);
  }

  /**
   * True when this policy needs per-frame byte accounting. False for {@link #DEFAULT}, which
   * is why the catalog routes never invoke {@link FrameRetentionSizer}.
   */
  public boolean tracksBytes() {
    return maxBytes != UNBOUNDED_BYTES || evidenceSlotEnabled();
  }

  /** True when frames may be routed to the evidence slot. */
  public boolean evidenceSlotEnabled() {
    return maxEvidenceBytes > 0 && evidenceClassifier != EvidenceClassifier.NARRATIVE_ONLY;
  }

  /**
   * Decides whether a frame is evidence (replace-only state, keyed latest-wins) or
   * narrative. Returning empty means narrative.
   */
  @FunctionalInterface
  public interface EvidenceClassifier {

    /** Every frame is narrative — the catalog-stream default. */
    EvidenceClassifier NARRATIVE_ONLY = frame -> Optional.empty();

    Optional<String> evidenceKey(SseEnvelope frame);
  }
}
