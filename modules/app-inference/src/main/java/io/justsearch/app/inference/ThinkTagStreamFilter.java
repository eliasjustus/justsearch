/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.util.function.Consumer;

/**
 * Stateful {@code <think>} filter for the streaming content channel (tempdoc 835 §5.3) — the one
 * authority for keeping reasoning markup out of answer text.
 *
 * <p>With {@code --reasoning-format deepseek} the model's reasoning arrives on
 * {@code delta.reasoning_content}. Some builds and configurations leak it inline instead, as
 * {@code <think>…</think>} (or a lone closing tag) inside {@code delta.content}. Two properties
 * make this a filter rather than a {@code replaceAll}:
 *
 * <ul>
 *   <li><b>Straddle-safe.</b> A tag can split across SSE frames ({@code "<thi"} + {@code "nk>"}),
 *       so a per-chunk regex would pass it through. Only a suffix that could still become a tag is
 *       held back; everything else is released immediately, so streaming stays live.
 *   <li><b>Rerouted, not deleted.</b> Text between the tags is fed to the reasoning channel, so a
 *       build that emits inline thinking behaves identically to one that emits
 *       {@code reasoning_content} — deleting it would make the model look like it never thought.
 * </ul>
 *
 * <p>Content with no tags passes through byte-identically (concatenating every {@link #accept}
 * result plus {@link #flush} reproduces the input exactly). A lone {@code </think>} — what a build
 * emits at a zero reasoning budget — has its tag dropped; the text before it was already streamed
 * as content and cannot be reclassified retroactively. On a truncated stream, whatever is buffered
 * mid-reasoning is flushed to the reasoning channel rather than silently discarded.
 *
 * <p>Not thread-safe: one instance per stream, driven by that stream's single parse loop.
 */
final class ThinkTagStreamFilter {

  private static final String OPEN = "<think>";
  private static final String CLOSE = "</think>";

  private final Consumer<String> reasoningSink;
  private final StringBuilder pending = new StringBuilder();
  private boolean inThink;

  /** @param reasoningSink where captured thinking text is rerouted; null discards it. */
  ThinkTagStreamFilter(Consumer<String> reasoningSink) {
    this.reasoningSink = reasoningSink;
  }

  /** Feeds one content delta; returns the text that is safe to emit as answer content. */
  String accept(String chunk) {
    if (chunk == null || chunk.isEmpty()) {
      return "";
    }
    pending.append(chunk);
    StringBuilder visible = new StringBuilder();
    while (true) {
      if (inThink) {
        int close = pending.indexOf(CLOSE);
        if (close >= 0) {
          emitReasoning(pending.substring(0, close));
          pending.delete(0, close + CLOSE.length());
          inThink = false;
          continue;
        }
        int held = heldBackLength(pending, CLOSE);
        if (pending.length() > held) {
          emitReasoning(pending.substring(0, pending.length() - held));
          pending.delete(0, pending.length() - held);
        }
        return visible.toString();
      }

      int open = pending.indexOf(OPEN);
      int close = pending.indexOf(CLOSE);
      if (open >= 0 && (close < 0 || open < close)) {
        visible.append(pending, 0, open);
        pending.delete(0, open + OPEN.length());
        inThink = true;
        continue;
      }
      if (close >= 0) {
        visible.append(pending, 0, close);
        pending.delete(0, close + CLOSE.length());
        continue;
      }
      int held = Math.max(heldBackLength(pending, OPEN), heldBackLength(pending, CLOSE));
      if (pending.length() > held) {
        visible.append(pending, 0, pending.length() - held);
        pending.delete(0, pending.length() - held);
      }
      return visible.toString();
    }
  }

  /** Releases whatever was held back at end of stream; returns trailing answer content. */
  String flush() {
    if (pending.isEmpty()) {
      return "";
    }
    String rest = pending.toString();
    pending.setLength(0);
    if (inThink) {
      emitReasoning(rest);
      return "";
    }
    return rest;
  }

  private void emitReasoning(String text) {
    if (reasoningSink != null && !text.isEmpty()) {
      reasoningSink.accept(text);
    }
  }

  /** Length of the longest suffix of {@code buf} that is a proper prefix of {@code tag}. */
  private static int heldBackLength(CharSequence buf, String tag) {
    int max = Math.min(tag.length() - 1, buf.length());
    for (int k = max; k > 0; k--) {
      if (endsWithPrefix(buf, tag, k)) {
        return k;
      }
    }
    return 0;
  }

  private static boolean endsWithPrefix(CharSequence buf, String tag, int k) {
    int offset = buf.length() - k;
    for (int i = 0; i < k; i++) {
      if (buf.charAt(offset + i) != tag.charAt(i)) {
        return false;
      }
    }
    return true;
  }
}
