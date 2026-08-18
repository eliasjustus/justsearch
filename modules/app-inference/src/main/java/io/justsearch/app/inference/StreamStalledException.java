/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.time.Duration;

/**
 * Signals that an LLM response body went silent — no further bytes arrived within the stream's idle
 * deadline — and the read was abandoned.
 *
 * <p>The request timeout on the HTTP client bounds only the arrival of the response <em>headers</em>;
 * once a streaming body is being consumed there is no deadline of its own. A server that answers 200
 * and then stops producing (or never terminates the chunked body) therefore parked the reading thread
 * forever — and, because streaming shares one executor thread and holds the online-request lock for
 * the whole exchange, that park wedged every later inference request in the process. This exception
 * is what that condition raises instead.
 */
public class StreamStalledException extends RuntimeException {

  private final Duration idleDeadline;

  public StreamStalledException(Duration idleDeadline) {
    super(
        "LLM stream produced no data for "
            + (idleDeadline == null ? "the idle deadline" : idleDeadline.toString())
            + "; the read was abandoned");
    this.idleDeadline = idleDeadline;
  }

  /** The idle window that elapsed with no bytes read. */
  public Duration idleDeadline() {
    return idleDeadline;
  }
}
