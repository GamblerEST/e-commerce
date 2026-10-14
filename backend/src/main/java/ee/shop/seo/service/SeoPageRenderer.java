package ee.shop.seo.service;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills one page into the built index.html.
 *
 * Every piece of text that reaches the page is escaped for the place it lands:
 * HTML-escaped inside tags and attributes, and JSON with '&lt;', '&gt;' and '&amp;'
 * written as unicode escapes inside structured data. Product names and
 * descriptions are written by staff, and a name containing "&lt;/script&gt;" must
 * stay a name.
 *
 * Tags added here carry a data-page-meta attribute so PageMeta can remove them
 * once the visitor navigates to a page they do not describe.
 */
@Component
public class SeoPageRenderer {

    private static final Pattern TITLE = Pattern.compile("<title>.*?</title>", Pattern.DOTALL);
    private static final Pattern DESCRIPTION = Pattern.compile("<meta\\s+name=\"description\"[^>]*>");
    private static final String EMPTY_ROOT = "<div id=\"root\"></div>";

    private final ObjectMapper json;

    public SeoPageRenderer(ObjectMapper json) {
        this.json = json;
    }

    public String render(String template, SeoPage page) {
        if (!template.contains("</head>") || !template.contains(EMPTY_ROOT)) {
            throw new IllegalStateException("index.html does not have the expected </head> and empty #root");
        }

        String html = template;
        StringBuilder head = new StringBuilder();

        String titleTag = "<title>" + escape(page.title()) + "</title>";
        Matcher title = TITLE.matcher(html);
        if (title.find()) {
            html = title.replaceFirst(Matcher.quoteReplacement(titleTag));
        } else {
            head.append("    ").append(titleTag).append('\n');
        }

        String descriptionTag = "<meta name=\"description\" content=\"" + escape(page.description()) + "\" />";
        Matcher description = DESCRIPTION.matcher(html);
        if (description.find()) {
            html = description.replaceFirst(Matcher.quoteReplacement(descriptionTag));
        } else {
            head.append("    ").append(descriptionTag).append('\n');
        }

        if (page.canonicalUrl() != null) {
            head.append(line("<link data-page-meta rel=\"canonical\" href=\"" + escape(page.canonicalUrl()) + "\" />"));
            head.append(meta("og:url", page.canonicalUrl()));
        }
        head.append(meta("og:title", page.title()));
        head.append(meta("og:description", page.description()));
        head.append(meta("og:type", "website"));
        if (page.imageUrl() != null) {
            head.append(meta("og:image", page.imageUrl()));
        }
        if (page.noindex()) {
            head.append(line("<meta data-page-meta name=\"robots\" content=\"noindex,nofollow\" />"));
        }
        for (Map<String, Object> data : page.structuredData()) {
            head.append(line("<script data-page-meta type=\"application/ld+json\">" + structuredData(data) + "</script>"));
        }

        int headEnd = html.indexOf("</head>");
        html = html.substring(0, headEnd) + head + "  " + html.substring(headEnd);
        return html.replace(EMPTY_ROOT, "<div id=\"root\">" + page.bodyHtml() + "</div>");
    }

    /**
     * '&lt;', '&gt;' and '&amp;' as JSON unicode escapes: the same data to anything reading
     * the JSON, but a "&lt;/script&gt;" inside a value can no longer end the element.
     * Those three characters only ever occur inside JSON strings, so replacing
     * them in the serialised text is safe.
     */
    String structuredData(Map<String, Object> data) {
        return json.writeValueAsString(data)
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026");
    }

    private static String meta(String property, String content) {
        return line("<meta data-page-meta property=\"" + property + "\" content=\"" + escape(content) + "\" />");
    }

    private static String line(String tag) {
        return "  " + tag + "\n";
    }

    static String escape(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text, "UTF-8");
    }
}
