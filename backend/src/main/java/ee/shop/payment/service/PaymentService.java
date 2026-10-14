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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

/**
 * Talks to Stripe. Card data never reaches this class (or this server at
 * all) — Stripe.js/Elements on the frontend collects it directly into
 * Stripe's own iframe and confirms the PaymentIntent client-side; this
 * service only ever sees amounts, currencies, and Stripe's own opaque ids
 * (PCI scope stays off us).
 *
 * Deliberately called from OUTSIDE any @Transactional method (see
 * CheckoutController) — an external HTTP round-trip to Stripe must never
 * run inside an open DB transaction, same reasoning as MailService.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final StripeProperties props;

    public PaymentService(PaymentRepository payments, StripeProperties props) {
        this.payments = payments;
        this.props = props;
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /**
     * Creates a Stripe PaymentIntent for an already-committed order and
     * records the link. amount is euros with 2 decimal places (this
     * project's money convention throughout); Stripe wants
     * integer minor units (cents), so the conversion uses
     * RoundingMode.UNNECESSARY — if that ever throws, something upstream
     * produced a non-2dp amount, which is itself a bug worth surfacing
     * loudly rather than silently rounding real money.
     */
    public String createPaymentIntent(UUID orderId, BigDecimal amount, String currency) {
        if (!isEnabled()) {
            throw new AppException("Payments are not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }

        long minorUnits = amount.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(minorUnits)
                .setCurrency(currency.toLowerCase())
                .putMetadata("order_id", orderId.toString())
                // allow_redirects=NEVER restricts automatic payment methods to
                // ones that confirm in-page (card, and any other non-redirect
                // method) — the frontend calls confirmPayment() and gets a
                // result back directly, with no return_url / page-navigation
                // dance to build for a redirect-based method (iDEAL etc.).
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .build();

        PaymentIntent intent;
        try {
            intent = PaymentIntent.create(params);
        } catch (StripeException e) {
            log.error("Stripe PaymentIntent creation failed for order {}: {}", orderId, e.getMessage());
            throw new AppException(
                    "Payment setup failed — your order was created but isn't ready for payment yet. "
                            + "Check My Orders and try again shortly.",
                    HttpStatus.BAD_GATEWAY);
        }

        payments.save(new Payment(orderId, intent.getId(), amount, currency));
        return intent.getClientSecret();
    }

    /** Which order a Stripe PaymentIntent id belongs to — never trust a client-supplied order id for this. */
    public Optional<UUID> findOrderIdForPaymentIntent(String stripePaymentIntentId) {
        return payments.findByStripePaymentIntentId(stripePaymentIntentId).map(Payment::getOrderId);
    }

    /**
     * For an idempotent checkout REPLAY (same Idempotency-Key resubmitted):
     * the order already exists, so this must NOT create a second
     * PaymentIntent — it re-fetches the client_secret of the one already
     * created for it. client_secret itself is never stored (only the
     * PaymentIntent id is), so this means one more Stripe call, not a
     * cached value that could go stale.
     */
    public Optional<String> getExistingClientSecret(UUID orderId) {
        return payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId).map(p -> {
            try {
                return PaymentIntent.retrieve(p.getStripePaymentIntentId()).getClientSecret();
            } catch (StripeException e) {
                // Fail, rather than returning empty.
                //
                // An empty Optional here is indistinguishable from "this order
                // has no payment at all", and the caller turns that into a null
                // client_secret — which the checkout page reads as "Stripe is
                // not configured, so there is nothing to pay" and shows the
                // order as complete. A transient Stripe error would therefore
                // tell a customer their unpaid order was done.
                //
                // A 503 is the honest answer: the order exists, the request was
                // fine, and retrying with the same idempotency key returns the
                // same order rather than creating another.
                log.error("Could not re-fetch PaymentIntent {} for order {}: {}",
                        p.getStripePaymentIntentId(), orderId, e.getMessage());
                throw AppException.unavailable(
                        "Your order was created, but we could not reach the payment provider. "
                                + "Open it from My Orders to pay.");
            }
        });
    }

    /**
     * Verifies the Stripe-Signature header against OUR configured webhook
     * secret before trusting anything in the payload — an unverified
     * webhook body is just an attacker POSTing "payment_intent.succeeded"
     * for an order they never paid for.
     */
    public Event verifyAndParseWebhook(String payload, String signatureHeader) {
        if (!isEnabled() || props.webhookSecret() == null || props.webhookSecret().isBlank()) {
            throw new AppException("Webhook processing is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            return Webhook.constructEvent(payload, signatureHeader, props.webhookSecret());
        } catch (SignatureVerificationException e) {
            throw AppException.badRequest("Invalid webhook signature");
        }
    }

    /**
     * Refunds the PaymentIntent on record for an order — OrderService calls
     * this rather than reaching into PaymentRepository itself, so the link
     * between "an order" and "its Stripe PaymentIntent id" stays inside this
     * service instead of leaking into order/ code.
     */
    public void refundForOrder(UUID orderId) {
        String stripePaymentIntentId = payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId)
                .map(Payment::getStripePaymentIntentId)
                .orElseThrow(() -> AppException.conflict("No payment found for this order"));
        createRefund(stripePaymentIntentId);
    }

    /** Full refund — cancelling an order that already moved past CREATED needs this, not just an inventory release. */
    public void createRefund(String stripePaymentIntentId) {
        try {
            Refund.create(RefundCreateParams.builder()
                    .setPaymentIntent(stripePaymentIntentId)
                    .build());
        } catch (StripeException e) {
            log.error("Stripe refund failed for PaymentIntent {}: {}", stripePaymentIntentId, e.getMessage());
            throw new AppException("Refund failed — please try again or contact support",
                    HttpStatus.BAD_GATEWAY);
        }
    }
}
