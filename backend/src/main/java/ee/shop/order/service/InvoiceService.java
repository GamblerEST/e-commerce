package ee.shop.order.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import ee.shop.common.audit.AuditService;
import ee.shop.common.error.AppException;
import ee.shop.config.InvoiceProperties;
import ee.shop.order.model.BillingDocument;
import ee.shop.order.model.Order;
import ee.shop.order.model.OrderEvent;
import ee.shop.order.model.OrderLine;
import ee.shop.order.repository.BillingDocumentRepository;
import ee.shop.order.repository.OrderEventRepository;
import ee.shop.order.repository.OrderLineRepository;
import ee.shop.order.repository.OrderRepository;
import ee.shop.order.web.dto.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Invoice PDFs for paid orders.
 *
 * A billing document is a legal record of what was billed at one moment, so it
 * is never edited: a "correction" is a NEW document (reissue), and every
 * earlier one stays exactly as it was issued. Each one is a row in
 * billing_documents plus an INVOICE_ISSUED order event, so it also shows up in
 * the order's timeline.
 *
 * Amounts are read straight off the order's own purchase-time snapshot
 * (unit price, VAT rate per line, totals). They are never re-derived from live
 * product prices or from TaxProperties: re-pricing a historical order from
 * today's configuration is exactly what the snapshot rule exists to prevent
 * (DECISIONS.md §2).
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8);

    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final BillingDocumentRepository billingDocuments;
    private final OrderEventRepository orderEvents;
    private final AuditService audit;
    private final InvoiceProperties properties;
    private final Path storageDir;

    public InvoiceService(OrderRepository orders, OrderLineRepository orderLines,
                          BillingDocumentRepository billingDocuments, OrderEventRepository orderEvents,
                          AuditService audit, InvoiceProperties properties) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.billingDocuments = billingDocuments;
        this.orderEvents = orderEvents;
        this.audit = audit;
        this.properties = properties;
        this.storageDir = Path.of(properties.storageDir());
    }

    /**
     * The invoice issued automatically when an order is paid. Never throws:
     * a payment succeeding matters far more than a PDF existing, and this runs
     * inside the payment-confirmation transaction. Rendering and the disk write
     * happen first, so the realistic failures (a full or unwritable volume) never
     * touch that transaction at all. If anything fails the problem is logged and
     * audited and the caller sends its email without an attachment; an admin can
     * then issue the document by hand with {@link #reissue}.
     */
    public Optional<BillingDocument> issueForPayment(UUID orderId) {
        try {
            return Optional.of(issue(orderId, null));
        } catch (Exception e) {
            // Class name only: a message can carry file paths or order data.
            log.warn("Could not issue an invoice for order {}: {}", orderId, e.getClass().getSimpleName());
            audit.record("invoice.generation_failed", null, "order " + orderId, null, null);
            return Optional.empty();
        }
    }

    /**
     * An admin's explicit reissue. Unlike the payment-time path, failures
     * propagate: someone who clicked the button needs to know it did not work.
     */
    @Transactional
    public BillingDocument reissue(UUID orderId, UUID adminUserId) throws IOException {
        return issue(orderId, adminUserId);
    }

    public byte[] readFile(BillingDocument document) throws IOException {
        return Files.readAllBytes(storageDir.resolve(document.getStorageKey()));
    }

    private BillingDocument issue(UUID orderId, UUID issuedByUserId) throws IOException {
        Order order = orders.findById(orderId).orElseThrow(() -> AppException.notFound("Order not found"));
        List<OrderLine> lines = orderLines.findByOrderIdOrderByCreatedAt(orderId);
        byte[] pdf = generate(order, lines);

        // Random name, never anything a client supplied.
        String filename = UUID.randomUUID() + ".pdf";
        Files.createDirectories(storageDir);
        Files.write(storageDir.resolve(filename), pdf);

        BillingDocument saved = billingDocuments.save(new BillingDocument(orderId, filename, issuedByUserId));
        orderEvents.save(new OrderEvent(orderId, OrderEvent.Type.INVOICE_ISSUED));
        return saved;
    }

    /** Pure rendering: no I/O beyond the in-memory buffer, no database. */
    byte[] generate(Order order, List<OrderLine> lines) {
        String currency = order.getCurrency();
        InvoiceProperties.Seller seller = properties.seller();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(new Paragraph("Invoice", TITLE));
            doc.add(new Paragraph("Order " + OrderResponse.referenceFor(order.getId()), BOLD));
            doc.add(new Paragraph("Issued " + LocalDate.now(ZoneOffset.UTC)
                    + " · order placed " + order.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), SMALL));
            doc.add(spacer());

            PdfPTable parties = new PdfPTable(2);
            parties.setWidthPercentage(100);
            parties.addCell(partyCell("Seller", List.of(
                    seller.name(),
                    seller.address(),
                    "Registry code: " + seller.registryCode(),
                    "VAT number: " + seller.vatNumber(),
                    seller.email() + " · " + seller.phone())));
            parties.addCell(partyCell("Billed to", billedTo(order)));
            doc.add(parties);
            doc.add(spacer());

            PdfPTable items = new PdfPTable(new float[] {5f, 1.2f, 2f, 1.4f, 2f});
            items.setWidthPercentage(100);
            for (String heading : List.of("Item", "Qty", "Unit price", "VAT", "Total")) {
                items.addCell(headerCell(heading));
            }
            for (OrderLine line : lines) {
                items.addCell(bodyCell(line.getProductName(), Element.ALIGN_LEFT));
                items.addCell(bodyCell(String.valueOf(line.getQuantity()), Element.ALIGN_RIGHT));
                items.addCell(bodyCell(money(line.getUnitPrice(), line.getCurrency()), Element.ALIGN_RIGHT));
                items.addCell(bodyCell(percent(line.getVatRate()), Element.ALIGN_RIGHT));
                items.addCell(bodyCell(money(line.getLineTotal(), line.getCurrency()), Element.ALIGN_RIGHT));
            }
            if (order.getDeliveryName() != null && order.getDeliveryPrice() != null) {
                items.addCell(bodyCell("Delivery: " + order.getDeliveryName(), Element.ALIGN_LEFT));
                items.addCell(bodyCell("1", Element.ALIGN_RIGHT));
                items.addCell(bodyCell(money(order.getDeliveryPrice(), currency), Element.ALIGN_RIGHT));
                items.addCell(bodyCell("", Element.ALIGN_RIGHT));
                items.addCell(bodyCell(money(order.getDeliveryPrice(), currency), Element.ALIGN_RIGHT));
            }
            doc.add(items);
            doc.add(spacer());

            // subtotal is NET and total is gross; delivery is already folded into
            // both (OrderService.createOrder), so it is not added again here.
            PdfPTable totals = new PdfPTable(new float[] {6f, 3f});
            totals.setWidthPercentage(45);
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totals.addCell(totalLabel("Total excluding VAT"));
            totals.addCell(totalValue(money(order.getSubtotal(), currency), false));
            totals.addCell(totalLabel("VAT"));
            totals.addCell(totalValue(money(order.getVatTotal(), currency), false));
            totals.addCell(totalLabel("Total"));
            totals.addCell(totalValue(money(order.getTotal(), currency), true));
            doc.add(totals);

            doc.add(spacer());
            doc.add(new Paragraph("Prices include VAT at the rate shown against each line, as it was when the "
                    + "order was placed. This document is a record of that order; if it is ever reissued, the "
                    + "earlier version stays on file.", SMALL));
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the invoice", e);
        } finally {
            if (doc.isOpen()) {
                doc.close();
            }
        }
        return out.toByteArray();
    }

    private static List<String> billedTo(Order order) {
        java.util.ArrayList<String> rows = new java.util.ArrayList<>();
        rows.add(order.getBillingName());
        rows.add(order.getBillingAddressLine1());
        if (order.getBillingAddressLine2() != null && !order.getBillingAddressLine2().isBlank()) {
            rows.add(order.getBillingAddressLine2());
        }
        rows.add(order.getBillingPostalCode() + " " + order.getBillingCity());
        rows.add(order.getBillingCountry());
        rows.add(order.getBillingEmail());
        return rows;
    }

    static String money(BigDecimal amount, String currency) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " " + currency;
    }

    static String percent(BigDecimal rate) {
        BigDecimal pct = rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
        if (pct.scale() < 0) {
            pct = pct.setScale(0);
        }
        return pct.toPlainString() + "%";
    }

    private static Paragraph spacer() {
        Paragraph p = new Paragraph(" ", SMALL);
        p.setSpacingAfter(4);
        return p;
    }

    private static PdfPCell partyCell(String heading, List<String> rows) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(4);
        cell.addElement(new Paragraph(heading.toUpperCase(), SMALL));
        for (String row : rows) {
            cell.addElement(new Paragraph(row == null ? "" : row, BODY));
        }
        return cell;
    }

    private static PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BOLD));
        cell.setPadding(5);
        cell.setBackgroundColor(new java.awt.Color(238, 238, 238));
        cell.setHorizontalAlignment("Item".equals(text) ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
        return cell;
    }

    private static PdfPCell bodyCell(String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY));
        cell.setPadding(5);
        cell.setHorizontalAlignment(align);
        return cell;
    }

    private static PdfPCell totalLabel(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(3);
        return cell;
    }

    private static PdfPCell totalValue(String text, boolean strong) {
        PdfPCell cell = new PdfPCell(new Phrase(text, strong ? BOLD : BODY));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(3);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }
}
