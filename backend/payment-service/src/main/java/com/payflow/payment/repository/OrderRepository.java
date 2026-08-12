package com.payflow.payment.repository;

import com.payflow.common.constant.OrderStatus;
import com.payflow.payment.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {

    List<Order> findByMerchantId(String merchantId);

    List<Order> findByStatus(OrderStatus status);

    List<Order> findByMerchantIdAndStatus(String merchantId, OrderStatus status);
}
