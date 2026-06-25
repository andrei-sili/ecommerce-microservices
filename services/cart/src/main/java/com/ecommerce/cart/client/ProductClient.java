package com.ecommerce.cart.client;

/**
 * Validates a product against the Product Service and captures a price/name snapshot. The caller's
 * bearer token is forwarded (user-scoped call per the Wave 2 contract); Product reads are public so
 * an anonymous call would also work, but forwarding keeps the trust model consistent.
 */
public interface ProductClient {

  /**
   * @throws com.ecommerce.cart.exception.NotFoundException ({@code PRODUCT_NOT_FOUND}) if the
   *     product is missing or soft-deleted
   * @throws com.ecommerce.cart.exception.ConflictException ({@code PRODUCT_UNAVAILABLE}) if the
   *     product exists but is not available for purchase
   * @throws com.ecommerce.cart.exception.ServiceUnavailableException if Product is unreachable
   */
  ProductSnapshot fetchAvailableProduct(long productId, String bearerToken);
}
