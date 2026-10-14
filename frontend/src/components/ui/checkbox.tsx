import * as CheckboxPrimitive from '@radix-ui/react-checkbox';
import { Check } from 'lucide-react';
import { forwardRef, type ComponentPropsWithoutRef, type ElementRef } from 'react';
import { cn } from '../../lib/cn';

const Checkbox = forwardRef<
  ElementRef<typeof CheckboxPrimitive.Root>,
  ComponentPropsWithoutRef<typeof CheckboxPrimitive.Root>
>(({ className, ...props }, ref) => (
  <CheckboxPrimitive.Root
    ref={ref}
    className={cn(
      'flex h-4 w-4 shrink-0 items-center justify-center rounded-sm border border-hairline bg-surface',
      'focus-visible:outline focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ink',
      'data-[state=checked]:border-ink data-[state=checked]:bg-ink data-[state=checked]:text-surface',
      'disabled:opacity-60',
      className,
    )}
    {...props}
  >
    <CheckboxPrimitive.Indicator>
      <Check className="h-3.5 w-3.5" aria-hidden="true" />
    </CheckboxPrimitive.Indicator>
  </CheckboxPrimitive.Root>
));
Checkbox.displayName = CheckboxPrimitive.Root.displayName;

export { Checkbox };
