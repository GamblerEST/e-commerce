package ee.shop.order.web;

import ee.shop.address.service.AddressLookupService;
import ee.shop.cart.service.CartService;
import ee.shop.cart.service.GuestCartItem;
import ee.shop.cart.web.GuestCartCookieFactory;
import ee.shop.common.error.AppException;
import ee.shop.common.security.RateLimitService;
import ee.shop.identity.model.User;
import ee.shop.order.service.OrderService;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.order.web.dto.OrderResponse;
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
    private final RateLimitService rateLimit;
    private final AddressLookupService addressLookup;

    public CheckoutController(OrderService orderService, CartService cartService, PaymentService paymentService,
                               RateLimitService rateLimit, AddressLookupService addressLookup) {
        this.orderService = orderService;
        this.cartService = cartService;
        this.paymentService = paymentService;
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

        // Idempotent replay: an earlier attempt with this exact key already
        // succeeded (or is in flight — see below) — return that same order
        // rather than trying to check out the (by now likely empty) cart
        // again. Cheap common-case fast path; the UNIQUE constraint on
        // orders.idempotency_key is what actually GUARANTEES this, not this
        // pre-check (see the catch below for the race it can't close).
        OrderResponse response = orderService.findByIdempotencyKey(idempotencyKey).orElse(null);
        boolean freshlyCreated = response == null;

        if (freshlyCreated) {
            List<GuestCartItem> lines;
            if (user != null) {
                lines = cartService.userCartLines(userId);
            } else if (guestToken != null) {
                lines = cartService.guestCartLines(guestToken);
            } else {
                lines = List.of();
            }

            try {
                response = orderService.createOrder(userId, idempotencyKey, req, lines);
            } catch (DataIntegrityViolationException e) {
                // Lost the idempotency race to a concurrent identical
                // request — same "insert, catch conflict, re-fetch" idiom as
                // AuthController.register / CartService.getOrCreateCart.
                response = orderService.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
                freshlyCreated = false; // someone ELSE created it — don't double-create a PaymentIntent either
            }
        }

        // Only after the order has actually committed — a guest cart lives
        // in Redis, outside Postgres's transaction, so clearing it any
        // earlier could survive a rollback and silently lose the cart.
        if (user != null) {
            cartService.clearUserCart(userId);
        } else if (guestToken != null) {
            cartService.clearGuestCart(guestToken);
        }

        // PaymentIntent creation is a real network call to Stripe — always
        // AFTER the order transaction has already committed (see
        // PaymentService's javadoc), never inside it. A fresh order gets a
        // brand new PaymentIntent; a replayed idempotency key re-fetches the
        // one already created for it rather than minting a second one.
        if (paymentService.isEnabled()) {
            String clientSecret = freshlyCreated
                    ? paymentService.createPaymentIntent(response.id(), response.total(), response.currency())
                    : paymentService.getExistingClientSecret(response.id()).orElse(null);
            response = response.withPaymentClientSecret(clientSecret);
        }

        return response;
    }

    private User authenticatedUser(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }
}
