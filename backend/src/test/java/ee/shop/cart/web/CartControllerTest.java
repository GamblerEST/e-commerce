package ee.shop.cart.web;

import ee.shop.cart.web.dto.AddCartItemRequest;
import ee.shop.cart.web.dto.CartResponse;
import ee.shop.cart.web.dto.SetCartItemQuantityRequest;
import ee.shop.identity.web.dto.AuthResponse;
import ee.shop.identity.web.dto.RegisterRequest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers cart item management and totals for BOTH
 * cart flavours (guest-in-Redis, user-in-Postgres) plus the guest→user merge
 * on login. Products have no public constructor (same reasoning as
 * CatalogPersistenceTest), so rows are inserted directly via JdbcTemplate.
 */
@Tag("api")
@Tag("flow")
@AutoConfigureMockMvc
class CartControllerTest extends IntegrationTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String GUEST_CART_COOKIE = "guest_cart_id";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private UUID insertProduct(String name, BigDecimal price) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, name, slug, price, currency) VALUES (?, ?, ?, ?, 'EUR')",
                id, name, "regression-" + id, price);
        // Generous stock — same pattern as OrderServiceTest's insertProduct.
        // Cart's add-to-cart now advisory-checks availability (CartService.
        // requireAvailable) same as checkout's real reservation always has,
        // so a product with no inventory row is correctly unbuyable either way.
        jdbc.update("INSERT INTO inventory (product_id, on_hand, reserved) VALUES (?, 1000, 0)", id);
        return id;
    }

    private String email() {
        return "cart-user-" + UUID.randomUUID() + "@test.ee";
    }

    private Cookie cookie(MvcResult result, String name) {
        Cookie c = result.getResponse().getCookie(name);
        assertThat(c).as("cookie '%s' present on response", name).isNotNull();
        return c;
    }

    /** Any request touches CsrfCookieFilter, so even an anonymous GET yields a fresh XSRF-TOKEN cookie. */
    private Cookie primeCsrf() throws Exception {
        return cookie(mvc.perform(get("/api/cart")).andReturn(), CSRF_COOKIE);
    }

    private CartResponse cart(MvcResult result) throws Exception {
        return json.readValue(result.getResponse().getContentAsString(), CartResponse.class);
    }

    // ── guest cart ───────────────────────────────────────────────────────

    @Test
    void guestCart_emptyWithNoCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/cart"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cart(result).items()).isEmpty();
        assertThat(cart(result).totalPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void guestCart_addUpdateRemove_roundTrip() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Guest Cart Widget", new BigDecimal("19.99"));

        MvcResult addResult = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 2))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie guestCartCookie = cookie(addResult, GUEST_CART_COOKIE);
        CartResponse afterAdd = cart(addResult);
        assertThat(afterAdd.items()).hasSize(1);
        assertThat(afterAdd.items().get(0).quantity()).isEqualTo(2);
        assertThat(afterAdd.items().get(0).lineTotal()).isEqualByComparingTo("39.98");
        assertThat(afterAdd.totalPrice()).isEqualByComparingTo("39.98");

        // A second GET, carrying the guest cart cookie, sees the same item —
        // it survived the round trip through Redis.
        MvcResult viewResult = mvc.perform(get("/api/cart").cookie(guestCartCookie))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cart(viewResult).items()).hasSize(1);

        MvcResult updateResult = mvc.perform(patch("/api/cart/items/{id}", productId)
                        .cookie(guestCartCookie, csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SetCartItemQuantityRequest(5))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cart(updateResult).items().get(0).quantity()).isEqualTo(5);

        mvc.perform(delete("/api/cart/items/{id}", productId)
                        .cookie(guestCartCookie, csrf).header(CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isOk());

        MvcResult afterRemove = mvc.perform(get("/api/cart").cookie(guestCartCookie))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cart(afterRemove).items()).isEmpty();
    }

    @Test
    void guestCart_mutationWithoutCsrfHeader_isForbidden() throws Exception {
        UUID productId = insertProduct("No CSRF Widget", new BigDecimal("5.00"));
        mvc.perform(post("/api/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 1))))
                .andExpect(status().isForbidden());
    }

    /**
     * A client sneaking extra fields into a request body — a spoofed price,
     * a role claim, anything AddCartItemRequest doesn't declare — must be
     * REJECTED, not silently deserialized with the extra fields discarded.
     * spring.jackson.deserialization.fail-on-unknown-properties makes this
     * a structural guarantee independent of whether every DTO in the
     * codebase happens to stay narrowly scoped forever.
     */
    @Test
    void requestWithAnUnexpectedField_isRejected() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Unexpected Field Widget", new BigDecimal("45.00"));

        mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"quantity\":1,\"unitPrice\":0.01,\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void guestCart_addingUnknownProduct_isNotFound() throws Exception {
        Cookie csrf = primeCsrf();
        mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(UUID.randomUUID(), 1))))
                .andExpect(status().isNotFound());
    }

    @Test
    void guestCart_totalsAcrossMultipleLines_areExact() throws Exception {
        Cookie csrf = primeCsrf();
        UUID a = insertProduct("Line A", new BigDecimal("3.33"));
        UUID b = insertProduct("Line B", new BigDecimal("10.00"));

        MvcResult firstAdd = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(a, 3))))
                .andExpect(status().isOk())
                .andReturn();
        // MockMvc doesn't carry cookies between requests like a real browser
        // would — the guest cart cookie minted by the FIRST add must be
        // threaded into the second explicitly, or each call gets its own
        // fresh (empty) guest cart.
        Cookie guestCartCookie = cookie(firstAdd, GUEST_CART_COOKIE);

        MvcResult result = mvc.perform(post("/api/cart/items")
                        .cookie(csrf, guestCartCookie).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(b, 1))))
                .andExpect(status().isOk())
                .andReturn();

        // 3 * 3.33 + 1 * 10.00 = 19.99 exactly — BigDecimal, not float
        // rounding error territory (the project's money rule).
        CartResponse view = cart(result);
        assertThat(view.itemCount()).isEqualTo(4);
        assertThat(view.totalPrice()).isEqualByComparingTo("19.99");
    }

    @Test
    void guestCart_addQuantity_clampsAtMax() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Clamp Widget", new BigDecimal("1.00"));

        MvcResult firstAdd = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 60))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie guestCartCookie = cookie(firstAdd, GUEST_CART_COOKIE);

        MvcResult result = mvc.perform(post("/api/cart/items")
                        .cookie(csrf, guestCartCookie).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 60))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(cart(result).items().get(0).quantity()).isEqualTo(99);
    }

    // ── logged-in cart ───────────────────────────────────────────────────

    private String registerAndGetAccessToken() throws Exception {
        var req = new RegisterRequest(email(), "correct-horse-battery", "Cart Test User", null);
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readValue(result.getResponse().getContentAsString(), AuthResponse.class).accessToken();
    }

    @Test
    void userCart_addUpdateRemove_persistsAcrossRequests() throws Exception {
        String token = registerAndGetAccessToken();
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("User Cart Widget", new BigDecimal("42.50"));

        mvc.perform(post("/api/cart/items")
                        .header("Authorization", "Bearer " + token)
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 1))))
                .andExpect(status().isOk());

        MvcResult viewResult = mvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cart(viewResult).items()).hasSize(1);
        assertThat(cart(viewResult).items().get(0).lineTotal()).isEqualByComparingTo("42.50");

        mvc.perform(delete("/api/cart/items/{id}", productId)
                        .header("Authorization", "Bearer " + token)
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isOk());

        MvcResult afterRemove = mvc.perform(get("/api/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cart(afterRemove).items()).isEmpty();
    }

    // ── guest → user merge on login ─────────────────────────────────────

    @Test
    void guestCart_mergesIntoUserCart_onLogin() throws Exception {
        Cookie csrf = primeCsrf();
        UUID productId = insertProduct("Merge Widget", new BigDecimal("7.00"));

        MvcResult addResult = mvc.perform(post("/api/cart/items")
                        .cookie(csrf).header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AddCartItemRequest(productId, 2))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie guestCartCookie = cookie(addResult, GUEST_CART_COOKIE);

        String email = email();
        var registerReq = new RegisterRequest(email, "correct-horse-battery", "Merge Test User", null);
        MvcResult registerResult = mvc.perform(post("/api/auth/register")
                        .cookie(guestCartCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(registerReq)))
                .andExpect(status().isCreated())
                .andReturn();

        // The guest cookie must be cleared once merged — otherwise a shared
        // computer's next guest "inherits" the same cart handle.
        Cookie clearedGuestCookie = registerResult.getResponse().getCookie(GUEST_CART_COOKIE);
        assertThat(clearedGuestCookie).isNotNull();
        assertThat(clearedGuestCookie.getMaxAge()).isEqualTo(0);

        String accessToken = json.readValue(registerResult.getResponse().getContentAsString(), AuthResponse.class).accessToken();
        MvcResult userCartResult = mvc.perform(get("/api/cart").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();

        CartResponse merged = cart(userCartResult);
        assertThat(merged.items()).hasSize(1);
        assertThat(merged.items().get(0).productId()).isEqualTo(productId);
        assertThat(merged.items().get(0).quantity()).isEqualTo(2);

        // And the guest-side Redis entry is gone — GET with the old cookie
        // (as an anonymous caller would still send it until the browser
        // honors the clearing Set-Cookie) resolves to nothing, not a stale
        // duplicate of what's now in the user's cart.
        MvcResult goneResult = mvc.perform(get("/api/cart").cookie(guestCartCookie))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cart(goneResult).items()).isEmpty();
    }
}
