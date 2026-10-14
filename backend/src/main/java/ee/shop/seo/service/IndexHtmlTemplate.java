package ee.shop.seo.service;

import ee.shop.config.SeoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The current frontend build's index.html.
 *
 * Read from disk rather than bundled into the backend: the file names this
 * build's hashed JavaScript and CSS, so it changes with every frontend build,
 * and the backend has to hand out the same one the proxy is serving assets for.
 * Kept in memory until the file's timestamp or size changes, so a rebuild is
 * picked up without restarting anything.
 */
@Component
public class IndexHtmlTemplate {

    private static final Logger log = LoggerFactory.getLogger(IndexHtmlTemplate.class);

    private record Cached(FileTime modified, long size, String html) {}

    private final Path path;
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile Cached cached;

    public IndexHtmlTemplate(SeoProperties props) {
        this.path = props.indexHtml() == null || props.indexHtml().isBlank() ? null : Path.of(props.indexHtml());
    }

    public Optional<String> html() {
        if (path == null) {
            return Optional.empty();
        }
        try {
            FileTime modified = Files.getLastModifiedTime(path);
            long size = Files.size(path);
            Cached current = cached;
            if (current == null || !current.modified().equals(modified) || current.size() != size) {
                current = new Cached(modified, size, Files.readString(path, StandardCharsets.UTF_8));
                cached = current;
            }
            warned.set(false);
            return Optional.of(current.html());
        } catch (IOException e) {
            // Once per outage, not once per request: a crawler walking the
            // sitemap would otherwise write a warning for every product.
            if (warned.compareAndSet(false, true)) {
                log.warn("Public pages cannot be pre-filled: {} is not readable ({}); "
                        + "answering 503 so the proxy serves the static app", path, e.getClass().getSimpleName());
            }
            return Optional.empty();
        }
    }
}
