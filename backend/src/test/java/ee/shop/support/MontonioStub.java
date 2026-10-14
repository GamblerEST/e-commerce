package ee.shop.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ee.shop.payment.banklink.MontonioJwt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A local stand-in for Montonio's payments API.
 *
 * It behaves the way the documentation says Montonio does where the shop depends
 * on it: requests without a token signed by the store's secret key are refused,
 * order and refund bodies arrive as signed tokens, a repeated refund idempotency
 * key is rejected with Montonio's message, and order status and refunds can be
 * read back. The bank list is stand-in data shaped like the documented response.
 *
 * The secret key is deliberately shorter than 256 bits, so every test also proves
 * the integration accepts a short key.
 */
public final class MontonioStub {

    public static final String ACCESS_KEY = "test-access-key";
    public static final String SECRET_KEY = "test-montonio-secret";

    private static final String BANKS = """
            {"paymentMethods":{"paymentInitiation":{"processor":"montonio","setup":{
              "EE":{"supportedCurrencies":["EUR"],"paymentMethods":[
                {"code":"LHVBEE22","name":"LHV","logoUrl":"https://logos.example/lhv.svg","supportedCurrencies":["EUR"],"uiPosition":2},
                {"code":"HABAEE2X","name":"Swedbank","logoUrl":"https://logos.example/swedbank.svg","supportedCurrencies":["EUR"],"uiPosition":1},
                {"code":"PLNONLY1","name":"Zloty Only Bank","logoUrl":"https://logos.example/pln.svg","supportedCurrencies":["PLN"],"uiPosition":3}
              ]},
              "LV":{"supportedCurrencies":["EUR"],"paymentMethods":[
                {"code":"HABALV22","name":"Swedbank Latvia","logoUrl":"https://logos.example/swedbank-lv.svg","supportedCurrencies":["EUR"],"uiPosition":1}
              ]}
            }}}}
            """;

    private final HttpServer server;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final MontonioJwt jwt = new MontonioJwt(json, SECRET_KEY, Clock.systemUTC());
    private final List<JsonNode> createdOrders = new CopyOnWriteArrayList<>();
    private final List<JsonNode> refundRequests = new CopyOnWriteArrayList<>();
    private final Map<String, String> paymentStatuses = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> refunds = new ConcurrentHashMap<>();
    private final Set<String> idempotencyKeys = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> refuseNextRefund = new AtomicReference<>();
    private final AtomicBoolean loseNextRefundResponse = new AtomicBoolean();

    private MontonioStub(HttpServer server) {
        this.server = server;
    }

    public static MontonioStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            MontonioStub stub = new MontonioStub(server);
            server.createContext("/api/stores/payment-methods", stub::paymentMethods);
            server.createContext("/api/orders", stub::orders);
            server.createContext("/api/refunds", stub::refunds);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/api";
    }

    public void stop() {
        server.stop(0);
    }

    /** Claims of every order the shop created, verified. */
    public List<JsonNode> createdOrders() {
        return createdOrders;
    }

    public List<JsonNode> refundRequests() {
        return refundRequests;
    }

    public void setPaymentStatus(String montonioOrderUuid, String status) {
        paymentStatuses.put(montonioOrderUuid, status);
    }

    /** Settles the latest refund on an order the way Montonio would report it. */
    public void settleLatestRefund(String montonioOrderUuid, String refundStatus) {
        List<Map<String, Object>> list = refunds.getOrDefault(montonioOrderUuid, List.of());
        if (!list.isEmpty()) {
            list.get(list.size() - 1).put("status", refundStatus);
        }
        if ("SUCCESSFUL".equals(refundStatus)) {
            paymentStatuses.put(montonioOrderUuid, "REFUNDED");
        }
    }

    /** The next refund request is refused and nothing is created, as Montonio refuses one it will not make. */
    public void refuseNextRefund(String message) {
        refuseNextRefund.set(message);
    }

    /** The next refund is created but its answer never arrives: a timeout, as the shop sees it. */
    public void loseNextRefundResponse() {
        loseNextRefundResponse.set(true);
    }

    /** A notification or return token exactly as Montonio would sign it. */
    public String resultToken(String montonioOrderUuid, String merchantReference, String paymentStatus,
                              BigDecimal grandTotal, String currency) {
        Map<String, Object> claims = new LinkedHashMap<>();
        long now = Instant.now().getEpochSecond();
        claims.put("uuid", montonioOrderUuid);
        claims.put("accessKey", ACCESS_KEY);
        claims.put("merchantReference", merchantReference);
        claims.put("paymentStatus", paymentStatus);
        claims.put("paymentMethod", "paymentInitiation");
        claims.put("grandTotal", grandTotal);
        claims.put("currency", currency);
        claims.put("iat", now);
        claims.put("exp", now + 600);
        return jwt.sign(claims);
    }

    private void paymentMethods(HttpExchange exchange) throws IOException {
        if (!authorised(exchange)) {
            respond(exchange, 401, "{\"message\":\"Unauthorized\"}");
            return;
        }
        respond(exchange, 200, BANKS);
    }

    private void orders(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(exchange.getRequestMethod()) && path.equals("/api/orders")) {
            JsonNode claims = signedBody(exchange);
            if (claims == null) {
                respond(exchange, 401, "{\"message\":\"Invalid token\"}");
                return;
            }
            createdOrders.add(claims);
            String uuid = UUID.randomUUID().toString();
            paymentStatuses.put(uuid, "PENDING");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("uuid", uuid);
            body.put("paymentStatus", "PENDING");
            body.put("paymentUrl", "https://pay.example/" + uuid);
            body.put("merchantReference", text(claims.path("merchantReference")));
            body.put("grandTotal", text(claims.path("grandTotal")));
            body.put("currency", text(claims.path("currency")));
            respond(exchange, 200, json.writeValueAsString(body));
            return;
        }
        if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/api/orders/")) {
            if (!authorised(exchange)) {
                respond(exchange, 401, "{\"message\":\"Unauthorized\"}");
                return;
            }
            String uuid = path.substring("/api/orders/".length());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("uuid", uuid);
            body.put("paymentStatus", paymentStatuses.getOrDefault(uuid, "PENDING"));
            body.put("refunds", refunds.getOrDefault(uuid, List.of()));
            respond(exchange, 200, json.writeValueAsString(body));
            return;
        }
        respond(exchange, 404, "{\"message\":\"Not found\"}");
    }

    private void refunds(HttpExchange exchange) throws IOException {
        JsonNode claims = signedBody(exchange);
        if (claims == null) {
            respond(exchange, 401, "{\"message\":\"Invalid token\"}");
            return;
        }
        refundRequests.add(claims);
        String orderUuid = text(claims.path("orderUuid"));
        String key = text(claims.path("idempotencyKey"));
        String refusal = refuseNextRefund.getAndSet(null);
        if (refusal != null) {
            respond(exchange, 400, json.writeValueAsString(Map.of("message", refusal)));
            return;
        }
        if (!idempotencyKeys.add(key)) {
            respond(exchange, 400, "{\"message\":\"Order uuid " + orderUuid
                    + " already has a refund with same idempotency key\"}");
            return;
        }
        Map<String, Object> refund = new ConcurrentHashMap<>();
        refund.put("uuid", UUID.randomUUID().toString());
        refund.put("status", "PENDING");
        refund.put("amount", text(claims.path("amount")));
        refunds.computeIfAbsent(orderUuid, k -> new CopyOnWriteArrayList<>()).add(refund);
        if (loseNextRefundResponse.getAndSet(false)) {
            respond(exchange, 504, "{\"message\":\"Gateway Timeout\"}");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>(refund);
        body.put("currency", "EUR");
        body.put("type", "FULL_REFUND");
        respond(exchange, 200, json.writeValueAsString(body));
    }

    private boolean authorised(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        try {
            return ACCESS_KEY.equals(text(jwt.verify(header.substring(7)).path("accessKey")));
        } catch (MontonioJwt.InvalidTokenException e) {
            return false;
        }
    }

    private JsonNode signedBody(HttpExchange exchange) throws IOException {
        try {
            JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
            JsonNode claims = jwt.verify(text(body.path("data")));
            return ACCESS_KEY.equals(text(claims.path("accessKey"))) ? claims : null;
        } catch (MontonioJwt.InvalidTokenException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** For tests that want every refund ever requested for an order. */
    public List<Map<String, Object>> refundsFor(String montonioOrderUuid) {
        return new ArrayList<>(refunds.getOrDefault(montonioOrderUuid, List.of()));
    }
}
