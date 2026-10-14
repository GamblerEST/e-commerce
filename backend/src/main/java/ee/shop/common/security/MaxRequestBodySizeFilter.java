package ee.shop.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Every DTO field carries an explicit @Size limit (see the DTOs themselves),
 * but that only bounds an individual FIELD once Jackson has already read
 * and parsed the whole request body — nothing stops a client from sending
 * an enormous body in the first place. Rejects on the declared
 * Content-Length before any of that parsing work happens, at the cheapest
 * possible point: this filter is ordered to run before Spring Security's
 * own chain (CSRF/JWT), so an oversized request is refused before spending
 * any compute on it.
 *
 * Content-Length only, not a streaming byte-count — a request lying about
 * its length via chunked transfer encoding isn't caught here. That's an
 * accepted gap for a defense-in-depth control against accidental/naive
 * oversized payloads, not a claim of a hard guarantee against a determined
 * attacker; Jackson's own parser has its own bounded read limits regardless.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MaxRequestBodySizeFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public MaxRequestBodySizeFilter(@Value("${app.security.max-request-body-size-bytes:1048576}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request body too large");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
