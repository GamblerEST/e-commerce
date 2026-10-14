package ee.shop.catalog.service;

import ee.shop.catalog.repository.HomeRepository;
import ee.shop.catalog.web.dto.CollectionSummary;
import ee.shop.catalog.web.dto.HomeResponse;
import ee.shop.catalog.web.dto.ProductSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Builds the home page's payload: featured products and collections.
 *
 * Both halves are read through two hand-written queries rather than assembled
 * in Java from repository calls. The reason is the thumbnail: a collection
 * tile and a product card each need ONE image out of a one-to-many table, and
 * doing that per row in Java is the classic N+1 — 8 collections plus 8
 * products would be 17 queries on the shop's busiest page. Postgres does it
 * in two with a lateral join.
 */
@Service
public class HomeService {

    /**
     * Caps, not pagination. The home page is a curated shop window; if an
     * admin flags 400 products as featured, the right behaviour is to show the
     * best of them, not to render 400 cards or to paginate a shop window.
     */
    private static final int MAX_FEATURED = 8;
    private static final int MAX_COLLECTIONS = 6;

    /** One row of four on desktop, so the strip never becomes a second catalogue. */
    private static final int MAX_RELATED = 4;

    private final HomeRepository home;

    public HomeService(HomeRepository home) {
        this.home = home;
    }

    /**
     * Related products for a PDP. Returns an empty list rather than throwing
     * when a product has no category or no neighbours — an empty strip is a
     * layout decision for the page, not an error for the shopper.
     */
    @Transactional(readOnly = true)
    public List<ProductSummary> related(java.util.UUID productId, java.util.UUID categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        return home.findRelatedProducts(productId, categoryId, MAX_RELATED).stream()
                .map(HomeService::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public HomeResponse load() {
        List<ProductSummary> featured = home.findFeaturedProducts(MAX_FEATURED).stream()
                .map(HomeService::toSummary)
                .toList();

        List<CollectionSummary> collections = home.findFeaturedCollections(MAX_COLLECTIONS).stream()
                .map(r -> new CollectionSummary(
                        r.getId(), r.getName(), r.getSlug(), r.getTagline(),
                        r.getProductCount(),
                        r.getThumbnailKey() == null ? null : "/" + r.getThumbnailKey()))
                .toList();

        return new HomeResponse(featured, collections);
    }

    private static ProductSummary toSummary(HomeRepository.FeaturedProductRow r) {
        return new ProductSummary(
                r.getId(), r.getName(), r.getSlug(), r.getBrand(),
                r.getPrice(), r.getCurrency(), r.getStockQuantity(),
                r.getAvgRating(), r.getReviewCount(),
                // storage_key is stored without a leading slash (see
                // ProductSearchService, which does the same) so the API returns
                // a root-relative URL the browser can use directly against
                // /product-images/*.
                r.getThumbnailKey() == null ? null : "/" + r.getThumbnailKey());
    }
}
