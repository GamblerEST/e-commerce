package ee.shop.common.security;

import ee.shop.identity.model.User;
import ee.shop.identity.repository.UserRepository;
import ee.shop.identity.service.AccessTokenDenylistService;
import ee.shop.identity.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Validates the access token on every request and populates the SecurityContext.
 *
 * Deliberately does NOT throw on a bad token. It simply leaves the context empty
 * and continues down the chain; Spring Security then rejects the request at the
 * endpoint with a 401 if authentication was required, or serves it normally if the
 * endpoint is public. Throwing here would 401 anonymous requests to public
 * endpoints that merely happened to carry a stale token.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final UserRepository users;
    private final TokenService tokenService;
    private final AccessTokenDenylistService denylist;

    public JwtAuthFilter(UserRepository users, TokenService tokenService,
                          AccessTokenDenylistService denylist) {
        this.users = users;
        this.tokenService = tokenService;
        this.denylist = denylist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String token = bearerToken(req);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token);
        }

        chain.doFilter(req, res);
    }

    private void authenticate(String token) {
        Optional<TokenService.AccessTokenClaims> claims = tokenService.verifyAccessToken(token);
        if (claims.isEmpty()) {
            return; // expired, tampered, malformed, wrong issuer -> stays anonymous
        }

        // See AccessTokenDenylistService: this is the ONLY revocation check on
        // the otherwise-stateless access token, scoped narrowly to explicit
        // logout so that action feels instant instead of waiting out the TTL.
        if (denylist.isDenylisted(claims.get().jti())) {
            return;
        }

        // We hit the DB rather than trusting the token's role claim blindly.
        // The claim is signed and therefore authentic, but it may be STALE: a
        // user demoted from ADMIN 30 seconds ago still holds a token that says
        // ADMIN for up to 15 minutes. For a store with real admin powers, one
        // indexed primary-key lookup is a cheap price for correctness.
        Optional<User> found = users.findById(claims.get().userId());
        if (found.isEmpty() || !found.get().isActive()) {
            return;   // deleted, disabled or anonymized -> stays anonymous
        }
        User user = found.get();

        var auth = new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String bearerToken(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length());
        }
        return null;
    }
}
