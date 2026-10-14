package ee.shop.inventory.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A hold on `quantity` units of `productId` for the length of a checkout
 * attempt. Active while both releasedAt and soldAt are null; resolves
 * EXACTLY one of two ways — released (expired or cancelled, stock goes back
 * to available) or sold (payment succeeded, stock is gone for good) — and
 * ReservationRepository's atomic claim queries guarantee only one of those
 * can ever win for a given reservation. See V10__inventory.sql's javadoc.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "sold_at")
    private Instant soldAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Reservation() {} // JPA

    public Reservation(UUID productId, int quantity, Instant expiresAt) {
        this.productId = productId;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public boolean isActive() { return releasedAt == null && soldAt == null; }
}
