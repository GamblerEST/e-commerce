// Mirrors the backend's DTOs exactly (ee.shop.identity.web.dto / ee.shop.catalog.web.dto).
// Kept hand-written rather than generated for this minimal pass — see
// a later pass, which may want OpenAPI codegen.

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
  recaptchaToken?: string | null;
}

export interface LoginRequest {
  email: string;
  password: string;
  totpCode?: string | null;
}

export interface AuthResponse {
  accessToken: string;
  expiresInSeconds: number;
}

export interface MeResponse {
  userId: string;
  email: string;
  displayName: string;
  role: string;
  totpEnabled: boolean;
}

export interface ProductSummary {
  id: string;
  name: string;
  slug: string;
  brand: string | null;
  price: string; // BigDecimal serializes as a JSON string-safe number; treat as string to avoid float rounding.
  currency: string;
  stockQuantity: number;
  avgRating: string;
  reviewCount: number;
  thumbnailUrl: string | null;
}

export interface ProductSearchResponse {
  items: ProductSummary[];
  nextCursor: string | null;
  totalCount: number;
}

export interface CategoryRef {
  id: string;
  name: string;
  slug: string;
}

export interface Dimensions {
  weightKg: string | null;
  weightLb: string | null;
  lengthCm: string | null;
  lengthIn: string | null;
  widthCm: string | null;
  widthIn: string | null;
  heightCm: string | null;
  heightIn: string | null;
}

/** One product image in every size the shop serves it (V21). */
export interface ProductImageView {
  /** Full size, as uploaded — for zoom. */
  url: string;
  /** ~1000px long edge, for the product page. */
  mediumUrl: string;
  /** ~400px long edge, for grids and cart lines. */
  thumbnailUrl: string;
  /** Admin-supplied. Preferred over anything composed from the product name. */
  altText: string | null;
}

export interface ProductDetail {
  id: string;
  name: string;
  slug: string;
  description: string;
  brand: string | null;
  price: string;
  currency: string;
  stockQuantity: number;
  avgRating: string;
  reviewCount: number;
  category: CategoryRef | null;
  dimensions: Dimensions;
  /** Full-size URLs. Prefer `images`, which carries every rendition. */
  imageUrls: string[];
  images: ProductImageView[];
  attributes: Record<string, string[]>;
}

export interface CategoryResponse {
  id: string;
  name: string;
  slug: string;
  parentId: string | null;
  // Promoted to the home page as a "collection" (V18__home_featured.sql).
  // Not admin-only: the home page already shows which categories are
  // promoted, so there is nothing to hide behind a separate admin DTO.
  featured: boolean;
  tagline: string | null;
}

/** A promoted category as the home page renders it. */
export interface CollectionSummary {
  id: string;
  name: string;
  slug: string;
  tagline: string | null;
  productCount: number;
  thumbnailUrl: string | null;
}

/** GET /api/home — the whole shop window in one round trip. */
export interface HomeResponse {
  featured: ProductSummary[];
  collections: CollectionSummary[];
}

export interface SuggestionItem {
  id: string;
  name: string;
  slug: string;
}

export type ProductSort = 'RELEVANCE' | 'PRICE_ASC' | 'PRICE_DESC' | 'RATING_DESC';

export interface FacetsResponse {
  brands: string[];
  attributes: Record<string, string[]>;
}

export interface AuthorizeResponse {
  authorizationUrl: string;
}

export interface TotpSetupResponse {
  otpauthUri: string;
  secret: string;
}

export interface TotpVerifyResponse {
  recoveryCodes: string[];
}

// Mirrors ee.shop.cart.web.dto — priced from the LIVE product row on every
// read, never a snapshot (that's what makes a cart a cart, not an order).
export interface CartItemResponse {
  productId: string;
  name: string;
  slug: string;
  unitPrice: string;
  currency: string;
  thumbnailUrl: string | null;
  quantity: number;
  lineTotal: string;
}

export interface CartResponse {
  items: CartItemResponse[];
  itemCount: number;
  totalPrice: string;
  currency: string;
}

// Mirrors ee.shop.order.web.dto — line items are NEVER sent here; the
// server always re-prices from the caller's own cart at checkout time.
export interface CheckoutRequest {
  billingName: string;
  billingEmail: string;
  billingPhone: string;
  addressLine1: string;
  addressLine2?: string | null;
  city: string;
  postalCode: string;
  country: string;
  /**
   * Chosen delivery method, by its stable code. Optional server-side so orders
   * placed before delivery options existed stay valid, but the checkout form
   * always sends one — an order with no delivery has no shipping cost and no
   * estimated delivery date to show on the confirmation page.
   */
  deliveryCode?: string | null;
  /**
   * The Estonian address register's id, sent only when the address was picked
   * from AddressLookup and not edited since. The server re-checks the fields
   * against it.
   */
  adsOid?: string | null;
}

export interface OrderLineResponse {
  productId: string;
  productName: string;
  unitPrice: string;
  vatRate: string;
  quantity: number;
  currency: string;
  lineTotal: string;
}

export interface OrderEventResponse {
  type: string;
  occurredAt: string;
}

/** Delivery method as SNAPSHOTTED on the order (V17) — never joined live. */
export interface DeliveryDetails {
  code: string;
  name: string;
  price: string;
  leadDaysMin: number;
  leadDaysMax: number;
  /** ISO date (yyyy-mm-dd) — the estimate is a RANGE, never a single day. */
  estimatedFrom: string;
  estimatedTo: string;
}

export interface OrderResponse {
  id: string;
  /** "ORD-3F2A9C1B" — a display projection of id, not a second identity. */
  reference: string;
  status: string;
  lines: OrderLineResponse[];
  events: OrderEventResponse[];
  currency: string;
  subtotal: string;
  vatTotal: string;
  total: string;
  /** Null when the order has no delivery method attached. */
  delivery: DeliveryDetails | null;
  billingName: string;
  billingEmail: string;
  billingPhone: string | null;
  billingAddressLine1: string;
  billingAddressLine2: string | null;
  billingCity: string;
  billingPostalCode: string;
  billingCountry: string;
  createdAt: string;
  /** Only populated on the response CheckoutController returns right after creating a fresh PaymentIntent — null on list/detail views, or if Stripe isn't configured. */
  paymentClientSecret: string | null;
}

export interface OrderSummaryResponse {
  id: string;
  status: string;
  itemCount: number;
  total: string;
  currency: string;
  createdAt: string;
}

export interface OrderListResponse {
  items: OrderSummaryResponse[];
  page: number;
  size: number;
  totalCount: number;
}

/** From a returning customer's most recent order — a convenience prefill, never authoritative. */
export interface CheckoutPrefillResponse {
  billingName: string;
  billingPhone: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string;
  postalCode: string;
  country: string;
}

// ── Reviews ──────────────────────────────────────────

export interface ReviewResponse {
  id: string;
  rating: number;
  title: string | null;
  body: string;
  /** null for an anonymized account — render as "Anonymous", not blank. */
  authorName: string | null;
  helpfulCount: number;
  votedByMe: boolean;
  mine: boolean;
  status: 'PUBLISHED' | 'HIDDEN';
  createdAt: string;
}

export interface ReviewListResponse {
  items: ReviewResponse[];
  page: number;
  size: number;
  totalCount: number;
}

export interface ReviewRequest {
  rating: number;
  title: string | null;
  body: string;
}

/** Matches ReviewService.SortBy. HELPFUL is the default. */
export type ReviewSort = 'NEWEST' | 'HELPFUL' | 'RATING_HIGH' | 'RATING_LOW';

// ── Admin ────────────────────────────────────────────

export interface ProductAdminView {
  id: string;
  name: string;
  slug: string;
  price: string;
  currency: string;
  vatCategory: string;
  stockQuantity: number;
  categoryId: string | null;
  deleted: boolean;
  featured: boolean;
  brand: string | null;
  description: string | null;
  weightKg: string | null;
  lengthCm: string | null;
  widthCm: string | null;
  heightCm: string | null;
}

export interface AdminProductImage {
  id: string;
  url: string;
  thumbnailUrl: string;
  altText: string | null;
  displayOrder: number;
}

export interface CategoryAdminView {
  id: string;
  parentId: string | null;
  name: string;
  slug: string;
  featured: boolean;
  tagline: string | null;
}

export interface AdminUserView {
  id: string;
  /** null once anonymized (GDPR erasure) — render as "(erased account)". */
  email: string | null;
  displayName: string | null;
  role: string;
  emailVerified: boolean;
  twoFactorEnabled: boolean;
  disabled: boolean;
  anonymized: boolean;
  createdAt: string;
}

/** One issued invoice PDF for an order. Never edited: a correction is a new one. */
export interface BillingDocumentView {
  id: string;
  generatedAt: string;
  /** "System (issued automatically at payment)" or the issuing admin's email. */
  issuedBy: string;
}

export interface AdminOrderSummary {
  id: string;
  reference: string;
  status: string;
  customerName: string;
  customerEmail: string;
  total: string;
  currency: string;
  deliveryName: string | null;
  createdAt: string;
}

/** Admin view — carries id/active/sortOrder the public price list omits. */
export interface AdminDeliveryOptionView {
  id: string;
  code: string;
  name: string;
  carrier: string | null;
  price: string;
  currency: string;
  leadTimeDaysMin: number;
  leadTimeDaysMax: number;
  active: boolean;
  sortOrder: number;
}

export interface DeliveryOptionResponse {
  code: string;
  name: string;
  carrier: string | null;
  price: string;
  currency: string;
  leadTimeDaysMin: number;
  leadTimeDaysMax: number;
}

export interface ImportResult {
  imported: number;
  productIds: string[];
}

// ── Privacy / GDPR ───────────────────────────────────────────────────────

/** GDPR Art. 15/20 export. Mirrors ee.shop.identity.web.dto.PersonalDataExport. */
export interface PersonalDataExport {
  exportedAt: string;
  account: {
    email: string | null;
    displayName: string | null;
    role: string;
    emailVerified: boolean;
    twoFactorEnabled: boolean;
    createdAt: string;
  };
  orders: Array<{
    reference: string;
    status: string;
    placedAt: string;
    currency: string;
    total: string;
    billingName: string;
    billingEmail: string;
    billingPhone: string | null;
    billingAddress: string;
    lines: Array<{ productName: string; quantity: number; unitPrice: string; lineTotal: string }>;
  }>;
  reviews: Array<{
    productName: string;
    rating: number;
    title: string | null;
    body: string;
    createdAt: string;
  }>;
}

// ── Saved addresses (account page) ───────────────────────────────────────

export interface AddressResponse {
  id: string;
  label: string | null;
  recipientName: string;
  phone: string | null;
  line1: string;
  line2: string | null;
  city: string;
  postalCode: string;
  country: string;
  isDefault: boolean;
}

// ── Address register ─────────────────────────────────────────────────────

/** One address from the Estonian address register, split into form fields. */
export interface AddressSuggestion {
  adsOid: string;
  label: string;
  line1: string;
  city: string;
  postalCode: string;
  country: string;
  /** Set when the search text named a flat the register could resolve directly (e.g. "Pikk 1-5"). */
  apartment: string;
  /** Every flat registered in this building, e.g. ["1", "2", "3"]; empty when none are on record. */
  apartmentOptions: string[];
}
