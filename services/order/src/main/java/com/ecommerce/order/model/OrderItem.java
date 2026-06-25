package com.ecommerce.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Immutable line snapshot; built from the reservation response's authoritative prices. */
@Entity
@Table(name = "order_items")
public class OrderItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private OrderEntity order;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "product_name", nullable = false, length = 200)
  private String productName;

  @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
  private BigDecimal unitPrice;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
  private BigDecimal lineTotal;

  protected OrderItem() {}

  public OrderItem(
      Long productId,
      String productName,
      BigDecimal unitPrice,
      int quantity,
      BigDecimal lineTotal) {
    this.productId = productId;
    this.productName = productName;
    this.unitPrice = unitPrice;
    this.quantity = quantity;
    this.lineTotal = lineTotal;
  }

  public Long getId() {
    return id;
  }

  public OrderEntity getOrder() {
    return order;
  }

  void setOrder(OrderEntity order) {
    this.order = order;
  }

  public Long getProductId() {
    return productId;
  }

  public String getProductName() {
    return productName;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getLineTotal() {
    return lineTotal;
  }
}
