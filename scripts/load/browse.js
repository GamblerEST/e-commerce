import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, withTag, loadSlugs, loadCategories, pick } from './lib.js';

/**
 * Scenario 1 — browsing the catalogue.
 *
 * The most common thing a visitor does and the most cache-friendly, so it sets
 * the baseline every other scenario is compared against: home page, a category
 * listing, a couple of pages of results, then a product detail page.
 *
 * The sleeps are not padding. A load test without think time measures how fast
 * the server can be hammered by a script, not how it behaves with N real
 * people reading pages — which is what "50 concurrent users" is supposed to
 * mean.
 */
export const options = {
  stages: [
    { duration: '20s', target: 20 },
    { duration: '40s', target: 50 },  // the concurrency target
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    // The performance objectives, asserted rather than eyeballed.
    'http_req_duration{expected_response:true}': ['p(90)<2000'],
    http_req_failed: ['rate<0.05'],
    checks: ['rate>0.98'],
  },
};

export function setup() {
  return { slugs: loadSlugs(http), categories: loadCategories(http) };
}

export default function (data) {
  const home = http.get(`${BASE_URL}/api/home`, withTag('home'));
  check(home, { 'home 200': (r) => r.status === 200 });
  sleep(1 + Math.random());

  if (data.categories.length) {
    const listing = http.get(
      `${BASE_URL}/api/products?categoryId=${pick(data.categories)}&limit=20`,
      withTag('listing'),
    );
    check(listing, { 'listing 200': (r) => r.status === 200 });
    sleep(1 + Math.random() * 2);
  }

  const sorted = http.get(
    `${BASE_URL}/api/products?sort=PRICE_ASC&limit=20`,
    withTag('listing-sorted'),
  );
  check(sorted, { 'sorted listing 200': (r) => r.status === 200 });
  sleep(1);

  if (data.slugs.length) {
    const slug = pick(data.slugs);
    const detail = http.get(`${BASE_URL}/api/products/${slug}`, withTag('product-detail'));
    check(detail, { 'detail 200': (r) => r.status === 200 });

    // A real visitor on a product page also loads its reviews and the related
    // strip; measuring the detail call alone would understate the page.
    const related = http.get(`${BASE_URL}/api/products/${slug}/related`, withTag('related'));
    check(related, { 'related 200': (r) => r.status === 200 });
    sleep(2 + Math.random() * 2);
  }
}
