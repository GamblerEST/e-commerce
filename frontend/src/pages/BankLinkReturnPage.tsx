import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiFetch } from '../api/client';
import type { BankLinkReturnResponse } from '../api/types';
import { useCart } from '../cart/CartContext';
import PageMeta from '../components/PageMeta';
import { formatDate, formatMoney } from '../lib/format';

/**
 * Where a customer lands after paying at their bank.
 *
 * The bank sends them back with ?order-token=…, Montonio's signed payment result.
 * The server verifies it and settles the order exactly as the webhook would; this
 * page only reports what the token says. Guests land here too, which is why the
 * response carries the reference and items but no name, email or address.
 */
export default function BankLinkReturnPage() {
  const [params] = useSearchParams();
  // Kept in a ref so it survives the address bar being cleared below.
  const token = useRef(params.get('order-token'));
  const [state, setState] = useState<'checking' | 'done' | 'error'>('checking');
  const [result, setResult] = useState<BankLinkReturnResponse | null>(null);
  const { refresh: refreshCart } = useCart();

  useEffect(() => {
    // Out of the address bar straight away. The token is a payment result that can
    // carry the payer's name and account number; it does not belong in the browser
    // history, a bookmark, or a screenshot.
    window.history.replaceState(null, '', window.location.pathname);

    if (!token.current) {
      setState('error');
      return;
    }
    apiFetch<BankLinkReturnResponse>('/payments/bank-link/return', {
      method: 'POST',
      body: { orderToken: token.current },
    })
      .then((r) => {
        setResult(r);
        setState('done');
        refreshCart().catch(() => {});
      })
      .catch(() => setState('error'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const meta = (
    <PageMeta
      title="Payment result — Estonian Shop"
      description="The result of your bank payment for your Estonian Shop order."
      noindex
    />
  );

  if (state === 'checking') {
    return (
      <div>
        {meta}
        <p role="status" className="text-muted">Confirming your payment…</p>
      </div>
    );
  }

  if (state === 'error' || !result) {
    return (
      <div>
        {meta}
        <h1 className="text-h1 font-extrabold text-ink">We could not confirm this payment</h1>
        <p className="mt-4 text-ink">
          If money has left your account, your order updates on its own once the bank confirms it. If
          not, your items are still available to order again.
        </p>
        <Link to="/products" className="mt-6 inline-block font-semibold text-accent underline">
          Continue shopping
        </Link>
      </div>
    );
  }

  const heading =
    result.status === 'PAID'
      ? 'Payment received'
      : result.status === 'FAILED'
        ? 'The payment was not completed'
        : 'Your payment is being confirmed';

  return (
    <div>
      {meta}
      <h1 className="text-h1 font-extrabold text-ink">{heading}</h1>
      <p role="status" className="mt-4 text-ink">
        {result.status === 'PAID'
          ? 'Thank you, your bank has confirmed the payment.'
          : result.status === 'FAILED'
            ? 'No money was taken. You can place the order again whenever you are ready.'
            : 'Your bank has not confirmed the payment yet. The order updates on its own as soon as it does.'}
      </p>
      <dl className="mt-6 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 rounded-card border border-hairline p-4">
        <dt className="text-muted">Order reference</dt>
        <dd>
          <strong>{result.reference}</strong>
        </dd>
        <dt className="text-muted">Total</dt>
        <dd>{formatMoney(result.total, result.currency)}</dd>
        {result.delivery && (
          <>
            <dt className="text-muted">Estimated delivery</dt>
            <dd>
              {result.delivery.estimatedFrom === result.delivery.estimatedTo
                ? formatDate(result.delivery.estimatedFrom)
                : `${formatDate(result.delivery.estimatedFrom)} – ${formatDate(result.delivery.estimatedTo)}`}
            </dd>
          </>
        )}
      </dl>
      <ul className="mt-4 space-y-1 text-ink">
        {result.lines.map((line) => (
          <li key={line.productName}>
            <span>
              {line.productName} × {line.quantity}
            </span>
          </li>
        ))}
      </ul>
      <Link to="/products" className="mt-6 inline-block font-semibold text-accent underline">
        Continue shopping
      </Link>
    </div>
  );
}
