package ee.shop.catalog.repository;

import ee.shop.catalog.model.ProductAttribute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductAttributeRepository extends JpaRepository<ProductAttribute, UUID> {
    List<ProductAttribute> findByProductIdOrderByAttributeName(UUID productId);

    void deleteByProductId(UUID productId);
}
