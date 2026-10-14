import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import LoginPage from './LoginPage';
import { ApiRequestError } from '../api/client';

const mockLogin = vi.fn();

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    login: mockLogin,
    register: vi.fn(),
    logout: vi.fn(),
    completeGoogleOAuth: vi.fn(),
    user: null,
    loading: false,
  }),
}));

describe('LoginPage', () => {
  it('reveals the TOTP field only after the backend reports one is required', async () => {
    mockLogin.mockRejectedValueOnce(new ApiRequestError(401, 'totp code required'));
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );

    // Not shown up front — a plain wrong-password case has no reason to see it.
    expect(screen.queryByLabelText(/authenticator/i)).not.toBeInTheDocument();

    await userEvent.type(screen.getByLabelText(/email/i), 'demo@example.test');
    await userEvent.type(screen.getByLabelText(/^password/i), 'correcthorsebattery9');
    await userEvent.click(screen.getByRole('button', { name: /log in/i }));

    expect(await screen.findByLabelText(/authenticator/i)).toBeInTheDocument();
  });

  it('does not reveal the TOTP field for a plain invalid-credentials error', async () => {
    mockLogin.mockRejectedValueOnce(new ApiRequestError(401, 'invalid email or password'));
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );

    await userEvent.type(screen.getByLabelText(/email/i), 'demo@example.test');
    await userEvent.type(screen.getByLabelText(/^password/i), 'wrong-password-here');
    await userEvent.click(screen.getByRole('button', { name: /log in/i }));

    expect(await screen.findByText(/invalid email or password/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/authenticator/i)).not.toBeInTheDocument();
  });
});
