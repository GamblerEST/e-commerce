interface StarRatingProps {
  /** 0–5. Comes off the API as a string (BigDecimal) — see types.ts. */
  rating: number;
  /** Shown next to the stars as "(12)". Omit to hide the count entirely. */
  reviewCount?: number;
  size?: 'sm' | 'md' | 'lg';
  /** Hide the trailing "4.5 (12)" text, e.g. in a dense card. */
  hideValue?: boolean;
}

const SIZES = {
  sm: 'h-3.5 w-3.5',
  md: 'h-4 w-4',
  lg: 'h-5 w-5',
} as const;

/**
 * A star rating that is readable by a screen reader and by someone who cannot
 * distinguish the fill colour.
 *
 * Three accessibility decisions worth stating, all WCAG 2.1 A:
 *
 *  - The stars themselves are aria-hidden and the real value is exposed once,
 *    as text, in a single <span>. Marking up five separate stars would make a
 *    screen reader announce "star star star star star" with no value at all.
 *  - Rating is never communicated by colour alone (1.4.1). The partial-fill
 *    width IS the information, and the numeric value is always in the
 *    accessible name even when `hideValue` hides it visually.
 *  - An unrated product renders "No reviews yet" rather than five empty stars,
 *    which otherwise reads identically to a genuine 0.0 rating.
 */
export default function StarRating({
  rating,
  reviewCount,
  size = 'md',
  hideValue = false,
}: StarRatingProps) {
  const safe = Number.isFinite(rating) ? Math.min(5, Math.max(0, rating)) : 0;
  const hasRating = reviewCount === undefined ? safe > 0 : reviewCount > 0;

  if (!hasRating) {
    return <span className="text-sm text-muted">No reviews yet</span>;
  }

  const label =
    reviewCount === undefined
      ? `Rated ${safe.toFixed(1)} out of 5`
      : `Rated ${safe.toFixed(1)} out of 5 from ${reviewCount} ${
          reviewCount === 1 ? 'review' : 'reviews'
        }`;

  return (
    <span className="inline-flex items-center gap-1.5 align-middle">
      {/* One accessible name for the whole widget, not five stars' worth. */}
      <span className="sr-only">{label}</span>

      <span
        aria-hidden="true"
        className="relative inline-block"
        // The width of the filled layer encodes the fraction, so 4.3 looks
        // different from 4.0 without rounding the display to whole stars.
        style={{ lineHeight: 0 }}
      >
        <span className="flex w-max">
          {[0, 1, 2, 3, 4].map((i) => (
            <Star key={i} className={`${SIZES[size]} shrink-0 text-hairline`} />
          ))}
        </span>
        <span
          className="absolute inset-0 overflow-hidden"
          style={{ width: `${(safe / 5) * 100}%` }}
        >
          {/* w-max is load-bearing, not decorative: the parent above is
              clipped to a PERCENTAGE width (10% for a 0.5 rating), and
              without an explicit width this row would inherit that same
              narrow box as its own — flexbox's default flex-shrink:1 would
              then squeeze all five full-size stars down to fit inside it,
              instead of rendering five FULL-SIZE stars for overflow:hidden
              to clip. w-max (shrink-0 on each star, belt-and-braces) keeps
              this row at its natural five-star width regardless of how
              narrow the clipping parent is. */}
          <span className="flex w-max">
            {[0, 1, 2, 3, 4].map((i) => (
              <Star key={i} className={`${SIZES[size]} shrink-0 text-[#f5b301]`} />
            ))}
          </span>
        </span>
      </span>

      {!hideValue && (
        <span aria-hidden="true" className="text-sm text-muted">
          {safe.toFixed(1)}
          {reviewCount !== undefined && ` (${reviewCount})`}
        </span>
      )}
    </span>
  );
}

function Star({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path d="M10 1.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8L1.5 7.7l5.9-.9L10 1.5z" />
    </svg>
  );
}
