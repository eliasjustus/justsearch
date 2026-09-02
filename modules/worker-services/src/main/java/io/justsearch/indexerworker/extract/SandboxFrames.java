/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-prefixed frame codec for the persistent extraction sandbox protocol (tempdoc 885 item 14).
 *
 * <p>A frame is a 4-byte big-endian unsigned length followed by that many bytes of UTF-8 JSON. The
 * one-process-per-file predecessor could use "read stdin to EOF" as its framing because the child
 * exited after one response; a persistent child multiplexes many request/response pairs over one
 * pipe, so the length prefix is what makes "where does this message end" answerable without
 * closing the stream.
 *
 * <p>The child keeps the real {@code System.out} as the protocol channel and redirects
 * {@code System.out} to stderr at startup ({@link ExtractionSandboxChild}), so parser chatter
 * cannot be mistaken for a frame.
 */
final class SandboxFrames {

  /** Hard ceiling on a single frame, independent of the caller's response budget. */
  static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;

  private SandboxFrames() {}

  static void write(OutputStream out, byte[] payload) throws IOException {
    int length = payload.length;
    out.write((length >>> 24) & 0xFF);
    out.write((length >>> 16) & 0xFF);
    out.write((length >>> 8) & 0xFF);
    out.write(length & 0xFF);
    out.write(payload);
    out.flush();
  }

  /**
   * Reads one frame.
   *
   * @return the frame payload, or {@code null} on a clean end of stream (peer exited between
   *     frames)
   * @throws EOFException if the stream ends mid-frame
   * @throws SandboxProtocolException if the declared length is negative or exceeds {@code maxBytes}
   */
  static byte[] read(InputStream in, int maxBytes) throws IOException {
    int b0 = in.read();
    if (b0 < 0) {
      return null;
    }
    int b1 = in.read();
    int b2 = in.read();
    int b3 = in.read();
    if (b1 < 0 || b2 < 0 || b3 < 0) {
      throw new EOFException("Truncated sandbox frame header");
    }
    int length = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    int ceiling = Math.min(maxBytes, MAX_FRAME_BYTES);
    if (length < 0 || length > ceiling) {
      throw new SandboxProtocolException(
          "Sandbox frame length " + length + " outside [0," + ceiling + "]");
    }
    byte[] payload = new byte[length];
    int read = 0;
    while (read < length) {
      int n = in.read(payload, read, length - read);
      if (n < 0) {
        throw new EOFException("Truncated sandbox frame body: " + read + "/" + length);
      }
      read += n;
    }
    return payload;
  }

  /** A frame header that cannot be honoured — a protocol break, not an I/O failure. */
  static final class SandboxProtocolException extends IOException {
    SandboxProtocolException(String message) {
      super(message);
    }
  }
}
