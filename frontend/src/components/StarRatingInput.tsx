interface StarRatingInputProps {
  value: number;
  onChange: (rating: number) => void;
  /** Shared radio-group name; must be unique if two inputs ever coexist. */
  name?: string;
  error?: boolean;
}

const LABELS = ['Poor', 'Fair', 'Good', 'Very good', 'Excellent'];

/**
 * The "pick a star rating" control.
 *
 * Built as a real <fieldset> of five radio inputs rather than five buttons or
 * a div with click handlers. That is not pedantry — it is what makes the
 * control work without a mouse and without sight:
 *
 *  - Arrow keys move between options and Space selects, for free, because
 *    that is native radio-group behaviour. A row of <button>s would need all
 *    of that hand-written (and WCAG 2.1.1 requires it).
 *  - Each option carries a real text label ("3 stars — Good"), so a screen
 *    reader announces the choice instead of "button, button, button".
 *  - The form submits it like any other field, so validation and focus
 *    management work the same way as the text inputs beside it.
 *
 * The radios are visually hidden with sr-only rather than display:none —
 * display:none would remove them from the accessibility tree and the tab
 * order entirely, which defeats the whole point.
 */
export default function StarRatingInput({
  value,
  onChange,
  name = 'rating',
  error = false,
}: StarRatingInputProps) {
  return (
    <fieldset className="border-0 p-0">
      <legend className="mb-2 text-sm font-semibold text-ink">Your rating</legend>

      <div
        className={[
          'inline-flex items-center gap-1 rounded-field p-1',
          error ? 'ring-2 ring-danger' : '',
        ].join(' ')}
      >
        {[1, 2, 3, 4, 5].map((star) => {
          const id = `${name}-${star}`;
          const filled = star <= value;
          return (
            <label
              key={star}
              htmlFor={id}
              className="cursor-pointer p-0.5"
              // The whole star is the click target, and focus-within draws the
              // ring because the actual input is visually hidden.
              title={`${star} ${star === 1 ? 'star' : 'stars'} — ${LABELS[star - 1]}`}
            >
              <input
                type="radio"
                id={id}
                name={name}
                value={star}
                checked={value === star}
                onChange={() => onChange(star)}
                className="sr-only peer"
              />
              <span className="sr-only">
                {star} {star === 1 ? 'star' : 'stars'} — {LABELS[star - 1]}
              </span>
              <svg
                aria-hidden="true"
                viewBox="0 0 20 20"
                className={[
                  'h-8 w-8 transition',
                  filled ? 'text-[#f5b301]' : 'text-hairline',
                  // Focus ring lives on the sibling svg because the input itself
                  // is invisible; without this a keyboard user sees nothing move.
                  'peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-ink',
                ].join(' ')}
                fill="currentColor"
              >
                <path d="M10 1.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8L1.5 7.7l5.9-.9L10 1.5z" />
              </svg>
            </label>
          );
        })}

        {/* The chosen value as text — rating must not be conveyed by the star
            fill colour alone (WCAG 1.4.1). */}
        <span className="ml-2 text-sm font-semibold text-ink">
          {value > 0 ? LABELS[value - 1] : 'Not rated'}
        </span>
      </div>
    </fieldset>
  );
}
