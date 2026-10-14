package ee.shop.seo.service;

import ee.shop.config.SeoProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class IndexHtmlTemplateTest {

    @TempDir
    Path dir;

    /** Empty, so the controller answers 503 and the proxy serves the static app. */
    @Test
    void aMissingBuildIsEmptyRatherThanAnException() {
        IndexHtmlTemplate template = new IndexHtmlTemplate(new SeoProperties(dir.resolve("missing.html").toString()));

        assertThat(template.html()).isEmpty();
    }

    @Test
    void aRebuiltFileIsPickedUpWithoutARestart() throws Exception {
        Path file = dir.resolve("index.html");
        Files.writeString(file, "<html>first build</html>");
        IndexHtmlTemplate template = new IndexHtmlTemplate(new SeoProperties(file.toString()));

        assertThat(template.html()).contains("<html>first build</html>");

        // A different size, so the change is seen even within the file system's
        // timestamp resolution.
        Files.writeString(file, "<html>second, longer build</html>");
        assertThat(template.html()).contains("<html>second, longer build</html>");
    }
}
