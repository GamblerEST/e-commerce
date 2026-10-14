package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * Covers both email-verify and password-reset links: unknown, wrong
 * purpose, already used, or expired. The caller cannot and should not
 * distinguish which — a stale link and a forged one look identical to
 * whoever is holding it.
 */
public class InvalidOneTimeTokenException extends AppException {
    public InvalidOneTimeTokenException(String action) {
        super("Invalid or expired " + action + " link", HttpStatus.BAD_REQUEST);
    }
}
