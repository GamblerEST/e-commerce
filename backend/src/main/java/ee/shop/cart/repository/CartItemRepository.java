package ee.shop.cart.repository;

import ee.shop.cart.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCartIdOrderByCreatedAt(UUID cartId);

    Optional<CartItem> findByCartIdAndProductId(UUID cartId, UUID productId);

    void deleteByCartId(UUID cartId);

    long deleteByCartIdAndProductId(UUID cartId, UUID productId);

    /**
     * Single atomic UPSERT rather than a SELECT-then-branch-into-insert-or-
     * update — the same "no check-then-act" principle inventory follows
     * (DECISIONS.md §3): two concurrent "add to cart" clicks for
     * the same product must both land, not race and silently drop one.
     * Quantity is clamped server-side (LEAST) so a client can't stack an
     * unbounded quantity through repeated adds.
     */
    @Modifying
    @Query(value = """
            INSERT INTO cart_items (id, cart_id, product_id, quantity)
            VALUES (gen_random_uuid(), :cartId, :productId, :quantity)
            ON CONFLICT (cart_id, product_id)
            DO UPDATE SET quantity = LEAST(cart_items.quantity + EXCLUDED.quantity, :maxQuantity),
                          updated_at = now()
            """, nativeQuery = true)
    void upsertAdd(@Param("cartId") UUID cartId, @Param("productId") UUID productId,
                    @Param("quantity") int quantity, @Param("maxQuantity") int maxQuantity);

    /** Sets an absolute quantity. Returns 0 (no-op for the caller) if the item isn't in the cart. */
    @Modifying
    @Query("UPDATE CartItem c SET c.quantity = :quantity, c.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE c.cartId = :cartId AND c.productId = :productId")
    int setQuantity(@Param("cartId") UUID cartId, @Param("productId") UUID productId,
                     @Param("quantity") int quantity);
}
