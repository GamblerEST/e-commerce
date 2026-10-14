package ee.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves uploaded product images from an external, writable directory —
 * distinct from the bundled placeholder SVGs under src/main/resources/static/,
 * which are baked into the jar and cannot be written to at runtime. Spring's
 * default classpath static-resource handler still serves those; this
 * registration only adds the /product-images/uploads/** path on top of it.
 */
@Configuration
public class ProductImageStorageConfig implements WebMvcConfigurer {

    private final String productImagesDir;

    public ProductImageStorageConfig(@Value("${app.uploads.product-images-dir}") String productImagesDir) {
        this.productImagesDir = productImagesDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/product-images/uploads/**")
                .addResourceLocations("file:" + productImagesDir + "/");
    }
}
