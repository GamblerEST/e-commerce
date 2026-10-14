import { useEffect, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { apiFetch, ApiRequestError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import type { OrderResponse } from '../api/types';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { formatDate, formatMoney, orderStatusTone } from '../lib/format';

export default function OrderDetailPage() {
  const { user, loading: authLoading } = useAuth();
  const { id } = useParams<{ id: string }>();
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  function load() {
    if (!id) return;
    apiFetch<OrderResponse>(`/orders/${id}`)
      .then(setOrder)
      .catch((err) => setError(err instanceof ApiRequestError && err.status === 404
        ? 'Order not found.'
        : 'Could not load this order. Is the backend running?'));
  }

  useEffect(() => {
    if (!user) return;
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user, id]);

  async function onCancel() {
    if (!id) return;
    setCancelling(true);
    setCancelError(null);
    try {
      const updated = await apiFetch<OrderResponse>(`/orders/${id}/cancel`, { method: 'POST' });
      setOrder(updated);
    } catch (err) {
      setCancelError(err instanceof ApiRequestError ? err.message : 'Could not cancel this order.');
    } finally {
      setCancelling(false);
    }
  }

  if (!authLoading && !user) {
    return <Navigate to="/login" replace />;
  }

  if (error) {
    return (
      <div className="mx-auto max-w-2xl">
        <p role="alert" className="rounded-field bg-danger-bg px-4 py-3 text-danger">
          {error}
        </p>
        <Link to="/orders" className="mt-4 inline-block font-semibold text-ink underline">
          Back to orders
        </Link>
      </div>
    );
  }

  if (!order) {
    return (
      <div className="mx-auto max-w-2xl">
        <p role="status" className="text-muted">Loading…</p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl">
      <Link to="/orders" className="text-sm font-semibold text-ink no-underline hover:underline">
        ← Back to orders
      </Link>
      {/* An order page is per-customer and transactional — never indexable. */}
      <PageMeta
        title={`Order ${order.reference} — Estonian Shop`}
        description="Your order summary, delivery estimate and reference number."
        noindex
      />

      {/* The human REFERENCE, not the raw UUID. The confirmation page needs
          a reference number a person can use, and "3f2a9c1b" is not
          something a customer can read down a phone to support. The full id
          stays available below for anyone who needs it. */}
      <h1 className="mt-3 text-h1 font-extrabold text-ink">Order {order.reference}</h1>
      <p className="mt-2">
        <Badge variant={orderStatusTone(order.status)}>{order.status}</Badge>
      </p>

      <dl className="mt-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 rounded-card border border-hairline bg-surface p-6 text-sm">
        <dt className="font-semibold text-muted">Order reference</dt>
        <dd className="m-0 font-bold text-ink">{order.reference}</dd>

        <dt className="font-semibold text-muted">Placed</dt>
        <dd className="m-0 text-ink">{formatDate(order.createdAt)}</dd>

        {order.delivery ? (
          <>
            <dt className="font-semibold text-muted">Delivery</dt>
            <dd className="m-0 text-ink">
              {order.delivery.name} — {formatMoney(order.delivery.price, order.currency)}
            </dd>

            <dt className="font-semibold text-muted">Estimated delivery</dt>
            {/* A RANGE, never a single date. Couriers quote ranges, and
                delivery_options stores min/max lead times precisely so the
                shop does not make a promise it cannot keep. */}
            <dd className="m-0 text-ink">
              {order.delivery.estimatedFrom === order.delivery.estimatedTo
                ? formatDate(order.delivery.estimatedFrom)
                : `${formatDate(order.delivery.estimatedFrom)} – ${formatDate(order.delivery.estimatedTo)}`}
            </dd>
          </>
        ) : (
          <>
            <dt className="font-semibold text-muted">Delivery</dt>
            <dd className="m-0 text-ink">No delivery method recorded for this order.</dd>
          </>
        )}

        <dt className="font-semibold text-muted">Order ID</dt>
        <dd className="m-0">
          <code className="text-xs text-muted">{order.id}</code>
        </dd>
      </dl>

      <h2 className="mt-8 text-h4 font-bold text-ink">Items</h2>
      <ul className="list-none rounded-card border border-hairline bg-surface p-6">
        {order.lines.map((line) => (
          <li
            key={line.productId ?? line.productName}
            className="flex justify-between gap-4 border-b border-hairline py-3 text-sm first:pt-0 last:border-b-0 last:pb-0"
          >
            <span className="text-ink">
              {line.productName} × {line.quantity} ({formatMoney(line.unitPrice, line.currency)} each, VAT{' '}
              {(Number(line.vatRate) * 100).toFixed(0)}%)
            </span>
            <span className="shrink-0 font-semibold text-ink">{formatMoney(line.lineTotal, line.currency)}</span>
          </li>
        ))}
      </ul>

      <div className="mt-4 rounded-card border border-hairline bg-surface p-6 text-sm">
        <p className="m-0 mb-2 flex justify-between text-muted">
          <span>Subtotal</span>
          <span>{formatMoney(order.subtotal, order.currency)}</span>
        </p>
        <p className="m-0 mb-2 flex justify-between text-muted">
          <span>VAT</span>
          <span>{formatMoney(order.vatTotal, order.currency)}</span>
        </p>
        <p className="m-0 flex justify-between border-t border-hairline pt-2 text-lg font-extrabold text-ink">
          <span>Total</span>
          <span>{formatMoney(order.total, order.currency)}</span>
        </p>
      </div>

      <h2 className="mt-8 text-h4 font-bold text-ink">Shipping address</h2>
      <address className="not-italic text-ink">
        {order.billingName}
        <br />
        {order.billingAddressLine1}
        {order.billingAddressLine2 ? <>, {order.billingAddressLine2}</> : null}
        <br />
        {order.billingCity} {order.billingPostalCode}, {order.billingCountry}
      </address>

      <h2 className="mt-8 text-h4 font-bold text-ink">History</h2>
      <ul className="list-none space-y-2 p-0 text-sm">
        {order.events.map((e, i) => (
          <li key={i} className="rounded-field border border-hairline bg-surface px-3 py-2 text-ink">
            <strong>{e.type}</strong> — {new Date(e.occurredAt).toLocaleString()}
          </li>
        ))}
      </ul>

      {order.status === 'CREATED' && (
        <div className="mt-8">
          {cancelError && (
            <p role="alert" className="mb-3 rounded-field bg-danger-bg px-4 py-3 text-danger">
              {cancelError}
            </p>
          )}
          <Button type="button" variant="secondary" onClick={onCancel} disabled={cancelling}>
            {cancelling ? 'Cancelling…' : 'Cancel order'}
          </Button>
        </div>
      )}
    </div>
  );
}
