package ee.shop.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The frontend's OWN callback route receives {code, state} from Google's
 * redirect as URL query params, then POSTs them here as JSON — this endpoint
 * never handles a browser redirect directly, matching every other auth
 * endpoint's plain POST+JSON contract.
 */
public record OAuthCallbackRequest(@NotBlank String code, @NotBlank String state) {}
