package com.ecommerce.product.repository;

import com.ecommerce.product.model.Category;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

  boolean existsBySlug(String slug);

  Optional<Category> findBySlug(String slug);
}
