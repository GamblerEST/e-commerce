package ee.shop.review.repository;

import ee.shop.review.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByProductIdAndStatus(UUID productId, Review.Status status, Pageable pageable);

    Optional<Review> findByProductIdAndUserId(UUID productId, UUID userId);

    /** Everything one person wrote — for their GDPR data export. */
    List<Review> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Moderation queue — every status, or one specific status when filtered. */
    Page<Review> findByStatus(Review.Status status, Pageable pageable);

    /**
     * The verified-purchaser rule: the caller must own an
     * order containing this product that actually got paid for.
     *
     * Order status is DERIVED, never stored (DECISIONS.md §1), so this folds
     * the event stream inline — the correlated subquery takes the order's
     * most recent STATUS event, exactly as OrderEvent.deriveStatus does in Java.
     * INVOICE_ISSUED is skipped: it is a fact about the order, not its status, and
     * one is appended right after every payment — counting it as "the most recent
     * event" would mean nobody who has paid could ever review.
     *
     * PAID, SHIPPED and DELIVERED count. CREATED (never paid), PAYMENT_FAILED and
     * CANCELLED obviously don't, and REFUNDED deliberately doesn't either:
     * buy → review → refund would otherwise be a free review for anyone.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                  FROM order_lines ol
                  JOIN orders o ON o.id = ol.order_id
                 WHERE ol.product_id = :productId
                   AND o.user_id = :userId
                   AND (
                       SELECT e.event_type
                         FROM order_events e
                        WHERE e.order_id = o.id
                          AND e.event_type <> 'INVOICE_ISSUED'
                        ORDER BY e.occurred_at DESC
                        LIMIT 1
                   ) IN ('PAID', 'SHIPPED', 'DELIVERED')
            )
            """, nativeQuery = true)
    boolean hasPurchased(@Param("userId") UUID userId, @Param("productId") UUID productId);

    // ── helpful votes ────────────────────────────────────────────────────
    // Native, and deliberately NOT a JPA entity: review_votes is a pure
    // join table whose only operations are "claim a vote" and "take it
    // back". Modelling it as an entity would buy nothing and cost a
    // composite-key mapping.

    /**
     * Single atomic claim rather than SELECT-then-INSERT — two rapid clicks
     * (or a double-submit) must not both land. Returns 1 if this was a new
     * vote, 0 if the caller had already voted (the PK conflict), which is
     * exactly what the caller needs to know without a second query.
     */
    @Modifying
    @Query(value = """
            INSERT INTO review_votes (review_id, user_id)
            VALUES (:reviewId, :userId)
            ON CONFLICT (review_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int addVote(@Param("reviewId") UUID reviewId, @Param("userId") UUID userId);

    @Modifying
    @Query(value = "DELETE FROM review_votes WHERE review_id = :reviewId AND user_id = :userId",
            nativeQuery = true)
    int removeVote(@Param("reviewId") UUID reviewId, @Param("userId") UUID userId);

    /**
     * Which of these reviews has the caller already marked helpful — one
     * query for a whole page, so rendering N reviews doesn't fire N+1.
     */
    @Query(value = """
            SELECT review_id FROM review_votes
             WHERE user_id = :userId AND review_id IN (:reviewIds)
            """, nativeQuery = true)
    List<UUID> findVotedReviewIds(@Param("userId") UUID userId, @Param("reviewIds") List<UUID> reviewIds);
}
