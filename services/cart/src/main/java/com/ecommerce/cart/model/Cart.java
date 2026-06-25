package com.ecommerce.cart.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "carts")
public class Cart {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, unique = true)
  private Long userId;

  @OneToMany(
      mappedBy = "cart",
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      fetch = jakarta.persistence.FetchType.LAZY)
  @OrderBy("id ASC")
  private List<CartItem> items = new ArrayList<>();

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Cart() {}

  public Cart(Long userId) {
    this.userId = userId;
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public List<CartItem> getItems() {
    return items;
  }

  public Optional<CartItem> findItem(Long productId) {
    return items.stream().filter(item -> item.getProductId().equals(productId)).findFirst();
  }

  public void addItem(CartItem item) {
    item.setCart(this);
    items.add(item);
  }

  public void removeItem(CartItem item) {
    items.remove(item);
    item.setCart(null);
  }

  public void clearItems() {
    items.forEach(item -> item.setCart(null));
    items.clear();
  }

  /** The single cart currency, or empty when the cart holds no items. */
  public Optional<String> currency() {
    return items.stream().map(CartItem::getCurrency).findFirst();
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
