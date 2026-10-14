package ee.shop.order.web;

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
 * Staff order management.
 *
 * Two things under test that matter more than the CRUD: that shipping is
 * APPENDED as an event rather than written over the order (DECISIONS.md §1),
 * and that the SUPPORT/ADMIN split actually holds — support can ship, only an
 * admin can send money back out.
 */
@Tag("api")
@AutoConfigureMockMvc
class AdminOrderControllerTest extends IntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String GUEST_CART_COOKIE = "guest_cart_id";

    @Autowired MockMvc mvc;
    @Autowired EmailHasher emailHasher;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private UUID insertProduct(BigDecimal price, int onHand) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price, currency, vat_category) "
                + "VALUES (?, 'Admin Order Widget', ?, ?, 'EUR', 'STANDARD')", id, "adminorder-" + id, price);
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, ?, 0)", id, onHand);
        return id;
    }

    private String staffToken(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@test.ee";
        String token = tokenFor(email);
        jdbc.update("UPDATE users SET role = ?, totp_secret_enc = 'test-secret', totp_enabled_at = now() "
                + "WHERE email_hash = ?", role, emailHasher.hash(email));
        return token;
    }

    private String tokenFor(String email) throws Exception {
        var req = new RegisterRequest(email, "correct-horse-battery", "Order Staff", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    /** A real guest order, left in CREATED. */
    private OrderResponse placeOrder() throws Exception {
        Cookie csrf = mvc.perform(get("/api/cart")).andReturn().getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();
        UUID productId = insertProduct(new BigDecimal("30.00"), 5);

        MvcResult added = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 1))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cart = added.getResponse().getCookie(GUEST_CART_COOKIE);

        var billing = new CheckoutRequest("Jaan Tamm", "jaan@test.ee", "+372 5555 5555",
                "Pikk tn 1", null, "Tallinn", "10123", "EE", null, null);
        MvcResult checkout = mvc.perform(post("/api/checkout")
                        .cookie(csrf, cart).header(CSRF_HEADER, csrf.getValue())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(billing)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(checkout.getResponse().getContentAsString(), OrderResponse.class);
    }

    private void markPaid(UUID orderId) {
        jdbc.update("INSERT INTO order_events (order_id, event_type) VALUES (?, 'PAID')", orderId);
    }

    // ── authorization ────────────────────────────────────────────────────

    @Test
    void orderList_isNotVisibleToCustomers() throws Exception {
        String customer = tokenFor("customer-" + UUID.randomUUID() + "@test.ee");

        mvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void support_canSeeEveryOrder_includingOnesTheyDidNotPlace() throws Exception {
        OrderResponse order = placeOrder();

        mvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + staffToken("SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + order.id() + "')]").isNotEmpty());
    }

    /** Refunds move real money out of the business — support may not. */
    @Test
    void support_cannotIssueRefunds() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());

        mvc.perform(post("/api/admin/orders/" + order.id() + "/refund")
                        .header("Authorization", "Bearer " + staffToken("SUPPORT")))
                .andExpect(status().isForbidden());
    }

    // ── shipping is an appended fact, not an edit ────────────────────────

    @Test
    void markingShipped_appendsAnEvent_andNeverUpdatesTheOrderRow() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());

        Integer eventsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM order_events WHERE order_id = ?", Integer.class, order.id());

        mvc.perform(post("/api/admin/orders/" + order.id() + "/shipped")
                        .header("Authorization", "Bearer " + staffToken("SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        Integer eventsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM order_events WHERE order_id = ?", Integer.class, order.id());
        assertThat(eventsAfter).as("a new fact, not an edit").isEqualTo(eventsBefore + 1);

        // The CREATED and PAID events are still there — history intact.
        Integer created = jdbc.queryForObject(
                "SELECT count(*) FROM order_events WHERE order_id = ? AND event_type = 'CREATED'",
                Integer.class, order.id());
        assertThat(created).isEqualTo(1);
    }

    /**
     * Shipping something that was never paid for is a warehouse mistake the
     * system should refuse, not faithfully record.
     */
    @Test
    void anUnpaidOrder_cannotBeMarkedShipped() throws Exception {
        OrderResponse order = placeOrder(); // still CREATED

        mvc.perform(post("/api/admin/orders/" + order.id() + "/shipped")
                        .header("Authorization", "Bearer " + staffToken("SUPPORT")))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnpaidOrder_cannotBeRefunded() throws Exception {
        OrderResponse order = placeOrder();

        mvc.perform(post("/api/admin/orders/" + order.id() + "/refund")
                        .header("Authorization", "Bearer " + staffToken("ADMIN")))
                .andExpect(status().isConflict());
    }

    // ── staff detail view ────────────────────────────────────────────────

    @Test
    void staffDetail_showsTheSameOrderTheCustomerWouldSee() throws Exception {
        OrderResponse order = placeOrder();

        mvc.perform(get("/api/admin/orders/" + order.id())
                        .header("Authorization", "Bearer " + staffToken("SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(order.reference()))
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.events").isArray());
    }

    @Test
    void shippingIsAudited() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());
        String token = staffToken("SUPPORT");

        mvc.perform(post("/api/admin/orders/" + order.id() + "/shipped")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        Long entries = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'admin.order.shipped' AND target_id = ?",
                Long.class, order.id().toString());
        assertThat(entries).isEqualTo(1);
    }

    // ── billing documents ────────────────────────────────────────────────

    private void reissue(UUID orderId, String token) throws Exception {
        mvc.perform(post("/api/admin/orders/" + orderId + "/billing-documents/reissue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void admin_canIssueABillingDocument_andItAppendsARowAndAnEvent() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());

        reissue(order.id(), staffToken("ADMIN"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_documents WHERE order_id = ?",
                Integer.class, order.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM order_events WHERE order_id = ? AND event_type = 'INVOICE_ISSUED'",
                Integer.class, order.id())).isEqualTo(1);
    }

    /** A second issue is a NEW document; the first is left exactly as it was. */
    @Test
    void aSecondIssueAddsADocumentAndLeavesTheFirstUntouched() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());
        String admin = staffToken("ADMIN");
        reissue(order.id(), admin);
        var first = jdbc.queryForMap(
                "SELECT id, storage_key, generated_at FROM billing_documents WHERE order_id = ?", order.id());

        reissue(order.id(), admin);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_documents WHERE order_id = ?",
                Integer.class, order.id())).isEqualTo(2);
        var firstAgain = jdbc.queryForMap(
                "SELECT id, storage_key, generated_at FROM billing_documents WHERE id = ?", first.get("id"));
        assertThat(firstAgain).isEqualTo(first);
    }

    /** The append-only guarantee is the database's, not just the application's. */
    @Test
    void aBillingDocumentRowCannotBeRewrittenOrDeleted() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());
        reissue(order.id(), staffToken("ADMIN"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE billing_documents SET storage_key = 'x.pdf' WHERE order_id = ?", order.id()))
                .rootCause().hasMessageMatching("(?s).*(permission denied|append-only).*");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("DELETE FROM billing_documents WHERE order_id = ?", order.id()))
                .rootCause().hasMessageMatching("(?s).*(permission denied|append-only).*");
    }

    @Test
    void support_cannotIssueABillingDocument() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());

        mvc.perform(post("/api/admin/orders/" + order.id() + "/billing-documents/reissue")
                        .header("Authorization", "Bearer " + staffToken("SUPPORT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void support_canListAndDownloadBillingDocuments() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());
        reissue(order.id(), staffToken("ADMIN"));
        String support = staffToken("SUPPORT");

        MvcResult list = mvc.perform(get("/api/admin/orders/" + order.id() + "/billing-documents")
                        .header("Authorization", "Bearer " + support))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].issuedBy").exists())
                .andReturn();
        String documentId = json.readTree(list.getResponse().getContentAsString()).get(0).get("id").asString();

        MvcResult download = mvc.perform(get(
                        "/api/admin/orders/" + order.id() + "/billing-documents/" + documentId + "/download")
                        .header("Authorization", "Bearer " + support))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(download.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(new String(download.getResponse().getContentAsByteArray(), 0, 4,
                java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }

    @Test
    void aDocumentCannotBeDownloadedThroughAnotherOrdersUrl() throws Exception {
        OrderResponse orderA = placeOrder();
        OrderResponse orderB = placeOrder();
        markPaid(orderA.id());
        reissue(orderA.id(), staffToken("ADMIN"));
        UUID documentOfA = jdbc.queryForObject(
                "SELECT id FROM billing_documents WHERE order_id = ?", UUID.class, orderA.id());

        mvc.perform(get("/api/admin/orders/" + orderB.id() + "/billing-documents/" + documentOfA + "/download")
                        .header("Authorization", "Bearer " + staffToken("SUPPORT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void customers_cannotSeeBillingDocuments() throws Exception {
        OrderResponse order = placeOrder();

        mvc.perform(get("/api/admin/orders/" + order.id() + "/billing-documents")
                        .header("Authorization", "Bearer " + tokenFor("c-" + UUID.randomUUID() + "@test.ee")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnpaidOrder_cannotHaveABillingDocumentIssued() throws Exception {
        OrderResponse order = placeOrder(); // still CREATED

        mvc.perform(post("/api/admin/orders/" + order.id() + "/billing-documents/reissue")
                        .header("Authorization", "Bearer " + staffToken("ADMIN")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing_documents WHERE order_id = ?",
                Integer.class, order.id())).isZero();
    }

    /** INVOICE_ISSUED is a fact about the order, never its status. */
    @Test
    void theOrderStillReportsPaidAfterABillingDocumentIsIssued() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());
        String admin = staffToken("ADMIN");
        reissue(order.id(), admin);

        mvc.perform(get("/api/admin/orders/" + order.id()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void issuingABillingDocumentIsAudited() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());

        reissue(order.id(), staffToken("ADMIN"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'admin.order.invoice_reissued' AND target_id = ?",
                Long.class, order.id().toString())).isEqualTo(1);
    }

    // ── delivered ────────────────────────────────────────────────────────

    private void ship(UUID orderId, String token) throws Exception {
        mvc.perform(post("/api/admin/orders/" + orderId + "/shipped").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aShippedOrderCanBeMarkedDelivered_asAnAppendedEvent() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());
        String support = staffToken("SUPPORT");
        ship(order.id(), support);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM order_events WHERE order_id = ?",
                Integer.class, order.id());

        mvc.perform(post("/api/admin/orders/" + order.id() + "/delivered").header("Authorization", "Bearer " + support))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_events WHERE order_id = ?",
                Integer.class, order.id())).isEqualTo(before + 1);
    }

    /** An order cannot arrive before it left. */
    @Test
    void onlyAShippedOrderCanBeMarkedDelivered() throws Exception {
        OrderResponse unpaid = placeOrder();
        OrderResponse paid = placeOrder();
        markPaid(paid.id());
        String support = staffToken("SUPPORT");

        mvc.perform(post("/api/admin/orders/" + unpaid.id() + "/delivered").header("Authorization", "Bearer " + support))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/orders/" + paid.id() + "/delivered").header("Authorization", "Bearer " + support))
                .andExpect(status().isConflict());
    }

    @Test
    void anOrderCannotBeMarkedDeliveredTwice() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());
        String support = staffToken("SUPPORT");
        ship(order.id(), support);
        mvc.perform(post("/api/admin/orders/" + order.id() + "/delivered").header("Authorization", "Bearer " + support))
                .andExpect(status().isOk());

        mvc.perform(post("/api/admin/orders/" + order.id() + "/delivered").header("Authorization", "Bearer " + support))
                .andExpect(status().isConflict());
    }

    @Test
    void markingDelivered_isRefusedToCustomers_andIsAudited() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());
        String support = staffToken("SUPPORT");
        ship(order.id(), support);

        mvc.perform(post("/api/admin/orders/" + order.id() + "/delivered")
                        .header("Authorization", "Bearer " + tokenFor("c-" + UUID.randomUUID() + "@test.ee")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/orders/" + order.id() + "/delivered").header("Authorization", "Bearer " + support))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'admin.order.delivered' AND target_id = ?",
                Long.class, order.id().toString())).isEqualTo(1);
    }

    @Test
    void aDeliveredOrderStillHasBillingDocumentsAndKeepsItsStatusWhenOneIsIssued() throws Exception {
        OrderResponse order = placeOrder();
        markPaid(order.id());
        String admin = staffToken("ADMIN");
        ship(order.id(), admin);
        mvc.perform(post("/api/admin/orders/" + order.id() + "/delivered").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        reissue(order.id(), admin);

        mvc.perform(get("/api/admin/orders/" + order.id()).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }
}
