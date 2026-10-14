package ee.shop.catalog.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** Path under the static image-serving root, e.g. "product-images/foo.svg". */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    /**
     * Smaller renditions of the same image (V21). Null means none exists —
     * true of everything seeded before renditions, and of vector images, which
     * scale without help. Callers fall back to storageKey rather than
     * constructing a URL that would 404.
     */
    @Column(name = "thumbnail_key")
    private String thumbnailKey;

    @Column(name = "medium_key")
    private String mediumKey;

    @Column(name = "alt_text")
    private String altText;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ProductImage() {} // JPA

    public ProductImage(UUID productId, String storageKey, String altText, int displayOrder) {
        this(productId, storageKey, null, null, altText, displayOrder);
    }

    public ProductImage(UUID productId, String storageKey, String thumbnailKey, String mediumKey,
                         String altText, int displayOrder) {
        this.productId = productId;
        this.storageKey = storageKey;
        this.thumbnailKey = thumbnailKey;
        this.mediumKey = mediumKey;
        this.altText = altText;
        this.displayOrder = displayOrder;
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public String getStorageKey() { return storageKey; }

    /** The rendition to use for a grid/cart thumbnail, falling back to the original. */
    public String getThumbnailKeyOrOriginal() {
        return thumbnailKey != null ? thumbnailKey : storageKey;
    }

    /** The rendition to use on a product page, falling back to the original. */
    public String getMediumKeyOrOriginal() {
        return mediumKey != null ? mediumKey : storageKey;
    }

    /** Set by the upload path, or by the backfill for seeded images. */
    public void setRenditions(String thumbnailKey, String mediumKey) {
        this.thumbnailKey = thumbnailKey;
        this.mediumKey = mediumKey;
    }

    public String getThumbnailKey() { return thumbnailKey; }
    public String getMediumKey() { return mediumKey; }
    public String getAltText() { return altText; }
    public int getDisplayOrder() { return displayOrder; }
}
