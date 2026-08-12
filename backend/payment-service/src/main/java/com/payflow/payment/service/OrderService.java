package com.payflow.payment.service;

import com.payflow.common.constant.OrderStatus;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.common.util.IdGenerator;
import com.payflow.payment.dto.CreateOrderRequest;
import com.payflow.payment.dto.OrderResponse;
import com.payflow.payment.mapper.OrderMapper;
import com.payflow.payment.model.Order;
import com.payflow.payment.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing payment orders.
 * Handles order creation, retrieval, listing, and expiration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    /**
     * Creates a new payment order.
     * Orders expire after 30 minutes if no payment is attempted.
     */
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        log.info("Creating order for merchant: {}, amount: {} {}", 
                request.getMerchantId(), request.getAmount(), request.getCurrency());

        Order order = Order.builder()
                .id(IdGenerator.generateOrderId())
                .merchantId(request.getMerchantId())
                .amount(request.getAmount())
                .currency(request.getCurrency().toUpperCase())
                .status(OrderStatus.CREATED)
                .customerEmail(request.getCustomerEmail())
                .description(request.getDescription())
                .receiptNumber(request.getReceiptNumber())
                .expiresAt(Instant.now().plus(30, ChronoUnit.MINUTES))
                .build();

        Order saved = orderRepository.save(order);
        log.info("Order created: {}", saved.getId());
        return orderMapper.toResponse(saved);
    }

    /**
     * Retrieves an order by ID.
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return orderMapper.toResponse(order);
    }

    /**
     * Lists all orders for a merchant.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> listByMerchant(String merchantId) {
        return orderRepository.findByMerchantId(merchantId).stream()
                .map(orderMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Expires orders that have passed their expiration time.
     * Called by a scheduled job or on-demand.
     */
    @Transactional
    public int expireOrders() {
        List<Order> createdOrders = orderRepository.findByStatus(OrderStatus.CREATED);
        int expired = 0;

        for (Order order : createdOrders) {
            if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(Instant.now())) {
                order.setStatus(OrderStatus.EXPIRED);
                orderRepository.save(order);
                expired++;
                log.info("Order expired: {}", order.getId());
            }
        }

        log.info("Expired {} orders", expired);
        return expired;
    }

    /**
     * Returns the Order entity (used internally by PaymentService).
     */
    @Transactional(readOnly = true)
    public Order getOrderEntity(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    /**
     * Updates order status (used internally after payment state changes).
     */
    @Transactional
    public void updateOrderStatus(String orderId, OrderStatus status) {
        Order order = getOrderEntity(orderId);
        order.setStatus(status);
        orderRepository.save(order);
        log.info("Order {} status updated to {}", orderId, status);
    }
}
