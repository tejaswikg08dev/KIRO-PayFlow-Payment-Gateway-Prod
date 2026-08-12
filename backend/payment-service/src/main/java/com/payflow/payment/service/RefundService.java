package com.payflow.payment.service;

import com.payflow.common.constant.PaymentStatus;
import com.payflow.common.event.PaymentEvent;
import com.payflow.common.exception.PayflowException;
import com.payflow.common.exception.ResourceNotFoundException;
import com.payflow.common.util.IdGenerator;
import com.payflow.payment.dto.RefundRequest;
import com.payflow.payment.dto.RefundResponse;
import com.payflow.payment.model.Payment;
import com.payflow.payment.model.Refund;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for processing refunds (full and partial).
 * Only captured payments can be refunded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final EventPublisher eventPublisher;

    /**
     * Creates a refund for a captured payment.
     * Validates that refund amount does not exceed remaining refundable amount.
     */
    @Transactional
    public RefundResponse createRefund(RefundRequest request) {
        log.info("Creating refund for payment: {}, amount: {}", 
                request.getPaymentId(), request.getAmount());

        // 1. Validate payment exists and is captured
        Payment payment = paymentRepository.findById(request.getPaymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", request.getPaymentId()));

        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new PayflowException("INVALID_STATE",
                    String.format("Cannot refund payment in status: %s. Must be CAPTURED.", 
                            payment.getStatus()));
        }

        // 2. Calculate total already refunded
        List<Refund> existingRefunds = refundRepository.findByPaymentId(payment.getId());
        BigDecimal totalRefunded = existingRefunds.stream()
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remainingRefundable = payment.getAmount().subtract(totalRefunded);

        // 3. Validate refund amount
        if (request.getAmount().compareTo(remainingRefundable) > 0) {
            throw new PayflowException("REFUND_EXCEEDS_LIMIT",
                    String.format("Refund amount %.4f exceeds remaining refundable amount %.4f",
                            request.getAmount(), remainingRefundable));
        }

        // 4. Create the refund
        Refund refund = Refund.builder()
                .id(IdGenerator.generateRefundId())
                .paymentId(payment.getId())
                .merchantId(payment.getMerchantId())
                .amount(request.getAmount())
                .reason(request.getReason())
                .status("PROCESSED")
                .build();

        refund = refundRepository.save(refund);

        // 5. If fully refunded, update payment status
        BigDecimal newTotalRefunded = totalRefunded.add(request.getAmount());
        if (newTotalRefunded.compareTo(payment.getAmount()) >= 0) {
            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);
            log.info("Payment fully refunded: {}", payment.getId());
        }

        // 6. Publish refund event
        publishRefundEvent(payment, refund);

        log.info("Refund created: {} for payment: {}", refund.getId(), payment.getId());
        return toRefundResponse(refund);
    }

    /**
     * Retrieves a refund by ID.
     */
    @Transactional(readOnly = true)
    public RefundResponse getRefund(String refundId) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund", refundId));
        return toRefundResponse(refund);
    }

    /**
     * Lists all refunds for a payment.
     */
    @Transactional(readOnly = true)
    public List<RefundResponse> listByPayment(String paymentId) {
        return refundRepository.findByPaymentId(paymentId).stream()
                .map(this::toRefundResponse)
                .collect(Collectors.toList());
    }

    // --- Private helpers ---

    private RefundResponse toRefundResponse(Refund refund) {
        return RefundResponse.builder()
                .id(refund.getId())
                .paymentId(refund.getPaymentId())
                .merchantId(refund.getMerchantId())
                .amount(refund.getAmount())
                .reason(refund.getReason())
                .status(refund.getStatus())
                .createdAt(refund.getCreatedAt())
                .build();
    }

    private void publishRefundEvent(Payment payment, Refund refund) {
        try {
            PaymentEvent event = PaymentEvent.builder()
                    .eventId(IdGenerator.generateEventId())
                    .eventType("payment.refunded")
                    .paymentId(payment.getId())
                    .orderId(payment.getOrderId())
                    .merchantId(payment.getMerchantId())
                    .amount(refund.getAmount())
                    .currency(payment.getCurrency())
                    .status("REFUNDED")
                    .paymentMethod(payment.getPaymentMethod().name())
                    .timestamp(Instant.now())
                    .metadata(refund.getId())
                    .build();

            eventPublisher.publishPaymentEvent("payment.refunded", event);
        } catch (Exception e) {
            log.error("Failed to publish refund event for refund: {}", refund.getId(), e);
        }
    }
}
