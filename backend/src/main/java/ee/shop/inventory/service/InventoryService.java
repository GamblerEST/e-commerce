package ee.shop.inventory.service;

import ee.shop.config.InventoryProperties;
import ee.shop.inventory.model.Reservation;
import ee.shop.inventory.repository.InventoryRepository;
import ee.shop.inventory.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The checkout-safe half of DECISIONS.md §3. Checkout reserves
 * stock when it starts and either converts the reservation to a sale (
 * payment succeeded) or lets it expire/cancels it (payment failed,
 * abandoned, or never attempted) — it never touches `inventory` directly.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventory;
    private final ReservationRepository reservations;
    private final InventoryProperties props;

    public InventoryService(InventoryRepository inventory, ReservationRepository reservations,
                             InventoryProperties props) {
        this.inventory = inventory;
        this.reservations = reservations;
        this.props = props;
    }

    /** Advisory stock check for the cart (not a hold) — see InventoryRepository.availableQuantity's javadoc. */
    @Transactional(readOnly = true)
    public int availableQuantity(UUID productId) {
        return inventory.availableQuantity(productId).orElse(0);
    }

    /**
     * Attempts to hold `quantity` units for the configured reservation TTL.
     *
     * Ordering matters: the atomic inventory claim happens FIRST, and a
     * Reservation row is only ever created after it succeeds. Creating the
     * row first (or unconditionally) would let two concurrent callers both
     * write a reservation for stock that only one of them actually holds —
     * the row must never represent a claim that didn't really succeed.
     *
     * Empty return = not enough available stock right now. The caller
     * (future checkout flow) shows "out of stock" / "only N left" — it must
     * NOT retry in a loop, which would just be a slower version of the same
     * check-then-act race this whole mechanism exists to avoid.
     */
    @Transactional
    public Optional<UUID> reserve(UUID productId, int quantity) {
        int claimed = inventory.reserve(productId, quantity);
        if (claimed == 0) {
            return Optional.empty();
        }
        Reservation r = reservations.save(
                new Reservation(productId, quantity, Instant.now().plus(props.reservationTtl())));
        return Optional.of(r.getId());
    }

    /**
     * Gives the reservation's stock back without any sale — cancelled
     * checkout, declined payment, or (via the scheduled sweep below)
     * silent abandonment. Idempotent: calling this twice, or racing it
     * against convertToSold for the same reservation, only ever lets one
     * caller through (ReservationRepository.claimRelease's atomic UPDATE).
     */
    @Transactional
    public boolean release(UUID reservationId) {
        Reservation r = reservations.findById(reservationId).orElse(null);
        if (r == null) {
            return false;
        }
        if (reservations.claimRelease(reservationId) == 0) {
            return false; // already resolved (released or sold) by someone else
        }
        inventory.release(r.getProductId(), r.getQuantity());
        return true;
    }

    /** Payment succeeded: the hold becomes a permanent decrement of on_hand. */
    @Transactional
    public boolean convertToSold(UUID reservationId) {
        Reservation r = reservations.findById(reservationId).orElse(null);
        if (r == null) {
            return false;
        }
        if (reservations.claimSold(reservationId) == 0) {
            return false;
        }
        inventory.sell(r.getProductId(), r.getQuantity());
        return true;
    }

    /**
     * An abandoned checkout must not lock stock forever (DECISIONS.md §3) —
     * this is what actually enforces the TTL. Reuses release()'s atomic
     * claim, so a reservation that gets paid for in the same instant this
     * sweep looks at it is simply skipped (claimRelease returns 0) rather
     * than incorrectly released out from under a successful payment.
     *
     * @Transactional here (not just on release()) because the call below is
     * a same-class self-invocation, which bypasses Spring's transactional
     * proxy entirely — without an active transaction on this method too,
     * release()'s @Modifying query has nothing to run inside.
     */
    @Scheduled(fixedDelayString = "${app.inventory.sweep-interval}")
    @Transactional
    public void releaseExpiredReservations() {
        List<Reservation> expired =
                reservations.findByExpiresAtBeforeAndReleasedAtIsNullAndSoldAtIsNull(Instant.now());
        for (Reservation r : expired) {
            if (release(r.getId())) {
                log.info("Released expired reservation {} ({} x {})", r.getId(), r.getQuantity(), r.getProductId());
            }
        }
    }
}
