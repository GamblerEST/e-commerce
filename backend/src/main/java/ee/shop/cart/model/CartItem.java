package ee.shop.cart.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately just (product_id, quantity) — no name/price/currency copied
 * here. Unlike an order line (DECISIONS.md §2), a cart line is NOT a
 * financial record and must always reflect the CURRENT catalog price;
 * CartService resolves name/price/thumbnail from the live Product row on
 * every read.
 */
@Entity
@Table(name = "cart_items")
public class CartItem {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CartItem() {} // JPA

    public CartItem(UUID cartId, UUID productId, int quantity) {
        this.cartId = cartId;
        this.productId = productId;
        this.quantity = quantity;
    }

    public UUID getId() { return id; }
    public UUID getProductId() { return productId; }
    public int getQuantity() { return quantity; }
}
