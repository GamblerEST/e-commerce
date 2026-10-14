package ee.shop.identity.web.dto;

import ee.shop.identity.model.User;

import java.time.Instant;
import java.util.UUID;

/**
 * What an administrator may see about an account.
 *
 * Built by explicitly naming fields rather than serialising the User entity,
 * which carries passwordHash and totpSecretEnc. Those must never leave the
 * server — not to an admin, not to anyone — and an allow-list like this
 * cannot leak a field somebody adds to the entity later, whereas returning
 * the entity itself would (trim API responses to what the caller
 * actually needs).
 *
 * twoFactorEnabled is a boolean, not the secret: an admin needs to know
 * whether a colleague has 2FA on (it now gates every privileged action),
 * never what their secret is.
 */
public record AdminUserView(
        UUID id,
        String email,
        String displayName,
        String role,
        boolean emailVerified,
        boolean twoFactorEnabled,
        boolean disabled,
        boolean anonymized,
        Instant createdAt
) {
    public static AdminUserView of(User u) {
        return new AdminUserView(
                u.getId(),
                u.getEmail(),
                u.getDisplayName(),
                u.getRole().name(),
                u.isEmailVerified(),
                u.is2faEnabled(),
                !u.isActive() && u.getAnonymizedAt() == null,
                u.getAnonymizedAt() != null,
                u.getCreatedAt());
    }
}
