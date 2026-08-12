package com.payflow.payment.repository;

import com.payflow.payment.model.PaymentMethodEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethodEntity, Long> {

    Optional<PaymentMethodEntity> findByPaymentId(String paymentId);
}
