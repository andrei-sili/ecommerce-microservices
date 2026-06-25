package com.ecommerce.cart.support;

import com.ecommerce.cart.client.ProductClient;
import com.ecommerce.cart.client.ProductSnapshot;
import com.ecommerce.cart.exception.ConflictException;
import com.ecommerce.cart.exception.NotFoundException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ProductClient} for integration tests — no real HTTP. Tests register products
 * with a price/currency/availability so the full service + DB stack can be exercised end to end.
 */
public class StubProductClient implements ProductClient {

  public record StubProduct(
      String name, BigDecimal unitPrice, String currency, boolean available) {}

  private final Map<Long, StubProduct> products = new ConcurrentHashMap<>();

  public void register(
      long productId, String name, String unitPrice, String currency, boolean available) {
    products.put(productId, new StubProduct(name, new BigDecimal(unitPrice), currency, available));
  }

  public void reset() {
    products.clear();
  }

  @Override
  public ProductSnapshot fetchAvailableProduct(long productId, String bearerToken) {
    StubProduct product = products.get(productId);
    if (product == null) {
      throw new NotFoundException("PRODUCT_NOT_FOUND", "Product not found");
    }
    if (!product.available()) {
      throw new ConflictException("PRODUCT_UNAVAILABLE", "Product is not available for purchase");
    }
    return new ProductSnapshot(
        productId, product.name(), product.unitPrice(), product.currency());
  }
}
