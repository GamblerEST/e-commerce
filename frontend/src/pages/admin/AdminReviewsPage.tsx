import { useCallback, useEffect, useState } from 'react';
import { apiFetch, ApiRequestError } from '../../api/client';
import type { ReviewListResponse } from '../../api/types';
import { formatDate } from '../../lib/format';
import StarRating from '../../components/StarRating';
import { AdminError, AdminNotice, Button, Field, Pill, TableWrap, Td, Th, inputClass } from './ui';

/**
 * Review moderation.
 *
 * Moderation here is after-the-fact TAKEDOWN, not approve-before-publish (see
 * V15__reviews.sql). A pending-first queue would mean an honest customer
 * review stays invisible until a human gets to it, which is worse for the
 * shopper and for the shop. Hiding a review also removes it from the
 * product's average, via the same database trigger that maintains it.
 */
export default function AdminReviewsPage() {
  const [data, setData] = useState<ReviewListResponse | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    const params = new URLSearchParams({ page: String(page), size: '25' });
    if (status) params.set('status', status);
    setData(await apiFetch<ReviewListResponse>(`/admin/reviews?${params}`));
  }, [status, page]);

  useEffect(() => {
    load().catch((e) => setError(m(e, 'Reviews could not be loaded.')));
  }, [load]);

  async function moderate(id: string, next: 'PUBLISHED' | 'HIDDEN') {
    setBusy(id);
    setError(null);
    try {
      await apiFetch(`/admin/reviews/${id}?status=${next}`, { method: 'PATCH' });
      setNotice(
        next === 'HIDDEN'
          ? 'Review hidden. It no longer counts toward the product rating.'
          : 'Review restored and counting toward the product rating again.',
      );
      await load();
    } catch (e) {
      setError(m(e, 'The review could not be updated.'));
    } finally {
      setBusy(null);
    }
  }

  const total = data?.totalCount ?? 0;
  const pageCount = Math.ceil(total / 25);

  return (
    <section aria-labelledby="reviews-heading">
      <h2 id="reviews-heading" className="mb-1 text-h3 font-extrabold text-ink">
        Review moderation
      </h2>
      <p className="mb-4 text-sm text-muted">
        Reviews publish immediately. Hiding one removes it from the product average.
      </p>

      <AdminError message={error} />
      <AdminNotice message={notice} />

      <div className="mb-4 max-w-xs">
        <Field label="Filter">
          <select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
            className={inputClass}
          >
            <option value="">All reviews</option>
            <option value="PUBLISHED">Published</option>
            <option value="HIDDEN">Hidden</option>
          </select>
        </Field>
      </div>

      <TableWrap label="Reviews">
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr>
              <Th>Rating</Th>
              <Th>Review</Th>
              <Th>Author</Th>
              <Th>Posted</Th>
              <Th>Status</Th>
              <Th className="text-right">Actions</Th>
            </tr>
          </thead>
          <tbody>
            {data?.items.length === 0 && (
              <tr>
                <Td className="text-muted">No reviews match.</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
                <Td>{''}</Td>
              </tr>
            )}
            {data?.items.map((r) => (
              <tr key={r.id} className={r.status === 'HIDDEN' ? 'opacity-60' : undefined}>
                <Td>
                  <StarRating rating={r.rating} size="sm" hideValue />
                </Td>
                <Td className="max-w-md">
                  {r.title && <span className="block font-semibold text-ink">{r.title}</span>}
                  <span className="block text-muted">{r.body}</span>
                  <span className="mt-1 block text-xs text-muted">
                    {r.helpfulCount} found this helpful
                  </span>
                </Td>
                <Td className="whitespace-nowrap">{r.authorName ?? 'Anonymous'}</Td>
                <Td className="whitespace-nowrap">{formatDate(r.createdAt)}</Td>
                <Td>
                  <Pill tone={r.status === 'HIDDEN' ? 'bad' : 'good'}>{r.status}</Pill>
                </Td>
                <Td className="text-right">
                  {r.status === 'PUBLISHED' ? (
                    <Button
                      variant="danger"
                      disabled={busy === r.id}
                      onClick={() => moderate(r.id, 'HIDDEN')}
                    >
                      Hide
                    </Button>
                  ) : (
                    <Button disabled={busy === r.id} onClick={() => moderate(r.id, 'PUBLISHED')}>
                      Restore
                    </Button>
                  )}
                </Td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableWrap>

      {pageCount > 1 && (
        <nav aria-label="Review pages" className="mt-6 flex flex-wrap justify-center gap-2">
          {Array.from({ length: pageCount }, (_, i) => (
            <button
              key={i}
              type="button"
              onClick={() => setPage(i)}
              aria-current={i === page ? 'page' : undefined}
              className={[
                'min-w-10 rounded-field border px-3 py-2 text-sm font-semibold',
                i === page
                  ? 'border-ink bg-accent text-on-accent'
                  : 'border-hairline bg-surface text-ink hover:bg-canvas',
              ].join(' ')}
            >
              {i + 1}
            </button>
          ))}
        </nav>
      )}
    </section>
  );
}

function m(e: unknown, fallback: string): string {
  return e instanceof ApiRequestError || e instanceof Error ? e.message : fallback;
}
