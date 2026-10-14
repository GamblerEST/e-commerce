package ee.shop.order.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import ee.shop.config.InvoiceProperties;
import ee.shop.order.model.DeliverySnapshot;
import ee.shop.order.model.Order;
import ee.shop.order.model.OrderLine;
import ee.shop.order.web.dto.OrderResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rendering only: no database, no disk. The collaborators generate() never
 * touches are left null on purpose, which also proves it stays that way.
 */
@Tag("unit")
class InvoicePdfGenerationTest {

    private final InvoiceProperties properties = new InvoiceProperties("unused",
            new InvoiceProperties.Seller("Estonian Shop OÜ", "16123456", "EE123456789",
                    "Pikk tn 1, 10123 Tallinn, Estonia", "hello@shop.ee", "+372 5555 5555"));

    private final InvoiceService service =
            new InvoiceService(null, null, null, null, null, properties);

    private static Order order(DeliverySnapshot delivery) {
        Order order = new Order(null, "key", "Mari Maasikas", "mari@example.ee", null,
                "Tartu mnt 5", "Apartment 12", "Tallinn", "10616", "EE", "EUR",
                new BigDecimal("83.87"), new BigDecimal("16.13"), new BigDecimal("100.00"), delivery);
        ReflectionTestUtils.setField(order, "id", UUID.fromString("3f2a9c1b-0000-4000-8000-000000000001"));
        return order;
    }

    private static List<OrderLine> lines(Order order) {
        return List.of(new OrderLine(order.getId(), UUID.randomUUID(), "Wool blanket", new BigDecimal("50.00"),
                new BigDecimal("0.2400"), 2, "EUR", new BigDecimal("100.00"), null));
    }

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder all = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(new PdfTextExtractor(reader).getTextFromPage(page)).append('\n');
            }
            return all.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    void producesAValidPdf() throws Exception {
        Order order = order(new DeliverySnapshot("OMNIVA", "Omniva parcel locker", new BigDecimal("3.99"), 2, 4));

        byte[] pdf = service.generate(order, lines(order));

        // Left in target/ (gitignored) so a person can open it and look.
        java.nio.file.Files.write(java.nio.file.Path.of("target", "invoice-sample.pdf"), pdf);
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        PdfReader reader = new PdfReader(pdf);
        assertThat(reader.getNumberOfPages()).isGreaterThanOrEqualTo(1);
        reader.close();
    }

    @Test
    void carriesTheReferenceTheAmountsAndBothParties() throws Exception {
        Order order = order(null);

        String text = text(service.generate(order, lines(order)));

        assertThat(text).contains(OrderResponse.referenceFor(order.getId()));
        assertThat(text).contains("Wool blanket", "24%", "50.00 EUR", "100.00 EUR");
        assertThat(text).contains("83.87 EUR", "16.13 EUR");
        assertThat(text).contains("Estonian Shop", "16123456", "EE123456789");
        assertThat(text).contains("Mari Maasikas", "Apartment 12", "10616 Tallinn");
    }

    @Test
    void aDeliveryMethodGetsItsOwnRow() throws Exception {
        Order order = order(new DeliverySnapshot("OMNIVA", "Omniva parcel locker", new BigDecimal("3.99"), 2, 4));

        String text = text(service.generate(order, lines(order)));

        assertThat(text).contains("Omniva parcel locker", "3.99 EUR");
    }

    @Test
    void amountsAreFormattedFromTheSnapshotNotRecalculated() {
        assertThat(InvoiceService.money(new BigDecimal("5"), "EUR")).isEqualTo("5.00 EUR");
        assertThat(InvoiceService.percent(new BigDecimal("0.2400"))).isEqualTo("24%");
        assertThat(InvoiceService.percent(new BigDecimal("0.0900"))).isEqualTo("9%");
        assertThat(InvoiceService.percent(new BigDecimal("0.0000"))).isEqualTo("0%");
    }
}
