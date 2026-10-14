package ee.shop.identity.service;

import com.sun.net.httpserver.HttpServer;
import ee.shop.config.RecaptchaProperties;
import ee.shop.identity.exception.RecaptchaVerificationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RecaptchaService's ENABLED path (a real HTTP call to Google's siteverify,
 * parsing the response, enforcing the score threshold) has zero coverage
 * anywhere else in the suite — every other test runs with a blank secret
 * (disabled). A local mock HTTP server stands in for Google here so this
 * actually executes rather than staying permanently untested until Google's
 * real endpoint is reached in production.
 */
@Tag("security")
class RecaptchaServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RecaptchaService serviceWithResponse(String json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/siteverify", exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        RecaptchaProperties props = new RecaptchaProperties(
                "test-secret", "http://localhost:" + server.getAddress().getPort() + "/siteverify", 0.5);
        return new RecaptchaService(props, RestClient.builder());
    }

    @Test
    void successfulVerification_doesNotThrow() throws IOException {
        RecaptchaService service = serviceWithResponse("""
                {"success": true, "score": 0.9}""");

        assertThatCode(() -> service.verify("a-real-looking-token", "1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void googleReportsFailure_throwsRecaptchaVerificationException() throws IOException {
        RecaptchaService service = serviceWithResponse("""
                {"success": false}""");

        assertThatThrownBy(() -> service.verify("bad-token", "1.2.3.4"))
                .isInstanceOf(RecaptchaVerificationException.class);
    }

    @Test
    void scoreBelowThreshold_throwsRecaptchaVerificationException() throws IOException {
        RecaptchaService service = serviceWithResponse("""
                {"success": true, "score": 0.1}""");

        assertThatThrownBy(() -> service.verify("bot-like-token", "1.2.3.4"))
                .isInstanceOf(RecaptchaVerificationException.class);
    }

    /** v2 checkbox responses have no score field — judged on success alone. */
    @Test
    void successWithNoScore_isTreatedAsV2AndPasses() throws IOException {
        RecaptchaService service = serviceWithResponse("""
                {"success": true}""");

        assertThatCode(() -> service.verify("v2-token", "1.2.3.4")).doesNotThrowAnyException();
    }

    /**
     * Regression test: Google's REAL siteverify response includes fields
     * this app doesn't read (challenge_ts, error-codes) — this app's global
     * spring.jackson.deserialization.fail-on-unknown-properties=true
     * (application.yml, added for OUR OWN inbound request DTOs) would
     * otherwise reject this exact shape and make every real registration
     * fail with "reCAPTCHA verification call failed", discovered live
     * against Google's actual test-key response, not a hypothetical.
     * SiteVerifyResponse's own @JsonIgnoreProperties(ignoreUnknown = true)
     * is what has to keep this passing.
     */
    @Test
    void extraUnrecognizedFieldsInGooglesRealResponse_doNotBreakParsing() throws IOException {
        RecaptchaService service = serviceWithResponse("""
                {"success": true, "challenge_ts": "2026-08-18T12:00:00Z", "hostname": "localhost", "error-codes": []}""");

        assertThatCode(() -> service.verify("v2-token", "1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void blankToken_throwsWithoutEvenCallingGoogle() throws IOException {
        RecaptchaService service = serviceWithResponse("""
                {"success": true, "score": 0.9}""");

        assertThatThrownBy(() -> service.verify("", "1.2.3.4"))
                .isInstanceOf(RecaptchaVerificationException.class);
        assertThatThrownBy(() -> service.verify(null, "1.2.3.4"))
                .isInstanceOf(RecaptchaVerificationException.class);
    }

    /**
     * A Google outage must fail the request, not silently waive
     * verification (RecaptchaService's own javadoc) — proven here by
     * pointing at a port nothing is listening on.
     */
    @Test
    void unreachableEndpoint_failsClosed() {
        RecaptchaProperties props = new RecaptchaProperties("test-secret", "http://localhost:1/siteverify", 0.5);
        RecaptchaService service = new RecaptchaService(props, RestClient.builder());

        assertThatThrownBy(() -> service.verify("token", "1.2.3.4"))
                .isInstanceOf(RecaptchaVerificationException.class);
    }

    @Test
    void disabledService_neverCallsGoogleAtAll() {
        RecaptchaProperties props = new RecaptchaProperties("", "http://localhost:1/siteverify", 0.5);
        RecaptchaService service = new RecaptchaService(props, RestClient.builder());

        // Port 1 refuses connections; if verify() actually attempted the
        // call, this would throw. A blank secret must short-circuit first.
        assertThatCode(() -> service.verify("anything", "1.2.3.4")).doesNotThrowAnyException();
    }
}
