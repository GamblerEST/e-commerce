package ee.shop.identity.service;

import ee.shop.common.audit.AuditService;
import ee.shop.common.security.TokenCrypto;
import ee.shop.config.JwtProperties;
import ee.shop.identity.exception.InvalidRefreshTokenException;
import ee.shop.identity.exception.SessionExpiredException;
import ee.shop.identity.exception.SessionRevokedException;
import ee.shop.identity.model.RefreshToken;
import ee.shop.identity.model.User;
import ee.shop.identity.repository.RefreshTokenRepository;
import ee.shop.identity.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final AuditService audit;
    private final JwtProperties props;
    private final SecretKey key;

    public TokenService(RefreshTokenRepository tokens, UserRepository users,
                         AuditService audit, JwtProperties props) {
        this.tokens = tokens;
        this.users = users;
        this.audit = audit;
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    // ── Issue (login) ────────────────────────────────────────────────────────

    /** Starts a brand-new token family. Called on successful authentication. */
    @Transactional
    public TokenPair issueNewFamily(User user, String userAgent, String ip) {
        return mint(user, UUID.randomUUID(), null, userAgent, ip);
    }

    // ── Rotate (refresh) ─────────────────────────────────────────────────────

    /**
     * Exchange a refresh token for a new pair.
     *
     * This is the security-critical path. Read the claim() javadoc first.
     *
     * DELIBERATELY NOT @Transactional. A method-spanning transaction here
     * self-deadlocks under concurrent refreshes of the same token: a losing
     * claim() that had to WAIT for the winner keeps a residual row lock on
     * the token row until its transaction ends (READ COMMITTED re-check
     * semantics — Postgres never releases row locks early). If that same
     * transaction is still open when revokeFamily(REQUIRES_NEW) fires on a
     * second connection, the inner UPDATE blocks on the suspended outer's
     * row lock while the outer waits for the inner to return. Postgres
     * cannot see the cycle (it passes through the JVM), so it hangs until
     * a timeout. TokenRotationTest's 20-thread race catches this.
     *
     * So: claim() commits in its own short transaction (releasing every
     * lock), and only then does the failure branch diagnose and revoke —
     * which also means the revocation is durable regardless of this method
     * throwing afterwards. The trade-off: claim and mint are no longer one
     * atomic unit, so a crash between them burns the token without issuing
     * a successor. That fails CLOSED — the user just signs in again.
     */
    public TokenPair rotate(String rawToken, User user, String userAgent, String ip) {
        byte[] hash = TokenCrypto.sha256(rawToken);

        // Atomically claim. Wins the race, or doesn't.
        int claimed = tokens.claim(hash);

        if (claimed == 0) {
            // We could not claim it. Find out WHY — the distinction matters
            // enormously: an already-used token means someone replayed a token
            // that had already been rotated, which honest software never does.
            Optional<RefreshToken> found = tokens.findByTokenSha256(hash);

            if (found.isEmpty()) {
                // Token has never existed. Random guess, or a token we already
                // pruned. Nothing to revoke.
                audit.record("token.refresh.unknown", null, null, ip, userAgent);
                throw new InvalidRefreshTokenException();
            }

            RefreshToken t = found.get();

            if (t.isUsed()) {
                // ══ REPLAY DETECTED ══
                //
                // This token was already exchanged. There are exactly two ways
                // to get here:
                //   (a) an attacker stole the token and is using it, or
                //   (b) the legitimate client fired two refreshes concurrently.
                //
                // We cannot tell them apart, and we must not guess. Revoking the
                // whole family handles (a) correctly — the attacker's session
                // and the victim's both die, and the victim re-authenticates.
                //
                // It handles (b) with an unnecessary logout, which is why THE
                // FRONTEND MUST SINGLE-FLIGHT ITS REFRESH CALLS: at most one
                // in-flight /auth/refresh, with all other pending requests
                // awaiting that same promise. This is a hard requirement on the
                // client, not a nice-to-have. We will build it that way.
                //
                // Do not be tempted to "solve" this with a grace window that
                // accepts a recently-used token. That reintroduces exactly the
                // replay hole this mechanism exists to close.
                int killed = tokens.revokeFamily(t.getFamilyId(), "reuse_detected");

                log.warn("Refresh token REPLAY detected. user={} family={} tokens_revoked={} ip={}",
                        t.getUserId(), t.getFamilyId(), killed, ip);

                audit.record("token.reuse_detected", t.getUserId(),
                        "refresh_token_family:" + t.getFamilyId(), ip, userAgent);

                throw new SessionRevokedException();
            }

            if (t.isRevoked()) {
                audit.record("token.refresh.revoked", t.getUserId(), null, ip, userAgent);
                throw new SessionRevokedException();
            }

            // Expired.
            throw new SessionExpiredException();
        }

        // Claimed successfully. Re-read to get the lineage, then mint the child
        // in the SAME family, pointing at the token we just consumed.
        RefreshToken parent = tokens.findByTokenSha256(hash).orElseThrow();
        return mint(user, parent.getFamilyId(), parent.getId(), userAgent, ip);
    }

    /**
     * Entry point for the PUBLIC /auth/refresh endpoint, which has no
     * authenticated caller and therefore no User to hand to rotate(...) —
     * only the raw token string. This resolves the owning user FROM the
     * token itself before doing anything else, so a controller can never
     * (accidentally or otherwise) pass in the wrong User. The atomic
     * single-use claim inside rotate(...) remains the sole authority on
     * whether the token is actually still valid.
     *
     * Not @Transactional for the same reason as rotate(...) above.
     */
    public TokenPair rotateForToken(String rawToken, String userAgent, String ip) {
        User user = tokens.findByTokenSha256(TokenCrypto.sha256(rawToken))
                .map(RefreshToken::getUserId)
                .flatMap(users::findById)
                .filter(User::isActive)
                .orElseGet(() -> {
                    audit.record("token.refresh.unknown", null, null, ip, userAgent);
                    throw new InvalidRefreshTokenException();
                });

        return rotate(rawToken, user, userAgent, ip);
    }

    // ── Revoke ───────────────────────────────────────────────────────────────

    @Transactional
    public void revokeFamilyOf(String rawToken, String reason) {
        tokens.findByTokenSha256(TokenCrypto.sha256(rawToken))
              .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), reason));
    }

    /** Password changed, account compromised, GDPR erasure. Nuke everything. */
    @Transactional
    public void revokeAllSessions(UUID userId, String reason) {
        int n = tokens.revokeAllForUser(userId, reason);
        audit.record("token.revoke_all", userId, "count:" + n, null, null);
    }

    // ── Access token (JWT) ───────────────────────────────────────────────────

    /**
     * Access tokens are short-lived and stateless — we deliberately do NOT check
     * them against the database on every request, because that would throw away
     * the entire point of using a JWT.
     *
     * The consequence: revoking a session does not instantly kill an access
     * token already in the wild. It stays valid for up to its remaining TTL
     * (<= 15 min) UNLESS its jti is explicitly denylisted — see
     * AccessTokenDenylistService. That denylist is deliberately narrow: it
     * exists ONLY to make an explicit /auth/logout feel instant to the user
     * who clicked it, not as a general revocation mechanism. Password
     * changes, GDPR erasure, and reuse-detection all still rely on the
     * refresh-token family dying and the access token merely expiring — if
     * we denylisted on every one of those too, we would have reinvented
     * server-side sessions with extra steps, which is the thing a stateless
     * JWT exists to avoid.
     */
    private String accessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .claim("role", user.getRole().name())
                // NOTE: no email, no name. A JWT is base64, not encryption —
                // anyone holding it can read every claim. Do not put PII in it.
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTtl())))
                .signWith(key)
                .compact();
    }

    /**
     * The single source of truth for verifying an access token — used by
     * JwtAuthFilter so that key construction and parsing logic exist in
     * exactly one place. requireIssuer matters: without it, a token signed
     * with this same key by some OTHER service we also operate would be
     * accepted here. Any failure (expired, bad signature, malformed, wrong
     * issuer) collapses to an empty Optional rather than distinguishing the
     * reason, since the filter's only response to any of them is "treat the
     * request as unauthenticated".
     */
    public Optional<AccessTokenClaims> verifyAccessToken(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key)
                    .requireIssuer(props.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new AccessTokenClaims(
                    UUID.fromString(c.getSubject()),
                    c.get("role", String.class),
                    c.getId(),
                    c.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record AccessTokenClaims(UUID userId, String role, String jti, Instant expiresAt) {}

    // ── Internals ────────────────────────────────────────────────────────────

    private TokenPair mint(User user, UUID familyId, UUID parentId, String userAgent, String ip) {
        String raw = TokenCrypto.randomToken();
        Instant expiry = Instant.now().plus(props.refreshTtl());

        tokens.save(new RefreshToken(
                user.getId(), familyId, parentId, TokenCrypto.sha256(raw), expiry, userAgent, ip));

        return new TokenPair(accessToken(user), raw, props.accessTtl().toSeconds());
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {}
}
