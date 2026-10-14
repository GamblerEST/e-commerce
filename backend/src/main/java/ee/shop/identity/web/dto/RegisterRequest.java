package ee.shop.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Never bind a request body straight to the User entity — a client could set role: ADMIN. */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 12, max = 100) String password,
        @NotBlank @Size(max = 120) String displayName,
        // Blank is valid when app.recaptcha.secret is unset — RecaptchaService
        // itself decides whether verification is actually enforced.
        String recaptchaToken
) {}
