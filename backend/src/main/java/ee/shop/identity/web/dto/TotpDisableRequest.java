package ee.shop.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/** code is a current TOTP code OR an unused recovery code — same acceptance
 *  as login, so disabling requires proving you still hold the second
 *  factor, not just an active session (a stolen access token alone must
 *  not be enough to turn off someone's 2FA). */
public record TotpDisableRequest(@NotBlank String code) {}
