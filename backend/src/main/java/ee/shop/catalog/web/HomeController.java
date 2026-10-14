package ee.shop.catalog.web;

import ee.shop.catalog.service.HomeService;
import ee.shop.catalog.web.dto.HomeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shop window.
 *
 * Public and unauthenticated — allowlisted in SecurityConfig. It exposes only
 * data the catalog API already serves anonymously, just pre-selected and
 * pre-joined for the landing page.
 *
 * Not rate limited, unlike /api/products/suggest and product search. Those
 * take caller-controlled input and so can be made expensive on purpose; this
 * takes no parameters at all, so every request is the same two bounded
 * queries. Rate limiting it would only add a Redis round trip to the shop's
 * most-hit endpoint.
 */
@RestController
@Tag(name = "Catalog")
public class HomeController {

    private final HomeService home;

    public HomeController(HomeService home) {
        this.home = home;
    }

    @GetMapping("/api/home")
    @Operation(summary = "Featured products and promoted collections for the home page")
    public HomeResponse home() {
        return home.load();
    }
}
