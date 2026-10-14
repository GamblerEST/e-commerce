import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiFetch, ApiRequestError } from '../api/client';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';

export default function VerifyEmailPage() {
  const [params] = useSearchParams();
  const [status, setStatus] = useState<'verifying' | 'success' | 'error'>('verifying');
  const [error, setError] = useState<string | null>(null);
  const [resendEmail, setResendEmail] = useState('');
  const [resent, setResent] = useState(false);
  const [resending, setResending] = useState(false);

  // Single-use token — StrictMode's dev-mode double-invoke would burn it on
  // the first (silent) call and always fail the second. See
  // OAuthCallbackPage for the same bug, hit and fixed there first.
  const attempted = useRef(false);

  useEffect(() => {
    if (attempted.current) return;
    attempted.current = true;

    const token = params.get('token');
    if (!token) {
      setStatus('error');
      setError('This verification link is missing its token.');
      return;
    }

    apiFetch<void>('/auth/verify-email', { method: 'POST', body: { token } })
      .then(() => setStatus('success'))
      .catch((err) => {
        setStatus('error');
        setError(err instanceof ApiRequestError ? err.message : 'Verification failed.');
      });
    // Runs once on mount; params is stable enough for this page's lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function onResend(e: FormEvent) {
    e.preventDefault();
    setResending(true);
    try {
      await apiFetch<void>('/auth/verify-email/resend', { method: 'POST', body: { email: resendEmail } });
    } finally {
      setResent(true);
      setResending(false);
    }
  }

  return (
    <div className="mx-auto max-w-sm">
      <PageMeta
        title="Verify your email — Estonian Shop"
        description="Confirm your email address for your Estonian Shop account."
        noindex
      />
      {status === 'verifying' && <p role="status" className="text-ink">Verifying your email…</p>}
      {status === 'success' && (
        <>
          <h1 className="text-h1 font-extrabold text-ink">Email verified</h1>
          <p className="mt-4 text-ink">Your email address has been confirmed. You can now log in.</p>
          <Link to="/login" className="mt-4 inline-block font-semibold text-ink underline">
            Go to login
          </Link>
        </>
      )}
      {status === 'error' && (
        <>
          <h1 className="text-h1 font-extrabold text-ink">Verification failed</h1>
          <p role="alert" className="mt-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
            {error}
          </p>
          <p className="mt-4 text-muted">The link may have expired — links are valid for 24 hours.</p>
          {resent ? (
            <p role="status" className="mt-6 text-ink">
              If that address has an account that is not verified yet, a new link is on its way.
            </p>
          ) : (
            <form onSubmit={onResend} className="mt-6">
              <label className="mb-4 block text-sm font-semibold text-ink">
                Get a new link
                <input
                  type="email"
                  value={resendEmail}
                  onChange={(e) => setResendEmail(e.target.value)}
                  required
                  autoComplete="email"
                  className="mt-1 w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink"
                />
              </label>
              <Button type="submit" disabled={resending} className="w-full">
                {resending ? 'Sending…' : 'Send a new verification link'}
              </Button>
            </form>
          )}
        </>
      )}
    </div>
  );
}
