import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, withTag, pick } from './lib.js';

/**
 * Scenario 2 — search and type-ahead.
 *
 * Deliberately the harshest of the scenarios. Search is the one public
 * endpoint whose cost the CALLER controls: a trigram similarity query over a
 * GIN index is far more expensive than a keyset-paginated listing, and the
 * type-ahead fires on nearly every keystroke.
 *
 * Each "user" here types a term one character at a time, which is what a
 * browser actually does — so a single search costs the server several
 * suggest queries plus the search itself. Measuring one query per user would
 * understate the load by roughly the length of the search term.
 */
export const options = {
  stages: [
    { duration: '20s', target: 15 },
    { duration: '40s', target: 40 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(90)<2000'],
    // Search is rate limited per IP, and k6 presents as ONE client. 429s are
    // the rate limiter working correctly, not the server failing, so they are
    // excluded from the failure budget here — see PERFORMANCE.md.
    'http_req_failed{endpoint:search}': ['rate<0.05'],
  },
};

const TERMS = ['lap', 'phone', 'book', 'kettle', 'chair', 'lamp', 'coat', 'aurora'];

export default function () {
  const term = pick(TERMS);

  // Type-ahead: one request per keystroke past the second character, which is
  // where QuickSearch starts asking.
  for (let i = 2; i <= term.length; i++) {
    const res = http.get(
      `${BASE_URL}/api/products/suggest?q=${encodeURIComponent(term.slice(0, i))}`,
      withTag('suggest'),
    );
    check(res, { 'suggest not a server error': (r) => r.status < 500 });
    sleep(0.15); // realistic inter-keystroke delay
  }

  const search = http.get(
    `${BASE_URL}/api/products?q=${encodeURIComponent(term)}&limit=20`,
    withTag('search'),
  );
  check(search, { 'search not a server error': (r) => r.status < 500 });

  sleep(2 + Math.random() * 2);

  // Faceted refinement, the expensive follow-up a real user does next.
  const facets = http.get(`${BASE_URL}/api/products/facets`, withTag('facets'));
  check(facets, { 'facets not a server error': (r) => r.status < 500 });
  sleep(1);
}
