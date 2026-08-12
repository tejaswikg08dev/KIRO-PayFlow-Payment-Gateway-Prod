package com.payflow.payment.service;

import com.payflow.common.constant.OrderStatus;
import com.payflow.common.constant.PaymentMethod;
import com.payflow.common.constant.PaymentStatus;
import com.payflow.common.event.PaymentEvent;
import com.payflow.common.exception.PayflowException;
import com.payflow.common.exception.PaymentDeclinedException;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.common.util.IdGenerator;
import com.payflow.payment.dto.AuthorizePaymentRequest;
import com.payflow.payment.dto.CapturePaymentRequest;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.feign.RoutingServiceClient;
import com.payflow.payment.mapper.PaymentMapper;
import com.payflow.payment.model.Order;
import com.payflow.payment.model.Payment;
import com.payflow.payment.model.PaymentMethodEntity;
import com.payflow.payment.repository.PaymentMethodRepository;
import com.payflow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Core payment processing service.
 * Handles authorization, capture, and void operations with proper state machine transitions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final OrderService orderService;
    private final RoutingServiceClient routingServiceClient;
    private final EventPublisher eventPublisher;
    private final PaymentMapper paymentMapper;

    /**
     * Authorizes a payment against an order.
     * Calls the routing-service to route the transaction to the appropriate bank.
     */
    @Transactional
    public PaymentResponse authorize(AuthorizePaymentRequest request) {
        log.info("Authorizing payment for order: {}", request.getOrderId());

        // 1. Validate the order
        Order order = orderService.getOrderEntity(request.getOrderId());
        validateOrderForPayment(order);

        // 2. Check for existing payment on this order
        paymentRepository.findByOrderId(order.getId()).ifPresent(existing -> {
            if (existing.getStatus() != PaymentStatus.FAILED) {
                throw new PayflowException("PAYMENT_EXISTS",
                        "Order already has an active payment: " + existing.getId());
            }
        });

        // 3. Create payment record
        PaymentMethod method = PaymentMethod.valueOf(request.getPaymentMethod().toUpperCase());
        Payment payment = Payment.builder()
                .id(IdGenerator.generatePaymentId())
                .orderId(order.getId())
                .merchantId(order.getMerchantId())
                .amount(order.getAmount())
                .currency(order.getCurrency())
                .status(PaymentStatus.CREATED)
                .paymentMethod(method)
                .build();

        payment = paymentRepository.save(payment);

        // 4. Save payment method details
        savePaymentMethodDetails(payment.getId(), request);

        // 5. Update order status to ATTEMPTED
        orderService.updateOrderStatus(order.getId(), OrderStatus.ATTEMPTED);

        // 6. Route to bank via routing-service
        try {
            Map<String, Object> routeRequest = Map.of(
                    "paymentId", payment.getId(),
                    "merchantId", order.getMerchantId(),
                    "amount", order.getAmount(),
                    "currency", order.getCurrency(),
                    "paymentMethod", method.name()
            );

            Map<String, Object> routeResponse = routingServiceClient.routePayment(routeRequest);

            // 7. Process bank response
            String bankStatus = (String) routeResponse.getOrDefault("status", "FAILED");
            
            if ("AUTHORIZED".equalsIgnoreCase(bankStatus)) {
                payment.setStatus(PaymentStatus.AUTHORIZED);
                payment.setAuthorizationCode((String) routeResponse.get("authorizationCode"));
                payment.setBankReferenceId((String) routeResponse.get("bankReferenceId"));
                log.info("Payment authorized: {}", payment.getId());

                // Publish authorization event
                publishEvent("payment.authorized", payment);
            } else {
                String reason = (String) routeResponse.getOrDefault("reason", "Bank declined");
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(reason);
                log.warn("Payment declined: {} - {}", payment.getId(), reason);

                // Publish failure event
                publishEvent("payment.failed", payment);

                throw new PaymentDeclinedException(
                        (String) routeResponse.getOrDefault("declineCode", "DECLINED"),
                        reason
                );
            }
        } catch (PaymentDeclinedException e) {
            paymentRepository.save(payment);
            throw e;
        } catch (Exception e) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Routing service error: " + e.getMessage());
            paymentRepository.save(payment);
            publishEvent("payment.failed", payment);
            log.error("Payment routing failed for: {}", payment.getId(), e);
            throw new PayflowException("ROUTING_ERROR",
                    "Failed to process payment: " + e.getMessage());
        }

        payment = paymentRepository.save(payment);
        return paymentMapper.toResponse(payment);
    }

    /**
     * Captures an authorized payment (full or partial).
     */
    @Transactional
    public PaymentResponse capture(CapturePaymentRequest request) {
        log.info("Capturing payment: {}", request.getPaymentId());

        Payment payment = paymentRepository.findById(request.getPaymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", request.getPaymentId()));

        // Validate state transition
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new PayflowException("INVALID_STATE",
                    String.format("Cannot capture payment in status: %s. Must be AUTHORIZED.", 
                            payment.getStatus()));
        }

        // Validate capture amount
        BigDecimal captureAmount = request.getAmount() != null 
                ? request.getAmount() 
                : payment.getAmount();

        if (captureAmount.compareTo(payment.getAmount()) > 0) {
            throw new PayflowException("INVALID_AMOUNT",
                    "Capture amount cannot exceed authorized amount");
        }

        // Update payment
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setAmount(captureAmount);
        payment = paymentRepository.save(payment);

        // Update order to PAID
        orderService.updateOrderStatus(payment.getOrderId(), OrderStatus.PAID);

        // Publish capture event
        publishEvent("payment.captured", payment);

        log.info("Payment captured: {} for amount: {}", payment.getId(), captureAmount);
        return paymentMapper.toResponse(payment);
    }

    /**
     * Voids an authorized payment (cancels authorization before capture).
     */
    @Transactional
    public PaymentResponse voidPayment(String paymentId) {
        log.info("Voiding payment: {}", paymentId);

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        // Validate state transition
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            throw new PayflowException("INVALID_STATE",
                    String.format("Cannot void payment in status: %s. Must be AUTHORIZED.", 
                            payment.getStatus()));
        }

        payment.setStatus(PaymentStatus.VOIDED);
        payment = paymentRepository.save(payment);

        log.info("Payment voided: {}", payment.getId());
        return paymentMapper.toResponse(payment);
    }

    /**
     * Retrieves a payment by ID.
     */
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
        return paymentMapper.toResponse(payment);
    }

    // --- Private helpers ---

    private void validateOrderForPayment(Order order) {
        if (order.getStatus() == OrderStatus.EXPIRED) {
            throw new PayflowException("ORDER_EXPIRED",
                    "Order has expired: " + order.getId());
        }
        if (order.getStatus() == OrderStatus.PAID) {
            throw new PayflowException("ORDER_ALREADY_PAID",
                    "Order is already paid: " + order.getId());
        }
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(Instant.now())) {
            orderService.updateOrderStatus(order.getId(), OrderStatus.EXPIRED);
            throw new PayflowException("ORDER_EXPIRED",
                    "Order has expired: " + order.getId());
        }
    }

    private void savePaymentMethodDetails(String paymentId, AuthorizePaymentRequest request) {
        PaymentMethodEntity.PaymentMethodEntityBuilder builder = PaymentMethodEntity.builder()
                .paymentId(paymentId)
                .type(request.getPaymentMethod().toUpperCase());

        switch (request.getPaymentMethod().toUpperCase()) {
            case "CARD" -> {
                if (request.getCardNumber() != null && request.getCardNumber().length() >= 4) {
                    builder.cardLast4(request.getCardNumber()
                            .substring(request.getCardNumber().length() - 4));
                }
                builder.cardBrand(detectCardBrand(request.getCardNumber()));
                builder.cardExpiryMonth(request.getCardExpiryMonth());
                builder.cardExpiryYear(request.getCardExpiryYear());
            }
            case "UPI" -> builder.upiId(request.getUpiId());
            case "NET_BANKING" -> {
                builder.bankCode(request.getBankCode());
                builder.bankName(request.getBankName());
            }
        }

        paymentMethodRepository.save(builder.build());
    }

    private String detectCardBrand(String cardNumber) {
        if (cardNumber == null || cardNumber.isEmpty()) return "UNKNOWN";
        if (cardNumber.startsWith("4")) return "VISA";
        if (cardNumber.startsWith("5")) return "MASTERCARD";
        if (cardNumber.startsWith("6")) return "RUPAY";
        if (cardNumber.startsWith("3")) return "AMEX";
        return "UNKNOWN";
    }

    private void publishEvent(String topic, Payment payment) {
        try {
            PaymentEvent event = PaymentEvent.builder()
                    .eventId(IdGenerator.generateEventId())
                    .eventType(topic)
                    .paymentId(payment.getId())
                    .orderId(payment.getOrderId())
                    .merchantId(payment.getMerchantId())
                    .amount(payment.getAmount())
                    .currency(payment.getCurrency())
                    .status(payment.getStatus().name())
                    .paymentMethod(payment.getPaymentMethod().name())
                    .timestamp(Instant.now())
                    .build();

            eventPublisher.publishPaymentEvent(topic, event);
        } catch (Exception e) {
            // Event publishing should not fail the payment transaction
            log.error("Failed to publish event for payment: {}", payment.getId(), e);
        }
    }
}
