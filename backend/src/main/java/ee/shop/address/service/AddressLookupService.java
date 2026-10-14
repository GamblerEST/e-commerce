package ee.shop.address.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import ee.shop.address.web.dto.AddressSuggestion;
import ee.shop.common.error.AppException;
import ee.shop.common.error.FieldValidationException;
import ee.shop.config.AddressLookupProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Estonian address register (ADS), through Maa- ja Ruumiamet's public
 * In-AKS gazetteer.
 *
 * Two jobs, one source. {@link #search} feeds the "find your address" box on the
 * checkout and address-book forms. {@link #verifySelected} re-checks an address
 * the customer picked from it when the form is submitted, because a register id
 * arriving from a browser proves nothing on its own.
 *
 * Typed addresses stay valid. Estonian rural addresses are often a farm name
 * with no street or number ("Männiku talu"), and plenty of customers will simply
 * type. The register improves accuracy for everyone who uses it and blocks no
 * one who does not.
 */
@Service
public class AddressLookupService {

    private static final Logger log = LoggerFactory.getLogger(AddressLookupService.class);

    /**
     * The register matches house numbers by prefix, not exact value: searching
     * "Mustamäe tee 5" also returns "50", "51", "54"... because they all start
     * with "5". A dense street can have well over a dozen such matches before its
     * genuine siblings ("5a", "5b") appear, so this stays generous rather than
     * the tighter 8 it was — that number was silently dropping real building
     * variants on streets like this one (verified against the live register).
     * The register itself allows up to 100.
     */
    static final int MAX_RESULTS = 20;

    private static final String ESTONIA = "EE";

    private static final String UNAVAILABLE =
            "Address search is not available right now. Please type your address in.";
    private static final String NOT_IN_REGISTER =
            "This does not match the address picked from the Estonian address register. "
                    + "Pick it again, or type your address in.";
    private static final String POSTAL_MISMATCH =
            "This postal code does not match the address picked from the register.";

    private final AddressLookupProperties props;
    private final RestClient http;

    public AddressLookupService(AddressLookupProperties props, RestClient.Builder builder) {
        this.props = props;
        // Explicit timeouts. The default is to wait indefinitely, and a customer
        // typing an address should be told to type it in within seconds, not
        // watch a box that never answers.
        Duration timeout = props.timeout() != null ? props.timeout() : Duration.ofSeconds(3);
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(timeout);
        requests.setReadTimeout(timeout);
        this.http = builder.requestFactory(requests).build();
    }

    public List<AddressSuggestion> search(String query) {
        if (!props.isEnabled()) {
            throw AppException.unavailable(UNAVAILABLE);
        }
        // The search text goes in as a URI template variable so it is encoded
        // strictly: '&', '=' and '+' become part of the search term instead of
        // breaking the query string or turning into spaces.
        URI uri = UriComponentsBuilder.fromUriString(props.gazetteerUrl())
                .queryParam("address", "{address}")
                .queryParam("results", MAX_RESULTS)
                // The register's own spelling. 1 lets a flat number typed after the
                // house number ("Pikk 1-5") resolve to that flat's kort_nr; a query
                // with no flat number behaves identically to 0 either way (verified
                // against the live register: same buildings, same order, kort_nr
                // empty), so this is a strict improvement, not a behaviour change
                // for the common case.
                .queryParam("appartment", 1)
                .queryParam("ihist", 0)        // current addresses only
                .queryParam("tech", 0)         // no technical placeholder addresses
                .queryParam("iTappAsendus", 1) // letters typed without diacritics still match
                .encode()
                .buildAndExpand(query)
                .toUri();
        return fetch(uri).stream()
                .filter(a -> notBlank(a.aadresstekst()))
                .map(AddressLookupService::toSuggestion)
                .toList();
    }

    /**
     * Re-checks an address the customer picked from the suggestions.
     *
     * The form sends the register id alongside the fields it filled in; this
     * confirms those fields still describe that register entry. One id can carry
     * several addresses (a corner building answers to every street it stands on,
     * each with its own postal code), so a match against any of them counts.
     *
     * No id means a typed address, which is not checked here at all. And if the
     * register cannot be reached the address is accepted as typed: an outage of a
     * government service must not stop a customer paying, and the fields have
     * already passed ordinary validation.
     *
     * @param line1Field the name of the street field in the calling form, so the
     *                   error lands on the input the customer has to fix
     */
    public void verifySelected(String adsOid, String line1, String postalCode, String country,
                               String line1Field) {
        if (!notBlank(adsOid)) {
            return;
        }
        if (!ESTONIA.equalsIgnoreCase(country)) {
            throw new FieldValidationException("country",
                    "An address from the Estonian address register must be in Estonia (EE).");
        }
        if (!props.isEnabled()) {
            return;
        }

        List<GazetteerAddress> entries;
        try {
            URI uri = UriComponentsBuilder.fromUriString(props.gazetteerUrl())
                    .queryParam("adsoid", "{adsoid}")
                    .encode()
                    .buildAndExpand(adsOid)
                    .toUri();
            entries = fetch(uri);
        } catch (AppException unavailable) {
            log.warn("Accepted a picked address as typed: the address register was unreachable");
            return;
        }

        if (entries.isEmpty()) {
            throw new FieldValidationException(line1Field, NOT_IN_REGISTER);
        }

        String typedLine = normalise(line1);
        String typedPostal = postalCode == null ? "" : postalCode.trim();
        boolean streetMatched = false;
        for (GazetteerAddress entry : entries) {
            if (!sameAddress(typedLine, normalise(entry.aadresstekst()))) {
                continue;
            }
            streetMatched = true;
            // Some register entries carry no postal code; there is nothing to
            // compare against, so the street match has to be enough.
            if (!notBlank(entry.sihtnumber()) || entry.sihtnumber().trim().equals(typedPostal)) {
                return;
            }
        }
        if (streetMatched) {
            throw new FieldValidationException("postalCode", POSTAL_MISMATCH);
        }
        throw new FieldValidationException(line1Field, NOT_IN_REGISTER);
    }

    /**
     * The typed street line is the register's address, optionally followed by
     * an apartment. "Pikk tn 1-5" is the picked building plus a flat; "Pikk tn 12"
     * is a different building that happens to start with the same characters, so
     * what follows the register's text must not continue the house number.
     */
    static boolean sameAddress(String typed, String registered) {
        if (registered.isEmpty() || !typed.startsWith(registered)) {
            return false;
        }
        return typed.length() == registered.length()
                || !Character.isLetterOrDigit(typed.charAt(registered.length()));
    }

    static AddressSuggestion toSuggestion(GazetteerAddress a) {
        String label = notBlank(a.ipikkaadress()) ? a.ipikkaadress() : a.pikkaadress();
        return new AddressSuggestion(a.adsOid(), label, a.aadresstekst(), cityOf(a),
                a.sihtnumber() == null ? "" : a.sihtnumber(), ESTONIA,
                a.kortNr() == null ? "" : a.kortNr(), apartmentOptionsOf(a));
    }

    /**
     * The register lists every flat registered in a building right on the
     * building's own entry - no separate lookup needed. Sorted numerically where
     * the label is a plain number (nearly always), falling back to text order for
     * the rare non-numeric one, so a picker reads "1, 2, 3..." rather than
     * whatever order the register happened to return.
     */
    private static List<String> apartmentOptionsOf(GazetteerAddress a) {
        if (a.appartments() == null) {
            return List.of();
        }
        return a.appartments().stream()
                .map(GazetteerApartment::tahis)
                .filter(AddressLookupService::notBlank)
                .map(String::trim)
                .sorted(Comparator.comparing(AddressLookupService::numericSortKey)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** Integer.MAX_VALUE pushes anything non-numeric after every plain number. */
    private static int numericSortKey(String tahis) {
        try {
            return Integer.parseInt(tahis);
        } catch (NumberFormatException notPlainNumber) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * The register describes a place as municipality plus settlement; the forms
     * have one city field. Real register entries decide the mapping:
     *
     *  - "Tallinn" in "Kesklinna linnaosa" becomes "Tallinn". The district adds
     *    nothing the postal code does not already say.
     *  - "Tartu linn", "Narva linn" become "Tartu", "Narva". A town is its own
     *    municipality, and the suffix is register vocabulary, not how anyone
     *    writes an address.
     *  - "Männiku küla" in "Märjamaa vald" becomes "Männiku küla, Märjamaa vald".
     *    In a rural municipality the village is where the parcel goes, and village
     *    names repeat across municipalities, so both are needed.
     */
    static String cityOf(GazetteerAddress a) {
        String municipality = a.omavalitsus() == null ? "" : a.omavalitsus().trim();
        String settlement = a.asustusyksus() == null ? "" : a.asustusyksus().trim();
        if (municipality.endsWith(" vald")) {
            return settlement.isEmpty() ? municipality : settlement + ", " + municipality;
        }
        if (municipality.endsWith(" linn")) {
            return municipality.substring(0, municipality.length() - " linn".length());
        }
        return municipality.isEmpty() ? settlement : municipality;
    }

    private List<GazetteerAddress> fetch(URI uri) {
        try {
            GazetteerResponse body = http.get().uri(uri).retrieve().body(GazetteerResponse.class);
            // The register leaves the "addresses" key out entirely when nothing
            // matches, rather than sending an empty list.
            return body == null || body.addresses() == null ? List.of() : body.addresses();
        } catch (RestClientException e) {
            // The exception's own message includes the full request URL, and that
            // URL contains the address being searched for: personal data, which
            // never goes into a log. The class name is enough to tell a timeout
            // from a refused connection.
            log.warn("Address register request failed: {}", e.getClass().getSimpleName());
            throw AppException.unavailable(UNAVAILABLE);
        }
    }

    private static String normalise(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // The register sends many more fields than these. Unknown fields are ignored
    // here explicitly because the application rejects unknown JSON fields by
    // default, and that setting applies to responses read by this client too.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GazetteerResponse(List<GazetteerAddress> addresses) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GazetteerAddress(
            @JsonProperty("ads_oid") String adsOid,
            String aadresstekst,
            String ipikkaadress,
            String pikkaadress,
            String omavalitsus,
            String asustusyksus,
            String sihtnumber,
            // Set only when "appartment=1" and the search text named a flat the
            // register could resolve, e.g. the "-5" in "Pikk 1-5".
            @JsonProperty("kort_nr") String kortNr,
            // Every flat registered in this building, present whenever the building
            // has any (independent of the "appartment" query flag - verified
            // against the live register). Absent, not empty, when there are none.
            List<GazetteerApartment> appartments
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GazetteerApartment(String tahis) {}
}
