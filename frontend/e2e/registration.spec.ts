import { test, expect, type Page } from '@playwright/test';

// Critical user flow #1: registration. A fresh visitor creates an account
// and lands signed in — no separate "verify your email before you can do
// anything" gate (email verification exists but doesn't block login, see
// AccountController).
//
// RegisterPage only renders the reCAPTCHA widget when VITE_RECAPTCHA_SITE_KEY
// is actually set (a fresh clone's .env.example default is blank, so a
// CI environment run against the documented setup has no widget at
// all). A REAL reCAPTCHA checkbox can't be completed by an automated
// browser by design — if this environment has one configured, these tests
// skip with a clear reason rather than failing opaquely.
async function skipIfRecaptchaConfigured(page: Page) {
  await page.goto('/register');
  const hasRecaptcha = await page.locator('iframe[title*="recaptcha" i]').first().isVisible().catch(() => false);
  test.skip(hasRecaptcha,
      'A real reCAPTCHA site key is configured in this environment (frontend/.env) — a real checkbox challenge ' +
      "can't be completed by an automated browser. Unset VITE_RECAPTCHA_SITE_KEY (or use Google's published " +
      'test key pair) to run this test, or run it against a fresh clone using .env.example\'s blank default.');
}

test('a new visitor can register and is signed in afterwards', async ({ page }) => {
  await skipIfRecaptchaConfigured(page);
  const email = `e2e-register-${Date.now()}@test.ee`;

  await page.getByLabel('Display name').fill('E2E Test User');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('correct-horse-battery-staple');
  await page.getByRole('button', { name: 'Register' }).click();

  // A successful register redirects to "/" and the navbar reflects the
  // logged-in user — the concrete, observable proof, not just "no error shown".
  await expect(page).toHaveURL('/');
  await expect(page.getByText('E2E Test User')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Log out' })).toBeVisible();
});

test('registering with an already-used email shows a field error, not a crash', async ({ page }) => {
  await skipIfRecaptchaConfigured(page);
  const email = `e2e-duplicate-${Date.now()}@test.ee`;

  async function register() {
    await page.goto('/register');
    await page.getByLabel('Display name').fill('E2E Test User');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill('correct-horse-battery-staple');
    await page.getByRole('button', { name: 'Register' }).click();
  }

  await register();
  await expect(page).toHaveURL('/');

  // Log back out, then attempt to register the SAME email again.
  await page.getByRole('button', { name: 'Log out' }).click();
  await register();

  await expect(page.locator('.field-error, .form-error')).toBeVisible();
  await expect(page).toHaveURL('/register'); // did not proceed as if it had succeeded
});
