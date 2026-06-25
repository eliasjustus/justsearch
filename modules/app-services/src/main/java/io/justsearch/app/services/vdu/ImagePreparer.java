/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Prepares images for VLM consumption (resize, format conversion).
 *
 * <p>Stateless utility - safe to reuse across multiple VDU operations.
 */
public class ImagePreparer {
    private static final Logger LOG = LoggerFactory.getLogger(ImagePreparer.class);

    /** Vision-model optimal: max 1280px on longest side (constraint inherited from Qwen3-VL era). */
    private static final int MAX_DIMENSION = 1280;

    /**
     * Loads and resizes image, returns JPEG bytes for base64 encoding.
     *
     * @param imagePath path to image file (PNG, JPEG, etc.)
     * @return JPEG bytes ready for base64 encoding
     * @throws IOException if image cannot be read or processed
     */
    public byte[] prepare(Path imagePath) throws IOException {
        BufferedImage original = ImageIO.read(imagePath.toFile());
        if (original == null) {
            throw new IOException("Failed to read image (unsupported format?): " + imagePath);
        }

        int origWidth = original.getWidth();
        int origHeight = original.getHeight();

        BufferedImage processed = resizeIfNeeded(original);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(processed, "JPEG", baos);

        LOG.trace("Prepared image: {}x{} -> {}x{}, {} bytes",
            origWidth, origHeight,
            processed.getWidth(), processed.getHeight(),
            baos.size());

        return baos.toByteArray();
    }

    private BufferedImage resizeIfNeeded(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();

        // Convert to RGB if needed (handles PNG with alpha) even if no resize
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            if (original.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                // Fill with white background for transparent PNGs
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.drawImage(original, 0, 0, null);
                g.dispose();
                return rgb;
            }
            return original;
        }

        // Calculate scale to fit within MAX_DIMENSION
        double scale = Math.min((double) MAX_DIMENSION / width, (double) MAX_DIMENSION / height);
        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        // Fill with white background for transparent PNGs
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, newWidth, newHeight);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, newWidth, newHeight, null);
        g.dispose();

        return resized;
    }
}
