import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '../api/client';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  // Always the same message regardless of outcome — the backend's response
  // is deliberately identical whether or not the email exists (enumeration-
  // safe), so the UI must not leak that distinction either.
  const [sent, setSent] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    try {
      await apiFetch<void>('/auth/password/forgot', { method: 'POST', body: { email } });
    } catch {
      // Swallowed on purpose — even a network hiccup shouldn't distinguish
      // "email doesn't exist" from "something went wrong" in the UI.
    } finally {
      setSubmitting(false);
      setSent(true);
    }
  }

  const meta = (
    <PageMeta
      title="Forgot your password? — Estonian Shop"
      description="Reset the password on your Estonian Shop account."
      noindex
    />
  );

  if (sent) {
    return (
      <div className="mx-auto max-w-sm">
        {meta}
        <h1 className="text-h1 font-extrabold text-ink">Check your email</h1>
        <p className="mt-4 text-ink">If an account exists for that address, a reset link is on its way.</p>
        <Link to="/login" className="mt-4 inline-block font-semibold text-ink underline">
          Back to login
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-sm">
      {meta}
      <h1 className="text-h1 font-extrabold text-ink">Forgot your password?</h1>
      <form onSubmit={onSubmit} className="mt-6">
        <label className="mb-4 block text-sm font-semibold text-ink">
          Email
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoFocus
            autoComplete="email"
            className="mt-1 w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink"
          />
        </label>
        <Button type="submit" disabled={submitting} className="w-full">
          {submitting ? 'Sending…' : 'Send reset link'}
        </Button>
      </form>
      <p className="mt-6">
        <Link to="/login" className="text-sm font-semibold text-ink underline">
          Back to login
        </Link>
      </p>
    </div>
  );
}
