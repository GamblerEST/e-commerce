import { COMPANY } from '../companyInfo';
import PageMeta from '../components/PageMeta';

export default function SalesConditionsPage() {
  return (
    <div className="mx-auto max-w-3xl">
      {/* Same text the server writes for /sales-conditions (SeoPageService). */}
      <PageMeta
        title="Sales conditions — Estonian Shop"
        description="The conditions of sale at Estonian Shop: who the seller is, how prices and payment work, and the terms that apply to every order placed in this shop."
        canonicalPath="/sales-conditions"
      />
      <h1 className="text-h1 font-extrabold text-ink">Sales conditions</h1>
      <p className="mt-2 text-sm text-muted">Last updated 2026-08-18.</p>

      <section className="mt-10">
        <h2 className="text-h3 font-extrabold text-ink">Seller</h2>
        <p className="mt-3 text-ink">
          {COMPANY.name}, registry code {COMPANY.registryCode}, {COMPANY.address}. Contact:{' '}
          <a href={`mailto:${COMPANY.email}`}>{COMPANY.email}</a>.
        </p>
      </section>

      <section className="mt-8">
        <h2 className="text-h3 font-extrabold text-ink">Prices and payment</h2>
        <p className="mt-3 text-ink">
          All prices are shown in euros and include Estonian VAT at the applicable rate. Payment is
          taken by card via Stripe at the time of order. We never see or store your full card
          number.
        </p>
      </section>

      <section className="mt-8">
        <h2 className="text-h3 font-extrabold text-ink">Delivery</h2>
        <p className="mt-3 text-ink">
          Orders are shipped to the address you provide at checkout. Estimated delivery times are
          shown per product; delays are communicated by email.
        </p>
      </section>

      <section className="mt-8">
        <h2 className="text-h3 font-extrabold text-ink">14-day right of withdrawal</h2>
        <p className="mt-3 text-ink">
          Under the EU Distance Marketing Directive, as implemented in Estonian law, you have the
          right to withdraw from a purchase within 14 days of receiving your order, without giving
          a reason. To exercise this right, contact us at{' '}
          <a href={`mailto:${COMPANY.email}`}>{COMPANY.email}</a> with your order number. Once we
          receive the returned item in its original condition, we refund the purchase price
          (excluding return shipping) within 14 days.
        </p>
        <p className="mt-3 text-ink">
          This right does not apply to goods made to your specification, perishable goods, or
          digital content you&apos;ve already started downloading with your consent.
        </p>
      </section>

      <section className="mt-8">
        <h2 className="text-h3 font-extrabold text-ink">Warranty and complaints</h2>
        <p className="mt-3 text-ink">
          Goods carry the statutory 2-year liability period under Estonian consumer law. If
          something arrives faulty or not as described, contact us and we&apos;ll arrange a repair,
          replacement, or refund.
        </p>
      </section>

      <section className="mt-8 mb-8">
        <h2 className="text-h3 font-extrabold text-ink">Governing law</h2>
        <p className="mt-3 text-ink">
          These conditions are governed by Estonian law. Disputes we can&apos;t resolve directly may be
          brought before the Estonian Consumer Disputes Committee or the competent Estonian court.
        </p>
      </section>
    </div>
  );
}
