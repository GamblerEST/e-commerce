package ee.shop.identity.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 2FA backup codes. Argon2-hashed (via the same PasswordEncoder as user
 * passwords), not SHA-256 like refresh/one-time tokens — these are short,
 * human-typeable, and therefore genuinely brute-forceable, so slow hashing
 * matters. See V1__identity.sql's recovery_codes table.
 */
@Entity
@Table(name = "recovery_codes")
public class RecoveryCode {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RecoveryCode() {} // JPA

    public RecoveryCode(UUID userId, String codeHash) {
        this.userId = userId;
        this.codeHash = codeHash;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
}
