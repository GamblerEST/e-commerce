package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * The `state` param didn't match a value we issued (missing, expired, or
 * already consumed) — the CSRF guard on the OAuth2 callback. See
 * OAuth2Controller: state is stored single-use in Redis with a short TTL.
 */
public class OAuthStateInvalidException extends AppException {
    public OAuthStateInvalidException() {
        super("Invalid or expired sign-in attempt, please try again", HttpStatus.BAD_REQUEST);
    }
}
