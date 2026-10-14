package ee.shop.config;

import ee.shop.common.security.CsrfCookieFilter;
import ee.shop.common.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final CsrfCookieFilter csrfCookieFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, CsrfCookieFilter csrfCookieFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.csrfCookieFilter = csrfCookieFilter;
    }

    /**
     * Argon2id — OWASP's first-choice password hash.
     *
     * Why not BCrypt: BCrypt is CPU-hard but only ~4 KB memory-hard, so a GPU can
     * run ~100k guesses in parallel. Argon2id needs 19 MiB *per guess*, so a 24 GB
     * GPU manages ~1,200. That multiplier is the entire point.
     *
     * Why DelegatingPasswordEncoder: it stores hashes prefixed with the algorithm,
     * e.g. {argon2}$argon2id$v=19$m=19456,t=2,p=1$... That prefix is the escape
     * hatch. When Argon2 is eventually superseded we change the default, old hashes
     * keep verifying under their original scheme. Moving users onto a new scheme at
     * their next login is not built yet, because only the argon2 scheme exists today.
     * A bare Argon2PasswordEncoder leaves exactly
     * one migration path: email everyone a reset link.
     *
     * Cost parameters are encoded in each hash, so raising them later is safe.
     */
    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${app.security.argon2.memory-kb}") int memoryKb,
            @Value("${app.security.argon2.iterations}") int iterations,
            @Value("${app.security.argon2.parallelism}") int parallelism) {
        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(
                16,          // salt length, bytes
                32,          // hash length, bytes
                parallelism,
                memoryKb,
                iterations
        );

        // Only argon2 is registered. A hash arriving with a different {id} prefix,
        // or none at all, fails to match rather than being silently guessed at.
        // If we ever import users from another system we add their scheme here as
        // a decode-only entry and re-hash on first successful login.
        return new DelegatingPasswordEncoder("argon2", Map.of("argon2", argon2));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors)
            throws Exception {

        http
            // Every OTHER endpoint authenticates via a Bearer header in
            // Authorization, which a cross-site request cannot attach
            // automatically — CSRF does not apply there. /api/auth/refresh is
            // the one endpoint whose authority comes from an ambient cookie
            // (the refresh_token cookie set by AuthController), which IS sent
            // automatically cross-site — precisely the CSRF precondition. See
            // DECISIONS.md §11: this is the re-enablement it calls for.
            //
            // CookieCsrfTokenRepository writes a readable (non-HttpOnly)
            // XSRF-TOKEN cookie the client echoes back as the X-XSRF-TOKEN
            // header; CsrfCookieFilter (below) is what actually forces that
            // cookie to be written on a pure REST API with no server-rendered
            // form to trigger it lazily.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // Cart mutations join /auth/refresh here for exactly the same
                // reason (DECISIONS.md §11): a guest shopper's identity comes
                // from the ambient guest_cart_id cookie (ee.shop.cart.web.
                // GuestCartCookieFactory), which the browser attaches
                // automatically cross-site — the CSRF precondition. Applied
                // uniformly to authenticated cart requests too rather than
                // conditioning the matcher on auth state, since the frontend
                // sends the CSRF header on every mutating call regardless.
                .requireCsrfProtectionMatcher(csrfRequiredMatcher())
            )

            .cors(c -> c.configurationSource(cors))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/api/auth/logout",
                        // NOT a wildcard: /api/auth/password/change is
                        // deliberately excluded, since it requires proving
                        // you hold the CURRENT password, which only makes
                        // sense for an authenticated caller. It falls
                        // through to anyRequest().authenticated() below.
                        "/api/auth/password/forgot",
                        "/api/auth/password/reset",
                        "/api/auth/verify-email",
                        "/api/auth/verify-email/resend",
                        "/api/auth/oauth2/**"
                ).permitAll()

                // Browsing the catalog needs no account.
                //
                // HttpMethod.GET, not the string "GET". The String overload of
                // requestMatchers takes PATTERNS - so requestMatchers("GET", "/api/x")
                // would have quietly registered a *path* literally called "GET" and
                // left /api/products open to every verb. A genuinely nasty bug,
                // because it fails open and nothing complains.
                .requestMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**").permitAll()

                // Cart works for guests too — identity
                // comes from either a Bearer token OR the guest_cart_id
                // cookie, resolved inside CartController, not here. permitAll
                // does not skip JwtAuthFilter: a request that DOES carry a
                // valid Bearer token still gets its Authentication populated,
                // so CartController can still tell the two cases apart.
                .requestMatchers("/api/cart/**").permitAll()

                // Checkout works for guests too (guest
                // checkout) — same permitAll + optional-Authentication shape
                // as cart, resolved inside CheckoutController.
                .requestMatchers(HttpMethod.POST, "/api/checkout").permitAll()

                // The delivery menu has to be readable before anyone signs in,
                // for the same reason checkout does: a guest picks how their
                // order ships. Only ACTIVE options are returned, and
                // OrderService re-checks availability at checkout — this is a
                // public price list, not an authorization decision.
                .requestMatchers(HttpMethod.GET, "/api/delivery-options").permitAll()

                // Address search for the checkout form: a guest has no account.
                // Rate limited per IP in AddressLookupController.
                .requestMatchers(HttpMethod.GET, "/api/addresses/search").permitAll()

                // Public pages pre-filled for crawlers and link previews (SeoPageController).
                // GET only, and only these paths: every other route is the single-page
                // app, served by the proxy without touching the backend.
                .requestMatchers(HttpMethod.GET, "/", "/products", "/products/*", "/about",
                        "/contacts", "/privacy", "/sales-conditions").permitAll()

                // The public contact form. Unauthenticated by necessity — the
                // people most likely to need it are the ones who cannot log
                // in. Rate limited per IP in ContactController, because an
                // unauthenticated endpoint that sends mail is otherwise an
                // open relay pointed at the shop's own inbox.
                .requestMatchers(HttpMethod.POST, "/api/contact").permitAll()

                // Crawler-facing, generated from the catalogue (see
                // SitemapController). Public by definition — a sitemap behind
                // authentication is a sitemap no crawler can read.
                .requestMatchers(HttpMethod.GET, "/robots.txt", "/sitemap.xml").permitAll()

                // The shop window. Public for the same reason the catalog is:
                // it IS the landing page, so requiring auth would mean an
                // anonymous visitor's first request 401s. Returns only
                // already-public catalog data (featured products, promoted
                // categories) — no per-user content, so there is nothing here
                // an anonymous caller could not already read from
                // /api/products.
                .requestMatchers(HttpMethod.GET, "/api/home").permitAll()

                // Static product images served from src/main/resources/static/
                // (see V3__catalog.sql / CatalogController) — same public
                // reasoning as the catalog API itself above.
                .requestMatchers(HttpMethod.GET, "/product-images/**").permitAll()

                // Stripe calls this directly — it can never carry our Bearer
                // JWT or ambient cookies, so it has to be open here. That is
                // NOT the same as unauthenticated: StripeWebhookController
                // rejects anything whose Stripe-Signature header doesn't
                // verify against our webhook secret (PaymentService.
                // verifyAndParseWebhook) before trusting a single field of
                // the payload. Also excluded from csrfRequiredMatcher() below
                // on purpose — CSRF is about an ambient BROWSER credential
                // being replayed cross-site (DECISIONS.md §11); there is no
                // browser and no cookie in this request at all.
                .requestMatchers(HttpMethod.POST, "/api/stripe/webhook").permitAll()

                // OpenAPI spec + Swagger UI: documentation, not data — same
                // public reasoning as the catalog API itself.
                .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                // Docker's own healthcheck (docker-compose.yml) hits this
                // directly on localhost:8080 INSIDE the container — it never
                // goes through Caddy, so Caddy's /actuator/* 404 rule can't
                // help it, and a denyAll() here would make the container
                // permanently unhealthy. show-details: never (application.yml)
                // means this leaks nothing beyond UP/DOWN, which is the
                // entire point of a liveness/readiness probe.
                .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()

                // Everything else under /actuator (prometheus, info, ...) stays
                // locked down. Caddy already 404s /actuator/* publicly; this is
                // the second, independent control so a Caddyfile mistake is not
                // a breach on its own.
                .requestMatchers("/actuator/**").denyAll()

                .anyRequest().authenticated()
            )

            // No httpBasic, no form login — the only way in is a Bearer JWT,
            // verified by JwtAuthFilter below. Without an explicit entry
            // point, an unauthenticated request hitting
            // anyRequest().authenticated() is not guaranteed a 401 by Spring
            // Security's defaults (it falls back to 403, since there is no
            // login page or WWW-Authenticate realm to challenge with), so
            // this is pinned explicitly.
            .exceptionHandling(e -> e.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            // Our JWT filter runs before Spring's username/password filter.
            //
            // NOTE: no .httpBasic() and no .formLogin(). Either would open a second,
            // weaker authentication path into the application. JWT is the only way in.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            // Positional anchor only — BasicAuthenticationFilter need not
            // actually be registered for addFilterAfter to place this
            // relative to Spring Security's well-known filter ordering.
            .addFilterAfter(csrfCookieFilter, BasicAuthenticationFilter.class);

        return http.build();
    }

    private static RequestMatcher csrfRequiredMatcher() {
        return new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/refresh"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/cart/items"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, "/api/cart/items/*"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/cart/items/*"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/cart"),
                // Same ambient-cookie-identity reasoning for guest checkout.
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/checkout")
        );
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String origins) {

        CorsConfiguration c = new CorsConfiguration();

        // Explicit allowlist. NEVER "*" alongside allowCredentials - browsers reject
        // that combination outright, and reaching for it is a reliable sign that
        // someone is about to disable a security control to make an error go away.
        c.setAllowedOrigins(List.of(origins.split(",")));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // The idempotency header is "Idempotency-Key" (no X- prefix, matching
        // Stripe's own convention) — not X-Idempotency-Key, which this
        // allowlist previously anticipated before checkout existed to actually
        // send it.
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-XSRF-TOKEN"));
        c.setAllowCredentials(true);   // required for the refresh cookie and CSRF cookie
        c.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }
}
