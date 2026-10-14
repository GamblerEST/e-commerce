package ee.shop.order.web;

import ee.shop.cart.web.dto.AddCartItemRequest;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.RegisterRequest;
import ee.shop.order.web.dto.CheckoutRequest;
import ee.shop.order.web.dto.OrderListResponse;
import ee.shop.order.web.dto.OrderResponse;
import ee.shop.payment.service.PaymentService;
import ee.shop.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers list/filter, detail with event history, and
 * cancel-before-shipping — both the CREATED (release reservation) and PAID
 * (Stripe refund) branches. PaymentService is a @MockitoBean here: it talks
 * to a real third-party API, which no test/CI environment has credentials
 * for, so this class proves the ORDER-side wiring (cancelOrder calls
 * refundForOrder and appends REFUNDED) — PaymentService's own Stripe-facing
 * logic is covered by PaymentServiceTest's mocked-SDK unit tests instead.
 */
@Tag("api")
@AutoConfigureMockMvc
class OrderControllerTest extends IntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentService paymentService;

    private UUID insertProduct(String name, BigDecimal price, int onHand) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price, currency, vat_category) VALUES (?, ?, ?, ?, 'EUR', 'STANDARD')",
                id, name, "regression-" + id, price);
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)", id, onHand);
        return id;
    }

    private String registerAndGetAccessToken() throws Exception {
        var req = new RegisterRequest("order-test-" + UUID.randomUUID() + "@test.ee",
                "correct-horse-battery", "Order Test User", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    private Cookie cookie(MvcResult result, String name) {
        Cookie c = result.getResponse().getCookie(name);
        assertThat(c).as("cookie '%s' present on response", name).isNotNull();
        return c;
    }

    private Cookie primeCsrf() throws Exception {
        return cookie(mvc.perform(get("/api/cart")).andReturn(), CSRF_COOKIE);
    }

    /** Full checkout as an authenticated user — returns the created order. */
    private OrderResponse checkoutAsUser(String accessToken, Cookie csrf, UUID productId, int quantity) throws Exception {
        mvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, quantity))))
                .andExpect(status().isOk());

        var billing = new CheckoutRequest("Order Test User", "order-test@test.ee", "+372 5555 5555", "Pikk tn 1", null,
                "Tallinn", "10123", "EE", null, null, null, null);
        MvcResult result = mvc.perform(post("/api/checkout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billing)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), OrderResponse.class);
    }

    @Test
    void list_returnsOnlyTheCallersOwnOrders() throws Exception {
        String tokenA = registerAndGetAccessToken();
        String tokenB = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("List Widget", new BigDecimal("20.00"), 10);

        OrderResponse orderA = checkoutAsUser(tokenA, csrf, productId, 1);
        checkoutAsUser(tokenB, csrf, productId, 1); // a different user's order — must not appear for A

        MvcResult result = mvc.perform(get("/api/orders").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        OrderListResponse list = json.readValue(result.getResponse().getContentAsString(), OrderListResponse.class);

        assertThat(list.items()).extracting("id").containsExactly(orderA.id());
    }

    @Test
    void list_filtersByStatus() throws Exception {
        String token = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Status Filter Widget", new BigDecimal("10.00"), 10);
        checkoutAsUser(token, csrf, productId, 1);

        MvcResult createdResult = mvc.perform(get("/api/orders?status=CREATED").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        OrderListResponse created = json.readValue(createdResult.getResponse().getContentAsString(), OrderListResponse.class);
        assertThat(created.items()).isNotEmpty();

        // Nothing can reach PAID without Stripe — every order this
        // user has is CREATED, so filtering for PAID must come back empty.
        MvcResult paidResult = mvc.perform(get("/api/orders?status=PAID").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        OrderListResponse paid = json.readValue(paidResult.getResponse().getContentAsString(), OrderListResponse.class);
        assertThat(paid.items()).isEmpty();
    }

    @Test
    void detail_showsLinesAndEventHistory() throws Exception {
        String token = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Detail Widget", new BigDecimal("15.00"), 10);
        OrderResponse order = checkoutAsUser(token, csrf, productId, 2);

        MvcResult result = mvc.perform(get("/api/orders/" + order.id()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        OrderResponse detail = json.readValue(result.getResponse().getContentAsString(), OrderResponse.class);

        assertThat(detail.lines()).hasSize(1);
        assertThat(detail.events()).hasSize(1);
        assertThat(detail.events().get(0).type()).isEqualTo("CREATED");
        assertThat(detail.status()).isEqualTo("CREATED");
    }

    @Test
    void detail_ofAnotherUsersOrder_isNotFound() throws Exception {
        String tokenA = registerAndGetAccessToken();
        String tokenB = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Ownership Widget", new BigDecimal("10.00"), 10);
        OrderResponse orderA = checkoutAsUser(tokenA, csrf, productId, 1);

        mvc.perform(get("/api/orders/" + orderA.id()).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void detail_withoutAuthentication_isUnauthorized() throws Exception {
        mvc.perform(get("/api/orders/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancel_releasesInventory_andAppendsCancelledEvent() throws Exception {
        String token = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Cancel Widget", new BigDecimal("25.00"), 10);
        OrderResponse order = checkoutAsUser(token, csrf, productId, 3);

        Integer reservedAfterOrder = jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(reservedAfterOrder).isEqualTo(3);

        MvcResult result = mvc.perform(post("/api/orders/" + order.id() + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        OrderResponse cancelled = json.readValue(result.getResponse().getContentAsString(), OrderResponse.class);

        assertThat(cancelled.status()).isEqualTo("CANCELLED");

        Integer reservedAfterCancel = jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(reservedAfterCancel).isEqualTo(0);
    }

    @Test
    void cancel_aSecondTime_isConflict() throws Exception {
        String token = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Double Cancel Widget", new BigDecimal("12.00"), 10);
        OrderResponse order = checkoutAsUser(token, csrf, productId, 1);

        mvc.perform(post("/api/orders/" + order.id() + "/cancel").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/orders/" + order.id() + "/cancel").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void cancel_aShippedOrder_isConflict() throws Exception {
        String token = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Shipped Widget", new BigDecimal("18.00"), 10);
        OrderResponse order = checkoutAsUser(token, csrf, productId, 1);

        // SHIPPED has no defined cancel behavior — once it's out the door,
        // "cancel" is a real-world return process, not a button. Backdated
        // directly since nothing in this stage moves an order to SHIPPED.
        jdbc.update("INSERT INTO order_events (order_id, event_type) VALUES (?::uuid, 'PAID')", order.id());
        jdbc.update("INSERT INTO order_events (order_id, event_type) VALUES (?::uuid, 'SHIPPED')", order.id());

        mvc.perform(post("/api/orders/" + order.id() + "/cancel").header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        // And inventory must NOT have been released — the sale is real now.
        Integer reserved = jdbc.queryForObject(
                "SELECT reserved FROM inventory WHERE product_id = ?", Integer.class, productId);
        assertThat(reserved).isEqualTo(1);
    }

    @Test
    void cancel_aPaidOrder_refundsViaStripe_andAppendsRefundedEvent() throws Exception {
        String token = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Paid Widget", new BigDecimal("18.00"), 10);
        OrderResponse order = checkoutAsUser(token, csrf, productId, 1);

        // Simulates what the RabbitMQ consumer does on real payment success
        // — no live Stripe call is made from this test
        // (paymentService is a mock; see the @MockitoBean below), only the
        // refund-then-REFUNDED-event branch of OrderService.cancelOrder.
        jdbc.update("INSERT INTO order_events (order_id, event_type) VALUES (?::uuid, 'PAID')", order.id());

        MvcResult result = mvc.perform(post("/api/orders/" + order.id() + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        OrderResponse refunded = json.readValue(result.getResponse().getContentAsString(), OrderResponse.class);

        assertThat(refunded.status()).isEqualTo("REFUNDED");
        verify(paymentService).refundForOrder(order.id());
    }

    @Test
    void cancel_someoneElsesOrder_isNotFound() throws Exception {
        String tokenA = registerAndGetAccessToken();
        String tokenB = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Cancel Ownership Widget", new BigDecimal("10.00"), 10);
        OrderResponse order = checkoutAsUser(tokenA, csrf, productId, 1);

        mvc.perform(post("/api/orders/" + order.id() + "/cancel").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
