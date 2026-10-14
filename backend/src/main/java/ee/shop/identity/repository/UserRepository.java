package ee.shop.identity.repository;

import ee.shop.identity.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Looked up by BLIND INDEX, not by the address itself (V24).
     *
     * users.email is AES-GCM ciphertext with a random IV, so the same address
     * encrypts differently every time and cannot be matched with `=`. The
     * deterministic keyed hash in email_hash is what makes the lookup possible;
     * EmailHasher lowercases before hashing, which preserves the
     * case-insensitive matching the CITEXT column used to give.
     */
    @Query("SELECT u FROM User u WHERE u.emailHash = :emailHash AND u.anonymizedAt IS NULL")
    Optional<User> findActiveByEmailHash(@Param("emailHash") String emailHash);

    boolean existsByEmailHash(String emailHash);

    /** Rows the blind-index backfill has not reached yet. */
}
