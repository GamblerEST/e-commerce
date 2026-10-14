package ee.shop.identity.repository;

import ee.shop.identity.model.OneTimeToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID> {

    Optional<OneTimeToken> findByTokenSha256(byte[] tokenSha256);

    /**
     * Atomically CLAIM a one-time token — same reasoning as
     * RefreshTokenRepository.claim(): a SELECT-then-UPDATE would let two
     * concurrent uses of the same email-verify or password-reset link both
     * succeed. @Transactional here (own short transaction, not inherited from
     * a caller) for the same reason TokenService.rotate() stopped being
     * @Transactional — see its javadoc.
     *
     * purpose is compared as a plain string against the CITEXT-free TEXT
     * column (@Enumerated(STRING) stores the enum's name()).
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE one_time_tokens
               SET consumed_at = now()
             WHERE token_sha256 = :hash
               AND purpose = :purpose
               AND consumed_at IS NULL
               AND expires_at > now()
            """, nativeQuery = true)
    int claim(@Param("hash") byte[] hash, @Param("purpose") String purpose);
}
