package com.ecommerce.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Standard error body returned by Product; parsed to relay the machine code and product id. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstreamError(String error, String message, Long productId) {}
