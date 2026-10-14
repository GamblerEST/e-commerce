package ee.shop.delivery.repository;

import ee.shop.delivery.model.DeliveryOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryOptionRepository extends JpaRepository<DeliveryOption, UUID> {

    Optional<DeliveryOption> findByCode(String code);

    /** What a shopper may choose from, in the order an admin arranged them. */
    List<DeliveryOption> findByActiveTrueOrderBySortOrderAscNameAsc();

    List<DeliveryOption> findAllByOrderBySortOrderAscNameAsc();
}
