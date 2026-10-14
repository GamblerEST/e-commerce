import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { CartProvider, useCart } from './CartContext';
import { apiFetch } from '../api/client';

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>();
  return { ...actual, apiFetch: vi.fn(), onAccessTokenChange: vi.fn(() => () => {}) };
});

const mockedFetch = vi.mocked(apiFetch);

function cartWith(count: number) {
  return {
    items: Array.from({ length: count }, (_, i) => ({ productId: `p${i}` })),
    itemCount: count,
    totalPrice: '0.00',
    currency: 'EUR',
  };
}

function Probe() {
  const { cart, loadError, refresh } = useCart();
  return (
    <div>
      <p data-testid="count">{cart.items.length}</p>
      {loadError && <p role="alert">{loadError}</p>}
      <button onClick={() => refresh()}>reload</button>
    </div>
  );
}

describe('CartProvider', () => {
  beforeEach(() => mockedFetch.mockReset());

  it('keeps the newest response when an older one arrives last', async () => {
    let resolveFirst: (v: unknown) => void = () => {};
    mockedFetch
      .mockReturnValueOnce(new Promise((r) => (resolveFirst = r)) as never)
      .mockResolvedValueOnce(cartWith(2) as never);

    render(
      <CartProvider>
        <Probe />
      </CartProvider>,
    );
    // The mount load is still in flight. A reload starts later and finishes first.
    await act(async () => {
      screen.getByText('reload').click();
    });
    expect(screen.getByTestId('count').textContent).toBe('2');

    // The older mount response arrives last with an empty cart. It must not win.
    await act(async () => resolveFirst(cartWith(0)));
    expect(screen.getByTestId('count').textContent).toBe('2');
  });

  it('keeps the cart you had when a reload fails, and reports the failure', async () => {
    mockedFetch
      .mockResolvedValueOnce(cartWith(1) as never)
      .mockRejectedValueOnce(new Error('network down'));

    render(
      <CartProvider>
        <Probe />
      </CartProvider>,
    );
    await screen.findByText('1');

    await act(async () => {
      screen.getByText('reload').click();
    });
    expect(screen.getByTestId('count').textContent).toBe('1');
    expect(screen.getByRole('alert').textContent).toBe('Your cart could not be loaded.');
  });
});
