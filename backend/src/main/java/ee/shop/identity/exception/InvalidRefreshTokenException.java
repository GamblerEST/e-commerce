package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/** Token absent, unknown, or malformed — nothing to revoke, no lineage to trust. */
public class InvalidRefreshTokenException extends AppException {
    public InvalidRefreshTokenException() {
        super("Invalid refresh token", HttpStatus.UNAUTHORIZED);
    }
}
