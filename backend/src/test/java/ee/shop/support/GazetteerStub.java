package ee.shop.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for the Estonian address register (In-AKS gazetteer),
 * answering with responses captured from the real service (see
 * src/test/resources/gazetteer), minus the register's internal code-address
 * fields, which the shop never reads. Every other field is kept exactly as the
 * register sent it, including the many the shop ignores, so these responses
 * still prove the client tolerates unknown fields. The data is the public
 * address register and contains no personal data.
 *
 * No test calls the real register. It is a public government service, and a
 * suite that depends on it fails whenever that service is slow or down, which
 * teaches everyone to ignore a red build.
 */
public final class GazetteerStub {

    private final HttpServer server;
    private final List<String> rawQueries = new CopyOnWriteArrayList<>();

    private GazetteerStub(HttpServer server) {
        this.server = server;
    }

    public static GazetteerStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            GazetteerStub stub = new GazetteerStub(server);
            server.createContext("/gazetteer", stub::answer);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/gazetteer";
    }

    /** Every query string received, still percent-encoded exactly as it was sent. */
    public List<String> rawQueries() {
        return rawQueries;
    }

    public void clearQueries() {
        rawQueries.clear();
    }

    public void stop() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String raw = exchange.getRequestURI().getRawQuery();
        rawQueries.add(raw == null ? "" : raw);
        byte[] body = fixture(fixtureFor(parse(raw)));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String fixtureFor(Map<String, String> params) {
        String adsOid = params.get("adsoid");
        if (adsOid != null) {
            return switch (adsOid) {
                case "EE00658009" -> "adsoid-EE00658009.json";
                case "CU00230197" -> "adsoid-CU00230197.json";
                default -> "search-nomatch.json";
            };
        }
        return switch (params.getOrDefault("address", "")) {
            case "Pikk 1" -> "search-pikk-1.json";
            case "Männiku talu" -> "search-mannikutalu.json";
            case "Ülikooli 18 Tartu" -> "search-ylikooli-tartu.json";
            case "Mustamäe tee 5-12" -> "search-mustamae-5-12.json";
            default -> "search-nomatch.json";
        };
    }

    /**
     * Decodes a query string while keeping a literal '+' as '+'.
     *
     * URLDecoder alone turns '+' into a space, which would let a test pass for a
     * client that sent '+' where it meant a space. Keeping it literal means a
     * test can tell whether the client encoded spaces and plus signs
     * unambiguously (%20 and %2B) or left them to the server to guess.
     */
    public static Map<String, String> parse(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = GazetteerStub.class.getResourceAsStream("/gazetteer/" + name)) {
            if (in == null) {
                throw new IOException("missing test fixture /gazetteer/" + name);
            }
            return in.readAllBytes();
        }
    }
}
