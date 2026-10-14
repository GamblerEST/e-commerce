package ee.shop.review.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries ONLY the review's own content. The product, the author, and the
 * "did they actually buy it" verdict all come from the server side — a
 * client that could name its own product_id/user_id could review anything
 * as anyone.
 *
 * Bounds mirror V15__reviews.sql's CHECK constraints exactly, so a value the
 * DTO accepts can never be rejected by the database as a 500 instead of a 400.
 */
public record ReviewRequest(
        @Min(value = 1, message = "must be between 1 and 5")
        @Max(value = 5, message = "must be between 1 and 5") short rating,
        @Size(max = 150) String title,
        @NotBlank @Size(max = 4000) String body
) {}
