/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Signals that an LLM stream ended without the {@code [DONE]} sentinel.
 *
 * <p>This indicates the response was truncated — the TCP connection closed (OOM, restart, network
 * drop) before the LLM server finished generating. The partial content already delivered via {@code
 * onChunk} may be incomplete.
 *
 * <p>Carries the last observed {@code finish_reason} from the stream (if any), which helps
 * diagnose whether the model was mid-generation or had already finished when the connection dropped.
 */
public class StreamTruncatedException extends RuntimeException {

    private final String finishReason;

    public StreamTruncatedException(String finishReason) {
        super(
                "LLM stream ended without [DONE] sentinel"
                        + (finishReason != null
                                ? " (last finish_reason: " + finishReason + ")"
                                : ""));
        this.finishReason = finishReason;
    }

    /** The last {@code finish_reason} observed in the stream, or {@code null} if none was seen. */
    public String finishReason() {
        return finishReason;
    }
}
