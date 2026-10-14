import { useCallback, useEffect, useState } from 'react';
import { apiFetch, ApiRequestError } from '../../api/client';
import type { CategoryAdminView } from '../../api/types';
import { AdminError, AdminNotice, Button, Field, Pill, TableWrap, Td, Th, inputClass } from './ui';

type Draft = {
  name: string;
  slug: string;
  parentId: string;
  featured: boolean;
  tagline: string;
};

const EMPTY: Draft = { name: '', slug: '', parentId: '', featured: false, tagline: '' };

/**
 * Category management, and with it the home page's "collections".
 *
 * A collection is not a separate concept — it is a category an admin has
 * promoted (V18). Managing both here rather than on a separate screen is
 * deliberate: two screens would imply two taxonomies to keep in sync.
 */
export default function AdminCategoriesPage() {
  const [rows, setRows] = useState<CategoryAdminView[]>([]);
  const [editing, setEditing] = useState<CategoryAdminView | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [showForm, setShowForm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setRows(await apiFetch<CategoryAdminView[]>('/categories'));
  }, []);

  useEffect(() => {
    load().catch((e) => setError(m(e, 'Categories could not be loaded.')));
  }, [load]);

  function openCreate() {
    setEditing(null);
    setDraft(EMPTY);
    setShowForm(true);
    setError(null);
  }

  function openEdit(c: CategoryAdminView) {
    setEditing(c);
    setDraft({
      name: c.name,
      slug: c.slug,
      parentId: c.parentId ?? '',
      featured: c.featured,
      tagline: c.tagline ?? '',
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
        name: draft.name,
        slug: draft.slug,
        parentId: draft.parentId || null,
        featured: draft.featured,
        tagline: draft.tagline || null,
      };
      if (editing) {
        await apiFetch(`/admin/categories/${editing.id}`, { method: 'PUT', body });
        setNotice(`Saved "${draft.name}".`);
      } else {
        await apiFetch('/admin/categories', { method: 'POST', body });
        setNotice(`Created "${draft.name}".`);
      }
      setShowForm(false);
      await load();
    } catch (e) {
      setError(m(e, 'The category could not be saved.'));
    } finally {
      setBusy(false);
    }
  }

  async function remove(c: CategoryAdminView) {
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/admin/categories/${c.id}`, { method: 'DELETE' });
      setNotice(`"${c.name}" deleted.`);
      await load();
    } catch (e) {
      // The server refuses to delete a category that still has children or
      // products, and says which — surfacing that message verbatim is more
      // useful than a generic failure.
      setError(m(e, 'That category could not be deleted.'));
    } finally {
      setBusy(false);
    }
  }

  const nameById = new Map(rows.map((c) => [c.id, c.name]));

  return (
    <section aria-labelledby="categories-heading">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h2 id="categories-heading" className="text-h3 font-extrabold text-ink">
          Categories
        </h2>
        <Button variant="primary" onClick={openCreate}>
          New category
        </Button>
      </div>

      <AdminError message={error} />
      <AdminNotice message={notice} />

      {showForm && (
        <form onSubmit={save} className="mb-6 rounded-card border border-hairline bg-surface p-6">
          <h3 className="mb-4 text-h4 font-bold text-ink">
            {editing ? `Edit ${editing.name}` : 'New category'}
          </h3>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Name">
              <input
                required
                maxLength={120}
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Slug" hint="Lowercase letters, digits and hyphens.">
              <input
                required
                pattern="[a-z0-9]+(-[a-z0-9]+)*"
                maxLength={120}
                value={draft.slug}
                onChange={(e) => setDraft({ ...draft, slug: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label="Parent category" hint="Leave empty for a top-level category.">
              <select
                value={draft.parentId}
                onChange={(e) => setDraft({ ...draft, parentId: e.target.value })}
                className={inputClass}
              >
                <option value="">— top level —</option>
                {rows
                  // A category cannot be its own parent; the server also
                  // rejects deeper cycles.
                  .filter((c) => c.id !== editing?.id)
                  .map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
              </select>
            </Field>
            <Field label="Tagline" hint="Shown under the collection title on the home page.">
              <input
                maxLength={200}
                value={draft.tagline}
                onChange={(e) => setDraft({ ...draft, tagline: e.target.value })}
                className={inputClass}
              />
            </Field>
          </div>

          <label className="mt-4 flex items-center gap-2 text-sm font-semibold text-ink">
            <input
              type="checkbox"
              checked={draft.featured}
              onChange={(e) => setDraft({ ...draft, featured: e.target.checked })}
            />
            Show as a collection on the home page
          </label>
          <p className="mt-1 text-xs text-muted">
            Only top-level categories that actually contain products appear there.
          </p>

          <div className="mt-6 flex gap-3">
            <Button type="submit" variant="primary" disabled={busy}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Create category'}
            </Button>
            <Button onClick={() => setShowForm(false)}>Cancel</Button>
          </div>
        </form>
      )}

      <TableWrap label="Categories">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr>
              <Th>Name</Th>
              <Th>Parent</Th>
              <Th>Home page</Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id}>
                <Td>
                  <span className="font-semibold text-ink">{c.name}</span>
                  <span className="block text-xs text-muted">/{c.slug}</span>
                </Td>
                <Td>{c.parentId ? (nameById.get(c.parentId) ?? '—') : '—'}</Td>
                <Td>
                  {c.featured ? <Pill tone="warn">Collection</Pill> : <Pill>Not shown</Pill>}
                  {c.tagline && <span className="mt-1 block text-xs text-muted">{c.tagline}</span>}
                </Td>
                <Td className="text-right">
                  <span className="flex justify-end gap-2">
                    <Button onClick={() => openEdit(c)} disabled={busy}>
                      Edit
                    </Button>
                    <Button variant="danger" onClick={() => remove(c)} disabled={busy}>
                      Delete
                    </Button>
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
