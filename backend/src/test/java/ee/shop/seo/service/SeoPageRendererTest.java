package ee.shop.seo.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
@Tag("security")
class SeoPageRendererTest {

    private final SeoPageRenderer renderer = new SeoPageRenderer(JsonMapper.builder().build());

    private static SeoPage page(List<Map<String, Object>> data, String body) {
        return new SeoPage(200, "Title", "Description", null, false, null, data, body);
    }

    @Test
    void aTemplateWithoutADescriptionStillGetsExactlyOne() {
        String html = renderer.render(
                "<html><head><title>x</title></head><body><div id=\"root\"></div></body></html>",
                page(List.of(), "<main>hi</main>"));

        assertThat(html.split("name=\"description\"", -1)).hasSize(2);
        assertThat(html).contains("<title>Title</title>");
    }

    @Test
    void theEmptyRootIsFilledWithThePageContent() {
        String html = renderer.render(
                "<html><head></head><body><div id=\"root\"></div></body></html>",
                page(List.of(), "<main>hi</main>"));

        assertThat(html).contains("<div id=\"root\"><main>hi</main></div>");
    }

    /** A different build layout must fail loudly, not quietly serve an unfilled page. */
    @Test
    void aTemplateWithoutTheExpectedShapeIsRefused() {
        assertThatThrownBy(() -> renderer.render("<html><body>no head</body></html>", page(List.of(), "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aValueInStructuredDataCannotEndItsScriptElement() {
        String html = renderer.render(
                "<html><head></head><body><div id=\"root\"></div></body></html>",
                page(List.of(Map.of("name", "</script><script>alert(1)</script>")), ""));

        assertThat(html).doesNotContain("</script><script>alert");
        assertThat(html).contains("\\u003c/script\\u003e\\u003cscript\\u003ealert(1)");
    }

    @Test
    void aTitleIsEscapedForHtml() {
        SeoPage hostile = new SeoPage(200, "</title><script>alert(1)</script>", "\"><img src=x onerror=y>",
                null, false, null, List.of(), "");

        String html = renderer.render("<html><head><title>x</title></head><body><div id=\"root\"></div></body></html>", hostile);

        assertThat(html).doesNotContain("<script>alert").doesNotContain("<img src=x");
    }
}
