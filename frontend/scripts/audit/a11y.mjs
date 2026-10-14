import { chromium } from '@playwright/test';
import { AxeBuilder } from '@axe-core/playwright';

const PAGES = [
  ['/', 'Home'],
  ['/products', 'Product listing'],
  ['/about', 'About'],
  ['/contacts', 'Contact'],
  ['/login', 'Login'],
  ['/register', 'Register'],
  ['/cart', 'Cart'],
  ['/nonexistent-page', '404'],
];

const browser = await chromium.launch();
// AxeBuilder requires a page created from an explicit context.
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await context.newPage();
let total = 0;

// A product detail page, resolved from live data so the slug is real.
// The dev server has to be up first: an unreachable server would otherwise
// die here with a raw stack trace instead of a message saying what to start.
try {
  const res = await page.request.get('http://localhost:5173/api/products?limit=1');
  const slug = (await res.json()).items[0].slug;
  PAGES.push([`/products/${slug}`, 'Product detail']);
} catch (e) {
  console.warn(`Could not reach http://localhost:5173 — start the frontend dev server (npm run dev) first. ${e.message.split('\n')[0]}`);
  await browser.close();
  process.exit(1);
}

for (const [path, name] of PAGES) {
  await page.goto('http://localhost:5173' + path, { waitUntil: 'load' });
  await page.waitForTimeout(1200);

  const results = await new AxeBuilder({ page })
    // WCAG 2.1 Level A is the conformance target.
    .withTags(['wcag2a', 'wcag21a'])
    .analyze();

  // heading-order is tagged best-practice, not wcag2a/wcag21a, so the scan
  // above never runs it — axe's own tag filter (runOnly) accepts one
  // selection, tag or rule, not both at once. A page can pass every WCAG 2.1
  // A check and still nest headings wrong (an h3 with no h2 above it), which
  // is exactly the kind of thing that looks fine until someone checks.
  const headingOrder = await new AxeBuilder({ page })
    .withRules(['heading-order'])
    .analyze();

  const violations = [...results.violations, ...headingOrder.violations];
  total += violations.length;
  console.log(`\n${violations.length === 0 ? 'PASS' : 'FAIL'}  ${name} (${path}) — ${violations.length} violation(s)`);
  for (const v of violations) {
    console.log(`   [${v.impact}] ${v.id}: ${v.help}`);
    for (const n of v.nodes.slice(0, 3)) {
      console.log(`      ${n.target.join(' ')}`);
      if (n.failureSummary) console.log(`      ${n.failureSummary.split('\n').filter(Boolean).slice(1,3).join(' | ')}`);
    }
  }
}

console.log(`\n=== TOTAL WCAG 2.1 A VIOLATIONS: ${total} ===`);
await browser.close();

// Every check above already printed PASS/FAIL per page — this is what turns
// that into something a CI step (or a human running the npm script) can
// actually rely on. Without it the process always exits 0, so a violation
// only exists if someone happens to read the log.
if (total > 0) {
  process.exitCode = 1;
}
