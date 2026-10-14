import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { apiFetch, ApiRequestError } from '../../api/client';
import type { ReviewResponse } from '../../api/types';
import StarRatingInput from '../StarRatingInput';
import { Button } from '../ui/button';

interface ReviewFormProps {
  productId: string;
  productName: string;
  /** Present when editing an existing review rather than writing a new one. */
  existing?: ReviewResponse;
  onDone: () => void | Promise<void>;
  onCancel?: () => void;
}

/**
 * Client-side bounds MIRROR the server exactly — ReviewRequest's Bean
 * Validation annotations, which in turn mirror V15__reviews.sql's CHECK
 * constraints. Three layers stating the same rule is deliberate — validation
 * belongs on the client AND the server: this one exists to give instant,
 * field-level feedback, and is never the thing that actually protects the
 * database. Anything that reaches the API is validated again there.
 */
const schema = z.object({
  rating: z
    .number()
    .int()
    .min(1, 'Choose a rating from 1 to 5 stars')
    .max(5, 'Choose a rating from 1 to 5 stars'),
  title: z.string().max(150, 'Keep the title under 150 characters').optional(),
  body: z
    .string()
    .trim()
    .min(1, 'Write a few words about the product')
    .max(4000, 'Keep the review under 4000 characters'),
});

type FormValues = z.infer<typeof schema>;

export default function ReviewForm({
  productId,
  productName,
  existing,
  onDone,
  onCancel,
}: ReviewFormProps) {
  const [submitError, setSubmitError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      rating: existing?.rating ?? 0,
      title: existing?.title ?? '',
      body: existing?.body ?? '',
    },
  });

  const rating = watch('rating');
  const body = watch('body') ?? '';

  async function onSubmit(values: FormValues) {
    setSubmitError(null);
    try {
      await apiFetch(
        existing ? `/reviews/${existing.id}` : `/products/${productId}/reviews`,
        {
          method: existing ? 'PATCH' : 'POST',
          body: {
            rating: values.rating,
            title: values.title?.trim() ? values.title.trim() : null,
            body: values.body.trim(),
          },
        },
      );
      await onDone();
    } catch (e) {
      setSubmitError(
        e instanceof ApiRequestError
          ? e.message
          : 'Your review could not be saved. Please try again.',
      );
    }
  }

  return (
    <form
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      className="mb-8 rounded-card border border-hairline bg-surface p-6"
    >
      <h3 className="mb-4 text-h4 font-bold text-ink">
        {existing ? 'Edit your review' : `Review ${productName}`}
      </h3>

      {submitError && (
        // role="alert" so a screen reader announces the failure immediately
        // rather than leaving the user waiting on a submit that silently died.
        <p role="alert" className="mb-4 rounded-field bg-danger-bg px-4 py-3 text-danger">
          {submitError}
        </p>
      )}

      <div className="mb-4">
        <StarRatingInput
          value={rating}
          onChange={(v) => setValue('rating', v, { shouldValidate: true })}
          error={Boolean(errors.rating)}
        />
        {errors.rating && (
          <p className="mt-1 text-sm text-danger">{errors.rating.message}</p>
        )}
      </div>

      <div className="mb-4">
        <label htmlFor="review-title" className="mb-1 block text-sm font-semibold text-ink">
          Title <span className="font-normal text-muted">(optional)</span>
        </label>
        <input
          id="review-title"
          type="text"
          maxLength={150}
          {...register('title')}
          aria-invalid={Boolean(errors.title)}
          aria-describedby={errors.title ? 'review-title-error' : undefined}
          className="w-full rounded-field border border-hairline bg-canvas px-3 py-2"
        />
        {errors.title && (
          <p id="review-title-error" className="mt-1 text-sm text-danger">
            {errors.title.message}
          </p>
        )}
      </div>

      <div className="mb-4">
        <label htmlFor="review-body" className="mb-1 block text-sm font-semibold text-ink">
          Your review
        </label>
        <textarea
          id="review-body"
          rows={5}
          maxLength={4000}
          {...register('body')}
          aria-invalid={Boolean(errors.body)}
          aria-describedby="review-body-count"
          className="w-full rounded-field border border-hairline bg-canvas px-3 py-2"
        />
        <div className="mt-1 flex justify-between gap-4">
          {errors.body ? (
            <p className="text-sm text-danger">{errors.body.message}</p>
          ) : (
            <span />
          )}
          {/* aria-live="polite" so the count is available to a screen reader
              on demand without interrupting typing on every keystroke. */}
          <p id="review-body-count" aria-live="polite" className="text-sm text-muted">
            {body.length} / 4000
          </p>
        </div>
      </div>

      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? 'Saving…' : existing ? 'Save changes' : 'Post review'}
        </Button>
        {onCancel && (
          // secondary, not the primary corner Cancel had before — two of
          // that shape side by side is exactly the zigzag the asymmetric
          // corner is meant to stay out of (see Button's own docstring).
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        )}
      </div>
    </form>
  );
}
