import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { apiFetch, attemptSilentLogin, getAccessToken, onAccessTokenChange, setAccessToken } from '../api/client';
import type { AuthResponse, LoginRequest, MeResponse, RegisterRequest } from '../api/types';

interface AuthContextValue {
  user: MeResponse | null;
  loading: boolean;
  register: (req: RegisterRequest) => Promise<void>;
  login: (req: LoginRequest) => Promise<void>;
  completeGoogleOAuth: (code: string, state: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Re-fetches /auth/me — needed after anything that changes account state
   *  server-side without a fresh login, e.g. enabling 2FA (see AccountPage). */
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null);
  // Starts true: on page load we don't yet know if a valid refresh_token
  // cookie exists from a previous session, so we probe /auth/refresh once
  // before deciding whether to show the logged-out state.
  const [loading, setLoading] = useState(true);

  async function loadMe() {
    try {
      const me = await apiFetch<MeResponse>('/auth/me');
      setUser(me);
    } catch {
      setUser(null);
    }
  }

  useEffect(() => {
    const unsubscribe = onAccessTokenChange((token) => {
      if (!token) setUser(null);
    });

    // Silent-login attempt: a real refresh_token cookie from an earlier
    // session (still valid, HttpOnly, so JS can't check it directly) lets a
    // returning shopper land back on the page still authenticated.
    (async () => {
      const ok = await attemptSilentLogin();
      if (ok) await loadMe();
      setLoading(false);
    })();

    return unsubscribe;
  }, []);

  async function register(req: RegisterRequest) {
    const res = await apiFetch<AuthResponse>('/auth/register', {
      method: 'POST',
      body: req,
      skipAuthRetry: true,
    });
    setAccessToken(res.accessToken);
    await loadMe();
  }

  async function login(req: LoginRequest) {
    const res = await apiFetch<AuthResponse>('/auth/login', {
      method: 'POST',
      body: req,
      skipAuthRetry: true,
    });
    setAccessToken(res.accessToken);
    await loadMe();
  }

  async function completeGoogleOAuth(code: string, state: string) {
    const res = await apiFetch<AuthResponse>('/auth/oauth2/google/callback', {
      method: 'POST',
      body: { code, state },
      skipAuthRetry: true,
    });
    setAccessToken(res.accessToken);
    await loadMe();
  }

  async function logout() {
    try {
      await apiFetch<void>('/auth/logout', { method: 'POST' });
    } finally {
      setAccessToken(null);
      setUser(null);
    }
  }

  return (
    <AuthContext.Provider
      value={{ user, loading, register, login, completeGoogleOAuth, logout, refreshUser: loadMe }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

// Re-exported so pages don't need to know client.ts exists directly.
export { getAccessToken };
