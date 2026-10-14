package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/** The linked local account exists but is disabled or anonymized. */
public class OAuthAccountUnavailableException extends AppException {
    public OAuthAccountUnavailableException() {
        super("This account is no longer available", HttpStatus.UNAUTHORIZED);
    }
}
