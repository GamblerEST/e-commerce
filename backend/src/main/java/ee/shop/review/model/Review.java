package ee.shop.review.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A product review by a VERIFIED PURCHASER — the "did this person actually
 * buy it" rule is enforced in ReviewService (it has to fold order_events,
 * since order status is derived rather than stored — DECISIONS.md §1), not
 * here and not in the schema.
 *
 * helpful_count and the product's avg_rating/review_count are both
 * maintained by database triggers (V15__reviews.sql), NOT by this class or
 * its service. Nothing in Java should ever write those numbers — they are
 * recomputed from the source rows so they cannot drift, which is the same
 * reasoning that puts anonymize_user() in the database (DECISIONS.md §5).
 */
@Entity
@Table(name = "reviews")
public class Review {

    /** Moderation is after-the-fact takedown, not approve-before-visible — see V15__reviews.sql. */
    public enum Status { PUBLISHED, HIDDEN }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    private short rating;
    private String title;
    private String body;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PUBLISHED;

    /** Trigger-maintained (review_votes_maintain_helpful_count). Never written from Java. */
    @Column(name = "helpful_count", insertable = false, updatable = false)
    private int helpfulCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Review() {} // JPA

    public Review(UUID productId, UUID userId, short rating, String title, String body) {
        this.productId = productId;
        this.userId = userId;
        this.rating = rating;
        this.title = title;
        this.body = body;
    }

    /**
     * The author editing their own review. Deliberately narrow: product and
     * author can never move, so an edit can't reassign a review to someone
     * else's purchase or another product's rating.
     */
    public void editContent(short rating, String title, String body) {
        this.rating = rating;
        this.title = title;
        this.body = body;
        this.updatedAt = Instant.now();
    }

    /** Moderator action. Separate from editContent so the two can be authorized differently. */
    public void moderate(Status status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public UUID getUserId() { return userId; }
    public short getRating() { return rating; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public Status getStatus() { return status; }
    public int getHelpfulCount() { return helpfulCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
