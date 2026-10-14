import { useEffect, useId, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Search } from 'lucide-react';
import { apiFetch } from '../api/client';
import type { SuggestionItem } from '../api/types';

/**
 * Site-wide type-ahead search with suggestions, available on every page.
 *
 * Implemented as a WAI-ARIA combobox rather than an input with a styled <ul>
 * underneath. The difference matters: without the combobox roles a screen
 * reader never learns that a list appeared, so suggestions are invisible to
 * anyone not looking at the screen, and arrow keys do nothing. With them,
 * Up/Down walk the options, Enter picks one, and Escape dismisses — the
 * behaviour every user already expects from a search box.
 */
export default function QuickSearch({ onNavigate }: { onNavigate?: () => void }) {
  const [q, setQ] = useState('');
  const [items, setItems] = useState<SuggestionItem[]>([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const navigate = useNavigate();
  const listId = useId();
  const boxRef = useRef<HTMLDivElement>(null);

  // Debounced, and aborted on change. Without the abort, a slow early response
  // can land after a later one and overwrite fresher suggestions with stale
  // ones — the classic out-of-order autocomplete bug.
  useEffect(() => {
    const term = q.trim();
    if (term.length < 2) {
      setItems([]);
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      apiFetch<SuggestionItem[]>(`/products/suggest?q=${encodeURIComponent(term)}`, {
        signal: controller.signal,
      })
        .then((res) => {
          setItems(res);
          setOpen(true);
          setActive(-1);
        })
        .catch(() => {
          /* A failed suggestion lookup is not worth showing an error for —
             the user can still submit the search. */
        });
    }, 200);

    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [q]);

  // Clicking anywhere else dismisses the list.
  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, []);

  function go(path: string) {
    setOpen(false);
    setQ('');
    onNavigate?.();
    navigate(path);
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    if (active >= 0 && items[active]) {
      go(`/products/${items[active].slug}`);
    } else if (q.trim()) {
      go(`/products?q=${encodeURIComponent(q.trim())}`);
    }
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (!open || items.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => (i + 1) % items.length);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => (i <= 0 ? items.length - 1 : i - 1));
    } else if (e.key === 'Escape') {
      setOpen(false);
      setActive(-1);
    }
  }

  return (
    <div ref={boxRef} className="relative w-full max-w-md">
      <form role="search" onSubmit={submit}>
        <label htmlFor="quick-search" className="sr-only">
          Search products
        </label>
        <div className="flex items-center gap-2 rounded-field border border-hairline bg-surface px-3 py-2">
          <Search className="h-4 w-4 shrink-0 text-muted" aria-hidden="true" />
          <input
            id="quick-search"
            type="search"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={onKeyDown}
            onFocus={() => items.length > 0 && setOpen(true)}
            placeholder="Search products…"
            autoComplete="off"
            role="combobox"
            aria-expanded={open && items.length > 0}
            aria-controls={listId}
            aria-autocomplete="list"
            aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
            className="w-full bg-transparent text-sm outline-none"
          />
        </div>
      </form>

      {/* Announced to screen readers as results arrive, without stealing focus. */}
      <span aria-live="polite" className="sr-only">
        {open && items.length > 0 ? `${items.length} suggestions available` : ''}
      </span>

      {open && items.length > 0 && (
        <ul
          id={listId}
          role="listbox"
          aria-label="Product suggestions"
          className="absolute top-full right-0 left-0 z-50 mt-1 max-h-80 list-none overflow-auto rounded-field border border-hairline bg-surface p-1 shadow-lg"
        >
          {items.map((s, i) => (
            <li
              key={s.id}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={i === active}
              // onMouseDown, not onClick: the input's blur fires first on
              // click and would close the list before the click registers.
              onMouseDown={(e) => {
                e.preventDefault();
                go(`/products/${s.slug}`);
              }}
              onMouseEnter={() => setActive(i)}
              className={[
                'cursor-pointer rounded-sm px-3 py-2 text-sm',
                i === active ? 'bg-canvas font-semibold' : '',
              ].join(' ')}
            >
              {s.name}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
