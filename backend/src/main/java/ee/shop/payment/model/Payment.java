package ee.shop.payment.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Links an order to the Stripe PaymentIntent created for it. Insert-only —
 * see V13__payments.sql's javadoc for why nothing here is ever UPDATEd; the
 * actual outcome (succeeded/failed/refunded) lives on the ORDER as an
 * order_event, not on this row.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "stripe_payment_intent_id", nullable = false)
    private String stripePaymentIntentId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Payment() {} // JPA

    public Payment(UUID orderId, String stripePaymentIntentId, BigDecimal amount, String currency) {
        this.orderId = orderId;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.amount = amount;
        this.currency = currency;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public String getStripePaymentIntentId() { return stripePaymentIntentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
}
