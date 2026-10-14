import { useCallback, useEffect, useState } from 'react';
import { apiFetch, ApiRequestError, resolveImageUrl, uploadFile } from '../../api/client';
import type { AdminProductImage } from '../../api/types';
import { AdminError, Button, Field, inputClass } from './ui';

const ACCEPTED = 'image/jpeg,image/png,image/webp';

/**
 * The images of ONE existing product: see them, add one, remove one.
 *
 * Only offered when editing, because an upload needs a product id to attach to.
 * The server verifies that a file really decodes as an image (the file type the
 * browser claims proves nothing), so the checks here only save a round trip.
 *
 * Images are saved the moment they are uploaded or removed, independently of the
 * product form's own Save — so an edit to the product's text can never undo them,
 * and cancelling the form does not un-upload a picture.
 */
export default function ProductImageManager({ productId }: { productId: string }) {
  const [images, setImages] = useState<AdminProductImage[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [altText, setAltText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setImages(await apiFetch<AdminProductImage[]>(`/admin/products/${productId}/images`));
  }, [productId]);

  useEffect(() => {
    load().catch((e) => setError(message(e, 'The images could not be loaded.')));
  }, [load]);

  async function upload() {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      if (altText.trim()) form.append('altText', altText.trim());
      await uploadFile(`/admin/products/${productId}/images`, form);
      setFile(null);
      setAltText('');
      await load();
    } catch (e) {
      setError(message(e, 'The image could not be uploaded.'));
    } finally {
      setBusy(false);
    }
  }

  async function remove(image: AdminProductImage) {
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/admin/products/${productId}/images/${image.id}`, { method: 'DELETE' });
      await load();
    } catch (e) {
      setError(message(e, 'The image could not be removed.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <fieldset className="mt-4 rounded-field border border-hairline p-4">
      <legend className="px-1 text-sm font-semibold text-ink">Images</legend>
      <AdminError message={error} />

      {images.length === 0 ? (
        <p className="mb-3 text-sm text-muted">No images yet. The first one added is shown first.</p>
      ) : (
        <ul className="mb-4 grid list-none grid-cols-2 gap-3 p-0 sm:grid-cols-4">
          {images.map((img) => (
            <li key={img.id} className="rounded-field border border-hairline p-2">
              <img
                src={resolveImageUrl(img.thumbnailUrl)}
                alt={img.altText ?? ''}
                className="mb-2 aspect-square w-full rounded-field bg-canvas object-cover"
              />
              <p className="mb-2 truncate text-xs text-muted" title={img.altText ?? undefined}>
                {img.altText || 'No description'}
              </p>
              <Button variant="danger" onClick={() => remove(img)} disabled={busy}>
                Remove
              </Button>
            </li>
          ))}
        </ul>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Add an image" hint="JPEG, PNG or WebP, up to 5 MB.">
          <input
            type="file"
            accept={ACCEPTED}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            className={inputClass}
          />
        </Field>
        <Field label="Description" hint="Read aloud by screen readers. Say what the picture shows.">
          <input
            maxLength={300}
            value={altText}
            onChange={(e) => setAltText(e.target.value)}
            className={inputClass}
          />
        </Field>
      </div>
      <div className="mt-3">
        <Button onClick={upload} disabled={busy || !file}>
          {busy ? 'Working…' : 'Upload image'}
        </Button>
      </div>
    </fieldset>
  );
}

function message(e: unknown, fallback: string): string {
  return e instanceof ApiRequestError || e instanceof Error ? e.message : fallback;
}
