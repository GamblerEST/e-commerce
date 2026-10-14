package ee.shop.seo.service;

/**
 * Page titles and descriptions, written exactly the way the frontend writes them.
 *
 * Server and client have to agree. A crawler that runs JavaScript reads the
 * values PageMeta sets; one that does not reads the values filled in here. Two
 * different descriptions for one URL is how a search result ends up showing
 * whichever one it happened to see last.
 */
final class MetaText {

    static final String SITE = "Estonian Shop";

    private MetaText() {}

    /** Mirrors ProductDetailPage: "&lt;name&gt; — Estonian Shop", cut to 60 characters. */
    static String productTitle(String name) {
        return truncate(name + " — " + SITE, 60);
    }

    /** Mirrors ProductListPage for a search: "Search: &lt;term&gt; — Estonian Shop", cut to 60. */
    static String searchTitle(String term) {
        return truncate("Search: " + term + " — " + SITE, 60);
    }

    /**
     * Mirrors buildMetaDescription in ProductDetailPage.tsx, character for
     * character: the product's own words lead, a factual suffix pads a short
     * one out, and anything past 160 characters is cut at a word boundary when
     * there is one reasonably close to the end.
     */
    static String productDescription(String name, String brand, String description) {
        String suffix = "Buy " + name + (brand != null && !brand.isEmpty() ? " by " + brand : "")
                + " from " + SITE + " — VAT included, tracked delivery across Estonia.";
        String own = description == null ? "" : description.trim();
        String full = own.isEmpty() ? suffix : own + " " + suffix;
        if (full.length() <= 160) {
            return full;
        }
        String cut = full.substring(0, 157);
        int lastSpace = cut.lastIndexOf(' ');
        return (lastSpace > 120 ? cut.substring(0, lastSpace) : cut).stripTrailing() + "…";
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
