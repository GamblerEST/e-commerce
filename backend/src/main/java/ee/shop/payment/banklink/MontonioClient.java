package ee.shop.payment.banklink;

import ee.shop.common.error.AppException;
import ee.shop.config.MontonioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Montonio's payments API: the bank list, orders, and refunds.
 *
 * Every call is signed. Order and refund bodies are sent as a token signed with
 * the store's secret key; reads carry a signed bearer token. Nothing here is
 * logged beyond the path and the HTTP status, because request and response
 * bodies carry customer details.
 */
@Component
public class MontonioClient {

    private static final Logger log = LoggerFactory.getLogger(MontonioClient.class);

    /** Montonio's own recommendation for how long a request token stays valid. */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    static final String UNAVAILABLE =
            "Bank payments are not available right now. Please try again shortly, or pay by card.";

    public record BankOption(String code, String name, String logoUrl) {}

    public record CreatedOrder(String uuid, String paymentUrl) {}

    /**
     * @param alreadyRequested true when Montonio reports a refund with this
     *                         idempotency key already exists: a retry of a request
     *                         that did reach it, not a failure
     */
    public record RefundRequest(String refundUuid, boolean alreadyRequested) {}

    private static final class DuplicateRefundException extends RuntimeException {}

    /**
     * Montonio answered and said no. Nothing was created, which is what makes it
     * different from a timeout: after a refusal a retry may start over, after a
     * timeout the request may already have landed.
     */
    public static final class RefusedException extends AppException {
        RefusedException(String message) {
            super(message, HttpStatus.BAD_GATEWAY);
        }
    }

    private final MontonioProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final MontonioJwt jwt;
    private final RestClient http;

    @Autowired // two constructors: this is the one Spring uses; the other lets tests fix the clock
    public MontonioClient(MontonioProperties props, ObjectMapper json, RestClient.Builder builder) {
        this(props, json, builder, Clock.systemUTC());
    }

    MontonioClient(MontonioProperties props, ObjectMapper json, RestClient.Builder builder, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.jwt = new MontonioJwt(json, props.secretKey(), clock);
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(15));
        this.http = builder.requestFactory(requests).build();
    }

    public MontonioJwt jwt() {
        return jwt;
    }

    /** Estonian banks that take euros, in the order Montonio says to show them. */
    public List<BankOption> estonianBanks() {
        JsonNode methods = get("/stores/payment-methods")
                .path("paymentMethods").path("paymentInitiation").path("setup").path("EE").path("paymentMethods");
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 0; i < methods.size(); i++) {
            rows.add(methods.get(i));
        }
        rows.sort(Comparator.comparingInt(n -> n.path("uiPosition").asInt(Integer.MAX_VALUE)));

        List<BankOption> banks = new ArrayList<>();
        for (JsonNode row : rows) {
            String code = MontonioJwt.text(row.path("code"));
            if (code == null || code.isBlank() || !takesEuros(row.path("supportedCurrencies"))) {
                continue;
            }
            banks.add(new BankOption(code, MontonioJwt.text(row.path("name")), MontonioJwt.text(row.path("logoUrl"))));
        }
        return banks;
    }

    public CreatedOrder createOrder(Map<String, Object> order) {
        JsonNode body = postSigned("/orders", order);
        String uuid = MontonioJwt.text(body.path("uuid"));
        String paymentUrl = MontonioJwt.text(body.path("paymentUrl"));
        if (uuid == null || paymentUrl == null) {
            log.warn("Montonio created an order but the response had no uuid or paymentUrl");
            throw new AppException(UNAVAILABLE, HttpStatus.BAD_GATEWAY);
        }
        return new CreatedOrder(uuid, paymentUrl);
    }

    /** Montonio's current view of one of its orders, including its refunds. */
    public JsonNode order(String montonioOrderUuid) {
        // Parsed as a UUID before it goes anywhere near a URL path.
        return get("/orders/" + UUID.fromString(montonioOrderUuid));
    }

    public RefundRequest createRefund(String montonioOrderUuid, BigDecimal amount, String idempotencyKey) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("orderUuid", UUID.fromString(montonioOrderUuid).toString());
        claims.put("amount", amount);
        claims.put("idempotencyKey", idempotencyKey);
        try {
            return new RefundRequest(MontonioJwt.text(postSigned("/refunds", claims).path("uuid")), false);
        } catch (DuplicateRefundException e) {
            return new RefundRequest(null, true);
        }
    }

    private JsonNode get(String path) {
        try {
            String body = http.get()
                    .uri(props.apiUrl() + path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.sign(withAccessKey(Map.of())))
                    .retrieve()
                    .body(String.class);
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RestClientResponseException e) {
            log.warn("Montonio GET {} answered {}", path, e.getStatusCode().value());
            throw new AppException(UNAVAILABLE, HttpStatus.BAD_GATEWAY);
        } catch (RestClientException e) {
            log.warn("Montonio GET {} failed: {}", path, e.getClass().getSimpleName());
            throw new AppException(UNAVAILABLE, HttpStatus.BAD_GATEWAY);
        }
    }

    private JsonNode postSigned(String path, Map<String, Object> claims) {
        try {
            String body = http.post()
                    .uri(props.apiUrl() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("data", jwt.sign(withAccessKey(claims))))
                    .retrieve()
                    .body(String.class);
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RestClientResponseException e) {
            String message = montonioMessage(e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 400 && message != null
                    && message.toLowerCase(Locale.ROOT).contains("idempotency key")) {
                throw new DuplicateRefundException();
            }
            log.warn("Montonio POST {} answered {}", path, e.getStatusCode().value());
            // Montonio's own explanation is shown to whoever asked (for a refund,
            // usually "the funds have not settled yet"); it names the order, never
            // the customer.
            if (e.getStatusCode().is4xxClientError()) {
                throw new RefusedException(message == null ? UNAVAILABLE : "Montonio refused the request: " + message);
            }
            throw new AppException(UNAVAILABLE, HttpStatus.BAD_GATEWAY);
        } catch (RestClientException e) {
            log.warn("Montonio POST {} failed: {}", path, e.getClass().getSimpleName());
            throw new AppException(UNAVAILABLE, HttpStatus.BAD_GATEWAY);
        }
    }

    private Map<String, Object> withAccessKey(Map<String, Object> claims) {
        Map<String, Object> signed = new LinkedHashMap<>(claims);
        long now = clock.instant().getEpochSecond();
        signed.put("accessKey", props.accessKey());
        signed.put("iat", now);
        signed.put("exp", now + TOKEN_TTL.toSeconds());
        return signed;
    }

    private static boolean takesEuros(JsonNode currencies) {
        if (currencies.isMissingNode() || currencies.isNull()) {
            return true;
        }
        for (int i = 0; i < currencies.size(); i++) {
            if ("EUR".equals(MontonioJwt.text(currencies.get(i)))) {
                return true;
            }
        }
        return false;
    }

    private String montonioMessage(String body) {
        try {
            JsonNode message = json.readTree(body).path("message");
            String text = message.isArray() && message.size() > 0 ? MontonioJwt.text(message.get(0)) : MontonioJwt.text(message);
            return text == null || text.isBlank() ? null : text.length() > 300 ? text.substring(0, 300) : text;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
