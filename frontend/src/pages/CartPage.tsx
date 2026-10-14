import { Link } from 'react-router-dom';
import { ShoppingCart } from 'lucide-react';
import { resolveImageUrl } from '../api/client';
import { useCart } from '../cart/CartContext';
import type { CartItemResponse } from '../api/types';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';
import { formatMoney } from '../lib/format';

function CartLine({ item }: { item: CartItemResponse }) {
  const { setQuantity, removeItem } = useCart();

  return (
    <li className="flex flex-wrap items-center gap-4 border-b border-hairline py-5 first:pt-0 last:border-b-0 last:pb-0">
      <Link
        to={`/products/${item.slug}`}
        className="block h-20 w-20 shrink-0 overflow-hidden rounded-field bg-canvas"
      >
        {item.thumbnailUrl && (
          // Decorative: the product name sits right next to it as real text,
          // so alt text here would announce the same thing twice.
          <img
            src={resolveImageUrl(item.thumbnailUrl)}
            alt=""
            aria-hidden="true"
            className="h-full w-full object-cover"
          />
        )}
      </Link>

      <div className="min-w-40 flex-1">
        <Link
          to={`/products/${item.slug}`}
          className="font-semibold text-ink no-underline hover:underline"
        >
          {item.name}
        </Link>
        <p className="mt-1 text-sm text-muted">{formatMoney(item.unitPrice, item.currency)} each</p>
      </div>

      <div className="flex items-center gap-4">
        <label className="flex items-center gap-2 text-sm font-medium text-ink">
          Qty
          <input
            type="number"
            min={1}
            max={99}
            value={item.quantity}
            onChange={(e) => {
              const qty = Number(e.target.value);
              // Errors (e.g. stock ran out) surface via CartContext's shared
              // `error` — rendered above by CartPage — not here; this just
              // stops the rejection from going fully unhandled.
              if (qty >= 1 && qty <= 99) setQuantity(item.productId, qty).catch(() => {});
            }}
            className="w-16 rounded-field border border-hairline bg-canvas px-2 py-1.5 text-center"
          />
        </label>
        <Button
          type="button"
          variant="link"
          onClick={() => removeItem(item.productId).catch(() => {})}
          className="text-danger"
        >
          Remove
        </Button>
      </div>

      <p className="ml-auto w-24 text-right font-bold text-ink sm:ml-0">
        {formatMoney(item.lineTotal, item.currency)}
      </p>
    </li>
  );
}

export default function CartPage() {
  const { cart, loading, error, clear } = useCart();

  // Declared once and rendered on every branch: an empty cart, a loading
  // cart and a full cart are all the cart page, and all three need the
  // same title. Putting it only in the populated branch meant the page
  // fell back to the site-wide description whenever the cart was empty.
  const cartMeta = (
    <PageMeta
      title="Your cart — Estonian Shop"
      description="Review the items in your basket, change quantities or remove anything you have second thoughts about, then continue to checkout. Shipping added at checkout."
      noindex
    />
  );

  if (loading) {
    return (
      <div className="mx-auto max-w-3xl">
        {cartMeta}
        <p role="status" className="py-12 text-center text-muted">
          Loading your cart…
        </p>
      </div>
    );
  }

  if (cart.items.length === 0) {
    return (
      <div className="mx-auto max-w-3xl">
        {cartMeta}
        <h1 className="text-h1 font-extrabold text-ink">Your cart</h1>
        {error && (
          <p role="alert" className="mt-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
            {error}
          </p>
        )}
        <div className="mt-8 flex flex-col items-center gap-4 rounded-card border border-hairline bg-surface py-16 text-center">
          <ShoppingCart className="h-10 w-10 text-muted" aria-hidden="true" />
          <p className="text-lg text-muted">Your cart is empty.</p>
          <Button asChild>
            <Link to="/products">Browse products</Link>
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl">
      {cartMeta}
      <h1 className="text-h1 font-extrabold text-ink">Your cart</h1>
      {error && (
        <p role="alert" className="mt-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
          {error}
        </p>
      )}

      <ul className="mt-6 list-none rounded-card border border-hairline bg-surface p-6">
        {cart.items.map((item) => (
          <CartLine key={item.productId} item={item} />
        ))}
      </ul>

      <div className="mt-6 flex flex-wrap items-center justify-between gap-4">
        <Button type="button" variant="link" onClick={() => clear().catch(() => {})} className="text-muted">
          Clear cart
        </Button>
        <p className="text-right text-ink">
          <span className="block text-sm text-muted">Total (excluding shipping)</span>
          <span className="text-h4 font-extrabold">{formatMoney(cart.totalPrice, cart.currency)}</span>
        </p>
      </div>

      <Button asChild size="lg" className="mt-8 w-full">
        <Link to="/checkout">Proceed to checkout</Link>
      </Button>
    </div>
  );
}
