package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 577 §2.14 Root I — {@link SseWriter#writeResult} must report a serialization failure as
 * {@link SseWriter.SseWriteOutcome#SERIALIZATION_FAILED}, NOT as a client-disconnect. The agent
 * hub-observer eviction keys off {@code CLIENT_GONE} only, so this is what stops a non-serializable
 * event (e.g. a tool's {@code structuredData}) from evicting a live observer + re-poisoning reattaches.
 */
final class SseWriterTest {

  @Test
  @DisplayName("a non-serializable payload is SERIALIZATION_FAILED, never CLIENT_GONE")
  void nonSerializablePayloadIsSerializationFailed() {
    var writer = new SseWriter(null);
    Object poison =
        new Object() {
          @SuppressWarnings("unused") // invoked reflectively by Jackson during serialization
          public String getValue() {
            throw new IllegalStateException("intentionally non-serializable");
          }
        };
    // Serialization is attempted BEFORE any Context is touched, so the null ctx is never
    // dereferenced on this path; if it WERE serializable the test would surface an NPE instead.
    SseWriter.SseWriteOutcome outcome = writer.writeResult(null, "evt", Map.of("v", poison));
    assertEquals(SseWriter.SseWriteOutcome.SERIALIZATION_FAILED, outcome);
  }
}
