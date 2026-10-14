import { Link } from 'react-router-dom';
import { COMPANY } from '../companyInfo';
import PageMeta from '../components/PageMeta';

export default function PrivacyPolicyPage() {
  return (
    <div className="mx-auto max-w-3xl">
      {/* Same text the server writes for /privacy (SeoPageService). */}
      <PageMeta
        title="Privacy policy — Estonian Shop"
        description="How Estonian Shop collects, uses and protects your personal data: what we store, who receives it and why, which cookies are set, and the rights you have over it."
        canonicalPath="/privacy"
      />
      <h1 className="text-h1 font-extrabold text-ink">Privacy policy</h1>
      <p className="mt-2 text-sm text-muted">Last updated 2026-08-18.</p>

      <section className="mt-10">
        <h2 className="text-h3 font-extrabold text-ink">Who we are</h2>
        <p className="mt-3 text-ink">
          {COMPANY.name} ({COMPANY.address}) is the data controller for the personal data described
          below. Contact us at <a href={`mailto:${COMPANY.email}`}>{COMPANY.email}</a> for any
          privacy request.
        </p>
      </section>

      <section className="mt-8">
        <h2 className="text-h3 font-extrabold text-ink">What we collect</h2>
        <ul className="mt-3 list-disc space-y-1 pl-5 text-ink">
          <li>Account data: email, display name, and a hashed password (never the password itself).</li>
          <li>Order data: billing name and address, snapshotted onto each order at the time of purchase.</li>
          <li>Payment data: handled entirely by Stripe — we never see or store your full card number.</li>
          <li>Technical data: rate-limiting and audit logs (IP address, timestamps) for security purposes.</li>
        </ul>
      </section>

      <section className="mt-8">
        <h2 className="text-h3 font-extrabold text-ink">How we use it</h2>
        <p className="mt-3 text-ink">
          To create and fulfil your orders, to let you sign in and see your order history, and to
          meet our legal obligations (Estonian accounting and tax law require invoices to be kept
          for 7 years). We never sell your data.
        </p>
      </section>

      <section className="mt-8">
        <h2 className="text-h3 font-extrabold text-ink">Your rights</h2>
        <p className="mt-3 text-ink">
          You do not have to ask us for either of these — both are buttons on your{' '}
          <Link to="/account">account page</Link>.
        </p>
        <ul className="mt-3 list-disc space-y-1 pl-5 text-ink">
          <li>
            <strong>Get a copy of your data.</strong> Downloads everything we hold about you as a
            JSON file: your profile, every order with its lines, and any reviews you have written.
          </li>
          <li>
            <strong>Delete your account.</strong> Erases your name, email and sign-in details
            immediately and ends every session.
          </li>
        </ul>
        <p className="mt-3 text-ink">
          Deleting an account does not delete your past orders. They are kept, with your account
          detached from them, for the 7-year retention period Estonian accounting law requires —
          a legal obligation we cannot override, and one the GDPR explicitly allows for
          (Art. 17(3)(b)).
        </p>
      </section>

      <section className="mt-8">
        <h2 className="text-h3 font-extrabold text-ink">Cookies</h2>
        <p className="mt-3 text-ink">Our own cookies are strictly necessary ones:</p>
        <ul className="mt-3 list-disc space-y-1 pl-5 text-ink">
          <li>one to keep you signed in (HttpOnly, so no script can read it);</li>
          <li>one for CSRF protection;</li>
          <li>one to remember a guest shopping cart.</li>
        </ul>
        <p className="mt-3 text-ink">
          Two of our service providers also set cookies in your browser. Stripe sets{' '}
          <code>__stripe_mid</code> and <code>__stripe_sid</code> on the checkout page, which it
          uses to detect fraud. Google&rsquo;s reCAPTCHA sets its own cookies on the registration
          page to tell people from bots. We do not control either set, and neither is used by us
          for advertising or profiling.
        </p>
        <p className="mt-3 text-ink">
          Because all of these are either strictly necessary or used solely for security and fraud
          prevention, we do not show a consent banner. We do not use analytics, advertising or
          tracking cookies of any kind — if that ever changes, this page changes first and you will
          be asked to consent.
        </p>
      </section>

      <section className="mt-8 mb-8">
        <h2 className="text-h3 font-extrabold text-ink">Third parties</h2>
        <p className="mt-3 text-ink">
          Four companies and one public authority receive some of your data when you use this
          shop, each for one specific purpose and under its own privacy policy:
        </p>
        <ul className="mt-3 list-disc space-y-2 pl-5 text-ink">
          <li>
            <strong>Stripe</strong> processes card payments. Your card details go directly to
            Stripe and never reach our servers.
          </li>
          <li>
            <strong>Montonio</strong> processes bank-link payments. When you pay through your bank
            you leave this shop for your bank's own pages, and Montonio tells us whether the
            payment went through. That answer can include the name and account number the payment
            came from; we do not keep them. We send Montonio the order reference and amount, not
            your name or address.
          </li>
          <li>
            <strong>Google</strong> receives your IP address and browser information when the
            reCAPTCHA check loads on the registration page, and again if you choose to sign in with
            a Google account.
          </li>
          <li>
            <strong>Our email provider</strong> handles order confirmations, verification links and
            password resets, so it necessarily sees your email address.
          </li>
          <li>
            <strong>Maa- ja Ruumiamet</strong>, the Estonian Land and Spatial Development Board,
            runs the national address register. When you use &ldquo;Find your address&rdquo;, the
            text you type is sent to it by our server rather than your browser, so it never sees
            your IP address.
          </li>
        </ul>
        <p className="mt-3 text-ink">We do not sell your data, and we share it with nobody beyond those above.</p>
      </section>
    </div>
  );
}
