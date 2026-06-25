/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Sink for completed inference-runtime transitions.
 *
 * <p>Tempdoc 518 Appendix G Wave B.1. {@link TransitionRunner} calls {@link #record} on every
 * commit + rollback + force-offline (the same site that populates the in-memory transition
 * ring buffer). The sink is the persistence axis; the ring buffer is the API-facing
 * snapshot. Independent for two reasons:
 *
 * <ul>
 *   <li>Lifetime: ring buffer is in-memory only (clears on process restart); the persistent
 *       sink survives the JVM's lifetime so a recorded transition can be replayed in tests
 *       after the fact.
 *   <li>Concerns: the ring buffer is sized for "what happened recently" UI rendering; the
 *       persistent sink can rotate independently and is sized for forensic depth.
 * </ul>
 *
 * <p>Default implementation is a no-op ({@link #NOOP}), so production ILMs in tests +
 * environments without a configured data directory pay zero overhead.
 */
public interface InferenceTransitionLog {

  InferenceTransitionLog NOOP =
      (timestampMs, from, to, reason, success, durationMs, wireCode, generation) -> {};

  /**
   * Records a single completed transition. Called from inside {@code TransitionRunner.run} (or
   * {@code runForceOffline}) after the in-memory ring buffer is updated. Best-effort: the
   * runner does not abort on a thrown {@link RuntimeException} from this method; it logs and
   * continues.
   *
   * @param timestampMs wall-clock ms when the transition completed
   * @param from FSM mode wireValue before the transition (e.g., "OFFLINE")
   * @param to FSM mode wireValue after the transition (e.g., "ONLINE"; equals the rolled-back
   *     state on failure paths)
   * @param reason transition reason wireValue (e.g., "USER_SWITCH", "CRASH_RECOVERY")
   * @param success {@code true} on commit, {@code false} on rollback / forced-offline-with-failure
   * @param durationMs elapsed time between begin and commit/rollback
   * @param wireCode failure wire code (nullable; non-null only when {@code success=false})
   * @param generation generation counter value at recording time
   */
  void record(
      long timestampMs,
      String from,
      String to,
      String reason,
      boolean success,
      long durationMs,
      String wireCode,
      long generation);
}
