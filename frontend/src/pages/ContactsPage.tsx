import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { COMPANY } from '../companyInfo';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';
import { apiFetch, ApiRequestError } from '../api/client';

/**
 * Contact page: the shop's identification details, plus a working form.
 *
 * The bounds below MIRROR ContactRequest's Bean Validation annotations exactly.
 * Three layers stating the same rule is deliberate — this one exists to give
 * instant, field-level feedback, and is never the thing that actually protects
 * the server. Anything that reaches the API is validated again there, and the
 * endpoint is rate limited per IP on top.
 */
const schema = z.object({
  name: z.string().trim().min(1, 'Tell us your name so we know who to reply to').max(120),
  email: z
    .string()
    .trim()
    .min(1, 'We need an email address to reply to')
    .email('That does not look like an email address')
    .max(254),
  subject: z.string().trim().min(1, 'Please add a subject').max(150),
  message: z
    .string()
    .trim()
    .min(10, 'Please write a little more — at least 10 characters')
    .max(4000, 'Please keep the message under 4000 characters'),
});

type FormValues = z.infer<typeof schema>;

const FIELD_CLASS = 'mt-1 w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink';

export default function ContactsPage() {
  const [sent, setSent] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', email: '', subject: '', message: '' },
  });

  const message = watch('message') ?? '';

  async function onSubmit(values: FormValues) {
    setSubmitError(null);
    try {
      await apiFetch('/contact', { method: 'POST', body: values });
      setSent(true);
      reset();
    } catch (e) {
      setSubmitError(
        e instanceof ApiRequestError
          ? e.message
          : 'Your message could not be sent. Please email us directly instead.',
      );
    }
  }

  return (
    <div className="mx-auto max-w-2xl">
      <PageMeta
        title="Contact Estonian Shop"
        description="Get in touch about an order, a return or anything else. Send us a message, or reach Estonian Shop directly by email or phone — we reply within two business days."
        canonicalPath="/contacts"
      />

      <h1 className="text-h1 font-extrabold text-ink">Contact us</h1>
      <p className="mt-3 text-muted">Questions about an order, a return, or anything else — send us a message:</p>

      {sent ? (
        // role="status" so the confirmation is announced, not just shown. A
        // form that visually clears with no feedback reads as "did that work?"
        <div role="status" className="mt-6 rounded-card border border-hairline bg-ok-bg p-6">
          <h2 className="mt-0 text-h4 font-bold text-ok">Message sent</h2>
          <p className="text-ink">
            Thanks — we have your message and aim to reply within 2 business days. Nothing else
            is needed from you.
          </p>
          <Button type="button" variant="link" onClick={() => setSent(false)}>
            Send another message
          </Button>
        </div>
      ) : (
        <form onSubmit={handleSubmit(onSubmit)} noValidate className="mt-6 rounded-card border border-hairline bg-surface p-6">
          {submitError && (
            <p role="alert" className="mb-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
              {submitError}
            </p>
          )}

          <label htmlFor="contact-name" className="mb-3 block text-sm font-semibold text-ink">
            Your name
            <input
              id="contact-name"
              type="text"
              autoComplete="name"
              maxLength={120}
              aria-invalid={Boolean(errors.name)}
              aria-describedby={errors.name ? 'contact-name-error' : undefined}
              {...register('name')}
              className={FIELD_CLASS}
            />
          </label>
          {errors.name && (
            <span id="contact-name-error" className="-mt-2 mb-3 block text-sm text-danger">
              {errors.name.message}
            </span>
          )}

          <label htmlFor="contact-email" className="mb-3 block text-sm font-semibold text-ink">
            Your email
            <input
              id="contact-email"
              type="email"
              autoComplete="email"
              maxLength={254}
              aria-invalid={Boolean(errors.email)}
              aria-describedby={errors.email ? 'contact-email-error' : undefined}
              {...register('email')}
              className={FIELD_CLASS}
            />
          </label>
          {errors.email && (
            <span id="contact-email-error" className="-mt-2 mb-3 block text-sm text-danger">
              {errors.email.message}
            </span>
          )}

          <label htmlFor="contact-subject" className="mb-3 block text-sm font-semibold text-ink">
            Subject
            <input
              id="contact-subject"
              type="text"
              maxLength={150}
              aria-invalid={Boolean(errors.subject)}
              aria-describedby={errors.subject ? 'contact-subject-error' : undefined}
              {...register('subject')}
              className={FIELD_CLASS}
            />
          </label>
          {errors.subject && (
            <span id="contact-subject-error" className="-mt-2 mb-3 block text-sm text-danger">
              {errors.subject.message}
            </span>
          )}

          <label htmlFor="contact-message" className="mb-1 block text-sm font-semibold text-ink">
            Message
            <textarea
              id="contact-message"
              rows={6}
              maxLength={4000}
              aria-invalid={Boolean(errors.message)}
              aria-describedby="contact-message-count"
              {...register('message')}
              className={FIELD_CLASS}
            />
          </label>
          <div className="mb-4 flex justify-between gap-4">
            {errors.message ? (
              <span className="text-sm text-danger">{errors.message.message}</span>
            ) : (
              <span />
            )}
            <span id="contact-message-count" aria-live="polite" className="text-sm text-muted">
              {message.length} / 4000
            </span>
          </div>

          <Button type="submit" disabled={isSubmitting} className="w-full">
            {isSubmitting ? 'Sending…' : 'Send message'}
          </Button>
          <p className="mt-3 text-center text-sm text-muted">
            We use your email address only to reply to this message.
          </p>
        </form>
      )}

      <section className="mt-10">
        <h2 className="text-h3 font-extrabold text-ink">Or reach us directly</h2>
        <dl className="mt-4 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
          <dt className="font-semibold text-muted">Company</dt>
          <dd className="m-0 text-ink">{COMPANY.name}</dd>
          <dt className="font-semibold text-muted">Registry code</dt>
          <dd className="m-0 text-ink">{COMPANY.registryCode}</dd>
          <dt className="font-semibold text-muted">Address</dt>
          <dd className="m-0 text-ink">{COMPANY.address}</dd>
          <dt className="font-semibold text-muted">Email</dt>
          <dd className="m-0">
            <a href={`mailto:${COMPANY.email}`}>{COMPANY.email}</a>
          </dd>
          <dt className="font-semibold text-muted">Phone</dt>
          <dd className="m-0">
            <a href={`tel:${COMPANY.phone.replace(/\s/g, '')}`}>{COMPANY.phone}</a>
          </dd>
        </dl>
        <p className="mt-4 text-sm text-muted">We aim to reply to every message within 2 business days.</p>
      </section>
    </div>
  );
}
