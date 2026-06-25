/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Tempdoc 627 (N2): forensic context the supervisor already computes at a recovery decision, carried
 * onto the recovery occurrence so RECENT EVENTS can say <em>which</em> attempt and <em>why</em>, not
 * just "restarting".
 *
 * <ul>
 *   <li>{@code attempt} — the 1-based restart attempt number ({@code SupervisionDecision.nextAttempt}).
 *   <li>{@code faultKind} — {@code "hang"} (alive-but-unresponsive → graceful restart) or {@code "death"}
 *       (process gone → respawn), derived from the decision's {@code Action}.
 *   <li>{@code backoffMs} — the cooldown slept before the respawn.
 * </ul>
 *
 * <p>The most-recent value is parked on {@link io.justsearch.app.services.lifecycle.WorkerCapability}
 * so the capability-health bridge — which observes the RECOVERING/READY transition the supervision
 * callback drives — can read it when it emits the occurrence (the transition fires listeners
 * synchronously, so the parked context is the fresh one).
 */
public record RecoveryContext(int attempt, String faultKind, long backoffMs) {}
