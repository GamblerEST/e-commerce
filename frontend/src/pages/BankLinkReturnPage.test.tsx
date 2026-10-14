import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BankLinkReturnPage from './BankLinkReturnPage';
import { apiFetch } from '../api/client';
import type { BankLinkReturnResponse } from '../api/types';

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  apiFetch: vi.fn(),
}));
vi.mock('../cart/CartContext', () => ({
  useCart: () => ({ refresh: vi.fn().mockResolvedValue(undefined) }),
}));

const mockApiFetch = vi.mocked(apiFetch);

const PAID: BankLinkReturnResponse = {
  reference: 'ORD-3F2A9C1B',
  status: 'PAID',
  total: '12.90',
  currency: 'EUR',
  lines: [{ productName: 'Rannamõisa Mug', quantity: 1 }],
  delivery: null,
};

function renderAt(url: string) {
  render(
    <MemoryRouter initialEntries={[url]}>
      <BankLinkReturnPage />
    </MemoryRouter>,
  );
}

describe('BankLinkReturnPage', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/checkout/bank-link/return?order-token=signed.result.token');
  });

  it('hands the token to the server and shows the confirmed order', async () => {
    mockApiFetch.mockResolvedValue(PAID);

    renderAt('/checkout/bank-link/return?order-token=signed.result.token');

    expect(await screen.findByRole('heading', { name: 'Payment received' })).toBeInTheDocument();
    expect(screen.getByText('ORD-3F2A9C1B')).toBeInTheDocument();
    expect(mockApiFetch).toHaveBeenCalledWith('/payments/bank-link/return', {
      method: 'POST',
      body: { orderToken: 'signed.result.token' },
    });
  });

  // The token can carry the payer's name and account number.
  it('removes the token from the address bar', async () => {
    mockApiFetch.mockResolvedValue(PAID);

    renderAt('/checkout/bank-link/return?order-token=signed.result.token');

    await screen.findByRole('heading', { name: 'Payment received' });
    expect(window.location.search).toBe('');
  });

  it('says so plainly when the payment cannot be confirmed', async () => {
    mockApiFetch.mockRejectedValue(new Error('Invalid payment notification'));

    renderAt('/checkout/bank-link/return?order-token=forged');

    expect(await screen.findByRole('heading', { name: /could not confirm/i })).toBeInTheDocument();
  });

  it('does not call the server at all without a token', async () => {
    renderAt('/checkout/bank-link/return');

    expect(await screen.findByRole('heading', { name: /could not confirm/i })).toBeInTheDocument();
    expect(mockApiFetch).not.toHaveBeenCalled();
  });
});
