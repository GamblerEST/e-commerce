package ee.shop.support.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A message from the public contact form.
 *
 * Every field is length-bounded. This endpoint is unauthenticated and reachable
 * by anyone, so an unbounded free-text field is an invitation to post a
 * multi-megabyte body — the bound is the cheap half of the defence, and the
 * rate limit is the other half.
 */
public record ContactRequest(
        @NotBlank(message = "Tell us your name so we know who to reply to")
        @Size(max = 120) String name,

        // Bean Validation's @Email is deliberately permissive (it accepts
        // "a@b"). That is correct here: the only real test of an address is
        // whether mail reaches it, and rejecting unusual-but-valid addresses
        // loses a customer to satisfy a regex.
        @NotBlank(message = "We need an email address to reply to")
        @Email(message = "That does not look like an email address")
        @Size(max = 254) String email,

        @NotBlank(message = "Please add a subject")
        @Size(max = 150) String subject,

        @NotBlank(message = "Please write your message")
        @Size(min = 10, max = 4000,
              message = "Messages should be between 10 and 4000 characters") String message
) {}
