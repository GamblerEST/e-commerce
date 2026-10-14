/**
 * Money and date formatting for an Estonian store.
 *
 * Prices arrive from the API as STRINGS, not numbers — the backend uses
 * BigDecimal and NUMERIC(12,2) precisely so money never touches a float
 *. Parsing to a JS number here is safe only because Intl needs
 * one and because a shop price is far inside the 2^53 range where doubles are
 * exact for two-decimal values; it is a DISPLAY conversion. Never do
 * arithmetic on the result — totals come from the server already summed.
 */

// et-EE renders "49,99 €" — comma decimal separator, symbol trailing, which is
// what an Estonian customer expects to see. Hardcoded rather than using the
// browser locale on purpose: the shop bills in one currency under one
// jurisdiction, and a German visitor seeing "49,99 €" is correct while an
// American visitor seeing "€49.99" would imply a different price convention.
const MONEY_LOCALE = 'et-EE';

export function formatMoney(amount: string | number, currency: string): string {
  const value = typeof amount === 'number' ? amount : Number(amount);

  if (!Number.isFinite(value)) {
    // Never silently render "NaN €" at a customer. Showing the raw value is
    // ugly but honest, and makes a bad payload obvious in a screenshot.
    return `${amount} ${currency}`;
  }

  try {
    return new Intl.NumberFormat(MONEY_LOCALE, {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  } catch {
    // Intl throws on an unrecognised currency code. A price must still render.
    return `${value.toFixed(2)} ${currency}`;
  }
}

/** Short, unambiguous date — "8. sept 2026". Avoids 03/04 day/month ambiguity. */
export function formatDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString(MONEY_LOCALE, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

/**
 * A delivery estimate as a RANGE, never a single date.
 *
 * Couriers quote ranges, and the delivery_options table stores
 * lead_time_days_min/max for that reason (V17). Collapsing it to one date
 * would be a promise the shop cannot keep — see the order confirmation page.
 */
export function formatDeliveryEstimate(minDays: number, maxDays: number, from = new Date()): string {
  const start = new Date(from);
  start.setDate(start.getDate() + minDays);
  const end = new Date(from);
  end.setDate(end.getDate() + maxDays);

  if (minDays === maxDays) {
    return formatDate(start.toISOString());
  }

  // Same month reads better as "10.–14. sept 2026" than two full dates.
  const sameMonth =
    start.getMonth() === end.getMonth() && start.getFullYear() === end.getFullYear();
  if (sameMonth) {
    return `${start.getDate()}.–${formatDate(end.toISOString())}`;
  }
  return `${formatDate(start.toISOString())} – ${formatDate(end.toISOString())}`;
}

/**
 * The colour an order's derived status should read as, shared by the
 * customer order pages and the admin order screen so the same word means the
 * same colour everywhere in the app.
 */
export type StatusTone = 'good' | 'bad' | 'warn' | 'neutral';

export function orderStatusTone(status: string): StatusTone {
  if (status === 'PAID' || status === 'SHIPPED' || status === 'DELIVERED') return 'good';
  if (status === 'CANCELLED' || status === 'PAYMENT_FAILED' || status === 'REFUNDED') return 'bad';
  if (status === 'CREATED') return 'warn';
  return 'neutral';
}
