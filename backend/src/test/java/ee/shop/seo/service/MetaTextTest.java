package ee.shop.seo.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's copy of ProductDetailPage.buildMetaDescription. The expected
 * strings are what the frontend function returns for the same input; if the two
 * drift, one URL starts carrying two different descriptions.
 */
@Tag("unit")
class MetaTextTest {

    @Test
    void aShortDescriptionIsPaddedWithAFactualSuffix() {
        assertThat(MetaText.productDescription("Kettle", "Acme", "Boils water."))
                .isEqualTo("Boils water. Buy Kettle by Acme from Estonian Shop — VAT included, tracked delivery across Estonia.");
    }

    @Test
    void withoutABrandOrDescriptionOnlyTheSuffixRemains() {
        assertThat(MetaText.productDescription("Kettle", null, null))
                .isEqualTo("Buy Kettle from Estonian Shop — VAT included, tracked delivery across Estonia.");
    }

    @Test
    void aLongDescriptionIsCutAtAWordBoundaryWithAnEllipsis() {
        String result = MetaText.productDescription("Kettle", "Acme", "word ".repeat(60).trim());

        assertThat(result).endsWith("…").hasSizeLessThanOrEqualTo(158);
        assertThat(result.substring(0, result.length() - 1)).endsWith("word");
    }

    @Test
    void aDescriptionWithNoSpaceNearTheEndIsCutHard() {
        assertThat(MetaText.productDescription("Kettle", "Acme", "x".repeat(300)))
                .isEqualTo("x".repeat(157) + "…");
    }

    @Test
    void titlesNeverExceedSixtyCharacters() {
        assertThat(MetaText.productTitle("A".repeat(80))).hasSize(60);
        assertThat(MetaText.searchTitle("B".repeat(80))).hasSize(60);
    }
}
