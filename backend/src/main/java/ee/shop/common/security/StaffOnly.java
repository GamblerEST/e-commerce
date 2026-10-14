package ee.shop.common.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ADMIN or SUPPORT, and in both cases with 2FA actually enabled.
 *
 * A second factor is strictly required only for admins, but SUPPORT reads
 * other people's orders and account details — that is exactly the access an
 * attacker wants, and "it wasn't technically an admin account" is a poor
 * thing to explain after a support login gets phished. Same reasoning, same
 * requirement; see AdminOnly for why enforcement lives here rather than at
 * login.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("(hasRole('ADMIN') or hasRole('SUPPORT')) and principal.is2faEnabled()")
public @interface StaffOnly {
}
