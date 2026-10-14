package ee.shop.identity.web;

import ee.shop.common.security.EmailHasher;
import tools.jackson.databind.ObjectMapper;
import ee.shop.config.JwtProperties;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.LoginRequest;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.support.IntegrationTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * First HTTP-layer test in the repo. Also implicitly proves
 * GlobalExceptionHandler, SecurityConfig's JWT filter wiring, CSRF protection
 * on /api/auth/refresh, and the access-token denylist, on top of the endpoint
 * behavior itself.
 */
@Tag("api")
@Tag("flow")
@AutoConfigureMockMvc
class AuthControllerTest extends IntegrationTest {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired MockMvc mvc;
    @Autowired EmailHasher emailHasher;
    @Autowired ObjectMapper json;
    @Autowired JwtProperties jwtProps;
    @Autowired JdbcTemplate jdbc;

    private String email() {
        return "user" + java.util.UUID.randomUUID() + "@test.ee";
    }

    private MvcResult register(String email) throws Exception {
        var req = new RegisterRequest(email, "correct-horse-battery", "Test User", null);
        return mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private AuthResponse body(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    private Cookie cookie(MvcResult result, String name) {
        Cookie c = result.getResponse().getCookie(name);
        assertThat(c).as("cookie '%s' present on response", name).isNotNull();
        return c;
    }

    /** Every CsrfCookieFilter-touched response carries a fresh XSRF-TOKEN cookie. */
    private Cookie csrfCookie(MvcResult result) {
        return cookie(result, CSRF_COOKIE);
    }

    private MvcResult refresh(Cookie refreshCookie, Cookie csrfCookie) throws Exception {
        return mvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie, csrfCookie)
                        .header(CSRF_HEADER, csrfCookie.getValue()))
                .andReturn();
    }

    @Test
    void register_login_refresh_logout_roundTrip() throws Exception {
        String email = email();
        MvcResult registered = register(email);
        AuthResponse registeredBody = body(registered);

        var loginReq = new LoginRequest(email, "correct-horse-battery");
        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie refreshAfterLogin = cookie(loginResult, REFRESH_COOKIE);
        Cookie csrf = csrfCookie(loginResult);

        MvcResult refreshResult = refresh(refreshAfterLogin, csrf);
        assertThat(refreshResult.getResponse().getStatus()).isEqualTo(200);
        AuthResponse refreshed = body(refreshResult);
        Cookie refreshAfterRotate = cookie(refreshResult, REFRESH_COOKIE);

        assertThat(refreshAfterRotate.getValue()).isNotEqualTo(refreshAfterLogin.getValue());

        MvcResult logoutResult = mvc.perform(post("/api/auth/logout")
                        .cookie(refreshAfterRotate)
                        .header("Authorization", "Bearer " + refreshed.accessToken()))
                .andExpect(status().isNoContent())
                .andReturn();

        // The just-logged-out refresh token must no longer work.
        assertThat(refresh(cookie(logoutResult, REFRESH_COOKIE), csrf).getResponse().getStatus())
                .isEqualTo(401);

        // The just-logged-out ACCESS token must also stop working immediately —
        // this is the denylist, not merely waiting out the 15-minute TTL.
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + refreshed.accessToken()))
                .andExpect(status().isUnauthorized());

        assertThat(registeredBody.accessToken()).isNotBlank();
    }

    @Test
    void authResponse_neverCarriesTheRefreshTokenInTheBody() throws Exception {
        MvcResult registered = register(email());
        String rawBody = registered.getResponse().getContentAsString();

        assertThat(rawBody).doesNotContain("refreshToken", "refresh_token");
    }

    @Test
    void refreshCookie_isHttpOnlySecureAndSameSiteStrict() throws Exception {
        MvcResult registered = register(email());
        MockHttpServletResponse response = registered.getResponse();

        String setCookieHeader = response.getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(REFRESH_COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no refresh_token Set-Cookie header"));

        assertThat(setCookieHeader)
                .contains("HttpOnly")
                .contains("Secure")
                .containsIgnoringCase("SameSite=Strict")
                .contains("Path=/api/auth");
    }

    @Test
    void duplicateEmail_returnsConflict() throws Exception {
        String email = email();
        register(email);

        var req = new RegisterRequest(email, "another-password-1", "Someone Else", null);
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownEmailAndWrongPassword_returnTheSameUnauthorizedMessage() throws Exception {
        String email = email();
        register(email);

        String unknownEmailBody = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest("nobody-" + email, "whatever12345"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String wrongPasswordBody = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest(email, "totally-wrong-pw"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownEmailBody.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"X\""))
                .isEqualTo(wrongPasswordBody.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"X\""));
    }

    @Test
    void replayingAnAlreadyUsedRefreshToken_isRejectedAndKillsTheFamily() throws Exception {
        MvcResult registered = register(email());
        Cookie a = cookie(registered, REFRESH_COOKIE);
        Cookie csrf = csrfCookie(registered);

        MvcResult bResult = refresh(a, csrf);
        assertThat(bResult.getResponse().getStatus()).isEqualTo(200);
        Cookie b = cookie(bResult, REFRESH_COOKIE);

        // Replay the already-rotated token A.
        assertThat(refresh(a, csrf).getResponse().getStatus()).isEqualTo(401);

        // The whole family is dead now, including the still-young token B.
        assertThat(refresh(b, csrf).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refresh_withoutCsrfToken_isForbidden() throws Exception {
        MvcResult registered = register(email());
        Cookie refreshCookie = cookie(registered, REFRESH_COOKIE);

        // Refresh cookie present, but no XSRF-TOKEN cookie/header at all —
        // exactly what a forged cross-site POST would look like.
        mvc.perform(post("/api/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void refresh_withMismatchedCsrfHeader_isForbidden() throws Exception {
        MvcResult registered = register(email());
        Cookie refreshCookie = cookie(registered, REFRESH_COOKIE);
        Cookie csrf = csrfCookie(registered);

        mvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie, csrf)
                        .header(CSRF_HEADER, "not-the-real-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void me_requiresAValidBearerToken() throws Exception {
        MvcResult registered = register(email());
        AuthResponse registeredBody = body(registered);

        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer garbage-not-a-jwt"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + registeredBody.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    /**
     * A cryptographically VALID, correctly-signed token whose exp claim is in
     * the past — distinct from "garbage"/tampered (me_requiresAValidBearerToken
     * above): that exercises signature verification, this exercises TTL
     * enforcement. TokenService.verifyAccessToken collapses both to the same
     * empty Optional, so this is the only thing that actually proves expiry
     * is enforced rather than merely assumed from the code reading right.
     */
    @Test
    void expiredAccessToken_isRejected() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(jwtProps.secret().getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minusSeconds(3600);
        String expiredToken = Jwts.builder()
                .issuer(jwtProps.issuer())
                .subject(UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .claim("role", "CUSTOMER")
                .issuedAt(Date.from(past.minusSeconds(60)))
                .expiration(Date.from(past))
                .signWith(key)
                .compact();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A user disabled/anonymized AFTER a token was issued must lose access
     * before that token's 15-minute TTL naturally expires — JwtAuthFilter
     * re-checks isActive() against the DB on every request specifically for
     * this. There was no admin-disable endpoint when this was written, so it reaches
     * past the API the same way OAuthAccountServiceTest does.
     */
    @Test
    void disabledUser_isRejectedEvenWithAStillValidToken() throws Exception {
        String email = email();
        AuthResponse registeredBody = body(register(email));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + registeredBody.accessToken()))
                .andExpect(status().isOk());

        jdbc.update("UPDATE users SET disabled_at = now() WHERE email_hash = ?", emailHasher.hash(email));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + registeredBody.accessToken()))
                .andExpect(status().isUnauthorized());
    }
}
