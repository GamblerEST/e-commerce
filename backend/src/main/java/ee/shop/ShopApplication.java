package ee.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @ConfigurationPropertiesScan picks up our @ConfigurationProperties records
 * (JwtProperties, Argon2Properties, TaxProperties) without needing @Component
 * on each — keeps them as immutable records rather than mutable beans.
 *
 * @EnableScheduling is needed for the expired-token and expired-reservation
 * cleanup jobs.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
