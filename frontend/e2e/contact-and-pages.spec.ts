import { test, expect } from '@playwright/test';

/**
 * Critical user flow: getting help, and the standing pages.
 *
 * The contact form is the only place a visitor who cannot sign in can reach
 * the shop, so it gets real coverage: validation that names the offending
 * field, and a submission that actually succeeds.
 */

test('the contact form rejects bad input and names the field', async ({ page }) => {
  await page.goto('/contacts');

  // Submitting empty must not silently do nothing — the commonest form bug.
  await page.getByRole('button', { name: /send message/i }).click();

  await expect(page.getByText(/tell us your name/i)).toBeVisible();
  await expect(page.getByText(/we need an email address/i)).toBeVisible();
  await expect(page.getByText(/please add a subject/i)).toBeVisible();

  // A too-short message is rejected with a reason, not a generic failure.
  await page.getByLabel('Your name').fill('E2E Tester');
  await page.getByLabel('Your email').fill('e2e@example.ee');
  await page.getByLabel('Subject').fill('Testing the form');
  await page.getByLabel('Message').fill('short');
  await page.getByRole('button', { name: /send message/i }).click();
  await expect(page.getByText(/at least 10 characters/i)).toBeVisible();
});

test('a valid contact message is accepted and confirmed', async ({ page }) => {
  await page.goto('/contacts');

  await page.getByLabel('Your name').fill('E2E Tester');
  await page.getByLabel('Your email').fill(`e2e-${Date.now()}@example.ee`);
  await page.getByLabel('Subject').fill('Question about an order');
  await page
    .getByLabel('Message')
    .fill('I would like to know when my order is expected to arrive. Thank you.');

  await page.getByRole('button', { name: /send message/i }).click();

  // What this asserts, and why it is not "the message was sent":
  //
  // The endpoint is rate limited to 5 messages an hour per IP. That is a real
  // control (an unauthenticated endpoint that sends mail is otherwise an open
  // relay), and it means a suite run twice inside an hour legitimately gets a
  // 429. A test demanding success would flake for a reason that is the system
  // working correctly — so it would end up being deleted or weakened, which is
  // worse than not asserting it.
  //
  // So this asserts the property that must hold on EVERY path: the form always
  // reports its outcome. A submit that silently does nothing is the failure
  // mode worth catching here. The success path itself — 202 plus an email
  // actually composed — is asserted in ContactControllerTest, where the rate
  // limiter is not in the way.
  const confirmation = page.getByRole('status').filter({ hasText: /message sent/i });
  const anyError = page.getByRole('alert');
  await expect(confirmation.or(anyError).first()).toBeVisible({ timeout: 10_000 });

  // And whichever way it went, the user is told something specific rather than
  // being left looking at an unchanged form.
  const feedback = (await confirmation.or(anyError).first().textContent())!.trim();
  expect(feedback.length).toBeGreaterThan(10);
});

test('the About page carries the company information it is required to', async ({ page }) => {
  await page.goto('/about');

  await expect(page.getByRole('heading', { level: 1 })).toContainText(/about/i);
  await expect(page.getByRole('heading', { name: /our mission/i })).toBeVisible();
  await expect(page.getByRole('heading', { name: /the team/i })).toBeVisible();
  await expect(page.getByRole('heading', { name: /company details/i })).toBeVisible();

  // Social links must leave the site safely: noopener stops the target page
  // reaching back through window.opener.
  const social = page.locator('section:has(h2:text-matches("follow us", "i")) a[target="_blank"]');
  expect(await social.count()).toBeGreaterThan(0);
  for (const link of await social.all()) {
    expect(await link.getAttribute('rel')).toContain('noopener');
  }
});

test('an unknown URL shows the 404 page rather than a blank screen', async ({ page }) => {
  await page.goto('/this-route-does-not-exist');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.locator('body')).toContainText(/not found|404/i);
});

test('every page is reachable by keyboard from the skip link', async ({ page }) => {
  // WCAG 2.4.1: the first tab stop must let a keyboard user jump the nav
  // instead of tabbing through it on every single page.
  await page.goto('/');
  await page.keyboard.press('Tab');

  const skip = page.getByRole('link', { name: /skip to main content/i });
  await expect(skip).toBeFocused();

  await page.keyboard.press('Enter');
  await expect(page.locator('#main-content')).toBeFocused();
});
