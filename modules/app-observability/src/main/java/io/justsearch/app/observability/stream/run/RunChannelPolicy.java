/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import io.justsearch.app.observability.stream.FrameRetentionPolicy;
import java.util.Optional;
import java.util.Set;

/**
 * The bounds and the execution posture of one run channel (tempdoc 834 §1.5, §2).
 *
 * <p>{@code parkable} selects the SUBTYPE at {@code open}: a parkable policy yields a
 * {@link SteppedRunChannel} (which has {@code setPark}), a non-parkable one yields a
 * {@link OneShotRunChannel} (which structurally does not). That is §3.4's ask-survival guard —
 * flattening an ask into a parkable run is a compile error, not a review catch.
 *
 * <p><strong>Two-tier retention</strong> (§2). The frame axis alone does not bound memory honestly:
 * one {@code rag.citations} frame carrying passage text can evict most of a narrative ring, making
 * truncation the norm for RAG. So the byte bound rides beside the frame bound, and the large
 * replace-only frames go to the evidence slot — a keyed latest-wins map with its own budget, since
 * a reattacher needs the LATEST citations, never their history.
 *
 * <p><strong>Provisional.</strong> The numbers below are §2's, and {@code
 * FrameRetentionSizer.FRAME_OVERHEAD_BYTES = 200} is still provisional pending probe P2, which has
 * not been run. If P2 lands, re-derive both budgets here rather than in the sizer.
 *
 * @param maxFrames narrative ring capacity in frames
 * @param maxBytes narrative ring byte bound
 * @param parkable whether this run has control points it can park at
 */
public record RunChannelPolicy(int maxFrames, long maxBytes, boolean parkable) {

  /** The evidence slot's own byte budget, segregated from the narrative ring's (§2). */
  public static final long EVIDENCE_BYTES = 4L * 1024 * 1024;

  /**
   * The §2 evidence vocabulary: large replace-only frames that are STATE, not narrative. Keyed
   * latest-wins, so a re-retrieved citation set replaces its predecessor instead of stacking.
   */
  private static final Set<String> EVIDENCE_EVENTS =
      Set.of("rag.meta", "rag.citations", "rag.citation_matches");

  /** A tool result is evidence only when it actually carries bulk — see {@link #evidenceKey}. */
  private static final String TOOL_COMPLETED_EVENT = "tool_exec_completed";

  private static final String STRUCTURED_DATA_KEY = "structuredData";

  public RunChannelPolicy {
    if (maxFrames <= 0) {
      throw new IllegalArgumentException("maxFrames must be > 0, got " + maxFrames);
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be > 0, got " + maxBytes);
    }
  }

  /**
   * One-shot conversational runs (ask / summarize / dispatch): 4000 narrative frames, 2 MiB. Never
   * parkable — §0's column two must not be flattened into column one.
   */
  public static RunChannelPolicy conversational() {
    return new RunChannelPolicy(4000, 2L * 1024 * 1024, false);
  }

  /** Stepped agent / workflow runs: 1000 frames (today's {@code AgentSession} value), 4 MiB. */
  public static RunChannelPolicy agent() {
    return new RunChannelPolicy(1000, 4L * 1024 * 1024, true);
  }

  /** The S3a retention layer this policy configures — bounds are NOT re-derived here. */
  public FrameRetentionPolicy frameRetention() {
    return new FrameRetentionPolicy(
        maxFrames, maxBytes, EVIDENCE_BYTES, frame -> evidenceKey(frame.payload()));
  }

  /**
   * The evidence key for a run frame's payload, or empty for narrative. Public so the classification
   * rule is testable as the rule it is, rather than only through a full ring.
   */
  public static Optional<String> evidenceKey(Object payload) {
    Optional<RunFrame> frame = RunFrame.from(payload);
    if (frame.isEmpty()) {
      return Optional.empty();
    }
    String event = frame.get().event();
    if (EVIDENCE_EVENTS.contains(event)) {
      return Optional.of(event);
    }
    if (!TOOL_COMPLETED_EVENT.equals(event)) {
      return Optional.empty();
    }
    // A tool result is evidence only when it carries structuredData — the bulk case. A plain
    // success/failure message is narrative, and routing it to the latest-wins slot would COLLAPSE
    // the record of several tool calls into whichever ran last.
    if (!frame.get().data().containsKey(STRUCTURED_DATA_KEY)) {
      return Optional.empty();
    }
    Object callId = frame.get().data().get("callId");
    return Optional.of(
        TOOL_COMPLETED_EVENT + ":" + (callId instanceof String id && !id.isBlank() ? id : "?"));
  }
}
