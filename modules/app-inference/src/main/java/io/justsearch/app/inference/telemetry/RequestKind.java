/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.telemetry;

/**
 * Bounded enum of request kinds tagged on {@code inference.request.*} metrics. {@code CHAT}
 * covers chat-completion and chat-streaming flows; {@code VISION} covers vision
 * (image-grounded) requests acquired via the same online request lock.
 */
public enum RequestKind {
  CHAT("chat"),
  VISION("vision"),
  STREAM("stream"),
  SUMMARY("summary");

  private final String wireValue;

  RequestKind(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
