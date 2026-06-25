/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

/**
 * Sealed-style enum for streaming transport tag values. Today only SSE is supported; WS is
 * reserved for future expansion.
 *
 * <p>Tempdoc 417 Phase 2c (relocated from {@code modules/ui} in F1).
 */
public enum StreamTransport {
  SSE("sse");

  private final String wire;

  StreamTransport(String wire) {
    this.wire = wire;
  }

  public String wireValue() {
    return wire;
  }
}
