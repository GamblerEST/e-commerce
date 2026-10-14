package ee.shop.order.repository;

import ee.shop.order.model.BillingDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BillingDocumentRepository extends JpaRepository<BillingDocument, UUID> {
    List<BillingDocument> findByOrderIdOrderByGeneratedAt(UUID orderId);
}
