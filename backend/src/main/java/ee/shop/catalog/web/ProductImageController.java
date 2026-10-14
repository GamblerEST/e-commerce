package ee.shop.catalog.web;

import ee.shop.catalog.model.Product;
import ee.shop.catalog.model.ProductImage;
import ee.shop.catalog.service.ImageRenditionService;
import ee.shop.catalog.repository.ProductImageRepository;
import ee.shop.catalog.repository.ProductRepository;
import ee.shop.common.audit.AuditService;
import ee.shop.common.error.AppException;
import ee.shop.common.security.AdminOnly;
import ee.shop.identity.model.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-only product image upload. Separate from CatalogController on
 * purpose: that one is all public, unauthenticated GET; this is the one
 * authenticated write endpoint in the catalog package.
 *
 * Authorization is @AdminOnly — which also requires 2FA to actually be
 * enabled on the account, not just the ADMIN role. See that annotation for
 * why the second factor is enforced here rather than at login.
 */
@RestController
public class ProductImageController {

    // svg deliberately excluded: it can embed <script>, and unlike the
    // bundled placeholder SVGs (which we author ourselves), an uploaded one
    // is untrusted input served back to every visitor who views the product.
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final ProductRepository products;
    private final ProductImageRepository images;
    private final AuditService audit;
    private final Path storageDir;
    private final ImageRenditionService renditions;

    public ProductImageController(ProductRepository products, ProductImageRepository images,
                                   AuditService audit, ImageRenditionService renditions,
                                   @Value("${app.uploads.product-images-dir}") String storageDir) {
        this.products = products;
        this.images = images;
        this.audit = audit;
        this.renditions = renditions;
        this.storageDir = Path.of(storageDir);
    }

    public record UploadedImage(UUID id, String url) {}

    @PostMapping("/api/admin/products/{productId}/images")
    @AdminOnly
    @ResponseStatus(HttpStatus.CREATED)
    public UploadedImage upload(@PathVariable UUID productId,
                                 @RequestParam MultipartFile file,
                                 @RequestParam(required = false) String altText,
                                 Authentication authentication, HttpServletRequest http) {
        // Authorization is @AdminOnly on this method now; the principal is
        // still needed here for the audit trail below.
        User user = (User) authentication.getPrincipal();

        Product product = products.findById(productId)
                .orElseThrow(() -> AppException.notFound("Product not found"));

        if (file.isEmpty()) {
            throw AppException.badRequest("File is empty");
        }
        if (file.getSize() > MAX_BYTES) {
            throw AppException.badRequest("File exceeds the 5MB limit");
        }
        // Content-Type is whatever the CLIENT claims in the multipart request —
        // trusting it alone lets an attacker upload anything (a script, an
        // HTML file, a polyglot) labeled "image/jpeg" and have it saved with
        // a .jpg extension. This map only decides which extension we'd use IF
        // the bytes below actually decode as that kind of image.
        String extension = ALLOWED_TYPES.get(file.getContentType());
        if (extension == null) {
            throw AppException.badRequest("Unsupported image type — use JPEG, PNG, or WebP");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }

        // The actual verification: ImageIO.read returns null (not an
        // exception) for anything it can't decode as a real image, which is
        // exactly what rejects a mislabeled non-image file — a claimed
        // Content-Type is not evidence, successfully decoded pixels are.
        BufferedImage decoded;
        try {
            decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            decoded = null;
        }
        if (decoded == null) {
            throw AppException.badRequest("File is not a valid image");
        }

        String baseName = UUID.randomUUID().toString();
        String filename = baseName + "." + extension;
        ImageRenditionService.Renditions renditions;
        try {
            Files.createDirectories(storageDir);
            Path target = storageDir.resolve(filename);
            Files.write(target, bytes);
            // Smaller copies for grids and the product page (V21). The
            // original is kept as uploaded — it is what a zoom view needs, and
            // re-encoding it would lose detail for no benefit.
            renditions = this.renditions.generate(decoded, storageDir, baseName, extension);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploaded image", e);
        }

        // max + 1, not the count: once images can be deleted the count can land on
        // an order value that is still in use.
        int nextOrder = images.findByProductIdOrderByDisplayOrder(productId).stream()
                .mapToInt(ProductImage::getDisplayOrder).max().orElse(-1) + 1;
        ProductImage saved = images.save(new ProductImage(
                productId, "product-images/uploads/" + filename,
                renditions.thumbnailKey(), renditions.mediumKey(), altText, nextOrder));

        audit.record("admin.product.image_uploaded", user.getId(), productId.toString(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));

        return new UploadedImage(saved.getId(), "/" + saved.getStorageKey());
    }

    public record AdminImageView(UUID id, String url, String thumbnailUrl, String altText, int displayOrder) {}

    /** Every image of a product, so the admin form can show what is already there. */
    @GetMapping("/api/admin/products/{productId}/images")
    @AdminOnly
    public List<AdminImageView> list(@PathVariable UUID productId) {
        products.findById(productId).orElseThrow(() -> AppException.notFound("Product not found"));
        return images.findByProductIdOrderByDisplayOrder(productId).stream()
                .map(i -> new AdminImageView(i.getId(), "/" + i.getStorageKey(),
                        "/" + i.getThumbnailKeyOrOriginal(), i.getAltText(), i.getDisplayOrder()))
                .toList();
    }

    /**
     * Removes an image from a product. Looked up THROUGH the product, so an image id
     * from another product is a 404 rather than a silent cross-product delete.
     * Only files this controller wrote (under the uploads directory) are removed
     * from disk; the bundled placeholder images live inside the jar and stay.
     */
    @DeleteMapping("/api/admin/products/{productId}/images/{imageId}")
    @AdminOnly
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID productId, @PathVariable UUID imageId,
                       Authentication authentication, HttpServletRequest http) {
        User user = (User) authentication.getPrincipal();
        ProductImage image = images.findById(imageId)
                .filter(i -> i.getProductId().equals(productId))
                .orElseThrow(() -> AppException.notFound("Image not found"));

        images.delete(image);
        for (String key : new String[] {image.getStorageKey(), image.getThumbnailKey(), image.getMediumKey()}) {
            deleteUploadedFile(key);
        }
        audit.record("admin.product.image_deleted", user.getId(), productId.toString(),
                http.getRemoteAddr(), http.getHeader("User-Agent"));
    }

    private static final String UPLOAD_PREFIX = "product-images/uploads/";

    /** Best effort: the row is already gone, so a file that cannot be removed is only clutter. */
    private void deleteUploadedFile(String key) {
        if (key == null || !key.startsWith(UPLOAD_PREFIX)) {
            return;
        }
        Path file = storageDir.resolve(key.substring(UPLOAD_PREFIX.length())).normalize();
        if (!file.startsWith(storageDir.normalize())) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // clutter, not an error
        }
    }
}
