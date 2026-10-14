package ee.shop.delivery.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A shipping method the shop offers.
 *
 * `price` is GROSS (VAT-inclusive), matching products.price — the rest of the
 * codebase back-calculates net from gross, and mixing conventions is how
 * shipping VAT ends up quietly wrong on a tax return.
 *
 * An order NEVER reads this row to describe itself; it snapshots the fields
 * it needs at checkout (DECISIONS.md §2). That is what lets an admin reprice
 * or retire a method without rewriting historical invoices.
 */
@Entity
@Table(name = "delivery_options")
public class DeliveryOption {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String carrier;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String currency;

    @Column(name = "lead_time_days_min", nullable = false)
    private int leadTimeDaysMin;

    @Column(name = "lead_time_days_max", nullable = false)
    private int leadTimeDaysMax;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DeliveryOption() {} // JPA

    public DeliveryOption(String code, String name, String carrier, BigDecimal price, String currency,
                           int leadTimeDaysMin, int leadTimeDaysMax, boolean active, int sortOrder) {
        this.code = code;
        this.name = name;
        this.carrier = carrier;
        this.price = price;
        this.currency = currency;
        this.leadTimeDaysMin = leadTimeDaysMin;
        this.leadTimeDaysMax = leadTimeDaysMax;
        this.active = active;
        this.sortOrder = sortOrder;
    }

    public void update(String name, String carrier, BigDecimal price, String currency,
                        int leadTimeDaysMin, int leadTimeDaysMax, boolean active, int sortOrder) {
        // `code` is deliberately not updatable: it is the stable key clients
        // pick by and orders snapshot. Renaming is what `name` is for.
        this.name = name;
        this.carrier = carrier;
        this.price = price;
        this.currency = currency;
        this.leadTimeDaysMin = leadTimeDaysMin;
        this.leadTimeDaysMax = leadTimeDaysMax;
        this.active = active;
        this.sortOrder = sortOrder;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCarrier() { return carrier; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public int getLeadTimeDaysMin() { return leadTimeDaysMin; }
    public int getLeadTimeDaysMax() { return leadTimeDaysMax; }
    public boolean isActive() { return active; }
    public int getSortOrder() { return sortOrder; }
}
