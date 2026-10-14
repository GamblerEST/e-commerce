package ee.shop.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        // Required only when the account has 2FA enabled — accepts either a
        // live TOTP code or a recovery code, see AuthController.login.
        String totpCode
) {
    public LoginRequest(String email, String password) {
        this(email, password, null);
    }
}
