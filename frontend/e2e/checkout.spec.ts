import { test, expect, type Page } from '@playwright/test';

// Critical user flow #2: checkout. Guest adds a product to their cart,
// checks out, and sees a confirmation — the payment step is EXERCISED when
// Stripe is configured on the backend (VITE_STRIPE_PUBLISHABLE_KEY +
// deploy/secrets/stripe_secret_key both set) and skipped gracefully when
// it isn't, matching CheckoutPage's own conditional rendering. Either way
// this proves the checkout flow end-to-end, not just the payment step.
const PRODUCT_SLUG = 'cast-iron-skillet';
const TEST_CARD = '4242424242424242';

async function fillCheckoutForm(page: Page) {
  await page.goto(`/products/${PRODUCT_SLUG}`);
  await expect(page.locator('.product-detail h1')).toBeVisible();
  await page.locator('.add-to-cart button').click();
  await expect(page.locator('.add-to-cart button')).toHaveText(/Added/);

  // Navigate by URL rather than through the header. The cart used to be a
  // plain `a.nav-cart` link; it is now a disclosure button that opens the quick
  // cart preview, so a selector tied to the old markup broke this flow the
  // moment the navbar was rewritten. The cart preview has its own coverage in
  // cart-and-delivery.spec.ts — this test is about checkout, so it should not
  // also be a test of how you get there.
  await page.goto('/cart');
  await expect(page.locator('.cart-item').first()).toBeVisible();
  await page.locator('a.checkout-cta').click();
  await expect(page.locator('.checkout-form')).toBeVisible();

  const inputs = page.locator('.checkout-form input');
  await inputs.nth(0).fill('E2E Checkout Tester');
  await inputs.nth(1).fill('e2e-checkout@test.ee');
  await inputs.nth(2).fill('+372 5555 5555');
  await inputs.nth(3).fill('Vabaduse pst 10');
  await inputs.nth(4).fill('Korter 5');
  await inputs.nth(5).fill('Tallinn');
  await inputs.nth(6).fill('10101');
  await inputs.nth(7).fill('EE');

  await page.locator('.checkout-form button[type="submit"]').click();
}

/**
 * Stripe's Payment Element renders as a tab list (Card / MB WAY / Satispay
 * in test mode) inside an iframe, with "Card" not expanded by default and
 * the tab list + card input fields sharing the SAME "accessory-target"
 * iframe. Link (Stripe's own save-card feature) is disabled server-side
 * (CheckoutPage's PaymentElement options), so no extra signup prompt
 * appears here.
 */
async function payWithTestCard(page: Page) {
  const fieldFrame = page.frameLocator('iframe[src*="elements-inner-accessory-target"]');
  await fieldFrame.getByText('Card', { exact: true }).click();
  await fieldFrame.locator('[placeholder="1234 1234 1234 1234"]').waitFor();

  await fieldFrame.locator('[placeholder="1234 1234 1234 1234"]').fill(TEST_CARD);
  await fieldFrame.locator('[placeholder="MM / YY"]').fill('12/34');
  await fieldFrame.locator('[placeholder="CVC"]').fill('123');

  // Debounced internal re-layout after the last field loses focus — same
  // stabilization this flow needed when first verified interactively.
  await page.waitForTimeout(800);
  await page.locator('.checkout-form button[type="submit"]').click();
}

test('guest can add a product to cart and complete checkout', async ({ page }) => {
  await fillCheckoutForm(page);

  // Payment step only renders when Stripe is configured on the backend
  // (see CheckoutPage: `order.paymentClientSecret` present).
  const paymentStep = page.getByRole('heading', { name: 'Payment' });
  const orderPlaced = page.getByRole('heading', { name: 'Order placed' });

  await expect(paymentStep.or(orderPlaced)).toBeVisible({ timeout: 15_000 });

  if (await paymentStep.isVisible()) {
    await payWithTestCard(page);
  }

  await expect(orderPlaced).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('.order-total-grand')).toBeVisible();
});

test('checkout with an empty cart shows "Browse products", not a broken form', async ({ page }) => {
  await page.goto('/checkout');
  await expect(page.getByText('Your cart is empty.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Browse products' })).toBeVisible();
});
