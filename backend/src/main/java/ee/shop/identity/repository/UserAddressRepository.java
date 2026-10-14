package ee.shop.identity.repository;

import ee.shop.identity.model.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAddressRepository extends JpaRepository<UserAddress, UUID> {

    List<UserAddress> findByUserIdOrderByDefaultAddressDescCreatedAtDesc(UUID userId);

    /**
     * Scoped to the owner in the QUERY, not checked afterwards. An address is
     * looked up by id AND user id together, so there is no code path where a
     * caller can load somebody else's row and then forget to verify it.
     */
    Optional<UserAddress> findByIdAndUserId(UUID id, UUID userId);

    Optional<UserAddress> findByUserIdAndDefaultAddressIsTrue(UUID userId);

    long countByUserId(UUID userId);
}
