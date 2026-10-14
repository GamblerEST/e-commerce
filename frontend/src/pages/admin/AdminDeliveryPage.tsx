import { useCallback, useEffect, useState } from 'react';
import { apiFetch, ApiRequestError } from '../../api/client';
import type { AdminDeliveryOptionView } from '../../api/types';
import { formatMoney } from '../../lib/format';
import { AdminError, AdminNotice, Button, Field, Pill, TableWrap, Td, Th, inputClass } from './ui';

type Draft = {
  code: string;
  name: string;
  carrier: string;
  price: string;
  currency: string;
  leadTimeDaysMin: string;
  leadTimeDaysMax: string;
  active: boolean;
  sortOrder: string;
};

const EMPTY: Draft = {
  code: '',
  name: '',
  carrier: '',
  price: '0.00',
  currency: 'EUR',
  leadTimeDaysMin: '1',
  leadTimeDaysMax: '3',
  active: true,
  sortOrder: '0',
};

/**
 * Delivery options — admins manage these alongside order status.
 *
 * Two rules from the schema surface directly in this UI:
 *
 *  - `code` is editable only on CREATE. Orders snapshot the delivery method
 *    (V17), so changing a code afterwards would orphan what an old order
 *    displays. The field is therefore disabled when editing rather than
 *    silently ignored by the server.
 *  - Removing an option RETIRES it rather than deleting the row, for the same
 *    reason — so the button says "Retire", not "Delete".
 */
export default function AdminDeliveryPage() {
  const [rows, setRows] = useState<AdminDeliveryOptionView[]>([]);
  const [editing, setEditing] = useState<AdminDeliveryOptionView | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setRows(await apiFetch<AdminDeliveryOptionView[]>('/admin/delivery-options'));
  }, []);

  useEffect(() => {
    load().catch((e) => setError(m(e, 'Delivery options could not be loaded.')));
  }, [load]);

  function openCreate() {
    setEditing(null);
    setDraft(EMPTY);
    setShowForm(true);
    setError(null);
  }

  function openEdit(o: AdminDeliveryOptionView) {
    setEditing(o);
    setDraft({
      code: o.code,
      name: o.name,
      carrier: o.carrier ?? '',
      price: String(o.price),
      currency: o.currency,
      leadTimeDaysMin: String(o.leadTimeDaysMin),
      leadTimeDaysMax: String(o.leadTimeDaysMax),
      active: o.active,
      sortOrder: String(o.sortOrder),
    });
    setShowForm(true);
    setError(null);
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (Number(draft.leadTimeDaysMin) > Number(draft.leadTimeDaysMax)) {
      setError('The earliest delivery day cannot be after the latest.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const body = {
        code: draft.code,
        name: draft.name,
        carrier: draft.carrier || null,
        price: draft.price,
        currency: draft.currency,
        leadTimeDaysMin: Number(draft.leadTimeDaysMin),
        leadTimeDaysMax: Number(draft.leadTimeDaysMax),
        active: draft.active,
        sortOrder: Number(draft.sortOrder),
      };
      if (editing) {
        await apiFetch(`/admin/delivery-options/${editing.id}`, { method: 'PUT', body });
        setNotice(`Saved "${draft.name}".`);
      } else {
        await apiFetch('/admin/delivery-options', { method: 'POST', body });
        setNotice(`Created "${draft.name}".`);
      }
      setShowForm(false);
      await load();
    } catch (e) {
      setError(m(e, 'The delivery option could not be saved.'));
    } finally {
      setBusy(false);
    }
  }

  async function retire(o: AdminDeliveryOptionView) {
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/admin/delivery-options/${o.id}`, { method: 'DELETE' });
      setNotice(`"${o.name}" retired. Existing orders are unaffected.`);
      await load();
    } catch (e) {
      setError(m(e, 'That option could not be retired.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="delivery-heading">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h2 id="delivery-heading" className="text-h3 font-extrabold text-ink">
          Delivery options
        </h2>
        <Button variant="primary" onClick={openCreate}>
          New option
        </Button>
      </div>

      <AdminError message={error} />
      <AdminNotice message={notice} />

      {showForm && (
        <form onSubmit={save} className="mb-6 rounded-card border border-hairline bg-surface p-6">
          <h3 className="mb-4 text-h4 font-bold text-ink">
            {editing ? `Edit ${editing.name}` : 'New delivery option'}
          </h3>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field
              label="Code"
              hint={
                editing
                  ? 'Fixed after creation — orders snapshot it.'
                  : 'Lowercase letters, digits and underscores, e.g. omniva_parcel.'
              }
            >
              <input
                required
                disabled={Boolean(editing)}
                pattern="[a-z0-9]+(_[a-z0-9]+)*"
                maxLength={60}
                value={draft.code}
                onChange={(e) => setDraft({ ...draft, code: e.target.value })}
                className={`${inputClass} disabled:opacity-60`}
              />
            </Field>
            <Field label="Name shown to customers">
              <input
                required
                maxLength={120}
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Carrier">
              <input
                maxLength={120}
                value={draft.carrier}
                onChange={(e) => setDraft({ ...draft, carrier: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Price (VAT included)">
              <input
                required
                inputMode="decimal"
                value={draft.price}
                onChange={(e) => setDraft({ ...draft, price: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Earliest delivery (days)">
              <input
                required
                type="number"
                min={0}
                value={draft.leadTimeDaysMin}
                onChange={(e) => setDraft({ ...draft, leadTimeDaysMin: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Latest delivery (days)" hint="The estimate shown to customers is a range.">
              <input
                required
                type="number"
                min={0}
                value={draft.leadTimeDaysMax}
                onChange={(e) => setDraft({ ...draft, leadTimeDaysMax: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Sort order" hint="Lower numbers appear first at checkout.">
              <input
                required
                type="number"
                value={draft.sortOrder}
                onChange={(e) => setDraft({ ...draft, sortOrder: e.target.value })}
                className={inputClass}
              />
            </Field>
          </div>

          <label className="mt-4 flex items-center gap-2 text-sm font-semibold text-ink">
            <input
              type="checkbox"
              checked={draft.active}
              onChange={(e) => setDraft({ ...draft, active: e.target.checked })}
            />
            Offer this at checkout
          </label>

          <div className="mt-6 flex gap-3">
            <Button type="submit" variant="primary" disabled={busy}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Create option'}
            </Button>
            <Button onClick={() => setShowForm(false)}>Cancel</Button>
          </div>
        </form>
      )}

      <TableWrap label="Delivery options">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr>
              <Th>Name</Th>
              <Th>Carrier</Th>
              <Th>Price</Th>
              <Th>Estimate</Th>
              <Th>State</Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </thead>
          <tbody>
            {rows.map((o) => (
              <tr key={o.id} className={o.active ? undefined : 'opacity-60'}>
                <Td>
                  <span className="font-semibold text-ink">{o.name}</span>
                  <span className="block font-mono text-xs text-muted">{o.code}</span>
                </Td>
                <Td>{o.carrier ?? '—'}</Td>
                <Td className="whitespace-nowrap">{formatMoney(o.price, o.currency)}</Td>
                <Td className="whitespace-nowrap">
                  {o.leadTimeDaysMin === o.leadTimeDaysMax
                    ? `${o.leadTimeDaysMax} days`
                    : `${o.leadTimeDaysMin}–${o.leadTimeDaysMax} days`}
                </Td>
                <Td>
                  <Pill tone={o.active ? 'good' : 'neutral'}>{o.active ? 'Offered' : 'Retired'}</Pill>
                </Td>
                <Td className="text-right">
                  <span className="flex justify-end gap-2">
                    <Button onClick={() => openEdit(o)} disabled={busy}>
                      Edit
                    </Button>
                    {o.active && (
                      <Button variant="danger" onClick={() => retire(o)} disabled={busy}>
                        Retire
                      </Button>
                    )}
                  </span>
                </Td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableWrap>
    </section>
  );
}

function m(e: unknown, fallback: string): string {
  return e instanceof ApiRequestError || e instanceof Error ? e.message : fallback;
}
