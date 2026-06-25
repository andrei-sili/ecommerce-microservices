package com.ecommerce.product.service;

import com.ecommerce.product.dto.CategoryRequest;
import com.ecommerce.product.dto.CategoryResponse;
import com.ecommerce.product.exception.ConflictException;
import com.ecommerce.product.model.Category;
import com.ecommerce.product.repository.CategoryRepository;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {

  private final CategoryRepository categoryRepository;

  public CategoryService(CategoryRepository categoryRepository) {
    this.categoryRepository = categoryRepository;
  }

  @Transactional(readOnly = true)
  public List<CategoryResponse> list() {
    return categoryRepository.findAll(Sort.by("name")).stream()
        .map(CategoryResponse::from)
        .toList();
  }

  @Transactional
  public CategoryResponse create(CategoryRequest request) {
    if (categoryRepository.existsBySlug(request.slug())) {
      throw new ConflictException(
          "SLUG_ALREADY_EXISTS", "A category with this slug already exists");
    }
    Category saved = categoryRepository.save(new Category(request.name(), request.slug()));
    return CategoryResponse.from(saved);
  }
}
