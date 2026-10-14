import * as SliderPrimitive from '@radix-ui/react-slider';
import { forwardRef, type ComponentPropsWithoutRef, type ElementRef } from 'react';
import { cn } from '../../lib/cn';

/**
 * Wraps Radix's slider, which already renders one <Slider.Thumb> per value in
 * the `value`/`defaultValue` array — pass two values (min, max) and it is a
 * real dual-handle range slider for free, with the keyboard/ARIA behaviour a
 * hand-rolled version would have to reimplement (arrow keys, Home/End, and
 * each thumb's own accessible name and current value announced natively).
 */
const Slider = forwardRef<
  ElementRef<typeof SliderPrimitive.Root>,
  ComponentPropsWithoutRef<typeof SliderPrimitive.Root>
>(({ className, ...props }, ref) => (
  <SliderPrimitive.Root
    ref={ref}
    className={cn('relative flex w-full touch-none items-center py-2 select-none', className)}
    {...props}
  >
    <SliderPrimitive.Track className="relative h-1.5 w-full grow overflow-hidden rounded-full bg-hairline">
      <SliderPrimitive.Range className="absolute h-full bg-ink" />
    </SliderPrimitive.Track>
    {(props.value ?? props.defaultValue ?? [0]).map((_, i) => (
      <SliderPrimitive.Thumb
        key={i}
        className="block h-5 w-5 shrink-0 rounded-full border-2 border-ink bg-surface transition-colors focus-visible:outline focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ink disabled:pointer-events-none disabled:opacity-50"
      />
    ))}
  </SliderPrimitive.Root>
));
Slider.displayName = SliderPrimitive.Root.displayName;

export { Slider };
