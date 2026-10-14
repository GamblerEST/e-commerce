package ee.shop.address.web.dto;

import java.util.List;

/**
 * One address from the Estonian address register, already split into the fields
 * the checkout and address-book forms use.
 *
 * @param adsOid           the register's own id for the address. The form sends it back
 *                         on submit so the server can re-check the fields against the
 *                         register instead of trusting them.
 * @param label            the full address as the register writes it, for the suggestion list
 * @param apartment        the flat/apartment number, when the search text named one the
 *                         register could resolve (e.g. "Pikk 1-5" resolves flat "5" of
 *                         "Pikk tn 1"); empty otherwise
 * @param apartmentOptions every flat registered in this building, e.g. ["1", "2", "3"], so
 *                         the form can offer them as a pick list instead of asking the
 *                         customer to already know the register's exact numbering; empty
 *                         when the building has none on record
 */
public record AddressSuggestion(
        String adsOid,
        String label,
        String line1,
        String city,
        String postalCode,
        String country,
        String apartment,
        List<String> apartmentOptions
) {}
