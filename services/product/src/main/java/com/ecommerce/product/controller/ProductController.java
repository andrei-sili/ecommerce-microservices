package com.ecommerce.product.controller;

import com.ecommerce.product.dto.InventoryAdjustmentRequest;
import com.ecommerce.product.dto.InventoryResponse;
import com.ecommerce.product.dto.PageResponse;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.dto.ProductResponse;
import com.ecommerce.product.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@Validated
public class ProductController {

  private static final int MAX_PAGE_SIZE = 100;

  private final ProductService productService;

  public ProductController(ProductService productService) {
    this.productService = productService;
  }

  @GetMapping
  public PageResponse<ProductResponse> list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String q) {
    Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"));
    return productService.list(category, q, pageable);
  }

  @GetMapping("/{id}")
  public ProductResponse get(@PathVariable Long id) {
    return productService.get(id);
  }

  @PostMapping
  public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
    ProductResponse created = productService.create(request);
    return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
  }

  @PutMapping("/{id}")
  public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
    return productService.update(id, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    productService.softDelete(id);
  }

  @GetMapping("/{id}/inventory")
  public InventoryResponse getInventory(@PathVariable Long id) {
    return productService.getInventory(id);
  }

  @PatchMapping("/{id}/inventory")
  public InventoryResponse adjustInventory(
      @PathVariable Long id, @Valid @RequestBody InventoryAdjustmentRequest request) {
    return productService.adjustInventory(id, request);
  }
}
