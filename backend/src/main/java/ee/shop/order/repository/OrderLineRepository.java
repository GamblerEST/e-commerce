package ee.shop.order.repository;

import ee.shop.order.model.OrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {
    List<OrderLine> findByOrderIdOrderByCreatedAt(UUID orderId);

    /** Batch form for list views — item counts for a whole page of orders in one query. */
    List<OrderLine> findByOrderIdIn(List<UUID> orderIds);
}
