package ee.shop.common.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Transparent AES-GCM for a String column: {@code @Convert(converter =
 * EncryptedStringConverter.class)} on the field is all a caller writes.
 *
 * A JPA converter rather than encrypting in the service layer, because the
 * guarantee has to hold on EVERY path into the table. A service-layer
 * approach protects the paths somebody remembered — and the one write added
 * next year, by someone who did not know the rule, stores plaintext with no
 * error and no test failure. Here, the column simply cannot be written in
 * clear.
 *
 * Registered as a Spring @Component so the FieldCipher (and therefore the
 * key) can be injected: Hibernate resolves converters through Spring's bean
 * container under Boot, so this is constructor-injected like anything else.
 *
 * Consequence worth stating plainly: an encrypted column CANNOT be used in a
 * WHERE clause, an ORDER BY, or a unique index. The IV is random, so the same
 * input produces different ciphertext every time. Anything that must be
 * searched needs a blind index alongside, not this converter.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final FieldCipher cipher;

    public EncryptedStringConverter(FieldCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
