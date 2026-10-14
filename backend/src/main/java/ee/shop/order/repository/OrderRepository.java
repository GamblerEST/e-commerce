package ee.shop.order.repository;

import ee.shop.order.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * Unpaged on purpose — status isn't a stored column (DECISIONS.md §1),
     * so a status filter can only be applied AFTER folding each order's
     * events, which means pagination has to happen after filtering too, not
     * at the query level. See OrderService.listOrders. One customer's own
     * order history is small enough that fetching it all is fine — this
     * isn't a deep-paginated public catalog.
     */
    List<Order> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Order> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Every order, for staff screens only — deliberately unscoped, which is
     * why the only caller (AdminOrderService) sits behind role checks. The
     * customer-facing path must never reach this method.
     */
    List<Order> findAllByOrderByCreatedAtDesc();
}
