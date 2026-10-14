import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ThumbsUp, Pencil, Trash2 } from 'lucide-react';
import { apiFetch, ApiRequestError } from '../../api/client';
import type { ReviewListResponse, ReviewResponse, ReviewSort } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { formatDate } from '../../lib/format';
import StarRating from '../StarRating';
import ReviewForm from './ReviewForm';

interface ReviewSectionProps {
  productId: string;
  productName: string;
  /** Lets the product page refresh its own header rating after a write. */
  onRatingChanged?: () => void;
}

const PAGE_SIZE = 10;

const SORTS: Array<{ value: ReviewSort; label: string }> = [
  // "Most helpful" leads because that is what the helpful votes are for,
  // and because it is the genuinely useful default — newest-first
  // surfaces whatever happened to arrive last, not what readers valued.
  { value: 'HELPFUL', label: 'Most helpful' },
  { value: 'NEWEST', label: 'Newest' },
  { value: 'RATING_HIGH', label: 'Highest rated' },
  { value: 'RATING_LOW', label: 'Lowest rated' },
];

export default function ReviewSection({
  productId,
  productName,
  onRatingChanged,
}: ReviewSectionProps) {
  const { user } = useAuth();
  const [data, setData] = useState<ReviewListResponse | null>(null);
  const [sort, setSort] = useState<ReviewSort>('HELPFUL');
  const [page, setPage] = useState(0);
  const [canReview, setCanReview] = useState(false);
  const [editing, setEditing] = useState<ReviewResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    const res = await apiFetch<ReviewListResponse>(
      `/products/${productId}/reviews?sort=${sort}&page=${page}&size=${PAGE_SIZE}`,
    );
    setData(res);
  }, [productId, sort, page]);

  useEffect(() => {
    load().catch(() => setError('Reviews could not be loaded.'));
  }, [load]);

  // Eligibility is a UI hint only — the POST enforces it regardless. Asked
  // separately so the form can explain WHY it is unavailable instead of
  // letting someone write 400 words and then be refused.
  useEffect(() => {
    if (!user) {
      setCanReview(false);
      return;
    }
    apiFetch<{ canReview: boolean }>(`/products/${productId}/reviews/eligibility`)
      .then((r) => setCanReview(r.canReview))
      .catch(() => setCanReview(false));
  }, [user, productId]);

  const mine = data?.items.find((r) => r.mine) ?? null;

  async function vote(review: ReviewResponse) {
    if (!user) return;
    setBusyId(review.id);
    setError(null);
    try {
      await apiFetch(`/reviews/${review.id}/helpful`, {
        method: review.votedByMe ? 'DELETE' : 'POST',
      });
      await load();
    } catch (e) {
      setError(
        e instanceof ApiRequestError ? e.message : 'Your vote could not be saved.',
      );
    } finally {
      setBusyId(null);
    }
  }

  async function remove(review: ReviewResponse) {
    setBusyId(review.id);
    setError(null);
    try {
      await apiFetch(`/reviews/${review.id}`, { method: 'DELETE' });
      await load();
      onRatingChanged?.();
    } catch (e) {
      setError(
        e instanceof ApiRequestError ? e.message : 'The review could not be deleted.',
      );
    } finally {
      setBusyId(null);
    }
  }

  const total = data?.totalCount ?? 0;
  const pageCount = Math.ceil(total / PAGE_SIZE);

  return (
    <section aria-labelledby="reviews-heading" className="mt-12 border-t border-hairline pt-10">
      <div className="mb-6 flex flex-wrap items-baseline justify-between gap-3">
        <h2 id="reviews-heading" className="text-h3 font-extrabold text-ink">
          Reviews{total > 0 && <span className="text-muted"> ({total})</span>}
        </h2>

        {total > 1 && (
          <label className="flex items-center gap-2 text-sm">
            <span className="font-semibold text-ink">Sort by</span>
            <select
              value={sort}
              onChange={(e) => {
                setSort(e.target.value as ReviewSort);
                setPage(0);
              }}
              className="rounded-field border border-hairline bg-surface px-3 py-2"
            >
              {SORTS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>

      {error && (
        <p role="alert" className="mb-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
          {error}
        </p>
      )}

      {/* ── Write / edit ─────────────────────────────────────────────────── */}
      {editing ? (
        <ReviewForm
          productId={productId}
          productName={productName}
          existing={editing}
          onDone={async () => {
            setEditing(null);
            await load();
            onRatingChanged?.();
          }}
          onCancel={() => setEditing(null)}
        />
      ) : (
        !mine &&
        (canReview ? (
          <ReviewForm
            productId={productId}
            productName={productName}
            onDone={async () => {
              await load();
              onRatingChanged?.();
            }}
          />
        ) : (
          <p className="mb-8 rounded-card border border-hairline bg-surface p-5 text-sm text-muted">
            {user ? (
              // The rule is "verified purchasers only". Saying so plainly is
              // better than hiding the form with no explanation — and it
              // doubles as a reason to buy.
              <>Only customers who have bought this product can review it.</>
            ) : (
              <>
                <Link to="/login" className="font-semibold underline">
                  Sign in
                </Link>{' '}
                to review this product. Reviews are open to customers who have bought it.
              </>
            )}
          </p>
        ))
      )}

      {/* ── The list ─────────────────────────────────────────────────────── */}
      {data && data.items.length === 0 && (
        <p className="text-muted">No reviews yet. Be the first to review this product.</p>
      )}

      {data && data.items.length > 0 && (
        <ul className="list-none space-y-6 p-0">
          {data.items.map((r) => (
            <li key={r.id} className="rounded-card border border-hairline bg-surface p-5">
              <div className="mb-2 flex flex-wrap items-center gap-x-3 gap-y-1">
                <StarRating rating={r.rating} size="sm" hideValue />
                <span className="font-semibold text-ink">
                  {/* An anonymized author keeps the review but loses the byline
                      (DECISIONS.md §5) — never render an empty name. */}
                  {r.authorName ?? 'Anonymous'}
                </span>
                <time dateTime={r.createdAt} className="text-sm text-muted">
                  {formatDate(r.createdAt)}
                </time>
                {r.mine && (
                  <span className="rounded-full bg-canvas px-2 py-0.5 text-xs font-bold text-muted">
                    Your review
                  </span>
                )}
              </div>

              {r.title && <h3 className="mb-1 text-h6 font-bold text-ink">{r.title}</h3>}
              {/* whitespace-pre-line keeps the reviewer's paragraph breaks
                  without ever interpreting their text as markup. */}
              <p className="whitespace-pre-line text-ink">{r.body}</p>

              <div className="mt-4 flex flex-wrap items-center gap-3">
                <button
                  type="button"
                  onClick={() => vote(r)}
                  disabled={!user || busyId === r.id || r.mine}
                  // Voting on your own review would let anyone push their
                  // review to the top of a helpfulness-sorted list.
                  title={r.mine ? 'You cannot vote on your own review' : undefined}
                  aria-pressed={r.votedByMe}
                  className={[
                    'inline-flex items-center gap-2 rounded-field border px-3 py-1.5 text-sm font-semibold transition',
                    r.votedByMe
                      ? 'border-ink bg-ink text-surface'
                      : 'border-hairline bg-surface text-ink hover:bg-canvas',
                    !user || r.mine ? 'cursor-not-allowed opacity-50' : '',
                  ].join(' ')}
                >
                  <ThumbsUp className="h-4 w-4" aria-hidden="true" />
                  Helpful
                  <span className="tabular-nums">({r.helpfulCount})</span>
                </button>

                {r.mine && (
                  <>
                    <button
                      type="button"
                      onClick={() => setEditing(r)}
                      className="inline-flex items-center gap-1.5 text-sm font-semibold underline"
                    >
                      <Pencil className="h-4 w-4" aria-hidden="true" />
                      Edit
                    </button>
                    <button
                      type="button"
                      onClick={() => remove(r)}
                      disabled={busyId === r.id}
                      className="inline-flex items-center gap-1.5 text-sm font-semibold text-danger underline"
                    >
                      <Trash2 className="h-4 w-4" aria-hidden="true" />
                      Delete
                    </button>
                  </>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {pageCount > 1 && (
        <nav aria-label="Review pages" className="mt-8 flex flex-wrap justify-center gap-2">
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
