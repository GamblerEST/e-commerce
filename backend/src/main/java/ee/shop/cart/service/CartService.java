package ee.shop.cart.service;

import ee.shop.cart.model.Cart;
import ee.shop.cart.model.CartItem;
import ee.shop.cart.repository.CartItemRepository;
import ee.shop.cart.repository.CartRepository;
import ee.shop.cart.web.dto.CartItemResponse;
import ee.shop.cart.web.dto.CartResponse;
import ee.shop.catalog.model.Product;
import ee.shop.catalog.model.ProductImage;
import ee.shop.catalog.repository.ProductImageRepository;
import ee.shop.catalog.repository.ProductRepository;
import ee.shop.common.error.AppException;
import ee.shop.inventory.service.InventoryService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fronts BOTH cart flavours behind one pricing/assembly
 * path: guest carts live in Redis (GuestCartStore), logged-in carts in
 * Postgres (CartRepository/CartItemRepository). Callers (CartController)
 * decide which flavour applies per request based on whether the caller is
 * authenticated; this class never guesses.
 */
@Service
public class CartService {

    /** Clamp applied server-side on every add/merge — see CartItemRepository.upsertAdd's javadoc. */
    public static final int MAX_QUANTITY_PER_ITEM = 99;

    private static final String DEFAULT_CURRENCY = "EUR";

    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final GuestCartStore guestStore;
    private final ProductRepository products;
    private final ProductImageRepository images;
    private final InventoryService inventory;

    public CartService(CartRepository carts, CartItemRepository cartItems, GuestCartStore guestStore,
                        ProductRepository products, ProductImageRepository images, InventoryService inventory) {
        this.carts = carts;
        this.cartItems = cartItems;
        this.guestStore = guestStore;
        this.products = products;
        this.images = images;
        this.inventory = inventory;
    }

    // ── reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CartResponse getUserCart(UUID userId) {
        return price(userCartLines(userId));
    }

    public CartResponse getGuestCart(String token) {
        return price(guestStore.get(token));
    }

    /**
     * Raw (unpriced) lines, for Checkout (OrderService) to re-price and
     * snapshot independently at order-creation time rather than trusting
     * whatever this cart's own price() happened to compute a moment
     * earlier — see OrderLine's javadoc on why pricing is never trusted
     * from anywhere but the live product row at the instant of purchase.
     */
    @Transactional(readOnly = true)
    public List<GuestCartItem> userCartLines(UUID userId) {
        Cart cart = carts.findByUserId(userId).orElse(null);
        return cart == null ? List.of() : toLines(cartItems.findByCartIdOrderByCreatedAt(cart.getId()));
    }

    public List<GuestCartItem> guestCartLines(String token) {
        return guestStore.get(token);
    }

    // ── mutations: authenticated user ────────────────────────────────────

    @Transactional
    public CartResponse addUserItem(UUID userId, UUID productId, int quantity) {
        requireLiveProduct(productId);
        Cart cart = getOrCreateCart(userId);
        int alreadyInCart = cartItems.findByCartIdAndProductId(cart.getId(), productId)
                .map(CartItem::getQuantity).orElse(0);
        requireAvailable(productId, alreadyInCart + quantity);
        cartItems.upsertAdd(cart.getId(), productId, quantity, MAX_QUANTITY_PER_ITEM);
        return getUserCart(userId);
    }

    @Transactional
    public CartResponse setUserItemQuantity(UUID userId, UUID productId, int quantity) {
        Cart cart = carts.findByUserId(userId).orElseThrow(() -> AppException.notFound("Cart item not found"));
        int updated = cartItems.setQuantity(cart.getId(), productId, quantity);
        if (updated == 0) {
            throw AppException.notFound("Cart item not found");
        }
        return getUserCart(userId);
    }

    @Transactional
    public CartResponse removeUserItem(UUID userId, UUID productId) {
        Cart cart = carts.findByUserId(userId).orElseThrow(() -> AppException.notFound("Cart item not found"));
        if (cartItems.deleteByCartIdAndProductId(cart.getId(), productId) == 0) {
            throw AppException.notFound("Cart item not found");
        }
        return getUserCart(userId);
    }

    @Transactional
    public void clearUserCart(UUID userId) {
        carts.findByUserId(userId).ifPresent(cart -> cartItems.deleteByCartId(cart.getId()));
    }

    // ── mutations: guest ──────────────────────────────────────────────────

    public CartResponse addGuestItem(String token, UUID productId, int quantity) {
        requireLiveProduct(productId);
        List<GuestCartItem> lines = new ArrayList<>(guestStore.get(token));
        int idx = indexOf(lines, productId);
        int alreadyInCart = idx >= 0 ? lines.get(idx).quantity() : 0;
        requireAvailable(productId, alreadyInCart + quantity);
        if (idx >= 0) {
            int clamped = Math.min(lines.get(idx).quantity() + quantity, MAX_QUANTITY_PER_ITEM);
            lines.set(idx, new GuestCartItem(productId, clamped));
        } else {
            lines.add(new GuestCartItem(productId, Math.min(quantity, MAX_QUANTITY_PER_ITEM)));
        }
        guestStore.save(token, lines);
        return price(lines);
    }

    public CartResponse setGuestItemQuantity(String token, UUID productId, int quantity) {
        List<GuestCartItem> lines = new ArrayList<>(guestStore.get(token));
        int idx = indexOf(lines, productId);
        if (idx < 0) {
            throw AppException.notFound("Cart item not found");
        }
        lines.set(idx, new GuestCartItem(productId, quantity));
        guestStore.save(token, lines);
        return price(lines);
    }

    public CartResponse removeGuestItem(String token, UUID productId) {
        List<GuestCartItem> lines = new ArrayList<>(guestStore.get(token));
        if (lines.removeIf(l -> l.productId().equals(productId))) {
            guestStore.save(token, lines);
            return price(lines);
        }
        throw AppException.notFound("Cart item not found");
    }

    public void clearGuestCart(String token) {
        guestStore.delete(token);
    }

    // ── guest → user merge, called on login/register (DECISIONS.md-adjacent:
    //    the guest cart is the closest thing to a "session" a guest has) ────

    @Transactional
    public void mergeGuestIntoUser(String guestToken, UUID userId) {
        if (guestToken == null) {
            return;
        }
        List<GuestCartItem> guestLines = guestStore.get(guestToken);
        if (!guestLines.isEmpty()) {
            Cart cart = getOrCreateCart(userId);
            for (GuestCartItem line : guestLines) {
                // A product removed/deleted between "added as guest" and "logged
                // in" silently drops rather than failing the whole login.
                products.findById(line.productId())
                        .filter(p -> !p.isDeleted())
                        .ifPresent(p -> cartItems.upsertAdd(cart.getId(), line.productId(), line.quantity(), MAX_QUANTITY_PER_ITEM));
            }
        }
        guestStore.delete(guestToken);
    }

    // ── shared assembly ───────────────────────────────────────────────────

    private Cart getOrCreateCart(UUID userId) {
        return carts.findByUserId(userId).orElseGet(() -> {
            try {
                return carts.save(new Cart(userId));
            } catch (DataIntegrityViolationException e) {
                // Lost a race with another request creating the same user's
                // first cart — same "atomic guard, not check-then-act" shape
                // as everywhere else in this codebase; the unique index on
                // user_id (V9__cart.sql) is what actually prevents duplicates.
                return carts.findByUserId(userId).orElseThrow();
            }
        });
    }

    private Product requireLiveProduct(UUID productId) {
        return products.findById(productId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> AppException.notFound("Product not found"));
    }

    /**
     * Advisory only (InventoryRepository.availableQuantity's javadoc) — the
     * real oversell guard stays at checkout's atomic reserve(). This just
     * turns "add to cart" into a helpful 409 instead of letting someone
     * stack 50 units of a product with 2 left, only to find out at checkout.
     */
    private void requireAvailable(UUID productId, int requestedTotalQuantity) {
        int available = inventory.availableQuantity(productId);
        if (requestedTotalQuantity > available) {
            throw AppException.conflict(available <= 0
                    ? "This product is out of stock"
                    : "Only " + available + " left in stock");
        }
    }

    private static int indexOf(List<GuestCartItem> lines, UUID productId) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).productId().equals(productId)) {
                return i;
            }
        }
        return -1;
    }

    private static List<GuestCartItem> toLines(List<CartItem> items) {
        return items.stream().map(i -> new GuestCartItem(i.getProductId(), i.getQuantity())).toList();
    }

    /**
     * Resolves each line against the LIVE product row (name/price/thumbnail),
     * silently dropping any product that has since been deleted rather than
     * failing the whole cart view — see CartItem's javadoc for why a cart
     * never snapshots.
     */
    private CartResponse price(List<GuestCartItem> lines) {
        if (lines.isEmpty()) {
            return new CartResponse(List.of(), 0, BigDecimal.ZERO, DEFAULT_CURRENCY);
        }

        Map<UUID, Integer> quantityByProduct = new LinkedHashMap<>();
        for (GuestCartItem line : lines) {
            quantityByProduct.put(line.productId(), line.quantity());
        }

        Map<UUID, Product> found = new HashMap<>();
        for (Product p : products.findAllById(quantityByProduct.keySet())) {
            if (!p.isDeleted()) {
                found.put(p.getId(), p);
            }
        }

        Map<UUID, String> thumbnails = new HashMap<>();
        for (ProductImage img : images.findByProductIdInOrderByDisplayOrder(List.copyOf(found.keySet()))) {
            thumbnails.putIfAbsent(img.getProductId(), "/" + img.getStorageKey());
        }

        List<CartItemResponse> views = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String currency = DEFAULT_CURRENCY;
        int itemCount = 0;

        for (Map.Entry<UUID, Integer> entry : quantityByProduct.entrySet()) {
            Product p = found.get(entry.getKey());
            if (p == null) {
                continue; // deleted since it was added — drop silently
            }
            int qty = entry.getValue();
            BigDecimal lineTotal = p.getPrice().multiply(BigDecimal.valueOf(qty));
            total = total.add(lineTotal);
            currency = p.getCurrency();
            itemCount += qty;
            views.add(new CartItemResponse(p.getId(), p.getName(), p.getSlug(), p.getPrice(), p.getCurrency(),
                    thumbnails.get(p.getId()), qty, lineTotal));
        }

        return new CartResponse(views, itemCount, total, currency);
    }
}
