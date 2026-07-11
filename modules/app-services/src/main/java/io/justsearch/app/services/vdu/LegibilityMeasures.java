/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

/**
 * Result of {@link ImageLegibility#measure(java.awt.image.BufferedImage)} — two independent
 * blur/contrast signals, no baked-in decision.
 *
 * @param laplacianVariance variance of the 4-neighbor discrete Laplacian response over the
 *     grayscale image; low values indicate a lack of sharp edges (blur or blank content). Pure
 *     noise also produces a HIGH value here — see {@link ImageLegibility} javadoc — so this
 *     signal must not be consulted alone.
 * @param rmsContrast root-mean-square contrast: the standard deviation of grayscale pixel
 *     intensities, normalized to {@code [0, 1]} by dividing by 255. Near zero for a flat/blank
 *     page regardless of its Laplacian response.
 */
public record LegibilityMeasures(double laplacianVariance, double rmsContrast) {

    /**
     * True only when BOTH signals are below their floors — i.e. there is no sharp-edge signal
     * <em>and</em> no intensity-variation signal anywhere in the image. Deliberately conjunctive
     * (AND, not OR): Stage 0 (tempdoc 677 §Proposed design) exists to catch pages with no
     * textual signal present for anything (a blank/blown-out/uniformly-flat scan), not to make a
     * judgment call from a single ambiguous signal. Requiring both floors to be breached before
     * abstaining keeps false-abstain risk low — see the "pure noise" case in {@link
     * ImageLegibility}'s javadoc, which breaches neither floor and is correctly NOT flagged as
     * illegible by this method alone (noise is a distinct failure mode, handled by later
     * cascade stages, not Stage 0).
     *
     * @param lapFloor Laplacian-variance floor below which no sharp-edge signal is present.
     *     Calibrated later against {@code golden/synth-scan-v1} — not hardcoded here.
     * @param contrastFloor RMS-contrast floor below which no intensity-variation signal is
     *     present. Calibrated later, same as {@code lapFloor}.
     */
    public boolean belowFloor(double lapFloor, double contrastFloor) {
        return laplacianVariance < lapFloor && rmsContrast < contrastFloor;
    }
}
