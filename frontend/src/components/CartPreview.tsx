import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ShoppingCart, X } from 'lucide-react';
import { useCart } from '../cart/CartContext';
import { resolveImageUrl } from '../api/client';
import { formatMoney } from '../lib/format';

/**
 * Quick cart preview — a pop-up preview of cart contents, on every page.
 *
 * A disclosure button + panel rather than a hover menu. Hover menus are
 * unusable on touch (no hover state) and unreachable by keyboard unless the
 * hover behaviour is duplicated in JS anyway — a click/Enter toggle works
 * everywhere for free.
 */
export default function CartPreview() {
  const { cart, removeItem } = useCart();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;

    function onDocClick(e: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setOpen(false);
        // Escape must return focus to the trigger, or a keyboard user is
        // dumped at the top of the document with no idea where they are.
        buttonRef.current?.focus();
      }
    }
    document.addEventListener('mousedown', onDocClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const items = cart.items.slice(0, 4);
  const hidden = cart.items.length - items.length;

  return (
    <div ref={wrapRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        aria-haspopup="dialog"
        // The count is in the accessible name, so a screen reader hears
        // "Cart, 3 items" rather than just "Cart" with a visual-only badge.
        aria-label={`Cart, ${cart.itemCount} ${cart.itemCount === 1 ? 'item' : 'items'}`}
        className="relative inline-flex items-center gap-2 rounded-field px-3 py-2 font-semibold text-ink hover:bg-canvas"
      >
        <ShoppingCart className="h-5 w-5" aria-hidden="true" />
        <span className="hidden sm:inline">Cart</span>
        {cart.itemCount > 0 && (
          <span
            aria-hidden="true"
            className="inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-accent px-1.5 text-xs font-bold text-on-accent"
          >
            {cart.itemCount}
          </span>
        )}
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="Cart preview"
          className="absolute top-full right-0 z-50 mt-2 w-80 rounded-card border border-hairline bg-surface p-4 shadow-xl"
        >
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-base font-bold text-ink">Your cart</h2>
            <button
              type="button"
              onClick={() => {
                setOpen(false);
                buttonRef.current?.focus();
              }}
              aria-label="Close cart preview"
              className="rounded-field p-1 text-muted hover:bg-canvas"
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </button>
          </div>

          {cart.items.length === 0 ? (
            <p className="py-4 text-center text-sm text-muted">Your cart is empty.</p>
          ) : (
            <>
              <ul className="list-none space-y-3 p-0">
                {items.map((item) => (
                  <li key={item.productId} className="flex items-center gap-3">
                    <img
                      src={
                        item.thumbnailUrl
                          ? resolveImageUrl(item.thumbnailUrl)
                          : '/favicon.svg'
                      }
                      alt=""
                      /* Decorative: the product name is right beside it as
                         text, so alt text here would be read twice. */
                      aria-hidden="true"
                      className="h-12 w-12 shrink-0 rounded-field bg-canvas object-cover"
                    />
                    <div className="min-w-0 flex-1">
                      <Link
                        to={`/products/${item.slug}`}
                        onClick={() => setOpen(false)}
                        className="block truncate text-sm font-semibold text-ink"
                      >
                        {item.name}
                      </Link>
                      <p className="text-xs text-muted">
                        {item.quantity} × {formatMoney(item.unitPrice, item.currency)}
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => removeItem(item.productId)}
                      aria-label={`Remove ${item.name} from cart`}
                      className="rounded-field p-1 text-muted hover:bg-canvas hover:text-danger"
                    >
                      <X className="h-4 w-4" aria-hidden="true" />
                    </button>
                  </li>
                ))}
              </ul>

              {hidden > 0 && (
                <p className="mt-3 text-xs text-muted">
                  and {hidden} more {hidden === 1 ? 'item' : 'items'}
                </p>
              )}

              <div className="mt-4 flex items-baseline justify-between border-t border-hairline pt-3">
                <span className="text-sm font-semibold text-ink">Total</span>
                <span className="text-lg font-extrabold text-ink">
                  {formatMoney(cart.totalPrice, cart.currency)}
                </span>
              </div>
              {/* Shipping is excluded here by design: the method is not
                  chosen until checkout, so the cart can only honestly show a
                  total EXCLUDING it. */}
              <p className="mt-1 text-xs text-muted">Shipping calculated at checkout.</p>

              <div className="mt-4 flex gap-2">
                <Link
                  to="/cart"
                  onClick={() => setOpen(false)}
                  className="flex-1 rounded-field border border-hairline px-3 py-2 text-center text-sm font-bold text-ink"
                >
                  View cart
                </Link>
                <Link
                  to="/checkout"
                  onClick={() => setOpen(false)}
                  className="flex-1 rounded-field bg-accent px-3 py-2 text-center text-sm font-bold text-on-accent"
                >
                  Checkout
                </Link>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
