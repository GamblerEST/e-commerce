import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, Star, Truck, ReceiptEuro } from 'lucide-react';
import { apiFetch, resolveImageUrl } from '../api/client';
import type { HomeResponse, ProductSummary } from '../api/types';
import ProductCard from '../components/ProductCard';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';

/**
 * The shop window.
 *
 * Visual language comes entirely from the design tokens in index.css — the
 * lime primary, charcoal ink and the asymmetric button corner. Nothing here
 * hardcodes a colour.
 *
 * Featured products and collections both belong on the shop window, and come
 * from one request, GET /api/home — see HomeResponse for why they are not two calls.
 */

const PROMISES = [
  {
    label: 'VAT included',
    detail: 'The price you see is the price you pay. No surprises at checkout.',
    Icon: ReceiptEuro,
  },
  {
    label: 'Secure payments',
    detail: 'Card details go straight to Stripe. They never touch our servers.',
    Icon: ShieldCheck,
  },
  {
    label: 'Estonia-wide delivery',
    detail: 'Shipped from Tallinn, with tracking on every order.',
    Icon: Truck,
  },
];

/**
 * A reviews-weighted average, not a flat average of averages — three reviews
 * at 5.0 should not count the same as three hundred. Only ever describes the
 * FEATURED set explicitly (never phrased as a store-wide figure): that is
 * genuinely all this request knows about, and this project has already found
 * one real bug (V19) caused by a rating claim that outran what the data
 * actually showed.
 */
function featuredRatingStats(featured: ProductSummary[]): { average: number; count: number } | null {
  const count = featured.reduce((sum, p) => sum + p.reviewCount, 0);
  if (count === 0) return null;
  const weighted = featured.reduce((sum, p) => sum + Number(p.avgRating) * p.reviewCount, 0);
  return { average: weighted / count, count };
}

export default function WelcomePage() {
  const [data, setData] = useState<HomeResponse | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    apiFetch<HomeResponse>('/home')
      .then((res) => active && setData(res))
      .catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, []);

  const ratingStats = data ? featuredRatingStats(data.featured) : null;
  const heroImages = (data?.featured ?? []).filter((p) => p.thumbnailUrl).slice(0, 3);

  return (
    <>
      <PageMeta
        title="Estonian Shop — home goods, books and electronics"
        description="Buy home goods, books, laptops, phones and clothing from Tallinn. VAT included in every price, tracked delivery across Estonia, and secure Stripe payments."
        canonicalPath="/"
      />

      {/* ── Hero ─────────────────────────────────────────────────────────── */}
      <section className="mb-16 overflow-hidden rounded-card bg-ink text-surface">
        <div className="grid grid-cols-1 items-center gap-10 px-6 py-16 sm:px-12 sm:py-20 lg:grid-cols-[1.1fr_0.9fr] lg:gap-6">
          <div>
            <p className="mb-4 text-sm font-bold tracking-[0.2em] text-accent uppercase">
              Shipped from Tallinn
            </p>
            {/* The single H1 for this page. */}
            <h1 className="max-w-xl text-4xl leading-tight font-extrabold text-balance sm:text-5xl">
              Everyday things, chosen well and priced honestly.
            </h1>
            <p className="mt-6 max-w-lg text-lg text-white/80">
              Home goods, books, laptops, phones and clothing — with VAT included in every price
              you see.
            </p>

            {/* Trust signals a shopper actually weighs before clicking through,
                stated together rather than scattered across the page. The
                rating clause only appears once there is a real one to show. */}
            <ul className="mt-6 flex list-none flex-wrap gap-x-6 gap-y-2 p-0 text-sm text-white/70">
              <li className="flex items-center gap-1.5">
                <ReceiptEuro className="h-4 w-4 text-accent" aria-hidden="true" />
                VAT included
              </li>
              {ratingStats && (
                <li className="flex items-center gap-1.5">
                  <Star className="h-4 w-4 fill-accent text-accent" aria-hidden="true" />
                  {ratingStats.average.toFixed(1)}★ on our featured picks ({ratingStats.count} reviews)
                </li>
              )}
              <li className="flex items-center gap-1.5">
                <Truck className="h-4 w-4 text-accent" aria-hidden="true" />
                Tracked Estonia-wide delivery
              </li>
            </ul>

            <Button asChild size="lg" className="mt-10 hover:-translate-y-1">
              <Link to="/products">Browse the shop</Link>
            </Button>
          </div>

          {/* A small collage of real stock, not decoration — every image is an
              actual featured product and links straight to it. Hidden below
              lg: three tilted cards have no graceful narrow layout, and the
              text column above already carries the page on a small screen. */}
          {heroImages.length > 0 && (
            <div className="relative hidden h-72 lg:block" aria-hidden="true">
              {heroImages.map((p, i) => (
                <Link
                  key={p.id}
                  to={`/products/${p.slug}`}
                  tabIndex={-1}
                  style={{
                    top: `${i * 15}%`,
                    left: `${i * 18}%`,
                    transform: `rotate(${(i - 1) * 4}deg)`,
                    zIndex: heroImages.length - i,
                  }}
                  className="absolute h-44 w-44 overflow-hidden rounded-card border-4 border-ink bg-surface shadow-xl transition hover:z-10! hover:-translate-y-1"
                >
                  <img
                    src={resolveImageUrl(p.thumbnailUrl!)}
                    alt=""
                    className="h-full w-full object-cover"
                  />
                </Link>
              ))}
            </div>
          )}
        </div>
      </section>

      {/* ── Collections ──────────────────────────────────────────────────── */}
      {data && data.collections.length > 0 && (
        <section aria-labelledby="collections-heading" className="mb-16">
          <div className="mb-6 flex flex-wrap items-baseline justify-between gap-2">
            <h2 id="collections-heading" className="text-h3 font-extrabold text-ink">
              Shop by collection
            </h2>
            <Link to="/products" className="text-sm font-semibold underline">
              See everything
            </Link>
          </div>

          <ul className="grid list-none grid-cols-1 gap-5 p-0 sm:grid-cols-2 lg:grid-cols-4">
            {data.collections.map((c) => (
              <li key={c.id}>
                <Link
                  to={`/products?categoryId=${c.id}`}
                  className="group relative block overflow-hidden rounded-card border border-hairline bg-surface transition hover:-translate-y-1 hover:shadow-lg"
                >
                  <div className="aspect-[16/9] w-full overflow-hidden bg-canvas">
                    {c.thumbnailUrl ? (
                      <img
                        src={resolveImageUrl(c.thumbnailUrl)}
                        /* The tile links to a category, so the alt describes
                           the destination rather than the stand-in product
                           that happens to supply the cover image. */
                        alt={`${c.name} collection`}
                        loading="lazy"
                        className="h-full w-full object-cover transition duration-300 group-hover:scale-105"
                      />
                    ) : (
                      <div aria-hidden="true" className="h-full w-full bg-canvas" />
                    )}
                  </div>
                  <div className="p-5">
                    <h3 className="text-h4 font-bold text-ink">{c.name}</h3>
                    {c.tagline && <p className="mt-1 text-sm text-muted">{c.tagline}</p>}
                    <p className="mt-2 text-sm font-semibold text-muted">
                      {c.productCount} {c.productCount === 1 ? 'product' : 'products'}
                    </p>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ── Featured products ────────────────────────────────────────────── */}
      {data && data.featured.length > 0 && (
        <section aria-labelledby="featured-heading" className="mb-16">
          <div className="mb-6 flex flex-wrap items-baseline justify-between gap-2">
            <div>
              <h2 id="featured-heading" className="text-h3 font-extrabold text-ink">
                Featured right now
              </h2>
              {ratingStats && (
                <p className="mt-1 text-sm text-muted">
                  Rated {ratingStats.average.toFixed(1)}★ across {ratingStats.count} reviews from
                  real customers.
                </p>
              )}
            </div>
            <Link to="/products" className="text-sm font-semibold underline">
              All products
            </Link>
          </div>

          <ul className="grid list-none grid-cols-2 gap-5 p-0 lg:grid-cols-4">
            {data.featured.map((p) => (
              <li key={p.id}>
                {/* h3, because the section heading above is an h2 — keeps the
                    document outline gapless (WCAG 1.3.1, and the heading
                    hierarchy search engines read). */}
                <ProductCard product={p} headingLevel={3} />
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* A failed home fetch must not produce a blank page — the catalogue is
          still reachable, so the shop stays usable. */}
      {failed && (
        <section className="mb-16 rounded-card border border-hairline bg-surface p-8 text-center">
          <h2 className="text-h4 font-bold text-ink">The shop window could not be loaded</h2>
          <p className="mt-2 text-muted">
            The catalogue itself is fine — browse it directly.
          </p>
          <Button asChild className="mt-6">
            <Link to="/products">Browse products</Link>
          </Button>
        </section>
      )}

      {/* ── Promises ─────────────────────────────────────────────────────── */}
      <section aria-labelledby="promises-heading" className="mb-16">
        <h2 id="promises-heading" className="sr-only">
          Why shop with us
        </h2>
        <ul className="grid list-none grid-cols-1 gap-6 p-0 sm:grid-cols-3">
          {PROMISES.map(({ label, detail, Icon }) => (
            <li key={label} className="rounded-card border border-hairline bg-surface p-6">
              <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-full bg-canvas">
                <Icon className="h-5 w-5 text-ink" aria-hidden="true" />
              </span>
              <h3 className="text-h6 font-bold text-ink">{label}</h3>
              <p className="mt-1 text-sm text-muted">{detail}</p>
            </li>
          ))}
        </ul>
      </section>

      {/* ── Closing CTA ──────────────────────────────────────────────────── */}
      <section className="mb-8 rounded-card bg-accent px-6 py-12 text-center sm:px-12">
        <h2 className="text-h2 font-extrabold text-on-accent">Ready to find something good?</h2>
        <p className="mx-auto mt-2 max-w-md text-on-accent/80">
          Every price already includes VAT, and every order ships tracked from Tallinn.
        </p>
        {/* The one spot this brand's primary button colour would have no
            contrast against its own section background — the section
            itself is bg-accent, so the CTA on it borrows the WelcomePage
            hero's dark fill instead, keeping the same corner and motion. */}
        <Button asChild size="lg" className="mt-8 bg-ink text-surface hover:-translate-y-1 hover:bg-ink">
          <Link to="/products">Start browsing</Link>
        </Button>
      </section>
    </>
  );
}
