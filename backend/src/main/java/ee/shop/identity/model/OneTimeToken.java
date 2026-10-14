package ee.shop.identity.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Backs both email verification and password reset — identical mechanics
 * (single use, short TTL, hashed at rest), differing only by purpose. See
 * V1__identity.sql's one_time_tokens table comment for why one table beats two.
 */
@Entity
@Table(name = "one_time_tokens")
public class OneTimeToken {

    public enum Purpose { EMAIL_VERIFY, PASSWORD_RESET }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    /** SHA-256 of the raw token — see TokenCrypto. The raw value exists only
     *  in the outbound email, never at rest. */
    @Column(name = "token_sha256", nullable = false)
    private byte[] tokenSha256;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OneTimeToken() {} // JPA

    public OneTimeToken(UUID userId, Purpose purpose, byte[] tokenSha256, Instant expiresAt) {
        this.userId = userId;
        this.purpose = purpose;
        this.tokenSha256 = tokenSha256;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }

}
