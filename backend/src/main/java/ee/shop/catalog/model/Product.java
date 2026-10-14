package ee.shop.catalog.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Weight/dimensions are stored ONLY in metric (kg, cm) — imperial values are
 * computed at the API layer (see ProductResponse), never stored, so the two
 * unit systems can never drift out of sync with each other.
 *
 * stock_quantity is a DISPLAY counter only ("12 in stock"). It has no
 * reservation semantics and is NOT what checkout will use to prevent
 * overselling — the inventory tables introduce the real on_hand/reserved
 * model (DECISIONS.md §3). Never decrement this at checkout.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "category_id")
    private UUID categoryId;

    private String name;
    private String slug;
    private String description;
    private String brand;

    private BigDecimal price;
    private String currency;

    @Column(name = "vat_category")
    private String vatCategory;

    @Column(name = "stock_quantity")
    private int stockQuantity;

    @Column(name = "weight_kg")
    private BigDecimal weightKg;
    @Column(name = "length_cm")
    private BigDecimal lengthCm;
    @Column(name = "width_cm")
    private BigDecimal widthCm;
    @Column(name = "height_cm")
    private BigDecimal heightCm;

    // Trigger-owned (V15__reviews.sql recomputes both from the review rows).
    // insertable/updatable=false so JPA never writes them: without this, a
    // plain INSERT sends an explicit NULL for avg_rating, which OVERRIDES
    // the column's `NOT NULL DEFAULT 0` and fails — a default only applies
    // when the column is omitted from the statement, not when NULL is sent.
    @Column(name = "avg_rating", insertable = false, updatable = false)
    private BigDecimal avgRating;
    @Column(name = "review_count", insertable = false, updatable = false)
    private int reviewCount;

    @Column(nullable = false)
    private boolean featured;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Product() {} // JPA

    public Product(ProductSpec spec) {
        apply(spec);
    }

    /**
     * Deliberately does NOT touch avgRating/reviewCount — those are
     * maintained by a database trigger from the review rows themselves
     * (V15__reviews.sql). An admin edit must never be able to overwrite a
     * product's earned rating, and ProductSpec has no field for it either.
     */
    public void update(ProductSpec spec) {
        apply(spec);
        this.updatedAt = Instant.now();
    }

    private void apply(ProductSpec spec) {
        this.categoryId = spec.categoryId();
        this.name = spec.name();
        this.slug = spec.slug();
        this.description = spec.description();
        this.brand = spec.brand();
        this.price = spec.price();
        this.currency = spec.currency();
        this.vatCategory = spec.vatCategory();
        this.stockQuantity = spec.stockQuantity();
        this.weightKg = spec.weightKg();
        this.lengthCm = spec.lengthCm();
        this.widthCm = spec.widthCm();
        this.heightCm = spec.heightCm();
        this.featured = spec.featured();
    }

    /**
     * Soft delete, never a row removal: order_lines reference product_id for
     * "show me this again", and a hard delete would either cascade into
     * financial records or fail. Search and the public API already filter on
     * deleted_at (see ProductSearchService), so this is enough to take a
     * product off the shop floor without rewriting history.
     */
    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void restore() {
        this.deletedAt = null;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCategoryId() { return categoryId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public String getBrand() { return brand; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public String getVatCategory() { return vatCategory; }
    public int getStockQuantity() { return stockQuantity; }
    public BigDecimal getWeightKg() { return weightKg; }
    public BigDecimal getLengthCm() { return lengthCm; }
    public BigDecimal getWidthCm() { return widthCm; }
    public BigDecimal getHeightCm() { return heightCm; }
    public BigDecimal getAvgRating() { return avgRating; }
    public int getReviewCount() { return reviewCount; }
    public boolean isFeatured() { return featured; }
    /** Feeds <lastmod> in the sitemap, so crawlers re-fetch only what changed. */
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isDeleted() { return deletedAt != null; }
}
