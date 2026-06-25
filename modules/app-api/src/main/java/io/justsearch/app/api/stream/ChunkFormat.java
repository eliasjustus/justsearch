/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Canonical streaming chunk contract shared between the worker, bridge, and UI layers.
 *
 * <p>This helper replaces the hand-rolled chunkers that previously lived in each module and
 * centralises validation of the lifecycle ({@link Event#START} → {@link Event#DATA}* → {@link
 * Event#END}/{@link Event#CANCELLED}).</p>
 */
public final class ChunkFormat {
  public static final int DEFAULT_MAX_CHARS = 24;

  private ChunkFormat() {}

  /** Lifecycle events emitted for each streaming summary. */
  public enum Event {
    START,
    DATA,
    END,
    CANCELLED;

    public static Event fromLabel(String label) {
      if (label == null || label.isBlank()) {
        return START;
      }
      try {
        return Event.valueOf(label.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        return START;
      }
    }
  }

  /** Adapter used by {@link #validateSequence(List)} to inspect chunk envelopes. */
  public interface ChunkEnvelope {
    Event event();

    int ordinal();

    boolean finalChunk();
  }

  /**
   * Splits {@code text} into whitespace-aware chunks that fit within {@link #DEFAULT_MAX_CHARS}.
   * Empty or blank input yields an empty list to mirror legacy behaviour.
   */
  public static List<String> chunkText(String text) {
    return chunkText(text, DEFAULT_MAX_CHARS);
  }

  /**
   * Splits {@code text} into whitespace-aware chunks that fit within {@code maxChars}. The helper
   * ensures each chunk contains at least one non-whitespace token before emitting unless the text
   * ends with whitespace.
   */
  public static List<String> chunkText(String text, int maxChars) {
    List<String> chunks = new ArrayList<>();
    if (text == null || text.isBlank()) {
      return chunks;
    }
    int limit = Math.max(8, maxChars);
    StringBuilder current = new StringBuilder(limit);
    boolean hasToken = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      current.append(c);
      if (!Character.isWhitespace(c)) {
        hasToken = true;
      }
      boolean atCapacity = current.length() >= limit;
      boolean boundary = Character.isWhitespace(c);
      if (hasToken && (boundary || atCapacity)) {
        chunks.add(current.toString());
        current.setLength(0);
        hasToken = false;
      }
    }
    if (current.length() > 0) {
      chunks.add(current.toString());
    }
    return chunks;
  }

  /**
   * Validates a streaming sequence, ensuring the canonical lifecycle is respected and that the
   * terminal chunk is flagged via {@link ChunkEnvelope#finalChunk()}.
   *
   * @throws IllegalArgumentException when the lifecycle is violated
   */
  public static void validateSequence(List<? extends ChunkEnvelope> envelopes) {
    if (envelopes == null || envelopes.isEmpty()) {
      throw new IllegalArgumentException("Chunk sequence is empty");
    }
    boolean started = false;
    boolean finished = false;
    Event terminal = null;
    int nextOrdinal = 0;
    for (ChunkEnvelope envelope : envelopes) {
      if (envelope == null) {
        throw new IllegalArgumentException("Chunk envelope is null");
      }
      Event event = envelope.event();
      if (!started) {
        if (event != Event.START) {
          throw new IllegalArgumentException("Sequence must begin with START");
        }
        started = true;
        continue;
      }
      if (finished) {
        throw new IllegalArgumentException("Sequence already completed");
      }
      switch (event) {
        case START -> throw new IllegalArgumentException("START may only appear once");
        case DATA -> {
          if (envelope.finalChunk()) {
            throw new IllegalArgumentException("DATA chunk cannot be final");
          }
          if (envelope.ordinal() != nextOrdinal) {
            throw new IllegalArgumentException(
                "Expected ordinal "
                    + nextOrdinal
                    + " but found "
                    + envelope.ordinal());
          }
          nextOrdinal++;
        }
        case END -> {
          if (!envelope.finalChunk()) {
            throw new IllegalArgumentException("END chunk must be marked final");
          }
          terminal = Event.END;
          finished = true;
        }
        case CANCELLED -> {
          terminal = Event.CANCELLED;
          finished = true;
        }
        default -> throw new IllegalArgumentException("Unknown chunk event: " + event);
      }
    }
    if (!started) {
      throw new IllegalArgumentException("Sequence never emitted START");
    }
    if (!finished) {
      throw new IllegalArgumentException("Sequence never emitted END/CANCELLED");
    }
    if (terminal == null) {
      throw new IllegalArgumentException("Sequence missing terminal event");
    }
  }
}
