package ee.shop.payment.repository;

import ee.shop.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByStripePaymentIntentId(String stripePaymentIntentId);

    /** An order's most recent PaymentIntent — checkout only ever creates one per order today. */
    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
