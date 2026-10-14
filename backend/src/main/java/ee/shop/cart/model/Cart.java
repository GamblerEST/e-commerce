package ee.shop.cart.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One cart per registered user (unique on user_id — see V9__cart.sql).
 * Guest carts never reach this table; they live in Redis until merged here
 * on login (see CartService.mergeGuestIntoUser).
 */
@Entity
@Table(name = "carts")
public class Cart {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Cart() {} // JPA

    public Cart(UUID userId) {
        this.userId = userId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
}
