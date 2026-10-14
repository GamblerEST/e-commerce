package ee.shop.identity.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per refresh token ever issued. Rows are never deleted on rotation —
 * we need the used/revoked history to detect replay.
 *
 * Lineage:
 *
 *   login    -> token A   family=F  parent=null
 *   refresh  -> token B   family=F  parent=A     (and A.used_at is set)
 *   refresh  -> token C   family=F  parent=B     (and B.used_at is set)
 *
 * A rotated token is dead. If one is ever presented again, that is not something
 * an honest client does — it means the token was captured. See TokenService.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** All tokens descended from one login share a family. Revoked together. */
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "parent_id")
    private UUID parentId;

    /**
     * SHA-256 of the raw token. The raw value exists only in the HTTP response
     * and the client's cookie — never at rest. A stolen database dump therefore
     * yields no usable tokens.
     */
    @Column(name = "token_sha256", nullable = false)
    private byte[] tokenSha256;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set when this token is exchanged. A second exchange attempt = replay. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    @Column(name = "user_agent")
    private String userAgent;

    /**
     * Postgres won't implicitly cast a bind parameter to INET (unlike a string
     * literal), so a plain @Column insert fails with "column is of type inet
     * but expression is of type character varying". @ColumnTransformer wraps
     * every write in an explicit cast.
     */
    @org.hibernate.annotations.ColumnTransformer(write = "?::inet")
    @Column(name = "ip", columnDefinition = "inet")
    private String ip;

    protected RefreshToken() {}   // JPA

    public RefreshToken(UUID userId, UUID familyId, UUID parentId,
                        byte[] tokenSha256, Instant expiresAt,
                        String userAgent, String ip) {
        this.userId = userId;
        this.familyId = familyId;
        this.parentId = parentId;
        this.tokenSha256 = tokenSha256;
        this.expiresAt = expiresAt;
        this.userAgent = userAgent;
        this.ip = ip;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public UUID getParentId() { return parentId; }
    public String getRevokedReason() { return revokedReason; }

    public boolean isUsed()    { return usedAt != null; }
    public boolean isRevoked() { return revokedAt != null; }
}
