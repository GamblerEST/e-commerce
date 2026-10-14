package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the built frontend's index.html is, for the backend to fill in per page
 * (see ee.shop.seo.web.SeoPageController).
 *
 * In Docker the frontend build is mounted into the backend read-only. If the
 * file cannot be read, public pages answer 503 and the proxy serves the plain
 * static app instead, so a missing build degrades the pages crawlers see and
 * never the pages people see.
 */
@ConfigurationProperties(prefix = "app.seo")
public record SeoProperties(String indexHtml) {}
