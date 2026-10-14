package ee.shop.inventory.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per product. Available-to-sell stock is always (on_hand -
 * reserved), never on_hand alone — see V10__inventory.sql's header for why
 * a plain counter oversells during the checkout window. Both mutating
 * fields are only ever touched via InventoryRepository's atomic UPDATEs
 * (reserve/release/sell); nothing here calls setters and persists them
 * through JPA's normal dirty-checking, which would reintroduce the exact
 * check-then-act race those queries exist to avoid.
 */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Inventory() {} // JPA

    public UUID getProductId() { return productId; }
    public int getOnHand() { return onHand; }
    public int getReserved() { return reserved; }
    public int getAvailable() { return onHand - reserved; }
}
