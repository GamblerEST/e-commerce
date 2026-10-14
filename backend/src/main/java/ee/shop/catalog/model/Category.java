package ee.shop.catalog.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    /**
     * Promoted to the home page as a "collection". A collection is not a
     * second taxonomy alongside categories — it is a category an admin has
     * chosen to show. See V18__home_featured.sql.
     */
    @Column(nullable = false)
    private boolean featured;

    /** Optional marketing line beneath the collection title; null renders nothing. */
    @Column
    private String tagline;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Category() {} // JPA

    public Category(UUID parentId, String name, String slug) {
        this.parentId = parentId;
        this.name = name;
        this.slug = slug;
    }

    public void update(UUID parentId, String name, String slug) {
        this.parentId = parentId;
        this.name = name;
        this.slug = slug;
        this.updatedAt = Instant.now();
    }

    /** Home-page curation, kept separate from update() so editing a category's
     *  name in the catalogue screen can never silently un-promote it. */
    public void promote(boolean featured, String tagline) {
        this.featured = featured;
        this.tagline = tagline;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getParentId() { return parentId; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public boolean isFeatured() { return featured; }
    public String getTagline() { return tagline; }
}
