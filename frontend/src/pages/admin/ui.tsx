import type { ReactNode } from 'react';
import { Badge, type BadgeProps } from '../../components/ui/badge';
import { Button as SharedButton } from '../../components/ui/button';

/**
 * Shared admin primitives.
 *
 * Six admin screens share one visual language, so the table shell, the status
 * pill and the message strip live here rather than being re-typed (and
 * re-diverging) per screen. Nothing here is clever — it exists so that how
 * staff move through a screen, and how data is presented, stay consistent
 * rather than being improvised per page.
 */

export function AdminError({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    // role="alert" so a failure is ANNOUNCED, not just displayed. An admin
    // acting on a table needs to know a write was rejected even if their
    // attention is on the row they just clicked.
    <p role="alert" className="mb-4 rounded-field bg-danger-bg px-4 py-3 font-semibold text-danger">
      {message}
    </p>
  );
}

export function AdminNotice({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p role="status" className="mb-4 rounded-field bg-ok-bg px-4 py-3 font-semibold text-ok">
      {message}
    </p>
  );
}

/**
 * Horizontally scrollable table wrapper.
 *
 * Admin tables are genuinely wide and the narrowest supported viewport is
 * 320px. The wrapper
 * scrolls the TABLE rather than letting it push the page wide — a page-level
 * horizontal scrollbar breaks every other screen too. tabIndex makes the
 * scroll container keyboard-reachable, which is required for a scrollable
 * region that would otherwise be unreachable without a mouse.
 */
export function TableWrap({ children, label }: { children: ReactNode; label: string }) {
  return (
    <div
      role="region"
      aria-label={label}
      tabIndex={0}
      className="overflow-x-auto rounded-card border border-hairline bg-surface"
    >
      {children}
    </div>
  );
}

export function Th({ children, className = '' }: { children: ReactNode; className?: string }) {
  return (
    <th
      scope="col"
      className={`border-b border-hairline px-4 py-3 text-left text-xs font-bold tracking-wide text-muted uppercase ${className}`}
    >
      {children}
    </th>
  );
}

export function Td({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <td className={`border-b border-hairline px-4 py-3 align-middle ${className}`}>{children}</td>;
}

/**
 * Both re-export the shop's shared primitives (components/ui/badge.tsx,
 * button.tsx — the same ones the storefront pages use) rather than keeping a
 * second, admin-only copy of the exact same tone map and button styles that
 * used to live here and could quietly drift from the storefront's. Kept
 * under their admin names, and admin's own narrower prop shape, so none of
 * the six screens calling <Pill>/<Button> needed to change.
 */
export function Pill({ children, tone = 'neutral' }: { children: ReactNode; tone?: BadgeProps['variant'] }) {
  return <Badge variant={tone}>{children}</Badge>;
}

export function Button({
  children,
  onClick,
  type = 'button',
  variant = 'secondary',
  disabled,
  title,
}: {
  children: ReactNode;
  onClick?: () => void;
  type?: 'button' | 'submit';
  variant?: 'primary' | 'secondary' | 'danger';
  disabled?: boolean;
  title?: string;
}) {
  // Admin's three names map onto the shared component's wider variant set —
  // "primary" keeps its plain corner (accent, not default: a table full of
  // action buttons is exactly the row-of-them case the asymmetric corner is
  // withheld from), "danger" here has always meant an outlined warning
  // colour, not a solid red fill.
  const mapped = { primary: 'accent', secondary: 'secondary', danger: 'outlineDanger' } as const;
  return (
    <SharedButton
      type={type}
      onClick={onClick}
      disabled={disabled}
      title={title}
      variant={mapped[variant]}
      size="sm"
    >
      {children}
    </SharedButton>
  );
}

export function Field({
  label,
  children,
  hint,
  error,
}: {
  label: string;
  children: ReactNode;
  hint?: string;
  error?: string;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-sm font-semibold text-ink">{label}</span>
      {children}
      {hint && !error && <span className="mt-1 block text-xs text-muted">{hint}</span>}
      {error && <span className="mt-1 block text-xs font-semibold text-danger">{error}</span>}
    </label>
  );
}

export const inputClass =
  'w-full rounded-field border border-hairline bg-canvas px-3 py-2 text-sm';
