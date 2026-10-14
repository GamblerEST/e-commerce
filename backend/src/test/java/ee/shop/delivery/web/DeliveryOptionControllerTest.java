package ee.shop.delivery.web;

import ee.shop.common.security.EmailHasher;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delivery options.
 *
 * The money is the dangerous part. Shipping is folded INTO subtotal/vatTotal
 * rather than bolted on outside them, so the invariant the rest of the system
 * depends on — subtotal + vatTotal == total, exactly — has to survive it.
 * That, and the snapshot: repricing a delivery method must never rewrite what
 * a past order says it charged.
 */
@Tag("api")
@AutoConfigureMockMvc
class DeliveryOptionControllerTest extends IntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String GUEST_CART_COOKIE = "guest_cart_id";

    @Autowired MockMvc mvc;
    @Autowired EmailHasher emailHasher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private UUID insertProduct(String name, BigDecimal price, int onHand) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price, currency, vat_category) "
                + "VALUES (?, ?, ?, ?, 'EUR', 'STANDARD')", id, name, "delivery-" + id, price);
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)", id, onHand);
        return id;
    }

    private Cookie primeCsrf() throws Exception {
        Cookie c = mvc.perform(get("/api/cart")).andReturn().getResponse().getCookie(CSRF_COOKIE);
        assertThat(c).isNotNull();
        return c;
    }

    private Cookie addToGuestCart(Cookie csrf, UUID productId) throws Exception {
        MvcResult result = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 1))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cart = result.getResponse().getCookie(GUEST_CART_COOKIE);
        assertThat(cart).isNotNull();
        return cart;
    }

    private OrderResponse checkout(Cookie csrf, Cookie cart, String deliveryCode) throws Exception {
        var billing = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", deliveryCode, null, null, null);
        MvcResult result = mvc.perform(post("/api/checkout")
                        .cookie(csrf, cart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billing)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), OrderResponse.class);
    }

    private String adminToken() throws Exception {
        String email = "delivery-admin-" + UUID.randomUUID() + "@test.ee";
        var req = new RegisterRequest(email, "correct-horse-battery", "Delivery Admin", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        jdbc.update("UPDATE users SET role = 'ADMIN', totp_secret_enc = 'test-secret', "
                + "totp_enabled_at = now() WHERE email_hash = ?", emailHasher.hash(email));
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    // ── public availability ──────────────────────────────────────────────

    /** Guest checkout has to see the menu before anyone signs in. */
    @Test
    void deliveryOptions_areReadableAnonymously() throws Exception {
        mvc.perform(get("/api/delivery-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").exists())
                .andExpect(jsonPath("$[0].leadTimeDaysMin").exists());
    }

    // ── the money ────────────────────────────────────────────────────────

    /**
     * Shipping must land inside the VAT breakdown, not beside it. If it were
     * added to `total` alone, subtotal + vatTotal would silently stop equalling
     * total and every invoice would be internally inconsistent.
     */
    @Test
    void shippingIsFoldedIntoTheVatBreakdown_soTheTotalsStillReconcile() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Shipped Widget", new BigDecimal("100.00"), 5);
        Cookie cart = addToGuestCart(csrf, productId);

        OrderResponse order = checkout(csrf, cart, "omniva_parcel");

        assertThat(order.total())
                .as("100.00 goods + 3.49 shipping, both gross")
                .isEqualByComparingTo("103.49");
        assertThat(order.subtotal().add(order.vatTotal()))
                .as("the invariant the whole ledger rests on")
                .isEqualByComparingTo(order.total());
        assertThat(order.delivery()).isNotNull();
        assertThat(order.delivery().price()).isEqualByComparingTo("3.49");
    }

    @Test
    void withoutADeliveryOption_theOrderHasNoShippingCostAndNoDeliveryBlock() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Unshipped Widget", new BigDecimal("100.00"), 5);
        Cookie cart = addToGuestCart(csrf, productId);

        OrderResponse order = checkout(csrf, cart, null);

        assertThat(order.total()).isEqualByComparingTo("100.00");
        assertThat(order.delivery()).isNull();
        assertThat(order.subtotal().add(order.vatTotal())).isEqualByComparingTo(order.total());
    }

    /** The confirmation page needs both of these; neither is stored. */
    @Test
    void orderCarriesAHumanReferenceAndAnEstimatedDeliveryWindow() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Estimated Widget", new BigDecimal("20.00"), 5);
        Cookie cart = addToGuestCart(csrf, productId);

        OrderResponse order = checkout(csrf, cart, "omniva_parcel");

        assertThat(order.reference())
                .as("readable down a phone, derived from the id")
                .matches("ORD-[0-9A-F]{8}");
        assertThat(order.delivery().estimatedFrom()).isNotNull();
        assertThat(order.delivery().estimatedTo()).isNotNull();
        assertThat(order.delivery().estimatedTo())
                .isAfterOrEqualTo(order.delivery().estimatedFrom());
    }

    // ── the snapshot ─────────────────────────────────────────────────────

    /**
     * Same rule as order lines (DECISIONS.md §2). Reprice the method and the
     * old order must still say what the customer actually agreed to.
     */
    @Test
    void repricingADeliveryOption_doesNotRewriteAnExistingOrder() throws Exception {
        // Its OWN option, not the seeded one: repricing shared seed data
        // would leak into every other test in the suite through the shared
        // database, and the failure would land on whichever test ran next.
        String code = "snapshot_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        jdbc.update("INSERT INTO delivery_options (code, name, price, currency, "
                + "lead_time_days_min, lead_time_days_max, active) VALUES (?, 'Snapshot method', 3.49, 'EUR', 1, 3, true)",
                code);

        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Snapshot Widget", new BigDecimal("50.00"), 5);
        Cookie cart = addToGuestCart(csrf, productId);
        OrderResponse order = checkout(csrf, cart, code);
        assertThat(order.delivery().price()).isEqualByComparingTo("3.49");

        jdbc.update("UPDATE delivery_options SET price = 99.99 WHERE code = ?", code);

        // This is a guest order, so read the snapshot straight off the row
        // rather than through an owner-scoped endpoint no guest can call.
        BigDecimal storedPrice = jdbc.queryForObject(
                "SELECT delivery_price FROM orders WHERE id = ?", BigDecimal.class, order.id());
        assertThat(storedPrice)
                .as("history is not rewritten by a later price change")
                .isEqualByComparingTo("3.49");

        // ...and a NEW order does pick up the new price, proving the snapshot
        // is the only reason the old one didn't move.
        Cookie csrf2 = primeCsrf();
        Cookie cart2 = addToGuestCart(csrf2, insertProduct("Later Widget", new BigDecimal("50.00"), 5));
        assertThat(checkout(csrf2, cart2, code).delivery().price())
                .isEqualByComparingTo("99.99");
    }

    // ── availability is enforced server-side ─────────────────────────────

    /**
     * A stale browser tab still showing a withdrawn method must not be able to
     * buy at its price — the client's menu is a cache, not the authority.
     */
    @Test
    void aRetiredDeliveryOption_cannotBeUsedAtCheckout() throws Exception {
        jdbc.update("INSERT INTO delivery_options (code, name, price, currency, "
                + "lead_time_days_min, lead_time_days_max, active) "
                + "VALUES ('retired_test', 'Retired method', 1.00, 'EUR', 1, 2, false)");

        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Retired Delivery Widget", new BigDecimal("10.00"), 5);
        Cookie cart = addToGuestCart(csrf, productId);

        var billing = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", "retired_test", null, null, null);
        mvc.perform(post("/api/checkout")
                        .cookie(csrf, cart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billing)))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/delivery-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'retired_test')]").isEmpty());
    }

    @Test
    void unknownDeliveryCode_isRejected() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Bad Code Widget", new BigDecimal("10.00"), 5);
        Cookie cart = addToGuestCart(csrf, productId);

        var billing = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", "does_not_exist", null, null, null);
        mvc.perform(post("/api/checkout")
                        .cookie(csrf, cart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billing)))
                .andExpect(status().isBadRequest());
    }

    // ── admin management ─────────────────────────────────────────────────

    @Test
    void managingDeliveryOptions_requiresAnAdmin() throws Exception {
        mvc.perform(get("/api/admin/delivery-options"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_canCreateADeliveryOption_andItBecomesSelectable() throws Exception {
        String token = adminToken();
        String code = "express_" + UUID.randomUUID().toString().substring(0, 6).replace("-", "");

        mvc.perform(post("/api/admin/delivery-options")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Express","carrier":"DPD","price":9.90,
                                 "currency":"EUR","leadTimeDaysMin":1,"leadTimeDaysMax":1,
                                 "active":true,"sortOrder":5}
                                """.formatted(code)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/delivery-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == '" + code + "')]").isNotEmpty());
    }

    @Test
    void leadTimeMaxBeforeMin_isRejected() throws Exception {
        mvc.perform(post("/api/admin/delivery-options")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"backwards_test","name":"Backwards","price":1.00,
                                 "currency":"EUR","leadTimeDaysMin":5,"leadTimeDaysMax":2,
                                 "active":true,"sortOrder":1}
                                """))
                .andExpect(status().isBadRequest());
    }
}
