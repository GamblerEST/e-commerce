package ee.shop.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Forces the deferred CsrfToken to materialize on every request, which is
 * what actually writes the XSRF-TOKEN cookie via CookieCsrfTokenRepository.
 * Spring Security 6's BREACH-protection deliberately makes the token lazy —
 * nothing reads it unless something asks for it — so without this filter a
 * pure REST API (nothing ever calls csrfToken.getToken() the way a
 * server-rendered form would) never gets the cookie written, and the client
 * has nothing to echo back on /api/auth/refresh.
 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
