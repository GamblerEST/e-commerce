import { chromium } from '@playwright/test';

/**
 * Checks the platform at the four viewport widths the requirements name.
 *
 * The specific failure being hunted is horizontal overflow: a page wider than
 * the viewport forces sideways scrolling, which on a 320px phone makes content
 * unreachable rather than merely ugly. It is also invisible on a desktop
 * browser, which is exactly why it needs asserting rather than eyeballing.
 *
 * Also checks text at 200% zoom (WCAG 1.4.4), simulated by halving the
 * viewport width at the same CSS pixel count — which is what a 200% zoom
 * actually does to a layout.
 */
const WIDTHS = [320, 768, 1024, 1440];
const PAGES = ['/', '/products', '/about', '/contacts', '/cart', '/login'];

const browser = await chromium.launch();
let failures = 0;

for (const width of WIDTHS) {
  const context = await browser.newContext({ viewport: { width, height: 900 } });
  const page = await context.newPage();
  const bad = [];

  for (const path of PAGES) {
    await page.goto('http://localhost:5173' + path, { waitUntil: 'load' });
    await page.waitForTimeout(900);
    const overflow = await page.evaluate(() => {
      const d = document.documentElement;
      return { scroll: d.scrollWidth, client: d.clientWidth };
    });
    if (overflow.scroll > overflow.client + 1) {
      bad.push(`${path} (${overflow.scroll}px content in ${overflow.client}px viewport)`);
    }
  }

  failures += bad.length;
  console.log(`${bad.length === 0 ? 'PASS' : 'FAIL'}  ${width}px — ${bad.length} page(s) overflow horizontally`);
  bad.forEach((b) => console.log(`        ${b}`));
  await context.close();
}

// 200% zoom on a 1280px screen behaves like a 640px viewport.
const zoomCtx = await browser.newContext({ viewport: { width: 640, height: 720 }, deviceScaleFactor: 2 });
const zoomPage = await zoomCtx.newPage();
let zoomBad = 0;
for (const path of PAGES) {
  await zoomPage.goto('http://localhost:5173' + path, { waitUntil: 'load' });
  await zoomPage.waitForTimeout(900);
  const o = await zoomPage.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    client: document.documentElement.clientWidth,
  }));
  if (o.scroll > o.client + 1) { zoomBad++; console.log(`        200% zoom overflow on ${path}`); }
}
failures += zoomBad;
console.log(`${zoomBad === 0 ? 'PASS' : 'FAIL'}  200% zoom (WCAG 1.4.4) — ${zoomBad} page(s) overflow`);

console.log(`\n=== TOTAL RESPONSIVE FAILURES: ${failures} ===`);
await browser.close();

// Same reasoning as a11y.mjs: PASS/FAIL was already printed per width, but
// without a real exit code the run "succeeds" either way — this is what
// makes an overflow actually block something instead of needing someone to
// read the log.
if (failures > 0) {
  process.exitCode = 1;
}
