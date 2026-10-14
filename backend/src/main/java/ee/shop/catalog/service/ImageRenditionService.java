package ee.shop.catalog.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates smaller copies of an uploaded product image.
 *
 * The shop serves the same photo in three places with wildly different needs:
 * a 200px grid card, a ~800px product page, and the original for zoom. Sending
 * a 5 MB original to a phone rendering it at 160px wastes the customer's data
 * and is the single biggest thing slowing a catalogue page down.
 *
 * Done at UPLOAD time rather than on request. Resizing per request means
 * either paying the CPU cost repeatedly or building a cache with its own
 * invalidation problem; an admin uploads a product image once and it is read
 * thousands of times, so the work belongs on the write.
 *
 * Deliberately no external dependency. ImageIO is already used here to verify
 * that an upload decodes as a real image, so scaling costs no new library.
 */
@Service
public class ImageRenditionService {

    private static final Logger log = LoggerFactory.getLogger(ImageRenditionService.class);

    /** Long edge, in pixels. Grid cards render at ~200 CSS px; 400 covers 2x displays. */
    public static final int THUMBNAIL_MAX_EDGE = 400;
    /** Product detail renders at ~600 CSS px; 1000 covers 2x without shipping the original. */
    public static final int MEDIUM_MAX_EDGE = 1000;

    public record Renditions(String thumbnailKey, String mediumKey) {}

    /**
     * Writes the two smaller renditions next to the original and returns their
     * storage keys. A null key means that rendition was not produced.
     *
     * Never throws. A product whose image failed to scale must still be
     * saleable — the callers fall back to the original, which is correct if
     * wasteful. Failing the whole upload because a thumbnail could not be
     * written would be a worse trade.
     */
    public Renditions generate(BufferedImage source, Path storageDir, String baseName,
                                String extension) {
        // A rendition larger than the source is pointless upscaling: it costs
        // bytes and adds no detail.
        String thumb = writeScaled(source, storageDir, baseName + "-thumb", extension,
                THUMBNAIL_MAX_EDGE);
        String medium = writeScaled(source, storageDir, baseName + "-medium", extension,
                MEDIUM_MAX_EDGE);
        return new Renditions(thumb, medium);
    }

    private String writeScaled(BufferedImage source, Path storageDir, String name,
                                String extension, int maxEdge) {
        int w = source.getWidth();
        int h = source.getHeight();
        if (Math.max(w, h) <= maxEdge) {
            return null; // already small enough; the original IS this size
        }

        double scale = (double) maxEdge / Math.max(w, h);
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));

        try {
            // TYPE_INT_RGB, not ARGB: JPEG cannot encode an alpha channel, and
            // ImageIO writes a corrupt file rather than complaining if handed
            // one. PNG uploads lose transparency in the rendition as a result,
            // which is an acceptable trade for renditions that always render.
            BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // White ground, so a transparent PNG does not scale to black.
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, targetW, targetH);
            g.drawImage(source, 0, 0, targetW, targetH, null);
            g.dispose();

            String filename = name + "." + extension;
            Path target = storageDir.resolve(filename);
            // ImageIO writer names differ from file extensions ("jpg" works,
            // but normalising keeps jpeg/jpg consistent).
            String format = extension.equalsIgnoreCase("jpg") ? "jpeg" : extension;
            if (!ImageIO.write(scaled, format, target.toFile())) {
                log.warn("No image writer for format '{}' — skipping the {}px rendition",
                        format, maxEdge);
                Files.deleteIfExists(target);
                return null;
            }
            return "product-images/uploads/" + filename;
        } catch (IOException | RuntimeException e) {
            // Logged, not thrown: see the class javadoc.
            log.warn("Could not write a {}px rendition, falling back to the original", maxEdge, e);
            return null;
        }
    }
}
