/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.health;

import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.LifecycleEvent;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Severity;
import io.justsearch.app.observability.health.Source;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 627 (Deliverable 9, N1): narrates an unclean *previous* app session as a calm one-shot
 * occurrence on the existing RECENT EVENTS substrate.
 *
 * <p>The Head's in-process supervisor recovers the Worker/Brain but is structurally blind to its own
 * death — when the whole app crashes, nothing is left running to notice. The cross-session signal
 * already exists on disk (a leftover runtime manifest with a dead PID — see {@code
 * RuntimeManifestPublisher#classifyPreviousShutdown}); it simply had no user-legible sink. This is
 * that sink. Conforms to <b>Observation-Actuation Closure</b> at the session tier (the same shape as
 * the Worker hang fix, one tier up). The wording deliberately does NOT claim index integrity — that
 * verification is tempdoc 628's authority; 627 surfaces only the <em>event</em>.
 *
 * <p>Lives in app-services (not the Head's {@code modules/ui}) so the {@code
 * HealthEventEmitCoverageTest} — which cannot see {@code modules/ui} — can reconcile {@link
 * #emittableIds()} into its producer set. The Head supplies the verdict + the substrate handles.
 */
public final class BootRecoveryEmitter {

  private static final Logger log = LoggerFactory.getLogger(BootRecoveryEmitter.class);
  private static final String ID = "head.unclean-shutdown-recovered";

  private BootRecoveryEmitter() {}

  /** Occurrence IDs this emitter produces — reconciled by {@code HealthEventEmitCoverageTest}. */
  public static Set<String> emittableIds() {
    return Set.of(ID);
  }

  /**
   * Emit the calm "app recovered from an unclean shutdown" occurrence onto the existing occurrence
   * substrate. Best-effort: never throws into the boot path. {@code previousPid} (when present) rides
   * as a forensic attribute.
   */
  public static void emitUncleanShutdownRecovered(
      OccurrenceLog occurrenceLog,
      HealthEventChangeRegistry changeRegistry,
      Source headSource,
      Clock clock,
      OptionalLong previousPid) {
    try {
      Map<String, Object> attributes = new LinkedHashMap<>();
      previousPid.ifPresent(pid -> attributes.put("previousPid", pid));
      HealthEvent event =
          new HealthEvent(
              ID,
              clock.instant(),
              headSource,
              Severity.INFO,
              Optional.of("health-events." + ID + ".message"),
              new LifecycleEvent(attributes, Optional.empty()));
      occurrenceLog.append(event);
      changeRegistry.broadcast(HealthEventChangeRegistry.Kind.OCCURRENCE_APPENDED, event);
    } catch (Exception e) {
      log.debug("Failed to emit unclean-shutdown-recovered occurrence: {}", e.getMessage());
    }
  }
}
