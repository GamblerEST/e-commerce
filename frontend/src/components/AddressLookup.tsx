import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { MapPin } from 'lucide-react';
import { apiFetch } from '../api/client';
import type { AddressSuggestion } from '../api/types';
import { Button } from './ui/button';

/**
 * "Find your address" against the Estonian address register.
 *
 * Picking a suggestion fills the address fields in one go and hands the register
 * id to the form, which sends it along so the server can confirm the address is
 * real rather than trusting typed text.
 *
 * A building the register knows to have flats offers them as a second step: the
 * register lists every registered flat right on the building's own record (see
 * AddressLookupService), so this asks rather than expecting the customer to
 * already know the register's own numbering. Typing the flat number straight
 * into the search ("Pikk 1-5") skips the second step entirely, since the
 * register already resolved it.
 *
 * Typing the address by hand stays possible throughout. The register is a
 * convenience and a check, never a gate: a customer the search cannot find, or
 * who arrives while the register is down, must still be able to check out.
 *
 * Same WAI-ARIA combobox pattern as QuickSearch, for the same reasons.
 */
export default function AddressLookup({ onSelect }: { onSelect: (address: AddressSuggestion) => void }) {
  const [q, setQ] = useState('');
  const [items, setItems] = useState<AddressSuggestion[]>([]);
  const [searched, setSearched] = useState(false);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [unavailable, setUnavailable] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  // Set once a picked building turns out to have registered flats: the list
  // below switches from buildings to that building's flats until this clears.
  const [apartmentPicker, setApartmentPicker] = useState<AddressSuggestion | null>(null);
  const inputId = useId();
  const listId = useId();
  const hintId = useId();
  const boxRef = useRef<HTMLDivElement>(null);

  // Debounced, and aborted on change, so a slow early answer can never replace a
  // fresher one.
  useEffect(() => {
    setApartmentPicker(null);
    const term = q.trim();
    if (term.length < 3) {
      setItems([]);
      setSearched(false);
      setOpen(false);
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      apiFetch<AddressSuggestion[]>(`/addresses/search?q=${encodeURIComponent(term)}`, {
        signal: controller.signal,
      })
        .then((results) => {
          setItems(results);
          setSearched(true);
          setOpen(true);
          setActive(-1);
          setUnavailable(false);
        })
        .catch(() => {
          if (controller.signal.aborted) return;
          // The register is down, or this visitor is rate limited. Say so once and
          // let the fields below do the job.
          setItems([]);
          setOpen(false);
          setUnavailable(true);
        });
    }, 250);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [q]);

  useEffect(() => {
    function onDocMouseDown(e: MouseEvent) {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', onDocMouseDown);
    return () => document.removeEventListener('mousedown', onDocMouseDown);
  }, []);

  function finish(address: AddressSuggestion, apartment: string) {
    onSelect({ ...address, apartment });
    // Cleared rather than left showing the address: the filled-in fields below
    // are now where the customer reads and corrects it.
    setQ('');
    setItems([]);
    setOpen(false);
    setApartmentPicker(null);
    setActive(-1);
    setAnnouncement(`Address filled in: ${address.label}${apartment ? `, apartment ${apartment}` : ''}`);
  }

  function pick(address: AddressSuggestion) {
    // A resolved apartment (typed as "-5" in the search) needs no second step;
    // an unresolved building that has flats on record does.
    if (address.apartmentOptions.length > 0 && !address.apartment) {
      setApartmentPicker(address);
      setActive(-1);
      return;
    }
    finish(address, address.apartment);
  }

  function pickApartment(tahis: string | null) {
    if (apartmentPicker) finish(apartmentPicker, tahis ?? '');
  }

  function backToBuildings() {
    setApartmentPicker(null);
    setActive(-1);
    setOpen(items.length > 0);
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    const rowCount = apartmentPicker ? apartmentPicker.apartmentOptions.length + 1 : items.length;
    // Enter in a search box must never submit the surrounding checkout form.
    if (e.key === 'Enter') {
      e.preventDefault();
      if (!open || active < 0) return;
      if (apartmentPicker) {
        pickApartment(active === 0 ? null : apartmentPicker.apartmentOptions[active - 1]);
      } else if (items[active]) {
        pick(items[active]);
      }
      return;
    }
    if (!open || rowCount === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((i) => (i + 1) % rowCount);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => (i <= 0 ? rowCount - 1 : i - 1));
    } else if (e.key === 'Escape') {
      e.preventDefault();
      if (apartmentPicker) backToBuildings();
      else {
        setOpen(false);
        setActive(-1);
      }
    }
  }

  const showList = open && (apartmentPicker ? true : items.length > 0);

  return (
    <div ref={boxRef} className="relative">
      <label htmlFor={inputId}>Find your address</label>
      <div className="flex items-center gap-2 rounded-field border border-hairline bg-surface px-3 py-2">
        <MapPin className="h-4 w-4 shrink-0 text-muted" aria-hidden="true" />
        <input
          id={inputId}
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={onKeyDown}
          onFocus={() => (apartmentPicker || items.length > 0) && setOpen(true)}
          placeholder="Start typing, e.g. Pikk 1 Tallinn"
          autoComplete="off"
          role="combobox"
          aria-expanded={showList}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={showList && active >= 0 ? `${listId}-${active}` : undefined}
          aria-describedby={hintId}
          className="w-full bg-transparent outline-none"
        />
      </div>
      <p id={hintId} className="hint">
        {unavailable
          ? 'Address search is not available right now. Please type your address in below.'
          : 'Searches the Estonian address register. Add a flat number after the house number (e.g. "5-12") if you know it, or type your address in below.'}
      </p>

      <span aria-live="polite" className="sr-only">
        {showList
          ? apartmentPicker
            ? `${apartmentPicker.apartmentOptions.length} apartments found`
            : `${items.length} addresses found`
          : announcement}
      </span>

      {showList && (
        <div className="absolute right-0 left-0 z-50 mt-1 max-h-72 overflow-auto rounded-field border border-hairline bg-surface shadow-lg">
          {apartmentPicker && (
            <div className="flex items-center justify-between gap-2 border-b border-hairline px-3 py-2">
              <span className="text-sm font-semibold text-ink">{apartmentPicker.line1} — which apartment?</span>
              <Button type="button" variant="link" onClick={backToBuildings} className="shrink-0">
                Back
              </Button>
            </div>
          )}
          <ul
            id={listId}
            role="listbox"
            aria-label={apartmentPicker ? `Apartments at ${apartmentPicker.line1}` : 'Address suggestions'}
            className="list-none p-1"
          >
            {apartmentPicker ? (
              <>
                <li
                  id={`${listId}-0`}
                  role="option"
                  aria-selected={active === 0}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pickApartment(null);
                  }}
                  onMouseEnter={() => setActive(0)}
                  className={[
                    'cursor-pointer rounded-sm px-3 py-2 text-sm text-muted',
                    active === 0 ? 'bg-canvas font-semibold' : '',
                  ].join(' ')}
                >
                  Just the building — I’ll add the apartment myself
                </li>
                {apartmentPicker.apartmentOptions.map((tahis, i) => (
                  <li
                    key={tahis}
                    id={`${listId}-${i + 1}`}
                    role="option"
                    aria-selected={active === i + 1}
                    onMouseDown={(e) => {
                      e.preventDefault();
                      pickApartment(tahis);
                    }}
                    onMouseEnter={() => setActive(i + 1)}
                    className={[
                      'cursor-pointer rounded-sm px-3 py-2 text-sm',
                      active === i + 1 ? 'bg-canvas font-semibold' : '',
                    ].join(' ')}
                  >
                    Apartment {tahis}
                  </li>
                ))}
              </>
            ) : (
              items.map((a, i) => (
                <li
                  key={`${a.adsOid}-${a.line1}`}
                  id={`${listId}-${i}`}
                  role="option"
                  aria-selected={i === active}
                  // onMouseDown, not onClick: the input's blur fires first on a click
                  // and would close the list before the click lands.
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pick(a);
                  }}
                  onMouseEnter={() => setActive(i)}
                  className={[
                    'cursor-pointer rounded-sm px-3 py-2 text-sm',
                    i === active ? 'bg-canvas font-semibold' : '',
                  ].join(' ')}
                >
                  {a.label}
                  {a.apartment && <span className="ml-1 font-normal text-muted">· apt {a.apartment}</span>}
                  {!a.apartment && a.apartmentOptions.length > 0 && (
                    <span className="ml-1 font-normal text-muted">· has apartments</span>
                  )}
                </li>
              ))
            )}
          </ul>
        </div>
      )}

      {open && !apartmentPicker && searched && items.length === 0 && !unavailable && (
        <p className="hint" role="status">
          No matching address found. Please type it in below.
        </p>
      )}
    </div>
  );
}
