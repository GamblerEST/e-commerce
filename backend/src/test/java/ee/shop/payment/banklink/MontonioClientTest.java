package ee.shop.payment.banklink;

import ee.shop.common.error.AppException;
import ee.shop.config.MontonioProperties;
import ee.shop.support.MontonioStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class MontonioClientTest {

    private MontonioStub montonio;
    private MontonioClient client;

    @BeforeEach
    void start() {
        montonio = MontonioStub.start();
        client = clientAt(montonio.url());
    }

    @AfterEach
    void stop() {
        montonio.stop();
    }

    private static MontonioClient clientAt(String url) {
        return new MontonioClient(new MontonioProperties(true, url, MontonioStub.ACCESS_KEY, MontonioStub.SECRET_KEY),
                JsonMapper.builder().build(), RestClient.builder());
    }

    @Test
    void theBankListIsEstonianEuroBanksInMontoniosOrder() {
        assertThat(client.estonianBanks())
                .extracting(MontonioClient.BankOption::code)
                .containsExactly("HABAEE2X", "LHVBEE22");
    }

    /** The stand-in refuses anything not signed with the store key, so this also proves the signing. */
    @Test
    void anOrderIsSentAsASignedTokenCarryingTheAccessKeyAndAnExpiry() {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("merchantReference", "order-123");
        order.put("grandTotal", new BigDecimal("12.90"));
        order.put("currency", "EUR");

        MontonioClient.CreatedOrder created = client.createOrder(order);

        assertThat(created.paymentUrl()).isEqualTo("https://pay.example/" + created.uuid());
        JsonNode sent = montonio.createdOrders().getFirst();
        assertThat(sent.path("merchantReference").asText()).isEqualTo("order-123");
        assertThat(sent.path("accessKey").asText()).isEqualTo(MontonioStub.ACCESS_KEY);
        assertThat(sent.path("exp").asLong()).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void anOrdersStatusCanBeReadBack() {
        MontonioClient.CreatedOrder created = client.createOrder(Map.of("merchantReference", "order-456"));
        montonio.setPaymentStatus(created.uuid(), "PAID");

        assertThat(client.order(created.uuid()).path("paymentStatus").asText()).isEqualTo("PAID");
    }

    /** A retry of a refund that did reach Montonio is not a failure, and must not become a second refund. */
    @Test
    void aRepeatedRefundKeyIsReportedAsAlreadyRequested() {
        MontonioClient.CreatedOrder created = client.createOrder(Map.of("merchantReference", "order-789"));

        MontonioClient.RefundRequest first = client.createRefund(created.uuid(), new BigDecimal("12.90"), "refund-1");
        MontonioClient.RefundRequest retry = client.createRefund(created.uuid(), new BigDecimal("12.90"), "refund-1");

        assertThat(first.alreadyRequested()).isFalse();
        assertThat(first.refundUuid()).isNotBlank();
        assertThat(retry.alreadyRequested()).isTrue();
        assertThat(montonio.refundsFor(created.uuid())).hasSize(1);
    }

    @Test
    void anUnreachableApiIsABadGatewayWithACustomerSafeMessage() throws IOException {
        String deadUrl;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadUrl = "http://localhost:" + socket.getLocalPort() + "/api";
        }

        assertThatThrownBy(() -> clientAt(deadUrl).estonianBanks())
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).isEqualTo(MontonioClient.UNAVAILABLE);
                });
    }

    /** A URL path is only ever built from something that parsed as a UUID. */
    @Test
    void anOrderLookupRefusesAnythingThatIsNotAUuid() {
        assertThatThrownBy(() -> client.order("../stores/payment-methods"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
