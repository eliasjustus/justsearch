/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ImageLegibility}'s two signals against synthetic fixtures. Assertions are relative
 * orderings between fixtures, not absolute magic numbers — the actual decision floors are
 * calibrated later (tempdoc 677 §Verification plan) against real fixture sets, not here.
 */
final class ImageLegibilityTest {

  private static final int WIDTH = 240;
  private static final int HEIGHT = 120;

  @Test
  @DisplayName("sharp black text on white: high Laplacian variance and non-trivial contrast")
  void sharpTextHasHighVarianceAndContrast() {
    LegibilityMeasures sharpText = ImageLegibility.measure(sharpTextImage());
    LegibilityMeasures uniform = ImageLegibility.measure(uniformGrayImage(200));

    assertTrue(
        sharpText.laplacianVariance() > uniform.laplacianVariance(),
        "sharp text edges must produce far more Laplacian response than a flat page");
    assertTrue(sharpText.rmsContrast() > 0.05, "black-on-white text must show real contrast");
  }

  @Test
  @DisplayName("uniform gray page: both signals near zero")
  void uniformImageHasNearZeroSignals() {
    LegibilityMeasures uniform = ImageLegibility.measure(uniformGrayImage(180));

    // A perfectly flat image has zero variation by construction: no neighbor differs, so
    // every Laplacian response is exactly 0, and every pixel equals the mean so stddev is 0.
    assertEquals(0.0, uniform.laplacianVariance(), 1e-9, "flat image has zero Laplacian variance");
    assertEquals(0.0, uniform.rmsContrast(), 1e-9, "flat image has zero contrast");
  }

  @Test
  @DisplayName("blurred text: Laplacian variance well below the same text sharp")
  void blurredTextHasMuchLowerVarianceThanSharp() {
    BufferedImage sharp = sharpTextImage();
    BufferedImage blurred = boxBlur(sharp, 5, 4);

    LegibilityMeasures sharpMeasures = ImageLegibility.measure(sharp);
    LegibilityMeasures blurredMeasures = ImageLegibility.measure(blurred);

    assertTrue(
        blurredMeasures.laplacianVariance() < sharpMeasures.laplacianVariance() * 0.25,
        "repeated box-blur must knock down edge sharpness to well below a quarter of the "
            + "original: sharp="
            + sharpMeasures.laplacianVariance()
            + " blurred="
            + blurredMeasures.laplacianVariance());
  }

  @Test
  @DisplayName("pure noise: HIGH Laplacian variance despite carrying no real textual signal "
      + "(documents why variance alone cannot gate — see ImageLegibility javadoc)")
  void pureNoiseHasHighVarianceDespiteNoSignal() {
    BufferedImage sharp = sharpTextImage();
    BufferedImage noise = noiseImage(42);

    LegibilityMeasures sharpMeasures = ImageLegibility.measure(sharp);
    LegibilityMeasures noiseMeasures = ImageLegibility.measure(noise);

    // Every neighboring pixel pair in uniform random noise differs by a large, unstructured
    // amount, so the discrete Laplacian response is large EVERYWHERE (not just at a few real
    // edges like in text). That makes noise's Laplacian variance rival or exceed sharp text's,
    // even though noise carries zero textual signal. This is exactly why Stage 0
    // (tempdoc 677 §Proposed design) must not gate on Laplacian variance alone, and why
    // LegibilityMeasures#belowFloor only fires when contrast is ALSO below its floor — noise
    // would otherwise be indistinguishable from "sharp legible text" by this signal alone.
    assertTrue(
        noiseMeasures.laplacianVariance() > sharpMeasures.laplacianVariance() * 0.5,
        "pure noise must produce Laplacian variance comparable to or exceeding sharp text: "
            + "sharp="
            + sharpMeasures.laplacianVariance()
            + " noise="
            + noiseMeasures.laplacianVariance());
  }

  @Test
  @DisplayName("downscale bound is applied without upscaling small images")
  void largeImageIsBoundedSmallImageIsNotUpscaled() {
    BufferedImage large = uniformGrayImage(1600, 1600, 128);
    BufferedImage small = uniformGrayImage(50, 50, 128);

    // Both are flat, so regardless of internal downscaling both must read as zero-signal;
    // this pins that boundSize() doesn't throw or corrupt data at either extreme.
    LegibilityMeasures largeMeasures = ImageLegibility.measure(large);
    LegibilityMeasures smallMeasures = ImageLegibility.measure(small);

    assertEquals(0.0, largeMeasures.laplacianVariance(), 1e-9);
    assertEquals(0.0, smallMeasures.laplacianVariance(), 1e-9);
  }

  private static BufferedImage sharpTextImage() {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, WIDTH, HEIGHT);
    g.setColor(Color.BLACK);
    g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
    g.drawString("Hello World", 10, 40);
    g.drawString("Sample Text", 10, 70);
    g.drawString("12345 ABCDE", 10, 100);
    g.dispose();
    return image;
  }

  private static BufferedImage uniformGrayImage(int gray) {
    return uniformGrayImage(WIDTH, HEIGHT, gray);
  }

  private static BufferedImage uniformGrayImage(int width, int height, int gray) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(new Color(gray, gray, gray));
    g.fillRect(0, 0, width, height);
    g.dispose();
    return image;
  }

  private static BufferedImage noiseImage(long seed) {
    Random random = new Random(seed);
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < HEIGHT; y++) {
      for (int x = 0; x < WIDTH; x++) {
        int value = random.nextInt(256);
        int rgb = (value << 16) | (value << 8) | value;
        image.setRGB(x, y, rgb);
      }
    }
    return image;
  }

  /** Simple separable-in-spirit box blur, applied {@code passes} times with a {@code size}x{@code size} kernel. */
  private static BufferedImage boxBlur(BufferedImage source, int size, int passes) {
    int width = source.getWidth();
    int height = source.getHeight();
    int[][] current = new int[height][width];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        current[y][x] = source.getRGB(x, y) & 0xFF; // channels are equal (grayscale source)
      }
    }

    int radius = size / 2;
    for (int pass = 0; pass < passes; pass++) {
      int[][] next = new int[height][width];
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          long sum = 0;
          int count = 0;
          for (int dy = -radius; dy <= radius; dy++) {
            int ny = y + dy;
            if (ny < 0 || ny >= height) {
              continue;
            }
            for (int dx = -radius; dx <= radius; dx++) {
              int nx = x + dx;
              if (nx < 0 || nx >= width) {
                continue;
              }
              sum += current[ny][nx];
              count++;
            }
          }
          next[y][x] = (int) (sum / count);
        }
      }
      current = next;
    }

    BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int v = current[y][x];
        int rgb = (v << 16) | (v << 8) | v;
        result.setRGB(x, y, rgb);
      }
    }
    return result;
  }
}
