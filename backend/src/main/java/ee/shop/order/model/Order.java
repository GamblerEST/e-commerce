package ee.shop.order.model;

import ee.shop.common.security.EncryptedStringConverter;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable once created — DECISIONS.md §1. No setters beyond the
 * constructor, no status column (status is derived by folding order_events,
 * see OrderService), no @Column(updatable=...) needed because nothing here
 * is ever meant to change.
 *
 * Billing fields are a SNAPSHOT, not a join to `users` — DECISIONS.md §2/§5.
 * userId is nullable (guest checkout) and is a link for "show me my
 * orders", never consulted when pricing or re-displaying a historical
 * order.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "billing_name", nullable = false)
    private String billingName;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "billing_email", nullable = false)
    private String billingEmail;
    // Nullable at the DB level (V14__order_billing_phone.sql) — orders
    // created before this column existed have none. Required for NEW
    // orders via CheckoutRequest's @NotBlank, not a DB constraint.
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "billing_phone")
    private String billingPhone;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "billing_address_line1", nullable = false)
    private String billingAddressLine1;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "billing_address_line2")
    private String billingAddressLine2;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "billing_city", nullable = false)
    private String billingCity;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "billing_postal_code", nullable = false)
    private String billingPostalCode;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "billing_country", nullable = false)
    private String billingCountry;

    @Column(nullable = false)
    private String currency;
    @Column(nullable = false)
    private BigDecimal subtotal;
    @Column(name = "vat_total", nullable = false)
    private BigDecimal vatTotal;
    @Column(nullable = false)
    private BigDecimal total;

    // Delivery method SNAPSHOT — see DeliverySnapshot. Nullable because
    // orders predating delivery options genuinely have none, and because a
    // shop may sell something that isn't shipped at all.
    @Column(name = "delivery_code", updatable = false)
    private String deliveryCode;
    @Column(name = "delivery_name", updatable = false)
    private String deliveryName;
    @Column(name = "delivery_price", updatable = false)
    private BigDecimal deliveryPrice;
    @Column(name = "delivery_lead_days_min", updatable = false)
    private Integer deliveryLeadDaysMin;
    @Column(name = "delivery_lead_days_max", updatable = false)
    private Integer deliveryLeadDaysMax;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Order() {} // JPA

    public Order(UUID userId, String idempotencyKey, String billingName, String billingEmail,
                 String billingPhone, String billingAddressLine1, String billingAddressLine2, String billingCity,
                 String billingPostalCode, String billingCountry, String currency,
                 BigDecimal subtotal, BigDecimal vatTotal, BigDecimal total,
                 DeliverySnapshot delivery) {
        if (delivery != null) {
            this.deliveryCode = delivery.code();
            this.deliveryName = delivery.name();
            this.deliveryPrice = delivery.price();
            this.deliveryLeadDaysMin = delivery.leadDaysMin();
            this.deliveryLeadDaysMax = delivery.leadDaysMax();
        }
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.billingName = billingName;
        this.billingEmail = billingEmail;
        this.billingPhone = billingPhone;
        this.billingAddressLine1 = billingAddressLine1;
        this.billingAddressLine2 = billingAddressLine2;
        this.billingCity = billingCity;
        this.billingPostalCode = billingPostalCode;
        this.billingCountry = billingCountry;
        this.currency = currency;
        this.subtotal = subtotal;
        this.vatTotal = vatTotal;
        this.total = total;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getBillingName() { return billingName; }
    public String getBillingEmail() { return billingEmail; }
    public String getBillingPhone() { return billingPhone; }
    public String getBillingAddressLine1() { return billingAddressLine1; }
    public String getBillingAddressLine2() { return billingAddressLine2; }
    public String getBillingCity() { return billingCity; }
    public String getBillingPostalCode() { return billingPostalCode; }
    public String getBillingCountry() { return billingCountry; }
    public String getCurrency() { return currency; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getVatTotal() { return vatTotal; }
    public BigDecimal getTotal() { return total; }
    public String getDeliveryCode() { return deliveryCode; }
    public String getDeliveryName() { return deliveryName; }
    public BigDecimal getDeliveryPrice() { return deliveryPrice; }
    public Integer getDeliveryLeadDaysMin() { return deliveryLeadDaysMin; }
    public Integer getDeliveryLeadDaysMax() { return deliveryLeadDaysMax; }
    public Instant getCreatedAt() { return createdAt; }
}
