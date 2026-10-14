import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, withTag, pick } from './lib.js';

/**
 * Scenario 4 — stress to saturation.
 *
 * The other three scenarios answer "does the shop meet its targets under a
 * realistic load". This one answers the capacity questions those
 * cannot: how many concurrent users before responses exceed five seconds, what
 * throughput the application tops out at, and where it breaks first.
 *
 * Two deliberate differences from the realistic scenarios:
 *
 *  - No think time. This is not a simulation of users any more; it is a search
 *    for the ceiling, and sleeps would just mean needing proportionally more
 *    VUs to reach it.
 *  - It hits the EXPENSIVE paths. Product search (trigram similarity over a GIN
 *    index) and the home page (two LATERAL joins) cost far more per request
 *    than a cached listing, so they saturate first and reveal the real limit.
 *
 * Run with the load-test overlay and a flushed Redis, or the rate limiter caps
 * the run long before the application does — see docker-compose.loadtest.yml.
 */
export const options = {
  stages: [
    { duration: '30s', target: 100 },
    { duration: '30s', target: 250 },
    { duration: '30s', target: 500 },
    { duration: '30s', target: 1000 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    // Recorded, not enforced: this run is EXPECTED to degrade. Failing the
    // run on threshold breach would stop it exactly where it gets interesting.
    'http_req_duration{expected_response:true}': ['p(95)<5000'],
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
