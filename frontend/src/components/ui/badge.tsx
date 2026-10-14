import { cva, type VariantProps } from 'class-variance-authority';
import type { HTMLAttributes } from 'react';
import { cn } from '../../lib/cn';

/**
 * Same tone system the order-status badges already used
 * (StatusPill/orderStatusTone) — this is that component turned into the
 * shared primitive so any future status/tag badge reads consistently
 * instead of being its own bespoke span.
 */
const badgeVariants = cva(
  'inline-flex items-center rounded-full px-2.5 py-1 text-xs font-bold whitespace-nowrap',
  {
    variants: {
      variant: {
        neutral: 'bg-canvas text-muted',
        good: 'bg-ok-bg text-ok',
        warn: 'bg-accent text-on-accent',
        bad: 'bg-danger-bg text-danger',
      },
    },
    defaultVariants: { variant: 'neutral' },
  },
);

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement>, VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ variant, className }))} {...props} />;
}

export { Badge, badgeVariants };
