package ee.shop.identity.web.dto;

import ee.shop.identity.service.TokenService;

/**
 * Deliberately carries ONLY the access token. The refresh token never appears
 * in a JSON body — it travels exclusively as an HttpOnly Set-Cookie header
 * (see AuthController.setRefreshCookie), which is the entire point of moving
 * it into a cookie: JavaScript, and therefore an XSS payload, cannot read it.
 * Putting it back in the response body here would silently undo that.
 */
public record AuthResponse(String accessToken, long expiresInSeconds) {
    public static AuthResponse from(TokenService.TokenPair pair) {
        return new AuthResponse(pair.accessToken(), pair.expiresInSeconds());
    }
}
