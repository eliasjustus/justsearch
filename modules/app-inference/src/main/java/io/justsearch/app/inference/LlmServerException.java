/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Typed exception for HTTP errors returned by the LLM server (llama-server).
 *
 * <p>Replaces the generic {@code RuntimeException("Server returned status ...")} pattern,
 * allowing callers to classify errors by HTTP status code instead of message-sniffing.
 */
public class LlmServerException extends RuntimeException {

    private final int httpStatus;

    public LlmServerException(int httpStatus, String body) {
        super("Server returned status " + httpStatus + (body != null ? ": " + body : ""));
        this.httpStatus = httpStatus;
    }

    /** The HTTP status code returned by the LLM server. */
    public int httpStatus() {
        return httpStatus;
    }
}
