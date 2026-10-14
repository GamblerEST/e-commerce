package ee.shop.seo.service;

import ee.shop.catalog.model.Category;
import ee.shop.catalog.model.Product;
import ee.shop.catalog.model.ProductImage;
import ee.shop.catalog.repository.CategoryRepository;
import ee.shop.catalog.repository.ProductImageRepository;
import ee.shop.catalog.repository.ProductRepository;
import ee.shop.catalog.service.HomeService;
import ee.shop.catalog.web.dto.CollectionSummary;
import ee.shop.catalog.web.dto.HomeResponse;
import ee.shop.catalog.web.dto.ProductSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ee.shop.seo.service.SeoPageRenderer.escape;

/**
 * What each public page says before JavaScript runs: its head, its structured
 * data, and readable content with real links.
 *
 * Built from the live database on every request rather than prerendered at
 * build time. A prerendered product page would go stale the moment staff
 * changed a price or a description, and would stay stale until someone rebuilt
 * the frontend.
 *
 * The titles, descriptions and headings are the same text the React pages use
 * (see MetaText for why that matters). Change one and change the other.
 */
@Service
public class SeoPageService {

    /** Links on the listing page. The sitemap is what lists every product. */
    static final int LISTING_LIMIT = 48;

    static final String HOME_TITLE = "Estonian Shop — home goods, books and electronics";
    static final String HOME_DESCRIPTION = "Buy home goods, books, laptops, phones and clothing from Tallinn. "
            + "VAT included in every price, tracked delivery across Estonia, and secure Stripe payments.";
    static final String HOME_HEADING = "Everyday things, chosen well and priced honestly.";
    static final String LISTING_DESCRIPTION = "Browse home goods, books, laptops, phones and clothing at Estonian Shop. "
            + "Filter by category, brand and price. VAT included, tracked delivery across Estonia.";
    static final String ABOUT_DESCRIPTION = "Who we are, what we sell and why. Estonian Shop is a small Tallinn "
            + "retailer selling durable everyday goods at VAT-inclusive prices, shipping across Estonia.";
    static final String CONTACTS_DESCRIPTION = "Get in touch about an order, a return or anything else. Send us a "
            + "message, or reach Estonian Shop directly by email or phone — we reply within two business days.";
    static final String PRIVACY_DESCRIPTION = "How Estonian Shop collects, uses and protects your personal data: "
            + "what we store, who receives it and why, which cookies are set, and the rights you have over it.";
    static final String SALES_DESCRIPTION = "The conditions of sale at Estonian Shop: who the seller is, how prices "
            + "and payment work, and the terms that apply to every order placed in this shop.";

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductImageRepository images;
    private final HomeService home;
    private final String siteUrl;

    public SeoPageService(ProductRepository products, CategoryRepository categories,
                          ProductImageRepository images, HomeService home,
                          @Value("${app.frontend-url}") String siteUrl) {
        this.products = products;
        this.categories = categories;
        this.images = images;
        this.home = home;
        // A trailing slash would produce "//products" in every absolute URL.
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    @Transactional(readOnly = true)
    public SeoPage home() {
        HomeResponse window = home.load();
        StringBuilder body = new StringBuilder("<main><h1>").append(escape(HOME_HEADING)).append("</h1>");
        if (!window.collections().isEmpty()) {
            body.append("<h2>Shop by collection</h2><ul>");
            for (CollectionSummary c : window.collections()) {
                body.append("<li><a href=\"/products?categoryId=").append(c.id()).append("\">")
                        .append(escape(c.name())).append("</a>");
                if (c.tagline() != null && !c.tagline().isBlank()) {
                    body.append(" — ").append(escape(c.tagline()));
                }
                body.append("</li>");
            }
            body.append("</ul>");
        }
        if (!window.featured().isEmpty()) {
            body.append("<h2>Featured right now</h2><ul>");
            window.featured().forEach(p -> body.append(productItem(p.slug(), p.name(), p.price(), p.currency())));
            body.append("</ul>");
        }
        body.append("<p><a href=\"/products\">Browse all products</a></p></main>");

        Map<String, Object> site = object("@context", "https://schema.org", "@type", "WebSite",
                "name", MetaText.SITE, "url", absolute("/"));
        return new SeoPage(200, HOME_TITLE, HOME_DESCRIPTION, absolute("/"), false, null,
                List.of(site), body.toString());
    }

    /**
     * The catalogue, or a search within it. Search results are asked not to be
     * indexed: they are thin, endless in number, and a search engine's own
     * results page is no place for someone else's.
     */
    @Transactional(readOnly = true)
    public SeoPage productList(String query) {
        String term = query == null ? "" : query.trim();
        if (!term.isEmpty()) {
            String description = "Search results for \"" + term + "\" at Estonian Shop. Filter by category, brand "
                    + "and price. VAT included in every price, tracked delivery across Estonia.";
            String body = "<main><h1>" + escape("Search results for \"" + term + "\"") + "</h1>"
                    + "<p><a href=\"/products\">Browse all products</a></p></main>";
            return new SeoPage(200, MetaText.searchTitle(term), description, absolute("/products"), true, null,
                    List.of(), body);
        }

        StringBuilder body = new StringBuilder("<main><h1>All products</h1><ul>");
        products.findByDeletedAtIsNullOrderByNameAsc(PageRequest.of(0, LISTING_LIMIT))
                .forEach(p -> body.append(productItem(p.getSlug(), p.getName(), p.getPrice(), p.getCurrency())));
        body.append("</ul></main>");
        return new SeoPage(200, "All products — " + MetaText.SITE, LISTING_DESCRIPTION, absolute("/products"),
                false, null, List.of(), body.toString());
    }

    @Transactional(readOnly = true)
    public SeoPage product(String slug) {
        Product p = products.findBySlugAndDeletedAtIsNull(slug).orElse(null);
        if (p == null) {
            return productNotFound();
        }
        Category category = p.getCategoryId() == null ? null : categories.findById(p.getCategoryId()).orElse(null);
        List<ProductImage> gallery = images.findByProductIdOrderByDisplayOrder(p.getId());
        List<ProductSummary> related = home.related(p.getId(), p.getCategoryId());
        String canonical = absolute("/products/" + p.getSlug());
        String imageUrl = gallery.isEmpty() ? null : absolute("/" + gallery.getFirst().getMediumKeyOrOriginal());
        boolean inStock = p.getStockQuantity() > 0;

        StringBuilder body = new StringBuilder("<main><nav aria-label=\"Breadcrumb\"><a href=\"/\">Home</a> › ")
                .append("<a href=\"/products\">All products</a>");
        if (category != null) {
            body.append(" › <a href=\"/products?categoryId=").append(category.getId()).append("\">")
                    .append(escape(category.getName())).append("</a>");
        }
        body.append("</nav><h1>").append(escape(p.getName())).append("</h1>");
        if (p.getBrand() != null && !p.getBrand().isBlank()) {
            body.append("<p>Brand: ").append(escape(p.getBrand())).append("</p>");
        }
        body.append("<p>").append(escape(money(p.getPrice(), p.getCurrency()))).append(", VAT included. ")
                .append(inStock ? "In stock." : "Out of stock.").append("</p>");
        if (p.getReviewCount() > 0 && p.getAvgRating() != null) {
            body.append("<p>Rated ").append(p.getAvgRating().stripTrailingZeros().toPlainString())
                    .append(" out of 5 from ").append(p.getReviewCount())
                    .append(p.getReviewCount() == 1 ? " review." : " reviews.").append("</p>");
        }
        if (!gallery.isEmpty()) {
            ProductImage first = gallery.getFirst();
            String alt = first.getAltText() != null && !first.getAltText().isBlank() ? first.getAltText() : p.getName();
            body.append("<img src=\"/").append(escape(first.getMediumKeyOrOriginal())).append("\" alt=\"")
                    .append(escape(alt)).append("\">");
        }
        if (p.getDescription() != null && !p.getDescription().isBlank()) {
            body.append("<p>").append(escape(p.getDescription())).append("</p>");
        }
        if (!related.isEmpty()) {
            body.append("<h2>You might also like</h2><ul>");
            related.forEach(r -> body.append(productItem(r.slug(), r.name(), r.price(), r.currency())));
            body.append("</ul>");
        }
        body.append("</main>");

        Map<String, Object> offer = object("@type", "Offer",
                "price", p.getPrice() == null ? null : p.getPrice().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                "priceCurrency", p.getCurrency(),
                "availability", inStock ? "https://schema.org/InStock" : "https://schema.org/OutOfStock",
                "url", canonical);
        Map<String, Object> rating = p.getReviewCount() > 0 && p.getAvgRating() != null
                ? object("@type", "AggregateRating",
                        "ratingValue", p.getAvgRating().stripTrailingZeros().toPlainString(),
                        "reviewCount", p.getReviewCount())
                : null;
        Map<String, Object> product = object(
                "@context", "https://schema.org",
                "@type", "Product",
                "name", p.getName(),
                "description", p.getDescription(),
                "url", canonical,
                "image", gallery.isEmpty() ? null
                        : gallery.stream().map(img -> absolute("/" + img.getMediumKeyOrOriginal())).toList(),
                "brand", p.getBrand() == null || p.getBrand().isBlank() ? null : object("@type", "Brand", "name", p.getBrand()),
                "offers", offer,
                "aggregateRating", rating);

        List<Map<String, Object>> crumbs = new ArrayList<>();
        crumbs.add(object("@type", "ListItem", "position", 1, "name", "Home", "item", absolute("/")));
        crumbs.add(object("@type", "ListItem", "position", 2, "name", "All products", "item", absolute("/products")));
        if (category != null) {
            crumbs.add(object("@type", "ListItem", "position", 3, "name", category.getName(),
                    "item", absolute("/products?categoryId=" + category.getId())));
        }
        crumbs.add(object("@type", "ListItem", "position", crumbs.size() + 1, "name", p.getName(), "item", canonical));
        Map<String, Object> breadcrumb = object("@context", "https://schema.org", "@type", "BreadcrumbList",
                "itemListElement", crumbs);

        return new SeoPage(200, MetaText.productTitle(p.getName()),
                MetaText.productDescription(p.getName(), p.getBrand(), p.getDescription()),
                canonical, false, imageUrl, List.of(product, breadcrumb), body.toString());
    }

    /** A real 404, so a removed product drops out of search results instead of lingering as an empty page. */
    public SeoPage productNotFound() {
        return new SeoPage(404, "Product not found", "This product could not be shown.", null, true, null,
                List.of(), "<main><h1>Product not found</h1><p><a href=\"/products\">Browse all products</a></p></main>");
    }

    public SeoPage about() {
        return simple("About Estonian Shop", ABOUT_DESCRIPTION, "/about", "About Estonian Shop");
    }

    public SeoPage contacts() {
        return simple("Contact Estonian Shop", CONTACTS_DESCRIPTION, "/contacts", "Contact us");
    }

    public SeoPage privacy() {
        return simple("Privacy policy — " + MetaText.SITE, PRIVACY_DESCRIPTION, "/privacy", "Privacy policy");
    }

    public SeoPage salesConditions() {
        return simple("Sales conditions — " + MetaText.SITE, SALES_DESCRIPTION, "/sales-conditions", "Sales conditions");
    }

    private SeoPage simple(String title, String description, String path, String heading) {
        return new SeoPage(200, title, description, absolute(path), false, null, List.of(),
                "<main><h1>" + escape(heading) + "</h1><p><a href=\"/products\">Browse the catalogue</a></p></main>");
    }

    private static String productItem(String slug, String name, BigDecimal price, String currency) {
        return "<li><a href=\"/products/" + escape(slug) + "\">" + escape(name) + "</a> — "
                + escape(money(price, currency)) + "</li>";
    }

    private static String money(BigDecimal amount, String currency) {
        return amount == null ? "" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + currency;
    }

    private String absolute(String path) {
        return siteUrl + path;
    }

    /** An ordered JSON object from key/value pairs; a null value leaves the key out. */
    private static Map<String, Object> object(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                map.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
        }
        return map;
    }
}
