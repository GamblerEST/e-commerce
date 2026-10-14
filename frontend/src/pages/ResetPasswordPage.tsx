import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch, ApiRequestError } from '../api/client';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';

export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const token = params.get('token');

  const [newPassword, setNewPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!token) {
      setError('This reset link is missing its token.');
      return;
    }
    setSubmitting(true);
    try {
      await apiFetch<void>('/auth/password/reset', { method: 'POST', body: { token, newPassword } });
      setDone(true);
      setTimeout(() => navigate('/login'), 2000);
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : 'Something went wrong.');
    } finally {
      setSubmitting(false);
    }
  }

  const meta = (
    <PageMeta
      title="Reset your password — Estonian Shop"
      description="Choose a new password for your Estonian Shop account."
      noindex
    />
  );

  if (!token) {
    return (
      <div className="mx-auto max-w-sm">
        {meta}
        <h1 className="text-h1 font-extrabold text-ink">Invalid link</h1>
        <p role="alert" className="mt-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
          This reset link is missing its token.
        </p>
        <Link to="/forgot-password" className="mt-4 inline-block font-semibold text-ink underline">
          Request a new one
        </Link>
      </div>
    );
  }

  if (done) {
    return (
      <div className="mx-auto max-w-sm">
        {meta}
        <h1 className="text-h1 font-extrabold text-ink">Password updated</h1>
        <p role="status" className="mt-4 text-ink">
          You&apos;ve been signed out of every session. Redirecting to login…
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-sm">
      {meta}
      <h1 className="text-h1 font-extrabold text-ink">Choose a new password</h1>
      <form onSubmit={onSubmit} className="mt-6">
        <label className="mb-1 block text-sm font-semibold text-ink">
          New password
          <input
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            minLength={12}
            required
            autoFocus
            autoComplete="new-password"
            className="mt-1 w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink"
          />
          <span className="mt-1 block text-sm font-normal text-muted">At least 12 characters.</span>
        </label>
        {error && (
          <p role="alert" className="mt-3 mb-1 rounded-field bg-danger-bg px-4 py-3 text-danger">
            {error}
          </p>
        )}
        <Button type="submit" disabled={submitting} className="mt-4 w-full">
          {submitting ? 'Updating…' : 'Update password'}
        </Button>
      </form>
    </div>
  );
}
