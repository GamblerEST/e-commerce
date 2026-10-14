package ee.shop.order.repository;

import ee.shop.order.model.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderEventRepository extends JpaRepository<OrderEvent, UUID> {
    /** Fold order is chronological — OrderService derives status from the LAST event. */
    List<OrderEvent> findByOrderIdOrderByOccurredAt(UUID orderId);

    /** Batch form for list views — avoids one query per order to derive its status. */
    List<OrderEvent> findByOrderIdInOrderByOccurredAt(List<UUID> orderIds);
}
