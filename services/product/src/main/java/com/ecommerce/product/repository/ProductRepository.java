package com.ecommerce.product.repository;

import com.ecommerce.product.model.Product;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

  boolean existsBySku(String sku);

  @EntityGraph(attributePaths = "category")
  Optional<Product> findByIdAndDeletedAtIsNull(Long id);

  /**
   * Loads an active product with a {@code PESSIMISTIC_WRITE} row lock ({@code SELECT … FOR UPDATE}).
   * Concurrent reservations on the same product serialize here, which is what prevents oversell —
   * every reserver reads {@code reserved_quantity} only after the previous one has committed its
   * increment and released the lock. No entity graph: reserve needs the {@code products} row's own
   * columns (price/currency/name/stock/reserved), not the category.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Product p where p.id = :id and p.deletedAt is null")
  Optional<Product> findByIdForUpdate(@Param("id") Long id);

  /**
   * Paginated catalog listing. Soft-deleted rows are excluded. {@code categorySlug} and {@code
   * qPattern} are optional (null = no filter). {@code qPattern} must already be lowercased and
   * wrapped in {@code %...%} by the caller; column sides are lowercased here so the match is
   * case-insensitive. (Building the pattern in Java avoids Postgres inferring a {@code bytea} type
   * for {@code lower(concat(...))} on a possibly-null bound parameter.) The category is fetched
   * eagerly via the entity graph to avoid N+1 on the response mapping.
   */
  @EntityGraph(attributePaths = "category")
  @Query(
      """
      select p from Product p
      where p.deletedAt is null
        and (:categorySlug is null or p.category.slug = :categorySlug)
        and (:qPattern is null
             or lower(p.name) like :qPattern
             or lower(p.description) like :qPattern)
      """)
  Page<Product> search(
      @Param("categorySlug") String categorySlug,
      @Param("qPattern") String qPattern,
      Pageable pageable);
}
