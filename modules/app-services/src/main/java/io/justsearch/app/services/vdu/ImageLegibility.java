/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import java.awt.image.BufferedImage;

/**
 * Stage 0 input-legibility measurement for VDU page images (tempdoc 677 §Proposed design,
 * "Stage 0 — input legibility gate"). Computes two cheap, purely pixel-based signals —
 * Laplacian variance (sharp-edge presence) and RMS contrast (intensity-variation presence) — so
 * a caller can decide, using its own calibrated floors, whether a page carries no textual signal
 * at all and the VLM call should be skipped.
 *
 * <p><b>CAUTION (quoted from the tempdoc, verbatim):</b> "the gate must key on 'no textual
 * signal present for anything' (blur/contrast floor), not 'OCR confidence low', or it would
 * defeat VDU's purpose." VDU exists precisely for documents where OCR already failed, so this
 * class deliberately does not read, call, or reason about OCR confidence, OCR output, or any
 * other OCR-derived signal — it only looks at raw pixels. Any future change that makes this
 * class consult OCR state would silently reintroduce the failure mode the tempdoc warns against.
 *
 * <p>Stateless, pure, no I/O, no new dependencies (java.awt.image only).
 *
 * <p><b>Why noise defeats Laplacian-alone (see {@code ImageLegibilityTest}):</b> pure random
 * noise produces a HIGH Laplacian variance (every neighboring pixel differs, so the discrete
 * Laplacian response is large everywhere) — the opposite of what "sharp text edges" would
 * suggest, and no visual sign of a real document. Laplacian variance alone cannot distinguish
 * "sharp readable text" from "sharp noise." RMS contrast helps but is not sufficient either
 * (noise typically has non-trivial contrast too). Neither signal alone is safe as a gate; this
 * is why {@link LegibilityMeasures#belowFloor} is deliberately conjunctive (both floors must be
 * breached) rather than a single combined score, and why Stage 0 floors must stay conservative
 * (low false-abstain risk) — a stray high-variance/low-signal case should fall through to the
 * later cascade stages (Stage 1/2), not get silently abstained here.
 */
public final class ImageLegibility {

    /**
     * Bound on the long edge of the image analyzed for legibility. Two independent reasons:
     * (1) cost bound — Laplacian variance and RMS contrast are both O(width * height); a VDU
     * page image can be up to {@code ImagePreparer.MAX_DIMENSION} (1280px) on its long edge,
     * and this measurement runs on every page pre-send, so it must stay cheap. (2) scale
     * normalization — the two decision floors are calibrated once (tempdoc 677 §Verification
     * plan, against {@code golden/synth-scan-v1}) and then applied to every image regardless of
     * its original resolution; analyzing a single fixed target size removes original-resolution
     * as a confound on the measured magnitudes (a naive-DPI scan and a high-DPI scan of the same
     * blurry page should score similarly once both are downscaled to the same analysis size).
     * 512px is well above the resolution needed to detect gross blur/blank-page cases this stage
     * targets — it is not attempting fine-grained text legibility, which is out of scope for
     * Stage 0 (that is what the VLM call itself, and later cascade stages, are for).
     */
    static final int MAX_ANALYSIS_DIMENSION = 512;

    private ImageLegibility() {}

    /**
     * Computes {@link LegibilityMeasures} for {@code image}. Downscales internally (never
     * upscales) to at most {@link #MAX_ANALYSIS_DIMENSION} on the long edge before analysis; see
     * that constant's javadoc for why.
     *
     * @param image the page image to analyze; must be non-null with positive dimensions.
     * @return the two independent legibility signals; no threshold/decision is applied here.
     */
    public static LegibilityMeasures measure(BufferedImage image) {
        BufferedImage bounded = boundSize(image, MAX_ANALYSIS_DIMENSION);
        double[][] gray = toGrayscale(bounded);
        double rmsContrast = computeRmsContrast(gray);
        double laplacianVariance = computeLaplacianVariance(gray);
        return new LegibilityMeasures(laplacianVariance, rmsContrast);
    }

    private static BufferedImage boundSize(BufferedImage image, int maxDimension) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (Math.max(width, height) <= maxDimension) {
            return image;
        }
        // Reuses ImagePreparer's scale-to-fit (bilinear, white-composited) rather than
        // duplicating resize logic; ImagePreparer's own resize behavior is unchanged.
        return ImagePreparer.scaleToFit(image, maxDimension);
    }

    /**
     * Converts to grayscale using the ITU-R BT.601 luma formula: {@code 0.299*R + 0.587*G +
     * 0.114*B}. Alpha is ignored (this is a measurement, not a rendering step); callers with
     * transparent sources larger than {@link #MAX_ANALYSIS_DIMENSION} get alpha composited onto
     * white for free via {@link ImagePreparer#scaleToFit}, but sub-threshold transparent images
     * are read with alpha ignored, which is acceptable for a coarse blur/contrast signal.
     */
    private static double[][] toGrayscale(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double[][] gray = new double[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                gray[y][x] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }
        return gray;
    }

    /**
     * RMS contrast: the standard deviation of grayscale intensities, normalized to {@code [0,
     * 1]} by dividing by 255 (the maximum possible stddev spread on an 8-bit channel is bounded
     * well below 255, but 255 is the standard normalization divisor for this metric — it gives a
     * stable, image-size-independent unit, not a claim that stddev can reach 255).
     */
    private static double computeRmsContrast(double[][] gray) {
        int height = gray.length;
        int width = gray[0].length;
        long n = (long) width * height;

        double sum = 0.0;
        for (double[] row : gray) {
            for (double v : row) {
                sum += v;
            }
        }
        double mean = sum / n;

        double sqSum = 0.0;
        for (double[] row : gray) {
            for (double v : row) {
                double d = v - mean;
                sqSum += d * d;
            }
        }
        double stddev = Math.sqrt(sqSum / n);
        return stddev / 255.0;
    }

    /**
     * Variance of the discrete 4-neighbor Laplacian response over the grayscale image — the
     * standard "variance of Laplacian" blur metric. For each interior pixel {@code (x, y)}, the
     * kernel
     *
     * <pre>
     *  0  1  0
     *  1 -4  1
     *  0  1  0
     * </pre>
     *
     * gives response {@code L(x,y) = gray[y-1][x] + gray[y+1][x] + gray[y][x-1] + gray[y][x+1] -
     * 4*gray[y][x]}. A sharp edge produces a large-magnitude response; a smooth/blurred region
     * produces a response near zero. The variance of {@code L} over all interior pixels is high
     * when the image has strong, well-distributed edges (in-focus text) and low when the image
     * is uniformly smooth or blurred. Border pixels (no full 4-neighborhood) are excluded rather
     * than special-cased — a 1px border loss is immaterial at this analysis size.
     */
    private static double computeLaplacianVariance(double[][] gray) {
        int height = gray.length;
        int width = gray[0].length;
        if (width < 3 || height < 3) {
            return 0.0;
        }

        int interiorCount = (width - 2) * (height - 2);
        double[] responses = new double[interiorCount];
        int idx = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                double laplacian = gray[y - 1][x] + gray[y + 1][x] + gray[y][x - 1] + gray[y][x + 1] - 4 * gray[y][x];
                responses[idx++] = laplacian;
            }
        }

        double mean = 0.0;
        for (double r : responses) {
            mean += r;
        }
        mean /= responses.length;

        double variance = 0.0;
        for (double r : responses) {
            double d = r - mean;
            variance += d * d;
        }
        return variance / responses.length;
    }
}
