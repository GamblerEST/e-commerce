import { forwardRef, type InputHTMLAttributes } from 'react';
import { cn } from '../../lib/cn';

const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-ink placeholder:text-muted',
        'focus-visible:outline focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ink',
        'disabled:opacity-60',
        // aria-invalid is how every form on this site already flags a bad
        // field — this makes the invalid state visible without any caller
        // having to pass a conditional className of their own.
        'aria-[invalid=true]:border-danger',
        className,
      )}
      {...props}
    />
  ),
);
Input.displayName = 'Input';

export { Input };
