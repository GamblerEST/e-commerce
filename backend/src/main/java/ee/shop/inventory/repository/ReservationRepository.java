package ee.shop.inventory.repository;

import ee.shop.inventory.model.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * Atomic claim, same shape as RefreshTokenRepository.claim: a single
     * UPDATE guarded by "still active" rather than read-then-branch, so the
     * scheduled expiry sweep and a real checkout (release vs. sell) can
     * never both resolve the same reservation.
     */
    @Modifying
    @Query(value = """
            UPDATE reservations
               SET released_at = now()
             WHERE id = :id
               AND released_at IS NULL
               AND sold_at IS NULL
            """, nativeQuery = true)
    int claimRelease(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            UPDATE reservations
               SET sold_at = now()
             WHERE id = :id
               AND released_at IS NULL
               AND sold_at IS NULL
            """, nativeQuery = true)
    int claimSold(@Param("id") UUID id);

    List<Reservation> findByExpiresAtBeforeAndReleasedAtIsNullAndSoldAtIsNull(Instant cutoff);
}
