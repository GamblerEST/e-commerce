package ee.shop.identity.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Links a local user to a third-party identity provider. Separate table, not
 * columns on users — a person may link Google today and another provider
 * tomorrow; columns would force a schema change and cap them at one. See
 * V1__identity.sql's oauth_identities table.
 */
@Entity
@Table(name = "oauth_identities")
public class OAuthIdentity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 'google' */
    @Column(nullable = false)
    private String provider;

    /** The provider's stable user id ('sub' for Google). */
    @Column(nullable = false)
    private String subject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OAuthIdentity() {} // JPA

    public OAuthIdentity(UUID userId, String provider, String subject) {
        this.userId = userId;
        this.provider = provider;
        this.subject = subject;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getSubject() { return subject; }
}
