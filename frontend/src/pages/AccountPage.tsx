import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import QRCode from 'qrcode';
import { useAuth } from '../auth/AuthContext';
import { apiFetch, ApiRequestError } from '../api/client';
import type {
  MeResponse,
  PersonalDataExport,
  TotpSetupResponse,
  TotpVerifyResponse,
} from '../api/types';
import PageMeta from '../components/PageMeta';
import AddressBook from '../components/AddressBook';
import { Button } from '../components/ui/button';

type Step = 'idle' | 'awaiting-code' | 'done';

const FIELD_CLASS = 'w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink';

export default function AccountPage() {
  const { user, loading, refreshUser, logout } = useAuth();
  const navigate = useNavigate();

  // Display name: inline edit, collapsed to a single "Edit" button until
  // clicked — matches the same reveal-on-demand pattern as the password and
  // disable-2FA forms below, instead of an always-open input.
  const [editingName, setEditingName] = useState(false);
  const [displayName, setDisplayName] = useState('');
  const [nameError, setNameError] = useState<string | null>(null);
  const [nameBusy, setNameBusy] = useState(false);

  const [showPasswordForm, setShowPasswordForm] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [passwordBusy, setPasswordBusy] = useState(false);

  const [step, setStep] = useState<Step>('idle');
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [manualSecret, setManualSecret] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Separate mini-flow from setup/verify above — disabling requires proof
  // you still hold the second factor (same as login), not just an active
  // session, so a stolen access token alone can't strip 2FA off an account.
  const [showDisableForm, setShowDisableForm] = useState(false);
  const [disableCode, setDisableCode] = useState('');
  const [disableError, setDisableError] = useState<string | null>(null);
  const [disableBusy, setDisableBusy] = useState(false);

  if (!loading && !user) {
    return <Navigate to="/login" replace />;
  }

  function startEditingName() {
    setDisplayName(user?.displayName ?? '');
    setNameError(null);
    setEditingName(true);
  }

  async function onSaveName(e: FormEvent) {
    e.preventDefault();
    setNameError(null);
    setNameBusy(true);
    try {
      await apiFetch<MeResponse>('/auth/profile', { method: 'POST', body: { displayName } });
      setEditingName(false);
      // The endpoint already returns the updated profile, but AuthContext's
      // user state lives in AuthProvider, not here — refetch through it
      // rather than trying to reach into that state directly.
      await refreshUser();
    } catch (err) {
      setNameError(err instanceof ApiRequestError ? err.message : 'Could not save.');
    } finally {
      setNameBusy(false);
    }
  }

  async function startSetup() {
    setError(null);
    setBusy(true);
    try {
      // Safe to call even if 2FA is already enabled — it only overwrites
      // the PENDING secret; nothing takes effect until /verify confirms it.
      const res = await apiFetch<TotpSetupResponse>('/auth/totp/setup', { method: 'POST' });
      const dataUrl = await QRCode.toDataURL(res.otpauthUri);
      setQrDataUrl(dataUrl);
      setManualSecret(res.secret);
      setStep('awaiting-code');
    } catch {
      setError('Could not start 2FA setup. Is the backend running?');
    } finally {
      setBusy(false);
    }
  }

  async function onVerify(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = await apiFetch<TotpVerifyResponse>('/auth/totp/verify', {
        method: 'POST',
        body: { code },
      });
      setRecoveryCodes(res.recoveryCodes);
      setStep('done');
      // Without this, user.totpEnabled stays stale at its pre-setup value
      // until the next full page load / login — the account page would keep
      // showing "not enabled" right after successfully enabling it.
      await refreshUser();
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : 'Verification failed.');
    } finally {
      setBusy(false);
    }
  }

  async function onDisable(e: FormEvent) {
    e.preventDefault();
    setDisableError(null);
    setDisableBusy(true);
    try {
      await apiFetch<void>('/auth/totp/disable', { method: 'POST', body: { code: disableCode } });
      setShowDisableForm(false);
      setDisableCode('');
      await refreshUser();
    } catch (err) {
      setDisableError(err instanceof ApiRequestError ? err.message : 'Could not disable 2FA.');
    } finally {
      setDisableBusy(false);
    }
  }

  async function onChangePassword(e: FormEvent) {
    e.preventDefault();
    setPasswordError(null);
    setPasswordBusy(true);
    try {
      await apiFetch<void>('/auth/password/change', {
        method: 'POST',
        body: { currentPassword, newPassword },
      });
      // The backend revokes every session on a password change, including
      // the one making this request — the local access token is dead the
      // moment this call succeeds, so log out client-side to match.
      await logout();
      navigate('/login');
    } catch (err) {
      setPasswordError(err instanceof ApiRequestError ? err.message : 'Could not change password.');
    } finally {
      setPasswordBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-2xl">
      <PageMeta
        title="Your account — Estonian Shop"
        description="Manage your Estonian Shop profile, change your password and turn on two-factor authentication to keep your account and order history secure."
        noindex
      />
      <h1 className="text-h1 font-extrabold text-ink">Account</h1>

      <section aria-labelledby="profile-heading" className="mt-8">
        <h2 id="profile-heading" className="text-h3 font-extrabold text-ink">
          Profile
        </h2>
        <dl className="mt-4 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-3 rounded-card border border-hairline bg-surface p-6 text-sm">
          <dt className="font-semibold text-muted">Email</dt>
          <dd className="m-0 text-ink">{user?.email}</dd>

          <dt className="self-start pt-1.5 font-semibold text-muted">Display name</dt>
          <dd className="m-0">
            {!editingName && (
              <span className="flex items-center gap-3 text-ink">
                {user?.displayName}
                <Button type="button" variant="link" onClick={startEditingName}>
                  Edit
                </Button>
              </span>
            )}
            {editingName && (
              <form onSubmit={onSaveName} className="flex flex-wrap items-start gap-2">
                <div>
                  <input
                    value={displayName}
                    onChange={(e) => setDisplayName(e.target.value)}
                    maxLength={120}
                    required
                    autoFocus
                    className={FIELD_CLASS}
                  />
                  {nameError && <p className="mt-1 text-sm text-danger">{nameError}</p>}
                </div>
                <Button type="submit" disabled={nameBusy}>
                  {nameBusy ? 'Saving…' : 'Save'}
                </Button>
                <Button type="button" variant="secondary" onClick={() => setEditingName(false)} disabled={nameBusy}>
                  Cancel
                </Button>
              </form>
            )}
          </dd>
        </dl>
      </section>

      <section aria-labelledby="security-heading" className="mt-12">
        <h2 id="security-heading" className="text-h3 font-extrabold text-ink">
          Security
        </h2>

        <div className="mt-4 rounded-card border border-hairline bg-surface p-6">
          {!showPasswordForm && (
            <Button type="button" variant="secondary" onClick={() => setShowPasswordForm(true)}>
              Change password
            </Button>
          )}

          {showPasswordForm && (
            <form onSubmit={onChangePassword} className="max-w-sm">
              <label className="mb-3 block text-sm font-semibold text-ink">
                Current password
                <input
                  type="password"
                  value={currentPassword}
                  onChange={(e) => setCurrentPassword(e.target.value)}
                  autoFocus
                  required
                  className={`mt-1 ${FIELD_CLASS}`}
                />
              </label>
              <label className="mb-3 block text-sm font-semibold text-ink">
                New password
                <input
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  minLength={12}
                  required
                  className={`mt-1 ${FIELD_CLASS}`}
                />
              </label>
              {passwordError && (
                <p role="alert" className="mb-3 rounded-field bg-danger-bg px-4 py-3 text-danger">
                  {passwordError}
                </p>
              )}
              <p className="mb-3 text-sm text-muted">
                At least 12 characters. This signs you out everywhere, including here.
              </p>
              <div className="flex flex-wrap gap-3">
                <Button type="submit" disabled={passwordBusy}>
                  {passwordBusy ? 'Changing…' : 'Confirm change'}
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  onClick={() => {
                    setShowPasswordForm(false);
                    setPasswordError(null);
                  }}
                  disabled={passwordBusy}
                >
                  Cancel
                </Button>
              </div>
            </form>
          )}
        </div>

        <div className="mt-4 rounded-card border border-hairline bg-surface p-6">
          {step === 'idle' && user?.totpEnabled && !showDisableForm && (
            <p className="m-0 flex flex-wrap items-center gap-3 text-ink">
              <span className="rounded-full bg-ok-bg px-2.5 py-1 text-xs font-bold text-ok">
                Enabled
              </span>
              Two-factor authentication is on.
              <Button type="button" variant="secondary" onClick={startSetup} disabled={busy}>
                {busy ? 'Starting…' : 'Reconfigure'}
              </Button>
              <Button type="button" variant="link" onClick={() => setShowDisableForm(true)} disabled={busy} className="text-danger">
                Disable
              </Button>
            </p>
          )}

          {step === 'idle' && user?.totpEnabled && showDisableForm && (
            <form onSubmit={onDisable} className="max-w-sm">
              <label className="mb-3 block text-sm font-semibold text-ink">
                Enter a current authenticator code or a recovery code to confirm
                <input
                  value={disableCode}
                  onChange={(e) => setDisableCode(e.target.value)}
                  autoFocus
                  required
                  className={`mt-1 ${FIELD_CLASS}`}
                />
              </label>
              {disableError && (
                <p role="alert" className="mb-3 rounded-field bg-danger-bg px-4 py-3 text-danger">
                  {disableError}
                </p>
              )}
              <div className="flex flex-wrap gap-3">
                <Button type="submit" disabled={disableBusy}>
                  {disableBusy ? 'Disabling…' : 'Confirm disable'}
                </Button>
                <Button type="button" variant="secondary" onClick={() => setShowDisableForm(false)} disabled={disableBusy}>
                  Cancel
                </Button>
              </div>
            </form>
          )}

          {step === 'idle' && !user?.totpEnabled && (
            <p className="m-0 flex flex-wrap items-center gap-3 text-ink">
              Two-factor authentication is off.
              <Button type="button" onClick={startSetup} disabled={busy}>
                {busy ? 'Starting…' : 'Set up 2FA'}
              </Button>
            </p>
          )}

          {step === 'awaiting-code' && (
            <>
              <p className="mt-0 text-ink">Scan this with an authenticator app (Google Authenticator, Authy, …):</p>
              {qrDataUrl && (
                <img
                  src={qrDataUrl}
                  alt="2FA setup QR code"
                  width={200}
                  height={200}
                  className="rounded-field border border-hairline"
                />
              )}
              {manualSecret && (
                <p className="mt-3 text-sm text-muted">
                  Can&apos;t scan it? Enter this manually: <code className="text-ink">{manualSecret}</code>
                </p>
              )}
              <form onSubmit={onVerify} className="mt-4 max-w-xs">
                <label className="mb-3 block text-sm font-semibold text-ink">
                  6-digit code
                  <input
                    value={code}
                    onChange={(e) => setCode(e.target.value)}
                    autoFocus
                    required
                    maxLength={6}
                    className={`mt-1 ${FIELD_CLASS}`}
                  />
                </label>
                {error && (
                  <p role="alert" className="mb-3 rounded-field bg-danger-bg px-4 py-3 text-danger">
                    {error}
                  </p>
                )}
                <Button type="submit" disabled={busy}>
                  {busy ? 'Verifying…' : 'Confirm'}
                </Button>
              </form>
            </>
          )}

          {step === 'done' && (
            <>
              <p role="alert" className="mt-0 rounded-field bg-danger-bg px-4 py-3 text-danger">
                Save these recovery codes now — they&apos;re shown only this once. Each
                works one time if you lose access to your authenticator app.
              </p>
              <ul className="grid list-none grid-cols-2 gap-2 p-0">
                {recoveryCodes.map((c) => (
                  <li
                    key={c}
                    className="rounded-field border border-hairline bg-canvas px-3 py-2 text-center font-mono text-sm text-ink"
                  >
                    {c}
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </section>

      <AddressBook />

      <PrivacySection />

      <p className="mt-12">
        <Link to="/products" className="font-semibold text-ink underline">
          Back to shop
        </Link>
      </p>
    </div>
  );
}

/**
 * The two rights a customer can exercise over their own data.
 *
 * Both existed in the schema and in the documentation long before there was
 * any way to use them — anonymize_user() had been in the database since the
 * first migration with nothing calling it.
 */
function PrivacySection() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function downloadMyData() {
    setError(null);
    setBusy(true);
    try {
      const data = await apiFetch<PersonalDataExport>('/account/data');
      // Turned into a file in the browser rather than following the server's
      // Content-Disposition, because apiFetch attaches the in-memory access
      // token — a plain <a href> navigation carries no Authorization header
      // and would just 401.
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'estonian-shop-my-data.json';
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      setError('Your data could not be downloaded. Please try again.');
    } finally {
      setBusy(false);
    }
  }

  async function deleteMyAccount(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await apiFetch('/account', { method: 'DELETE', body: { currentPassword: password } });
      // The server has already revoked every session; this clears the client's
      // in-memory token so the UI does not keep acting signed in.
      await logout();
      navigate('/');
    } catch (err) {
      setError(
        err instanceof ApiRequestError ? err.message : 'Your account could not be deleted.',
      );
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="privacy-heading" className="mt-12 border-t border-hairline pt-8">
      <h2 id="privacy-heading" className="text-h3 font-extrabold text-ink">
        Your data
      </h2>

      {error && (
        <p role="alert" className="mt-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
          {error}
        </p>
      )}

      <p className="mt-4 text-muted">
        You can download everything we hold about you, or delete your account entirely.
      </p>

      <Button type="button" variant="secondary" onClick={downloadMyData} disabled={busy}>
        Download my data
      </Button>

      {/* Deleting an account is irreversible, so its own controls sit in a
          visually distinct, clearly-bounded danger zone rather than reading
          like any other setting on the page. */}
      <div className="mt-8 rounded-card border border-danger/40 bg-danger-bg/40 p-6">
        <h3 className="mt-0 text-h6 font-bold text-danger">Delete my account</h3>
        <p className="text-sm text-ink">
          This cannot be undone. Your name, email and sign-in details are erased permanently.
          Past orders are kept, without your account attached, because Estonian accounting law
          requires invoices to be retained for seven years.
        </p>

        {confirming ? (
          <form onSubmit={deleteMyAccount} className="max-w-sm">
            <label className="mb-3 block text-sm font-semibold text-ink">
              Confirm your password
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(ev) => setPassword(ev.target.value)}
                required
                className={`mt-1 ${FIELD_CLASS}`}
              />
            </label>
            <div className="flex flex-wrap items-center gap-3">
              <Button type="submit" variant="danger" disabled={busy || !password}>
                {busy ? 'Deleting…' : 'Permanently delete my account'}
              </Button>
              <Button type="button" variant="link" onClick={() => setConfirming(false)}>
                Cancel
              </Button>
            </div>
          </form>
        ) : (
          <Button type="button" variant="outlineDanger" onClick={() => setConfirming(true)}>
            Delete my account…
          </Button>
        )}
      </div>
    </section>
  );
}
