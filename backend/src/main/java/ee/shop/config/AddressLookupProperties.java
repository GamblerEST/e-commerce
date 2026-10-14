package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The Estonian address register lookup: Maa- ja Ruumiamet's public In-AKS
 * gazetteer.
 *
 * There is no key and no account; the service is open. The URL is still
 * configuration rather than a constant, for two reasons. Tests point it at a
 * local stand-in, so the suite never depends on a government service being up.
 * And the service has already moved once (In-ADS became In-AKS on 27 April 2026,
 * with the old host retired at the end of July 2026), so the next move should be
 * a configuration change rather than a release.
 *
 * A blank URL switches the lookup off; the forms then take typed addresses only.
 */
@ConfigurationProperties(prefix = "app.address-lookup")
public record AddressLookupProperties(String gazetteerUrl, Duration timeout) {

    public boolean isEnabled() {
        return gazetteerUrl != null && !gazetteerUrl.isBlank();
    }
}
