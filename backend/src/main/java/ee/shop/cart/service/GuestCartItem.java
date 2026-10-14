package ee.shop.cart.service;

import java.util.UUID;

/** One line of a guest cart as stored in Redis (see GuestCartStore). */
public record GuestCartItem(UUID productId, int quantity) {}
