/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.BootTrace;
import io.justsearch.app.services.bootstrap.PhaseRecord;
import java.util.List;
import java.util.Optional;

/**
 * Tempdoc 541 §5.1 — Brain process composition root.
 *
 * <p><strong>Critical design note per §9.1 C2:</strong> tempdoc 518's
 * {@link InferenceLifecycleManager} is <em>not</em> phase-decomposable. It is a stateful
 * service whose own internal {@code TransitionRunner} IS its lifecycle. This Brain composition
 * root therefore <strong>wraps</strong> ILM as a single Phase Output, rather than extracting
 * phases <em>from</em> it.
 *
 * <p>This is the "service-wrapping" variant of the substrate's body-shape per tempdoc 541
 * §5.1's two-variant model:
 *
 * <ul>
 *   <li>Head + Worker: phase-decomposition (typed Input → Output → next-phase Input chain).
 *   <li>Brain: wraps a stateful service (ILM) as one Phase Output; subsequent phases consume
 *       the held service. The {@code Phase<I,O>} primitive is the same; the body is "store
 *       the constructed ILM" — ILM's own state machine is opaque to the composition substrate.
 * </ul>
 *
 * <p>Today the Brain process is co-resident with Head — ILM is constructed inside
 * {@code ServicePhase} of {@link HeadAssembly}. {@code BrainAssembly} therefore observes ILM's
 * construction window via Head's bootTrace and projects a Brain-process view of it. When the
 * Brain process is ever split out (separate JVM), this assembly becomes the actual composition
 * root for that JVM; the contract (same {@code BootTrace} shape) is preserved.
 *
 * <p>BootTrace shape: one phase named {@code "ilm-construction"} recording the wall-clock
 * timing of ILM construction within Head's ServicePhase. Outcome is Ready when ILM is
 * non-null, Degraded ({@code "inference.not_configured"}) when ILM was not constructed
 * (lite-mode / AI-disabled paths).
 */
public record BrainAssembly(InferenceLifecycleManager inferenceManager, BootTrace bootTrace) {

  /**
   * Project a Brain {@link BootTrace} from Head's service-phase window. {@code
   * serviceStartedAtMs} / {@code serviceCompletedAtMs} come from {@link HeadAssembly}'s own
   * trace; the projected Brain trace records a single {@code "ilm-construction"} phase whose
   * outcome reflects whether ILM was constructed.
   *
   * @param inferenceManager the constructed manager, or null when AI is disabled / lite-mode.
   * @param serviceStartedAtMs the wall-clock entry of the parent service phase.
   * @param serviceCompletedAtMs the wall-clock exit of the parent service phase.
   */
  public static BrainAssembly project(
      InferenceLifecycleManager inferenceManager,
      long serviceStartedAtMs,
      long serviceCompletedAtMs) {
    // Fix-pass E.5: construct BootTrace directly. Previously this went through
    // BootTrace.Builder.seal() and re-stamped the timestamps — awkward because the Builder
    // captures currentTimeMillis() at seal time, requiring an overwrite-via-new-record dance.
    // Direct construction is simpler and intent-aligned: the Brain trace's lifecycle window
    // IS the parent service-phase window by definition of the co-resident projection.
    PhaseRecord record =
        inferenceManager == null
            ? PhaseRecord.degraded(
                "ilm-construction",
                serviceStartedAtMs,
                serviceCompletedAtMs,
                "inference.not_configured",
                null)
            : PhaseRecord.ready(
                "ilm-construction", serviceStartedAtMs, serviceCompletedAtMs, null);
    BootTrace trace =
        new BootTrace(BootTrace.BRAIN, serviceStartedAtMs, serviceCompletedAtMs, List.of(record));
    return new BrainAssembly(inferenceManager, trace);
  }

  /** Convenience: empty Optional iff ILM is null (lite-mode / AI-disabled). */
  public Optional<InferenceLifecycleManager> inferenceManagerOpt() {
    return Optional.ofNullable(inferenceManager);
  }
}
