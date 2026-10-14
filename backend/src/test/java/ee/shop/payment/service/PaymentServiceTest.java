package ee.shop.payment.service;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import ee.shop.common.error.AppException;
import ee.shop.config.StripeProperties;
import ee.shop.payment.model.Payment;
import ee.shop.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests — no Spring context, no DB, no network. PaymentService's
 * real job is translating between our domain and Stripe's SDK, so these
 * mock the SDK's static entry points (PaymentIntent/Webhook/Refund) rather
 * than calling the real Stripe API, which no CI or test environment has
 * credentials for. What's actually worth proving here: the BigDecimal ->
 * Stripe-integer-minor-units conversion (the money rules), and that
 * an unverified/misconfigured webhook is refused rather than trusted.
 */
@Tag("unit")
class PaymentServiceTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final StripeProperties enabled = new StripeProperties("sk_test_fake", "whsec_fake");
    private final StripeProperties disabled = new StripeProperties("", "");

    @Test
    void createPaymentIntent_convertsGrossEurosToIntegerCents() {
        PaymentService service = new PaymentService(payments, enabled);
        UUID orderId = UUID.randomUUID();

        try (MockedStatic<PaymentIntent> stripeIntent = mockStatic(PaymentIntent.class)) {
            PaymentIntent created = mock(PaymentIntent.class);
            when(created.getId()).thenReturn("pi_123");
            when(created.getClientSecret()).thenReturn("pi_123_secret_abc");

            stripeIntent.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenAnswer(inv -> {
                        PaymentIntentCreateParams params = inv.getArgument(0);
                        assertThat(params.getAmount()).isEqualTo(12400L); // 124.00 EUR -> 12400 cents
                        assertThat(params.getCurrency()).isEqualTo("eur");
                        return created;
                    });

            String clientSecret = service.createPaymentIntent(orderId, new BigDecimal("124.00"), "EUR");

            assertThat(clientSecret).isEqualTo("pi_123_secret_abc");
            verify(payments).save(org.mockito.ArgumentMatchers.argThat(p ->
                    p.getStripePaymentIntentId().equals("pi_123") && p.getOrderId().equals(orderId)));
        }
    }

    @Test
    void createPaymentIntent_whenStripeNotConfigured_refusesRatherThanSilentlySkipping() {
        PaymentService service = new PaymentService(payments, disabled);

        assertThatThrownBy(() -> service.createPaymentIntent(UUID.randomUUID(), new BigDecimal("10.00"), "EUR"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus().value())
                .isEqualTo(503);
    }

    @Test
    void createPaymentIntent_whenStripeCallFails_wrapsAsGateway502() {
        PaymentService service = new PaymentService(payments, enabled);

        try (MockedStatic<PaymentIntent> stripeIntent = mockStatic(PaymentIntent.class)) {
            stripeIntent.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
                    .thenThrow(mock(StripeException.class));

            assertThatThrownBy(() -> service.createPaymentIntent(UUID.randomUUID(), new BigDecimal("10.00"), "EUR"))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getStatus().value())
                    .isEqualTo(502);
        }
    }

    @Test
    void verifyAndParseWebhook_validSignature_returnsTheEvent() {
        PaymentService service = new PaymentService(payments, enabled);
        Event event = mock(Event.class);

        try (MockedStatic<Webhook> webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent("payload", "sig", "whsec_fake")).thenReturn(event);

            assertThat(service.verifyAndParseWebhook("payload", "sig")).isSameAs(event);
        }
    }

    @Test
    void verifyAndParseWebhook_invalidSignature_isRejected_neverTrustingAnUnverifiedPayload() {
        PaymentService service = new PaymentService(payments, enabled);

        try (MockedStatic<Webhook> webhook = mockStatic(Webhook.class)) {
            webhook.when(() -> Webhook.constructEvent(any(), any(), any()))
                    .thenThrow(mock(SignatureVerificationException.class));

            assertThatThrownBy(() -> service.verifyAndParseWebhook("payload", "forged-sig"))
                    .isInstanceOf(AppException.class)
                    .extracting(e -> ((AppException) e).getStatus().value())
                    .isEqualTo(400);
        }
    }

    @Test
    void refundForOrder_looksUpTheOrdersPaymentIntent_andRefundsExactlyThat() {
        PaymentService service = new PaymentService(payments, enabled);
        UUID orderId = UUID.randomUUID();
        Payment payment = new Payment(orderId, "pi_456", new BigDecimal("50.00"), "EUR");
        when(payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.of(payment));

        try (MockedStatic<Refund> refund = mockStatic(Refund.class)) {
            refund.when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenAnswer(inv -> {
                        RefundCreateParams params = inv.getArgument(0);
                        assertThat(params.getPaymentIntent()).isEqualTo("pi_456");
                        return mock(Refund.class);
                    });

            service.refundForOrder(orderId);

            refund.verify(() -> Refund.create(any(RefundCreateParams.class)));
        }
    }

    @Test
    void refundForOrder_withNoPaymentOnRecord_isConflict_notASilentNoOp() {
        PaymentService service = new PaymentService(payments, enabled);
        UUID orderId = UUID.randomUUID();
        when(payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refundForOrder(orderId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus().value())
                .isEqualTo(409);
    }
}
