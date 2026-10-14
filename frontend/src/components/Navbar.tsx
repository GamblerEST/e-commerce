import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Menu, X, LayoutDashboard } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import QuickSearch from './QuickSearch';
import CartPreview from './CartPreview';
import { Button } from './ui/button';

/**
 * Site header.
 *
 * Carries the two features that belong on EVERY page — quick search
 * with suggestions, and the quick cart preview — plus the staff entry point.
 *
 * The admin link is a convenience, never a control: it is hidden from
 * customers only so the nav is not cluttered. Every admin route re-checks the
 * role server-side on every request, so a customer who types /admin directly
 * gets a 403 from the API regardless of what the nav rendered.
 */
export default function Navbar() {
  const { user, logout } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const navigate = useNavigate();

  // User.Role is CUSTOMER | SUPPORT | ADMIN. SUPPORT is also the sales role: a
  // separate SALES role would carry no permissions distinct from it, and a role
  // that grants nothing new is worse than no role at all.
  const isStaff = user?.role === 'ADMIN' || user?.role === 'SUPPORT';

  async function onLogout() {
    setMenuOpen(false);
    await logout();
    navigate('/');
  }

  return (
    <header className="sticky top-0 z-40 border-b border-hairline bg-surface">
      <nav aria-label="Main" className="mx-auto flex max-w-[1100px] flex-wrap items-center gap-3 px-6 py-3">
        <Link to="/" className="text-lg font-extrabold tracking-tight text-ink">
          Estonian Shop
        </Link>

        {/* Search takes the middle on desktop; drops to its own full-width row
            on narrow screens rather than being hidden behind a menu, because
            search is the primary way people navigate a shop. */}
        <div className="order-3 w-full sm:order-none sm:w-auto sm:flex-1 sm:px-4">
          <QuickSearch onNavigate={() => setMenuOpen(false)} />
        </div>

        <div className="ml-auto flex items-center gap-1 sm:gap-2">
          <Link
            to="/products"
            className="hidden rounded-field px-3 py-2 font-semibold text-ink hover:bg-canvas sm:inline-block"
          >
            Products
          </Link>

          <CartPreview />

          <button
            type="button"
            onClick={() => setMenuOpen((o) => !o)}
            aria-expanded={menuOpen}
            aria-controls="account-menu"
            aria-label={menuOpen ? 'Close menu' : 'Open menu'}
            className="rounded-field p-2 text-ink hover:bg-canvas"
          >
            {menuOpen ? (
              <X className="h-5 w-5" aria-hidden="true" />
            ) : (
              <Menu className="h-5 w-5" aria-hidden="true" />
            )}
          </button>
        </div>

        {menuOpen && (
          <div
            id="account-menu"
            className="order-4 w-full border-t border-hairline pt-3 sm:order-none"
          >
            <ul className="flex list-none flex-wrap items-center gap-2 p-0">
              <li className="sm:hidden">
                <MenuLink to="/products" onClick={() => setMenuOpen(false)}>
                  Products
                </MenuLink>
              </li>
              <li>
                <MenuLink to="/about" onClick={() => setMenuOpen(false)}>
                  About
                </MenuLink>
              </li>
              <li>
                <MenuLink to="/contacts" onClick={() => setMenuOpen(false)}>
                  Contact
                </MenuLink>
              </li>

              {user ? (
                <>
                  <li>
                    <MenuLink to="/orders" onClick={() => setMenuOpen(false)}>
                      Orders
                    </MenuLink>
                  </li>
                  <li>
                    <MenuLink to="/account" onClick={() => setMenuOpen(false)}>
                      Account
                    </MenuLink>
                  </li>
                  {isStaff && (
                    <li>
                      <Link
                        to="/admin"
                        onClick={() => setMenuOpen(false)}
                        className="inline-flex items-center gap-2 rounded-field bg-ink px-3 py-2 font-semibold text-surface"
                      >
                        <LayoutDashboard className="h-4 w-4" aria-hidden="true" />
                        Admin
                      </Link>
                    </li>
                  )}
                  <li className="ml-auto flex items-center gap-3">
                    <span className="text-sm text-muted">{user.displayName}</span>
                    <button
                      type="button"
                      onClick={onLogout}
                      className="rounded-field border border-hairline px-3 py-2 font-semibold text-ink"
                    >
                      Log out
                    </button>
                  </li>
                </>
              ) : (
                <li className="ml-auto flex items-center gap-2">
                  <MenuLink to="/login" onClick={() => setMenuOpen(false)}>
                    Log in
                  </MenuLink>
                  <Button asChild size="sm">
                    <Link to="/register" onClick={() => setMenuOpen(false)}>
                      Register
                    </Link>
                  </Button>
                </li>
              )}
            </ul>
          </div>
        )}
      </nav>
    </header>
  );
}

function MenuLink({
  to,
  onClick,
  children,
}: {
  to: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <Link
      to={to}
      onClick={onClick}
      className="inline-block rounded-field px-3 py-2 font-semibold text-ink hover:bg-canvas"
    >
      {children}
    </Link>
  );
}
