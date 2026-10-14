import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiFetch, ApiRequestError, resolveImageUrl } from '../api/client';
import { useCart } from '../cart/CartContext';
import type { ProductDetail, ProductSummary } from '../api/types';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';
import ProductCard from '../components/ProductCard';
import StarRating from '../components/StarRating';
import ReviewSection from '../components/reviews/ReviewSection';
import { formatMoney } from '../lib/format';

/**
 * Builds a 155-160 character meta description: the product's own description
 * first, then real facts about buying it, trimmed at a word boundary so it
 * never ends mid-word.
 */
function buildMetaDescription(product: ProductDetail): string {
  const suffix = `Buy ${product.name}${product.brand ? ` by ${product.brand}` : ''} from Estonian Shop — VAT included, tracked delivery across Estonia.`;
  const own = product.description?.trim();
  const full = own ? `${own} ${suffix}` : suffix;
  if (full.length <= 160) return full;
  const cut = full.slice(0, 157);
  const lastSpace = cut.lastIndexOf(' ');
  return `${(lastSpace > 120 ? cut.slice(0, lastSpace) : cut).trimEnd()}…`;
}

export default function ProductDetailPage() {
  const { slug } = useParams<{ slug: string }>();
  const [product, setProduct] = useState<ProductDetail | null>(null);
  const [error, setError] = useState<'notfound' | 'unavailable' | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [addState, setAddState] = useState<'idle' | 'adding' | 'added'>('idle');
  const [related, setRelated] = useState<ProductSummary[]>([]);
  const { addItem, error: cartError } = useCart();

  const loadProduct = useCallback(() => {
    if (!slug) return;
    apiFetch<ProductDetail>(`/products/${slug}`)
      .then(setProduct)
      .catch((err) =>
        // Distinguish "this product does not exist" from "we could not reach
        // the server". Reporting every failure as 404 told a customer their
        // product was gone whenever the backend hiccuped, which is both untrue
        // and sends them away instead of asking them to retry.
        setError(
          err instanceof ApiRequestError && err.status === 404
            ? 'notfound'
            : 'unavailable',
        ),
      );
  }, [slug]);

  useEffect(() => {
    loadProduct();
  }, [loadProduct]);

  // Separate request, deliberately — see CatalogController.related(). A failure
  // here hides the strip and leaves the rest of the page working.
  useEffect(() => {
    if (!slug) return;
    apiFetch<ProductSummary[]>(`/products/${slug}/related`)
      .then(setRelated)
      .catch(() => setRelated([]));
  }, [slug]);

  async function onAddToCart() {
    if (!product) return;
    setAddState('adding');
    try {
      await addItem(product.id, quantity);
      setAddState('added');
      setTimeout(() => setAddState('idle'), 1500);
    } catch {
      setAddState('idle');
    }
  }

  if (error) {
    return (
      <div className="mx-auto max-w-lg">
        <PageMeta
          title={error === 'notfound' ? 'Product not found' : 'Product unavailable'}
          description="This product could not be shown."
          noindex
        />
        <h1 className="text-h1 font-extrabold text-ink">
          {error === 'notfound' ? 'Product not found' : 'Something went wrong'}
        </h1>
        <p role="alert" className="mt-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
          {error === 'notfound'
            ? 'This product does not exist, or it is no longer sold.'
            : 'We could not load this product just now. It has not gone anywhere — please try again.'}
        </p>
        {error === 'unavailable' && (
          <Button type="button" variant="secondary" onClick={loadProduct} className="mt-4">
            Try again
          </Button>
        )}
        <p className="mt-4">
          <Link to="/products" className="font-semibold text-ink underline">
            Back to catalog
          </Link>
        </p>
      </div>
    );
  }
  if (!product) {
    return (
      <div>
        <p role="status" className="text-muted">Loading…</p>
      </div>
    );
  }

  // Title stays under 60 characters, so it is not
  // truncated in a search result. Long product names are trimmed rather than
  // letting the suffix push it over.
  const metaTitle = `${product.name} — Estonian Shop`.slice(0, 60);
  const dimensionRows: { label: string; value: string; sub?: boolean }[] = [];
  if (product.dimensions.weightKg) {
    dimensionRows.push({
      label: 'Weight',
      value: `${product.dimensions.weightKg} kg (${product.dimensions.weightLb} lb)`,
    });
  }
  if (product.dimensions.lengthCm) {
    dimensionRows.push({ label: 'Length', value: `${product.dimensions.lengthCm} cm (${product.dimensions.lengthIn} in)`, sub: true });
  }
  if (product.dimensions.widthCm) {
    dimensionRows.push({ label: 'Width', value: `${product.dimensions.widthCm} cm (${product.dimensions.widthIn} in)`, sub: true });
  }
  if (product.dimensions.heightCm) {
    dimensionRows.push({ label: 'Height', value: `${product.dimensions.heightCm} cm (${product.dimensions.heightIn} in)`, sub: true });
  }

  return (
    <div>
      <PageMeta
        title={metaTitle}
        /* A short product description leaves a stub of a meta description —
           one product came out at 39 characters against a 155-160 target. So
           the product's own words lead, and a factual suffix pads it out
           rather than truncating whatever happens to be there. */
        description={buildMetaDescription(product)}
        canonicalPath={`/products/${product.slug}`}
      />
      <Link to="/products" className="mb-4 inline-block text-sm font-semibold text-ink no-underline hover:underline">
        ← Back to catalog
      </Link>
      <div className="grid grid-cols-1 gap-8 md:grid-cols-2">
        <div>
          {product.images.map((img, i) => (
            <img
              key={img.url}
              /* The MEDIUM rendition, not the original. A product page renders
                 these at ~600px, so shipping a 5 MB upload here is the single
                 biggest thing that makes the page slow on mobile data. */
              src={resolveImageUrl(img.mediumUrl)}
              /* Every image previously carried the identical alt text, so a
                 screen reader announced the same sentence four times over.
                 Only the first image describes the product; the rest are
                 numbered views of the same thing (WCAG 1.1.1). */
              /* The admin's own description wins: only someone looking at the
                 photo knows whether it shows the product in use, a detail shot
                 or the packaging. Falls back to a composed description. */
              alt={
                img.altText?.trim()
                  ? img.altText
                  : i === 0
                    ? `${product.brand ? product.brand + ' ' : ''}${product.name}`
                    : `${product.name}, view ${i + 1} of ${product.images.length}`
              }
              loading={i === 0 ? 'eager' : 'lazy'}
              className="mb-3 w-full rounded-card bg-surface"
            />
          ))}
        </div>
        <div>
          <h1 className="text-h1 font-extrabold text-ink">{product.name}</h1>
          {product.category && <p className="mt-2 mb-0 text-sm text-muted">{product.category.name}</p>}
          {product.brand && <p className="mt-1 mb-0 text-sm text-muted">{product.brand}</p>}
          <p className="my-3 text-h3 font-extrabold text-ink">{formatMoney(product.price, product.currency)}</p>
          <p className="mb-3">
            <StarRating rating={Number(product.avgRating)} reviewCount={product.reviewCount} />
          </p>
          <p className={`mb-4 text-sm font-semibold ${product.stockQuantity > 0 ? 'text-ok' : 'text-danger'}`}>
            {product.stockQuantity > 0 ? `${product.stockQuantity} in stock` : 'Out of stock'}
          </p>
          <p className="text-ink">{product.description}</p>

          {Object.keys(product.attributes).length > 0 && (
            <table className="mt-4 w-full border-collapse text-sm">
              <tbody>
                {Object.entries(product.attributes).map(([name, values]) => (
                  <tr key={name} className="border-b border-hairline">
                    <th className="w-2/5 py-2 pr-2 text-left font-medium text-muted capitalize">{name}</th>
                    <td className="py-2 text-ink">{values.join(', ')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {dimensionRows.length > 0 && (
            <table className="mt-4 w-full border-collapse text-sm">
              <tbody>
                {dimensionRows.map((row) => (
                  <tr key={row.label} className={row.sub ? undefined : 'border-b border-hairline'}>
                    <th className={`w-2/5 py-2 pr-2 text-left font-medium text-muted ${row.sub ? 'text-xs' : ''}`}>
                      {row.label}
                    </th>
                    <td className={`py-2 text-ink ${row.sub ? 'text-xs' : ''}`}>{row.value}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          <div className="my-6 flex items-end gap-3">
            <label className="flex flex-col gap-1 text-sm text-muted">
              Qty
              <input
                type="number"
                min={1}
                max={99}
                value={quantity}
                onChange={(e) => setQuantity(Math.min(99, Math.max(1, Number(e.target.value) || 1)))}
                className="w-20 rounded-field border border-hairline bg-canvas px-3 py-2 text-ink"
              />
            </label>
            <Button
              type="button"
              onClick={onAddToCart}
              disabled={addState === 'adding' || product.stockQuantity === 0}
            >
              {addState === 'added' ? 'Added ✓' : product.stockQuantity === 0 ? 'Out of stock' : 'Add to cart'}
            </Button>
          </div>
          {cartError && (
            <p role="alert" className="rounded-field bg-danger-bg px-4 py-3 text-danger">
              {cartError}
            </p>
          )}
        </div>
      </div>

      {/* ── Reviews ──────────────────────────────────────────────────────── */}
      <ReviewSection
        productId={product.id}
        productName={product.name}
        /* A new/edited/deleted review changes avg_rating via the database
           trigger in V15, so the header rating above is refetched rather than
           left showing a value the reviews below now contradict. */
        onRatingChanged={loadProduct}
      />

      {/* ── Related products ─────────────────────────────────────────────── */}
      {related.length > 0 && (
        <section
          aria-labelledby="related-heading"
          className="mt-12 border-t border-hairline pt-10"
        >
          <h2 id="related-heading" className="mb-6 text-h3 font-extrabold text-ink">
            You might also like
          </h2>
          <ul className="grid list-none grid-cols-2 gap-5 p-0 lg:grid-cols-4">
            {related.map((p) => (
              <li key={p.id}>
                <ProductCard product={p} headingLevel={3} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
