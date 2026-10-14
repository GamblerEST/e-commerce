package ee.shop.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AdminCategoryRequest(
        /** Null for a top-level category. Categories are self-referential (V3__catalog.sql). */
        UUID parentId,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 120)
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "must be lowercase letters, digits and hyphens") String slug,

        /**
         * Promote this category to the home page as a "collection"
         * (V18__home_featured.sql). Boxed for the same reason as
         * AdminProductRequest.featured — a primitive would make an omitted
         * field a hard 400 for every existing caller. Null means not promoted.
         */
        Boolean featured,

        /** Optional marketing line shown under the collection title. */
        @Size(max = 200) String tagline
) {}
