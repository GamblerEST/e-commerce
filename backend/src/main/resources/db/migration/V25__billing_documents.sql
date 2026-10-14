-- ─────────────────────────────────────────────────────────────────────────────
-- billing_documents — one row per invoice PDF actually generated for an
-- order. The first is written automatically the moment OrderService.markPaid
-- fires; any later ones come from AdminOrderService's admin-only "reissue"
-- action. A reissue always APPENDS a new row; nothing here is ever updated to
-- point at a replacement file — an invoice, once issued, is a legal record of
-- what was billed at that moment, not a draft to correct in place. Same rule
-- order_events already follows for order status (DECISIONS.md §1).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE billing_documents (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id              UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,

    -- Filename under app.invoice.storage-dir (InvoiceService) — not a public
    -- URL path like product_images.storage_key: billing documents are never
    -- served by a public static handler, only through the @StaffOnly
    -- download endpoint, so there is no route prefix to encode here.
    storage_key           TEXT NOT NULL,

    generated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NULL = issued automatically by OrderService.markPaid the moment the
    -- order was paid. Set = an admin's explicit "Reissue billing document"
    -- action. SET NULL if that admin's account is later removed — the same
    -- trade-off orders.user_id already accepts for guest checkouts: the
    -- document itself must survive, only the "who" is lost.
    generated_by_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT billing_documents_storage_key_not_blank CHECK (length(trim(storage_key)) > 0)
);

CREATE INDEX billing_documents_order_idx ON billing_documents (order_id, generated_at);

-- Same defence as order_events/audit_log: a trigger stops a bug in our own
-- code, a withheld privilege stops an attacker who has SQL execution as
-- shop_app. InvoiceService only ever INSERTs here, so this costs the
-- legitimate path nothing.
CREATE OR REPLACE FUNCTION billing_documents_is_append_only()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'billing_documents is append-only; % is not permitted', TG_OP;
END;
$$;

CREATE TRIGGER billing_documents_no_update
    BEFORE UPDATE OR DELETE ON billing_documents
    FOR EACH ROW EXECUTE FUNCTION billing_documents_is_append_only();

REVOKE UPDATE, DELETE, TRUNCATE ON billing_documents FROM shop_app;
