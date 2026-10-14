import { NavLink, Navigate, Outlet } from 'react-router-dom';
import { Package, FolderTree, ShoppingBag, Users, MessageSquare, Truck } from 'lucide-react';
import { useAuth } from '../../auth/AuthContext';
import PageMeta from '../../components/PageMeta';

const SECTIONS = [
  { to: '/admin/products', label: 'Products', Icon: Package },
  { to: '/admin/categories', label: 'Categories', Icon: FolderTree },
  { to: '/admin/orders', label: 'Orders', Icon: ShoppingBag },
  { to: '/admin/delivery', label: 'Delivery', Icon: Truck },
  { to: '/admin/reviews', label: 'Reviews', Icon: MessageSquare },
  { to: '/admin/users', label: 'Users', Icon: Users },
];

/**
 * Admin shell.
 *
 * The guard here is a CONVENIENCE, not a security control. It exists so a
 * customer who wanders to /admin gets a clear "not for you" instead of a
 * screen full of failed requests. Every admin endpoint independently enforces
 * @AdminOnly server-side — role AND an enabled second factor — so removing
 * this component would leak no data at all, only polish.
 *
 * That distinction is worth stating explicitly because the opposite mistake
 * (trusting a client-side role check) is the single most common way an admin
 * panel gets broken into.
 */
export default function AdminLayout() {
  const { user, loading } = useAuth();

  if (loading) return <p className="p-8 text-muted">Loading…</p>;
  if (!user) return <Navigate to="/login" replace />;

  const isStaff = user.role === 'ADMIN' || user.role === 'SUPPORT';
  if (!isStaff) {
    return (
      <div className="mx-auto max-w-xl py-16 text-center">
        <h1 className="text-h2 font-extrabold text-ink">Not available</h1>
        <p className="mt-3 text-muted">This area is for shop staff.</p>
      </div>
    );
  }

  return (
    <div>
      {/* Admin screens must never be indexed: thin, private, and duplicated
          content that would dilute the site in a search index. */}
      <PageMeta
        title="Admin — Estonian Shop"
        description="Shop administration."
        noindex
      />

      <header className="mb-6 border-b border-hairline pb-4">
        <h1 className="text-h2 font-extrabold text-ink">Administration</h1>
        <p className="mt-1 text-sm text-muted">
          Signed in as {user.displayName} · {user.role}
        </p>
      </header>

      <nav aria-label="Admin sections" className="mb-8">
        <ul className="flex list-none flex-wrap gap-2 p-0">
          {SECTIONS.map(({ to, label, Icon }) => (
            <li key={to}>
              <NavLink
                to={to}
                className={({ isActive }) =>
                  [
                    'inline-flex items-center gap-2 rounded-field border px-4 py-2 font-semibold no-underline transition',
                    isActive
                      ? 'border-ink bg-ink text-surface'
                      : 'border-hairline bg-surface text-ink hover:bg-canvas',
                  ].join(' ')
                }
              >
                <Icon className="h-4 w-4" aria-hidden="true" />
                {label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      <Outlet />
    </div>
  );
}
