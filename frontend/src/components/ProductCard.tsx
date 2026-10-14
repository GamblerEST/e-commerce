import { Link } from 'react-router-dom';
import type { ProductSummary } from '../api/types';
import { resolveImageUrl } from '../api/client';
import StarRating from './StarRating';
import { formatMoney } from '../lib/format';

interface ProductCardProps {
  product: ProductSummary;
  /** Grid is the default shop layout; list is the PLP's list-view toggle. */
  layout?: 'grid' | 'list';
  /**
   * Which heading level the product name should be. A card sitting under an
   * <h2> section heading must render an <h3>, or the page grows a heading-order
   * gap — WCAG 1.3.1 and the heading hierarchy search engines read (H2–H6
   * structured hierarchically).
   */
  headingLevel?: 2 | 3 | 4;
}

export default function ProductCard({
  product,
  layout = 'grid',
  headingLevel = 3,
}: ProductCardProps) {
  const Heading = `h${headingLevel}` as 'h2' | 'h3' | 'h4';
  const outOfStock = product.stockQuantity <= 0;
  const isList = layout === 'list';

  return (
    <article
      className={[
        'group relative rounded-card border border-hairline bg-surface transition',
        // Cards lift on hover, matching the primary button's own hover
        // behaviour. Disabled automatically for prefers-reduced-motion by the
        // global rule in index.css.
        'hover:-translate-y-1 hover:shadow-lg',
        isList ? 'flex gap-4 p-4' : 'flex flex-col p-4',
      ].join(' ')}
    >
      <div
        className={[
          'overflow-hidden rounded-field bg-canvas',
          isList ? 'w-32 shrink-0 sm:w-40' : 'mb-3 w-full',
        ].join(' ')}
      >
        {product.thumbnailUrl ? (
          <img
            src={resolveImageUrl(product.thumbnailUrl)}
            /* Descriptive, not "product image" — WCAG 1.1.1
               ask for alt text that conveys what the image shows. Brand is
               included when known because "Acme Kettle" identifies the item far
               better than "Kettle" in a grid of similar products. */
            alt={product.brand ? `${product.brand} ${product.name}` : product.name}
            loading="lazy"
            className="aspect-square w-full object-cover"
          />
        ) : (
          <div
            className="flex aspect-square w-full items-center justify-center text-sm text-muted"
            /* Decorative placeholder: it carries no information a sighted user
               gets either, so it is hidden rather than given fake alt text. */
            aria-hidden="true"
          >
            No image
          </div>
        )}
      </div>

      <div className={isList ? 'flex min-w-0 flex-1 flex-col' : 'flex flex-1 flex-col'}>
        {product.brand && (
          <p className="mb-1 text-xs font-semibold tracking-wide text-muted uppercase">
            {product.brand}
          </p>
        )}

        <Heading className="mb-1 text-base leading-snug font-bold text-ink">
          {/* The whole card is clickable via this stretched link rather than
              wrapping everything in an <a>. A single link keeps one tab stop
              and one announced name per card, instead of a screen reader
              hitting image, title and price as three separate links. */}
          <Link to={`/products/${product.slug}`} className="after:absolute after:inset-0">
            {product.name}
          </Link>
        </Heading>

        <div className="mb-2">
          <StarRating rating={Number(product.avgRating)} reviewCount={product.reviewCount} size="sm" />
        </div>

        {isList && (
          <p className="mb-2 line-clamp-2 text-sm text-muted">
            {/* Availability only. The brand already has its own line above, and
                repeating it here read as a duplicate rather than context. */}
            {product.stockQuantity > 0 ? 'In stock' : 'Currently unavailable'}
          </p>
        )}

        <p className="mt-auto text-lg font-extrabold text-ink">
          {formatMoney(product.price, product.currency)}
        </p>

        {outOfStock && (
          <p className="mt-1 text-sm font-semibold text-danger">Out of stock</p>
        )}
      </div>
    </article>
  );
}
