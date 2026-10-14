package ee.shop.common.web;

import ee.shop.catalog.service.ProductImportService;
import ee.shop.common.error.AppException;
import ee.shop.common.error.FieldValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String message, Object errors, Instant timestamp) {
        static ApiError of(String message)                { return new ApiError(message, null, Instant.now()); }
        static ApiError of(String message, Object errors) { return new ApiError(message, errors, Instant.now()); }
    }

    /** @Valid failures on a request DTO -> 400 with a field:message map. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new HashMap<>();
        e.getBindingResult().getFieldErrors()
         .forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiError.of("Validation failed", fields));
    }

    /**
     * Method security (@AdminOnly / @StaffOnly) denials.
     *
     * Without this, Spring Security's AuthorizationDeniedException falls
     * through to the catch-all below and every denied admin call answers
     * 500 — which reads as "the server is broken", not "you may not do
     * that", and would page someone at 3am for a working control doing its
     * job. @EnableMethodSecurity had been switched on for a long time
     * without a single annotated method, so nothing surfaced this until the
     * first @PreAuthorize actually shipped.
     *
     * The message is deliberately flat: telling a caller WHICH condition
     * they failed (wrong role vs. no second factor) tells an attacker which
     * half of the requirement they have already satisfied.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiError> authorizationDenied(AuthorizationDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("Access denied"));
    }

    /**
     * A rejected bulk import. Returns the FULL list of row errors, not just
     * the first: fixing a 200-row spreadsheet one error per upload cycle is
     * not a workflow anyone completes. Nothing was written — the import is
     * all-or-nothing (ProductImportService).
     */
    @ExceptionHandler(ProductImportService.ImportFailedException.class)
    public ResponseEntity<ApiError> importFailed(ProductImportService.ImportFailedException e) {
        return ResponseEntity.badRequest().body(ApiError.of("Import rejected — nothing was saved", e.errors()));
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiError> app(AppException e) {
        return ResponseEntity.status(e.getStatus()).body(ApiError.of(e.getMessage()));
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ApiError> fieldValidation(FieldValidationException e) {
        return ResponseEntity.badRequest().body(ApiError.of("Validation failed", e.getErrors()));
    }

    /**
     * A query/path param that can't convert to its target type — e.g.
     * ?sort=NOT_A_REAL_VALUE against an enum @RequestParam. Without this,
     * Spring's own ConversionFailedException falls through to the generic
     * catch-all below and becomes a 500, which is wrong twice over: it's a
     * client mistake, not a server fault, and 500s are what paging/alerting
     * watches for.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("Invalid value for parameter '" + e.getName() + "'"));
    }

    /**
     * Malformed JSON, a wrong type for a field, or (with
     * spring.jackson.deserialization.fail-on-unknown-properties=true — see
     * application.yml) a field no DTO declares. All of these are the
     * CLIENT'S mistake, not ours — without this handler they fall through
     * to the generic catch-all below and become a 500, same reasoning as
     * MethodArgumentTypeMismatchException above. Never echo e.getMessage()
     * here either: Jackson's own exception text can include raw field
     * values from the request.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformedBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiError.of("Malformed or unexpected request body"));
    }

    /**
     * An unknown URL is a 404, not a 500.
     *
     * Without this, Spring's NoResourceFoundException falls through to the
     * catch-all below, so every typo'd path answers "500 An unexpected error
     * occurred" and logs a full stack trace. That is wrong three times over:
     * it tells a caller the server broke when the server is fine, it buries
     * genuine 500s in noise from bots probing for /wp-login.php, and during
     * development it sends you hunting for a bug when the real problem is a
     * misspelled URL. (Found exactly that way — a probe of
     * /api/account/totp/setup, whose real path is /api/auth/totp/setup,
     * reported a 500 and looked like a broken 2FA enrolment.)
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException e) {
        // Deliberately does not echo the requested path back — reflecting
        // caller-controlled input into a response body is how probing tools
        // confirm what they hit.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("Not found"));
    }

    /**
     * Catch-all. Log the stack trace internally; return a generic message.
     *
     * NEVER echo e.getMessage() to the client here. It routinely contains SQL
     * fragments, table names, file paths and library internals - a free map of the
     * system for anyone probing it. The client gets a sentence; we get the detail.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("An unexpected error occurred."));
    }
}
