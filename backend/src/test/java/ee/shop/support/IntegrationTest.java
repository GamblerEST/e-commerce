package ee.shop.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests.
 *
 * Every integration test runs against a REAL PostgreSQL 16 and a REAL Redis,
 * started in Docker by Testcontainers. This is not gold-plating — the schema
 * depends on citext, INET, JSONB, partial indexes and plpgsql triggers, none of
 * which H2 implements faithfully. A test suite that is green against H2 tells
 * you nothing about whether the code works against the database you deploy.
 *
 * The containers are `static`, so JUnit starts them once for the whole suite
 * rather than once per test class. Startup cost is paid a single time (~2s).
 *
 * Flyway runs against these containers exactly as it does in production, which
 * means the migrations themselves are covered by every test that touches the DB.
 */
@SpringBootTest
@Testcontainers
public abstract class IntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("shop_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withReuse(true);

    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"))
                    .withReuse(true);

    /**
     * The Estonian address register, stood in for locally. Wired for every
     * integration test, not just the address ones: any checkout or address-book
     * test that sends a register id would otherwise call the real government
     * service.
     */
    protected static final GazetteerStub GAZETTEER = GazetteerStub.start();

    /**
     * Montonio, stood in for locally and switched on for every integration test:
     * bank links are part of the same application context the whole suite starts,
     * and nothing ever reaches Montonio's real API.
     */
    protected static final MontonioStub MONTONIO = MontonioStub.start();

    /**
     * Test-only password for shop_runtime; the real one is a Docker secret
     * (deploy/secrets/db_runtime_password). Set on the role by
     * R__runtime_role.sql via the Flyway placeholder wired below, so tests that
     * open their own connection as this role use the same value.
     */
    protected static final String RUNTIME_PASSWORD = "test-only-runtime-password";

    static {
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
    }

    /**
     * Wires the randomly-assigned container ports into Spring's Environment
     * before the context starts. Testcontainers maps to random host ports to
     * avoid collisions, so these cannot be hardcoded.
     *
     * Note we also supply the secrets that application.yml declares with no
     * default (JWT_SECRET, MAIL_*). Those have no defaults deliberately — the
     * app must refuse to boot without them in production — so tests have to
     * provide them explicitly. That is the intended friction.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("app.address-lookup.gazetteer-url", GAZETTEER::url);
        registry.add("app.montonio.enabled", () -> "true");
        registry.add("app.montonio.api-url", MONTONIO::url);
        registry.add("app.montonio.access-key", () -> MontonioStub.ACCESS_KEY);
        registry.add("app.montonio.secret-key", () -> MontonioStub.SECRET_KEY);
        // Refund tests run the reconciler themselves; a scheduled run must not race them.
        registry.add("app.montonio.refund-check-interval", () -> "PT1H");
        // A fixture shaped like a real frontend build, so page tests do not depend
        // on the frontend having been built first.
        registry.add("app.seo.index-html", () -> testResource("/seo/index.html"));

        // Tests connect as the SAME least-privileged role production uses.
        //
        // Running them as the container superuser would mean the suite proves
        // nothing about whether shop_app actually holds the privileges the code
        // needs — a missing GRANT would pass every test and fail on deploy. It
        // also means the audit-log tamper-proofing is exercised for real.
        //
        // Flyway keeps the superuser below, because migrations need DDL, and
        // R__runtime_role.sql is what gives shop_runtime this password.
        registry.add("spring.datasource.username", () -> "shop_runtime");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);

        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.runtime_password", () -> RUNTIME_PASSWORD);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        registry.add("app.jwt.secret",
                () -> "dGVzdC1vbmx5LXNlY3JldC1uZXZlci11c2VkLWluLXByb2QtMzJieXRlcw==");
        registry.add("app.security.totp.key",
                () -> "dGVzdC1vbmx5LXRvdHAta2V5LW5ldmVyLXVzZWQtaW4tcHJvZA==");
        // PII field-encryption key. Fixed rather than random per run so a
        // reused Testcontainers volume can still decrypt rows an earlier run
        // wrote — a random key would fail every read with "wrong key".
        registry.add("app.security.field.key",
                () -> "dGVzdC1vbmx5LWZpZWxkLWtleS1uZXZlci11c2VkLWluLXByb2Q=");
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.username", () -> "test");
        registry.add("spring.mail.password", () -> "test");

        // Tests that mock JavaMailSender (AccountControllerTest) replace it
        // with a plain mock of the interface, not a JavaMailSenderImpl.
        // Spring Boot's mail health indicator specifically needs the
        // concrete class to introspect host/port and fails context startup
        // ("Beans must not be empty") when it can't find one. Not a real
        // health signal worth wiring around in tests either way.
        registry.add("management.health.mail.enabled", () -> false);

        // Rate-limit buckets are keyed "route:ip" in the SAME Redis container
        // for the whole suite, and every MockMvc request shares one fake
        // client IP (127.0.0.1). Left at production defaults (register: 5/hr)
        // the very first handful of unrelated tests exhausts the bucket and
        // every later register()/login() call 429s regardless of what it's
        // actually testing. Generous test-only capacities restore isolation
        // between test methods; the real, small production defaults are what
        // RateLimitServiceTest exercises directly against this same Redis.
        registry.add("app.rate-limit.register.capacity", () -> "100000");
        registry.add("app.rate-limit.register.period", () -> "PT1S");
        registry.add("app.rate-limit.login.capacity", () -> "100000");
        registry.add("app.rate-limit.login.period", () -> "PT1S");
        registry.add("app.rate-limit.refresh.capacity", () -> "100000");
        registry.add("app.rate-limit.refresh.period", () -> "PT1S");
        registry.add("app.rate-limit.forgot-password.capacity", () -> "100000");
        registry.add("app.rate-limit.forgot-password.period", () -> "PT1S");
        registry.add("app.rate-limit.verify-email.capacity", () -> "100000");
        registry.add("app.rate-limit.verify-email.period", () -> "PT1S");
        registry.add("app.rate-limit.password-reset.capacity", () -> "100000");
        registry.add("app.rate-limit.password-reset.period", () -> "PT1S");
        registry.add("app.rate-limit.checkout.capacity", () -> "100000");
        registry.add("app.rate-limit.checkout.period", () -> "PT1S");
        registry.add("app.rate-limit.cart-mutation.capacity", () -> "100000");
        registry.add("app.rate-limit.cart-mutation.period", () -> "PT1S");
        registry.add("app.rate-limit.product-search.capacity", () -> "100000");
        registry.add("app.rate-limit.product-search.period", () -> "PT1S");

        // Production default (/app/data/product-images) only exists inside
        // the container — tests run directly on the host, so uploads need a
        // real, writable location here instead.
        registry.add("app.uploads.product-images-dir",
                () -> System.getProperty("java.io.tmpdir") + "/shop-test-product-images");

        // Same reasoning, for generated invoice PDFs. app.invoice.seller.*
        // needs no override — application.yml's defaults load fine here.
        registry.add("app.invoice.storage-dir",
                () -> System.getProperty("java.io.tmpdir") + "/shop-test-invoices");
    }

    private static String testResource(String name) {
        try {
            return java.nio.file.Path.of(IntegrationTest.class.getResource(name).toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
