package ee.shop.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A separate Lettuce client from Spring Data Redis's own connection factory
 * — Bucket4j's Redis integration needs a raw io.lettuce.core.RedisClient
 * with a byte[]-valued codec, which Spring's LettuceConnectionFactory
 * doesn't expose directly. Same Redis instance either way (same host/port/
 * password from application.yml), just a second lightweight connection to
 * it — rate-limit buckets are shared state across every app instance
 * (an in-JVM limiter is useless the moment you run two
 * containers").
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public ProxyManager<String> bucketProxyManager(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.password:}") String password) {

        RedisURI.Builder uri = RedisURI.Builder.redis(host, port);
        if (password != null && !password.isBlank()) {
            uri.withPassword(password.toCharArray());
        }

        RedisClient client = RedisClient.create(uri.build());
        StatefulRedisConnection<String, byte[]> connection =
                client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));

        return Bucket4jLettuce.casBasedBuilder(connection).build();
    }
}
