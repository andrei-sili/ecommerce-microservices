package com.ecommerce.cart.repository;

import com.ecommerce.cart.model.Cart;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<Cart, Long> {

  @EntityGraph(attributePaths = "items")
  Optional<Cart> findByUserId(Long userId);
}
