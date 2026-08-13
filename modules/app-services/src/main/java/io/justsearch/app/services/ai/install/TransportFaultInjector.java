/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.EnvRegistry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev/test-only synthetic transport failures, so a reliability fix can be verified by TRIGGERING the
 * failure instead of hoping the network produces one.
 *
 * <p>{@code -Djustsearch.ai.install.faultInjectPct=40} makes ~40 % of transport attempts fail as a
 * synthetic curl exit 52 (empty reply from server) — the exact code round 16's environment produced.
 * A sandbox round can then assert that spaced retries actually converge, rather than passing because
 * the network happened to be healthy (the {@code green-masked-destructive} precondition).
 *
 * <p><b>Refused in production.</b> When {@code justsearch.prod} / {@code JUSTSEARCH_PROD} is set, the
 * property is ignored and a WARN is logged once. A shipped build must not be able to break its own
 * downloads because a stray property survived into it.
 */
public final class TransportFaultInjector {

  private static final Logger log = LoggerFactory.getLogger(TransportFaultInjector.class);

  /** System property holding the injected failure percentage (0-100). */
  public static final String PCT_PROPERTY = "justsearch.ai.install.faultInjectPct";

  private static final AtomicBoolean prodRefusalLogged = new AtomicBoolean();
  private static final AtomicBoolean malformedLogged = new AtomicBoolean();
  private static final AtomicBoolean armedLogged = new AtomicBoolean();

  private TransportFaultInjector() {}

  /**
   * True when this transport attempt should fail synthetically.
   *
   * @param pct the configured percentage, read by the caller — {@code DownloadExecutor} owns the
   *     property read because the app-services env/sysprop guardrail allowlists it and not this
   *     class (see {@code AppServicesWorkerGuardrailsTest})
   */
  static boolean shouldFailAttempt(int pct) {
    return shouldFailAttempt(pct, ThreadLocalRandom.current().nextInt(100));
  }

  /**
   * Deterministic form: {@code roll} is a draw in {@code [0, 100)}, so a test pins the decision
   * without pinning the RNG.
   */
  static boolean shouldFailAttempt(int pct, int roll) {
    if (pct <= 0) return false;
    if (isProduction()) {
      if (prodRefusalLogged.compareAndSet(false, true)) {
        log.warn(
            "{}={} ignored: transport fault injection is refused in production builds",
            PCT_PROPERTY,
            pct);
      }
      return false;
    }
    if (armedLogged.compareAndSet(false, true)) {
      log.warn("Transport fault injection ARMED at {}% ({}) - downloads will fail on purpose",
          pct, PCT_PROPERTY);
    }
    return roll < pct;
  }

  /** The failure a synthetic fault reports, matching what round 16's environment produced. */
  static TransportFailure syntheticFailure() {
    return TransportFailure.curlExit(
        52, "synthetic transport fault injected via " + PCT_PROPERTY);
  }

  /** Parses the raw property value, clamped to {@code [0, 100]}; 0 (off) when absent or malformed. */
  static int parsePct(String raw) {
    if (raw == null || raw.isBlank()) return 0;
    try {
      return Math.max(0, Math.min(100, Integer.parseInt(raw.trim())));
    } catch (NumberFormatException e) {
      if (malformedLogged.compareAndSet(false, true)) {
        log.warn("{}={} is not an integer 0-100; fault injection stays off", PCT_PROPERTY, raw);
      }
      return 0;
    }
  }

  /**
   * Production is the same authority the rest of the app uses ({@link EnvRegistry#PROD_MODE}, i.e.
   * {@code justsearch.prod} / {@code JUSTSEARCH_PROD}).
   */
  private static boolean isProduction() {
    return EnvRegistry.PROD_MODE.getBoolean(false);
  }

  /** Test hook: forget which one-shot warnings have already been emitted. */
  static void resetWarningState() {
    prodRefusalLogged.set(false);
    malformedLogged.set(false);
    armedLogged.set(false);
  }
}
