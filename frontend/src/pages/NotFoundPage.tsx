import { Link } from 'react-router-dom';
import PageMeta from '../components/PageMeta';
import { Button } from '../components/ui/button';

export default function NotFoundPage() {
  return (
    <div className="mx-auto max-w-sm text-center">
      <PageMeta
        title="Page not found — Estonian Shop"
        description="There is nothing at this address. Browse the catalogue, or go back to the shop's front page."
        noindex
      />
      <p className="text-display font-extrabold text-hairline">404</p>
      <h1 className="text-h2 font-extrabold text-ink">Page not found</h1>
      <p className="mt-3 text-muted">There&apos;s nothing here.</p>
      <Button asChild className="mt-6">
        <Link to="/">Back to the shop</Link>
      </Button>
    </div>
  );
}
