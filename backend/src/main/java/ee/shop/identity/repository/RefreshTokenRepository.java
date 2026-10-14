package ee.shop.identity.repository;

import ee.shop.identity.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenSha256(byte[] tokenSha256);

    /**
     * Atomically CLAIM a token for rotation.
     *
     * ── Why this is a single UPDATE and not SELECT-then-UPDATE ────────────────
     *
     * The obvious implementation is:
     *
     *     var token = repo.findByHash(h);          // 1. read
     *     if (token.isUsed()) -> reuse detected;   // 2. decide
     *     token.setUsedAt(now); repo.save(token);  // 3. write
     *
     * That is a check-then-act race, the exact same class of bug as the
     * inventory oversell. Two concurrent requests with the same token both
     * execute step 1, both see used_at = NULL, both proceed. Result: the token
     * is exchanged TWICE and reuse detection never fires — the one thing this
     * whole mechanism exists to catch, defeated by a double-click.
     *
     * A single UPDATE ... WHERE used_at IS NULL is atomic. Postgres takes a row
     * lock; the second transaction blocks, re-reads under the lock, finds
     * used_at now set, and its WHERE matches zero rows. It returns 0.
     *
     * So: returns 1 -> we won the race, this token is ours to rotate.
     *     returns 0 -> not claimable. Could be: unknown token, ALREADY USED
     *                  (replay!), revoked, or expired. The caller must then
     *                  look up which, because "already used" means theft and
     *                  the others merely mean "log in again".
     *
     * @Transactional here because TokenService.rotate deliberately runs
     * WITHOUT a method-spanning transaction (see its javadoc — an open
     * outer transaction self-deadlocks against revokeFamily's REQUIRES_NEW
     * under concurrent refreshes). The claim therefore commits — and
     * releases its locks — in its own short transaction right here.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE refresh_tokens
               SET used_at = now()
             WHERE token_sha256 = :hash
               AND used_at    IS NULL
               AND revoked_at IS NULL
               AND expires_at > now()
            """, nativeQuery = true)
    int claim(@Param("hash") byte[] hash);

    /**
     * Revoke an entire family. Called when replay is detected: we cannot know
     * whether the attacker holds the current token or the victim does, so we
     * kill every token descended from that login and force a fresh
     * authentication. A user re-typing their password is a small cost; a
     * silently hijacked session is not.
     *
     * REQUIRES_NEW is deliberate, same reasoning as AuditService.record(...).
     * The caller (TokenService.rotate) invokes this on the reuse-detected path
     * and then immediately throws AppException to reject the request. Without
     * its own transaction, that throw would trigger Spring's default rollback
     * rule on the SURROUNDING @Transactional method and silently undo the
     * revocation — the exact security response this mechanism exists to
     * perform would vanish along with the exception that reported it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
            UPDATE refresh_tokens
               SET revoked_at = now(), revoked_reason = :reason
             WHERE family_id = :familyId
               AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("reason") String reason);

    /**
     * Revoke every session for a user: password change, admin lockout, erasure.
     * REQUIRES_NEW for the same reason as revokeFamily above — this is an
     * irreversible security action and must survive whatever the caller does
     * (or throws) after invoking it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
            UPDATE refresh_tokens
               SET revoked_at = now(), revoked_reason = :reason
             WHERE user_id = :userId
               AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("reason") String reason);

    /** Housekeeping: drop tokens that expired long ago and can prove nothing. */
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE expires_at < :before", nativeQuery = true)
    int deleteExpiredBefore(@Param("before") Instant before);
}
