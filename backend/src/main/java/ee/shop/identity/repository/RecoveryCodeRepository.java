package ee.shop.identity.repository;

import ee.shop.identity.model.RecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {

    List<RecoveryCode> findByUserIdAndConsumedAtIsNull(UUID userId);

    /**
     * Atomic single-row claim — same reasoning as every other single-use
     * token in this codebase (RefreshTokenRepository.claim,
     * OneTimeTokenRepository.claim). The caller has already found a
     * candidate row via Argon2 matches() (unavoidable — hashed codes can't
     * be looked up by equality), but MARKING it consumed must still be a
     * guarded UPDATE, not a blind save(), or two concurrent logins racing
     * the same recovery code could both succeed.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE recovery_codes SET consumed_at = now() WHERE id = :id AND consumed_at IS NULL",
            nativeQuery = true)
    int claim(@Param("id") UUID id);

    @Modifying
    @Query(value = "DELETE FROM recovery_codes WHERE user_id = :userId", nativeQuery = true)
    void deleteAllForUser(@Param("userId") UUID userId);
}
