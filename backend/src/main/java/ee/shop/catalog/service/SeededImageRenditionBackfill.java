package ee.shop.catalog.service;

import ee.shop.catalog.model.ProductImage;
import ee.shop.catalog.repository.ProductImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates renditions for images that arrived with the CATALOGUE SEED rather
 * than through an upload.
 *
 * ImageRenditionService runs inside the upload endpoint, so it only ever saw
 * images an admin posted. Every seeded image therefore had NULL
 * thumbnail_key/medium_key: the multi-size capability was real, tested and
 * completely invisible on a fresh clone, where the only images that exist are
 * seeded ones. Callers fall back to the original, so nothing looked broken —
 * which is exactly why it went unnoticed.
 *
 * Safe to run on every boot: it only selects rows with no rendition and skips
 * anything it cannot improve, so a second run finds nothing to do.
 */
@Component
public class SeededImageRenditionBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeededImageRenditionBackfill.class);

    private final ProductImageRepository images;
    private final ImageRenditionService renditions;
    private final Path storageDir;

    public SeededImageRenditionBackfill(ProductImageRepository images,
                                         ImageRenditionService renditions,
                                         @Value("${app.uploads.product-images-dir}") String storageDir) {
        this.images = images;
        this.renditions = renditions;
        this.storageDir = Path.of(storageDir);
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ProductImage> pending = images.findByThumbnailKeyIsNullAndMediumKeyIsNull();
        if (pending.isEmpty()) {
            return;
        }

        int generated = 0;
        for (ProductImage image : pending) {
            if (generateFor(image)) {
                generated++;
            }
        }

        if (generated > 0) {
            images.flush();
            log.info("Generated smaller renditions for {} seeded product image(s)", generated);
        }
    }

    private boolean generateFor(ProductImage image) {
        String key = image.getStorageKey();

        // SVG is a vector: it scales to any size without help, and ImageIO
        // cannot decode it anyway. Skipping is correct, not a limitation.
        if (key.toLowerCase().endsWith(".svg")) {
            return false;
        }

        int dot = key.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String extension = key.substring(dot + 1);
        String baseName = Path.of(key).getFileName().toString().substring(
                0, Path.of(key).getFileName().toString().lastIndexOf('.'));

        try {
            BufferedImage source = read(key);
            if (source == null) {
                return false;
            }

            Files.createDirectories(storageDir);
            var result = renditions.generate(source, storageDir, baseName, extension);

            // Both null means the source was already smaller than every target.
            // Nothing to record; the original IS the right size.
            if (result.thumbnailKey() == null && result.mediumKey() == null) {
                return false;
            }

            image.setRenditions(result.thumbnailKey(), result.mediumKey());
            images.save(image);
            return true;
        } catch (Exception e) {
            // One unreadable seed image must not stop the application booting.
            log.warn("Could not generate renditions for {}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Seeded images live on the CLASSPATH (src/main/resources/static), while
     * uploaded ones live on disk under the uploads directory. Renditions for
     * both are written to the uploads directory, which is the only one served
     * from a writable location.
     */
    private BufferedImage read(String storageKey) throws Exception {
        if (storageKey.startsWith("product-images/uploads/")) {
            Path onDisk = storageDir.resolve(Path.of(storageKey).getFileName().toString());
            return Files.exists(onDisk) ? ImageIO.read(onDisk.toFile()) : null;
        }
        ClassPathResource resource = new ClassPathResource("static/" + storageKey);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return ImageIO.read(in);
        }
    }
}
