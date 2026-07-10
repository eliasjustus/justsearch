package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * De-OCR completeness (tempdoc 706, D2/D3): the structured (non-OCR) Tika pass must never spawn
 * tesseract for any file type. Tika owns no injectable process seam, so the strongest available
 * assertion is behavioural — a text-bearing raster image must come back with NO OCR'd text. With a
 * real tesseract on the machine this only stays green because {@code setSkipOcr(true)} is live in
 * the default parse context; if that regressed, tesseract would read the token and this fails.
 */
final class StructuredContentDeOcrTest {
  @TempDir Path tempDir;

  private static final String TOKEN = "DEOCRZEBRA";

  @Test
  @Timeout(60)
  void structuredPassDoesNotOcrRasterImageText() throws Exception {
    Path image = tempDir.resolve("scan.png");
    ImageIO.write(textImage(TOKEN), "png", image.toFile());

    ContentExtractor.ExtractionResult result = new StructuredContentExtractor().extract(image);

    String content = result.content() == null ? "" : result.content().toLowerCase(Locale.ROOT);
    assertFalse(
        content.contains(TOKEN.toLowerCase(Locale.ROOT)),
        "structured (non-OCR) extraction must not invoke tesseract; got: " + result.content());
  }

  private static BufferedImage textImage(String text) {
    BufferedImage buffered = new BufferedImage(1400, 400, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = buffered.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
      graphics.setColor(Color.BLACK);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 72));
      graphics.drawString(text, 80, 220);
    } finally {
      graphics.dispose();
    }
    return buffered;
  }
}
