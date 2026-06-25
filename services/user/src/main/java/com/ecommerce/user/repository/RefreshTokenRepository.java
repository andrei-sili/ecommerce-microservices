package com.ecommerce.user.repository;

import com.ecommerce.user.model.RefreshToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @Modifying
  @Query(
      "UPDATE RefreshToken t SET t.revoked = true WHERE t.userId = :userId AND t.revoked = false")
  int revokeAllForUser(@Param("userId") Long userId);
}
