package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

/** Thrown by OAuth2Controller when GoogleOAuthProperties.isEnabled() is false. */
public class OAuth2NotConfiguredException extends AppException {
    public OAuth2NotConfiguredException() {
        super("Sign-in with Google is not available", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
