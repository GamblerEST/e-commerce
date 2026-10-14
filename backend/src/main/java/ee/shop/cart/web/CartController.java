package ee.shop.cart.web;

import ee.shop.cart.service.CartService;
import ee.shop.cart.web.dto.AddCartItemRequest;
import ee.shop.cart.web.dto.CartResponse;
import ee.shop.cart.web.dto.SetCartItemQuantityRequest;
import ee.shop.common.error.AppException;
import ee.shop.common.security.RateLimitService;
import ee.shop.identity.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Serves BOTH guest and logged-in shoppers on the same endpoints —
 * permitAll in SecurityConfig, with JwtAuthFilter still populating
 * Authentication when a Bearer token IS present, so callers here can branch
 * on "is this actually a logged-in user" without a second auth mechanism.
 *
 * Every mutation requires the CSRF header (SecurityConfig's csrfRequiredMatcher)
 * because the guest path's identity comes from an ambient cookie
 * (guest_cart_id) — DECISIONS.md §11's rule applies here exactly as it does
 * to /auth/refresh.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private static final CartResponse EMPTY_CART = new CartResponse(List.of(), 0, BigDecimal.ZERO, "EUR");

    private final CartService cartService;
    private final GuestCartCookieFactory guestCookies;
    private final RateLimitService rateLimit;

    public CartController(CartService cartService, GuestCartCookieFactory guestCookies, RateLimitService rateLimit) {
        this.cartService = cartService;
        this.guestCookies = guestCookies;
        this.rateLimit = rateLimit;
    }

    @GetMapping
    public CartResponse getCart(Authentication authentication,
                                 @CookieValue(name = GuestCartCookieFactory.COOKIE_NAME, required = false) String guestToken) {
        User user = authenticatedUser(authentication);
        if (user != null) {
            return cartService.getUserCart(user.getId());
        }
        return guestToken == null ? EMPTY_CART : cartService.getGuestCart(guestToken);
    }

    @PostMapping("/items")
    public CartResponse addItem(Authentication authentication,
                                 @CookieValue(name = GuestCartCookieFactory.COOKIE_NAME, required = false) String guestToken,
                                 @Valid @RequestBody AddCartItemRequest req,
                                 HttpServletResponse response, HttpServletRequest http) {
        User user = authenticatedUser(authentication);
        rateLimit.enforceCartMutation(http.getRemoteAddr(), user != null ? user.getId() : null);
        if (user != null) {
            return cartService.addUserItem(user.getId(), req.productId(), req.quantity());
        }
        String token = ensureGuestToken(guestToken, response);
        return cartService.addGuestItem(token, req.productId(), req.quantity());
    }

    @PatchMapping("/items/{productId}")
    public CartResponse setQuantity(Authentication authentication,
                                     @CookieValue(name = GuestCartCookieFactory.COOKIE_NAME, required = false) String guestToken,
                                     @PathVariable UUID productId,
                                     @Valid @RequestBody SetCartItemQuantityRequest req,
                                     HttpServletRequest http) {
        User user = authenticatedUser(authentication);
        rateLimit.enforceCartMutation(http.getRemoteAddr(), user != null ? user.getId() : null);
        if (user != null) {
            return cartService.setUserItemQuantity(user.getId(), productId, req.quantity());
        }
        if (guestToken == null) {
            throw AppException.notFound("Cart item not found");
        }
        return cartService.setGuestItemQuantity(guestToken, productId, req.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(Authentication authentication,
                                    @CookieValue(name = GuestCartCookieFactory.COOKIE_NAME, required = false) String guestToken,
                                    @PathVariable UUID productId,
                                    HttpServletRequest http) {
        User user = authenticatedUser(authentication);
        rateLimit.enforceCartMutation(http.getRemoteAddr(), user != null ? user.getId() : null);
        if (user != null) {
            return cartService.removeUserItem(user.getId(), productId);
        }
        if (guestToken == null) {
            throw AppException.notFound("Cart item not found");
        }
        return cartService.removeGuestItem(guestToken, productId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(Authentication authentication,
                       @CookieValue(name = GuestCartCookieFactory.COOKIE_NAME, required = false) String guestToken,
                       HttpServletResponse response, HttpServletRequest http) {
        User user = authenticatedUser(authentication);
        rateLimit.enforceCartMutation(http.getRemoteAddr(), user != null ? user.getId() : null);
        if (user != null) {
            cartService.clearUserCart(user.getId());
            return;
        }
        if (guestToken != null) {
            cartService.clearGuestCart(guestToken);
            response.addHeader(HttpHeaders.SET_COOKIE, guestCookies.clear().toString());
        }
    }

    private String ensureGuestToken(String existing, HttpServletResponse response) {
        if (existing != null) {
            return existing;
        }
        String token = guestCookies.newToken();
        response.addHeader(HttpHeaders.SET_COOKIE, guestCookies.issue(token).toString());
        return token;
    }

    private User authenticatedUser(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }
}
