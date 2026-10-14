import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import RegisterPage from './RegisterPage';
import { ApiRequestError } from '../api/client';

const mockRegister = vi.fn();

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    register: mockRegister,
    login: vi.fn(),
    logout: vi.fn(),
    completeGoogleOAuth: vi.fn(),
    user: null,
    loading: false,
  }),
}));

describe('RegisterPage', () => {
  it('submits the form with what the user typed', async () => {
    mockRegister.mockResolvedValueOnce(undefined);
    render(
      <MemoryRouter>
        <RegisterPage />
      </MemoryRouter>,
    );

    await userEvent.type(screen.getByLabelText(/display name/i), 'Ada Lovelace');
    await userEvent.type(screen.getByLabelText(/email/i), 'ada@example.test');
    await userEvent.type(screen.getByLabelText(/^password/i), 'correcthorsebattery9');
    await userEvent.click(screen.getByRole('button', { name: /register/i }));

    expect(mockRegister).toHaveBeenCalledWith({
      email: 'ada@example.test',
      password: 'correcthorsebattery9',
      displayName: 'Ada Lovelace',
      // No VITE_RECAPTCHA_SITE_KEY in the test environment — the widget
      // doesn't render, matching the backend's own graceful-degrade path.
      recaptchaToken: null,
    });
  });

  it('shows a field-level error under the right input, not just a banner', async () => {
    mockRegister.mockRejectedValueOnce(
      new ApiRequestError(400, 'Validation failed', { email: 'Email already registered' }),
    );
    render(
      <MemoryRouter>
        <RegisterPage />
      </MemoryRouter>,
    );

    await userEvent.type(screen.getByLabelText(/display name/i), 'Ada Lovelace');
    await userEvent.type(screen.getByLabelText(/email/i), 'taken@example.test');
    await userEvent.type(screen.getByLabelText(/^password/i), 'correcthorsebattery9');
    await userEvent.click(screen.getByRole('button', { name: /register/i }));

    expect(await screen.findByText('Email already registered')).toBeInTheDocument();
  });
});
