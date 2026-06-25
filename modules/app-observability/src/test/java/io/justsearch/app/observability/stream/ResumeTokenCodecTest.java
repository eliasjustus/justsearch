package io.justsearch.app.observability.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.StreamId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ResumeTokenCodec")
final class ResumeTokenCodecTest {

  @Test
  @DisplayName("encode → decode round-trips streamId + seq")
  void roundTrip() {
    StreamId id = StreamId.registry("capabilities");
    String token = ResumeTokenCodec.encode(id, 42L);

    Optional<ResumeTokenCodec.Decoded> decoded = ResumeTokenCodec.decode(token);
    assertTrue(decoded.isPresent());
    assertEquals(id, decoded.get().streamId());
    assertEquals(42L, decoded.get().seq());
  }

  @Test
  @DisplayName("decode of malformed token returns empty")
  void decodeMalformed() {
    assertTrue(ResumeTokenCodec.decode("not-base64-+++").isEmpty());
    assertTrue(ResumeTokenCodec.decode(null).isEmpty());
    assertTrue(ResumeTokenCodec.decode("").isEmpty());
  }

  @Test
  @DisplayName("decode of base64 without separator returns empty")
  void decodeMissingSeparator() {
    // base64 of "abc" has no colon
    String bad = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("abc".getBytes());
    assertTrue(ResumeTokenCodec.decode(bad).isEmpty());
  }

  @Test
  @DisplayName("decode of bad-streamId-shape returns empty")
  void decodeBadStreamId() {
    String bad = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("not-a-stream-id:42".getBytes());
    assertTrue(ResumeTokenCodec.decode(bad).isEmpty());
  }

  @Test
  @DisplayName("decode of non-numeric seq returns empty")
  void decodeNonNumericSeq() {
    String bad = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("registry:capabilities:notnumeric".getBytes());
    assertTrue(ResumeTokenCodec.decode(bad).isEmpty());
  }

  @Test
  @DisplayName("encode is deterministic for same inputs")
  void encodeDeterministic() {
    StreamId id = StreamId.surface("health-events");
    String t1 = ResumeTokenCodec.encode(id, 100L);
    String t2 = ResumeTokenCodec.encode(id, 100L);
    assertEquals(t1, t2);
  }
}
