package com.ecommerce.product.service;

import com.ecommerce.product.dto.InventoryAdjustmentRequest;
import com.ecommerce.product.dto.InventoryResponse;
import com.ecommerce.product.dto.PageResponse;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.dto.ProductResponse;
import com.ecommerce.product.exception.ConflictException;
import com.ecommerce.product.exception.NotFoundException;
import com.ecommerce.product.exception.UnprocessableEntityException;
import com.ecommerce.product.model.Category;
import com.ecommerce.product.model.Product;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductService {

  private final ProductRepository productRepository;
  private final CategoryRepository categoryRepository;

  public ProductService(
      ProductRepository productRepository, CategoryRepository categoryRepository) {
    this.productRepository = productRepository;
    this.categoryRepository = categoryRepository;
  }

  @Transactional(readOnly = true)
  public PageResponse<ProductResponse> list(String categorySlug, String q, Pageable pageable) {
    String slug = StringUtils.hasText(categorySlug) ? categorySlug : null;
    String qPattern = StringUtils.hasText(q) ? "%" + q.trim().toLowerCase() + "%" : null;
    Page<Product> page = productRepository.search(slug, qPattern, pageable);
    return PageResponse.of(page, page.map(ProductResponse::from).getContent());
  }

  @Transactional(readOnly = true)
  public ProductResponse get(Long id) {
    return ProductResponse.from(findActive(id));
  }

  @Transactional(readOnly = true)
  public InventoryResponse getInventory(Long id) {
    return InventoryResponse.from(findActive(id));
  }

  @Transactional
  public ProductResponse create(ProductRequest request) {
    if (productRepository.existsBySku(request.sku())) {
      throw new ConflictException("SKU_ALREADY_EXISTS", "A product with this SKU already exists");
    }
    Category category = requireCategory(request.categoryId());

    Product product = new Product();
    product.setSku(request.sku());
    applyFields(product, request, category);
    product.setStockQuantity(request.stockQuantity());
    return ProductResponse.from(productRepository.save(product));
  }

  @Transactional
  public ProductResponse update(Long id, ProductRequest request) {
    Product product = findActive(id);

    if (!product.getSku().equals(request.sku()) && productRepository.existsBySku(request.sku())) {
      throw new ConflictException("SKU_ALREADY_EXISTS", "A product with this SKU already exists");
    }
    Category category = requireCategory(request.categoryId());

    product.setSku(request.sku());
    applyFields(product, request, category);
    product.setStockQuantity(request.stockQuantity());
    return ProductResponse.from(productRepository.save(product));
  }

  @Transactional
  public void softDelete(Long id) {
    productRepository
        .findByIdAndDeletedAtIsNull(id)
        .ifPresent(
            product -> {
              product.setDeletedAt(Instant.now());
              productRepository.save(product);
            });
  }

  @Transactional
  public InventoryResponse adjustInventory(Long id, InventoryAdjustmentRequest request) {
    Product product = findActive(id);
    int result = product.getStockQuantity() + request.delta();
    if (result < 0) {
      throw new ConflictException(
          "INSUFFICIENT_STOCK", "Stock adjustment would result in a negative quantity");
    }
    product.setStockQuantity(result);
    return InventoryResponse.from(productRepository.save(product));
  }

  private Product findActive(Long id) {
    return productRepository
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
  }

  private Category requireCategory(Long categoryId) {
    return categoryRepository
        .findById(categoryId)
        .orElseThrow(
            () ->
                new UnprocessableEntityException("CATEGORY_NOT_FOUND", "Category does not exist"));
  }

  private void applyFields(Product product, ProductRequest request, Category category) {
    product.setName(request.name());
    product.setDescription(request.description());
    product.setPrice(request.price());
    product.setCurrency(request.currencyOrDefault());
    product.setCategory(category);
  }
}
