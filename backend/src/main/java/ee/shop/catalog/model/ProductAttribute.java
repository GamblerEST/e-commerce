package ee.shop.catalog.model;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * EAV row for one facet value (e.g. color=Navy). See V3__catalog.sql for why
 * this is EAV rather than fixed columns: facets are open-ended and vary by
 * category.
 */
@Entity
@Table(name = "product_attributes")
public class ProductAttribute {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "attribute_name", nullable = false)
    private String attributeName;

    @Column(name = "attribute_value", nullable = false)
    private String attributeValue;

    protected ProductAttribute() {} // JPA

    public ProductAttribute(UUID productId, String attributeName, String attributeValue) {
        this.productId = productId;
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
    }

    public UUID getProductId() { return productId; }
    public String getAttributeName() { return attributeName; }
    public String getAttributeValue() { return attributeValue; }
}
