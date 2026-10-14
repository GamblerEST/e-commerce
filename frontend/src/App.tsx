import { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { CartProvider } from './cart/CartContext';
import Navbar from './components/Navbar';
import Footer from './components/Footer';
import WelcomePage from './pages/WelcomePage';
import PrivacyPolicyPage from './pages/PrivacyPolicyPage';
import SalesConditionsPage from './pages/SalesConditionsPage';
import ContactsPage from './pages/ContactsPage';
import AboutPage from './pages/AboutPage';
import RegisterPage from './pages/RegisterPage';
import LoginPage from './pages/LoginPage';
import OAuthCallbackPage from './pages/OAuthCallbackPage';
import VerifyEmailPage from './pages/VerifyEmailPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import ResetPasswordPage from './pages/ResetPasswordPage';
import AccountPage from './pages/AccountPage';
import ProductListPage from './pages/ProductListPage';
import ProductDetailPage from './pages/ProductDetailPage';
import CartPage from './pages/CartPage';
import CheckoutPage from './pages/CheckoutPage';
import OrdersPage from './pages/OrdersPage';
import OrderDetailPage from './pages/OrderDetailPage';
import NotFoundPage from './pages/NotFoundPage';
// The admin panel is LAZY. It is six screens of tables and forms that no
// shopper will ever open, and bundling it with the storefront pushed the main
// chunk past 500 KB — a cost paid on first paint by every customer, on mobile
// data, to ship code for a handful of staff accounts. Split out, it downloads
// only when someone actually navigates to /admin.
const AdminLayout = lazy(() => import('./pages/admin/AdminLayout'));
const AdminProductsPage = lazy(() => import('./pages/admin/AdminProductsPage'));
const AdminCategoriesPage = lazy(() => import('./pages/admin/AdminCategoriesPage'));
const AdminOrdersPage = lazy(() => import('./pages/admin/AdminOrdersPage'));
const AdminUsersPage = lazy(() => import('./pages/admin/AdminUsersPage'));
const AdminReviewsPage = lazy(() => import('./pages/admin/AdminReviewsPage'));
const AdminDeliveryPage = lazy(() => import('./pages/admin/AdminDeliveryPage'));
import './App.css';

function AppShell() {
  const { loading } = useAuth();
  if (loading) {
    return <div className="app-loading">Loading…</div>;
  }
  return (
    <>
      {/* WCAG 2.4.1 Bypass Blocks — the first tab stop on every page lets a
          keyboard or screen-reader user jump the whole navigation instead of
          tabbing through it on every single page. Visible only when focused
          (see .skip-link in index.css). */}
      <a href="#main-content" className="skip-link">
        Skip to main content
      </a>
      <Navbar />
      {/* tabIndex={-1} so the skip link can actually move focus here; without
          it the browser scrolls but focus stays in the nav, and the next Tab
          lands back at the top. */}
      <main id="main-content" tabIndex={-1}>
        {/* Only the lazy admin chunk can suspend; every storefront route is
            statically imported, so this fallback is never seen by a shopper. */}
        <Suspense fallback={<p className="p-8 text-muted">Loading…</p>}>
        <Routes>
          <Route path="/" element={<WelcomePage />} />
          <Route path="/products" element={<ProductListPage />} />
          <Route path="/products/:slug" element={<ProductDetailPage />} />
          <Route path="/privacy" element={<PrivacyPolicyPage />} />
          <Route path="/sales-conditions" element={<SalesConditionsPage />} />
          <Route path="/contacts" element={<ContactsPage />} />
          <Route path="/about" element={<AboutPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/oauth/google/callback" element={<OAuthCallbackPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="/cart" element={<CartPage />} />
          <Route path="/checkout" element={<CheckoutPage />} />
          <Route path="/orders" element={<OrdersPage />} />
          <Route path="/orders/:id" element={<OrderDetailPage />} />
          {/* Admin. Nested so the shell (role guard + section nav) renders
              once and each section swaps inside it. The guard is convenience
              only — every endpoint enforces the role server-side. */}
          <Route path="/admin" element={<AdminLayout />}>
            <Route index element={<AdminProductsPage />} />
            <Route path="products" element={<AdminProductsPage />} />
            <Route path="categories" element={<AdminCategoriesPage />} />
            <Route path="orders" element={<AdminOrdersPage />} />
            <Route path="delivery" element={<AdminDeliveryPage />} />
            <Route path="reviews" element={<AdminReviewsPage />} />
            <Route path="users" element={<AdminUsersPage />} />
          </Route>

          {/* Catch-all: an unmatched route must show something, not render
              blank below the Navbar — that silence is exactly how a missing
              route (e.g. the email verification link) goes unnoticed. */}
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
        </Suspense>
      </main>
      <Footer />
    </>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <CartProvider>
          <AppShell />
        </CartProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
