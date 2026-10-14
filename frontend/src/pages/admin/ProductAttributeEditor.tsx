import { useEffect, useState } from 'react';
import { apiFetch, ApiRequestError } from '../../api/client';
import { AdminError, Button, Field, inputClass } from './ui';

type Row = { name: string; values: string };

/**
 * The filterable facts about ONE existing product — colour, size, material and
 * so on. These are what the shop's filters offer, so a product with none simply
 * never shows up under one.
 *
 * Each row is a name and its values separated by commas ("red, blue"). Saving
 * replaces the product's whole set with what is on screen, so nothing stale can
 * be left behind. Saved on its own, like the images, so a product edit can never
 * undo it. The server tidies the names (lowercase, trimmed) and drops repeats.
 */
export default function ProductAttributeEditor({ productId }: { productId: string }) {
  const [rows, setRows] = useState<Row[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    apiFetch<Record<string, string[]>>(`/admin/products/${productId}/attributes`)
      .then((data) => setRows(toRows(data)))
      .catch((e) => setError(message(e, 'The attributes could not be loaded.')));
  }, [productId]);

  function update(i: number, patch: Partial<Row>) {
    setSaved(false);
    setRows(rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r)));
  }

  async function save() {
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const body: Record<string, string[]> = {};
      for (const row of rows) {
        const name = row.name.trim();
        if (!name) continue;
        body[name] = [...(body[name] ?? []), ...row.values.split(',')];
      }
      const result = await apiFetch<Record<string, string[]>>(`/admin/products/${productId}/attributes`, {
        method: 'PUT',
        body,
      });
      setRows(toRows(result));
      setSaved(true);
    } catch (e) {
      setError(message(e, 'The attributes could not be saved.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <fieldset className="mt-4 rounded-field border border-hairline p-4">
      <legend className="px-1 text-sm font-semibold text-ink">Attributes (used by the shop's filters)</legend>
      <AdminError message={error} />
      {saved && (
        <p role="status" className="mb-3 text-sm font-semibold text-ok">
          Attributes saved.
        </p>
      )}

      {rows.length === 0 && (
        <p className="mb-3 text-sm text-muted">
          No attributes yet. Add one, e.g. “colour” with the values “red, blue”.
        </p>
      )}

      <div className="space-y-3">
        {rows.map((row, i) => (
          <div key={i} className="grid items-end gap-3 sm:grid-cols-[1fr_2fr_auto]">
            <Field label="Name">
              <input
                maxLength={60}
                value={row.name}
                onChange={(e) => update(i, { name: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Values, separated by commas">
              <input
                value={row.values}
                onChange={(e) => update(i, { values: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Button
              variant="danger"
              onClick={() => {
                setSaved(false);
                setRows(rows.filter((_, idx) => idx !== i));
              }}
              disabled={busy}
            >
              Remove
            </Button>
          </div>
        ))}
      </div>

      <div className="mt-3 flex flex-wrap gap-3">
        <Button
          onClick={() => {
            setSaved(false);
            setRows([...rows, { name: '', values: '' }]);
          }}
          disabled={busy}
        >
          Add attribute
        </Button>
        <Button variant="primary" onClick={save} disabled={busy}>
          {busy ? 'Saving…' : 'Save attributes'}
        </Button>
      </div>
    </fieldset>
  );
}

function toRows(data: Record<string, string[]>): Row[] {
  return Object.entries(data).map(([name, values]) => ({ name, values: values.join(', ') }));
}

function message(e: unknown, fallback: string): string {
  return e instanceof ApiRequestError || e instanceof Error ? e.message : fallback;
}
