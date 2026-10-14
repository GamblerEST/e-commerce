import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import PageMeta from '../components/PageMeta';

export default function OAuthCallbackPage() {
  const { completeGoogleOAuth } = useAuth();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  // The code and state are single-use (Google's code, and our Redis-backed
  // state token via getAndDelete) — so StrictMode's dev-mode double-invoke of
  // this effect would fire the exchange twice and the second call always
  // fails, masking a successful login behind a false "state expired" error.
  const attempted = useRef(false);

  useEffect(() => {
    if (attempted.current) return;
    attempted.current = true;

    const code = params.get('code');
    const state = params.get('state');
    if (!code || !state) {
      setError('Google did not return the expected code/state.');
      return;
    }
    completeGoogleOAuth(code, state)
      .then(() => navigate('/'))
      .catch(() => setError('Google login failed — the state may have expired (10 min TTL). Try again.'));
    // Runs once on mount; completeGoogleOAuth/navigate are stable enough for this page's lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const meta = <PageMeta title="Signing in — Estonian Shop" description="Completing Google sign-in." noindex />;

  if (error) {
    return (
      <div className="mx-auto max-w-sm">
        {meta}
        <p role="alert" className="rounded-field bg-danger-bg px-4 py-3 text-danger">
          {error}
        </p>
      </div>
    );
  }
  return (
    <div className="mx-auto max-w-sm">
      {meta}
      <p role="status" className="text-ink">Completing Google sign-in…</p>
    </div>
  );
}
