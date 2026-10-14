import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import VerifyEmailPage from './VerifyEmailPage';
import { apiFetch, ApiRequestError } from '../api/client';

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  apiFetch: vi.fn(),
}));

const mockApiFetch = vi.mocked(apiFetch);

// Regression coverage for a real bug: this page didn't
// exist at all, so a real verification email's link rendered a blank page
// below the navbar (React Router silently renders nothing on an unmatched
// route). These three cases are exactly what someone clicking that link
// would hit.
describe('VerifyEmailPage', () => {
  it('shows success once the backend confirms the token', async () => {
    mockApiFetch.mockResolvedValueOnce(undefined);
    render(
      <MemoryRouter initialEntries={['/verify-email?token=a-real-token']}>
        <VerifyEmailPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText(/email verified/i)).toBeInTheDocument();
    expect(mockApiFetch).toHaveBeenCalledWith('/auth/verify-email', {
      method: 'POST',
      body: { token: 'a-real-token' },
    });
  });

  it('shows a clear error for an invalid or expired token — not a blank page', async () => {
    mockApiFetch.mockRejectedValueOnce(new ApiRequestError(400, 'Invalid or expired verification link'));
    render(
      <MemoryRouter initialEntries={['/verify-email?token=stale-or-fake']}>
        <VerifyEmailPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText(/verification failed/i)).toBeInTheDocument();
    expect(screen.getByText(/invalid or expired verification link/i)).toBeInTheDocument();
  });

  it('shows an error immediately when the link has no token at all', () => {
    render(
      <MemoryRouter initialEntries={['/verify-email']}>
        <VerifyEmailPage />
      </MemoryRouter>,
    );

    expect(screen.getByText(/verification failed/i)).toBeInTheDocument();
    expect(mockApiFetch).not.toHaveBeenCalled();
  });
  it('lets someone whose link failed ask for a new one, and says the same either way', async () => {
    mockApiFetch.mockRejectedValueOnce(new ApiRequestError(400, 'This link has already been used.'));
    mockApiFetch.mockResolvedValueOnce(undefined);
    render(
      <MemoryRouter initialEntries={['/verify-email?token=used-token']}>
        <VerifyEmailPage />
      </MemoryRouter>,
    );

    await userEvent.type(await screen.findByLabelText(/get a new link/i), 'me@example.ee');
    await userEvent.click(screen.getByRole('button', { name: /send a new verification link/i }));

    expect(mockApiFetch).toHaveBeenLastCalledWith('/auth/verify-email/resend', {
      method: 'POST',
      body: { email: 'me@example.ee' },
    });
    expect(await screen.findByText(/a new link is on its way/i)).toBeInTheDocument();
  });
});
