package com.payflow.payment.repository;

import com.payflow.common.constant.PaymentStatus;
import com.payflow.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByOrderId(String orderId);

    List<Payment> findByMerchantIdAndStatus(String merchantId, PaymentStatus status);

    List<Payment> findByMerchantId(String merchantId);
}
