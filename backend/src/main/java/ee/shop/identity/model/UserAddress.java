package ee.shop.identity.model;

import ee.shop.common.security.EncryptedStringConverter;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A saved delivery address in a customer's address book.
 *
 * Every personal field is encrypted at rest, exactly as the equivalent fields
 * on an order are (V20). An address book is the same category of data as a
 * billing snapshot, and it would be strange to protect one and not the other.
 *
 * Editing one of these never touches a past order: orders carry their own
 * snapshot (DECISIONS.md §2), so correcting a typo here cannot rewrite what an
 * old invoice said the goods were shipped to.
 */
@Entity
@Table(name = "user_addresses")
public class UserAddress {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The customer's own name for it ("Home"). Not personal data on its own. */
    @Column
    private String label;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column
    private String phone;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String line1;

    @Convert(converter = EncryptedStringConverter.class)
    @Column
    private String line2;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String city;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "postal_code", nullable = false)
    private String postalCode;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false)
    private String country;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserAddress() {} // JPA

    public UserAddress(UUID userId) {
        this.userId = userId;
    }

    public void apply(String label, String recipientName, String phone, String line1,
                       String line2, String city, String postalCode, String country) {
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.postalCode = postalCode;
        this.country = country;
        this.updatedAt = Instant.now();
    }

    public void setDefaultAddress(boolean value) {
        this.defaultAddress = value;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getLabel() { return label; }
    public String getRecipientName() { return recipientName; }
    public String getPhone() { return phone; }
    public String getLine1() { return line1; }
    public String getLine2() { return line2; }
    public String getCity() { return city; }
    public String getPostalCode() { return postalCode; }
    public String getCountry() { return country; }
    public boolean isDefaultAddress() { return defaultAddress; }
}
