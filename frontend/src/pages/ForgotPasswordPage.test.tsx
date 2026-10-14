import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import ForgotPasswordPage from './ForgotPasswordPage';
import { apiFetch } from '../api/client';

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  apiFetch: vi.fn(),
}));

const mockApiFetch = vi.mocked(apiFetch);

async function submit(email: string) {
  render(
    <MemoryRouter>
      <ForgotPasswordPage />
    </MemoryRouter>,
  );
  await userEvent.type(screen.getByLabelText(/email/i), email);
  await userEvent.click(screen.getByRole('button', { name: /send reset link/i }));
}

describe('ForgotPasswordPage', () => {
  // The backend is deliberately enumeration-safe (identical response for a
  // real vs. made-up email) — the UI must not undo that by reacting
  // differently to success vs. failure.
  it('shows the identical confirmation whether the email exists or not', async () => {
    mockApiFetch.mockResolvedValueOnce(undefined);
    await submit('real-account@example.test');
    expect(await screen.findByText(/check your email/i)).toBeInTheDocument();
  });

  it('shows the same confirmation even when the request fails outright', async () => {
    mockApiFetch.mockRejectedValueOnce(new Error('network error'));
    await submit('unknown@example.test');
    expect(await screen.findByText(/check your email/i)).toBeInTheDocument();
  });
});
