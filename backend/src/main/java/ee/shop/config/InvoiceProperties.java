package ee.shop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where invoice PDFs are stored, and the shop's own billing identity that
 * gets printed on every one.
 *
 * Seller fields deliberately duplicate frontend/src/companyInfo.ts's COMPANY
 * object — there is no code-sharing between the TS frontend and this
 * backend, so keep the two in sync BY HAND; nothing enforces that
 * automatically.
 */
@ConfigurationProperties(prefix = "app.invoice")
public record InvoiceProperties(String storageDir, Seller seller) {

    public record Seller(String name, String registryCode, String vatNumber,
                          String address, String email, String phone) {}
}
