import { ExternalLink } from 'lucide-react';
import { ABOUT, COMPANY } from '../companyInfo';
import PageMeta from '../components/PageMeta';

// Brand marks (Facebook/Instagram/LinkedIn) are deliberately not used here.
// lucide-react dropped them from the main package over trademark licensing,
// and shipping a lookalike would be worse than a plain, honest text link.

/**
 * About page: company information, mission, team, and links to social media.
 *
 * Company identification is read from companyInfo.ts, the same source the
 * footer and Sales Conditions use, so the legally-required seller details
 * cannot say three different things in three places.
 */
export default function AboutPage() {
  return (
    <div className="mx-auto max-w-3xl">
      <PageMeta
        title="About Estonian Shop"
        description="Who we are, what we sell and why. Estonian Shop is a small Tallinn retailer selling durable everyday goods at VAT-inclusive prices, shipping across Estonia."
        canonicalPath="/about"
      />

      <h1 className="text-h1 font-extrabold text-balance text-ink">About Estonian Shop</h1>

      <p className="mt-6 text-lg text-muted">{ABOUT.story}</p>

      <section aria-labelledby="mission-heading" className="mt-12">
        <h2 id="mission-heading" className="text-h3 font-extrabold text-ink">
          Our mission
        </h2>
        <p className="mt-3 text-lg text-ink">{ABOUT.mission}</p>
      </section>

      <section aria-labelledby="values-heading" className="mt-12">
        <h2 id="values-heading" className="text-h3 font-extrabold text-ink">
          What we stand for
        </h2>
        <ul className="mt-4 grid list-none gap-4 p-0 sm:grid-cols-3">
          {ABOUT.values.map((v) => (
            <li key={v.title} className="rounded-card border border-hairline bg-surface p-5">
              <h3 className="text-h6 font-bold text-ink">{v.title}</h3>
              <p className="mt-1 text-sm text-muted">{v.detail}</p>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="team-heading" className="mt-12">
        <h2 id="team-heading" className="text-h3 font-extrabold text-ink">
          The team
        </h2>
        <ul className="mt-4 grid list-none gap-4 p-0 sm:grid-cols-3">
          {ABOUT.team.map((m) => (
            <li
              key={m.name}
              className="rounded-card border border-hairline bg-surface p-5 text-center"
            >
              {/* Initials rather than a stock photo: a fake portrait of a
                  person who does not exist is worse than none. */}
              <span
                aria-hidden="true"
                className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-accent text-lg font-extrabold text-on-accent"
              >
                {m.name
                  .split(' ')
                  .map((part) => part[0])
                  .join('')}
              </span>
              <h3 className="text-h6 font-bold text-ink">{m.name}</h3>
              <p className="text-sm text-muted">{m.role}</p>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="company-heading" className="mt-12">
        <h2 id="company-heading" className="text-h3 font-extrabold text-ink">
          Company details
        </h2>
        {/* A description list, not a table: these are name/value pairs, and
            <dl> is what a screen reader announces correctly as such. */}
        <dl className="mt-4 grid grid-cols-1 gap-x-8 gap-y-2 sm:grid-cols-[max-content_1fr]">
          <dt className="font-semibold text-ink">Registered name</dt>
          <dd className="m-0 text-muted">{COMPANY.name}</dd>
          <dt className="font-semibold text-ink">Registry code</dt>
          <dd className="m-0 text-muted">{COMPANY.registryCode}</dd>
          <dt className="font-semibold text-ink">Address</dt>
          <dd className="m-0 text-muted">{COMPANY.address}</dd>
          <dt className="font-semibold text-ink">Trading since</dt>
          <dd className="m-0 text-muted">{ABOUT.founded}</dd>
          <dt className="font-semibold text-ink">Email</dt>
          <dd className="m-0">
            <a href={`mailto:${COMPANY.email}`}>{COMPANY.email}</a>
          </dd>
          <dt className="font-semibold text-ink">Phone</dt>
          <dd className="m-0">
            <a href={`tel:${COMPANY.phone.replace(/\s/g, '')}`}>{COMPANY.phone}</a>
          </dd>
        </dl>
      </section>

      <section aria-labelledby="social-heading" className="mt-12 mb-8">
        <h2 id="social-heading" className="text-h3 font-extrabold text-ink">
          Follow us
        </h2>
        <ul className="mt-4 flex list-none flex-wrap gap-3 p-0">
          {ABOUT.social.map((s) => {
            return (
              <li key={s.label}>
                <a
                  href={s.href}
                  // Third-party links: noopener stops the target page reaching
                  // back through window.opener, noreferrer withholds where the
                  // visitor came from.
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-2 rounded-field border border-hairline bg-surface px-4 py-2 font-semibold text-ink no-underline hover:bg-canvas"
                >
                  {s.label}
                  <ExternalLink className="h-4 w-4" aria-hidden="true" />
                  {/* Tells a screen-reader user the link leaves the site,
                      which a sighted user infers from the icon. */}
                  <span className="sr-only"> (opens in a new tab)</span>
                </a>
              </li>
            );
          })}
        </ul>
      </section>
    </div>
  );
}
