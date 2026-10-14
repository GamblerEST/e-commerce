import { defineConfig, devices } from '@playwright/test';

// Critical-user-flow E2E tests: registration and the checkout process.
// Requires the backend stack running
// separately (docker compose up — see README "Local demo") since these hit
// a real Postgres/Redis-backed API, not a mock. The Vite dev server itself
// is started automatically below.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false, // tests share one backend + database; avoid cross-test interference
  retries: process.env.CI ? 1 : 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
