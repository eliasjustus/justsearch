/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.SseEnvelope;
import java.util.Map;

/**
 * Estimates the <em>retained heap</em> cost of holding an {@link SseEnvelope} in a
 * {@link FrameHistoryRingBuffer}.
 *
 * <p>Tempdoc 834 §2: "The sizer measures <em>retained</em> bytes (fixed per-frame overhead +
 * payload string content), not wire size." Serializing every frame to measure it would cost
 * more than the bound saves at 30 fps, so this is deliberately an estimate, and the byte
 * bound is a memory guardrail rather than an exact accountant.
 *
 * <p><strong>Honest limits.</strong> Two constants below are estimates pending tempdoc 834's
 * probe P2 (retained bytes and frame counts per answer, measured per effort tier). Payloads
 * are walked as maps / iterables / char sequences — the shape run frames actually use, since
 * they are wire-projected {@code (name, payload)} pairs. A payload that is a typed record is
 * charged {@link #OPAQUE_VALUE_BYTES} without recursion; that under-counts, and is only
 * sound because the streams that publish typed records (the 18 catalog routes) run with
 * {@link FrameRetentionPolicy#DEFAULT}, whose unbounded byte budget never invokes this class
 * at all.
 */
final class FrameRetentionSizer {

  /**
   * Fixed per-frame retained overhead: the {@code SseEnvelope} record itself, its
   * {@code StreamId} / {@code SseFrameKind} / {@code Instant} references, the resume token
   * string, and the deque node. Estimate pending P2.
   */
  static final int FRAME_OVERHEAD_BYTES = 200;

  /** Charged for a payload value this sizer does not walk (numbers, booleans, records). */
  static final int OPAQUE_VALUE_BYTES = 16;

  /** Charged per map entry / collection element for its container node. */
  static final int CONTAINER_ENTRY_BYTES = 32;

  /** Payload nesting beyond this depth is charged as opaque rather than walked. */
  static final int MAX_DEPTH = 12;

  private FrameRetentionSizer() {}

  /** Estimated retained bytes for one frame. Never negative, never zero. */
  static long retainedBytes(SseEnvelope frame) {
    long tokenBytes = frame.resumeToken() == null ? 0L : charBytes(frame.resumeToken().length());
    return FRAME_OVERHEAD_BYTES + tokenBytes + valueBytes(frame.payload(), 0);
  }

  private static long valueBytes(Object value, int depth) {
    if (value == null) {
      return 0L;
    }
    if (depth > MAX_DEPTH) {
      return OPAQUE_VALUE_BYTES;
    }
    if (value instanceof CharSequence text) {
      return charBytes(text.length());
    }
    if (value instanceof Map<?, ?> map) {
      long total = 0L;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        total +=
            CONTAINER_ENTRY_BYTES
                + valueBytes(entry.getKey(), depth + 1)
                + valueBytes(entry.getValue(), depth + 1);
      }
      return total;
    }
    if (value instanceof Iterable<?> items) {
      long total = 0L;
      for (Object item : items) {
        total += CONTAINER_ENTRY_BYTES + valueBytes(item, depth + 1);
      }
      return total;
    }
    if (value instanceof Object[] items) {
      long total = 0L;
      for (Object item : items) {
        total += CONTAINER_ENTRY_BYTES + valueBytes(item, depth + 1);
      }
      return total;
    }
    return OPAQUE_VALUE_BYTES;
  }

  /** Two bytes per char — the non-compact (UTF-16) worst case. */
  private static long charBytes(int length) {
    return 2L * length;
  }
}
