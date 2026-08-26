/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.interaction;

import java.util.List;
import java.util.Map;

/**
 * Tempdoc 871 §3b — the ACTION plane's contribution to the unified thread, plus the one fact that
 * plane cannot place on its own.
 *
 * <p>The reasoning fold attaches a block to the next event that PROJECTS (848 §2.4), and on an agent
 * run that is normally the terminal answer. Tempdoc 863 then suppresses that answer for a stamped run
 * whose answer the ANSWER plane already holds — which deletes the carrier of the run's last thinking
 * block. Re-homing it onto the run's last surviving event (what 863 did, and named a "KNOWN
 * INVERSION") puts it on an event that happened BEFORE it, and the FE draws a carrier's blocks above
 * the carrier — so a search card rendered AFTER the thought that analysed its results.
 *
 * <p>The correct carrier is the ANSWER plane's copy of that same answer, and only the thread
 * controller sees both planes. So this record hands the orphaned blocks across, keyed by run, instead
 * of guessing at a carrier inside the action plane. {@link #events()} is what the plane itself
 * projects; {@link #trailingReasoningByRun()} is empty for every run whose answer was NOT suppressed
 * (that run still carries its own trailing block on its own terminal answer).
 */
public record ThreadProjection(
    List<InteractionEvent> events, Map<String, List<Map<String, Object>>> trailingReasoningByRun) {

  public ThreadProjection {
    events = List.copyOf(events);
    trailingReasoningByRun = Map.copyOf(trailingReasoningByRun);
  }

  /** The projection of a plane that has no suppressed answers to hand across. */
  public static ThreadProjection of(List<InteractionEvent> events) {
    return new ThreadProjection(events, Map.of());
  }
}
