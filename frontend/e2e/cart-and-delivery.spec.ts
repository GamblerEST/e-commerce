import { test, expect } from '@playwright/test';

/**
 * Critical user flow: cart to the point of payment.
 *
 * Covers the quick cart preview (required on every page), the cart itself, and
 * delivery-method selection at checkout — the last of which existed only in
 * the backend until an audit caught that no customer could ever choose one.
 *
 * These stop before actually paying. Payment is covered by checkout.spec.ts
 * against Stripe's test cards; repeating it here would create real
 * PaymentIntents for assertions that are about the cart, not the card.
 */

async function addFirstInStockProductToCart(page: import('@playwright/test').Page) {
  await page.goto('/products');
  // Out-of-stock products have a disabled add button, so picking blindly makes
  // this test flaky depending on seed data and previous runs.
  const cards = page.locator('.product-grid-new > li, .product-list-new > li');
  const count = await cards.count();
  for (let i = 0; i < count; i++) {
    await cards.nth(i).locator('a').first().click();
    const addButton = page.locator('.add-to-cart button');
    if (await addButton.isEnabled()) {
      const name = (await page.getByRole('heading', { level: 1 }).textContent())!.trim();
      await addButton.click();
      return name;
    }
    await page.goBack();
  }
  throw new Error('No in-stock product found to add to the cart');
}

test('the quick cart preview reflects what was added', async ({ page }) => {
  const name = await addFirstInStockProductToCart(page);

  // The preview is a disclosure button, reachable on every page — hover menus
  // are unusable on touch and unreachable by keyboard.
  const cartButton = page.getByRole('button', { name: /^Cart,/ });
  await expect(cartButton).toBeVisible();
  await cartButton.click();

  const dialog = page.getByRole('dialog', { name: /cart preview/i });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(name);
  // Shipping is deliberately excluded from the cart total; it is not chosen yet.
  await expect(dialog).toContainText(/shipping calculated at checkout/i);

  // Escape must close it AND return focus to the trigger, or a keyboard user
  // is dumped at the top of the document with no idea where they are.
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(cartButton).toBeFocused();
});

test('the cart page lists the item and links to checkout', async ({ page }) => {
  const name = await addFirstInStockProductToCart(page);

  await page.goto('/cart');
  await expect(page.getByRole('heading', { level: 1, name: /your cart/i })).toBeVisible();
  await expect(page.locator('.cart-items')).toContainText(name);

  await expect(page.getByRole('link', { name: /checkout/i }).first()).toBeVisible();
});

test('checkout offers delivery methods and adds the cost to the total', async ({ page }) => {
  await addFirstInStockProductToCart(page);
  await page.goto('/checkout');

  const deliveryGroup = page.getByRole('group', { name: /delivery method/i });
  await expect(deliveryGroup).toBeVisible();

  const options = deliveryGroup.getByRole('radio');
  expect(await options.count()).toBeGreaterThan(1);

  // One must be preselected. An unselected radio group means an order can be
  // placed with no delivery method, no shipping cost and no estimated date —
  // which is exactly the bug this flow was added to prevent.
  await expect(options.first()).toBeChecked();

  const totalOf = async () => {
    const text = (await page.locator('.order-total-grand').textContent())!;
    return Number(text.replace(/[^\d,]/g, '').replace(',', '.'));
  };

  const cheapTotal = await totalOf();

  // Switching to a dearer method must move the total, or the summary is lying.
  await options.nth(1).check();
  await expect(page.locator('.order-total-grand')).not.toContainText(
    cheapTotal.toFixed(2).replace('.', ','),
  );
  expect(await totalOf()).toBeGreaterThan(cheapTotal);

  // Each option states when it would arrive, as a range rather than a single
  // date the shop cannot promise.
  await expect(deliveryGroup).toContainText(/Estimated/);
});

test('an empty cart tells you so rather than showing a broken checkout', async ({ page }) => {
  await page.context().clearCookies();
  await page.goto('/checkout');
  await expect(page.getByText(/your cart is empty/i)).toBeVisible();
  await expect(page.getByRole('link', { name: /browse products/i })).toBeVisible();
});
