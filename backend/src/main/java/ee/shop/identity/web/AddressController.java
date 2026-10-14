package ee.shop.identity.web;

import ee.shop.address.service.AddressLookupService;
import ee.shop.common.error.AppException;
import ee.shop.identity.model.User;
import ee.shop.identity.model.UserAddress;
import ee.shop.identity.repository.UserAddressRepository;
import ee.shop.identity.web.dto.AddressRequest;
import ee.shop.identity.web.dto.AddressResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * A customer's saved delivery addresses.
 *
 * Every method resolves the address by id AND the authenticated user's id
 * together, so another customer's address is a 404 rather than a 403 — telling
 * someone an id exists but is not theirs is itself a small leak.
 */
@RestController
@RequestMapping("/api/account/addresses")
@Tag(name = "Account")
public class AddressController {

    /** Enough for a person; low enough that the endpoint cannot be used as storage. */
    private static final long MAX_PER_USER = 20;

    private final UserAddressRepository addresses;
    private final AddressLookupService addressLookup;

    public AddressController(UserAddressRepository addresses, AddressLookupService addressLookup) {
        this.addresses = addresses;
        this.addressLookup = addressLookup;
    }

    private static UUID callerId(Authentication authentication) {
        return ((User) authentication.getPrincipal()).getId();
    }

    @GetMapping
    @Operation(summary = "List your saved addresses, default first")
    public List<AddressResponse> list(Authentication authentication) {
        return addresses.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(callerId(authentication))
                .stream().map(AddressResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    @Operation(summary = "Save a new address")
    public AddressResponse create(@Valid @RequestBody AddressRequest req,
                                   Authentication authentication) {
        UUID userId = callerId(authentication);

        if (addresses.countByUserId(userId) >= MAX_PER_USER) {
            throw AppException.badRequest(
                    "You can save up to " + MAX_PER_USER + " addresses. Delete one first.");
        }

        addressLookup.verifySelected(req.adsOid(), req.line1(), req.postalCode(), req.country(), "line1");
        UserAddress address = new UserAddress(userId);
        apply(address, req);

        // The first address a customer saves becomes the default whether they
        // asked or not — otherwise checkout has a set of addresses and no idea
        // which to offer.
        boolean isFirst = addresses.countByUserId(userId) == 0;
        if (req.makeDefault() || isFirst) {
            clearExistingDefault(userId);
            address.setDefaultAddress(true);
        }
        return AddressResponse.of(addresses.save(address));
    }

    @PutMapping("/{id}")
    @Transactional
    @Operation(summary = "Update one of your addresses")
    public AddressResponse update(@PathVariable UUID id, @Valid @RequestBody AddressRequest req,
                                   Authentication authentication) {
        UUID userId = callerId(authentication);
        UserAddress address = addresses.findByIdAndUserId(id, userId)
                .orElseThrow(() -> AppException.notFound("Address not found"));

        addressLookup.verifySelected(req.adsOid(), req.line1(), req.postalCode(), req.country(), "line1");
        apply(address, req);
        if (req.makeDefault() && !address.isDefaultAddress()) {
            clearExistingDefault(userId);
            address.setDefaultAddress(true);
        }
        return AddressResponse.of(addresses.save(address));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "Delete one of your addresses")
    public void delete(@PathVariable UUID id, Authentication authentication) {
        UUID userId = callerId(authentication);
        UserAddress address = addresses.findByIdAndUserId(id, userId)
                .orElseThrow(() -> AppException.notFound("Address not found"));

        boolean wasDefault = address.isDefaultAddress();
        addresses.delete(address);

        // Deleting the default must not leave the customer with addresses and
        // no default — checkout would then have nothing to preselect.
        if (wasDefault) {
            addresses.findByUserIdOrderByDefaultAddressDescCreatedAtDesc(userId).stream()
                    .findFirst()
                    .ifPresent(next -> {
                        next.setDefaultAddress(true);
                        addresses.save(next);
                    });
        }
    }

    /**
     * The partial unique index (V22) allows only one default per user, so the
     * old one must be cleared and FLUSHED before the new one is set — leaving
     * both true for even an instant inside the transaction trips the
     * constraint.
     */
    private void clearExistingDefault(UUID userId) {
        addresses.findByUserIdAndDefaultAddressIsTrue(userId).ifPresent(current -> {
            current.setDefaultAddress(false);
            addresses.saveAndFlush(current);
        });
    }

    private static void apply(UserAddress address, AddressRequest req) {
        address.apply(req.label(), req.recipientName(),
                req.phone() == null || req.phone().isBlank() ? null : req.phone(),
                req.line1(),
                req.line2() == null || req.line2().isBlank() ? null : req.line2(),
                req.city(), req.postalCode(), req.country());
    }
}
