// Relative, not absolute — vite.config.ts proxies /api (and /product-images)
// to the backend server-side, so from the BROWSER's perspective every
// request is same-origin (localhost:5173). This matters for more than
// tidiness: the refresh_token cookie is HttpOnly+Secure+SameSite=Strict
// (DECISIONS.md §6/§11), and modern Chrome's default third-party-cookie
// restrictions silently drop it from a genuinely cross-origin fetch even
// between two localhost ports — SameSite=Strict's own "same-site" rule
// (which ignores port) does NOT override that. Proxying also mirrors how
// the real deployment works: Caddy fronts both the frontend and `/api/*`
// on one origin (see deploy/Caddyfile's `handle /api/*` block) for exactly
// this reason.
const BASE_URL = '/api';

// Product image URLs come back as root-relative paths (e.g.
// "/product-images/foo.svg") — already correct as-is once proxied, since
// they resolve against this same origin.
export function resolveImageUrl(path: string): string {
  return path;
}

// Access token lives ONLY in memory — never localStorage/sessionStorage.
// DECISIONS.md §6: an XSS payload that can run JS can also read
// localStorage, so a token that lives there is a token that's already
// leaked. The refresh token never reaches JS at all — it's an HttpOnly
// cookie the browser attaches automatically.
let accessToken: string | null = null;
const listeners = new Set<(token: string | null) => void>();

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
  listeners.forEach((l) => l(token));
}

export function onAccessTokenChange(listener: (token: string | null) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[1]) : null;
}

export class ApiRequestError extends Error {
  status: number;
  errors?: Record<string, string> | null;
  constructor(status: number, message: string, errors?: Record<string, string> | null) {
    super(message);
    this.status = status;
    this.errors = errors;
  }
}

// DECISIONS.md §6 is explicit: the frontend MUST single-flight /auth/refresh.
// Firing two concurrent refreshes races the backend's single-use rotation —
// the loser's token is already spent, so it looks exactly like a replay
// attack and the WHOLE session family gets revoked, logging the user out.
// This promise is the single flight: every caller that hits a 401 while a
// refresh is already in progress awaits this same promise instead of
// starting its own.
let refreshInFlight: Promise<boolean> | null = null;

/**
 * Spring Security's deferred CSRF token materialization (CsrfCookieFilter's
 * forced .getToken(), needed because BREACH-protected tokens are lazy by
 * default — see that filter's javadoc) is NOT safe under concurrent
 * requests sharing the same cookie state: two requests racing can end up
 * with the XSRF-TOKEN cookie DELETED (Set-Cookie: XSRF-TOKEN=; Expires=
 * epoch) instead of consistently regenerated. Reproduced deterministically
 * with two concurrent authenticated curl requests to the same endpoint —
 * this is a real concurrency edge case (two tabs, a double-click, a
 * StrictMode-doubled effect in dev), not a one-off fluke. A GET fired
 * immediately before reading the cookie can't fully close the window (some
 * OTHER concurrent request can still race it), but narrows it enough that
 * doRefresh's own one-retry-with-reprime below makes the visible failure
 * rate effectively zero.
 */
async function primeCsrfCookie(): Promise<void> {
  await fetch(`${BASE_URL}/categories`, { credentials: 'include' }).catch(() => {});
}

async function attemptRefresh(): Promise<Response> {
  const csrf = getCookie('XSRF-TOKEN');
  return fetch(`${BASE_URL}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
    headers: csrf ? { 'X-XSRF-TOKEN': csrf } : {},
  });
}

async function doRefresh(): Promise<boolean> {
  await primeCsrfCookie();
  let res = await attemptRefresh();

  // One self-healing retry: if the CSRF cookie was cleared by a concurrent
  // request in the gap between priming and sending, re-prime and try once
  // more rather than forcing the user through a full re-login.
  if (!res.ok && !getCookie('XSRF-TOKEN')) {
    await primeCsrfCookie();
    res = await attemptRefresh();
  }

  if (!res.ok) {
    setAccessToken(null);
    return false;
  }
  const body = (await res.json()) as { accessToken: string };
  setAccessToken(body.accessToken);
  return true;
}

function refreshOnce(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = doRefresh().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

/**
 * Called once on app load to silently re-establish a session from an
 * existing refresh_token cookie (e.g. the shopper reloaded the page).
 * Same single-flight path as a mid-session 401 retry — there's only ever
 * one kind of refresh, not a separate "startup" variant.
 */
export function attemptSilentLogin(): Promise<boolean> {
  return refreshOnce();
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Skip the auth-retry dance — used by login/register/refresh themselves. */
  skipAuthRetry?: boolean;
  /** Extra headers beyond Content-Type/Authorization/X-XSRF-TOKEN — e.g. checkout's Idempotency-Key. */
  headers?: Record<string, string>;
  /**
   * Cancels the request. Needed by type-ahead search, where a slow early
   * response can otherwise land after a later one and overwrite fresher
   * results with stale ones.
   *
   * Exposed here rather than letting callers reach for raw fetch(), because
   * bypassing apiFetch would also bypass the single-flight /auth/refresh
   * handling below — and concurrent refreshes self-inflict a logout
   * (DECISIONS.md §6).
   */
  signal?: AbortSignal;
}

async function parseErrorBody(res: Response): Promise<ApiRequestError> {
  try {
    const body = await res.json();
    return new ApiRequestError(res.status, body.message ?? res.statusText, body.errors);
  } catch {
    return new ApiRequestError(res.status, res.statusText || `Request failed (${res.status})`);
  }
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const doFetch = (): Promise<Response> => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
    // Cart mutations (and anything else cookie-authenticated — see
    // DECISIONS.md §11) require this header server-side. Attaching it
    // whenever the cookie happens to be present is harmless for endpoints
    // that don't need it, and the cookie is reliably there by this point:
    // CsrfCookieFilter forces it on every response, including the very
    // first anonymous GET the app makes on load.
    const csrf = getCookie('XSRF-TOKEN');
    if (csrf) headers['X-XSRF-TOKEN'] = csrf;
    if (options.headers) Object.assign(headers, options.headers);
    return fetch(`${BASE_URL}${path}`, {
      method: options.method ?? 'GET',
      credentials: 'include',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
    });
  };

  let res = await doFetch();

  // A 401 on anything other than the auth endpoints themselves means the
  // access token expired (15 min TTL) — try ONE silent refresh-and-retry
  // before surfacing the failure, so someone clicking around doesn't get
  // logged out just because they paused on a page for 16 minutes.
  if (res.status === 401 && !options.skipAuthRetry) {
    const refreshed = await refreshOnce();
    if (refreshed) {
      res = await doFetch();
    }
  }

  if (!res.ok) {
    throw await parseErrorBody(res);
  }

  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

/**
 * Downloads a binary file the API only serves to signed-in staff (an invoice PDF),
 * and hands it to the browser as a save. apiFetch cannot do this: it always
 * parses JSON, and a plain <a href> cannot carry the in-memory access token.
 *
 * Same auth, CSRF header and single-flight refresh-and-retry as apiFetch —
 * it goes through refreshOnce, never its own refresh call (DECISIONS.md §6).
 */
export async function downloadFile(path: string, filename: string): Promise<void> {
  const doFetch = (): Promise<Response> => {
    const headers: Record<string, string> = {};
    if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
    const csrf = getCookie('XSRF-TOKEN');
    if (csrf) headers['X-XSRF-TOKEN'] = csrf;
    return fetch(`${BASE_URL}${path}`, { method: 'GET', credentials: 'include', headers });
  };

  let res = await doFetch();
  if (res.status === 401 && (await refreshOnce())) {
    res = await doFetch();
  }
  if (!res.ok) {
    throw await parseErrorBody(res);
  }

  const url = URL.createObjectURL(await res.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * Multipart upload (a product image). apiFetch always sends JSON, and a
 * multipart body needs the browser to set its own Content-Type boundary, so this
 * sends no Content-Type at all. Same auth, CSRF header and single-flight
 * refresh-and-retry as apiFetch (DECISIONS.md §6).
 */
export async function uploadFile<T>(path: string, form: FormData): Promise<T> {
  const doFetch = (): Promise<Response> => {
    const headers: Record<string, string> = {};
    if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
    const csrf = getCookie('XSRF-TOKEN');
    if (csrf) headers['X-XSRF-TOKEN'] = csrf;
    return fetch(`${BASE_URL}${path}`, { method: 'POST', credentials: 'include', headers, body: form });
  };

  let res = await doFetch();
  if (res.status === 401 && (await refreshOnce())) {
    res = await doFetch();
  }
  if (!res.ok) {
    throw await parseErrorBody(res);
  }
  return (await res.json()) as T;
}
