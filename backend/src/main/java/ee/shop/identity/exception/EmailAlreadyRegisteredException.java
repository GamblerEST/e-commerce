package ee.shop.identity.exception;

import ee.shop.common.error.AppException;
import org.springframework.http.HttpStatus;

public class EmailAlreadyRegisteredException extends AppException {
    public EmailAlreadyRegisteredException() {
        super("Email already registered", HttpStatus.CONFLICT);
    }
}
