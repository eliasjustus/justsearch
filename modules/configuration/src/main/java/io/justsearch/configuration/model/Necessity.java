/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * How badly the product needs a model package — the axis a per-component install decision is
 * offered on (tempdoc 840 Phase 2).
 *
 * <p>Orthogonal to both {@link CapabilityTier} (which capability group the package serves, the axis
 * an {@link InstallIntent} selects over) and {@link DownloadProfile} (the hardware axis, which picks
 * the precision variant within a wanted package). Necessity answers the only question a user can
 * actually act on: <em>what do I lose if I say no?</em>
 *
 * <ul>
 *   <li>{@link #REQUIRED} — search does not work without it ({@code embedding}).
 *   <li>{@link #IMPROVES_RESULTS} — search works; results are measurably worse without it ({@code
 *       splade}, {@code reranker}, {@code ner}, {@code citation-scorer}).
 *   <li>{@link #ADDS_FEATURE} — search is unaffected; a distinct capability is lost ({@code chat}).
 *   <li>{@link #INFRASTRUCTURE} — not a user-facing capability at all; plumbing ({@code
 *       cuda-runtime}).
 * </ul>
 *
 * <p><b>Declinability is derived, never stored.</b> A package is user-declinable iff its necessity
 * is {@link #IMPROVES_RESULTS} or {@link #ADDS_FEATURE} — see {@link #userDeclinable()}. There is
 * deliberately no second {@code declinable} field: two fields that must agree will drift, and the
 * one that drifts here turns a mandatory package off.
 *
 * <p><b>Why {@code cuda-runtime} is INFRASTRUCTURE and must not be declinable.</b> This is the
 * non-obvious case. {@link DownloadProfile} gates two axes at once — {@code usesCuda()} AND {@code
 * includesGguf()} — and the {@code cuda-runtime} package does not only deliver the CUDA DLLs the
 * FP16 ONNX variants need: it also delivers the cuda12 {@code llama-server.exe} that {@code
 * AiInstallService.applyCudaServerExe()} points chat at. This build does not support CPU chat at
 * all (see {@code InstallPlanner}'s skip reason: "CPU chat is not supported in this build"). So
 * offering "GPU runtime libraries" as a decline would silently remove chat as a side effect of a
 * choice that reads as being about DLLs. It is already correctly gated on hardware alone, via
 * {@code requiresCuda} × {@code profile.usesCuda()}; that decision needs no user input, and a user
 * cannot give informed consent to a consequence the label does not name.
 */
public enum Necessity {
  /** Search does not work without this package. Never declinable. */
  REQUIRED("required", "Required"),
  /** Search works without it, but results are measurably worse. Declinable. */
  IMPROVES_RESULTS("improves-results", "Improves results"),
  /** Search is unaffected; declining loses a distinct capability. Declinable. */
  ADDS_FEATURE("adds-feature", "Adds a feature"),
  /** Plumbing, not a user-facing capability. Never declinable — see the class javadoc. */
  INFRASTRUCTURE("infrastructure", "Infrastructure");

  private final String id;
  private final String label;

  Necessity(String id, String label) {
    this.id = id;
    this.label = label;
  }

  /** The kebab-case identifier used in the registry JSON ({@code "necessity"} field). */
  public String id() {
    return id;
  }

  /** Human-readable label for UI copy (this becomes the user-facing category name). */
  public String label() {
    return label;
  }

  /**
   * Whether a user may decline a package of this necessity. Derived, so it cannot disagree with the
   * category the package was classified into.
   */
  public boolean userDeclinable() {
    return this == IMPROVES_RESULTS || this == ADDS_FEATURE;
  }

  /**
   * Resolves a necessity from its registry JSON id (kebab-case). Deliberately tolerant — returns
   * {@code null} for a null, blank, OR unrecognized id rather than throwing (unlike {@link
   * CapabilityTier#fromId}, whose unknown-tier throw only risks over-installing). Callers map that
   * {@code null} to the conservative {@link #REQUIRED}; a necessity name a future registry uses and
   * this build does not know must leave the package mandatory, not fail the whole registry load and
   * not become silently switch-off-able.
   */
  public static Necessity fromId(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    for (Necessity n : values()) {
      if (n.id.equals(id)) {
        return n;
      }
    }
    return null;
  }
}
