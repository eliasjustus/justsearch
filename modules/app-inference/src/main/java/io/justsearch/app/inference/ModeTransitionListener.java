/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.Mode;
import io.justsearch.app.inference.telemetry.TransitionReason;

/**
 * Tempdoc 837 §D.2 (option c) — a mode-change listener that also carries WHY the transition
 * happened.
 *
 * <p>{@link io.justsearch.app.api.ModeChangeListener} is {@code (from, to)} only, so crash recovery
 * and a user deactivation both arrive as a bare {@code * → OFFLINE} and the capability derived one
 * generic reason for both. The reason already exists at the notify site — {@link TransitionRunner}
 * carries a {@link TransitionReason} through {@code run} / {@code runForceOffline}, and crash
 * recovery specifically passes {@link TransitionReason#CRASH_RECOVERY}.
 *
 * <p><b>Why this interface lives HERE and not in {@code app-api}.</b> Three placements were measured
 * (§D.2): moving {@code TransitionReason} into {@code app-api} touches 18 files and puts a second
 * same-simple-named type into a widely-imported package (an unrelated
 * {@code io.justsearch.ort.telemetry.TransitionReason} has 5 main-source references and must NEVER
 * be confused with this one — they share a name, not a reason to change); an {@code app-api}-local
 * mirror enum forks a 10-member vocabulary that would have to stay in sync forever. Declaring the
 * listener beside the enum costs 3 files: {@code app-services} already declares
 * {@code api(project(":modules:app-inference"))}, so the one consumer that needs the reason names it
 * where it already lives. {@code ModeChangeListener} is not touched at all.
 */
@FunctionalInterface
public interface ModeTransitionListener {

  /**
   * @param from the mode the runtime left
   * @param to the mode it entered
   * @param reason why — never {@code null}; {@link TransitionReason#UNKNOWN} when the originating
   *     call site supplied none
   */
  void onModeTransition(Mode from, Mode to, TransitionReason reason);
}
