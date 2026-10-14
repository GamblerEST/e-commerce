package ee.shop.order.web;

import ee.shop.cart.web.dto.AddCartItemRequest;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.order.web.dto.OrderResponse;
import ee.shop.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers checkout and VAT: snapshotted lines,
 * server-computed pricing (never trusting a client-supplied price/qty),
 * idempotent order creation, and inventory integration. The mandated N-
 * thread oversell concurrency test lives in InventoryServiceTest — this
 * class proves the HTTP-level WIRING (a failed reservation becomes a clean
 * 409, not a partial order), not the race itself.
 */
@Tag("api")
@Tag("flow")
@AutoConfigureMockMvc
class CheckoutControllerTest extends IntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String GUEST_CART_COOKIE = "guest_cart_id";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private UUID insertProduct(String name, BigDecimal price, int onHand) {
        return insertProduct(name, price, onHand, "STANDARD");
    }

    private UUID insertProduct(String name, BigDecimal price, int onHand, String vatCategory) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price, currency, vat_category) VALUES (?, ?, ?, ?, 'EUR', ?)",
                id, name, "regression-" + id, price, vatCategory);
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)", id, onHand);
        return id;
    }

    private Cookie cookie(MvcResult result, String name) {
        Cookie c = result.getResponse().getCookie(name);
        assertThat(c).as("cookie '%s' present on response", name).isNotNull();
        return c;
    }

    private Cookie primeCsrf() throws Exception {
        return cookie(mvc.perform(get("/api/cart")).andReturn(), CSRF_COOKIE);
    }

    private Cookie addToGuestCart(Cookie csrf, UUID productId, int quantity) throws Exception {
        MvcResult result = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, quantity))))
                .andExpect(status().isOk())
                .andReturn();
        return cookie(result, GUEST_CART_COOKIE);
    }

    private CheckoutRequest billingAddress() {
        return new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555", "Pikk tn 1", null,
                "Tallinn", "10123", "EE", null, null, null, null);
    }

    private OrderResponse checkoutAsGuest(Cookie csrf, Cookie guestCart, String idempotencyKey) throws Exception {
        MvcResult result = mvc.perform(post("/api/checkout")
                        .cookie(csrf, guestCart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billingAddress())))
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), OrderResponse.class);
    }

    /**
     * @Valid on CheckoutController's request parameter runs before the
     * method body — a cart doesn't need to exist for these to return 400,
     * since binding/validation fails before OrderService is ever reached.
     */
    private ResultActions postCheckout(Cookie csrf, CheckoutRequest req) throws Exception {
        return mvc.perform(post("/api/checkout")
                .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));
    }

    // ── field validation (missing fields,
    //    invalid email/phone/address formats) ───────────────────────────

    @Test
    void checkout_missingRequiredField_isRejectedWithSpecificMessage() throws Exception {
        Cookie csrf = primeCsrf();
        CheckoutRequest blankName = new CheckoutRequest("", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", null, null, null, null);

        postCheckout(csrf, blankName)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.billingName").exists());
    }

    @Test
    void checkout_invalidEmailFormat_isRejectedWithSpecificMessage() throws Exception {
        Cookie csrf = primeCsrf();
        CheckoutRequest badEmail = new CheckoutRequest("Jaan Tamm", "not-an-email", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", null, null, null, null);

        postCheckout(csrf, badEmail)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.billingEmail").exists());
    }

    @Test
    void checkout_invalidPhoneFormat_isRejectedWithSpecificMessage() throws Exception {
        Cookie csrf = primeCsrf();
        CheckoutRequest badPhone = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "call me maybe",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", null, null, null, null);

        postCheckout(csrf, badPhone)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.billingPhone").value("must be a valid phone number"));
    }

    /**
     * Regression test: an earlier version of this validation required a
     * digit in addressLine1, which rejected real Estonian rural/farmstead
     * addresses (Estonia's own ADS identifies these by property name alone,
     * e.g. "Männiku talu" — no street, no number). This proves the checkout
     * flow actually accepts one end to end, not just that a lone field
     * validator passes.
     */
    @Test
    void checkout_ruralAddressWithNoStreetNumber_isAccepted() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Farmstead Delivery Widget", new BigDecimal("15.00"), 5);
        Cookie guestCart = addToGuestCart(csrf, productId, 1);
        CheckoutRequest farmstead = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Männiku talu", null, "Rõngu", "61001", "EE", null, null, null, null);

        mvc.perform(post("/api/checkout")
                        .cookie(csrf, guestCart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(farmstead)))
                .andExpect(status().isCreated());
    }

    @Test
    void checkout_cityContainingDigits_isRejectedWithSpecificMessage() throws Exception {
        Cookie csrf = primeCsrf();
        CheckoutRequest badCity = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn123", "10123", "EE", null, null, null, null);

        postCheckout(csrf, badCity)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.city").value("must be a valid city name"));
    }

    @Test
    void checkout_malformedPostalCode_isRejectedWithSpecificMessage() throws Exception {
        Cookie csrf = primeCsrf();
        // "12345x" is six characters, so it clears @Size(min=3) and matches
        // the loose @Pattern (letters and digits are both allowed there) —
        // which isolates the country-aware check. Estonian postal codes are
        // exactly five digits.
        CheckoutRequest badPostal = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "12345x", "EE", null, null, null, null);

        postCheckout(csrf, badPostal)
                .andExpect(status().isBadRequest())
                // The message names the expected format rather than saying
                // "invalid". A customer can act on the first and not the second.
                .andExpect(jsonPath("$.errors.postalCode")
                        .value("Estonian postal codes are five digits, for example 10123"));
    }

    @Test
    void checkout_invalidCountryCode_isRejectedWithSpecificMessage() throws Exception {
        Cookie csrf = primeCsrf();
        CheckoutRequest badCountry = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "e3", null, null, null, null);

        postCheckout(csrf, badCountry)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.country").exists());
    }

    @Test
    void guestCheckout_createsOrderWithSnapshottedLinesAndCorrectVat() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Checkout Widget", new BigDecimal("124.00"), 10);
        Cookie guestCart = addToGuestCart(csrf, productId, 2);

        MvcResult result = mvc.perform(post("/api/checkout")
                        .cookie(csrf, guestCart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billingAddress())))
                .andExpect(status().isCreated())
                .andReturn();

        OrderResponse order = json.readValue(result.getResponse().getContentAsString(), OrderResponse.class);

        assertThat(order.status()).isEqualTo("CREATED");
        assertThat(order.lines()).hasSize(1);
        assertThat(order.lines().get(0).productName()).isEqualTo("Checkout Widget");
        assertThat(order.lines().get(0).quantity()).isEqualTo(2);
        assertThat(order.lines().get(0).unitPrice()).isEqualByComparingTo("124.00");
        assertThat(order.lines().get(0).vatRate()).isEqualByComparingTo("0.24"); // app.tax.rates.STANDARD

        // total = 2 * 124.00 = 248.00 gross; net = 248.00 / 1.24 = 200.00 exactly.
        assertThat(order.total()).isEqualByComparingTo("248.00");
        assertThat(order.subtotal()).isEqualByComparingTo("200.00");
        assertThat(order.vatTotal()).isEqualByComparingTo("48.00");
        assertThat(order.subtotal().add(order.vatTotal())).isEqualByComparingTo(order.total());

        assertThat(order.billingName()).isEqualTo("Jaan Tamm");
        assertThat(order.billingCity()).isEqualTo("Tallinn");

        // Inventory actually reserved, not just priced.
        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(reserved).isEqualTo(2);

        // Guest cart cleared after a successful checkout.
        MvcResult afterCheckout = mvc.perform(get("/api/cart").cookie(guestCart)).andReturn();
        assertThat(afterCheckout.getResponse().getContentAsString()).contains("\"items\":[]");
    }

    @Test
    void checkout_withoutIdempotencyKey_isBadRequest() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("No Key Widget", new BigDecimal("10.00"), 5);
        Cookie guestCart = addToGuestCart(csrf, productId, 1);

        mvc.perform(post("/api/checkout")
                        .cookie(csrf, guestCart).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billingAddress())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkout_withEmptyCart_isBadRequest() throws Exception {
        Cookie csrf = primeCsrf();

        mvc.perform(post("/api/checkout")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billingAddress())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkout_sameIdempotencyKeyTwice_returnsTheSameOrder_notTwo() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Idempotent Widget", new BigDecimal("50.00"), 10);
        Cookie guestCart = addToGuestCart(csrf, productId, 1);
        String key = UUID.randomUUID().toString();

        OrderResponse first = checkoutAsGuest(csrf, guestCart, key);
        // Cart is empty now, but a genuine retry replays the SAME key — must
        // return the same order rather than failing on an empty cart.
        OrderResponse second = checkoutAsGuest(csrf, guestCart, key);

        assertThat(second.id()).isEqualTo(first.id());

        Integer orderCount = jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE idempotency_key = ?", Integer.class, key);
        assertThat(orderCount).isEqualTo(1);

        // Only reserved ONCE — a naive retry would have double-reserved.
        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(reserved).isEqualTo(1);
    }

    @Test
    void checkout_outOfStock_failsCleanly_withNoPartialOrderOrReservation() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Scarce Widget", new BigDecimal("30.00"), 5);
        Cookie guestCart = addToGuestCart(csrf, productId, 5); // matches what's available at add time

        // Stock disappears AFTER it's in the cart (another channel sold it,
        // a restock correction, etc.) — CartService's own advisory check
        // (an add-to-cart convenience, not the real guard) can't catch this;
        // this is specifically testing checkout's atomic reserve(), which
        // must still fail cleanly on a race it didn't see coming.
        jdbc.update("UPDATE inventory SET on_hand = 1 WHERE product_id = ?", productId);

        mvc.perform(post("/api/checkout")
                        .cookie(csrf, guestCart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billingAddress())))
                .andExpect(status().isConflict());

        // Scoped to THIS product, not a global orders count — the shared
        // test-suite database already has orders from other test methods.
        Integer orderLineCount = jdbc.queryForObject(
                "SELECT count(*) FROM order_lines WHERE product_id = ?", Integer.class, productId);
        assertThat(orderLineCount).isEqualTo(0);

        Integer reserved = jdbc.queryForObject("SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(reserved).isEqualTo(0); // nothing left dangling from the failed attempt

        // Cart is untouched — a failed checkout must not clear it.
        MvcResult cartResult = mvc.perform(get("/api/cart").cookie(guestCart)).andReturn();
        assertThat(cartResult.getResponse().getContentAsString()).doesNotContain("\"items\":[]");
    }

    @Test
    void secondCheckout_afterFirstTookTheLastUnit_isConflict() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Last Unit Widget", new BigDecimal("20.00"), 1);

        // Both shoppers add while the advisory check still sees 1 available —
        // reservation only happens at checkout (DECISIONS.md §3), not at
        // add-to-cart, so both can legitimately get this far before either
        // commits. This is the real race the atomic reserve() exists for.
        Cookie firstCart = addToGuestCart(csrf, productId, 1);
        Cookie secondCart = addToGuestCart(csrf, productId, 1);

        checkoutAsGuest(csrf, firstCart, UUID.randomUUID().toString());

        mvc.perform(post("/api/checkout")
                        .cookie(csrf, secondCart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billingAddress())))
                .andExpect(status().isConflict());
    }

    @Test
    void orderLine_isSnapshotted_andSurvivesALaterPriceChange() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Price Drift Widget", new BigDecimal("100.00"), 10);
        Cookie guestCart = addToGuestCart(csrf, productId, 1);

        OrderResponse order = checkoutAsGuest(csrf, guestCart, UUID.randomUUID().toString());
        assertThat(order.lines().get(0).unitPrice()).isEqualByComparingTo("100.00");

        // Raise the live price AFTER the order exists.
        jdbc.update("UPDATE products SET price = ? WHERE id = ?", new BigDecimal("999.00"), productId);

        BigDecimal storedUnitPrice = jdbc.queryForObject(
                "SELECT unit_price FROM order_lines WHERE order_id = ?::uuid", BigDecimal.class, order.id());
        assertThat(storedUnitPrice).isEqualByComparingTo("100.00"); // untouched — never re-joined to products
    }

    @Test
    void loggedInCheckout_setsOrderUserId_andClearsThePersistentCart() throws Exception {
        var registerReq = new RegisterRequest("checkout-" + UUID.randomUUID() + "@test.ee",
                "correct-horse-battery", "Checkout User", null);
        MvcResult registerResult = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(registerReq)))
                .andExpect(status().isCreated())
                .andReturn();
        String token = json.readValue(registerResult.getResponse().getContentAsString(), AuthResponse.class).accessToken();

        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("User Checkout Widget", new BigDecimal("15.00"), 5);

        mvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 1))))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/api/checkout")
                        .header("Authorization", "Bearer " + token)
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billingAddress())))
                .andExpect(status().isCreated())
                .andReturn();

        OrderResponse order = json.readValue(result.getResponse().getContentAsString(), OrderResponse.class);
        UUID userId = jdbc.queryForObject("SELECT user_id FROM orders WHERE id = ?::uuid", UUID.class, order.id());
        assertThat(userId).isNotNull();

        MvcResult cartAfter = mvc.perform(get("/api/cart").header("Authorization", "Bearer " + token)).andReturn();
        assertThat(cartAfter.getResponse().getContentAsString()).contains("\"items\":[]");
    }

    // ── addresses picked from the Estonian address register ─────────────

    private CheckoutRequest registerAddress(String line1, String postalCode, String adsOid) {
        return new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555", line1, null,
                "Tallinn", postalCode, "EE", null, adsOid, null, null);
    }

    private ResultActions checkoutAsGuestWith(Cookie csrf, Cookie guestCart, String idempotencyKey,
                                              CheckoutRequest req) throws Exception {
        return mvc.perform(post("/api/checkout")
                .cookie(csrf, guestCart).header(CSRF_HEADER, csrf.getValue())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(req)));
    }

    @Test
    void anAddressPickedFromTheRegisterCompletesCheckout() throws Exception {
        UUID productId = insertProduct("Register Verified Mug", new BigDecimal("9.90"), 5);
        Cookie csrf = primeCsrf();
        Cookie guestCart = addToGuestCart(csrf, productId, 1);

        checkoutAsGuestWith(csrf, guestCart, UUID.randomUUID().toString(),
                registerAddress("Pikk tn 1", "10123", "EE00658009"))
                .andExpect(status().isCreated());
    }

    /**
     * The attack: a genuine register id with a changed house number. It has to be
     * refused before anything is reserved, or a rejected order would still hold
     * stock until the reservation expired, and the cart has to survive so the
     * customer can correct the address and try again.
     */
    @Test
    void aTamperedRegisterAddressIsRefusedBeforeStockIsReserved() throws Exception {
        UUID productId = insertProduct("Register Tamper Mug", new BigDecimal("9.90"), 5);
        Cookie csrf = primeCsrf();
        Cookie guestCart = addToGuestCart(csrf, productId, 1);
        String key = UUID.randomUUID().toString();

        checkoutAsGuestWith(csrf, guestCart, key, registerAddress("Pikk tn 12", "10123", "EE00658009"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.errors.addressLine1").exists());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE idempotency_key = ?", Integer.class, key)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, productId)).isZero();
        mvc.perform(get("/api/cart").cookie(guestCart))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.items[0].productId").value(productId.toString()));
    }
}
