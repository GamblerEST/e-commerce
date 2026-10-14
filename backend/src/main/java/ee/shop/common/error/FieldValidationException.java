package ee.shop.common.error;

import java.util.Map;

/**
 * A request that passed bean validation but fails a check only the server can
 * make, such as an address that does not match the register entry the form
 * says it came from.
 *
 * Rendered exactly like a bean-validation failure (400, "Validation failed",
 * field to message), so the form highlights the input to fix instead of showing
 * a banner the customer has to interpret.
 */
public class FieldValidationException extends RuntimeException {

    private final Map<String, String> errors;

    public FieldValidationException(String field, String message) {
        super(message);
        this.errors = Map.of(field, message);
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
