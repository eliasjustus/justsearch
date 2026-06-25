/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

/** Distributed trace identity attached to every agent event. */
public record TraceContext(
    String traceId,
    String stepId,
    String spanId,
    String parentSpanId,
    String agentId,
    String toolCallId,
    int iteration) {

  private static final TraceContext NONE = new TraceContext(null, null, null, null, null, null, 0);

  /** Sentinel for events where trace context is not yet available. */
  public static TraceContext none() {
    return NONE;
  }

  /** Alias for traceId in agent contracts where this is called runId. */
  public String runId() {
    return traceId;
  }

  /** True when any identity field is populated. */
  public boolean hasIdentity() {
    return traceId != null
        || stepId != null
        || spanId != null
        || parentSpanId != null
        || agentId != null
        || toolCallId != null
        || iteration > 0;
  }

  /**
   * Tempdoc 585 §D Phase 2 (B1) — the per-run monotonic event sequence, parsed from the {@code
   * spanId} the {@code AgentEventTracing.Sequencer} stamps as {@code span-NNNNNN}. This IS the SSE
   * {@code Last-Event-ID} of the event: it is strictly increasing across every event of a run, so a
   * reattaching client can resume precisely from the last id it saw instead of replaying the whole
   * buffer. Returns {@code -1} when there is no parseable span id (an untraced event), which the
   * replay filter treats as "older than any real cursor".
   */
  public long seq() {
    if (spanId == null) {
      return -1L;
    }
    int dash = spanId.lastIndexOf('-');
    if (dash < 0 || dash + 1 >= spanId.length()) {
      return -1L;
    }
    try {
      return Long.parseLong(spanId.substring(dash + 1));
    } catch (NumberFormatException notNumeric) {
      return -1L;
    }
  }
}
