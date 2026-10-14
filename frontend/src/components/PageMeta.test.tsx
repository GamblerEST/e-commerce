import { render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import PageMeta, { SITE_DESCRIPTION, SITE_TITLE } from './PageMeta';

const head = () => document.head;
const meta = (selector: string) => head().querySelector(selector)?.getAttribute('content') ?? null;

/**
 * Public pages arrive with their head already filled in by the server. These
 * tests start from that state, because it is where the unmount behaviour
 * matters: the page a visit starts on must not leave its own title, canonical
 * URL or structured data behind on the next page.
 */
function serverFilledHead() {
  document.title = 'Rannamõisa Mug — Estonian Shop';
  head().innerHTML = `
    <meta name="description" content="Server description" />
    <link data-page-meta rel="canonical" href="https://shop.example/products/rannamoisa-mug" />
    <meta data-page-meta property="og:image" content="https://shop.example/product-images/mug.jpg" />
    <script data-page-meta type="application/ld+json">{"@type":"Product"}</script>
  `;
}

afterEach(() => {
  head().innerHTML = '';
});

describe('PageMeta', () => {
  it('writes the page title, description, canonical URL and robots directive', () => {
    render(<PageMeta title="Search: mugs — Estonian Shop" description="Results" canonicalPath="/products" noindex />);

    expect(document.title).toBe('Search: mugs — Estonian Shop');
    expect(meta('meta[name="description"]')).toBe('Results');
    expect(head().querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      `${window.location.origin}/products`,
    );
    expect(meta('meta[name="robots"]')).toBe('noindex,nofollow');
  });

  it('updates the tags the server filled in rather than adding second copies', () => {
    serverFilledHead();

    render(<PageMeta title="Rannamõisa Mug — Estonian Shop" description="Client description" canonicalPath="/products/rannamoisa-mug" />);

    expect(head().querySelectorAll('meta[name="description"]')).toHaveLength(1);
    expect(head().querySelectorAll('link[rel="canonical"]')).toHaveLength(1);
    expect(meta('meta[name="description"]')).toBe('Client description');
  });

  it('resets to the site-wide values on unmount instead of restoring the entry page', () => {
    serverFilledHead();
    const { unmount } = render(
      <PageMeta title="Rannamõisa Mug — Estonian Shop" description="Client description" canonicalPath="/products/rannamoisa-mug" />,
    );

    unmount();

    expect(document.title).toBe(SITE_TITLE);
    expect(meta('meta[name="description"]')).toBe(SITE_DESCRIPTION);
    expect(head().querySelector('link[rel="canonical"]')).toBeNull();
    expect(head().querySelector('meta[property^="og:"]')).toBeNull();
    expect(head().querySelector('script[type="application/ld+json"]')).toBeNull();
  });

  it('drops a noindex directive when the next page is indexable', () => {
    const { rerender } = render(<PageMeta title="Search — Estonian Shop" description="d" noindex />);
    expect(meta('meta[name="robots"]')).toBe('noindex,nofollow');

    rerender(<PageMeta title="All products — Estonian Shop" description="d" canonicalPath="/products" />);

    expect(head().querySelector('meta[name="robots"]')).toBeNull();
  });
});
