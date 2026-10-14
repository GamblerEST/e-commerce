package ee.shop.order.web;

import ee.shop.address.service.AddressLookupService;
import ee.shop.cart.service.CartService;
import ee.shop.cart.service.GuestCartItem;
import ee.shop.cart.web.GuestCartCookieFactory;
import ee.shop.common.error.AppException;
import ee.shop.common.error.FieldValidationException;
import ee.shop.common.security.RateLimitService;
import ee.shop.identity.model.User;
import ee.shop.order.model.OrderEvent;
import ee.shop.order.service.OrderService;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.order.web.dto.OrderResponse;
import ee.shop.payment.banklink.BankLinkPaymentService;
import ee.shop.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Serves both guest and logged-in checkout on the same endpoint, same
 * permitAll + optional-Authentication shape as CartController. Line items
 * always come from the caller's own cart (never the request body — see
 * CheckoutRequest's javadoc); this endpoint's only job with respect to
 * pricing is telling OrderService WHOSE cart to resolve.
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private final OrderService orderService;
    private final CartService cartService;
    private final PaymentService paymentService;
    private final BankLinkPaymentService bankLinks;
    private final RateLimitService rateLimit;
    private final AddressLookupService addressLookup;

    public CheckoutController(OrderService orderService, CartService cartService, PaymentService paymentService,
                               BankLinkPaymentService bankLinks, RateLimitService rateLimit,
                               AddressLookupService addressLookup) {
        this.orderService = orderService;
        this.cartService = cartService;
        this.paymentService = paymentService;
        this.bankLinks = bankLinks;
        this.rateLimit = rateLimit;
        this.addressLookup = addressLookup;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse checkout(Authentication authentication,
                                   @CookieValue(name = GuestCartCookieFactory.COOKIE_NAME, required = false) String guestToken,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                   @Valid @RequestBody CheckoutRequest req,
                                   HttpServletRequest http) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw AppException.badRequest("Idempotency-Key header is required");
        }

        User user = authenticatedUser(authentication);
        UUID userId = user != null ? user.getId() : null;
        rateLimit.enforceCheckout(http.getRemoteAddr(), userId);

        // Before anything is reserved: a rejected address must not hold stock until
        // a reservation expires, and the cart must still be there to try again.
        addressLookup.verifySelected(req.adsOid(), req.addressLine1(), req.postalCode(), req.country(),
                "addressLine1");
        boolean bankLink = requirePayableChoice(req);

        // Idempotent replay: an earlier attempt with this exact key already
        // succeeded (or is in flight — see below) — return that same order
        // rather than trying to check out the (by now likely empty) cart
        // again. Cheap common-case fast path; the UNIQUE constraint on
        // orders.idempotency_key is what actually GUARANTEES this, not this
        // pre-check (see the catch below for the race it can't close).
        OrderResponse response = orderService.findByIdempotencyKey(idempotencyKey).orElse(null);
        boolean freshlyCreated = response == null;

        if (freshlyCreated) {
            try {
                response = orderService.createOrder(userId, idempotencyKey, req, cartLines(userId, guestToken));
            } catch (DataIntegrityViolationException e) {
                // Lost the idempotency race to a concurrent identical
                // request — same "insert, catch conflict, re-fetch" idiom as
                // AuthController.register / CartService.getOrCreateCart.
                response = orderService.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
                freshlyCreated = false; // someone ELSE created it — don't double-create a payment either
            }
        }

        // Only after the order has actually committed — a guest cart lives
        // in Redis, outside Postgres's transaction, so clearing it any
        // earlier could survive a rollback and silently lose the cart.
        clearCart(userId, guestToken);

        // Starting a payment is a real network call to the provider — always
        // AFTER the order transaction has already committed (see
        // PaymentService's javadoc), never inside it. A fresh order gets a
        // brand new payment; a replayed idempotency key gets the one already
        // started for it rather than a second one.
        if (bankLink) {
            return response.withPaymentRedirectUrl(bankLinkPayment(response, freshlyCreated, req.bankCode()));
        }
        if (!freshlyCreated && bankLinks.paidByBankLink(response.id())) {
            // An order is paid one way only; see bankLinkPayment.
            throw AppException.conflict("This order is already being paid through your bank.");
        }
        if (paymentService.isEnabled()) {
            String clientSecret = freshlyCreated
                    ? paymentService.createPaymentIntent(response.id(), response.total(), response.currency())
                    : paymentService.getExistingClientSecret(response.id()).orElse(null);
            response = response.withPaymentClientSecret(clientSecret);
        }
        return response;
    }

    /**
     * Same reasoning as the address check: an order nobody can pay would hold its
     * stock for nothing, so the bank is checked before the order exists.
     *
     * @return whether the customer pays through their bank
     */
    private boolean requirePayableChoice(CheckoutRequest req) {
        if (req.paymentMethod() == CheckoutRequest.PaymentMethod.BANK_LINK) {
            bankLinks.requireSelectableBank(req.bankCode());
            return true;
        }
        if (req.bankCode() != null) {
            throw new FieldValidationException("bankCode", "A bank is only chosen for bank payments.");
        }
        return false;
    }

    /**
     * The customer's payment page at their bank. An order is paid one way only: a
     * card payment may already have gone through without being reported yet, and a
     * second way to pay the same order is how a customer gets charged twice. An
     * order no longer waiting for payment gets no new payment either.
     */
    private String bankLinkPayment(OrderResponse order, boolean freshlyCreated, String bankCode) {
        if (!freshlyCreated && paymentService.hasCardPayment(order.id())) {
            throw AppException.conflict("This order is already being paid by card.");
        }
        if (!OrderEvent.Type.CREATED.name().equals(order.status())) {
            return bankLinks.existingPaymentUrl(order.id());
        }
        return bankLinks.startPayment(order.id(), order.reference(), order.total(), order.currency(), bankCode);
    }

    private List<GuestCartItem> cartLines(UUID userId, String guestToken) {
        if (userId != null) {
            return cartService.userCartLines(userId);
        }
        return guestToken != null ? cartService.guestCartLines(guestToken) : List.of();
    }

    private void clearCart(UUID userId, String guestToken) {
        if (userId != null) {
            cartService.clearUserCart(userId);
        } else if (guestToken != null) {
            cartService.clearGuestCart(guestToken);
        }
    }

    private User authenticatedUser(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }
}
