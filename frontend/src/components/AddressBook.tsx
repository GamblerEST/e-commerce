import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { apiFetch, ApiRequestError } from '../api/client';
import AddressLookup from './AddressLookup';
import type { AddressResponse } from '../api/types';
import { Button } from './ui/button';
import { Checkbox } from './ui/checkbox';
import { Label } from './ui/label';

const EMPTY = {
  label: '',
  recipientName: '',
  phone: '',
  line1: '',
  line2: '',
  city: '',
  postalCode: '',
  country: 'EE',
  adsOid: null as string | null,
  makeDefault: false,
};

// Editing any of these after picking a register address turns it back into a
// typed one, so the server checks exactly what was picked or nothing at all.
const ADDRESS_FIELDS = new Set<string>(['line1', 'city', 'postalCode', 'country']);

const FIELD_CLASS = 'w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink';

/**
 * Saved delivery addresses on the account page.
 *
 * Before this the only thing resembling an address book was checkout
 * prefilling from the last order — which cannot hold a second address, cannot
 * be corrected, and vanishes as soon as someone orders somewhere else.
 *
 * Editing an address here never changes a past order: orders keep their own
 * snapshot of where they were sent, so a corrected typo cannot rewrite what an
 * old invoice says.
 */
export default function AddressBook() {
  const [addresses, setAddresses] = useState<AddressResponse[]>([]);
  const [draft, setDraft] = useState({ ...EMPTY });
  const [editingId, setEditingId] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setAddresses(await apiFetch<AddressResponse[]>('/account/addresses'));
  }, []);

  useEffect(() => {
    load().catch(() => setError('Your saved addresses could not be loaded.'));
  }, [load]);

  function startNew() {
    setDraft({ ...EMPTY, makeDefault: addresses.length === 0 });
    setEditingId(null);
    setFieldErrors({});
    setShowForm(true);
  }

  function startEdit(a: AddressResponse) {
    setDraft({
      label: a.label ?? '',
      recipientName: a.recipientName,
      phone: a.phone ?? '',
      line1: a.line1,
      line2: a.line2 ?? '',
      city: a.city,
      postalCode: a.postalCode,
      country: a.country,
      makeDefault: a.isDefault,
      // Saved addresses do not keep the register id; an edit starts as typed.
      adsOid: null,
    });
    setEditingId(a.id);
    setFieldErrors({});
    setShowForm(true);
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      const body = {
        ...draft,
        label: draft.label || null,
        phone: draft.phone || null,
        line2: draft.line2 || null,
      };
      await apiFetch(editingId ? `/account/addresses/${editingId}` : '/account/addresses', {
        method: editingId ? 'PUT' : 'POST',
        body,
      });
      setShowForm(false);
      await load();
    } catch (err) {
      if (err instanceof ApiRequestError) {
        setError(err.message);
        if (err.errors) setFieldErrors(err.errors);
      } else {
        setError('That address could not be saved.');
      }
    } finally {
      setBusy(false);
    }
  }

  async function remove(a: AddressResponse) {
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/account/addresses/${a.id}`, { method: 'DELETE' });
      await load();
    } catch {
      setError('That address could not be deleted.');
    } finally {
      setBusy(false);
    }
  }

  async function makeDefault(a: AddressResponse) {
    setBusy(true);
    try {
      await apiFetch(`/account/addresses/${a.id}`, {
        method: 'PUT',
        body: {
          label: a.label,
          recipientName: a.recipientName,
          phone: a.phone,
          line1: a.line1,
          line2: a.line2,
          city: a.city,
          postalCode: a.postalCode,
          country: a.country,
          makeDefault: true,
        },
      });
      await load();
    } catch {
      setError('That address could not be made the default.');
    } finally {
      setBusy(false);
    }
  }

  const field = (
    name: keyof typeof EMPTY,
    label: string,
    required = false,
    extra: Record<string, unknown> = {},
  ) => (
    <label className="mb-3 block text-sm font-semibold text-ink">
      {label}
      <input
        value={String(draft[name] ?? '')}
        required={required}
        aria-invalid={Boolean(fieldErrors[name])}
        onChange={(e) =>
          setDraft({
            ...draft,
            [name]: e.target.value,
            ...(ADDRESS_FIELDS.has(name) ? { adsOid: null } : {}),
          })
        }
        className={`mt-1 ${FIELD_CLASS}`}
        {...extra}
      />
      {fieldErrors[name] && <span className="mt-1 block text-sm font-normal text-danger">{fieldErrors[name]}</span>}
    </label>
  );

  return (
    <section aria-labelledby="address-book-heading" className="mt-12">
      <h2 id="address-book-heading" className="text-h3 font-extrabold text-ink">
        Delivery addresses
      </h2>

      {error && (
        <p role="alert" className="mt-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
          {error}
        </p>
      )}

      {addresses.length === 0 && !showForm && (
        <p className="mt-4 text-muted">You have no saved addresses yet.</p>
      )}

      {addresses.length > 0 && (
        <ul className="mt-4 grid list-none gap-4 p-0 sm:grid-cols-2">
          {addresses.map((a) => (
            <li key={a.id} className="flex flex-col justify-between gap-3 rounded-card border border-hairline bg-surface p-4">
              <div>
                <p className="m-0 mb-1 flex flex-wrap items-center gap-2 font-bold text-ink">
                  {a.label || a.recipientName}
                  {a.isDefault && (
                    <span className="rounded-full bg-ok-bg px-2 py-0.5 text-xs font-bold text-ok">
                      Default
                    </span>
                  )}
                </p>
                <address className="text-sm not-italic text-muted">
                  {a.recipientName}
                  <br />
                  {a.line1}
                  {a.line2 && (
                    <>
                      <br />
                      {a.line2}
                    </>
                  )}
                  <br />
                  {a.postalCode} {a.city}
                  <br />
                  {a.country}
                  {a.phone && (
                    <>
                      <br />
                      {a.phone}
                    </>
                  )}
                </address>
              </div>
              <div className="flex flex-wrap gap-1">
                <Button type="button" variant="link" onClick={() => startEdit(a)} disabled={busy}>
                  Edit
                </Button>
                {!a.isDefault && (
                  <Button type="button" variant="link" onClick={() => makeDefault(a)} disabled={busy}>
                    Make default
                  </Button>
                )}
                <Button type="button" variant="link" onClick={() => remove(a)} disabled={busy} className="text-danger">
                  Delete
                </Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {showForm ? (
        <form onSubmit={save} className="mt-6 max-w-md rounded-card border border-hairline bg-surface p-6">
          <h3 className="mt-0 mb-4 text-h4 font-bold text-ink">
            {editingId ? 'Edit address' : 'New address'}
          </h3>
          {field('label', 'Label (optional, e.g. Home)')}
          {field('recipientName', 'Recipient name', true)}
          {field('phone', 'Phone (optional)')}
          {draft.country === 'EE' && (
            <div className="mb-3">
              <AddressLookup
                onSelect={(s) =>
                  setDraft({
                    ...draft,
                    line1: s.line1,
                    // A resolved apartment replaces it; an unresolved one leaves
                    // whatever was already typed there alone, rather than wiping
                    // a manual note the register has no way to know about.
                    line2: s.apartment || draft.line2,
                    city: s.city,
                    postalCode: s.postalCode,
                    country: s.country,
                    adsOid: s.adsOid,
                  })
                }
              />
            </div>
          )}
          {draft.adsOid && (
            <p role="status" className="mb-3 text-sm text-muted">
              Matched in the Estonian address register.
            </p>
          )}
          {field('line1', 'Street address', true)}
          {field('line2', 'Apartment, suite, etc. (optional)')}
          {field('city', 'City', true)}
          {field('postalCode', 'Postal code', true)}
          {field('country', 'Country', true, { maxLength: 2 })}
          <div className="mb-4 flex items-center gap-2">
            <Checkbox
              id="address-make-default"
              checked={draft.makeDefault}
              onCheckedChange={(v) => setDraft({ ...draft, makeDefault: v === true })}
            />
            <Label htmlFor="address-make-default" className="m-0 font-normal">
              Use as my default delivery address
            </Label>
          </div>
          <div className="flex flex-wrap gap-3">
            <Button type="submit" disabled={busy}>
              {busy ? 'Saving…' : editingId ? 'Save changes' : 'Save address'}
            </Button>
            <Button type="button" variant="secondary" onClick={() => setShowForm(false)}>
              Cancel
            </Button>
          </div>
        </form>
      ) : (
        <Button type="button" variant="secondary" onClick={startNew} disabled={busy} className="mt-6">
          Add an address
        </Button>
      )}
    </section>
  );
}
