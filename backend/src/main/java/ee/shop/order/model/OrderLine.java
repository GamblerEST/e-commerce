package ee.shop.order.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A SNAPSHOT — product name, unit price (gross, VAT-inclusive — what the
 * customer actually saw and paid), and VAT rate, all copied at purchase
 * time (DECISIONS.md §2). productId is kept only for "show me this product
 * again"; nothing here is ever re-derived from the live `products` row.
 */
@Entity
@Table(name = "order_lines")
public class OrderLine {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "vat_rate", nullable = false)
    private BigDecimal vatRate;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private String currency;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OrderLine() {} // JPA

    public OrderLine(UUID orderId, UUID productId, String productName, BigDecimal unitPrice,
                      BigDecimal vatRate, int quantity, String currency, BigDecimal lineTotal,
                      UUID reservationId) {
        this.orderId = orderId;
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.vatRate = vatRate;
        this.quantity = quantity;
        this.currency = currency;
        this.lineTotal = lineTotal;
        this.reservationId = reservationId;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getProductId() { return productId; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getVatRate() { return vatRate; }
    public int getQuantity() { return quantity; }
    public String getCurrency() { return currency; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public UUID getReservationId() { return reservationId; }
}
