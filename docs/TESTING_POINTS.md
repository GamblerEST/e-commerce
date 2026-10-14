# Testing points

Each listed point, with where it is implemented and how it is checked. "Automated" means a test in the backend or frontend suite.
"Script" means one of the audit scripts in `frontend/scripts/audit/`. "Manual"
means it is checked by hand.

## Mandatory

| Point | Where it is implemented | How it is checked |
|---|---|---|
| README contains the project overview, entity relationship diagram, performance report, setup instructions and usage guide | `README.md`, `docs/REFERENCE.md`, `PERFORMANCE.md` | Manual |
| Star rating is the average of all reviews | `V15__reviews.sql`, trigger `reviews_maintain_product_rating` | Automated: `ReviewControllerTest.productRating_isMaintainedByTheDatabase_asReviewsLand` |
| Users can submit text reviews for purchased products | `ReviewService.create()`, `ReviewRepository.hasPurchased()` | Automated: `ReviewControllerTest` |
| Reviews are sorted by helpfulness votes | `ReviewService.SortBy.HELPFUL` | Automated: `ReviewControllerTest.sortingByHelpful_putsTheMostVotedReviewFirst` |
| 2FA is enforced for all admin accounts | `@AdminOnly` and `@StaffOnly` require `principal.is2faEnabled()` | Automated: `AdminCatalogControllerTest`, `AdminOrderControllerTest`. An admin without 2FA can log in, which is audited, but cannot use admin actions |
| Product CRUD with all required fields | `/admin/products`, `AdminProductRequest` | Automated: `AdminCatalogControllerTest` |
| Admins can add, edit, delete, update and manage products, categories, orders and refunds | `/admin/products`, `/admin/categories`, `/admin/orders`. Refund is admin only | Automated: `AdminCatalogControllerTest`, `AdminOrderControllerTest` |
| Admins can manage delivery options and order status updates | `/admin/delivery`. Mark shipped and mark delivered in `/admin/orders` | Automated: `DeliveryOptionControllerTest`, `AdminOrderControllerTest` |
| Admins can view all users and assign roles | `/admin/users`, `PUT /api/admin/users/{id}/role` | Automated: `AdminUserControllerTest` |
| Bulk upload of products via JSON and CSV | `/admin/products`, `POST /api/admin/products/bulk` and `/bulk/csv` | Automated: `ProductBulkImportTest` |
| Home page showcases featured products and collections | `/`, `GET /api/home` | Automated: `HomeControllerTest` |
| Product listing shows details, ratings, filters, grid and list views, search and sorting | `/products`, `ProductSearchService` | Automated: `CatalogControllerTest`. Manual for grid and list |
| Product detail shows full information, images, reviews, a call to action, and related products | `/products/:slug`, `GET /api/products/{slug}/related` | Automated: `CatalogControllerTest`, `ReviewControllerTest`. Manual for layout |
| Cart lists items with thumbnails and prices, allows updates and removals, shows the total excluding shipping, and has a clear call to action | `/cart` | Automated: `CartControllerTest`. Manual for layout |
| Checkout shows the guest or signed-in order summary, address input, shipping and payment | `/checkout`, `CheckoutController` | Automated: `CheckoutControllerTest` |
| Order confirmation shows the order summary, estimated delivery and reference number | `/orders/:id`, `OrderDetailPage.tsx` | Manual |
| Search results include filtering, sorting, result count and pagination | `/products?q=...`, numbered pages from a keyset cursor | Automated: `CatalogControllerTest` |
| Admin page provides CRUD for products, orders, users, review moderation and bulk uploads | `/admin/*` | Automated: the admin controller tests |
| Contact form works | `/contacts`, `POST /api/contact` | Automated: `ContactControllerTest`. A failed send returns 503 |
| About page includes company information, mission, team and social links | `/about`, `frontend/src/companyInfo.ts` | Manual |
| 404 page with a catch-all message | Any unmatched route, `NotFoundPage.tsx` | Automated: `NotFoundHandlingTest` |
| Quick cart preview | `frontend/src/components/CartPreview.tsx` | Manual |
| Quick search with dynamic suggestions | `frontend/src/components/QuickSearch.tsx`, `GET /api/products/suggest` | Automated: `CatalogControllerTest`. Manual for the dropdown |
| Product images stored and served in multiple sizes | `product_images` (`thumbnail_key`, `medium_key`) | Automated: `ProductImageControllerTest` |
| Functionality across 320, 768, 1024 and 1440 px viewports | `frontend/scripts/audit/responsive.mjs` | Script: `npm run test:responsive` |
| Self-signed TLS certificate | `deploy/Caddyfile.local`, `tls internal` | Manual |
| Sensitive data encrypted at rest: credentials, personal data, shipping addresses, order details, session tokens | Passwords with Argon2id. Personal data, addresses and billing details with AES-256-GCM through `EncryptedStringConverter`. Refresh tokens stored as SHA-256 hashes | Automated: `EncryptionAtRestTest` |
| Token bucket rate limiting | `RateLimitService` (Bucket4j), `application.yml` `rate-limit` | Automated: `RateLimitServiceTest` |
| SEO: titles under 60 characters, heading hierarchy H2 to H6, logical URLs, descriptive alt text | `ProductDetailPage.tsx`, `SeoPageService`, `SitemapController` | Script: `npm run test:seo`. Heading order is also checked by `npm run test:a11y` |
| All meaningful images include descriptive alt text | `seo.mjs` reports any image without alt text | Script: `npm run test:seo` |
| Text readable when zoomed to 200% | `frontend/scripts/audit/responsive.mjs` | Script: `npm run test:responsive` |
| Automated tests exist for unit, API integration, user flow and security | JUnit tags `unit`, `api`, `flow`, `security` | Automated: 442 tests (96 unit, 338 API, 83 flow, 179 security; a class can carry two tags) |
| Load test identifies the maximum concurrent users before response times exceed 5 seconds | `PERFORMANCE.md` | Load test: about 1,400 users, measured 2026-09-08 |
| Load test shows transaction throughput | `PERFORMANCE.md` | Load test: about 430 requests per second sustained |
| Potential bottlenecks identified, with proposed solutions | `PERFORMANCE.md`: the HikariCP connection pool, at its default of 10. Proposed fix: raise `DB_POOL_MAX` | Load test |

## Extra

| Point | Where it is implemented | How it is checked |
|---|---|---|
| UI/UX design quality, consistency and responsiveness across pages | Design tokens in `frontend/src/index.css`, shared components in `frontend/src/components/ui/` | Script: `npm run test:responsive`, `npm run test:a11y`. Otherwise manual |
| Admin interface functionality, data presentation and workflow efficiency | `/admin/*`, and the order event timeline in `/admin/orders` | Automated: the admin controller tests. Manual for workflow |
| Error handling and user guidance across interactions and edge cases | `AppException` mapping, field-level messages on forms | Automated: `NotFoundHandlingTest`, `CheckoutControllerTest` |
| Additional features | Invoice PDFs with reissue, delivery options, saved addresses, address register lookup, GDPR data export and erasure, 2FA recovery codes | Automated: `InvoicePdfGenerationTest`, `OrderEmailContentTest`, `PrivacyControllerTest`, `TotpSecretCipherTest` |
| Security, performance and accessibility quality | Argon2id, append-only audit log and invoices, least-privilege database role, load test, WCAG 2.1 A audit | Automated: `AuditLogTamperProofTest`, `TokenRotationTest`. Load test. Script: `npm run test:a11y` |
| Application is containerised with Docker | `docker-compose.yml`: postgres, redis, rabbitmq, backend, frontend-build, caddy | Manual: `docker compose up -d --build` |
| Host needs only Docker and a payment simulation CLI | `README.md`, Setup. The Stripe CLI is optional | Manual |
