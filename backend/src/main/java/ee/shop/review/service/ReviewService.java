package ee.shop.review.service;

import ee.shop.catalog.repository.ProductRepository;
import ee.shop.common.error.AppException;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.review.model.Review;
import ee.shop.review.repository.ReviewRepository;
import ee.shop.review.web.dto.ReviewListResponse;
import ee.shop.review.web.dto.ReviewRequest;
import ee.shop.review.web.dto.ReviewResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Product reviews. Two rules do the real work here:
 *
 * 1. Only a verified purchaser may review — enforced via
 *    ReviewRepository.hasPurchased, which folds the order event stream
 *    because status is derived, not stored (DECISIONS.md §1).
 * 2. One review per person per product — enforced by a UNIQUE index, with
 *    the pre-check here only for a friendlier message. The index is what
 *    actually holds under concurrency; the pre-check alone would be a
 *    check-then-act race, the same shape ruled out for inventory.
 *
 * Ratings are NOT aggregated here. products.avg_rating/review_count are
 * maintained by a database trigger (V15__reviews.sql) so they cannot drift
 * away from the rows they summarise, no matter which code path writes a
 * review — same reasoning as anonymize_user() living in the database.
 */
@Service
public class ReviewService {

    /** Sort options exposed to the API. Anything else is rejected rather than silently ignored. */
    public enum SortBy { NEWEST, HELPFUL, RATING_HIGH, RATING_LOW }

    private final ReviewRepository reviews;
    private final UserRepository users;
    private final ProductRepository products;

    public ReviewService(ReviewRepository reviews, UserRepository users, ProductRepository products) {
        this.reviews = reviews;
        this.users = users;
        this.products = products;
    }

    // ── reads ─────────────────────────────────────────────────────────────

    /** Published reviews for one product. `callerId` may be null — anonymous readers see the same list. */
    @Transactional(readOnly = true)
    public ReviewListResponse listForProduct(UUID productId, UUID callerId, SortBy sort, int page, int size) {
        Page<Review> result = reviews.findByProductIdAndStatus(
                productId, Review.Status.PUBLISHED, PageRequest.of(page, size, sortOf(sort)));
        return toListResponse(result, callerId);
    }

    /** Whether the caller is allowed to write a review — lets the UI show the form or a reason, not a failed POST. */
    @Transactional(readOnly = true)
    public boolean canReview(UUID userId, UUID productId) {
        return reviews.hasPurchased(userId, productId)
                && reviews.findByProductIdAndUserId(productId, userId).isEmpty();
    }

    // ── writes ────────────────────────────────────────────────────────────

    @Transactional
    public ReviewResponse create(UUID userId, UUID productId, ReviewRequest req) {
        products.findById(productId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> AppException.notFound("Product not found"));

        if (!reviews.hasPurchased(userId, productId)) {
            throw AppException.forbidden("Only verified purchasers can review this product");
        }

        Review review = new Review(productId, userId, req.rating(), req.title(), req.body());
        try {
            // saveAndFlush, NOT save: a plain save() defers the INSERT to
            // transaction commit, which happens AFTER this catch block has
            // been passed — the unique-constraint violation would then escape
            // unhandled and surface as a 500 instead of this 409. Verified by
            // the test, which failed exactly that way before the flush.
            reviews.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against the caller's own concurrent submit, or
            // they simply already had one — the UNIQUE index is the guard.
            throw AppException.conflict("You have already reviewed this product");
        }
        return toResponse(review, authorNames(List.of(review)), Set.of(), userId);
    }

    @Transactional
    public ReviewResponse update(UUID userId, UUID reviewId, ReviewRequest req) {
        Review review = ownedByCaller(userId, reviewId);
        review.editContent(req.rating(), req.title(), req.body());
        reviews.save(review);
        return toResponse(review, authorNames(List.of(review)), Set.of(), userId);
    }

    @Transactional
    public void delete(UUID userId, UUID reviewId) {
        reviews.delete(ownedByCaller(userId, reviewId));
    }

    /**
     * Idempotent on purpose: the UI is a toggle, so a double-click or a
     * retried request must land on "voted", not alternate or 409. The
     * repository's ON CONFLICT DO NOTHING is what makes that safe under
     * concurrency rather than a read-then-insert.
     */
    @Transactional
    public void vote(UUID userId, UUID reviewId) {
        Review review = reviews.findById(reviewId)
                .filter(r -> r.getStatus() == Review.Status.PUBLISHED)
                .orElseThrow(() -> AppException.notFound("Review not found"));
        if (review.getUserId().equals(userId)) {
            throw AppException.forbidden("You cannot mark your own review helpful");
        }
        reviews.addVote(reviewId, userId);
    }

    @Transactional
    public void unvote(UUID userId, UUID reviewId) {
        reviews.removeVote(reviewId, userId);
    }

    // ── moderation (authorization is enforced at the controller) ──────────

    @Transactional(readOnly = true)
    public ReviewListResponse listForModeration(Review.Status status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, sortOf(SortBy.NEWEST));
        Page<Review> result = status == null
                ? reviews.findAll(pageable)
                : reviews.findByStatus(status, pageable);
        return toListResponse(result, null);
    }

    @Transactional
    public ReviewResponse moderate(UUID reviewId, Review.Status status) {
        Review review = reviews.findById(reviewId)
                .orElseThrow(() -> AppException.notFound("Review not found"));
        review.moderate(status);
        reviews.save(review);
        return toResponse(review, authorNames(List.of(review)), Set.of(), null);
    }

    // ── assembly ──────────────────────────────────────────────────────────

    private Review ownedByCaller(UUID userId, UUID reviewId) {
        // 404 rather than 403 for someone else's review — same posture as
        // OrderController: don't confirm a resource exists to a non-owner.
        return reviews.findById(reviewId)
                .filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> AppException.notFound("Review not found"));
    }

    private static Sort sortOf(SortBy sort) {
        return switch (sort == null ? SortBy.NEWEST : sort) {
            case NEWEST      -> Sort.by(Sort.Direction.DESC, "createdAt");
            case HELPFUL     -> Sort.by(Sort.Direction.DESC, "helpfulCount").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case RATING_HIGH -> Sort.by(Sort.Direction.DESC, "rating").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case RATING_LOW  -> Sort.by(Sort.Direction.ASC, "rating").and(Sort.by(Sort.Direction.DESC, "createdAt"));
        };
    }

    private ReviewListResponse toListResponse(Page<Review> result, UUID callerId) {
        List<Review> items = result.getContent();
        Map<UUID, String> names = authorNames(items);
        Set<UUID> voted = votedByCaller(callerId, items);

        List<ReviewResponse> views = items.stream()
                .map(r -> toResponse(r, names, voted, callerId))
                .toList();
        return new ReviewListResponse(views, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    /**
     * Bulk-loaded, not per-row: rendering a page of reviews must not fire a
     * user lookup per review. Unlike orders — which must NEVER join to users
     * (DECISIONS.md §2/§5) — a review legitimately belongs to its author and
     * loses its byline when that account is anonymized, which is the correct
     * behaviour rather than a corruption.
     */
    private Map<UUID, String> authorNames(List<Review> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = items.stream().map(Review::getUserId).distinct().toList();
        Map<UUID, String> names = new HashMap<>();
        for (User u : users.findAllById(ids)) {
            names.put(u.getId(), u.getDisplayName());
        }
        return names;
    }

    private Set<UUID> votedByCaller(UUID callerId, List<Review> items) {
        if (callerId == null || items.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = items.stream().map(Review::getId).toList();
        return reviews.findVotedReviewIds(callerId, ids).stream().collect(Collectors.toSet());
    }

    private ReviewResponse toResponse(Review r, Map<UUID, String> names, Set<UUID> voted, UUID callerId) {
        return new ReviewResponse(
                r.getId(), r.getRating(), r.getTitle(), r.getBody(),
                names.get(r.getUserId()),
                r.getHelpfulCount(),
                voted.contains(r.getId()),
                callerId != null && callerId.equals(r.getUserId()),
                r.getStatus().name(),
                r.getCreatedAt());
    }
}
