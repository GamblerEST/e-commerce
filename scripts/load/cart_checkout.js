import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, withTag, loadSlugs, pick } from './lib.js';

/**
 * Scenario 3 — cart and the run-up to checkout.
 *
 * The only WRITE-heavy scenario, and therefore the one most likely to expose a
 * bottleneck the read paths hide: guest carts live in Redis, and every add
 * re-prices from the live product row.
 *
 * It stops SHORT of creating an order. That is a deliberate limit, not an
 * oversight: completing checkout reserves inventory and creates a Stripe
 * PaymentIntent, so a sustained load test would drain real stock and hammer
 * Stripe's sandbox with thousands of intents. The expensive, contended part of
 * the path — cart writes and stock reads — is covered here; order creation
 * under concurrency is covered instead by the oversell test in the backend
 * suite, which fires 20 threads at one product and asserts exactly one wins.
 */
export const options = {
  stages: [
    { duration: '20s', target: 10 },
    { duration: '40s', target: 30 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    'http_req_duration{expected_response:true}': ['p(90)<2000'],
    'http_req_failed{endpoint:cart-add}': ['rate<0.05'],
  },
};

export function setup() {
  const slugs = loadSlugs(http, 30);
  // Cart operations need product IDs, not slugs.
  const ids = [];
  for (const slug of slugs.slice(0, 10)) {
    const res = http.get(`${BASE_URL}/api/products/${slug}`, withTag('warmup'));
    try {
      const p = JSON.parse(res.body);
      if (p.stockQuantity > 0) ids.push(p.id);
    } catch { /* skip */ }
  }
  return { ids };
}

export default function (data) {
  if (!data.ids.length) return;

  // Each VU is its own guest, so carts do not collide — a shared cookie jar
  // would serialise every user onto one Redis key and measure contention that
  // does not exist in production.
  const jar = http.cookieJar();
  jar.clear(BASE_URL);

  const view = http.get(`${BASE_URL}/api/cart`, withTag('cart-view'));
  check(view, { 'cart readable': (r) => r.status === 200 });
  sleep(1);

  // Cart mutations are CSRF-protected (DECISIONS.md §11): the server sets an
  // XSRF-TOKEN cookie and requires it echoed back in the X-XSRF-TOKEN header.
  // Without this the POST is rejected before it ever reaches the cart code,
  // and the scenario measures the security filter rather than the cart.
  const csrf = (jar.cookiesForURL(`${BASE_URL}/api/cart`)['XSRF-TOKEN'] || [])[0];

  const add = http.post(
    `${BASE_URL}/api/cart/items`,
    JSON.stringify({ productId: pick(data.ids), quantity: 1 }),
    {
      ...withTag('cart-add'),
      headers: {
        'Content-Type': 'application/json',
        ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}),
      },
    },
  );
  check(add, { 'add to cart not a server error': (r) => r.status < 500 });
  sleep(1 + Math.random());

  const reread = http.get(`${BASE_URL}/api/cart`, withTag('cart-view'));
  check(reread, { 'cart still readable': (r) => r.status === 200 });
  sleep(2);

  // What the checkout page loads before the customer types anything.
  const delivery = http.get(`${BASE_URL}/api/delivery-options`, withTag('delivery-options'));
  check(delivery, { 'delivery options 200': (r) => r.status === 200 });
  sleep(1);
}
