/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.StreamId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Opaque server-side codec for SSE envelope resume tokens.
 *
 * <p>Per slice 436 §B.4: the simplest correct implementation is base64-encoded
 * {@code (streamId, seq)} tuple. Documented as opaque so the format can change without
 * a protocol break.
 *
 * <p>Token format (intentionally undocumented at the wire boundary): the colon-separated
 * pair {@code "<streamId>:<seq>"}, base64-URL-encoded. {@link #encode} produces the
 * token; {@link #decode} parses it. Decoding rejects malformed tokens by returning
 * {@link Optional#empty()} — the controller treats this identically to "token outside
 * the resume window" (emits {@code reset + snapshot}).
 *
 * <p>The streamId portion ensures that a token from one stream cannot be used to resume
 * a different stream — a minor defense in depth against client misuse.
 */
public final class ResumeTokenCodec {

  private ResumeTokenCodec() {}

  /**
   * Encodes {@code (streamId, seq)} into an opaque base64-URL token.
   *
   * <p>Rejects {@code seq < 0} symmetrically with {@link #decode} (which returns empty on a negative
   * seq) and with {@link Decoded}'s invariant, so {@code decode(encode(streamId, seq))} round-trips
   * for every valid input.
   */
  public static String encode(StreamId streamId, long seq) {
    Objects.requireNonNull(streamId, "streamId");
    if (seq < 0) {
      throw new IllegalArgumentException("seq must be >= 0");
    }
    String raw = streamId.value() + ":" + seq;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Attempts to decode an opaque token into its {@code (streamId, seq)} parts. Returns
   * {@link Optional#empty()} on any decode failure (malformed base64, missing separator,
   * non-numeric seq, invalid streamId pattern). Callers should treat decode failure
   * identically to "token outside resume window."
   */
  public static Optional<Decoded> decode(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(token);
      String raw = new String(decoded, StandardCharsets.UTF_8);
      // streamId itself contains a colon, so split on the LAST colon to separate seq.
      int lastColon = raw.lastIndexOf(':');
      if (lastColon < 0) {
        return Optional.empty();
      }
      String streamIdRaw = raw.substring(0, lastColon);
      String seqRaw = raw.substring(lastColon + 1);
      long seq = Long.parseLong(seqRaw);
      if (seq < 0) {
        return Optional.empty();
      }
      StreamId streamId = new StreamId(streamIdRaw);
      return Optional.of(new Decoded(streamId, seq));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Decoded resume-token contents. */
  public record Decoded(StreamId streamId, long seq) {
    public Decoded {
      Objects.requireNonNull(streamId, "streamId");
      if (seq < 0) {
        throw new IllegalArgumentException("seq must be >= 0");
      }
    }
  }
}
