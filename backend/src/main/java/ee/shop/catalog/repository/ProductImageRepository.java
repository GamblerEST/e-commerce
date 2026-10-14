package ee.shop.catalog.repository;

import ee.shop.catalog.model.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
    List<ProductImage> findByProductIdOrderByDisplayOrder(UUID productId);
    List<ProductImage> findByProductIdInOrderByDisplayOrder(List<UUID> productIds);

    /** Images with no smaller copies yet — seeded rows, and anything that predates V21. */
    List<ProductImage> findByThumbnailKeyIsNullAndMediumKeyIsNull();
}
