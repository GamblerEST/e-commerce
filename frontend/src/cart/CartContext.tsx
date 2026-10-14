import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { apiFetch, ApiRequestError, onAccessTokenChange } from '../api/client';
import type { CartResponse } from '../api/types';

const EMPTY_CART: CartResponse = { items: [], itemCount: 0, totalPrice: '0.00', currency: 'EUR' };

interface CartContextValue {
  cart: CartResponse;
  loading: boolean;
  /** A failed mutation (e.g. stock ran out between browsing and adding) — cleared automatically on the next attempt, or manually via clearError. */
  error: string | null;
  /** The last load of the cart failed. The cart shown is the last one that loaded, not an empty one. */
  loadError: string | null;
  clearError: () => void;
  refresh: () => Promise<void>;
  addItem: (productId: string, quantity: number) => Promise<void>;
  setQuantity: (productId: string, quantity: number) => Promise<void>;
  removeItem: (productId: string) => Promise<void>;
  clear: () => Promise<void>;
}

const CartContext = createContext<CartContextValue | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  const [cart, setCart] = useState<CartResponse>(EMPTY_CART);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  // Every cart write takes a number when it starts, and only the newest one may
  // apply its result. Without this, a slow earlier response (a refresh started
  // before sign-in finished, say) can land after a newer one and show a stale
  // cart, including an empty one, on the checkout page.
  const latestWrite = useRef(0);

  async function refresh() {
    const ticket = ++latestWrite.current;
    try {
      const next = await apiFetch<CartResponse>('/cart');
      if (ticket === latestWrite.current) {
        setCart(next);
        setLoadError(null);
      }
    } catch (err) {
      // Keep the cart we already have. A failed load is not a cart with nothing
      // in it, and showing it as empty sends a customer away from a cart that is
      // still there.
      if (ticket === latestWrite.current) {
        setLoadError(err instanceof ApiRequestError ? err.message : 'Your cart could not be loaded.');
      }
    }
  }

  useEffect(() => {
    refresh().finally(() => setLoading(false));

    // A login/register/OAuth callback merges the guest cart into the
    // account's cart server-side (AuthController.mergeGuestCart) — refetch
    // whenever the access token changes so that merge shows up immediately
    // instead of only after the next unrelated cart action. Also covers
    // logout, where the next request is anonymous again.
    return onAccessTokenChange(() => {
      refresh();
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A cart mutation can fail for reasons entirely outside the user's
  // control (stock sold out between browsing and clicking, a stale item
  // already removed elsewhere) — every caller must see a real message, not
  // a silently-swallowed or unhandled-rejection failure. Re-throws after
  // recording the message so a caller with its own local state (e.g.
  // ProductDetailPage's "Adding…" button state) can still react too.
  async function mutate(action: () => Promise<CartResponse>) {
    setError(null);
    const ticket = ++latestWrite.current;
    try {
      const next = await action();
      if (ticket === latestWrite.current) setCart(next);
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : 'Something went wrong. Please try again.');
      throw err;
    }
  }

  async function addItem(productId: string, quantity: number) {
    await mutate(() => apiFetch<CartResponse>('/cart/items', { method: 'POST', body: { productId, quantity } }));
  }

  async function setQuantity(productId: string, quantity: number) {
    await mutate(() => apiFetch<CartResponse>(`/cart/items/${productId}`, { method: 'PATCH', body: { quantity } }));
  }

  async function removeItem(productId: string) {
    await mutate(() => apiFetch<CartResponse>(`/cart/items/${productId}`, { method: 'DELETE' }));
  }

  async function clear() {
    setError(null);
    const ticket = ++latestWrite.current;
    try {
      await apiFetch<void>('/cart', { method: 'DELETE' });
      if (ticket === latestWrite.current) setCart(EMPTY_CART);
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : 'Something went wrong. Please try again.');
      throw err;
    }
  }

  return (
    <CartContext.Provider
      value={{
        cart,
        loading,
        error,
        loadError,
        clearError: () => setError(null),
        refresh,
        addItem,
        setQuantity,
        removeItem,
        clear,
      }}
    >
      {children}
    </CartContext.Provider>
  );
}

export function useCart(): CartContextValue {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error('useCart must be used within CartProvider');
  return ctx;
}
