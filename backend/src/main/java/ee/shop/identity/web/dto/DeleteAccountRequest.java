package ee.shop.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Erasure is irreversible, so it is re-authenticated.
 *
 * Same reasoning as disabling 2FA: a stolen access token alone must not be
 * enough to destroy someone's account. The password proves the person at the
 * keyboard is the account holder, not whoever picked up an unlocked laptop.
 */
public record DeleteAccountRequest(
        @NotBlank(message = "Confirm your password to delete your account") String currentPassword
) {}
