/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    // Otherwise a mock's call history leaks from one test into the next.
    clearMocks: true,
    // e2e/ holds Playwright specs (test()/expect() from @playwright/test,
    // run via `npm run test:e2e`) — Vitest's own default glob would
    // otherwise also pick them up and fail trying to run them as unit
    // tests, since the two frameworks' test() globals aren't compatible.
    exclude: ['**/node_modules/**', '**/e2e/**'],
  },
  server: {
    // Proxy API/image calls to the backend so the browser sees everything as
    // same-origin (localhost:5173). Without this, the refresh_token cookie
    // (HttpOnly, Secure, SameSite=Strict — DECISIONS.md §11/§6) silently
    // never gets attached to a cross-origin fetch to localhost:8080: modern
    // Chrome's default third-party-cookie restrictions treat different
    // ports as different parties even on "localhost", regardless of
    // SameSite=Strict's own same-SITE (not same-origin) rule. This mirrors
    // how the real deployment actually works too — Caddy's production
    // Caddyfile fronts both the frontend and `/api/*` on ONE origin for
    // exactly this reason (see deploy/Caddyfile's `handle /api/*` block).
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/product-images': { target: 'http://localhost:8080', changeOrigin: true },
      // Backend-generated (SitemapController) because they list live catalogue
      // URLs. Without these two lines they fall through to the SPA and return
      // index.html with a 200 — which looks fine in a browser and is useless
      // to a crawler.
      '/robots.txt': { target: 'http://localhost:8080', changeOrigin: true },
      '/sitemap.xml': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
