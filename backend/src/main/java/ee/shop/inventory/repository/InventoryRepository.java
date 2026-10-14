package ee.shop.inventory.repository;

import ee.shop.inventory.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    /**
     * Every product needs a row here or it is silently unsellable: the cart's
     * availability check reads zero and checkout's atomic reserve() matches
     * no row. V10__inventory.sql backfilled the products that existed then,
     * but nothing created one for a product added later — so admin product
     * creation calls this.
     *
     * ON CONFLICT DO NOTHING makes it safe to call for a product that
     * already has stock (a restore, a retried create) without clobbering the
     * real counts with zeroes.
     */
    @Modifying
    @Query(value = """
            INSERT INTO inventory (product_id, on_hand, reserved)
            VALUES (:productId, :onHand, 0)
            ON CONFLICT (product_id) DO NOTHING
            """, nativeQuery = true)
    int createIfAbsent(@Param("productId") UUID productId, @Param("onHand") int onHand);

    /**
     * Advisory read for the cart — NOT a reservation. Lets "add to cart"
     * warn about stock without holding it (only checkout's reserve() does
     * that); a concurrent buyer can still take the stock between this read
     * and checkout, which is fine — checkout's atomic reserve() is what
     * actually prevents overselling, this is purely a better error message.
     */
    @Query(value = "SELECT on_hand - reserved FROM inventory WHERE product_id = :productId", nativeQuery = true)
    Optional<Integer> availableQuantity(@Param("productId") UUID productId);

    /**
     * Atomically claim `quantity` units of available stock. A plain
     * `on_hand -= quantity` would already be race-free (Postgres row-locks
     * it) — the guard here isn't about the race, it's about correctness:
     * this must never succeed past what's actually available (on_hand -
     * reserved), so the WHERE clause and the write happen in the SAME
     * statement rather than a read-then-decide-then-write sequence that a
     * concurrent caller could interleave with.
     *
     * Returns 1 = reserved, this caller may proceed to create a Reservation
     * row. Returns 0 = not enough available stock right now (could be
     * genuinely out of stock, or every unit is currently reserved by other
     * in-progress checkouts) — the caller must NOT create a reservation row
     * in that case, or it would represent stock that was never actually
     * claimed.
     */
    @Modifying
    @Query(value = """
            UPDATE inventory
               SET reserved = reserved + :quantity
             WHERE product_id = :productId
               AND on_hand - reserved >= :quantity
            """, nativeQuery = true)
    int reserve(@Param("productId") UUID productId, @Param("quantity") int quantity);

    /** Gives reserved stock back without touching on_hand — expiry or cancel. */
    @Modifying
    @Query(value = """
            UPDATE inventory
               SET reserved = reserved - :quantity
             WHERE product_id = :productId
               AND reserved >= :quantity
            """, nativeQuery = true)
    int release(@Param("productId") UUID productId, @Param("quantity") int quantity);

    /** Payment succeeded: the reservation becomes a real, permanent decrement. */
    @Modifying
    @Query(value = """
            UPDATE inventory
               SET on_hand = on_hand - :quantity,
                   reserved = reserved - :quantity
             WHERE product_id = :productId
               AND reserved >= :quantity
               AND on_hand >= :quantity
            """, nativeQuery = true)
    int sell(@Param("productId") UUID productId, @Param("quantity") int quantity);
}
