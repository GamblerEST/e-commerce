import { useCallback, useEffect, useState } from 'react';
import { apiFetch, ApiRequestError } from '../../api/client';
import type { AdminUserView } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { formatDate } from '../../lib/format';
import { AdminError, AdminNotice, Button, Pill, TableWrap, Td, Th, inputClass } from './ui';

const ROLES = ['CUSTOMER', 'SUPPORT', 'ADMIN'];

/**
 * SUPPORT is also the sales role. The people handling orders and customers
 * after a sale are the people handling them before one, and a separate SALES
 * role would grant exactly the same access. The label says so, so an admin
 * assigning a salesperson picks it without having to guess.
 */
const ROLE_LABELS: Record<string, string> = {
  CUSTOMER: 'Customer',
  SUPPORT: 'Support & sales',
  ADMIN: 'Admin',
};

/**
 * User and role management.
 *
 * Two things this screen deliberately cannot do:
 *
 *  - Delete a user. Account removal is ANONYMIZATION (GDPR erasure via
 *    anonymize_user()), because orders and invoices are retained seven years
 *    under the Estonian Accounting Act. A delete button here would imply a
 *    capability that must not exist.
 *  - Change your own role. The server refuses it; the UI disables it too, so
 *    the last admin cannot lock themselves out of their own shop by demoting
 *    the only account that could promote anyone back.
 */
export default function AdminUsersPage() {
  const { user: me } = useAuth();
  const [rows, setRows] = useState<AdminUserView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    setRows(await apiFetch<AdminUserView[]>('/admin/users?size=100'));
  }, []);

  useEffect(() => {
    load().catch((e) => setError(m(e, 'Users could not be loaded.')));
  }, [load]);

  async function changeRole(u: AdminUserView, role: string) {
    setBusy(u.id);
    setError(null);
    try {
      await apiFetch(`/admin/users/${u.id}/role?role=${role}`, { method: 'PUT' });
      setNotice(`${u.email ?? 'Account'} is now ${ROLE_LABELS[role] ?? role}.`);
      await load();
    } catch (e) {
      setError(m(e, 'The role could not be changed.'));
    } finally {
      setBusy(null);
    }
  }

  async function setDisabled(u: AdminUserView, disabled: boolean) {
    setBusy(u.id);
    setError(null);
    try {
      await apiFetch(`/admin/users/${u.id}/disabled?disabled=${disabled}`, { method: 'PUT' });
      setNotice(`${u.email ?? 'Account'} ${disabled ? 'disabled' : 're-enabled'}.`);
      await load();
    } catch (e) {
      setError(m(e, 'That account could not be updated.'));
    } finally {
      setBusy(null);
    }
  }

  const isAdmin = me?.role === 'ADMIN';

  return (
    <section aria-labelledby="users-heading">
      <h2 id="users-heading" className="mb-1 text-h3 font-extrabold text-ink">
        Users
      </h2>
      <p className="mb-4 text-sm text-muted">
        {isAdmin
          ? 'Assign roles and disable accounts. Admin accounts must have two-factor authentication enabled before they can use admin functions.'
          : 'Read-only: changing roles requires an administrator.'}
      </p>

      <AdminError message={error} />
      <AdminNotice message={notice} />

      <TableWrap label="Users">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr>
              <Th>Account</Th>
              <Th>Joined</Th>
              <Th>State</Th>
              <Th>Role</Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </thead>
          <tbody>
            {rows.map((u) => {
              const self = u.id === me?.userId;
              return (
                <tr key={u.id} className={u.anonymized ? 'opacity-60' : undefined}>
                  <Td>
                    <span className="font-semibold text-ink">
                      {u.displayName ?? '(no name)'}
                      {self && <span className="ml-2 text-xs text-muted">(you)</span>}
                    </span>
                    <span className="block text-xs text-muted">
                      {u.email ?? '(erased account)'}
                    </span>
                  </Td>
                  <Td className="whitespace-nowrap">{formatDate(u.createdAt)}</Td>
                  <Td>
                    <span className="flex flex-wrap gap-1">
                      {u.anonymized && <Pill tone="bad">Erased</Pill>}
                      {u.disabled && <Pill tone="bad">Disabled</Pill>}
                      {!u.emailVerified && !u.anonymized && <Pill tone="warn">Unverified</Pill>}
                      {u.twoFactorEnabled && <Pill tone="good">2FA</Pill>}
                      {/* An ADMIN without 2FA can sign in but every admin
                          endpoint refuses them (@AdminOnly checks both), so
                          this state is worth surfacing — otherwise it looks
                          like the panel is broken for that person. */}
                      {u.role === 'ADMIN' && !u.twoFactorEnabled && (
                        <Pill tone="bad">Admin without 2FA</Pill>
                      )}
                    </span>
                  </Td>
                  <Td>
                    <select
                      value={u.role}
                      disabled={!isAdmin || self || u.anonymized || busy === u.id}
                      onChange={(e) => changeRole(u, e.target.value)}
                      aria-label={`Role for ${u.email ?? 'account'}`}
                      title={self ? 'You cannot change your own role' : undefined}
                      className={`${inputClass} w-36`}
                    >
                      {ROLES.map((r) => (
                        <option key={r} value={r}>
                          {ROLE_LABELS[r] ?? r}
                        </option>
                      ))}
                    </select>
                  </Td>
                  <Td className="text-right">
                    {!u.anonymized && (
                      <Button
                        variant={u.disabled ? 'secondary' : 'danger'}
                        disabled={!isAdmin || self || busy === u.id}
                        onClick={() => setDisabled(u, !u.disabled)}
                        title={self ? 'You cannot disable your own account' : undefined}
                      >
                        {u.disabled ? 'Enable' : 'Disable'}
                      </Button>
                    )}
                  </Td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </TableWrap>
    </section>
  );
}

function m(e: unknown, fallback: string): string {
  return e instanceof ApiRequestError || e instanceof Error ? e.message : fallback;
}
