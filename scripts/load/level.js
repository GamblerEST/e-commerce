import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, withTag, pick } from './lib.js';

/**
 * A single constant-concurrency measurement, used to build the
 * concurrency-vs-latency table in PERFORMANCE.md.
 *
 * The ramping stress run answers "does it survive"; it cannot cleanly answer
 * "what is the latency AT 500 users", because every stage's numbers are
 * smeared into one summary. Holding concurrency flat for a fixed window gives
 * one honest data point per level.
 *
 *   VUS=500 k6 run level.js
 */
export const options = {
  scenarios: {
    level: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 100),
      duration: __ENV.DURATION || '30s',
    },
  },
};

const TERMS = ['lap', 'phone', 'book', 'kettle', 'chair', 'aurora'];

export default function () {
  const home = http.get(`${BASE_URL}/api/home`, withTag('home'));
  check(home, { 'home ok': (r) => r.status === 200 });

  const term = pick(TERMS);
  const search = http.get(
    `${BASE_URL}/api/products?q=${encodeURIComponent(term)}&limit=20`,
    withTag('search'),
  );
  check(search, { 'search ok': (r) => r.status === 200 });

  const suggest = http.get(
    `${BASE_URL}/api/products/suggest?q=${encodeURIComponent(term)}`,
    withTag('suggest'),
  );
  check(suggest, { 'suggest ok': (r) => r.status === 200 });
}
