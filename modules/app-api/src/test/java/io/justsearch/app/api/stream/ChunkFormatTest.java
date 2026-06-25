package io.justsearch.app.api.stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.stream.ChunkFormat.ChunkEnvelope;
import io.justsearch.app.api.stream.ChunkFormat.Event;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ChunkFormatTest {

  @Test
  void chunkTextPreservesWhitespaceBoundaries() {
    List<String> chunks = ChunkFormat.chunkText("Hello world from shared chunker");
    assertEquals(List.of("Hello ", "world ", "from ", "shared ", "chunker"), chunks);
  }

  @Test
  void validateSequenceHonoursLifecycle() {
    List<ChunkEnvelope> envelopes =
        List.of(
            envelope(Event.START, 0, false),
            envelope(Event.DATA, 0, false),
            envelope(Event.DATA, 1, false),
            envelope(Event.END, 2, true));
    assertDoesNotThrow(() -> ChunkFormat.validateSequence(envelopes));
  }

  @Test
  void validateSequenceRejectsMissingStart() {
    List<ChunkEnvelope> envelopes = List.of(envelope(Event.DATA, 0, false), envelope(Event.END, 1, true));
    assertThrows(IllegalArgumentException.class, () -> ChunkFormat.validateSequence(envelopes));
  }

  @Test
  void validateSequenceAcceptsCancelledTerminalEvent() {
    List<ChunkEnvelope> envelopes =
        List.of(envelope(Event.START, 0, false), envelope(Event.DATA, 0, false), envelope(Event.CANCELLED, 0, false));
    assertDoesNotThrow(() -> ChunkFormat.validateSequence(envelopes));
  }

  private static ChunkEnvelope envelope(Event event, int ordinal, boolean finalChunk) {
    return new ChunkEnvelope() {
      @Override
      public Event event() {
        return event;
      }

      @Override
      public int ordinal() {
        return ordinal;
      }

      @Override
      public boolean finalChunk() {
        return finalChunk;
      }
    };
  }
}
