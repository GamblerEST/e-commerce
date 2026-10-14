package ee.shop.seo.web;

import ee.shop.seo.service.IndexHtmlTemplate;
import ee.shop.seo.service.SeoPage;
import ee.shop.seo.service.SeoPageRenderer;
import ee.shop.seo.service.SeoPageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * The shop's public pages, as crawlers and link previews that never run
 * JavaScript should see them.
 *
 * Each response is the built single-page app with this page's own title,
 * description, canonical URL, structured data and readable content already in
 * it. A browser gets the same app and React replaces the pre-filled content as
 * it starts, so nothing changes for people; everything changes for anything that
 * reads the HTML as sent.
 *
 * Only public, indexable pages are here. The proxy sends every other route
 * (cart, checkout, account, admin) straight to the static app.
 */
@RestController
@Tag(name = "Pages")
public class SeoPageController {

    /**
     * Explicit, because a String body otherwise goes out as ISO-8859-1 and every
     * "õ" in a product name arrives broken.
     */
    private static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);

    /** Free-text path and query values are bounded before they reach a page. */
    static final int MAX_PARAM = 200;

    private final SeoPageService pages;
    private final SeoPageRenderer renderer;
    private final IndexHtmlTemplate template;

    public SeoPageController(SeoPageService pages, SeoPageRenderer renderer, IndexHtmlTemplate template) {
        this.pages = pages;
        this.renderer = renderer;
        this.template = template;
    }

    @GetMapping("/")
    @Operation(summary = "Home page, pre-filled for crawlers")
    public ResponseEntity<String> home() {
        return render(pages::home);
    }

    @GetMapping("/products")
    @Operation(summary = "Product listing or search, pre-filled for crawlers")
    public ResponseEntity<String> products(@RequestParam(required = false) String q) {
        String term = q != null && q.length() > MAX_PARAM ? q.substring(0, MAX_PARAM) : q;
        return render(() -> pages.productList(term));
    }

    @GetMapping("/products/{slug}")
    @Operation(summary = "Product page, pre-filled for crawlers")
    public ResponseEntity<String> product(@PathVariable String slug) {
        return render(() -> slug.length() > MAX_PARAM ? pages.productNotFound() : pages.product(slug));
    }

    @GetMapping("/about")
    public ResponseEntity<String> about() {
        return render(pages::about);
    }

    @GetMapping("/contacts")
    public ResponseEntity<String> contacts() {
        return render(pages::contacts);
    }

    @GetMapping("/privacy")
    public ResponseEntity<String> privacy() {
        return render(pages::privacy);
    }

    @GetMapping("/sales-conditions")
    public ResponseEntity<String> salesConditions() {
        return render(pages::salesConditions);
    }

    /**
     * The template is checked before the page is built, so a missing build costs
     * no database queries. 503 is what the proxy watches for to serve the plain
     * static app instead.
     */
    private ResponseEntity<String> render(Supplier<SeoPage> page) {
        return template.html()
                .map(html -> {
                    SeoPage built = page.get();
                    return ResponseEntity.status(built.status())
                            .contentType(HTML_UTF8)
                            // Revalidate every time: index.html names this build's
                            // hashed assets, and a cached copy outlives those files.
                            .cacheControl(CacheControl.noCache())
                            .body(renderer.render(html, built));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Page template unavailable"));
    }
}
