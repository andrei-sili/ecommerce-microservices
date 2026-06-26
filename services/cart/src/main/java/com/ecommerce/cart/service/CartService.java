package com.ecommerce.cart.service;

import com.ecommerce.cart.client.ProductClient;
import com.ecommerce.cart.client.ProductSnapshot;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.exception.NotFoundException;
import com.ecommerce.cart.exception.UnprocessableEntityException;
import com.ecommerce.cart.model.Cart;
import com.ecommerce.cart.model.CartItem;
import com.ecommerce.cart.repository.CartRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CartService {

  private static final int MAX_QUANTITY = 100;

  private final CartRepository cartRepository;
  private final ProductClient productClient;

  public CartService(CartRepository cartRepository, ProductClient productClient) {
    this.cartRepository = cartRepository;
    this.productClient = productClient;
  }

  @Transactional
  public CartResponse getCart(long userId) {
    return CartResponse.from(getOrCreateCart(userId));
  }

  @Transactional
  public CartResponse addItem(long userId, long productId, int quantity, String bearerToken) {
    Cart cart = getOrCreateCart(userId);
    ProductSnapshot snapshot = productClient.fetchAvailableProduct(productId, bearerToken);

    CartItem existing = cart.findItem(productId).orElse(null);
    if (existing != null) {
      int newQuantity = existing.getQuantity() + quantity;
      if (newQuantity > MAX_QUANTITY) {
        throw new UnprocessableEntityException(
            "QUANTITY_LIMIT_EXCEEDED", "Item quantity cannot exceed " + MAX_QUANTITY);
      }
      existing.setQuantity(newQuantity);
      existing.applySnapshot(snapshot.unitPrice(), snapshot.currency(), snapshot.productName());
    } else {
      enforceSingleCurrency(cart, snapshot.currency());
      cart.addItem(
          new CartItem(
              productId,
              quantity,
              snapshot.unitPrice(),
              snapshot.currency(),
              snapshot.productName()));
    }
    return CartResponse.from(save(cart));
  }

  @Transactional
  public CartResponse setItemQuantity(
      long userId, long productId, int quantity, String bearerToken) {
    Cart cart = getOrCreateCart(userId);
    CartItem item =
        cart.findItem(productId)
            .orElseThrow(
                () -> new NotFoundException("CART_ITEM_NOT_FOUND", "Item is not in the cart"));

    ProductSnapshot snapshot = productClient.fetchAvailableProduct(productId, bearerToken);
    item.setQuantity(quantity);
    item.applySnapshot(snapshot.unitPrice(), snapshot.currency(), snapshot.productName());
    return CartResponse.from(save(cart));
  }

  @Transactional
  public void removeItem(long userId, long productId) {
    cartRepository
        .findByUserId(userId)
        .ifPresent(
            cart ->
                cart.findItem(productId)
                    .ifPresent(
                        item -> {
                          cart.removeItem(item);
                          save(cart);
                        }));
  }

  @Transactional
  public void clearCart(long userId) {
    cartRepository
        .findByUserId(userId)
        .ifPresent(
            cart -> {
              if (!cart.getItems().isEmpty()) {
                cart.clearItems();
                save(cart);
              }
            });
  }

  private Cart getOrCreateCart(long userId) {
    return cartRepository.findByUserId(userId).orElseGet(() -> createCart(userId));
  }

  private Cart createCart(long userId) {
    try {
      return cartRepository.saveAndFlush(new Cart(userId));
    } catch (DataIntegrityViolationException ex) {
      // A concurrent request created the cart first (user_id is UNIQUE); reuse it.
      return cartRepository
          .findByUserId(userId)
          .orElseThrow(() -> new NotFoundException("CART_NOT_FOUND", "Cart could not be resolved"));
    }
  }

  private void enforceSingleCurrency(Cart cart, String incomingCurrency) {
    cart.currency()
        .filter(existing -> !existing.equalsIgnoreCase(incomingCurrency))
        .ifPresent(
            existing -> {
              throw new UnprocessableEntityException(
                  "MIXED_CURRENCY_CART", "All cart items must share a single currency");
            });
  }

  private Cart save(Cart cart) {
    return cartRepository.save(cart);
  }
}
