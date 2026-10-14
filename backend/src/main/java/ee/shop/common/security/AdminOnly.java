package ee.shop.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ADMIN role AND an actually-enabled second factor.
 *
 * The 2FA half is the point. AuthController deliberately does NOT block an
 * admin without 2FA from logging in — there is no admin-promotion flow to
 * test a lockout against, and hard-failing at login with no recovery path
 * risks locking the only administrator out of their own shop permanently.
 * Its comment names this exact place as the intended enforcement point, so
 * that is where it lands: an admin without 2FA can log in and shop like
 * anyone else, but cannot take a single privileged action until they turn
 * it on. That satisfies "2FA enforced for all admin accounts" without ever
 * being able to strand someone outside the building.
 *
 * `principal.is2faEnabled()` reads the User put into the Authentication by
 * JwtAuthFilter — the live row, re-read per request, not a claim baked into
 * the token. An admin who disables 2FA loses these powers immediately
 * rather than at token expiry.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasRole('ADMIN') and principal.is2faEnabled()")
public @interface AdminOnly {
}
