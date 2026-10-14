package ee.shop.inventory;

import ee.shop.inventory.repository.InventoryRepository;
import ee.shop.inventory.repository.ReservationRepository;
import ee.shop.inventory.service.InventoryService;
import ee.shop.support.IntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DECISIONS.md §3: the mechanism under test isn't atomicity (a plain
 * `on_hand -= 1` is already atomic — Postgres row-locks it) but DURATION —
 * reserved stock must be held for the checkout window and given back if
 * payment never lands, without ever letting two concurrent checkouts both
 * claim the last unit. Overselling is the failure this exists to catch, so
 * the concurrency test
 * specifically: N threads, exactly one winner.
 */
@Tag("api")
@Tag("flow")
class InventoryServiceTest extends IntegrationTest {

    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcTemplate jdbc;

    private UUID insertProductWithStock(int onHand) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price) VALUES (?, ?, ?, ?)",
                id, "Regression Product", "regression-" + id, new BigDecimal("10.00"));
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)", id, onHand);
        return id;
    }

    @Test
    void reserve_claimsAvailableStock() {
        UUID productId = insertProductWithStock(5);

        Optional<UUID> reservationId = inventoryService.reserve(productId, 3);

        assertThat(reservationId).isPresent();
        var inv = inventoryRepository.findById(productId).orElseThrow();
        assertThat(inv.getOnHand()).isEqualTo(5);
        assertThat(inv.getReserved()).isEqualTo(3);
        assertThat(inv.getAvailable()).isEqualTo(2);
    }

    @Test
    void reserve_moreThanAvailable_fails_andCreatesNoReservationRow() {
        UUID productId = insertProductWithStock(2);

        Optional<UUID> reservationId = inventoryService.reserve(productId, 3);

        assertThat(reservationId).isEmpty();
        var inv = inventoryRepository.findById(productId).orElseThrow();
        assertThat(inv.getReserved()).isEqualTo(0); // untouched — nothing was actually claimed
        assertThat(reservationRepository.findAll().stream().filter(r -> r.getProductId().equals(productId)))
                .isEmpty();
    }

    @Test
    void release_givesReservedStockBack_withoutTouchingOnHand() {
        UUID productId = insertProductWithStock(5);
        UUID reservationId = inventoryService.reserve(productId, 3).orElseThrow();

        boolean released = inventoryService.release(reservationId);

        assertThat(released).isTrue();
        var inv = inventoryRepository.findById(productId).orElseThrow();
        assertThat(inv.getOnHand()).isEqualTo(5);
        assertThat(inv.getReserved()).isEqualTo(0);
    }

    @Test
    void convertToSold_permanentlyDecrementsOnHand() {
        UUID productId = insertProductWithStock(5);
        UUID reservationId = inventoryService.reserve(productId, 3).orElseThrow();

        boolean sold = inventoryService.convertToSold(reservationId);

        assertThat(sold).isTrue();
        var inv = inventoryRepository.findById(productId).orElseThrow();
        assertThat(inv.getOnHand()).isEqualTo(2);
        assertThat(inv.getReserved()).isEqualTo(0);
    }

    @Test
    void aReservation_canOnlyBeResolvedOnce() {
        UUID productId = insertProductWithStock(5);
        UUID reservationId = inventoryService.reserve(productId, 3).orElseThrow();

        assertThat(inventoryService.release(reservationId)).isTrue();
        // Already released — a second release, or a late-arriving sale, must not double-apply.
        assertThat(inventoryService.release(reservationId)).isFalse();
        assertThat(inventoryService.convertToSold(reservationId)).isFalse();

        var inv = inventoryRepository.findById(productId).orElseThrow();
        assertThat(inv.getReserved()).isEqualTo(0); // not -3 from a double release
    }

    @Test
    void expiredReservation_isReleasedByTheScheduledSweep() {
        UUID productId = insertProductWithStock(5);
        UUID reservationId = inventoryService.reserve(productId, 3).orElseThrow();
        // Backdate the reservation directly — simulates "checkout started 20
        // minutes ago and nobody ever paid", without waiting out a real TTL.
        jdbc.update("UPDATE reservations SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), reservationId);

        inventoryService.releaseExpiredReservations();

        var inv = inventoryRepository.findById(productId).orElseThrow();
        assertThat(inv.getReserved()).isEqualTo(0);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().isActive()).isFalse();
    }

    @Test
    void sweep_neverTouchesAReservationThatHasAlreadyBeenSold() {
        UUID productId = insertProductWithStock(5);
        UUID reservationId = inventoryService.reserve(productId, 3).orElseThrow();
        inventoryService.convertToSold(reservationId);
        jdbc.update("UPDATE reservations SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), reservationId);

        inventoryService.releaseExpiredReservations();

        // Still sold, on_hand still permanently reduced — the sweep must not
        // have "released" a reservation that already converted to a sale.
        var inv = inventoryRepository.findById(productId).orElseThrow();
        assertThat(inv.getOnHand()).isEqualTo(2);
        assertThat(inv.getReserved()).isEqualTo(0);
    }

    /**
     * The race a naive SELECT-then-UPDATE reservation would lose: twenty
     * threads all try to reserve the LAST unit of the same product at the
     * same instant. Exactly one may succeed — anything else is an oversell.
     */
    @Test
    void concurrentReserve_forTheLastUnit_letsExactlyOneWinnerThrough() throws Exception {
        UUID productId = insertProductWithStock(1);

        int threads = 20;
        var pool = Executors.newFixedThreadPool(threads);
        var startLine = new CountDownLatch(1);
        var successes = new AtomicInteger();
        var failures = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startLine.await();
                    Optional<UUID> result = inventoryService.reserve(productId, 1);
                    if (result.isPresent()) {
                        successes.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }

        startLine.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(successes.get())
                .as("exactly one thread may reserve the last unit")
                .isEqualTo(1);
        assertThat(failures.get()).isEqualTo(threads - 1);

        var inv = inventoryRepository.findById(productId).orElseThrow();
        assertThat(inv.getOnHand()).isEqualTo(1); // never went negative
        assertThat(inv.getReserved()).isEqualTo(1);
    }
}
