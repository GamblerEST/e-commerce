import { useCallback, useEffect, useState } from 'react';
import { apiFetch, ApiRequestError, downloadFile } from '../../api/client';
import type { AdminOrderSummary, BillingDocumentView, OrderResponse } from '../../api/types';
import { formatDate, formatMoney, orderStatusTone } from '../../lib/format';
import { AdminError, AdminNotice, Button, Field, Pill, TableWrap, Td, Th, inputClass } from './ui';

const STATUSES = ['', 'CREATED', 'PAID', 'PAYMENT_FAILED', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED'];

// An order can only have a billing document once it was actually paid at some point.
const BILLED = ['PAID', 'SHIPPED', 'DELIVERED', 'REFUNDED'];

// A delivered order can still come back (a withdrawal, a fault), so it stays refundable.
const REFUNDABLE = ['PAID', 'SHIPPED', 'DELIVERED'];

/**
 * Order management — list, filter, inspect, mark shipped, refund, and the
 * order's billing documents.
 *
 * Note what is NOT here: editing an order or a billing document. Orders are
 * immutable financial records; status is DERIVED from an append-only
 * order_events stream, never updated in place. So every write this screen offers
 * appends something — an event, or a NEW billing document that leaves the earlier
 * ones exactly as issued — and there is deliberately no "edit" form to reach for.
 */
export default function AdminOrdersPage() {
  const [rows, setRows] = useState<AdminOrderSummary[]>([]);
  const [status, setStatus] = useState('');
  const [selected, setSelected] = useState<OrderResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [documents, setDocuments] = useState<BillingDocumentView[]>([]);

  const load = useCallback(async () => {
    const params = new URLSearchParams();
    if (status) params.set('status', status);
    setRows(await apiFetch<AdminOrderSummary[]>(`/admin/orders?${params}`));
  }, [status]);

  useEffect(() => {
    load().catch((e) => setError(m(e, 'Orders could not be loaded.')));
  }, [load]);

  async function open(id: string) {
    setError(null);
    try {
      setSelected(await apiFetch<OrderResponse>(`/admin/orders/${id}`));
      await loadDocuments(id);
    } catch (e) {
      setError(m(e, 'That order could not be opened.'));
    }
  }

  async function loadDocuments(id: string) {
    setDocuments(await apiFetch<BillingDocumentView[]>(`/admin/orders/${id}/billing-documents`));
  }

  async function issueBillingDocument(id: string) {
    setBusy(true);
    setError(null);
    try {
      const updated = await apiFetch<OrderResponse>(`/admin/orders/${id}/billing-documents/reissue`, {
        method: 'POST',
      });
      setSelected(updated);
      setNotice('New billing document issued and emailed to the customer. Earlier ones are kept.');
      await loadDocuments(id);
    } catch (e) {
      setError(m(e, 'The billing document could not be issued.'));
    } finally {
      setBusy(false);
    }
  }

  async function download(order: OrderResponse, doc: BillingDocumentView) {
    setError(null);
    try {
      await downloadFile(
        `/admin/orders/${order.id}/billing-documents/${doc.id}/download`,
        `invoice-${order.reference}-${doc.generatedAt.slice(0, 10)}.pdf`,
      );
    } catch (e) {
      setError(m(e, 'The billing document could not be downloaded.'));
    }
  }

  async function act(id: string, action: 'shipped' | 'delivered' | 'refund') {
    setBusy(true);
    setError(null);
    try {
      const updated = await apiFetch<OrderResponse>(`/admin/orders/${id}/${action}`, {
        method: 'POST',
      });
      setSelected(updated);
      setNotice(
        action === 'shipped'
          ? 'Order marked as shipped.'
          : action === 'delivered'
            ? 'Order marked as delivered.'
            : 'Refund issued through Stripe.',
      );
      await load();
    } catch (e) {
      setError(m(e, 'That action failed.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="orders-heading">
      <h2 id="orders-heading" className="mb-4 text-h3 font-extrabold text-ink">
        Orders
      </h2>

      <AdminError message={error} />
      <AdminNotice message={notice} />

      <div className="mb-4 max-w-xs">
        <Field label="Filter by status">
          <select
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            className={inputClass}
          >
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s || 'All statuses'}
              </option>
            ))}
          </select>
        </Field>
      </div>

      <TableWrap label="Orders">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr>
              <Th>Reference</Th>
              <Th>Customer</Th>
              <Th>Placed</Th>
              <Th>Delivery</Th>
              <Th>Total</Th>
              <Th>Status</Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <Td className="text-muted">No orders.</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
              </tr>
            )}
            {rows.map((o) => (
              <tr key={o.id}>
                <Td className="font-mono font-semibold whitespace-nowrap">{o.reference}</Td>
                <Td>
                  <span className="font-semibold text-ink">{o.customerName}</span>
                  <span className="block text-xs text-muted">{o.customerEmail}</span>
                </Td>
                <Td className="whitespace-nowrap">{formatDate(o.createdAt)}</Td>
                <Td className="whitespace-nowrap">{o.deliveryName ?? '—'}</Td>
                <Td className="whitespace-nowrap">{formatMoney(o.total, o.currency)}</Td>
                <Td>
                  <Pill tone={orderStatusTone(o.status)}>{o.status}</Pill>
                </Td>
                <Td className="text-right">
                  <Button onClick={() => open(o.id)}>View</Button>
                </Td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableWrap>

      {selected && (
        <section
          aria-labelledby="order-detail-heading"
          className="mt-8 rounded-card border border-hairline bg-surface p-6"
        >
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <h3 id="order-detail-heading" className="text-h4 font-bold text-ink">
              {selected.reference}{' '}
              <Pill tone={orderStatusTone(selected.status)}>{selected.status}</Pill>
            </h3>
            <span className="flex gap-2">
              <Button
                variant="primary"
                disabled={busy || selected.status !== 'PAID'}
                onClick={() => act(selected.id, 'shipped')}
                // Shipping an unpaid order is the classic costly mistake, so
                // the control is disabled rather than merely rejected later.
                title={
                  selected.status === 'PAID'
                    ? undefined
                    : 'Only a paid order can be marked shipped'
                }
              >
                Mark shipped
              </Button>
              <Button
                variant="primary"
                disabled={busy || selected.status !== 'SHIPPED'}
                onClick={() => act(selected.id, 'delivered')}
                title={
                  selected.status === 'SHIPPED'
                    ? undefined
                    : 'Only a shipped order can be marked delivered'
                }
              >
                Mark delivered
              </Button>
              <Button
                variant="danger"
                disabled={busy || !REFUNDABLE.includes(selected.status)}
                onClick={() => act(selected.id, 'refund')}
                title={
                  REFUNDABLE.includes(selected.status)
                    ? undefined
                    : 'Only a paid, shipped or delivered order can be refunded'
                }
              >
                Refund
              </Button>
              <Button
                disabled={busy || !BILLED.includes(selected.status)}
                onClick={() => issueBillingDocument(selected.id)}
                // Issuing sends a legal document to the customer, and only a
                // paid order was ever billed.
                title={
                  BILLED.includes(selected.status)
                    ? 'Creates a new billing document and emails it. Earlier ones are kept.'
                    : 'Only an order that was actually paid can have a billing document'
                }
              >
                Issue new billing document
              </Button>
              <Button onClick={() => setSelected(null)}>Close</Button>
            </span>
          </div>

          <div className="grid gap-6 sm:grid-cols-2">
            <div>
              <h4 className="mb-2 text-sm font-bold tracking-wide text-muted uppercase">
                Billing
              </h4>
              <address className="text-sm not-italic text-ink">
                {selected.billingName}
                <br />
                {selected.billingAddressLine1}
                <br />
                {selected.billingAddressLine2 && (
                  <>
                    {selected.billingAddressLine2}
                    <br />
                  </>
                )}
                {selected.billingPostalCode} {selected.billingCity}
                <br />
                {selected.billingCountry}
                <br />
                {selected.billingEmail}
                {selected.billingPhone && (
                  <>
                    <br />
                    {selected.billingPhone}
                  </>
                )}
              </address>
            </div>

            <div>
              <h4 className="mb-2 text-sm font-bold tracking-wide text-muted uppercase">
                History
              </h4>
              {/* The event stream IS the order's status. Showing it in full
                  means an admin can see why an order is in the state it is,
                  rather than a single word with no provenance. */}
              <ol className="list-none space-y-1 p-0 text-sm">
                {selected.events.map((e, i) => (
                  <li key={i} className="text-muted">
                    <strong className="text-ink">{e.type}</strong> — {formatDate(e.occurredAt)}
                  </li>
                ))}
              </ol>
            </div>
          </div>

          <h4 className="mt-6 mb-2 text-sm font-bold tracking-wide text-muted uppercase">Items</h4>
          <TableWrap label="Order lines">
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr>
                  <Th>Product</Th>
                  <Th>Unit</Th>
                  <Th>VAT</Th>
                  <Th>Qty</Th>
                  <Th>Line total</Th>
                </tr>
              </thead>
              <tbody>
                {selected.lines.map((l, i) => (
                  <tr key={i}>
                    {/* The SNAPSHOTTED name, not a live product lookup — an
                        order must describe what was actually bought even after
                        the product is renamed or retired. */}
                    <Td>{l.productName}</Td>
                    <Td className="whitespace-nowrap">{formatMoney(l.unitPrice, l.currency)}</Td>
                    <Td className="whitespace-nowrap">
                      {(Number(l.vatRate) * 100).toFixed(0)}%
                    </Td>
                    <Td>{l.quantity}</Td>
                    <Td className="whitespace-nowrap">{formatMoney(l.lineTotal, l.currency)}</Td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableWrap>

          <h4 className="mt-6 mb-2 text-sm font-bold tracking-wide text-muted uppercase">
            Billing documents
          </h4>
          <TableWrap label="Billing documents">
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr>
                  <Th>Issued</Th>
                  <Th>Issued by</Th>
                  <Th className="text-right">Actions</Th>
                </tr>
              </thead>
              <tbody>
                {documents.length === 0 && (
                  <tr>
                    <Td className="text-muted">
                      {BILLED.includes(selected.status)
                        ? 'None on file — issue one with the button above.'
                        : 'None yet — one is issued automatically once the order is paid.'}
                    </Td>
                    <Td>{''}</Td>
                    <Td>{''}</Td>
                  </tr>
                )}
                {documents.map((d) => (
                  <tr key={d.id}>
                    <Td className="whitespace-nowrap">{formatDate(d.generatedAt)}</Td>
                    <Td>{d.issuedBy}</Td>
                    <Td className="text-right">
                      <Button onClick={() => download(selected, d)}>Download PDF</Button>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableWrap>

          <dl className="mt-4 ml-auto grid max-w-xs grid-cols-2 gap-1 text-sm">
            <dt className="text-muted">Subtotal</dt>
            <dd className="m-0 text-right">{formatMoney(selected.subtotal, selected.currency)}</dd>
            <dt className="text-muted">VAT</dt>
            <dd className="m-0 text-right">{formatMoney(selected.vatTotal, selected.currency)}</dd>
            {selected.delivery && (
              <>
                <dt className="text-muted">{selected.delivery.name}</dt>
                <dd className="m-0 text-right">
                  {formatMoney(selected.delivery.price, selected.currency)}
                </dd>
              </>
            )}
            <dt className="font-bold text-ink">Total</dt>
            <dd className="m-0 text-right font-bold text-ink">
              {formatMoney(selected.total, selected.currency)}
            </dd>
          </dl>
        </section>
      )}
    </section>
  );
}

function m(e: unknown, fallback: string): string {
  return e instanceof ApiRequestError || e instanceof Error ? e.message : fallback;
}
