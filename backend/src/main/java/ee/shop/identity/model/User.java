package ee.shop.identity.model;

import ee.shop.common.security.EncryptedStringConverter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    /**
     * SUPPORT sits between CUSTOMER and ADMIN: it can see orders, accounts
     * and the review moderation queue (the things needed to answer "where is
     * my order"), but cannot change the catalogue, move money, or grant
     * roles. See V16__support_role.sql and ee.shop.common.security.StaffOnly.
     *
     * SUPPORT is also the sales role. Sales staff need the same access —
     * orders, customer accounts, reviews — and nothing more, so a separate
     * SALES value would be a second name for identical permissions.
     */
    public enum Role { CUSTOMER, SUPPORT, ADMIN }

    @Id @GeneratedValue
    private UUID id;

    /**
     * AES-GCM ciphertext (V24). NULL once anonymised.
     *
     * Plain TEXT since V24. It was CITEXT, so that two spellings of one
     * address were one account — but case-insensitivity is meaningless over
     * ciphertext, which differs on every write anyway. Case-insensitive
     * MATCHING moved to emailHash below, computed from the lowercased address.
     */
    @Convert(converter = EncryptedStringConverter.class)
    private String email;

    /**
     * Blind index: a deterministic keyed hash of the lowercased email
     * (EmailHasher). This is what every lookup and the unique index use, because
     * the encrypted column above cannot be searched.
     *
     * Kept in lockstep with `email` through setEmail() — the two must never
     * disagree, or an account becomes unfindable or duplicable.
     */
    @Column(name = "email_hash")
    private String emailHash;

    @Column(name = "password_hash")
    private String passwordHash;

    // Personal data: encrypted at rest (V20). Never used in a WHERE clause,
    // so the randomised IV that makes these columns unsearchable costs
    // nothing here.
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "display_name")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.CUSTOMER;

    @Column(name = "email_verified_at") private Instant emailVerifiedAt;
    @Column(name = "disabled_at")       private Instant disabledAt;
    @Column(name = "totp_secret_enc")   private String  totpSecretEnc;
    @Column(name = "totp_enabled_at")   private Instant totpEnabledAt;
    @Column(name = "anonymized_at")     private Instant anonymizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected User() {}

    public User(String email, String emailHash, String passwordHash, String displayName) {
        this(passwordHash, displayName);
        setEmail(email, emailHash);
    }

    private User(String passwordHash, String displayName) {
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    /**
     * Sets both halves together, because they must never disagree: an address
     * whose hash is stale is an account that cannot be found, and one whose
     * hash is missing is an account the unique index no longer protects.
     */
    public void setEmail(String email, String emailHash) {
        this.email = email;
        this.emailHash = emailHash;
    }


    /** Administrative role change — see AdminUserService for the guards around calling this. */
    public void changeRole(Role role) {
        this.role = role;
        this.updatedAt = Instant.now();
    }

    /**
     * Suspends sign-in without destroying anything. Distinct from
     * anonymization (DECISIONS.md §5), which is irreversible and erases PII —
     * this is the reversible "lock the door for now" action.
     */
    public void disable() {
        this.disabledAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void enable() {
        this.disabledAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() { return disabledAt == null && anonymizedAt == null; }
    public boolean is2faEnabled() { return totpEnabledAt != null; }
    public boolean isEmailVerified() { return emailVerifiedAt != null; }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String h) { this.passwordHash = h; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Role getRole() { return role; }
    public String getTotpSecretEnc() { return totpSecretEnc; }
    public Instant getAnonymizedAt() { return anonymizedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void markEmailVerified() { this.emailVerifiedAt = Instant.now(); }

    /** Stores the encrypted secret from setup, NOT yet 2FA-enabled — see enableTotp(). */
    public void setTotpSecretEnc(String encryptedSecret) { this.totpSecretEnc = encryptedSecret; }

    /** Only call after verifying a real code against the just-set secret. */
    public void enableTotp() { this.totpEnabledAt = Instant.now(); }

    /** Only call after verifying the CURRENT code/recovery code — see
     *  TotpController.disable. Clears the secret too, not just the enabled
     *  flag, so a stale encrypted secret can't be silently re-armed. */
    public void disableTotp() { this.totpEnabledAt = null; this.totpSecretEnc = null; }
}
