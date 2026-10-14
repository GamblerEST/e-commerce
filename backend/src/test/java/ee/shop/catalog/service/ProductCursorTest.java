package ee.shop.catalog.service;

import ee.shop.common.error.AppException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests — no Spring context needed. Keyset pagination correctness
 * depends entirely on this round-tripping exactly: a
 * cursor that silently corrupts a value would show up downstream as
 * skipped/repeated pagination results, not as an obvious failure here.
 */
@Tag("unit")
class ProductCursorTest {

    @Test
    void roundTrips_numericSortValue() {
        UUID id = UUID.randomUUID();
        ProductCursor original = new ProductCursor("1099.00", id);

        ProductCursor decoded = ProductCursor.decode(original.encode());

        assertThat(decoded.sortValue()).isEqualTo("1099.00");
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void roundTrips_textSortValue() {
        UUID id = UUID.randomUUID();
        ProductCursor original = new ProductCursor("Vantage 14 Ultrabook", id);

        ProductCursor decoded = ProductCursor.decode(original.encode());

        assertThat(decoded.sortValue()).isEqualTo("Vantage 14 Ultrabook");
        assertThat(decoded.id()).isEqualTo(id);
    }

    /**
     * decode() splits on the LAST '|', so a sort value that itself contains
     * one (e.g. a product name someone typed with a pipe in it) must not
     * corrupt the id.
     */
    @Test
    void sortValueContainingPipe_stillDecodesCorrectId() {
        UUID id = UUID.randomUUID();
        ProductCursor original = new ProductCursor("Salt & Pepper | Gift Set", id);

        ProductCursor decoded = ProductCursor.decode(original.encode());

        assertThat(decoded.sortValue()).isEqualTo("Salt & Pepper | Gift Set");
        assertThat(decoded.id()).isEqualTo(id);
    }

    @Test
    void decode_ofGarbageBase64_throwsBadRequest() {
        assertThatThrownBy(() -> ProductCursor.decode("not-valid-base64-!!!"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void decode_ofValidBase64ButNoPipeSeparator_throwsBadRequest() {
        String noPipe = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("justsometext".getBytes());

        assertThatThrownBy(() -> ProductCursor.decode(noPipe))
                .isInstanceOf(AppException.class);
    }

    @Test
    void decode_ofValidBase64WithMalformedUuid_throwsBadRequest() {
        String badUuid = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("129.00|not-a-uuid".getBytes());

        assertThatThrownBy(() -> ProductCursor.decode(badUuid))
                .isInstanceOf(AppException.class);
    }

    @Test
    void decode_ofEmptyString_throwsBadRequest() {
        assertThatThrownBy(() -> ProductCursor.decode(""))
                .isInstanceOf(AppException.class);
    }
}
