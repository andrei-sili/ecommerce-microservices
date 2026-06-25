package com.ecommerce.product.repository;

import com.ecommerce.product.model.Product;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

  boolean existsBySku(String sku);

  @EntityGraph(attributePaths = "category")
  Optional<Product> findByIdAndDeletedAtIsNull(Long id);

  /**
   * Paginated catalog listing. Soft-deleted rows are excluded. {@code categorySlug} and {@code q}
   * are optional (null = no filter); {@code q} matches name or description case-insensitively. The
   * category is fetched eagerly via the entity graph to avoid N+1 on the response mapping.
   */
  @EntityGraph(attributePaths = "category")
  @Query(
      """
      select p from Product p
      where p.deletedAt is null
        and (:categorySlug is null or p.category.slug = :categorySlug)
        and (:q is null
             or lower(p.name) like lower(concat('%', :q, '%'))
             or lower(p.description) like lower(concat('%', :q, '%')))
      """)
  Page<Product> search(
      @Param("categorySlug") String categorySlug, @Param("q") String q, Pageable pageable);
}
