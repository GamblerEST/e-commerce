package ee.shop.review.web;

import ee.shop.common.security.StaffOnly;
import ee.shop.identity.model.User;
import ee.shop.review.model.Review;
import ee.shop.review.service.ReviewService;
import ee.shop.review.web.dto.ReviewListResponse;
import ee.shop.review.web.dto.ReviewRequest;
import ee.shop.review.web.dto.ReviewResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Reading reviews is public (GET /api/products/** is already permitAll in
 * SecurityConfig); writing one requires an account AND a verified purchase,
 * which ReviewService enforces.
 *
 * No CSRF exception is needed for the write endpoints: SecurityConfig's
 * csrfRequiredMatcher covers only the endpoints whose identity rides on an
 * ambient cookie (/auth/refresh, cart). These authenticate with a Bearer
 * token, which a browser never attaches on its own — no CSRF precondition.
 */
@RestController
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/api/products/{productId}/reviews")
    public ReviewListResponse list(@PathVariable UUID productId,
                                    @RequestParam(required = false) ReviewService.SortBy sort,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    Authentication authentication) {
        // Anonymous is normal here, not an error — the caller id only decides
        // whether each row is flagged as "yours" / "you found this helpful".
        return reviews.listForProduct(productId, callerIdOrNull(authentication), sort,
                Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }

    /**
     * Lets the product page show the review form, or a reason it can't, without
     * making the shopper submit a doomed POST to find out. Anonymous callers
     * get a plain false rather than a 401 — this endpoint is a UI hint, and
     * the real enforcement is on the POST regardless of what this returned.
     */
    @GetMapping("/api/products/{productId}/reviews/eligibility")
    public Map<String, Boolean> eligibility(@PathVariable UUID productId, Authentication authentication) {
        UUID callerId = callerIdOrNull(authentication);
        boolean canReview = callerId != null && reviews.canReview(callerId, productId);
        return Map.of("canReview", canReview);
    }

    @PostMapping("/api/products/{productId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewResponse create(@PathVariable UUID productId,
                                  @Valid @RequestBody ReviewRequest req,
                                  Authentication authentication) {
        return reviews.create(currentUserId(authentication), productId, req);
    }

    @PatchMapping("/api/reviews/{id}")
    public ReviewResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody ReviewRequest req,
                                  Authentication authentication) {
        return reviews.update(currentUserId(authentication), id, req);
    }

    @DeleteMapping("/api/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        reviews.delete(currentUserId(authentication), id);
    }

    /**
     * 204 rather than the updated review: helpful_count is maintained by a
     * database trigger, so the entity in this transaction's persistence
     * context is already stale by the time the vote lands. Returning it would
     * mean reporting a number we know is wrong; the client refetches or
     * updates optimistically instead.
     */
    @PostMapping("/api/reviews/{id}/helpful")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markHelpful(@PathVariable UUID id, Authentication authentication) {
        reviews.vote(currentUserId(authentication), id);
    }

    @DeleteMapping("/api/reviews/{id}/helpful")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmarkHelpful(@PathVariable UUID id, Authentication authentication) {
        reviews.unvote(currentUserId(authentication), id);
    }

    // ── moderation ────────────────────────────────────────────────────────
    // @StaffOnly rather than @AdminOnly: answering "this review is abusive"
    // is exactly the everyday work SUPPORT exists for, and needing a full
    // administrator for it is how shops end up sharing an admin login.

    @GetMapping("/api/admin/reviews")
    @StaffOnly
    public ReviewListResponse moderationQueue(@RequestParam(required = false) Review.Status status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return reviews.listForModeration(status, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @PatchMapping("/api/admin/reviews/{id}")
    @StaffOnly
    public ReviewResponse moderate(@PathVariable UUID id, @RequestParam Review.Status status) {
        return reviews.moderate(id, status);
    }

    private static UUID currentUserId(Authentication authentication) {
        return ((User) authentication.getPrincipal()).getId();
    }

    private static UUID callerIdOrNull(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            return null;
        }
        return user.getId();
    }
}
