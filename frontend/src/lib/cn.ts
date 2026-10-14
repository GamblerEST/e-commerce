import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Merges class strings the way every shadcn-style component expects:
 * conditional classes via clsx, then tailwind-merge resolves conflicts so the
 * LAST conflicting utility wins (e.g. a caller passing `bg-danger` to override
 * a component's own `bg-accent` actually wins, instead of both landing in the
 * className string and Tailwind's cascade picking whichever was generated
 * first in the stylesheet — which is not necessarily the one written last).
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
