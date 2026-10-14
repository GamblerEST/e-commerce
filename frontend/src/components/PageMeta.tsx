import { useEffect } from 'react';

/**
 * The site-wide title and description in index.html, and what every page
 * returns to once its own are gone. Keep these in step with index.html.
 */
export const SITE_TITLE = 'Estonian Shop — home goods, books and electronics';
export const SITE_DESCRIPTION =
  'Buy home goods, books, laptops, phones and clothing from Tallinn. VAT included in every price, tracked delivery across Estonia, and secure payments.';

interface PageMetaProps {
  /** Under 60 characters, so a search result does not truncate it. */
  title: string;
  /** 155–160 characters — the length search results actually show. */
  description: string;
  /** Root-relative path, e.g. "/products/kettle". Omit for pages that should not be canonical. */
  canonicalPath?: string;
  /**
   * Keep this page out of search results. Used for account/checkout/admin
   * pages — thin, private or duplicate content that would dilute the site in
   * an index even though it is not secret.
   */
  noindex?: boolean;
}

/**
 * Per-page <title>, meta description, canonical URL and robots directive.
 *
 * Written by hand rather than pulling in react-helmet-async. This app renders
 * one page at a time under a single router, so the whole problem is "set a few
 * tags on mount, reset them on unmount" — a dependency would add a provider, a
 * context and a bundle for behaviour that is a few dozen lines. React 19 also
 * renders bare <title>/<meta> in JSX now, but that hoisting does NOT reliably
 * remove the previous page's tags on navigation, which is exactly the bug that
 * makes per-page meta worthless.
 *
 * Public pages arrive with these tags already filled in by the backend
 * (SeoPageController), so a crawler or link preview that never runs JavaScript
 * still sees them. This component keeps them right as the visitor moves around
 * the app, and has to write the same values the server writes for a page, or
 * one URL ends up with two descriptions.
 *
 * On unmount it resets to the site-wide values instead of restoring whatever was
 * there before. "Before" is the head of the page the visit started on, and
 * restoring it would carry that page's title, canonical URL and structured data
 * onto the next page that sets none of its own.
 */
export default function PageMeta({ title, description, canonicalPath, noindex }: PageMetaProps) {
  useEffect(() => {
    document.title = title;
    setMeta('name', 'description', description);

    // Open Graph, so a shared link renders as a card rather than a bare URL.
    setMeta('property', 'og:title', title);
    setMeta('property', 'og:description', description);
    setMeta('property', 'og:type', 'website');

    if (noindex) {
      setMeta('name', 'robots', 'noindex,nofollow');
    } else {
      removeAll('meta[name="robots"]');
    }

    if (canonicalPath) {
      const absolute = new URL(canonicalPath, window.location.origin).toString();
      setLink('canonical', absolute);
      setMeta('property', 'og:url', absolute);
    } else {
      removeAll('link[rel="canonical"], meta[property="og:url"]');
    }

    return () => {
      document.title = SITE_TITLE;
      setMeta('name', 'description', SITE_DESCRIPTION);
      // Includes the server's og:image and structured data (data-page-meta):
      // they describe the page being left, not the one being opened.
      removeAll('meta[property^="og:"], meta[name="robots"], link[rel="canonical"], [data-page-meta]');
    };
  }, [title, description, canonicalPath, noindex]);

  return null;
}

function setMeta(keyAttr: 'name' | 'property', key: string, content: string): void {
  let el = document.head.querySelector<HTMLMetaElement>(`meta[${keyAttr}="${key}"]`);
  if (!el) {
    el = document.createElement('meta');
    el.setAttribute(keyAttr, key);
    document.head.appendChild(el);
  }
  el.setAttribute('content', content);
}

function setLink(rel: string, href: string): void {
  let el = document.head.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
  if (!el) {
    el = document.createElement('link');
    el.setAttribute('rel', rel);
    document.head.appendChild(el);
  }
  el.setAttribute('href', href);
}

function removeAll(selector: string): void {
  document.head.querySelectorAll(selector).forEach((el) => el.remove());
}
