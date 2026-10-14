import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { loadStripe, type Stripe } from '@stripe/stripe-js';
import { Elements, PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js';
import { apiFetch, ApiRequestError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useCart } from '../cart/CartContext';
import type {
  CheckoutPrefillResponse,
  CheckoutRequest,
  DeliveryOptionResponse,
  OrderResponse,
} from '../api/types';
import { formatDeliveryEstimate, formatMoney } from '../lib/format';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';
import AddressLookup from '../components/AddressLookup';

const STRIPE_KEY = import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY;
// Loaded once at module scope, not per-render/per-mount — loadStripe()
// fetches Stripe.js itself, and Stripe's own guidance is to call it exactly
// once and reuse the promise.
const stripePromise: Promise<Stripe | null> | null = STRIPE_KEY ? loadStripe(STRIPE_KEY) : null;

const FIELD_CLASS = 'w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink';
const CARD_CLASS = 'rounded-card border border-hairline bg-surface p-6';

function OrderSummary({ order }: { order: OrderResponse }) {
  return (
    <>
      <ul className="list-none space-y-2 p-0">
        {order.lines.map((line) => (
          <li key={line.productId} className="flex justify-between gap-4 text-sm text-ink">
            <span>
              {line.productName} × {line.quantity}
            </span>
            <span className="shrink-0 font-semibold">{formatMoney(line.lineTotal, line.currency)}</span>
          </li>
        ))}
      </ul>
      <div className="mt-4 border-t border-hairline pt-4 text-sm">
        <p className="m-0 mb-2 flex justify-between text-muted">
          <span>Subtotal</span>
          <span>{formatMoney(order.subtotal, order.currency)}</span>
        </p>
        <p className="m-0 mb-2 flex justify-between text-muted">
          <span>VAT</span>
          <span>{formatMoney(order.vatTotal, order.currency)}</span>
        </p>
        <p className="m-0 flex justify-between text-lg font-extrabold text-ink">
          <span>Total</span>
          <span>{formatMoney(order.total, order.currency)}</span>
        </p>
      </div>
      <p className="mt-4 text-sm text-muted">
        Shipping to: {order.billingAddressLine1}
        {order.billingAddressLine2 ? `, ${order.billingAddressLine2}` : ''}, {order.billingCity}{' '}
        {order.billingPostalCode}, {order.billingCountry}
      </p>
    </>
  );
}

/**
 * Card entry never touches our server (PCI scope stays off us).
 * allow_redirects=NEVER on the backend PaymentIntent means confirmPayment()
 * always resolves in-page with a result, never navigates away, so there's
 * no return_url page to build for this stage.
 */
function PaymentStep({ order, onPaid }: { order: OrderResponse; onPaid: () => void }) {
  const stripe = useStripe();
  const elements = useElements();
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!stripe || !elements) return;
    setError(null);
    setConfirming(true);

    const { error: submitError } = await elements.submit();
    if (submitError) {
      setError(submitError.message ?? 'Please check your payment details.');
      setConfirming(false);
      return;
    }

    const { error: confirmError } = await stripe.confirmPayment({
      elements,
      redirect: 'if_required',
    });

    if (confirmError) {
      // Card declined / insufficient funds / gateway error — the PaymentIntent
      // itself stays open, so the shopper can just try a different card
      // without us creating a second order.
      setError(confirmError.message ?? 'Payment failed. Please try again or use a different card.');
      setConfirming(false);
      return;
    }

    // Stripe confirmed synchronously — the webhook -> queue -> order_events
    // path will still land PAID moments later, but the
    // shopper doesn't need to wait on that round trip to see confirmation.
    onPaid();
  }

  return (
    <form onSubmit={onSubmit} className={CARD_CLASS}>
      {/* Link is Stripe's own separate saved-card identity — out of scope
          for this stage's guest/account model, and its inline signup
          prompt (email/mobile/name) would otherwise appear unprompted the
          moment a card number is typed. */}
      <PaymentElement options={{ wallets: { link: 'never' } }} />
      {error && (
        <p role="alert" className="mt-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
          {error}
        </p>
      )}
      <Button type="submit" disabled={!stripe || confirming} className="mt-6 w-full">
        {confirming ? 'Confirming payment…' : `Pay ${formatMoney(order.total, order.currency)}`}
      </Button>
    </form>
  );
}

export default function CheckoutPage() {
  const { user } = useAuth();
  const { cart, loading: cartLoading, loadError: cartLoadError, refresh: refreshCart } = useCart();

  const [billingName, setBillingName] = useState(user?.displayName ?? '');
  const [billingEmail, setBillingEmail] = useState(user?.email ?? '');
  const [billingPhone, setBillingPhone] = useState('');
  const [addressLine1, setAddressLine1] = useState('');
  const [addressLine2, setAddressLine2] = useState('');
  const [city, setCity] = useState('');
  const [postalCode, setPostalCode] = useState('');
  const [country, setCountry] = useState('EE');
  // Set only when the address came from the register and has not been edited
  // since; any manual change clears it, so the server checks exactly what was picked.
  const [adsOid, setAdsOid] = useState<string | null>(null);

  // Delivery options come from the server, never a hardcoded list: an admin can
  // add, reprice or retire one, and a client-side copy would quietly disagree.
  const [deliveryOptions, setDeliveryOptions] = useState<DeliveryOptionResponse[]>([]);
  const [deliveryCode, setDeliveryCode] = useState('');

  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [order, setOrder] = useState<OrderResponse | null>(null);
  const [paid, setPaid] = useState(false);

  // One idempotency key per checkout ATTEMPT (this page mount), not per
  // submit click — a retry after a network hiccup or a 5xx must resend the
  // SAME key, or the server has no way to tell it apart from a second,
  // genuine order.
  const idempotencyKeyRef = useRef(crypto.randomUUID());

  // Convenience only — a returning customer shouldn't have to retype an
  // address we already have on their last order. Never overwrites anything
  // the shopper has already typed (only fills currently-blank fields), and
  // silently no-ops for guests or first-time buyers (no prior order / 204).
  // Delivery options load for EVERYONE, so this is its own effect rather than
  // living inside the address-prefill effect below — that one returns early for
  // guests, and guest checkout is supported. Sharing it meant a guest saw no
  // shipping methods at all and could place an order with no delivery.
  useEffect(() => {
    let cancelled = false;
    apiFetch<DeliveryOptionResponse[]>('/delivery-options')
      .then((options) => {
        if (cancelled) return;
        setDeliveryOptions(options);
        // Preselect the cheapest rather than leaving nothing chosen. An
        // unselected radio group means the customer can submit an order with
        // no delivery method, which is how orders ended up with no shipping
        // cost and no estimated delivery date at all.
        const cheapest = [...options].sort((a, b) => Number(a.price) - Number(b.price))[0];
        if (cheapest) setDeliveryCode((current) => current || cheapest.code);
      })
      .catch(() => {
        if (!cancelled) setDeliveryOptions([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;

    apiFetch<CheckoutPrefillResponse>('/orders/last-address')
      .then((prefill) => {
        if (cancelled || !prefill) return;
        setBillingPhone((v) => v || prefill.billingPhone || '');
        setAddressLine1((v) => v || prefill.addressLine1 || '');
        setAddressLine2((v) => v || prefill.addressLine2 || '');
        setCity((v) => v || prefill.city || '');
        setPostalCode((v) => v || prefill.postalCode || '');
        setCountry((v) => (v && v !== 'EE' ? v : prefill.country || v));
      })
      .catch(() => {}); // no prior order, or not logged in — leave the form blank
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      const req: CheckoutRequest = {
        billingName,
        billingEmail,
        billingPhone,
        addressLine1,
        addressLine2: addressLine2 || null,
        city,
        postalCode,
        country,
        deliveryCode: deliveryCode || null,
        adsOid,
      };
      const result = await apiFetch<OrderResponse>('/checkout', {
        method: 'POST',
        body: req,
        headers: { 'Idempotency-Key': idempotencyKeyRef.current },
      });
      setOrder(result);
      await refreshCart(); // now empty — updates the navbar badge
      // No PaymentIntent means Stripe isn't configured in this environment
      // (see StripeProperties.isEnabled) — the order still exists, just
      // without a payment step to confirm.
      if (!result.paymentClientSecret) setPaid(true);
    } catch (err) {
      if (err instanceof ApiRequestError) {
        setError(err.message);
        if (err.errors) setFieldErrors(err.errors);
      } else {
        setError('Something went wrong. Is the backend running?');
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (order && paid) {
    return (
      <div className="mx-auto max-w-md">
        <h1 className="text-h1 font-extrabold text-ink">Order placed</h1>
        <p className="mt-2 text-muted">
          {order.status === 'PAID'
            ? 'Payment confirmed — thank you for your order!'
            : order.paymentClientSecret
              ? 'Payment is still being confirmed. You will get an email when it is.'
              : 'Your order has been received. No payment step is set up on this server, so it stays unpaid.'}
        </p>
        <div className={`mt-6 ${CARD_CLASS}`}>
          <OrderSummary order={order} />
        </div>
        <Link to="/products" className="mt-6 inline-block font-semibold text-ink underline">
          Continue shopping
        </Link>
      </div>
    );
  }

  // Rendered on every branch — payment step, loading, empty cart and the
  // form itself are all the checkout page. Only the populated branch had
  // it before, so an empty cart fell back to the site-wide description.
  const checkoutMeta = (
    <PageMeta
      title="Checkout — Estonian Shop"
      description="Complete your order: enter a delivery address, choose a shipping method and pay securely by card. Card details go straight to Stripe, never to our servers."
      noindex
    />
  );

  if (order && order.paymentClientSecret) {
    return (
      <div>
        {checkoutMeta}
        <h1 className="text-h1 font-extrabold text-ink">Payment</h1>
        <div className="mt-6 grid grid-cols-1 items-start gap-8 lg:grid-cols-2">
          {stripePromise ? (
            <Elements
              stripe={stripePromise}
              options={{ clientSecret: order.paymentClientSecret, appearance: { theme: 'stripe' } }}
            >
              <PaymentStep order={order} onPaid={() => setPaid(true)} />
            </Elements>
          ) : (
            <p role="alert" className="rounded-field bg-danger-bg px-4 py-3 text-danger">
              Payments are not configured in this environment.
            </p>
          )}
          <div className={CARD_CLASS}>
            <h2 className="mt-0 mb-4 text-h4 font-bold text-ink">Order summary</h2>
            <OrderSummary order={order} />
          </div>
        </div>
      </div>
    );
  }

  const selectedDelivery = deliveryOptions.find((o) => o.code === deliveryCode) ?? null;
  // Kept as a string through BigDecimal-safe arithmetic would be ideal, but this
  // is a DISPLAY total only — the server re-computes the authoritative figure
  // from the cart and the chosen delivery option, and that is what gets charged.
  const orderTotal = (
    Number(cart.totalPrice) + Number(selectedDelivery?.price ?? 0)
  ).toFixed(2);

  if (cartLoading) {
    return (
      <div>
        {checkoutMeta}
        <p role="status" className="text-muted">Loading…</p>
      </div>
    );
  }

  // A failed load must not read as an empty cart: the customer's items may still be
  // there, so offer a retry instead of sending them to browse products.
  if (cart.items.length === 0 && cartLoadError) {
    return (
      <div>
        {checkoutMeta}
        <h1 className="text-h1 font-extrabold text-ink">Checkout</h1>
        <p role="alert" className="mt-4 text-danger">We could not load your cart. {cartLoadError}</p>
        <Button variant="secondary" className="mt-4" onClick={() => refreshCart()}>
          Try again
        </Button>
      </div>
    );
  }

  if (cart.items.length === 0) {
    return (
      <div>
        {checkoutMeta}
        <h1 className="text-h1 font-extrabold text-ink">Checkout</h1>
        <p className="mt-4 text-muted">Your cart is empty.</p>
        <Link to="/products" className="mt-4 inline-block font-semibold text-ink underline">
          Browse products
        </Link>
      </div>
    );
  }

  return (
    <div>
      {checkoutMeta}
      <h1 className="text-h1 font-extrabold text-ink">Checkout</h1>
      <div className="mt-6 grid grid-cols-1 items-start gap-8 lg:grid-cols-2">
        <form onSubmit={onSubmit} className={CARD_CLASS}>
          <label className="mb-3 block text-sm font-semibold text-ink">
            Full name
            <input
              value={billingName}
              onChange={(e) => setBillingName(e.target.value)}
              required
              autoComplete="name"
              className={`mt-1 ${FIELD_CLASS}`}
            />
            {fieldErrors.billingName && (
              <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.billingName}</span>
            )}
          </label>
          <label className="mb-3 block text-sm font-semibold text-ink">
            Email
            <input
              type="email"
              value={billingEmail}
              onChange={(e) => setBillingEmail(e.target.value)}
              required
              autoComplete="email"
              className={`mt-1 ${FIELD_CLASS}`}
            />
            {fieldErrors.billingEmail && (
              <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.billingEmail}</span>
            )}
          </label>
          <label className="mb-3 block text-sm font-semibold text-ink">
            Phone
            <input
              type="tel"
              value={billingPhone}
              onChange={(e) => setBillingPhone(e.target.value)}
              minLength={5}
              maxLength={30}
              placeholder="+372 5555 5555"
              required
              autoComplete="tel"
              className={`mt-1 ${FIELD_CLASS}`}
            />
            {fieldErrors.billingPhone && (
              <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.billingPhone}</span>
            )}
          </label>
          {country === 'EE' && (
            <div className="mb-3">
              <AddressLookup
                onSelect={(s) => {
                  setAddressLine1(s.line1);
                  // A resolved apartment replaces it; an unresolved one leaves
                  // whatever was already typed there alone, rather than wiping
                  // a manual note the register has no way to know about.
                  if (s.apartment) setAddressLine2(s.apartment);
                  setCity(s.city);
                  setPostalCode(s.postalCode);
                  setCountry(s.country);
                  setAdsOid(s.adsOid);
                }}
              />
            </div>
          )}
          {adsOid && (
            <p role="status" className="mb-3 text-sm text-muted">
              Matched in the Estonian address register.
            </p>
          )}
          <label className="mb-3 block text-sm font-semibold text-ink">
            Street address
            <input
              value={addressLine1}
              onChange={(e) => {
                setAddressLine1(e.target.value);
                setAdsOid(null);
              }}
              required
              autoComplete="address-line1"
              className={`mt-1 ${FIELD_CLASS}`}
            />
            {fieldErrors.addressLine1 && (
              <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.addressLine1}</span>
            )}
          </label>
          <label className="mb-3 block text-sm font-semibold text-ink">
            Apartment, suite, etc. (optional)
            <input
              value={addressLine2}
              onChange={(e) => setAddressLine2(e.target.value)}
              autoComplete="address-line2"
              className={`mt-1 ${FIELD_CLASS}`}
            />
          </label>
          <div className="mb-3 flex flex-wrap gap-3 [&>label]:min-w-32 [&>label]:flex-1">
            <label className="block text-sm font-semibold text-ink">
              City
              <input
                value={city}
                onChange={(e) => {
                  setCity(e.target.value);
                  setAdsOid(null);
                }}
                required
                autoComplete="address-level2"
                className={`mt-1 ${FIELD_CLASS}`}
              />
              {fieldErrors.city && <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.city}</span>}
            </label>
            <label className="block text-sm font-semibold text-ink">
              Postal code
              <input
                value={postalCode}
                onChange={(e) => {
                  setPostalCode(e.target.value);
                  setAdsOid(null);
                }}
                required
                autoComplete="postal-code"
                className={`mt-1 ${FIELD_CLASS}`}
              />
              {fieldErrors.postalCode && (
                <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.postalCode}</span>
              )}
            </label>
            <label className="block text-sm font-semibold text-ink">
              Country
              <input
                value={country}
                maxLength={2}
                onChange={(e) => {
                  setCountry(e.target.value.toUpperCase());
                  setAdsOid(null);
                }}
                required
                autoComplete="country"
                className={`mt-1 ${FIELD_CLASS}`}
              />
              {fieldErrors.country && (
                <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.country}</span>
              )}
            </label>
          </div>
          {deliveryOptions.length > 0 && (
            <fieldset className="mt-4 rounded-card border border-hairline p-4">
              <legend className="px-1 text-sm font-bold text-ink">Delivery method</legend>
              {deliveryOptions.map((option) => (
                <label
                  key={option.code}
                  className="flex cursor-pointer items-center gap-3 rounded-field p-2 hover:bg-canvas"
                >
                  <input
                    type="radio"
                    name="deliveryCode"
                    value={option.code}
                    checked={deliveryCode === option.code}
                    onChange={() => setDeliveryCode(option.code)}
                    className="shrink-0"
                  />
                  <span className="flex flex-1 flex-col">
                    <span className="font-semibold text-ink">
                      {option.name}
                      {option.carrier && <span className="text-sm font-normal text-muted"> · {option.carrier}</span>}
                    </span>
                    {/* The estimate a customer actually cares about: a date
                        range, not "1-3 days" arithmetic they have to do. */}
                    <span className="text-sm text-muted">
                      Estimated {formatDeliveryEstimate(option.leadTimeDaysMin, option.leadTimeDaysMax)}
                    </span>
                  </span>
                  <span className="shrink-0 font-bold text-ink">{formatMoney(option.price, option.currency)}</span>
                </label>
              ))}
              {fieldErrors.deliveryCode && (
                <span className="mt-1 block text-sm text-danger">{fieldErrors.deliveryCode}</span>
              )}
            </fieldset>
          )}
          {error && (
            <p role="alert" className="mt-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
              {error}
            </p>
          )}
          <Button type="submit" disabled={submitting} className="mt-6 w-full">
            {submitting ? 'Placing order…' : `Place order — ${formatMoney(orderTotal, cart.currency)}`}
          </Button>
        </form>

        <div className={CARD_CLASS}>
          <h2 className="mt-0 mb-4 text-h4 font-bold text-ink">Order summary</h2>
          <ul className="list-none space-y-2 p-0">
            {cart.items.map((item) => (
              <li key={item.productId} className="flex justify-between gap-4 text-sm text-ink">
                <span>
                  {item.name} × {item.quantity}
                </span>
                <span className="shrink-0 font-semibold">{formatMoney(item.lineTotal, item.currency)}</span>
              </li>
            ))}
          </ul>
          <div className="mt-4 border-t border-hairline pt-4 text-sm">
            <p className="m-0 mb-2 flex justify-between text-muted">
              <span>Items</span>
              <span>{formatMoney(cart.totalPrice, cart.currency)}</span>
            </p>
            <p className="m-0 mb-2 flex justify-between text-muted">
              <span>{selectedDelivery ? selectedDelivery.name : 'Delivery'}</span>
              <span>
                {selectedDelivery ? formatMoney(selectedDelivery.price, selectedDelivery.currency) : '—'}
              </span>
            </p>
            <p className="m-0 flex justify-between text-lg font-extrabold text-ink">
              <span>Total</span>
              <span>{formatMoney(orderTotal, cart.currency)}</span>
            </p>
          </div>
          <p className="mt-4 text-sm text-muted">VAT breakdown is confirmed on the order once placed.</p>
        </div>
      </div>
    </div>
  );
}
