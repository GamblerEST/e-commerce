// Shared helpers for the k6 scenarios.
//
// BASE_URL points at the backend directly rather than through Caddy by
// default. That is deliberate for the headline numbers: the question these
// tests answer is "how does the APPLICATION behave under load", and putting a
// TLS-terminating reverse proxy in front adds handshake cost that varies with
// the client's CPU and tells you about the proxy instead. Run with
// BASE_URL=https://localhost:8443 to measure the full production path.
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const params = {
  // A self-signed certificate is expected when pointed at Caddy locally.
  insecureSkipTLSVerify: true,
  // Without a tag, k6 reports one aggregate for every URL, so a slow endpoint
  // hides inside the average of a fast one.
  tags: {},
};

export function withTag(name) {
  return { ...params, tags: { endpoint: name } };
}

/** A stable pool of real slugs, so the tests exercise rows that exist. */
export function loadSlugs(http, count = 20) {
  const res = http.get(`${BASE_URL}/api/products?limit=${count}`, withTag('warmup'));
  try {
    return JSON.parse(res.body).items.map((p) => p.slug);
  } catch {
    return [];
  }
}

export function loadCategories(http) {
  const res = http.get(`${BASE_URL}/api/categories`, withTag('warmup'));
  try {
    return JSON.parse(res.body).map((c) => c.id);
  } catch {
    return [];
  }
}

export function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}
