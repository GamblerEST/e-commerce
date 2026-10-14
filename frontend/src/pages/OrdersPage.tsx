import { useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { apiFetch } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import type { OrderListResponse } from '../api/types';
import PageMeta from '../components/PageMeta';
import { Badge } from '../components/ui/badge';
import { formatDate, formatMoney, orderStatusTone } from '../lib/format';

// Mirrors ee.shop.order.model.OrderEvent.Type — the derived status values an
// order can actually show (OrderController's ?status= filter matches one of these).
const STATUSES = [
  'CREATED', 'PAID', 'PAYMENT_FAILED', 'CANCELLED', 'SHIPPED', 'DELIVERED', 'REFUND_REQUESTED', 'REFUNDED',
];

const FIELD_CLASS = 'rounded-field border border-hairline bg-surface px-3 py-2 text-sm text-ink';

export default function OrdersPage() {
  const { user, loading: authLoading } = useAuth();
  const [orders, setOrders] = useState<OrderListResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  useEffect(() => {
    if (!user) return;
    const params = new URLSearchParams();
    if (status) params.set('status', status);
    // Date inputs give "YYYY-MM-DD" — OrderController's ?from/?to expect a
    // full ISO date-time, so widen to the start/end of that calendar day.
    if (from) params.set('from', `${from}T00:00:00Z`);
    if (to) params.set('to', `${to}T23:59:59Z`);
    const query = params.toString();
    const url = query ? `/orders?${query}` : '/orders';

    apiFetch<OrderListResponse>(url)
      .then(setOrders)
      .catch(() => setError('Could not load orders. Is the backend running?'));
  }, [user, status, from, to]);

  if (!authLoading && !user) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="mx-auto max-w-4xl">
      <PageMeta
        title="Your orders — Estonian Shop"
        description="Every order you have placed with Estonian Shop, with its current status, what it contained and what it cost. Filter by status or by the date it was placed."
        noindex
      />
      <h1 className="text-h1 font-extrabold text-ink">Your orders</h1>

      <div className="mt-6 mb-6 flex flex-wrap gap-3">
        <label className="flex flex-col gap-1 text-xs font-semibold text-muted">
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value)} className={FIELD_CLASS}>
            <option value="">All statuses</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs font-semibold text-muted">
          From
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            className={FIELD_CLASS}
          />
        </label>
        <label className="flex flex-col gap-1 text-xs font-semibold text-muted">
          To
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            className={FIELD_CLASS}
          />
        </label>
      </div>

      {error && (
        <p role="alert" className="mb-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
          {error}
        </p>
      )}

      {!error && !orders && <p className="text-muted">Loading…</p>}

      {orders && orders.items.length === 0 && (
        <div className="rounded-card border border-hairline bg-surface py-12 text-center">
          <p className="text-muted">
            {status || from || to ? 'No orders match these filters.' : "You haven't placed any orders yet."}
          </p>
          <Link to="/products" className="mt-2 inline-block font-semibold text-ink underline">
            Browse products
          </Link>
        </div>
      )}

      {orders && orders.items.length > 0 && (
        <ul className="list-none space-y-3 p-0">
          {orders.items.map((o) => (
            <li key={o.id}>
              <Link
                to={`/orders/${o.id}`}
                className="grid grid-cols-2 items-center gap-3 rounded-card border border-hairline bg-surface p-4 text-ink no-underline transition hover:-translate-y-0.5 hover:shadow-md sm:grid-cols-[auto_auto_1fr_auto_auto]"
              >
                <span className="font-mono font-semibold">Order {o.id.slice(0, 8)}</span>
                <Badge variant={orderStatusTone(o.status)}>{o.status}</Badge>
                <span className="text-sm text-muted sm:text-right">
                  {o.itemCount} item{o.itemCount === 1 ? '' : 's'}
                </span>
                <span className="font-semibold">{formatMoney(o.total, o.currency)}</span>
                <span className="text-sm text-muted">{formatDate(o.createdAt)}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
