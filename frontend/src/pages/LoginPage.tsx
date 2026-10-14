import { useState, type FormEvent } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiRequestError, apiFetch } from '../api/client';
import type { AuthorizeResponse } from '../api/types';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';

const FIELD_CLASS = 'w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink';

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [totpCode, setTotpCode] = useState('');
  const [needsTotp, setNeedsTotp] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [googleLoading, setGoogleLoading] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login({ email, password, totpCode: needsTotp ? totpCode : null });
      navigate('/');
    } catch (err) {
      if (err instanceof ApiRequestError && err.status === 401 && err.message.toLowerCase().includes('totp')) {
        // Credentials were correct — the account just also needs a second
        // factor. Reveal the code field and let them resubmit.
        setNeedsTotp(true);
        setError('Enter your 6-digit authenticator code or a recovery code.');
      } else if (err instanceof ApiRequestError) {
        setError(err.message);
      } else {
        setError('Something went wrong. Is the backend running?');
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function onGoogleLogin() {
    setGoogleLoading(true);
    setError(null);
    try {
      const res = await apiFetch<AuthorizeResponse>('/auth/oauth2/google/authorize');
      window.location.href = res.authorizationUrl;
    } catch (err) {
      setGoogleLoading(false);
      if (err instanceof ApiRequestError && err.status === 503) {
        setError('Google login is not configured in this environment (GOOGLE_CLIENT_ID unset).');
      } else {
        setError('Could not start Google login.');
      }
    }
  }

  return (
    <div className="mx-auto max-w-sm">
      <PageMeta
        title="Sign in — Estonian Shop"
        description="Sign in to your Estonian Shop account to track orders, review products you have bought and check out faster with your saved delivery address."
        noindex
      />
      <h1 className="text-h1 font-extrabold text-ink">Log in</h1>
      <form onSubmit={onSubmit} className="mt-6">
        <label className="mb-3 block text-sm font-semibold text-ink">
          Email
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoComplete="email"
            className={`mt-1 ${FIELD_CLASS}`}
          />
        </label>
        <label className="mb-1 block text-sm font-semibold text-ink">
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoComplete="current-password"
            className={`mt-1 ${FIELD_CLASS}`}
          />
        </label>
        <p className="mt-1 mb-3">
          <Link to="/forgot-password" className="text-sm font-semibold text-ink underline">
            Forgot your password?
          </Link>
        </p>
        {needsTotp && (
          <label className="mb-3 block text-sm font-semibold text-ink">
            Authenticator / recovery code
            <input
              value={totpCode}
              onChange={(e) => setTotpCode(e.target.value)}
              autoFocus
              className={`mt-1 ${FIELD_CLASS}`}
            />
          </label>
        )}
        {error && (
          <p role="alert" className="mb-3 rounded-field bg-danger-bg px-4 py-3 text-danger">
            {error}
          </p>
        )}
        <Button type="submit" disabled={submitting} className="w-full">
          {submitting ? 'Logging in…' : 'Log in'}
        </Button>
      </form>
      {/* secondary, not the primary corner it had before — stacked right
          under the submit button, two of that shape in a row is the same
          zigzag the corner is meant to stay out of. */}
      <Button variant="secondary" onClick={onGoogleLogin} disabled={googleLoading} className="mt-3 w-full">
        {googleLoading ? 'Redirecting…' : 'Continue with Google'}
      </Button>
      <p className="mt-6 text-sm text-muted">
        No account yet?{' '}
        <Link to="/register" className="font-semibold text-ink underline">
          Register
        </Link>
      </p>
    </div>
  );
}
