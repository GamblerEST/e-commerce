import * as DialogPrimitive from '@radix-ui/react-dialog';
import { cva, type VariantProps } from 'class-variance-authority';
import { X } from 'lucide-react';
import { forwardRef, type ComponentPropsWithoutRef, type ElementRef } from 'react';
import { cn } from '../../lib/cn';

/**
 * A slide-over panel, built on Radix's Dialog — same focus-trap, Escape-to-
 * close and inert-background behaviour a modal needs, just docked to an edge
 * instead of centred. Used for the mobile filter drawer: a full filter form
 * does not fit beside a product grid on a narrow screen, and a dialog is the
 * correct native-feeling place to put it rather than pushing it inline and
 * making the page itself scroll past it.
 */
const Sheet = DialogPrimitive.Root;
const SheetTrigger = DialogPrimitive.Trigger;
const SheetClose = DialogPrimitive.Close;
const SheetPortal = DialogPrimitive.Portal;

const SheetOverlay = forwardRef<
  ElementRef<typeof DialogPrimitive.Overlay>,
  ComponentPropsWithoutRef<typeof DialogPrimitive.Overlay>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Overlay
    ref={ref}
    className={cn(
      'fixed inset-0 z-50 bg-ink/50 transition-opacity duration-300',
      'data-[state=closed]:opacity-0 data-[state=open]:opacity-100',
      className,
    )}
    {...props}
  />
));
SheetOverlay.displayName = DialogPrimitive.Overlay.displayName;

const sheetVariants = cva(
  'fixed z-50 flex flex-col gap-4 overflow-y-auto bg-surface p-6 shadow-xl transition-transform duration-300 ease-in-out',
  {
    variants: {
      side: {
        // Only 'right' and 'bottom' are used in this app (a desktop-width
        // panel and a mobile drawer) — left/top omitted rather than left
        // half-implemented and untested.
        right:
          'inset-y-0 right-0 h-full w-full max-w-sm border-l border-hairline data-[state=closed]:translate-x-full data-[state=open]:translate-x-0',
        bottom:
          'inset-x-0 bottom-0 max-h-[85vh] rounded-t-card border-t border-hairline data-[state=closed]:translate-y-full data-[state=open]:translate-y-0',
      },
    },
    defaultVariants: { side: 'right' },
  },
);

interface SheetContentProps
  extends ComponentPropsWithoutRef<typeof DialogPrimitive.Content>,
    VariantProps<typeof sheetVariants> {}

const SheetContent = forwardRef<ElementRef<typeof DialogPrimitive.Content>, SheetContentProps>(
  ({ side, className, children, ...props }, ref) => (
    <SheetPortal>
      <SheetOverlay />
      <DialogPrimitive.Content ref={ref} className={cn(sheetVariants({ side }), className)} {...props}>
        {children}
        <DialogPrimitive.Close className="absolute top-4 right-4 rounded-field p-1.5 text-muted hover:bg-canvas hover:text-ink">
          <X className="h-5 w-5" aria-hidden="true" />
          <span className="sr-only">Close</span>
        </DialogPrimitive.Close>
      </DialogPrimitive.Content>
    </SheetPortal>
  ),
);
SheetContent.displayName = DialogPrimitive.Content.displayName;

function SheetHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-1.5 pr-8', className)} {...props} />;
}

const SheetTitle = forwardRef<
  ElementRef<typeof DialogPrimitive.Title>,
  ComponentPropsWithoutRef<typeof DialogPrimitive.Title>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Title ref={ref} className={cn('text-h4 font-bold text-ink', className)} {...props} />
));
SheetTitle.displayName = DialogPrimitive.Title.displayName;

export { Sheet, SheetTrigger, SheetClose, SheetContent, SheetHeader, SheetTitle };
