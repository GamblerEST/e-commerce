import { test, expect } from '@playwright/test';

/**
 * Critical user flow: finding a product.
 *
 * This is the journey most visitors actually take — land, search or browse,
 * narrow it down, open something. It exercises the home page, the site-wide
 * quick search, the listing's filters, the grid/list toggle and the product
 * page, against a real backend and a real database.
 *
 * These assert BEHAVIOUR a customer would notice, not implementation details:
 * "the result count matches what is listed", not "this component has this
 * class".
 */

test('the home page shows featured products and collections', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

  const featured = page.getByRole('region', { name: /featured/i })
    .or(page.locator('section:has(h2:text-is("Featured right now"))'));
  await expect(featured.first()).toBeVisible();

  // Collections must link somewhere real — an empty collection tile promises a
  // range and delivers a "no products" page.
  const collection = page.locator('a[href*="categoryId="]').first();
  await expect(collection).toBeVisible();
  await collection.click();
  await expect(page).toHaveURL(/categoryId=/);
  await expect(page.locator('.product-grid-new li, .product-list-new li').first()).toBeVisible();
});

test('quick search suggests products and navigates to one', async ({ page }) => {
  await page.goto('/');

  // The suggestion list is a combobox: typing must announce options, and
  // picking one must go straight to that product.
  const search = page.getByRole('combobox', { name: /search products/i });
  await search.fill('aur');

  const option = page.getByRole('option').first();
  await expect(option).toBeVisible({ timeout: 10_000 });
  const suggested = (await option.textContent())?.trim() ?? '';

  await option.click();
  await expect(page).toHaveURL(/\/products\//);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(suggested);
});

test('the listing reports a result count that matches what it shows', async ({ page }) => {
  await page.goto('/products');

  const count = page.locator('.result-count');
  await expect(count).toBeVisible();
  await expect(count).toContainText(/\d+ products?/);

  const total = Number((await count.textContent())!.match(/(\d+)/)![1]);
  expect(total).toBeGreaterThan(0);

  // The count describes the whole filtered set, so one page shows at most a
  // page's worth of it — but never more than the total it claims.
  const shown = await page.locator('.product-grid-new > li, .product-list-new > li').count();
  expect(shown).toBeGreaterThan(0);
  expect(shown).toBeLessThanOrEqual(total);
});

test('the grid/list toggle switches layout and survives a reload', async ({ page }) => {
  await page.goto('/products');

  await expect(page.locator('.product-grid-new')).toBeVisible();

  await page.getByRole('button', { name: 'List' }).click();
  await expect(page.locator('.product-list-new')).toBeVisible();
  await expect(page.getByRole('button', { name: 'List' })).toHaveAttribute('aria-pressed', 'true');

  // A view preference the shopper set should still be there next visit.
  await page.reload();
  await expect(page.locator('.product-list-new')).toBeVisible();
});

test('filtering by category narrows the results', async ({ page }) => {
  await page.goto('/products');
  const before = Number((await page.locator('.result-count').textContent())!.match(/(\d+)/)![1]);

  const categoryFilter = page.getByLabel('Filter by category');
  const options = await categoryFilter.locator('option').all();
  // option[0] is "All categories".
  await categoryFilter.selectOption(await options[1].getAttribute('value') ?? '');

  await expect(page.locator('.result-count')).not.toContainText(`${before} products`, {
    timeout: 10_000,
  });
  const after = Number((await page.locator('.result-count').textContent())!.match(/(\d+)/)![1]);
  expect(after).toBeLessThan(before);
});

test('a product page shows its details, reviews and related products', async ({ page }) => {
  await page.goto('/products');
  await page.locator('.product-grid-new > li a, .product-list-new > li a').first().click();

  await expect(page).toHaveURL(/\/products\/[a-z0-9-]+$/);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

  // Every meaningful image needs alt text — asserted here rather than trusted,
  // because it is the kind of thing that silently regresses.
  for (const img of await page.locator('.product-images img').all()) {
    expect((await img.getAttribute('alt'))?.trim()).toBeTruthy();
  }

  await expect(page.getByRole('heading', { name: /^Reviews/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: /you might also like/i })).toBeVisible();
});

test('reviews can be sorted by helpfulness', async ({ page }) => {
  // Helpfulness ordering is an explicit requirement, and the seeded data
  // carries vote counts precisely so it is demonstrable.
  //
  // The product is chosen from live data rather than taking whatever happens
  // to be first. Picking blindly meant landing on a product with fewer than
  // two reviews, where no sort control renders — and the test then SKIPPED,
  // which reads as "passing" while asserting nothing about the requirement.
  const res = await page.request.get('/api/products?limit=100&sort=RATING_DESC');
  const withReviews = (await res.json()).items.filter(
    (p: { reviewCount: number }) => p.reviewCount >= 2,
  );
  expect(
    withReviews.length,
    'the seeded catalogue should contain products with several reviews',
  ).toBeGreaterThan(0);

  await page.goto(`/products/${withReviews[0].slug}`);

  const sort = page.getByLabel('Sort by');
  await expect(sort).toBeVisible();
  await sort.selectOption('HELPFUL');

  // Wait for the reordered list rather than a fixed sleep.
  await expect(page.locator('button:has-text("Helpful")').first()).toBeVisible();
  await page.waitForTimeout(600);

  const counts = await page.locator('button:has-text("Helpful")').allTextContents();
  const numbers = counts.map((c) => Number(c.match(/\((\d+)\)/)?.[1] ?? 0));
  expect(numbers.length).toBeGreaterThan(1);

  const sorted = [...numbers].sort((a, b) => b - a);
  expect(numbers, 'reviews should be ordered by helpful votes, descending').toEqual(sorted);
});
