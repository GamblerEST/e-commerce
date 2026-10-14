package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

public class SessionExpiredException extends AppException {
    public SessionExpiredException() {
        super("Session expired. Please sign in again.", HttpStatus.UNAUTHORIZED);
    }
}
