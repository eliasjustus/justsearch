/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.diagnostic;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Centralizes the SLF4J/Logback marker that excludes log emissions from the
 * DiagnosticChannel substrate's own delivery path.
 *
 * <p>Per slice 448 §4 + phase 3 D4: any code that runs as part of the SSE delivery
 * machinery (the appender's {@code append()} body, the registry's broadcast loop, the
 * controller's frame-emit thread, etc.) MUST mark its log calls with this marker so the
 * substrate's recursion carve-out can drop them before they re-enter the channel.
 *
 * <p>Mechanism: {@link DiagnosticChannelAppender#append} performs an
 * early-return when the {@link org.slf4j.event.LoggingEvent} carries this marker (or any
 * compound marker that contains it). Combined with Logback's built-in
 * {@code AppenderBase.doAppend()} per-thread recursion guard (handles same-thread
 * re-entry), this prevents the firehose from observing itself.
 *
 * <p>Centralizing the marker here ensures emit and filter sites cannot drift on the
 * literal name; both reference {@link #NAME} and {@link #get()}.
 */
public final class DiagnosticChannelInternalMarker {

  /** Wire-stable marker name. Used by both emit sites (logger.warn(get(), ...)) and filter. */
  public static final String NAME = "justsearch.diagnostic.delivery-internal";

  private static final Marker MARKER = MarkerFactory.getMarker(NAME);

  private DiagnosticChannelInternalMarker() {}

  /** Returns the SLF4J Marker singleton for delivery-internal log emissions. */
  public static Marker get() {
    return MARKER;
  }

  /**
   * Returns true if the supplied marker reference (which may be null, equal to the
   * delivery-internal marker, or a compound marker that contains it) should cause the
   * appender to drop the event.
   */
  public static boolean matches(Marker candidate) {
    if (candidate == null) {
      return false;
    }
    if (candidate.equals(MARKER) || NAME.equals(candidate.getName())) {
      return true;
    }
    return candidate.contains(MARKER) || candidate.contains(NAME);
  }
}
