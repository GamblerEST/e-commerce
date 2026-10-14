package ee.shop.review.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately carries the author's DISPLAY NAME only — never their email or
 * user id. A review is public content; the reviewer's identity beyond a
 * chosen name is not part of it.
 *
 * `authorName` is null for an anonymized account (anonymize_user() nulls
 * users.display_name — DECISIONS.md §5); the frontend renders that as
 * "Anonymous" rather than the review disappearing, since the review itself
 * isn't personal data once the byline is gone.
 */
public record ReviewResponse(
        UUID id,
        short rating,
        String title,
        String body,
        String authorName,
        int helpfulCount,
        /** Whether the CALLER has marked this helpful — false for anonymous readers. */
        boolean votedByMe,
        /** Whether the CALLER wrote this, so the UI can offer edit/delete without a second lookup. */
        boolean mine,
        String status,
        Instant createdAt
) {}
