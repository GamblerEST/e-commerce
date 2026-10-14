import { useCallback, useEffect, useState } from 'react';
import { apiFetch, ApiRequestError } from '../../api/client';
import type {
  CategoryAdminView,
  ImportResult,
  ProductAdminView,
} from '../../api/types';
import { formatMoney } from '../../lib/format';
import {
  AdminError,
  AdminNotice,
  Button,
  Field,
  Pill,
  TableWrap,
  Td,
  Th,
  inputClass,
} from './ui';
import ProductAttributeEditor from './ProductAttributeEditor';
import ProductImageManager from './ProductImageManager';

type Draft = {
  name: string;
  slug: string;
  description: string;
  brand: string;
  price: string;
  currency: string;
  vatCategory: string;
  stockQuantity: string;
  categoryId: string;
  featured: boolean;
  weightKg: string;
  lengthCm: string;
  widthCm: string;
  heightCm: string;
};

const EMPTY: Draft = {
  name: '',
  slug: '',
  description: '',
  brand: '',
  price: '',
  currency: 'EUR',
  vatCategory: 'STANDARD',
  stockQuantity: '0',
  categoryId: '',
  featured: false,
  weightKg: '',
  lengthCm: '',
  widthCm: '',
  heightCm: '',
};

/**
 * Product administration — create, edit, delete (soft), restore, promote to
 * the home page, and bulk upload (JSON/CSV).
 *
 * Deletion is a SOFT delete server-side: order lines reference product ids,
 * so a hard delete would either cascade into financial records or fail. That
 * is why this screen has a "show deleted" toggle and a restore action at all —
 * without them, deleting a product would put it permanently out of reach even
 * though the row is still there.
 */
export default function AdminProductsPage() {
  const [rows, setRows] = useState<ProductAdminView[]>([]);
  const [categories, setCategories] = useState<CategoryAdminView[]>([]);
  const [q, setQ] = useState('');
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [editing, setEditing] = useState<ProductAdminView | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const params = new URLSearchParams();
    if (q.trim()) params.set('q', q.trim());
    if (includeDeleted) params.set('includeDeleted', 'true');
    const data = await apiFetch<ProductAdminView[]>(`/admin/products?${params}`);
    setRows(data);
  }, [q, includeDeleted]);

  useEffect(() => {
    load().catch((e) => setError(msg(e, 'Products could not be loaded.')));
  }, [load]);

  useEffect(() => {
    apiFetch<CategoryAdminView[]>('/categories')
      .then(setCategories)
      .catch(() => setCategories([]));
  }, []);

  function openCreate() {
    setEditing(null);
    setDraft(EMPTY);
    setShowForm(true);
    setError(null);
  }

  function openEdit(p: ProductAdminView) {
    setEditing(p);
    setDraft({
      name: p.name,
      slug: p.slug,
      description: p.description ?? '',
      brand: p.brand ?? '',
      price: String(p.price),
      currency: p.currency,
      vatCategory: p.vatCategory,
      stockQuantity: String(p.stockQuantity),
      categoryId: p.categoryId ?? '',
      featured: p.featured,
      weightKg: p.weightKg ?? '',
      lengthCm: p.lengthCm ?? '',
      widthCm: p.widthCm ?? '',
      heightCm: p.heightCm ?? '',
    });
    setShowForm(true);
    setError(null);
  }

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = {
        ...draft,
        // Empty string is not a valid UUID; the field is genuinely optional.
        categoryId: draft.categoryId || null,
        brand: draft.brand || null,
        price: draft.price,
        stockQuantity: Number(draft.stockQuantity),
        // Blank means "not recorded" (null), never 0 — a zero weight is a real,
        // wrong value, and the product page hides a dimension that is null.
        weightKg: draft.weightKg.trim() || null,
        lengthCm: draft.lengthCm.trim() || null,
        widthCm: draft.widthCm.trim() || null,
        heightCm: draft.heightCm.trim() || null,
      };
      if (editing) {
        await apiFetch(`/admin/products/${editing.id}`, { method: 'PUT', body });
        setNotice(`Saved "${draft.name}".`);
      } else {
        await apiFetch('/admin/products', { method: 'POST', body });
        setNotice(`Created "${draft.name}".`);
      }
      setShowForm(false);
      await load();
    } catch (e) {
      setError(msg(e, 'The product could not be saved.'));
    } finally {
      setBusy(false);
    }
  }

  async function act(p: ProductAdminView, action: 'delete' | 'restore') {
    setBusy(true);
    setError(null);
    try {
      if (action === 'delete') {
        await apiFetch(`/admin/products/${p.id}`, { method: 'DELETE' });
        setNotice(`"${p.name}" removed from the shop. It can be restored.`);
      } else {
        await apiFetch(`/admin/products/${p.id}/restore`, { method: 'POST' });
        setNotice(`"${p.name}" is back in the shop.`);
      }
      await load();
    } catch (e) {
      setError(msg(e, 'That action failed.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="products-heading">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h2 id="products-heading" className="text-h3 font-extrabold text-ink">
          Products
        </h2>
        <Button variant="primary" onClick={openCreate}>
          New product
        </Button>
      </div>

      <AdminError message={error} />
      <AdminNotice message={notice} />

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <Field label="Search by name">
          <input
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            className={inputClass}
            placeholder="e.g. skillet"
          />
        </Field>
        <label className="flex items-center gap-2 pb-2 text-sm font-semibold text-ink">
          <input
            type="checkbox"
            checked={includeDeleted}
            onChange={(e) => setIncludeDeleted(e.target.checked)}
          />
          Show removed products
        </label>
      </div>

      {showForm && (
        <form
          onSubmit={save}
          className="mb-6 rounded-card border border-hairline bg-surface p-6"
        >
          <h3 className="mb-4 text-h4 font-bold text-ink">
            {editing ? `Edit ${editing.name}` : 'New product'}
          </h3>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Name">
              <input
                required
                maxLength={200}
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Slug" hint="Lowercase letters, digits and hyphens — it is the URL.">
              <input
                required
                pattern="[a-z0-9]+(-[a-z0-9]+)*"
                maxLength={200}
                value={draft.slug}
                onChange={(e) => setDraft({ ...draft, slug: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Brand">
              <input
                maxLength={120}
                value={draft.brand}
                onChange={(e) => setDraft({ ...draft, brand: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Category">
              <select
                value={draft.categoryId}
                onChange={(e) => setDraft({ ...draft, categoryId: e.target.value })}
                className={inputClass}
              >
                <option value="">— none —</option>
                {categories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Price (VAT included)" hint="Two decimal places, e.g. 45.00">
              <input
                required
                inputMode="decimal"
                value={draft.price}
                onChange={(e) => setDraft({ ...draft, price: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Currency">
              <input
                required
                pattern="[A-Z]{3}"
                maxLength={3}
                value={draft.currency}
                onChange={(e) => setDraft({ ...draft, currency: e.target.value.toUpperCase() })}
                className={inputClass}
              />
            </Field>
            <Field label="VAT category" hint="Must match a configured rate (DECISIONS.md §4).">
              <input
                required
                value={draft.vatCategory}
                onChange={(e) => setDraft({ ...draft, vatCategory: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Stock quantity">
              <input
                required
                type="number"
                min={0}
                value={draft.stockQuantity}
                onChange={(e) => setDraft({ ...draft, stockQuantity: e.target.value })}
                className={inputClass}
              />
            </Field>
          </div>

          <fieldset className="mt-4 rounded-field border border-hairline p-4">
            <legend className="px-1 text-sm font-semibold text-ink">
              Weight and dimensions (optional)
            </legend>
            <p className="mb-3 text-sm text-muted">
              Metric only — pounds and inches are worked out for the product page. Leave a field
              blank if it is not known.
            </p>
            <div className="grid gap-4 sm:grid-cols-4">
              {(
                [
                  ['weightKg', 'Weight (kg)'],
                  ['lengthCm', 'Length (cm)'],
                  ['widthCm', 'Width (cm)'],
                  ['heightCm', 'Height (cm)'],
                ] as const
              ).map(([key, label]) => (
                <Field key={key} label={label}>
                  <input
                    inputMode="decimal"
                    value={draft[key]}
                    onChange={(e) => setDraft({ ...draft, [key]: e.target.value })}
                    className={inputClass}
                  />
                </Field>
              ))}
            </div>
          </fieldset>

          {editing ? (
            <>
              <ProductImageManager productId={editing.id} />
              <ProductAttributeEditor productId={editing.id} />
            </>
          ) : (
            <p className="mt-4 text-sm text-muted">
              Save the product first, then edit it to add images and attributes.
            </p>
          )}

          <Field label="Description">
            <textarea
              rows={3}
              maxLength={5000}
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              className={`${inputClass} mt-4`}
            />
          </Field>

          <label className="mt-4 flex items-center gap-2 text-sm font-semibold text-ink">
            <input
              type="checkbox"
              checked={draft.featured}
              onChange={(e) => setDraft({ ...draft, featured: e.target.checked })}
            />
            Feature on the home page
          </label>

          <div className="mt-6 flex gap-3">
            <Button type="submit" variant="primary" disabled={busy}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Create product'}
            </Button>
            <Button onClick={() => setShowForm(false)}>Cancel</Button>
          </div>
        </form>
      )}

      <TableWrap label="Products">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr>
              <Th>Name</Th>
              <Th>Price</Th>
              <Th>Stock</Th>
              <Th>State</Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <Td className="text-muted">No products match.</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
              </tr>
            )}
            {rows.map((p) => (
              <tr key={p.id} className={p.deleted ? 'opacity-60' : undefined}>
                <Td>
                  <span className="font-semibold text-ink">{p.name}</span>
                  <span className="block text-xs text-muted">/{p.slug}</span>
                </Td>
                <Td className="whitespace-nowrap">{formatMoney(p.price, p.currency)}</Td>
                <Td>
                  {p.stockQuantity > 0 ? (
                    p.stockQuantity
                  ) : (
                    <Pill tone="bad">Out of stock</Pill>
                  )}
                </Td>
                <Td>
                  <span className="flex flex-wrap gap-1">
                    {p.deleted && <Pill tone="bad">Removed</Pill>}
                    {p.featured && <Pill tone="warn">Featured</Pill>}
                    {!p.deleted && !p.featured && <Pill>Live</Pill>}
                  </span>
                </Td>
                <Td className="text-right">
                  <span className="flex justify-end gap-2">
                    <Button onClick={() => openEdit(p)} disabled={busy}>
                      Edit
                    </Button>
                    {p.deleted ? (
                      <Button onClick={() => act(p, 'restore')} disabled={busy}>
                        Restore
                      </Button>
                    ) : (
                      <Button variant="danger" onClick={() => act(p, 'delete')} disabled={busy}>
                        Remove
                      </Button>
                    )}
                  </span>
                </Td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableWrap>

      <BulkUpload
        onDone={(result) => {
          setNotice(`Imported ${result.imported} ${result.imported === 1 ? 'product' : 'products'}.`);
          load().catch(() => {});
        }}
        // Every attempt starts by reporting an empty error, so this also clears the
        // previous import's success message: the top of the page shows only the last try.
        onError={(message) => {
          setNotice(null);
          setError(message);
        }}
      />
    </section>
  );
}

/**
 * Bulk upload (JSON or CSV).
 *
 * The import is transactional server-side: either every row lands or none do.
 * That is why errors are shown as a full LIST rather than "row 12 failed" —
 * fixing one row at a time against an all-or-nothing import would mean
 * re-uploading the file once per mistake.
 */
function BulkUpload({
  onDone,
  onError,
}: {
  onDone: (r: ImportResult) => void;
  onError: (m: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [rowErrors, setRowErrors] = useState<Array<{ row: number; field: string; message: string }>>(
    [],
  );

  async function upload(file: File) {
    setBusy(true);
    setRowErrors([]);
    onError('');
    try {
      const isCsv = file.name.toLowerCase().endsWith('.csv');
      let result: ImportResult;

      if (isCsv) {
        // Multipart, so Content-Type must NOT be forced to application/json —
        // apiFetch always sets that, so this one call goes through fetch
        // directly. It is a same-origin admin POST with the CSRF header
        // attached manually; no auth-retry subtlety applies because the admin
        // is definitionally already authenticated on this screen.
        const form = new FormData();
        form.append('file', file);
        const csrf = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
        const res = await fetch('/api/admin/products/bulk/csv', {
          method: 'POST',
          credentials: 'include',
          headers: csrf ? { 'X-XSRF-TOKEN': decodeURIComponent(csrf[1]) } : {},
          body: form,
        });
        const body = await res.json();
        if (!res.ok) {
          if (Array.isArray(body?.errors)) setRowErrors(body.errors);
          throw new Error(body?.message ?? 'The CSV could not be imported.');
        }
        result = body;
      } else {
        const rows = JSON.parse(await file.text());
        result = await apiFetch<ImportResult>('/admin/products/bulk', {
          method: 'POST',
          body: rows,
        });
      }
      onDone(result);
    } catch (e) {
      // A rejected import comes back with the full row list. The JSON path goes
      // through apiFetch, which keeps it on the error, so show it the same way
      // as the CSV path does.
      if (e instanceof ApiRequestError && Array.isArray(e.errors)) {
        setRowErrors(e.errors as unknown as Array<{ row: number; field: string; message: string }>);
      }
      onError(e instanceof Error ? e.message : 'The upload failed.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="bulk-heading" className="mt-10 rounded-card border border-hairline bg-surface p-6">
      <h3 id="bulk-heading" className="text-h4 font-bold text-ink">
        Bulk upload
      </h3>
      <p className="mt-1 mb-4 text-sm text-muted">
        A <code>.json</code> array or a <code>.csv</code> with a header row. Columns match the
        product fields (<code>name</code>, <code>slug</code>, <code>price</code>,{' '}
        <code>currency</code>, <code>vatCategory</code>, <code>stockQuantity</code>,{' '}
        <code>featured</code>…). The whole file is imported or none of it is.
      </p>

      {/* The native input is visually hidden, not removed, so keyboard users can
          still Tab to it and the label below stays the accessible name. The
          label is styled as the secondary button, so the control reads as an
          action rather than as text. */}
      <label
        className={`inline-flex cursor-pointer items-center justify-center gap-2 whitespace-nowrap rounded-field border border-hairline bg-surface px-4 py-2 font-bold text-ink hover:bg-canvas focus-within:outline focus-within:outline-3 focus-within:outline-offset-2 focus-within:outline-ink ${busy ? 'pointer-events-none opacity-60' : ''}`}
      >
        Choose a file to import
        <input
          type="file"
          accept=".json,.csv,application/json,text/csv"
          disabled={busy}
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f) upload(f);
            // Reset so re-selecting the SAME file after fixing it still fires.
            e.target.value = '';
          }}
          className="sr-only"
        />
      </label>
      {busy && <p className="mt-2 text-sm text-muted">Importing…</p>}

      {rowErrors.length > 0 && (
        <div className="mt-4">
          <h4 className="text-sm font-bold text-danger">
            {rowErrors.length} {rowErrors.length === 1 ? 'row was' : 'rows were'} rejected — nothing
            was imported
          </h4>
          <ul className="mt-2 list-none space-y-1 p-0 text-sm">
            {rowErrors.map((err, i) => (
              <li key={i} className="text-danger">
                Row {err.row}, <strong>{err.field}</strong>: {err.message}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

function msg(e: unknown, fallback: string): string {
  return e instanceof ApiRequestError || e instanceof Error ? e.message : fallback;
}
