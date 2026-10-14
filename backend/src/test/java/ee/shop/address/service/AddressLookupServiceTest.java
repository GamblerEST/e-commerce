package ee.shop.address.service;

import ee.shop.address.web.dto.AddressSuggestion;
import ee.shop.common.error.AppException;
import ee.shop.common.error.FieldValidationException;
import ee.shop.config.AddressLookupProperties;
import ee.shop.support.GazetteerStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Against responses captured from the real register, served locally.
 *
 * Two halves. The mapping from register fields onto a delivery form, where a
 * quietly wrong city or postal code would send a parcel somewhere else. And the
 * re-check of a picked address, which is attacked directly: a genuine register
 * id with a changed house number must not pass.
 */
@Tag("unit")
@Tag("security")
class AddressLookupServiceTest {

    private GazetteerStub register;
    private AddressLookupService lookup;

    @BeforeEach
    void startRegister() {
        register = GazetteerStub.start();
        lookup = serviceAt(register.url());
    }

    @AfterEach
    void stopRegister() {
        register.stop();
    }

    private static AddressLookupService serviceAt(String url) {
        return new AddressLookupService(
                new AddressLookupProperties(url, Duration.ofSeconds(2)), RestClient.builder());
    }

    /** A port nothing listens on: the register being down, reproduced exactly. */
    private static String unreachableUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://localhost:" + socket.getLocalPort() + "/gazetteer";
        }
    }

    // ── mapping register fields onto the form ────────────────────────────

    @Test
    void aSuggestionCarriesFormReadyFieldsAndTheRegisterId() {
        AddressSuggestion first = lookup.search("Pikk 1").getFirst();

        assertThat(first.line1()).isEqualTo("Pikk tn 1");
        assertThat(first.city()).isEqualTo("Tallinn");
        assertThat(first.postalCode()).isEqualTo("10123");
        assertThat(first.country()).isEqualTo("EE");
        assertThat(first.adsOid()).isEqualTo("EE00658009");
        assertThat(first.label()).isEqualTo("Pikk tn 1, Kesklinna linnaosa, Tallinn, Harju maakond");
    }

    @Test
    void aTownDropsTheRegistersLinnSuffix() {
        List<AddressSuggestion> pikk = lookup.search("Pikk 1");

        assertThat(pikk).extracting(AddressSuggestion::city).contains("Narva", "Pärnu");
        assertThat(lookup.search("Ülikooli 18 Tartu").getFirst().city()).isEqualTo("Tartu");
    }

    /**
     * Farmsteads have no street and no number, and village names repeat across
     * municipalities, so the city field has to name both or the parcel has more
     * than one plausible destination.
     */
    @Test
    void aRuralAddressNamesTheVillageAndTheMunicipality() {
        AddressSuggestion farm = lookup.search("Männiku talu").getFirst();

        assertThat(farm.line1()).isEqualTo("Männiku talu");
        assertThat(farm.city()).isEqualTo("Männiku küla, Märjamaa vald");
        assertThat(farm.postalCode()).isEqualTo("78117");
    }

    /** The register leaves out the "addresses" key entirely when nothing matches. */
    @Test
    void noMatchesIsAnEmptyListNotAnError() {
        assertThat(lookup.search("qqqzzzxxx")).isEmpty();
    }

    /**
     * "Pikk tn 1" in Tallinn carries two registered flats, "15" and "16"
     * (search-pikk-1.json). A form offers these as a pick list rather than
     * asking a customer to already know how the register numbers them.
     */
    @Test
    void aBuildingWithRegisteredFlatsListsThemAsOptions() {
        AddressSuggestion tallinnPikk1 = lookup.search("Pikk 1").getFirst();

        assertThat(tallinnPikk1.apartmentOptions()).containsExactly("15", "16");
    }

    /**
     * Narva's "Pikk tn 1" in the same response has no flats on record at all
     * (no "appartments" key), unlike its Tallinn namesake above.
     */
    @Test
    void aBuildingWithNoRegisteredFlatsHasNoOptions() {
        AddressSuggestion narvaPikk1 = lookup.search("Pikk 1").stream()
                .filter(a -> a.city().equals("Narva"))
                .findFirst().orElseThrow();

        assertThat(narvaPikk1.apartmentOptions()).isEmpty();
    }

    /**
     * Rakvere's "Pikk tn 1" (also in search-pikk-1.json) registers flat "8" out
     * of numeric order - it arrives last in the register's own array, after "16".
     * A customer scanning a pick list expects ascending order regardless.
     */
    @Test
    void apartmentOptionsAreSortedNumericallyNotInRegisterOrder() {
        AddressSuggestion rakverePikk1 = lookup.search("Pikk 1").stream()
                // Rakvere also has a "Pikk tn 1/1" at the same postal code - match
                // the plain building specifically, not its sub-building neighbour.
                .filter(a -> a.line1().equals("Pikk tn 1") && a.postalCode().equals("44307"))
                .findFirst().orElseThrow();

        assertThat(rakverePikk1.apartmentOptions())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                        "11", "12", "13", "14", "15", "16");
    }

    /**
     * A flat number typed straight after the house number ("5-12") resolves to
     * that flat directly, collapsed onto the single matching building - the
     * register does this itself; the client only has to ask for it.
     */
    @Test
    void aTypedApartmentSuffixResolvesToThatFlat() {
        AddressSuggestion resolved = lookup.search("Mustamäe tee 5-12").getFirst();

        assertThat(resolved.line1()).isEqualTo("Mustamäe tee 5");
        assertThat(resolved.apartment()).isEqualTo("12");
    }

    /** No suffix typed, no flat resolved - the common case stays unresolved. */
    @Test
    void aSearchWithNoApartmentSuffixLeavesApartmentUnresolved() {
        assertThat(lookup.search("Pikk 1").getFirst().apartment()).isEmpty();
    }

    @Test
    void reservedAndNonAsciiCharactersArriveAsOneIntactSearchTerm() {
        lookup.search("Ülikooli 18 & Tartu+1");

        String raw = register.rawQueries().getLast();
        assertThat(raw).contains("address=%C3%9Clikooli%2018%20%26%20Tartu%2B1");
        assertThat(GazetteerStub.parse(raw)).containsEntry("address", "Ülikooli 18 & Tartu+1");
    }

    @Test
    void anUnreachableRegisterIsA503AndTheMessageDoesNotEchoTheAddress() throws IOException {
        AddressLookupService down = serviceAt(unreachableUrl());

        assertThatThrownBy(() -> down.search("Pikk 1"))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("Pikk");
                });
    }

    // ── re-checking a picked address ─────────────────────────────────────

    @Test
    void aPickedAddressThatMatchesTheRegisterIsAccepted() {
        assertThatCode(() -> lookup.verifySelected("EE00658009", "Pikk tn 1", "10123", "EE", "addressLine1"))
                .doesNotThrowAnyException();
    }

    /**
     * One register id can carry several addresses. This corner building is
     * Pikk tn 1 (10123), and also Lai tn 2 and Nunne tn 2 (both 10133). A customer
     * at any of them lives at a real address, so matching only the first entry
     * would turn real people away.
     */
    @Test
    void anyAddressOfACornerBuildingIsAccepted() {
        assertThatCode(() -> lookup.verifySelected("EE00658009", "Lai tn 2", "10133", "EE", "addressLine1"))
                .doesNotThrowAnyException();
    }

    @Test
    void anApartmentAfterThePickedAddressIsAccepted() {
        assertThatCode(() -> lookup.verifySelected("EE00658009", "Pikk tn 1-5", "10123", "EE", "addressLine1"))
                .doesNotThrowAnyException();
    }

    /**
     * The attack this check exists for: keep a genuine register id, change the
     * house number. "Pikk tn 12" also starts with "Pikk tn 1", which is exactly
     * why a plain prefix match would have let it through.
     */
    @Test
    void aChangedHouseNumberUnderARealRegisterIdIsRejected() {
        assertThatThrownBy(() -> lookup.verifySelected("EE00658009", "Pikk tn 12", "10123", "EE", "addressLine1"))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("addressLine1"));
    }

    @Test
    void aPostalCodeBelongingToAnotherAddressIsRejectedOnThePostalCodeField() {
        // 10133 is a real postal code of this same building, but for Lai tn 2.
        assertThatThrownBy(() -> lookup.verifySelected("EE00658009", "Pikk tn 1", "10133", "EE", "addressLine1"))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("postalCode"));
    }

    @Test
    void aRegisterIdTheRegisterDoesNotKnowIsRejected() {
        assertThatThrownBy(() -> lookup.verifySelected("EE99999999", "Pikk tn 1", "10123", "EE", "line1"))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("line1"));
    }

    @Test
    void aRegisterIdWithACountryOtherThanEstoniaIsRejected() {
        assertThatThrownBy(() -> lookup.verifySelected("EE00658009", "Pikk tn 1", "10123", "FI", "addressLine1"))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("country"));
    }

    @Test
    void aTypedAddressWithoutARegisterIdIsNotLookedUp() {
        lookup.verifySelected(null, "Männiku talu", "78117", "EE", "addressLine1");

        assertThat(register.rawQueries()).isEmpty();
    }

    /**
     * The deliberate trade-off, pinned down: while the register is unreachable a
     * picked address is accepted as typed, even a wrong one, because an outage of
     * a government service must not stop anyone paying. The fields have still
     * passed ordinary validation.
     */
    @Test
    void whileTheRegisterIsDownAPickedAddressIsAcceptedAsTyped() throws IOException {
        AddressLookupService down = serviceAt(unreachableUrl());

        assertThatCode(() -> down.verifySelected("EE00658009", "Pikk tn 12", "10123", "EE", "addressLine1"))
                .doesNotThrowAnyException();
    }
}
