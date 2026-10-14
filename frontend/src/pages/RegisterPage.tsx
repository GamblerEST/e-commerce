import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ApiRequestError } from '../api/client';
import { loadRecaptchaScript } from '../auth/recaptcha';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';

const SITE_KEY = import.meta.env.VITE_RECAPTCHA_SITE_KEY;
const FIELD_CLASS = 'w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink';

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const recaptchaContainerRef = useRef<HTMLDivElement>(null);
  const widgetIdRef = useRef<number | null>(null);

  // Only render the real widget when a site key is configured. Without one,
  // app.recaptcha.secret is unset server-side too and RecaptchaService
  // accepts registration unchecked — see README "Known limitations". This
  // keeps local dev working before Google keys exist, same as the backend.
  useEffect(() => {
    if (!SITE_KEY) return;
    let cancelled = false;
    loadRecaptchaScript().then(() => {
      if (cancelled || !recaptchaContainerRef.current || widgetIdRef.current !== null) return;
      widgetIdRef.current = window.grecaptcha!.render(recaptchaContainerRef.current, {
        sitekey: SITE_KEY,
      });
    });
    return () => {
      cancelled = true;
    };
  }, []);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});

    let recaptchaToken: string | null = null;
    if (SITE_KEY) {
      recaptchaToken = window.grecaptcha?.getResponse(widgetIdRef.current ?? undefined) ?? '';
      if (!recaptchaToken) {
        setError('Please complete the reCAPTCHA check.');
        return;
      }
    }

    setSubmitting(true);
    try {
      await register({ email, password, displayName, recaptchaToken });
      navigate('/');
    } catch (err) {
      if (err instanceof ApiRequestError) {
        setError(err.message);
        if (err.errors) setFieldErrors(err.errors);
      } else {
        setError('Something went wrong. Is the backend running?');
      }
      // v2 tokens are single-use — a failed submit needs a fresh checkbox.
      if (SITE_KEY && widgetIdRef.current !== null) {
        window.grecaptcha?.reset(widgetIdRef.current);
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-sm">
      <PageMeta
        title="Create an account — Estonian Shop"
        description="Create an Estonian Shop account to track your orders, review the products you buy and check out faster. VAT-inclusive prices and tracked delivery."
        noindex
      />
      <h1 className="text-h1 font-extrabold text-ink">Create an account</h1>
      <form onSubmit={onSubmit} className="mt-6">
        <label className="mb-3 block text-sm font-semibold text-ink">
          Display name
          <input
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            maxLength={120}
            required
            autoComplete="name"
            className={`mt-1 ${FIELD_CLASS}`}
          />
          {fieldErrors.displayName && (
            <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.displayName}</span>
          )}
        </label>
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
          {fieldErrors.email && <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.email}</span>}
        </label>
        <label className="mb-3 block text-sm font-semibold text-ink">
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={12}
            required
            autoComplete="new-password"
            className={`mt-1 ${FIELD_CLASS}`}
          />
          {fieldErrors.password && (
            <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors.password}</span>
          )}
          <span className="mt-1 block text-sm text-muted">At least 12 characters.</span>
        </label>
        {SITE_KEY && <div ref={recaptchaContainerRef} className="mb-3" />}
        {error && (
          <p role="alert" className="mb-3 rounded-field bg-danger-bg px-4 py-3 text-danger">
            {error}
          </p>
        )}
        <Button type="submit" disabled={submitting} className="w-full">
          {submitting ? 'Creating account…' : 'Register'}
        </Button>
      </form>
      <p className="mt-6 text-sm text-muted">
        Already have an account?{' '}
        <Link to="/login" className="font-semibold text-ink underline">
          Log in
        </Link>
      </p>
    </div>
  );
}
