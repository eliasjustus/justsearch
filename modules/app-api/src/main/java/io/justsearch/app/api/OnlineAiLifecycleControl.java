/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;


/**
 * Lifecycle-control surface for the inference runtime. Tempdoc 518 P4 — the fourth role-typed
 * interface alongside {@link OnlineAiService} (user-facing operations),
 * {@link OnlineAiRuntimeControl} (operator config-apply / detach), and
 * {@link OnlineAiRuntimeIntrospection} (read-only diagnostics).
 *
 * <p>This interface hosts the six methods the bootstrap, VDU, and offline-coordinator paths
 * need that don't fit the other three role types:
 *
 * <ul>
 *   <li>{@link #switchToOnlineMode()} / {@link #switchToIndexingMode()} — bootstrap auto-start
 *       and the offline coordinator's phase sequencing.
 *   <li>{@link #enterVduMode()} / {@link #exitVduMode()} — VDU processor's vision-safe-flag
 *       restart pair.
 *   <li>{@link #addModeChangeListener(ModeChangeListener)} /
 *       {@link #removeModeChangeListener(ModeChangeListener)} — bootstrap GPU-broadcast
 *       observer registration.
 * </ul>
 *
 * <p>Production implementation: {@code OnlineAiServiceImpl} delegates to
 * {@code InferenceLifecycleManager}. External consumers (other modules) hold this interface,
 * never the implementation class. An ArchUnit rule (Slice 3 / P4) enforces the boundary.
 */
public interface OnlineAiLifecycleControl {

  /**
   * Start the inference runtime in Online Mode. Idempotent — already-online is a no-op
   * success. Tempdoc 518 P1 envelope: lock-held, single notify per transition, typed events.
   */
  void switchToOnlineMode() throws ModeTransitionException;

  /**
   * Stop the inference runtime and switch to Indexing Mode. Idempotent — already-indexing is
   * a no-op success. Clears external-adoption state on transition completion (tempdoc 518
   * P1 uniform cleanup — dissolves the prior pre-518 cleanup-omission bug).
   */
  void switchToIndexingMode() throws ModeTransitionException;

  /**
   * Enter VDU (Vision Document Understanding) mode by restarting llama-server with vision-safe
   * flags ({@code -np 1}, {@code --cache-ram 0}). Requires ONLINE phase; idempotent within
   * VDU mode.
   *
   * <p>Callers <b>must</b> call {@link #exitVduMode()} in a {@code finally} block to restore
   * the normal server configuration.
   */
  void enterVduMode() throws ModeTransitionException;

  /**
   * Exit VDU mode and restore the prior non-VDU configuration. No-op if not in VDU mode. Safe
   * to call from a {@code finally} block.
   */
  void exitVduMode() throws ModeTransitionException;

  /**
   * Register a listener to be notified of every mode change. The intermediate
   * {@link Mode#TRANSITIONING} state is visible on this path; typed
   * {@code InferenceTelemetryEvents.onTransition} suppresses the half-event.
   */
  void addModeChangeListener(ModeChangeListener listener);

  /** Unregister a previously-added listener. No-op if the listener wasn't registered. */
  void removeModeChangeListener(ModeChangeListener listener);

  /**
   * Returns true when the runtime FSM is in the ONLINE phase. Tempdoc 518 Appendix F W4.2 —
   * exposed on this interface so {@code OfflineCoordinator} can be migrated off the concrete
   * {@code InferenceLifecycleManager} dependency. Semantically distinct from
   * {@code OnlineAiService.isAvailable()} (which adds a process-health gate).
   */
  default boolean isOnline() {
    return false;
  }

  /** Returns true when the runtime FSM is in the INDEXING phase. Tempdoc 518 Appendix F W4.2. */
  default boolean isIndexing() {
    return false;
  }
}
