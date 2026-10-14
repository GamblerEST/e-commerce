package ee.shop.address.web;

import ee.shop.address.service.AddressLookupService;
import ee.shop.address.web.dto.AddressSuggestion;
import ee.shop.common.error.AppException;
import ee.shop.common.security.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Address search for the checkout and address-book forms.
 *
 * Proxied through this server rather than called from the browser, even though
 * the register would accept browser calls:
 *  - the customer's IP address and the address they are typing reach this shop,
 *    not a third party;
 *  - the Content-Security-Policy keeps connect-src at 'self';
 *  - the same service verifies a picked address when the form is submitted, so
 *    suggesting and checking read one source.
 */
@RestController
@RequestMapping("/api/addresses")
@Tag(name = "Addresses")
public class AddressLookupController {

    static final int MIN_QUERY = 3;
    static final int MAX_QUERY = 120;

    private final AddressLookupService lookup;
    private final RateLimitService rateLimit;

    public AddressLookupController(AddressLookupService lookup, RateLimitService rateLimit) {
        this.lookup = lookup;
        this.rateLimit = rateLimit;
    }

    @GetMapping("/search")
    @Operation(summary = "Search the Estonian address register")
    public List<AddressSuggestion> search(@RequestParam(defaultValue = "") String q,
                                          HttpServletRequest request) {
        String term = q.trim();
        if (term.length() > MAX_QUERY) {
            throw AppException.badRequest("Search text is too long");
        }
        if (term.length() < MIN_QUERY) {
            // Still typing. Not an error, and not worth a call to the register.
            return List.of();
        }
        rateLimit.enforceAddressLookup(request.getRemoteAddr());
        return lookup.search(term);
    }
}
