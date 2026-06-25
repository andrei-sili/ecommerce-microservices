package com.ecommerce.cart.controller;

import com.ecommerce.cart.dto.AddItemRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.dto.SetQuantityRequest;
import com.ecommerce.cart.security.CurrentUser;
import com.ecommerce.cart.service.CartService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** All endpoints operate strictly on the caller's own cart (ownership from the JWT {@code sub}). */
@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

  private final CartService cartService;

  public CartController(CartService cartService) {
    this.cartService = cartService;
  }

  @GetMapping
  public CartResponse getCart() {
    return cartService.getCart(CurrentUser.requireUserId());
  }

  @PostMapping("/items")
  public ResponseEntity<CartResponse> addItem(
      @Valid @RequestBody AddItemRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
    CartResponse cart =
        cartService.addItem(
            CurrentUser.requireUserId(), request.productId(), request.quantity(), authorization);
    return ResponseEntity.status(HttpStatus.CREATED).body(cart);
  }

  @PutMapping("/items/{productId}")
  public CartResponse setItemQuantity(
      @PathVariable long productId,
      @Valid @RequestBody SetQuantityRequest request,
      @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
    return cartService.setItemQuantity(
        CurrentUser.requireUserId(), productId, request.quantity(), authorization);
  }

  @DeleteMapping("/items/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeItem(@PathVariable long productId) {
    cartService.removeItem(CurrentUser.requireUserId(), productId);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void clearCart() {
    cartService.clearCart(CurrentUser.requireUserId());
  }
}
