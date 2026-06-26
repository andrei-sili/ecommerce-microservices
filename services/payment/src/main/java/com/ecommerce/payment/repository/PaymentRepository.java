package com.ecommerce.payment.repository;

import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  Optional<Payment> findByIdempotencyKey(String idempotencyKey);

  boolean existsByOrderIdAndStatus(UUID orderId, PaymentStatus status);

  Optional<Payment> findByIdAndUserId(UUID id, Long userId);

  Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId);
}
