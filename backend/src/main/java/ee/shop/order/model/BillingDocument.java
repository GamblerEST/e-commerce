package ee.shop.order.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One invoice PDF actually generated for an order (InvoiceService). Never
 * updated after being written — see V25__billing_documents.sql. A reissue is
 * always a new row, never an edit of an existing one.
 */
@Entity
@Table(name = "billing_documents")
public class BillingDocument {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /** Filename under app.invoice.storage-dir, never client-supplied. */
    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt = Instant.now();

    /** Null = issued automatically by OrderService.markPaid, not an admin. */
    @Column(name = "generated_by_user_id")
    private UUID generatedByUserId;

    protected BillingDocument() {} // JPA

    public BillingDocument(UUID orderId, String storageKey, UUID generatedByUserId) {
        this.orderId = orderId;
        this.storageKey = storageKey;
        this.generatedByUserId = generatedByUserId;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public String getStorageKey() { return storageKey; }
    public Instant getGeneratedAt() { return generatedAt; }
    public UUID getGeneratedByUserId() { return generatedByUserId; }
}
