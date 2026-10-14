import { chromium } from '@playwright/test';
const PAGES = ['/', '/products', '/about', '/contacts', '/login', '/register', '/cart', '/checkout', '/orders', '/account'];
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const page = await ctx.newPage();
const res = await page.request.get('http://localhost:5173/api/products?limit=1');
try { PAGES.push('/products/' + (await res.json()).items[0].slug); } catch {}

console.log('path | title len | desc len | h1 count | imgs missing alt | canonical');
for (const p of PAGES) {
  await page.goto('http://localhost:5173' + p, { waitUntil: 'load' });
  await page.waitForTimeout(1000);
  const d = await page.evaluate(() => {
    const desc = document.querySelector('meta[name="description"]')?.content ?? '';
    const canon = document.querySelector('link[rel="canonical"]')?.href ?? '-';
    const imgs = [...document.querySelectorAll('img')];
    const missing = imgs.filter(i => !i.hasAttribute('alt')).length;
    return { title: document.title, desc, h1: document.querySelectorAll('h1').length, imgs: imgs.length, missing, canon };
  });
  const flag = (d.title.length > 60 ? ' TITLE>60' : '') + (d.h1 !== 1 ? ` H1=${d.h1}` : '') + (d.missing ? ` NOALT=${d.missing}` : '');
  console.log(`${p} | ${d.title.length} | ${d.desc.length} | ${d.h1} | ${d.missing}/${d.imgs} | ${d.canon === '-' ? 'none' : 'yes'}${flag}`);
}
await browser.close();
