package com.ecommerce.cart.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "cart_items")
public class CartItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "cart_id", nullable = false)
  private Cart cart;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
  private BigDecimal unitPrice;

  @Column(nullable = false, length = 3, columnDefinition = "char(3)")
  @JdbcTypeCode(Types.CHAR)
  private String currency;

  @Column(name = "product_name", nullable = false, length = 200)
  private String productName;

  @Column(name = "snapshot_at", nullable = false)
  private Instant snapshotAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected CartItem() {}

  public CartItem(
      Long productId, int quantity, BigDecimal unitPrice, String currency, String productName) {
    this.productId = productId;
    this.quantity = quantity;
    applySnapshot(unitPrice, currency, productName);
  }

  /** Refreshes the price/name snapshot to the latest values seen from the Product Service. */
  public void applySnapshot(BigDecimal unitPrice, String currency, String productName) {
    this.unitPrice = unitPrice;
    this.currency = currency;
    this.productName = productName;
    this.snapshotAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public Cart getCart() {
    return cart;
  }

  void setCart(Cart cart) {
    this.cart = cart;
  }

  public Long getProductId() {
    return productId;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public String getCurrency() {
    return currency;
  }

  public String getProductName() {
    return productName;
  }

  public Instant getSnapshotAt() {
    return snapshotAt;
  }

  public BigDecimal lineTotal() {
    return unitPrice.multiply(BigDecimal.valueOf(quantity));
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
