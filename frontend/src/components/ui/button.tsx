import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { cn } from '../../lib/cn';

/**
 * The shop's one button component, in place of the `rounded-[0_1.25rem_0_
 * 1.25rem] bg-accent px-6 py-2.5 font-bold ...` string that used to be
 * retyped, with small drifts, on every page.
 *
 * The asymmetric corner (index.css's --radius-btn, the single most
 * recognisable piece of the brand) stays exactly where it was: on the
 * `default` variant only. A whole toolbar of them reads as a zigzag, so
 * `secondary`/`ghost`/`link` use the plain field radius instead — this is a
 * design constraint carried over from before, not a new one.
 */
const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap font-bold transition disabled:pointer-events-none disabled:opacity-60 focus-visible:outline focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ink',
  {
    variants: {
      variant: {
        default: 'rounded-[0_1.25rem_0_1.25rem] bg-accent text-on-accent hover:bg-accent-hover',
        // Same fill as `default`, plain corner — a row of admin-table/toolbar
        // actions is exactly the "whole row of them reads as a zigzag" case
        // the asymmetric corner is deliberately withheld from.
        accent: 'rounded-field bg-accent text-on-accent hover:bg-accent-hover',
        secondary: 'rounded-field border border-hairline bg-surface text-ink hover:bg-canvas',
        danger: 'rounded-[0_1.25rem_0_1.25rem] bg-danger text-surface hover:opacity-90',
        outlineDanger: 'rounded-field border border-danger text-danger hover:bg-danger-bg',
        ghost: 'rounded-field text-ink hover:bg-canvas',
        link: 'font-semibold text-ink underline-offset-4 hover:underline',
      },
      size: {
        default: 'px-6 py-2.5 text-base',
        // px-3, not px-4: this is admin's own former button padding, and
        // admin's dense tables (several actions in one row, sometimes beside
        // a Pill/Badge too) are the main place `sm` is used, down to a
        // required 320px viewport — the 4px-a-side difference is exactly
        // the kind of thing that turns "fits" into "wraps" at that width.
        sm: 'px-3 py-1.5 text-sm',
        lg: 'px-10 py-4 text-lg',
        icon: 'p-2',
      },
    },
    defaultVariants: {
      variant: 'default',
      size: 'default',
    },
  },
);

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  /** Renders the child element itself with the button's classes/behaviour (Radix's slot pattern) — used to make a <Link> look and act like a button without nesting an <a> inside a <button>. */
  asChild?: boolean;
}

const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, type = 'button', ...props }, ref) => {
    const Comp = asChild ? Slot : 'button';
    return (
      <Comp
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...(asChild ? {} : { type })}
        {...props}
      />
    );
  },
);
Button.displayName = 'Button';

export { Button, buttonVariants };
